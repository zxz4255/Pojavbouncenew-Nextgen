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
package net.ccbluex.liquidbounce.utils.render

import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.ui.common.UiDraw
import net.ccbluex.liquidbounce.ui.common.UiFontWeight
import net.ccbluex.liquidbounce.ui.common.UiPalette
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sin

object Ts2VisualUtils {

    private val SECTION_REGEX = Regex("§[0-9a-fk-orA-FK-OR]")

    fun stripColor(text: String): String = text.replace(SECTION_REGEX, "")

    /**
     * 1:1 port of ts2 `ColorUtils.rainbow`.
     */
    fun rainbow(offset: Long = 400_000L, alpha: Float = 1f): Color4b {
        val hue = (((System.nanoTime() + offset * 1_000_000L) / 10_000_000_000.0) % 1.0).toFloat()
        val rgb = Color.HSBtoRGB(if (hue < 0f) hue + 1f else hue, 1f, 1f)
        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        return Color4b((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, a)
    }

    /**
     * 1:1 port of ts2 `ColorUtils.skyRainbow`.
     */
    fun skyRainbow(index: Int, saturation: Float, brightness: Float, speed: Float, alpha: Int = 255): Color4b {
        var v1 = ceil((System.currentTimeMillis() + (index * 109f * speed).toLong()).toDouble()) / 5.0
        v1 %= 360.0
        val hue = if ((v1 / 360.0).toFloat() < 0.5f) -((v1 / 360.0).toFloat()) else (v1 / 360.0).toFloat()
        val c = Color.getHSBColor(hue, saturation.coerceIn(0f, 1f), brightness.coerceIn(0f, 1f))
        return Color4b(c.red, c.green, c.blue, alpha.coerceIn(0, 255))
    }

    /**
     * 1:1 port of ts2 `ColorUtils.fade`.
     */
    fun fade(color: Color4b, index: Int, count: Int): Color4b {
        val safeCount = max(1, count)
        val hsb = Color.RGBtoHSB(color.r, color.g, color.b, null)
        var brightness = abs(
            ((System.currentTimeMillis() % 2000L).toFloat() / 1000.0f +
                index.toFloat() / safeCount.toFloat() * 2.0f) % 2.0f - 1.0f
        )
        brightness = 0.5f + 0.5f * brightness
        val rgb = Color.HSBtoRGB(hsb[0], hsb[1], brightness % 2.0f)
        return Color4b((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, color.a)
    }

    /**
     * 1:1 port of ts2 `ClientThemesUtils.getColor(index)`.
     */
    fun themeColor(index: Int = 0): Color4b {
        val base = UiPalette.accentColor
        return fade(base, index * 12, 100)
    }

    /**
     * 1:1 port of ts2 `getMixedColor(index, seconds)`.
     */
    fun mixerColor(c1: Color4b, c2: Color4b, index: Int, seconds: Int): Color4b {
        val periodMs = max(1, seconds) * 1000L
        val phase = ((System.currentTimeMillis() + index * 100L) % periodMs).toDouble() / periodMs.toDouble()
        val t = ((sin(phase * Math.PI * 2.0) + 1.0) * 0.5).toFloat()
        return blendColors(c1, c2, t)
    }

    fun blendColors(c1: Color4b, c2: Color4b, ratio: Float): Color4b {
        val t = ratio.coerceIn(0f, 1f)
        val r = (c1.r * (1f - t) + c2.r * t).toInt().coerceIn(0, 255)
        val g = (c1.g * (1f - t) + c2.g * t).toInt().coerceIn(0, 255)
        val b = (c1.b * (1f - t) + c2.b * t).toInt().coerceIn(0, 255)
        val a = (c1.a * (1f - t) + c2.a * t).toInt().coerceIn(0, 255)
        return Color4b(r, g, b, a)
    }

    /**
     * 1:1 port of ts2 `AnimationUtil.base`.
     */
    fun animBase(current: Float, target: Float, speed: Float): Float {
        val step = (target - current) * speed.coerceIn(0.01f, 1f)
        return if (abs(target - current) < 0.001f) target else current + step
    }

    /**
     * Renders a crisp smooth outer rounded drop-shadow matching ts2's stencil outer glow/shadow.
     */
    fun drawOuterShadow(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        radius: Float,
        shadowRadius: Float,
        shadowColor: Color4b,
    ) {
        if (w <= 0f || h <= 0f || shadowRadius <= 0.1f || shadowColor.a <= 0) return
        UiDraw.fillCssBox(
            ctx = ctx,
            x = x,
            y = y,
            w = w,
            h = h,
            rTL = radius,
            rTR = radius,
            rBR = radius,
            rBL = radius,
            cTL = 0,
            cTR = 0,
            cBR = 0,
            cBL = 0,
            shadowColor = shadowColor.argb,
            shadowBlur = shadowRadius,
        )
    }

    fun drawTopRoundedRect(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        radius: Float,
        argb: Int,
    ) {
        UiDraw.fillCssBox(
            ctx = ctx,
            x = x,
            y = y,
            w = w,
            h = h,
            rTL = radius,
            rTR = radius,
            rBR = 0f,
            rBL = 0f,
            cTL = argb,
        )
    }

    private fun sectionColor(code: Char, defaultArgb: Int): Int {
        val alpha = (defaultArgb ushr 24) and 0xFF
        val rgb = when (code.lowercaseChar()) {
            '0' -> 0x000000
            '1' -> 0x0000AA
            '2' -> 0x00AA00
            '3' -> 0x00AAAA
            '4' -> 0xAA0000
            '5' -> 0xAA00AA
            '6' -> 0xFFAA00
            '7' -> 0xAAAAAA
            '8' -> 0x555555
            '9' -> 0x5555FF
            'a' -> 0x55FF55
            'b' -> 0x55FFFF
            'c' -> 0xFF5555
            'd' -> 0xFF55FF
            'e' -> 0xFFFF55
            'f' -> 0xFFFFFF
            else -> defaultArgb and 0x00FFFFFF
        }
        return (alpha shl 24) or rgb
    }

    fun measureFormattedText(
        text: String,
        fontSizePx: Float,
        weight: UiFontWeight = UiFontWeight.REGULAR,
    ): Float {
        if (text.isEmpty() || fontSizePx <= 0f) return 0f
        var width = 0f
        var bold = false
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '§' && i + 1 < text.length) {
                if (sb.isNotEmpty()) {
                    width += UiDraw.measureText(sb.toString(), fontSizePx, weight, bold = bold)
                    sb.clear()
                }
                val code = text[i + 1].lowercaseChar()
                when {
                    code in '0'..'9' || code in 'a'..'f' || code == 'r' -> bold = false
                    code == 'l' -> bold = true
                }
                i += 2
                continue
            }
            sb.append(ch)
            i++
        }
        if (sb.isNotEmpty()) {
            width += UiDraw.measureText(sb.toString(), fontSizePx, weight, bold = bold)
        }
        return width
    }

