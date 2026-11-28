#!/usr/bin/env python3
"""Extracts verbatim production methods from modified UI files and generates minimal typed API stubs
so real kotlinc compiles LightPollutionGlassSurface.kt, ClickGuiGlassSurface.kt, UiPalette.kt,
ModernGuiSurface.kt, and the exact extracted methods from onyx/render/UI.kt and SettingWidgets.kt."""
from pathlib import Path
import sys

work, repo = map(Path, sys.argv[1:3])
work.mkdir(parents=True, exist_ok=True)


def extract_block(text: str, start_marker: str) -> str:
    a = text.index(start_marker)
    b = text.index("{", a)
    i = b + 1
    depth = 1
    while depth > 0:
        depth += (text[i] == "{") - (text[i] == "}")
        i += 1
    return text[a:i]


(work / "Stubs.kt").write_text("""
package org.joml
class Matrix3x2f
class Vector2f(var x: Float = 0f, var y: Float = 0f)
""")

(work / "McStubs.kt").write_text("""
package net.minecraft.client.gui.navigation
data class ScreenRectangle(val left: Int, val top: Int, val right: Int, val bottom: Int)
""")

(work / "McRenderStubs.kt").write_text("""
package net.minecraft.client.gui.render
class TextureSetup
""")

(work / "McGuiStubs.kt").write_text("""
package net.minecraft.client.gui
import net.minecraft.client.gui.navigation.ScreenRectangle

class ScissorStack {
    private val stack = ArrayDeque<ScreenRectangle>()
    fun peek(): ScreenRectangle? = stack.lastOrNull()
    fun push(r: ScreenRectangle) { stack.addLast(r) }
    fun pop() { if (stack.isNotEmpty()) stack.removeLast() }
}

class GuiRenderState {
    val elements = mutableListOf<Any>()
    fun addGuiElement(el: Any) { elements.add(el) }
}

class GuiGraphicsExtractor {
    val guiRenderState = GuiRenderState()
    val scissorStack = ScissorStack()
    var strata = 0
    fun nextStratum() { strata++ }
}
""")

(work / "McUtilStubs.kt").write_text("""
package net.minecraft.util
object ARGB {
    fun opaque(color: Int): Int = color or (0xFF shl 24)
}
""")

(work / "LbRenderStubs.kt").write_text("""
package net.ccbluex.liquidbounce.render
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import org.joml.Matrix3x2f
import kotlin.math.ceil
import kotlin.math.floor

fun GuiGraphicsExtractor.copyPosePooled(): Matrix3x2f = Matrix3x2f()
fun GuiGraphicsExtractor.getBounds(x0: Float, y0: Float, x1: Float, y1: Float): ScreenRectangle =
    ScreenRectangle(floor(x0).toInt(), floor(y0).toInt(), ceil(x1).toInt(), ceil(y1).toInt())
""")

(work / "LbElementStubs.kt").write_text("""
package net.ccbluex.liquidbounce.render.gui.element
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import org.joml.Matrix3x2f

data class ClickGuiGlassGuiElementRenderState(
    val x0: Float, val y0: Float, val x1: Float, val y1: Float,
    val radiusTL: Float, val radiusTR: Float, val radiusBR: Float, val radiusBL: Float,
    val colorTL: Int, val colorTR: Int, val colorBR: Int, val colorBL: Int,
    val layerOpacity: Float, val refractionStrength: Float,
    val rimColor: Int, val rimWidth: Float,
    val shadowColor: Int, val shadowSpread: Float,
    val referenceGlass: Boolean, val referenceProfile: Int,
    val textureSetup: TextureSetup,
    val pose: Matrix3x2f, val scissorArea: ScreenRectangle?, val bounds: ScreenRectangle?
) {
    constructor(
        x0: Float, y0: Float, x1: Float, y1: Float,
        radiusTL: Float, radiusTR: Float, radiusBR: Float, radiusBL: Float,
        colorTL: Int, colorTR: Int, colorBR: Int, colorBL: Int,
        layerOpacity: Float, refractionStrength: Float,
        rimColor: Int, rimWidth: Float,
        shadowColor: Int, shadowSpread: Float,
        textureSetup: TextureSetup,
        pose: Matrix3x2f, scissorArea: ScreenRectangle?, bounds: ScreenRectangle?
    ) : this(
        x0, y0, x1, y1, radiusTL, radiusTR, radiusBR, radiusBL,
        colorTL, colorTR, colorBR, colorBL, layerOpacity, refractionStrength,
        rimColor, rimWidth, shadowColor, shadowSpread, false, 0, textureSetup, pose, scissorArea, bounds
    )
}

data class CssBoxGuiElementRenderState(
    val x0: Float, val y0: Float, val x1: Float, val y1: Float,
    val radiusTL: Float, val radiusTR: Float, val radiusBR: Float, val radiusBL: Float,
    val colorTL: Int, val colorTR: Int, val colorBR: Int, val colorBL: Int,
    val borderColor: Int, val borderWidth: Float,
    val shadowColor: Int, val shadowBlur: Float,
    val pose: Matrix3x2f, val scissorArea: ScreenRectangle?, val bounds: ScreenRectangle?
)
""")

