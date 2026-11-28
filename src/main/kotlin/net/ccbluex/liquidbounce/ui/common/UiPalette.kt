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
package net.ccbluex.liquidbounce.ui.common

import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.ui.clickgui.glass.ClickGuiGlassSurface
import java.awt.Color
import kotlin.math.roundToInt

/**
 * Exact color tokens and CSS custom property derivations from 043's `_colors.scss` and `metadata.json`.
 */
object UiPalette {

    val DEFAULT_ACCENT = Color4b(0x46, 0x77, 0xFF, 0xFF)
    val DEFAULT_TINT = Color4b(0x00, 0x00, 0x00, 0xFF)

    @Volatile
    var accentColor: Color4b = DEFAULT_ACCENT

    @Volatile
    var tintColor: Color4b = DEFAULT_TINT

    /**
     * CSS: `--surface-color: color-mix(in srgb, #000000 82%, var(--tint-color) 18%);`
     */
    val surfaceBase: Color4b
        get() {
            val t = tintColor
            val r = (t.r * 0.18f).roundToInt().coerceIn(0, 255)
            val g = (t.g * 0.18f).roundToInt().coerceIn(0, 255)
            val b = (t.b * 0.18f).roundToInt().coerceIn(0, 255)
            return Color4b(r, g, b, 255)
        }

    fun surface(alpha: Float): Int {
        val s = surfaceBase
        val a = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
        return packArgb(a, s.r, s.g, s.b)
    }

    fun surfaceLightened(deltaRgb: Int, alpha: Float = 1f): Int {
        val s = surfaceBase
        val a = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
        return packArgb(
            a,
            (s.r + deltaRgb).coerceIn(0, 255),
            (s.g + deltaRgb).coerceIn(0, 255),
            (s.b + deltaRgb).coerceIn(0, 255),
        )
    }

    fun accent(alpha: Float = 1f): Int {
        val ac = if (ClickGuiGlassSurface.isActive &&
            net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui.isLightPollutionGlass) {
            Color4b(244, 247, 255, 255)
        } else if (ClickGuiGlassSurface.isActive &&
            net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui.isGaussianGlass) {
            Color4b(244, 184, 198, 255)
        } else accentColor
        val a = ((ac.a / 255f) * alpha.coerceIn(0f, 1f) * 255f).roundToInt()
        return packArgb(a, ac.r, ac.g, ac.b)
    }

    fun white(alpha: Float = 1f): Int =
        packArgb((alpha.coerceIn(0f, 1f) * 255f).roundToInt(), 255, 255, 255)

    fun black(alpha: Float = 1f): Int =
        packArgb((alpha.coerceIn(0f, 1f) * 255f).roundToInt(), 0, 0, 0)

    // Semantic text colors for Modern/Glass ClickGUI and HUD.
    val TEXT_COLOR: Int
        get() = 0xFFFFFFFF.toInt()
    val TEXT_DIMMED_COLOR: Int
        get() = 0xFFCBD5E1.toInt()
    val TEXT_MUTED_COLOR: Int
        get() = 0xFF94A3B8.toInt()
    val TAG_COLOR: Int
        get() = 0xFFAAAAAA.toInt()
    val ARRAYLIST_TAG_COLOR: Int get() = TAG_COLOR
    const val TABGUI_DISABLED_COLOR: Int = 0xFFD3D3D3.toInt()

    const val SUCCESS_COLOR: Int = 0xFF4DAC68.toInt()
    const val ERROR_COLOR: Int = 0xFFFC4130.toInt()
    const val INFO_COLOR: Int = 0xFF4677FF.toInt()
    const val WARNING_COLOR: Int = 0xFFFAF25E.toInt()

    // Hotbar status bar colors from `HotBar.svelte`
    const val HOTBAR_ARMOR: Int = 0xFF49EAD6.toInt()
    const val HOTBAR_AIR: Int = 0xFFAAC1E3.toInt()
    const val HOTBAR_ABSORPTION: Int = 0xFFD2AF26.toInt()
    const val HOTBAR_HEALTH: Int = 0xFFFC4130.toInt()
    const val HOTBAR_FOOD: Int = 0xFFB88458.toInt()
    const val HOTBAR_EXP: Int = 0xFF88C657.toInt()

    const val HOTBAR_ARMOR_COLOR: Int = HOTBAR_ARMOR
    const val HOTBAR_AIR_COLOR: Int = HOTBAR_AIR
    const val HOTBAR_ABSORPTION_COLOR: Int = HOTBAR_ABSORPTION
    const val HOTBAR_HEALTH_COLOR: Int = HOTBAR_HEALTH
    const val HOTBAR_HUNGER_COLOR: Int = HOTBAR_FOOD
    const val HOTBAR_EXPERIENCE_COLOR: Int = HOTBAR_EXP

