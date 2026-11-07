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

/**
 * Port of Solstice's `ColorUtils::getThemedColor`.
 *
 * The original walks a cyclic color table whose phase is driven by the row index and the current
 * time, so the list forms a gradient that slides downwards over time. That is reproduced here with a
 * [ColorMode.GRADIENT] (two stop color table, the default in Solstice), a [ColorMode.RAINBOW] (the
 * `Interface::Rainbow` theme) and a plain [ColorMode.STATIC] color.
 */
class ArraylistColors : ValueGroup("Colors") {

    enum class ColorMode(override val tag: String) : Tagged {
        GRADIENT("Gradient"),
        RAINBOW("Rainbow"),
        STATIC("Static"),
    }

    private val mode by enumChoice("Mode", ColorMode.GRADIENT)

    /**
     * Full color cycle time in seconds, `Interface::mColorSpeed` in the original.
     */
    private val speed by float("Speed", 3f, 0.5f..20f, "s")

    private val saturation by float("Saturation", 1f, 0f..1f)

    private val brightness by float("Brightness", 1f, 0f..1f)

    private val primary by color("Primary", Color4b(0x5B, 0x8C, 0xFF, 255))

    private val secondary by color("Secondary", Color4b(0xB4, 0x66, 0xFF, 255))

    private val staticColor by color("Static", Color4b.WHITE)

    private val stops = arrayOf(Color4b.WHITE, Color4b.WHITE)

    /**
     * @param index color table offset, the arraylist passes twice the row position like the original
     * @param now current time in milliseconds
     */
    fun color(index: Float, now: Long): Color4b = when (mode) {
        ColorMode.RAINBOW -> rainbow(speed, saturation, brightness, index, now)
        ColorMode.GRADIENT -> {
            stops[0] = primary
            stops[1] = secondary
            lerpColors(speed, index, stops, now)
        }
        ColorMode.STATIC -> staticColor
    }

    private fun rainbow(seconds: Float, saturation: Float, brightness: Float, index: Float, now: Long): Color4b {
        val period = (seconds * 1000f).toInt().coerceAtLeast(1)
        val hue = ((now + index.toLong()) % period) / period.toFloat()
        return Color4b.ofHSB(hue, saturation, brightness, 1f)
    }

    /**
     * Literal port of `ColorUtils::LerpColors`, including its `10000 / speed` cycle time.
     */
    private fun lerpColors(seconds: Float, index: Float, colors: Array<Color4b>, now: Long): Color4b {
        if (colors.isEmpty()) {
            return Color4b.WHITE
        }

        val time = 10000f / seconds
        val angle = ((now + index.toLong()) % time.toInt().coerceAtLeast(1)).toFloat()
        val segmentTime = time / colors.size

        val segmentIndex = (angle / segmentTime).toInt().coerceIn(0, colors.lastIndex)
        val segmentIndexFloat = angle / segmentTime - segmentIndex

        return colors[segmentIndex].lerp(colors[(segmentIndex + 1) % colors.size], segmentIndexFloat)
    }

    private fun Color4b.lerp(to: Color4b, delta: Float): Color4b {
        val t = delta.coerceIn(0f, 1f)
        return Color4b(
            r = mathLerp(r, to.r, t),
            g = mathLerp(g, to.g, t),
            b = mathLerp(b, to.b, t),
            a = mathLerp(a, to.a, t),
        )
    }

    private fun mathLerp(from: Int, to: Int, delta: Float): Int =
        (from + (to - from) * delta).toInt().coerceIn(0, 255)

}