(work / "LbModuleStubs.kt").write_text("""
package net.ccbluex.liquidbounce.render.engine.type
data class Color4b(val r: Int, val g: Int, val b: Int, val a: Int = 255) {
    val argb: Int get() = ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
}
""")

(work / "LbBackdropStub.kt").write_text("""
package net.ccbluex.liquidbounce.ui.clickgui.glass
import net.minecraft.client.gui.render.TextureSetup

object ClickGuiGlassBackdrop {
    var available: Boolean = true
    var lastStrength: Float = 0f
    var lastVibrance: Float = 0f
    fun request(strength: Float, vibrance: Float = 1.08f, contrast: Boolean = false): TextureSetup? {
        lastStrength = strength
        lastVibrance = vibrance
        return if (available) TextureSetup() else null
    }
}
""")

(work / "LbClickGuiModuleStub.kt").write_text("""
package net.ccbluex.liquidbounce.features.module.modules.render
object ModuleClickGui {
    var isGlassStyle: Boolean = true
    var isLightPollutionGlass: Boolean = true
    var glassBlurValue: Float = 1.25f
    var glassTintOpacityValue: Float = 0.28f
    var glassRefractionValue: Float = 1.0f
    var glassRimValue: Float = 1.0f
    var glassShadowValue: Float = 1.0f
}
""")

(work / "LbModernGuiModuleStub.kt").write_text("""
package net.ccbluex.liquidbounce.features.module.modules.render.moderngui
import net.ccbluex.liquidbounce.render.engine.type.Color4b

data class MonetPalette(
    val isLight: Boolean, val primary: Int, val onPrimary: Int,
    val primaryContainer: Int, val secondaryContainer: Int,
    val surfaceLowest: Int, val surfaceLow: Int, val surface: Int,
    val surfaceHigh: Int, val surfaceHighest: Int,
    val onSurface: Int, val onSurfaceVariant: Int,
    val outline: Int, val outlineVariant: Int, val scrim: Int,
)

object ModuleModernGui {
    var running: Boolean = true
    var usesGlass: Boolean = true
    var isLightPollutionGlass: Boolean = true
    object Targets {
        var clientHudCards: Boolean = true
        var screensAndMenus: Boolean = true
    }
    object CustomColors {
        var surfaceColor = Color4b(24, 28, 36, 255)
        var accentColor = Color4b(70, 119, 255, 255)
        var borderColor = Color4b(255, 255, 255, 255)
    }
    object GlassOptics {
        var blurStrength: Float = 1.35f
        var refraction: Float = 0.9f
        var specularRim: Float = 1.0f
        var accentRimMix: Float = 0.28f
    }
    object CardDesign {
        var panelRadius: Float = 10f
        var widgetRadius: Float = 6f
        var slotRadius: Float = 4f
        var cardOpacity: Float = 0.36f
        var slotOpacity: Float = 0.28f
        var borderOpacity: Float = 0.32f
        var borderWidth: Float = 1.0f
        var shadowOpacity: Float = 0.42f
        var shadowSpread: Float = 14f
    }
}
""")

