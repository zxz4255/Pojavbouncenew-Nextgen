/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.features.module.modules.combat.moonlight;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MoonlightPacketQueueTest {
    @Test
    void partialReleaseStopsAtFirstMarkerAndCountsQueuedMovement() {
        var queue = new MoonlightPacketQueue<String>();
        queue.addMovement("move-1");
        queue.add("swing-1");
        queue.postMotion();
        queue.addMovement("move-2");
        queue.postMotion();
        queue.addMovement("move-3");
        queue.postMotion();

        assertEquals(3, queue.movingPackets());
        assertEquals(List.of("move-1", "swing-1"), queue.drainOneTick());
        assertEquals(2, queue.movingPackets());
        assertEquals(List.of("move-2"), queue.drainOneTick());
        assertEquals(1, queue.movingPackets());
        assertEquals(List.of("move-3"), queue.drainOneTick());
        assertEquals(0, queue.movingPackets());
        assertEquals(List.of(), queue.drainOneTick());
    }

    @Test
    void correctionOrKnockbackWithExtraAndMissingMovementCannotCorruptProgress() {
        var queue = new MoonlightPacketQueue<String>();
        queue.addMovement("move-before-hit");
        queue.addMovement("move-during-hit");
        queue.postMotion();
        queue.add("attack");
        queue.postMotion(); // no movement in this tick
        queue.addMovement("move-after-hit");
        queue.postMotion();
        assertEquals(3, queue.movingPackets());
        assertEquals(List.of("move-before-hit", "move-during-hit"), queue.drainOneTick());
        assertEquals(1, queue.movingPackets());
        assertEquals(List.of("attack"), queue.drainOneTick());
        assertEquals(1, queue.movingPackets());
        assertEquals(List.of("move-after-hit"), queue.drainOneTick());
        assertEquals(0, queue.movingPackets());
        assertEquals(List.of(), queue.drainOneTick());
        assertEquals(0, queue.movingPackets());
    }

    @Test
    void fullReleaseDropsUseAndDigButKeepsOtherPacketsInOrder() {
        var queue = new MoonlightPacketQueue<String>();
        queue.add("use");
        queue.addMovement("move-1");
        queue.postMotion();
        queue.add("dig");
        queue.add("attack");
        queue.postMotion();
        queue.addMovement("move-2");
        assertEquals(List.of("move-1", "attack", "move-2"),
            queue.drainAll(s -> s.equals("use") || s.equals("dig")));
        assertEquals(0, queue.movingPackets());
        assertEquals(List.of(), queue.drainAll(s -> false));
    }

    @Test
    void threeTickDripsStillReachThe32MoveBurst() {
        var queue = new MoonlightPacketQueue<String>();
        int tick = 0;
        while (queue.movingPackets() < 32 && tick < 100) {
            tick++;
            if (tick % 3 == 0) queue.drainOneTick();
            queue.addMovement("move-" + tick);
            queue.postMotion();
        }
        assertEquals(32, queue.movingPackets());
        assertTrue(tick > 32 && tick < 100);
        assertEquals(32, queue.drainAll(s -> false).size());
        assertEquals(0, queue.movingPackets());
    }

    @Test
    void auraActionsShareTickOrderingAndOnlySwordUseIsDiscardedOnFullRelease() {
        var queue = new MoonlightPacketQueue<Object>();
        var interact = new MoonlightBufferedActions.Interact(42);
        var swordUse = new MoonlightBufferedActions.SwordUse(null);
        queue.addMovement("move-1");
        queue.add(interact);
        queue.add(swordUse);
        queue.postMotion();
        assertEquals(List.of("move-1", interact, swordUse), queue.drainOneTick());
        assertEquals(0, queue.movingPackets());

        queue.addMovement("move-2");
        queue.add(interact);
        queue.add(swordUse);
        queue.postMotion();
        assertEquals(List.of("move-2", interact), queue.drainAll(MoonlightBufferedActions::discardOnFullRelease));
        assertEquals(0, queue.movingPackets());
    }

    @Test
    void resetClearsPacketsAndMovementCount() {
        var queue = new MoonlightPacketQueue<String>();
        queue.addMovement("move");
        queue.postMotion();
        queue.reset();
        assertEquals(0, queue.movingPackets());
        assertEquals(List.of(), queue.drainOneTick());
    }
}
