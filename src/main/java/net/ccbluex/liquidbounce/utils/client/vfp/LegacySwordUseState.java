/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.utils.client.vfp;

import java.util.function.BooleanSupplier;

/** Main-thread, one manual press/session. No timers, retransmission loops or inventory mutations. */
public final class LegacySwordUseState {
    private Object connection, world, player, stack;
    private int slot;
    private boolean active;

    public boolean matches(Object connection, Object world, Object player, Object stack, int slot) {
        return active && this.connection == connection && this.world == world && this.player == player
            && this.stack == stack && this.slot == slot;
    }

    public boolean begin(Object connection, Object world, Object player, Object stack, int slot,
                         BooleanSupplier sendUse) {
        if (matches(connection, world, player, stack, slot)) return true;
        if (active || connection == null || world == null || player == null || stack == null) return false;
        // Never display a block for a failed/absent packet send.
        if (!sendUse.getAsBoolean()) return false;
        this.connection = connection; this.world = world; this.player = player; this.stack = stack;
        this.slot = slot; active = true;
        return true;
    }

    public void update(Object connection, Object world, Object player, Object stack, int slot,
                       boolean eligible, BooleanSupplier sendRelease) {
        if (!active || eligible && matches(connection, world, player, stack, slot)) return;
        // Forget FIRST even if transport throws. Never release a previous world's block in a new one.
        boolean sameSession = this.connection == connection && this.world == world && this.player == player;
        active = false;
        this.connection = this.world = this.player = this.stack = null;
        if (sameSession) sendRelease.getAsBoolean();
    }
}
