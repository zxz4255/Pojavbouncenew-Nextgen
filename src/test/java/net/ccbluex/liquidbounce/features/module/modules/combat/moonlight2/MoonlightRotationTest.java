/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.features.module.modules.combat.moonlight2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure maths checks for the aura rotation helper. These run on the desktop JVM, so they cover only
 * the arithmetic the aura relies on, not a live server.
 */
class MoonlightRotationTest {

    @Test
    void deltaWrapsAcrossTheYawSeam() {
        assertEquals(20.0f, MoonlightRotation.INSTANCE.delta(170.0f, -170.0f), 0.0001f);
        assertEquals(-20.0f, MoonlightRotation.INSTANCE.delta(-170.0f, 170.0f), 0.0001f);
        assertEquals(0.0f, MoonlightRotation.INSTANCE.delta(0.0f, 360.0f), 0.0001f);
    }

    @Test
    void stepNeverOvershoots() {
        for (RotationMode mode : RotationMode.values()) {
            float next = MoonlightRotation.INSTANCE.step(0.0f, 10.0f, 90.0f, mode, 1, 0.2f);
            assertTrue(next >= 0.0f && next <= 10.0f, mode + " overshot: " + next);
        }
    }

    @Test
    void wrapAndClampStayInRange() {
        assertEquals(-170.0f, MoonlightRotation.INSTANCE.wrapYaw(190.0f), 0.0001f);
        assertEquals(90.0f, MoonlightRotation.INSTANCE.clampPitch(120.0f), 0.0001f);
        assertEquals(-90.0f, MoonlightRotation.INSTANCE.clampPitch(-120.0f), 0.0001f);
    }

    @Test
    void stepReachesTargetWhenBudgetIsEnough() {
        assertEquals(45.0f, MoonlightRotation.INSTANCE.step(0.0f, 45.0f, 180.0f, RotationMode.LINEAR, 1, 0f), 0.0001f);
    }

    @Test
    void stepIsMonotonicTowardsTarget() {
        float current = 0.0f;
        for (int i = 0; i < 20; i++) {
            float next = MoonlightRotation.INSTANCE.step(current, 90.0f, 12.0f, RotationMode.CUBIC, 8, 0f);
            assertTrue(next >= current, "rotation went backwards");
            current = next;
        }
        assertTrue(current <= 90.0f, "rotation overshot the target");
    }
}