    fun drawFormattedText(
        ctx: GuiGraphicsExtractor,
        text: String,
        x: Float,
        y: Float,
        fontSizePx: Float,
        defaultArgb: Int,
        weight: UiFontWeight = UiFontWeight.REGULAR,
        shadow: Boolean = false,
    ): Float {
        if (text.isEmpty() || fontSizePx <= 0f || ((defaultArgb ushr 24) and 0xFF) == 0) return 0f
        var cursorX = x
        var currentColor = defaultArgb
        var bold = false
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '§' && i + 1 < text.length) {
                if (sb.isNotEmpty()) {
                    cursorX += UiDraw.drawText(
                        ctx, sb.toString(), cursorX, y, fontSizePx, currentColor,
                        weight = weight, shadow = shadow, bold = bold,
                    )
                    sb.clear()
                }
                val code = text[i + 1].lowercaseChar()
                when {
                    code in '0'..'9' || code in 'a'..'f' -> {
                        currentColor = sectionColor(code, defaultArgb)
                        bold = false
                    }
                    code == 'r' -> {
                        currentColor = defaultArgb
                        bold = false
                    }
                    code == 'l' -> bold = true
                }
                i += 2
                continue
            }
            sb.append(ch)
            i++
        }
        if (sb.isNotEmpty()) {
            cursorX += UiDraw.drawText(
                ctx, sb.toString(), cursorX, y, fontSizePx, currentColor,
                weight = weight, shadow = shadow, bold = bold,
            )
        }
        return cursorX - x
    }
}
