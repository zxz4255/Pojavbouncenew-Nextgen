/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.features.module.modules.combat.moonlight;

/** Pure selection logic for moving an apple to the hotbar. */
public final class MoonlightApplePlanner {
    private MoonlightApplePlanner() { }

    /**
     * Prefer any empty hotbar slot. If all are full, swap a non-selected slot into the apple's
     * old inventory slot rather than changing the item currently held by the player.
     */
    public static int chooseHotbar(boolean[] empty, int selected) {
        if (empty.length != 9 || selected < 0 || selected > 8) {
            throw new IllegalArgumentException("Expected nine slots and a selected index in 0..8");
        }
        for (int slot = 0; slot < 9; slot++) {
            if (empty[slot]) return slot;
        }
        return selected == 8 ? 7 : 8;
    }
}
