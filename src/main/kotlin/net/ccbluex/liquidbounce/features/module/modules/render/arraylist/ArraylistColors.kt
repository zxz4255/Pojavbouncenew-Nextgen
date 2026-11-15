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

import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import java.awt.Color

/**
 * Colour source of the arraylist, the notifications and the world lyrics accent.
 *
 * Solstice only had one two-stop gradient plus a rainbow mode, which is why every port of it looks
 * identical. Here the colours come from a [ArraylistPalette.Scheme] table, and every element of the
 * list - row text, row surface, accent bar, rim light and glow - is sampled from that one palette:
 *
 * - [color] is the row tone,
 * - [secondColor] is one palette step further along, used for the vertical gradient of the surface
 *   and of the accent bar,
 * - [glowTones] returns the inner and outer bloom tone, so the glow follows the scheme too.
 *
 * Sampling is a seamless cycle: the phase walks the stops and wraps from the last back to the first,
 * eased with a smoothstep so the wrap is not visible as a seam.
 */
class ArraylistColors : ValueGroup("Colors") {

    enum class ColorMode(override val tag: String) : Tagged {
        /** Walk the stops of the selected [Scheme]. */
        GRADIENT("Gradient"),

        /** Hue cycle; ignores the scheme stops but honours saturation and brightness. */
        RAINBOW("Rainbow"),

        /** One fixed colour for the whole list. */
        STATIC("Static"),
    }

    /**
     * How the glow is coloured.
     */
    enum class GlowMode(override val tag: String) : Tagged {
        /** Follow the active palette (two tones: inner and outer). */
        PALETTE("Palette"),

        /** Use the text colour of the row. */
        TEXT("Text"),

        /** One fixed [glow] colour for the whole list. */
        MANUAL("Manual"),
    }

    private val scheme by enumChoice("Scheme", ArraylistPalette.Scheme.SOLSTICE)

    private val mode by enumChoice("Mode", ColorMode.GRADIENT)

    /**
     * Full colour cycle time in seconds, `Interface::mColorSpeed` in the original. The original time
     * base is `10000 / speed` milliseconds per cycle, which is kept here for the same feel.
     */
    private val speed by float("Speed", 3f, 0.5f..20f, "s")

    /**
     * Extra phase, in milliseconds, per unit of row index. The arraylist passes `y * 2` as the index,
     * so `1` reproduces the original stagger and larger values stretch the diagonal.
     */
    private val stagger by float("Stagger", 1f, 0f..20f)

    private val saturation by float("Saturation", 1f, 0f..1f)

    private val brightness by float("Brightness", 1f, 0f..1f)

    /**
     * How far the second tone sits from the first one, as a fraction of a full cycle. This is what
     * turns a flat single-colour list into the diagonal two-tone look.
     */
    private val gradientOffset by float("GradientOffset", 0.42f, 0f..1f)

    private val customPrimary by color("CustomPrimary", Color4b(0x5B, 0x8C, 0xFF, 255))
    private val customSecondary by color("CustomSecondary", Color4b(0xB4, 0x66, 0xFF, 255))
    private val staticColor by color("Static", Color4b.WHITE)

    private val glowMode by enumChoice("GlowMode", GlowMode.PALETTE)
    private val glow by color("Glow", Color4b(0x5B, 0x8C, 0xFF, 255))

    /**
     * How far the outer bloom tone sits from the inner one, as a fraction of a full cycle.
     */
    private val bloomShift by float("BloomShift", 0.3f, 0f..1f)

    /**
     * Primary tone of the row at [index].
     *
     * @param index colour table offset in milliseconds, the arraylist passes twice the row position
     *   like the original did
     * @param now current time in milliseconds
     */
    fun color(index: Float, now: Long): Color4b = when (mode) {
        ColorMode.RAINBOW -> rainbow(index, now, 0f)
        ColorMode.GRADIENT -> gradient(index, now, 0f)
        ColorMode.STATIC -> tone(staticColor)
    }

    /**
     * Second tone for the same row: the palette sampled [gradientOffset] of a cycle further along.
     * Paired with [color] this is the vertical gradient of the row surface and of the accent bar.
     */
    fun secondColor(index: Float, now: Long): Color4b = when (mode) {
        ColorMode.RAINBOW -> rainbow(index, now, gradientOffset)
        ColorMode.GRADIENT -> gradient(index, now, gradientOffset)
        ColorMode.STATIC -> tone(staticColor)
    }

    /**
     * Inner and outer glow tones for a row, so the bloom belongs to the same scheme without being a
     * flat copy of the text colour.
     */
    fun glowTones(index: Float, now: Long, textColor: Color4b): Pair<Color4b, Color4b> = when (glowMode) {
        GlowMode.MANUAL -> glow to glow
        GlowMode.TEXT -> textColor to textColor
        GlowMode.PALETTE -> when (mode) {
            ColorMode.RAINBOW -> rainbow(index, now, 0f) to rainbow(index, now, bloomShift)
            ColorMode.GRADIENT -> gradient(index, now, 0f) to gradient(index, now, bloomShift)
            ColorMode.STATIC -> tone(staticColor) to tone(staticColor)
        }
    }

    /** The stop table of the active scheme, with [ArraylistPalette.Scheme.CUSTOM] resolved. */
    fun stops(): IntArray {
        val active = scheme
        return if (active == ArraylistPalette.Scheme.CUSTOM) {
            intArrayOf(customPrimary.argb, customSecondary.argb)
        } else {
            active.stops
        }
    }

    /** Every stop of the active scheme, already passed through saturation and brightness. */
    fun schemeStops(): List<Color4b> = stops().map { tone(Color4b(it)) }

    private fun gradient(index: Float, now: Long, extraPhase: Float): Color4b {
        val table = stops()
        if (table.isEmpty()) {
            return tone(staticColor)
        }

        val base = phase(index, now)
        val position = ((base + extraPhase) * table.size) % table.size

        val from = position.toInt().coerceIn(0, table.size - 1)
        val to = (from + 1) % table.size
        val t = position - from

        // Smoothstep between stops, so the wrap from the last stop back to the first is seamless.
        val eased = t * t * (3f - 2f * t)

        return tone(Color4b(table[from]).interpolateTo(Color4b(table[to]), eased.toDouble()))
    }

    private fun rainbow(index: Float, now: Long, extraPhase: Float): Color4b {
        val hue = ((phase(index, now) + extraPhase) % 1f + 1f) % 1f
        return Color4b.ofHSB(
            hue,
            saturation.coerceIn(0f, 1f),
            brightness.coerceIn(0f, 1f),
            1f,
        )
    }

    /** Position inside the current cycle, `0..1`. */
    private fun phase(index: Float, now: Long): Float {
        val periodMs = (10_000f / speed.coerceAtLeast(0.01f)).toLong().coerceAtLeast(200L)
        val shifted = now + (index * stagger).toLong()
        return Math.floorMod(shifted, periodMs).toFloat() / periodMs
    }

    private fun tone(value: Color4b): Color4b {
        if (saturation >= 1f && brightness >= 1f) {
            return value
        }

        val hsb = Color.RGBtoHSB(value.r, value.g, value.b, null)
        return Color4b.ofHSB(
            hsb[0],
            (hsb[1] * saturation).coerceIn(0f, 1f),
            (hsb[2] * brightness).coerceIn(0f, 1f),
            value.a / 255f,
        )
    }

}