(work / "LbMaterialStub.kt").write_text("""
package net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material
import kotlin.math.roundToInt

object Material {
    fun mix(a: Int, b: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        fun ch(sh: Int): Int {
            val ca = (a ushr sh) and 0xFF
            val cb = (b ushr sh) and 0xFF
            return (ca + (cb - ca) * f).roundToInt().coerceIn(0, 255)
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
    fun lstarFromArgb(argb: Int): Double {
        val r = ((argb ushr 16) and 0xFF) / 255.0
        val g = ((argb ushr 8) and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        return (0.2126 * r + 0.7152 * g + 0.0722 * b) * 100.0
    }
}
""")

(work / "LbUiDrawStub.kt").write_text("""
package net.ccbluex.liquidbounce.ui.common
import net.ccbluex.liquidbounce.render.copyPosePooled
import net.ccbluex.liquidbounce.render.getBounds
import net.ccbluex.liquidbounce.render.gui.element.CssBoxGuiElementRenderState
import net.minecraft.client.gui.GuiGraphicsExtractor

enum class UiFontWeight { REGULAR, MEDIUM, SEMIBOLD, BOLD }

object UiDraw {
    fun fillCssBox(
        ctx: GuiGraphicsExtractor,
        x: Float, y: Float, w: Float, h: Float,
        rTL: Float = 0f, rTR: Float = rTL, rBR: Float = rTL, rBL: Float = rTL,
        cTL: Int, cTR: Int = cTL, cBR: Int = cTL, cBL: Int = cTL,
        borderColor: Int = 0, borderWidth: Float = 0f,
        shadowColor: Int = 0, shadowBlur: Float = 0f,
    ) {
        val pad = if ((shadowColor ushr 24) != 0 && shadowBlur > 0.01f) shadowBlur * 2.2f else 0f
        ctx.guiRenderState.addGuiElement(CssBoxGuiElementRenderState(
            x, y, x + w, y + h, rTL, rTR, rBR, rBL, cTL, cTR, cBR, cBL,
            borderColor, borderWidth, shadowColor, shadowBlur,
            ctx.copyPosePooled(), ctx.scissorStack.peek(), ctx.getBounds(x - pad, y - pad, x + w + pad, y + h + pad)
        ))
    }
    fun fillBox(
        ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float,
        argb: Int, radius: Float = 0f, borderColor: Int = 0, borderWidth: Float = 0f,
        shadowColor: Int = 0, shadowBlur: Float = 0f,
    ) = fillCssBox(ctx, x, y, w, h, radius, radius, radius, radius, argb, argb, argb, argb, borderColor, borderWidth, shadowColor, shadowBlur)

    fun lineHeight(fontSizePx: Float, weight: UiFontWeight = UiFontWeight.REGULAR): Float = fontSizePx * 1.2f
    fun ellipsize(text: String, maxWidth: Float, fontSizePx: Float, weight: UiFontWeight = UiFontWeight.REGULAR): String = text
    fun drawText(
        ctx: GuiGraphicsExtractor, text: String, x: Float, y: Float,
        fontSizePx: Float, argb: Int, weight: UiFontWeight = UiFontWeight.REGULAR, shadow: Boolean = false
    ): Float = text.length * fontSizePx * 0.5f
}
""")

ui_src = (repo / "src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/misc/music/onyx/render/UI.kt").read_text()
lp_card_fn = extract_block(ui_src, "    fun lightPollutionCard(")
music_card_fn = extract_block(ui_src, "    fun musicPanelCard(")

onyx_header = """package net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render
import net.ccbluex.liquidbounce.features.module.modules.render.moderngui.ModuleModernGui
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.min

object Theme {
    fun radius(r: Float, w: Float, h: Float): Float = min(r, min(w, h) * 0.5f)
}

class OnyxUiHost(val ctx: GuiGraphicsExtractor, var scale: Float = 1f, var alpha: Float = 1f) {
    fun visibleScissor(): Boolean = true
    fun screenX(x: Float): Float = x * scale
    fun screenY(y: Float): Float = y * scale
"""
(work / "OnyxUiExtracted.kt").write_text(onyx_header + lp_card_fn + "\n\n" + music_card_fn + "\n}\n")

