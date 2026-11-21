/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.features.module.modules.combat.moonlight;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MoonlightApplePlannerTest {
    @Test void prefersAnEmptyHotbarSlot() {
        boolean[] empty = new boolean[9];
        empty[2] = true;
        empty[7] = true;
        assertEquals(2, MoonlightApplePlanner.chooseHotbar(empty, 1));
    }
    @Test void fullHotbarPreservesTheSelectedSlot() {
        assertEquals(8, MoonlightApplePlanner.chooseHotbar(new boolean[9], 0));
        assertEquals(7, MoonlightApplePlanner.chooseHotbar(new boolean[9], 8));
    }
    @Test void rejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> MoonlightApplePlanner.chooseHotbar(new boolean[8], 0));
        assertThrows(IllegalArgumentException.class, () -> MoonlightApplePlanner.chooseHotbar(new boolean[9], 9));
    }
}