    // ClickGUI & HUD Editor surface tokens matching `_colors.scss`
    val clickGuiBackdrop: Int get() = black(0.60f)
    val clickGuiGridLine: Int get() = white(0.05f)
    val GRID_LINE_COLOR: Int get() = clickGuiGridLine
    val tabsBg: Int get() = surface(0.85f)
    val tabsPillBg: Int get() = tabsBg
    val tabsShadow: Int get() = black(0.50f)
    val tabHoverBg: Int get() = white(0.06f)
    val tabActiveBg: Int get() = accent(0.18f)
    val tabActiveBorder: Int get() = accent(0.85f)
    val SEARCH_ALIAS_COLOR: Int get() = TEXT_MUTED_COLOR
    val MODULE_HIGHLIGHT_BORDER: Int get() = accent(1f)
    val ANCHOR_ZONE_BG: Int get() = white(0.02f)
    val ANCHOR_ZONE_BORDER: Int get() = white(0.08f)
    val ANCHOR_ZONE_ACTIVE_BG: Int get() = accent(0.10f)
    val ANCHOR_ZONE_ACTIVE_BORDER: Int get() = accent(0.55f)
    val MAGNETIC_GUIDE_COLOR: Int get() = accent(0.95f)

    val panelHeaderBg: Int get() = surface(0.90f)
    val panelBodyBg: Int get() = surface(0.80f)
    val panelShadow: Int get() = black(0.50f)
    val moduleHoverBg: Int get() = white(0.05f)
    val moduleSettingsBg: Int get() = surface(0.50f)

    val windowHeaderBg: Int get() = surface(0.92f)
    val windowBodyBg: Int get() = surface(0.82f)

    val searchBg: Int get() = surface(0.90f)
    val inputBg: Int get() = when {
        ClickGuiGlassSurface.isActive -> white(0.08f)
        else -> surface(0.55f)
    }
    val inputBorder: Int get() = when {
        ClickGuiGlassSurface.isActive -> white(0.20f)
        else -> white(0.14f)
    }
    val dropdownTriggerBg: Int get() = when {
        ClickGuiGlassSurface.isActive -> white(0.10f)
        else -> surfaceLightened(24, 0.85f)
    }
    val dropdownBg: Int get() = surfaceLightened(16, 0.96f)
    val dropdownBorder: Int get() = when {
        ClickGuiGlassSurface.isActive -> white(0.22f)
        else -> white(0.12f)
    }
    val switchUncheckedBg: Int get() = when {
        ClickGuiGlassSurface.isActive -> white(0.18f)
        else -> lerpArgb(surface(1f), TEXT_COLOR, 0.45f)
    }
    val switchTrackUnchecked: Int get() = switchUncheckedBg
    val switchThumbUnchecked: Int get() = TEXT_COLOR
    val switchTrackChecked: Int get() = when {
        ClickGuiGlassSurface.isActive &&
            net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui.isLightPollutionGlass ->
            packArgb(116, 198, 210, 228)
        ClickGuiGlassSurface.isActive -> accent(0.62f)
        else -> lerpArgb(surface(1f), accent(1f), 0.40f)
    }
    val switchThumbChecked: Int get() = when {
        ClickGuiGlassSurface.isActive &&
            net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui.isLightPollutionGlass ->
            TEXT_COLOR
        ClickGuiGlassSurface.isActive -> lerpArgb(accent(1f), TEXT_COLOR, 0.25f)
        else -> accent(1f)
    }
    val sliderTrackBg: Int get() = when {
        ClickGuiGlassSurface.isActive -> white(0.16f)
        else -> lerpArgb(surface(1f), TEXT_COLOR, 0.20f)
    }
    val hudPrimaryBg: Int get() = surface(0.68f)
    val hudSecondaryBg: Int get() = surface(0.50f)

    /**
     * Exact port of `mapToColor(value: number)` from `043` `color_utils.ts` (hue 0..120 deg in HSL).
     * Used by `BlockCounter` and `ItemStackView` durability bar.
     */
    fun mapToColor(value: Float, alpha: Int = 255): Int {
        val clamped = value.coerceIn(0f, 120f)
        val hue = clamped / 360f
        val rgb = Color.HSBtoRGB(hue, 1.0f, 0.92f) and 0x00FFFFFF
        return ((alpha.coerceIn(0, 255)) shl 24) or rgb
    }

    fun packArgb(a: Int, r: Int, g: Int, b: Int): Int =
        ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    fun withAlpha(argb: Int, alphaMultiplier: Float): Int {
        val baseA = (argb ushr 24) and 0xFF
        val outA = (baseA * alphaMultiplier.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
        return (argb and 0x00FFFFFF) or (outA shl 24)
    }

    fun withAlphaFactor(argb: Int, alphaMultiplier: Float): Int = withAlpha(argb, alphaMultiplier)

    fun lerpArgb(c0: Int, c1: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        val a0 = (c0 ushr 24) and 0xFF
        val r0 = (c0 ushr 16) and 0xFF
        val g0 = (c0 ushr 8) and 0xFF
        val b0 = c0 and 0xFF

        val a1 = (c1 ushr 24) and 0xFF
        val r1 = (c1 ushr 16) and 0xFF
        val g1 = (c1 ushr 8) and 0xFF
        val b1 = c1 and 0xFF

        return packArgb(
            (a0 + (a1 - a0) * f).roundToInt(),
            (r0 + (r1 - r0) * f).roundToInt(),
            (g0 + (g1 - g0) * f).roundToInt(),
            (b0 + (b1 - b0) * f).roundToInt(),
        )
    }
}
