/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */

package net.ccbluex.liquidbounce.features.module.modules.render.arraylist

import net.ccbluex.liquidbounce.config.types.list.Tagged

/**
 * Colour schemes for the arraylist, the notifications and anything else that samples
 * [ArraylistColors].
 *
 * Stops are stored as opaque ARGB ints and are walked cyclically, so `[a, b, c]` means
 * `a -> b -> c -> a`. Three or four stops are enough to get the diagonal multi-colour look famous
 * clients have without turning the fade into noise; two stops reproduce the Solstice original.
 *
 * The set below is a deliberate cross section of the palettes people recognise: the classic rainbow
 * cycle, Astolfo's pastel sweep, Vaporwave's pink/cyan, cyberpunk neon, Material-ish teals, plus the
 * editor themes (Dracula, Nord, Gruvbox) that translate well onto a dark translucent HUD. They are
 * colour tables, not ports of those clients' code.
 */
object ArraylistPalette {

    enum class Scheme(override val tag: String, vararg colors: Int) : Tagged {

        /** The original Solstice two-stop gradient. */
        SOLSTICE("Solstice", 0xFF5B8CFF.toInt(), 0xFFB466FF.toInt()),

        /** Pastel four-colour cycle, the "Astolfo" look. */
        ASTOLFO("Astolfo", 0xFFFF6BE5.toInt(), 0xFF9D6BFF.toInt(), 0xFF6BC8FF.toInt(), 0xFFFFFFFF.toInt()),

        /** Full hue cycle, sampled from the palette instead of the rainbow mode. */
        RAINBOW("Rainbow", 0xFFFF4D4D.toInt(), 0xFFFFFF4D.toInt(), 0xFF4DFF88.toInt(),
            0xFF4DF5FF.toInt(), 0xFF6B6BFF.toInt(), 0xFFFF6BE5.toInt()),

        /** Cyan into deep blue into mint. */
        AQUA("Aqua", 0xFF38E1FF.toInt(), 0xFF0A84FF.toInt(), 0xFF6FFFE0.toInt()),

        /** Frost white into ice blue. */
        ICE("Ice", 0xFFE9F7FF.toInt(), 0xFF9BD4FF.toInt(), 0xFF5AA9E6.toInt()),

        /** Warm dusk: amber, coral, magenta. */
        SUNSET("Sunset", 0xFFFFB86B.toInt(), 0xFFFF7A5C.toInt(), 0xFFFF4E8B.toInt(), 0xFFB65BFF.toInt()),

        /** Pink, cyan, mint - the vaporwave trio. */
        VAPORWAVE("Vaporwave", 0xFFFF71CE.toInt(), 0xFF01CDFE.toInt(), 0xFF05FFA1.toInt(), 0xFFB967FF.toInt()),

        /** Neon cyan into electric blue into magenta. */
        CYBER("Cyber", 0xFF00F0FF.toInt(), 0xFF0A6BFF.toInt(), 0xFFFF00E1.toInt()),

        /** Deep violet, cyan, orchid. */
        GALAXY("Galaxy", 0xFF6A4CFF.toInt(), 0xFF00D4FF.toInt(), 0xFFB45CFF.toInt()),

        /** Toxic green into spring green. */
        EMERALD("Emerald", 0xFF00FFA3.toInt(), 0xFF00C2A0.toInt(), 0xFF7CFF9B.toInt()),

        /** Ember: red, orange, gold. */
        LAVA("Lava", 0xFFFF3D00.toInt(), 0xFFFF9500.toInt(), 0xFFFFE259.toInt()),

        /** Crimson into rose. */
        BLOOD("Blood", 0xFFB3001B.toInt(), 0xFFFF2E63.toInt(), 0xFF8B0000.toInt()),

        /** Acid lime into cyan. */
        TOXIC("Toxic", 0xFF9BFF00.toInt(), 0xFF00FFB3.toInt(), 0xFFD4FF00.toInt()),

        /** The Dracula editor theme. */
        DRACULA("Dracula", 0xFFBD93F9.toInt(), 0xFFFF79C6.toInt(), 0xFF8BE9FD.toInt()),

        /** The Gruvbox editor theme. */
        GRUVBOX("Gruvbox", 0xFFFABD2F.toInt(), 0xFFFE8019.toInt(), 0xFFD3869B.toInt(), 0xFF8EC07C.toInt()),

        /** The Nord editor theme. */
        NORD("Nord", 0xFF88C0D0.toInt(), 0xFF81A1C1.toInt(), 0xFFB48EAD.toInt(), 0xFFA3BE8C.toInt()),

        /** Graphite, white into steel grey. */
        GRAPHITE("Graphite", 0xFFFFFFFF.toInt(), 0xFFC9D1D9.toInt(), 0xFF8B949E.toInt()),

        /** Soft pink, blush and baby blue. */
        CANDY("Candy", 0xFFFF8FB1.toInt(), 0xFFFFC6E1.toInt(), 0xFFB5EAFF.toInt()),

        /** Deep sea blue into turquoise. */
        OCEAN("Ocean", 0xFF0F4C81.toInt(), 0xFF2EB8C8.toInt(), 0xFF8CE8F0.toInt()),

        /** Cherry red into pink. */
        CHERRY("Cherry", 0xFFFF2D55.toInt(), 0xFFFF85A2.toInt(), 0xFFFFC2D1.toInt()),

        /** Terminal green. */
        MATRIX("Matrix", 0xFF00FF41.toInt(), 0xFF008F11.toInt(), 0xFF66FF99.toInt()),

        /** Rose, violet, electric blue. */
        NEBULA("Nebula", 0xFFFF2E63.toInt(), 0xFF7B2FF7.toInt(), 0xFF00E0FF.toInt()),

        /** Two colours picked in the Colors group. */
        CUSTOM("Custom", 0xFFFFFFFF.toInt(), 0xFF808080.toInt()),
        ;

        /** Cyclic stop table of this scheme, opaque ARGB. */
        val stops: IntArray = colors
    }

}
