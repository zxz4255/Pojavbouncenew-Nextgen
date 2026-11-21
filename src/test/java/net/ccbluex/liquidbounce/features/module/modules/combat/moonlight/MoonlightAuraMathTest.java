/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.features.module.modules.combat.moonlight;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MoonlightAuraMathTest {
    private static final float[] P = {0f, .05f, .2f, .4f, .6f, .8f, .95f, .1f};
    @Test void slerpUsesWrappedShortestDelta() {
        assertEquals(180f, MoonlightAuraMath.smooth(170f, -170f, "Slerp", 90f, P, .3f, .5f), 0.0001f);
        assertEquals(-180f, MoonlightAuraMath.wrap(180f), 0.0001f);
    }
    @Test void allDeclaredModesProduceFiniteValues() {
        for (String mode : new String[]{"Slerp", "Adaptive Bezier", "Adaptive Slerp", "Sinusoidal", "Spring",
                "Cosine Interpolation", "Logarithmic Interpolation", "Elastic Spring", "Bezier"}) {
            float value = MoonlightAuraMath.smooth(20f, 80f, mode, 90f, P, .3f, .5f);
            assertTrue(Float.isFinite(value), mode);
        }
        // "Spring" is listed in MoonLight, but has no matching smooth() case there.
        assertEquals(80f, MoonlightAuraMath.smooth(20f, 80f, "Spring", 90f, P, .3f, .5f));
    }
    @Test void pointArrayMustHaveEightElements() {
        assertThrows(IllegalArgumentException.class, () -> MoonlightAuraMath.smooth(0f, 1f, "Bezier", 90f,
            new float[7], .3f, .5f));
    }
}