sw_src = (repo / "src/main/kotlin/net/ccbluex/liquidbounce/ui/clickgui/setting/SettingWidgets.kt").read_text()
switch_obj = extract_block(sw_src, "object SwitchWidget ")
sw_header = """package net.ccbluex.liquidbounce.ui.clickgui.setting
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui
import net.ccbluex.liquidbounce.ui.clickgui.glass.ClickGuiGlassSurface
import net.ccbluex.liquidbounce.ui.common.UiDraw
import net.ccbluex.liquidbounce.ui.common.UiFontWeight
import net.ccbluex.liquidbounce.ui.common.UiPalette
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.max

"""
(work / "SwitchExtracted.kt").write_text(sw_header + switch_obj + "\n")


# Extract verbatim slider track+pill render blocks from SettingWidgets.kt
s_idx = sw_src.index("        run {\n            if (ClickGuiGlassSurface.isActive && ModuleClickGui.isLightPollutionGlass)")
single_slider_block = extract_block(sw_src[s_idx:], "        run ")

r_idx = sw_src.index("        if (ClickGuiGlassSurface.isActive && ModuleClickGui.isLightPollutionGlass) {\n            val glowScale = ModuleClickGui.glassRimValue.coerceIn(0f, 1.6f)\n            val lpTrackH = 3.0f\n            val lpTrackY = trackY - 0.5f\n            UiDraw.fillBox(ctx, trackX, lpTrackY, trackW, lpTrackH, UiPalette.white(0.18f), radius = 1.5f)\n            val connX")
r_else_idx = sw_src.index("        maybeShowTooltip(mouseX, mouseY, x, y, width, 19f, canvasWidth)\n        return h\n    }\n\n    private fun updateThumb", r_idx)
range_slider_block = sw_src[r_idx:r_else_idx]

tab_src = (repo / "src/main/kotlin/net/ccbluex/liquidbounce/ui/clickgui/ClickGuiTabView.kt").read_text()
p_idx = tab_src.index("        if (glassMode) {\n            val panelRadius = if (ModuleClickGui.isLightPollutionGlass)")
panel_hdr_block = extract_block(tab_src[p_idx:], "        if (glassMode)")
row_idx = tab_src.index("                    if (glassMode) {\n                        val capsuleFade =")
row_cap_block = extract_block(tab_src[row_idx:], "                    if (glassMode)")

(work / "SlidersAndRowsExtracted.kt").write_text(f"""package net.ccbluex.liquidbounce.ui.clickgui.setting
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui
import net.ccbluex.liquidbounce.ui.clickgui.glass.ClickGuiGlassSurface
import net.ccbluex.liquidbounce.ui.common.UiDraw
import net.ccbluex.liquidbounce.ui.common.UiPalette
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object ExtractedControlsHost {{
    fun renderSingleSlider(ctx: GuiGraphicsExtractor, trackX: Float, trackY: Float, trackW: Float, trackH: Float, ratio: Float) {{
{single_slider_block}
    }}
    fun renderRangeSlider(ctx: GuiGraphicsExtractor, trackX: Float, trackY: Float, trackW: Float, trackH: Float, rLo: Float, rHi: Float) {{
{range_slider_block}
    }}
    class RowStub(var enabledAnim: Float, var hoverAnim: Float)
    fun renderPanelShell(ctx: GuiGraphicsExtractor, glassMode: Boolean, px: Float, py: Float, PANEL_WIDTH: Float, PANEL_HEADER_HEIGHT: Float, bodyH: Float, totalH: Float) {{
{panel_hdr_block}
    }}
    fun renderModuleRowGlass(ctx: GuiGraphicsExtractor, glassMode: Boolean, px: Float, rowY: Float, PANEL_WIDTH: Float, MODULE_ROW_HEIGHT: Float, row: RowStub) {{
{row_cap_block}
    }}
}}
""")

print("Generated host stubs + extracted verbatim Onyx UI, SwitchWidget, Slider & ClickGuiTabView blocks.")
