package net.ccbluex.liquidbounce.ui.clickgui.glass

import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.OnyxUiHost
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui
import net.ccbluex.liquidbounce.features.module.modules.render.moderngui.ModernGuiSurface
import net.ccbluex.liquidbounce.features.module.modules.render.moderngui.ModuleModernGui
import net.ccbluex.liquidbounce.render.gui.element.ClickGuiGlassGuiElementRenderState
import net.ccbluex.liquidbounce.render.gui.element.CssBoxGuiElementRenderState
import net.ccbluex.liquidbounce.ui.clickgui.setting.ExtractedControlsHost
import net.ccbluex.liquidbounce.ui.clickgui.setting.SwitchWidget
import net.ccbluex.liquidbounce.ui.common.UiPalette
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import kotlin.math.abs

fun main() {
    var checks = 0
    fun expect(cond: Boolean, msg: String = "") {
        checks++
        check(cond) { "Assertion #$checks failed: $msg" }
    }

    // 1. UiPalette LightPollutionGlass frost-white accent (no pink 0xF4B8C6!)
    ModuleClickGui.isGlassStyle = true
    ModuleClickGui.isLightPollutionGlass = true
    expect(ClickGuiGlassSurface.beginFrame(), "beginFrame must succeed")
    val lpAccent = UiPalette.accent(1f)
    val acR = (lpAccent ushr 16) and 0xFF
    val acG = (lpAccent ushr 8) and 0xFF
    val acB = lpAccent and 0xFF
    expect(acR >= 240 && acG >= 240 && acB >= 240, "ClickGUI accent must be frost-white, got $acR,$acG,$acB")
    expect(abs(acR - acB) <= 15, "ClickGUI accent must be neutral white, not pink")

    // 2. ClickGuiGlassSurface: BLOOM_PANEL vs BLOOM_CAPSULE channel encoding & exterior bloom bounds
    val ctx = GuiGraphicsExtractor()
    ClickGuiGlassSurface.drawElevatedPanel(ctx, 100f, 80f, 250f, 420f, rTL = 6f)
    val panelEl = ctx.guiRenderState.elements.last() as ClickGuiGlassGuiElementRenderState
    expect(panelEl.referenceGlass && panelEl.referenceProfile == 0, "Panel must route to Profile.PANEL (0)")
    expect((panelEl.colorTL and 0xFF) == 160, "BLOOM_PANEL variant byte must be 160")
    expect((panelEl.shadowColor and 0xFF) == 160, "BLOOM_PANEL shadow variant byte must be 160")
    expect(((panelEl.shadowColor ushr 24) and 0xFF) > 0 && panelEl.shadowSpread >= 11f, "Panel must emit exterior white bloom")
    expect(panelEl.radiusTL == 6f, "Panel 6px corner radius preserved")

    ClickGuiGlassSurface.drawAccentCapsule(ctx, 104f, 127f, 242f, 28f, radius = 4.5f, shadowSpread = 0f)
    val capEl = ctx.guiRenderState.elements.last() as ClickGuiGlassGuiElementRenderState
    expect(capEl.referenceGlass && capEl.referenceProfile == 0, "Capsule must route to Profile.PANEL (0)")
    expect((capEl.colorTL and 0xFF) == 242, "BLOOM_CAPSULE variant byte must be 242")
    expect((capEl.shadowColor and 0xFF) == 242, "BLOOM_CAPSULE shadow variant byte must be 242")
    expect(((capEl.shadowColor ushr 24) and 0xFF) > 0 && capEl.shadowSpread >= 8.5f,
        "Capsule must emit exterior white bloom even when caller passes shadowSpread=0f")

    // 3. SwitchWidget in LightPollutionGlass emits glowing white thumb shadow
    val beforeSwitch = ctx.guiRenderState.elements.size
    SwitchWidget.render(ctx, 120f, 200f, 200f, "show-in-arraylist", 1.0f)
    val switchThumb = ctx.guiRenderState.elements.last() as CssBoxGuiElementRenderState
    expect(ctx.guiRenderState.elements.size >= beforeSwitch + 2, "Switch track + thumb emitted")
    expect(((switchThumb.shadowColor ushr 24) and 0xFF) > 80 && switchThumb.shadowBlur > 4f,
        "Checked switch thumb must emit outward white glow")
    expect((switchThumb.shadowColor and 0x00FFFFFF) == 0x00FFFFFF, "Switch thumb glow must be pure white")
    expect(switchThumb.x1 > 295f, "SwitchWidget right-aligned in LightPollutionGlass: x1=${switchThumb.x1}")
    // 3b. Verbatim SingleSlider, RangeSlider & ClickGuiTabView row/header blocks in LightPollutionGlass
    val beforeSlider = ctx.guiRenderState.elements.size
    ExtractedControlsHost.renderSingleSlider(ctx, 120f, 240f, 180f, 2f, 0.65f)
    val sliderKnob = ctx.guiRenderState.elements.last() as CssBoxGuiElementRenderState
    expect(ctx.guiRenderState.elements.size == beforeSlider + 3, "SingleSlider emits track + fill + vertical pill")
    expect(abs((sliderKnob.x1 - sliderKnob.x0) - 6f) < 0.01f && abs((sliderKnob.y1 - sliderKnob.y0) - 13f) < 0.01f,
        "SingleSlider handle is a 6x13 vertical rounded pill")
    expect(((sliderKnob.shadowColor ushr 24) and 0xFF) > 120 && sliderKnob.shadowBlur > 4f,
        "SingleSlider pill handle emits bright white glow")

    ExtractedControlsHost.renderRangeSlider(ctx, 120f, 280f, 180f, 2f, 0.25f, 0.75f)
    val rangeKnob = ctx.guiRenderState.elements.last() as CssBoxGuiElementRenderState
    expect(abs((rangeKnob.x1 - rangeKnob.x0) - 6f) < 0.01f && ((rangeKnob.shadowColor ushr 24) and 0xFF) > 120,
        "RangeSlider pill handles emit bright white glow")

    ExtractedControlsHost.renderPanelShell(ctx, true, 150f, 80f, 250f, 38f, 300f, 338f)
    ExtractedControlsHost.renderModuleRowGlass(ctx, true, 150f, 124f, 250f, 34f, ExtractedControlsHost.RowStub(1f, 0f))
    val rowCapEl = ctx.guiRenderState.elements.last() as ClickGuiGlassGuiElementRenderState
    expect((rowCapEl.colorTL and 0xFF) == 242 && rowCapEl.radiusTL == 4.5f,
        "Enabled ClickGuiTabView module row emits full-width BLOOM_CAPSULE (no 2.5px pink stripe)")

    ClickGuiGlassSurface.endFrame()

    // 4. Global ModernGUI LightPollutionGlass theme, elevated container, active/hovered widget pills, and slot hover
    ModuleModernGui.isLightPollutionGlass = true
    ModuleModernGui.usesGlass = true
    val theme = ModernGuiSurface.resolveTheme(null)
    val pR = (theme.primary ushr 16) and 0xFF
    val pG = (theme.primary ushr 8) and 0xFF
    val pB = theme.primary and 0xFF
    expect(pR >= 240 && pG >= 240 && pB >= 240, "ModernGUI primary must be frost-white, got $pR,$pG,$pB")

    val mCtx = GuiGraphicsExtractor()
    val setup = TextureSetup()
    ModernGuiSurface.drawElevatedCard(mCtx, theme, setup, 40f, 40f, 320f, 220f, radius = 10f)
    val mCard = mCtx.guiRenderState.elements.first() as ClickGuiGlassGuiElementRenderState
    expect(mCard.referenceGlass && mCard.referenceProfile == 0 && (mCard.colorTL and 0xFF) == 160,
        "ModernGUI container card must use BLOOM_PANEL")

    ModernGuiSurface.drawWidgetPill(mCtx, theme, setup, 60f, 80f, 160f, 22f, hoveredOrFocused = false, selected = false)
    val normalBtn = mCtx.guiRenderState.elements.last() as ClickGuiGlassGuiElementRenderState
    expect((normalBtn.colorTL and 0xFF) == 160, "Unselected ModernGUI button uses BLOOM_PANEL")

    ModernGuiSurface.drawWidgetPill(mCtx, theme, setup, 60f, 110f, 160f, 22f, hoveredOrFocused = true, selected = false)
    val hoverBtn = mCtx.guiRenderState.elements.last() as ClickGuiGlassGuiElementRenderState
    expect((hoverBtn.colorTL and 0xFF) == 242, "Hovered ModernGUI button upgrades to BLOOM_CAPSULE")

    ModernGuiSurface.drawWidgetPill(mCtx, theme, setup, 60f, 140f, 160f, 22f, hoveredOrFocused = false, selected = true)
    val selBtn = mCtx.guiRenderState.elements.last() as ClickGuiGlassGuiElementRenderState
    expect((selBtn.colorTL and 0xFF) == 242, "Selected ModernGUI tab/button upgrades to BLOOM_CAPSULE")

    ModernGuiSurface.drawWidgetPill(mCtx, theme, setup, 60f, 170f, 160f, 22f, disabled = true)
    val disBtn = mCtx.guiRenderState.elements.last() as ClickGuiGlassGuiElementRenderState
    expect(disBtn.shadowColor == 0 && disBtn.shadowSpread == 0f, "Disabled ModernGUI button suppresses exterior bloom")

    ModernGuiSurface.drawSlotWell(mCtx, theme, 80f, 200f, hovered = true, hasItem = true)
    val hovSlot = mCtx.guiRenderState.elements.last() as CssBoxGuiElementRenderState
    expect(((hovSlot.shadowColor ushr 24) and 0xFF) > 40 && hovSlot.shadowBlur > 3f,
        "Hovered inventory slot emits soft white glow")

    // 5. 089 HUD profiles (TARGET=1, COUNTER=2, SOFT=3) and 094 standalone musicPanelCard (QUIET) untouched!
    for (prof in listOf(
        LightPollutionGlassSurface.Profile.TARGET,
        LightPollutionGlassSurface.Profile.COUNTER,
        LightPollutionGlassSurface.Profile.SOFT,
    )) {
        LightPollutionGlassSurface.draw(
            mCtx, 10f, 10f, 180f, 56f, 14f, 14f, 14f, 14f,
            0x80000000.toInt(), 0x80000000.toInt(), 0x80000000.toInt(), 0x80000000.toInt(),
            1f, 1f, 1f, 0x70000000, 10f, setup, prof
        )
        val hudEl = mCtx.guiRenderState.elements.last() as ClickGuiGlassGuiElementRenderState
        expect(hudEl.referenceProfile == prof.lane, "HUD profile lane preserved: ${prof.lane}")
        expect((hudEl.colorTL and 0xFF) == 32 && ((hudEl.colorTL ushr 8) and 0xFF) == 32,
            "HUD smoked tint 32 preserved verbatim")
        expect((hudEl.shadowColor and 0x00FFFFFF) == 0, "HUD shadow RGB is pure black (089 preserved)")
    }

    val onyxHost = OnyxUiHost(mCtx, scale = 1f, alpha = 1f)
    onyxHost.musicPanelCard(20f, 20f, 960f, 600f, 1f)
    val musicEl = mCtx.guiRenderState.elements.last() as ClickGuiGlassGuiElementRenderState
    expect(musicEl.referenceProfile == 0, "MusicScreen stays on Profile 0")
    expect((musicEl.colorTL and 0xFF) == 96 && ((musicEl.colorTL ushr 8) and 0xFF) == 96,
        "094 MusicScreen stays on QUIET B=96, G=96")
    expect(musicEl.rimColor == 0 && musicEl.rimWidth == 0f, "094 MusicScreen keeps zero rim")
    expect((musicEl.shadowColor and 0x00FFFFFF) == 0 && ((musicEl.shadowColor ushr 24) and 0xFF) <= 28,
        "094 MusicScreen keeps subtle black shadow <= 28")

    // 6. Graceful null-FBO fallback never leaks packed transport channels into CssBox RGB
    LightPollutionGlassSurface.draw(
        mCtx, 20f, 20f, 200f, 120f, 8f, 8f, 8f, 8f,
        0x80000000.toInt(), 0x80000000.toInt(), 0x80000000.toInt(), 0x80000000.toInt(),
        1f, 1f, 1f, 0x60000000, 14f, null,
        LightPollutionGlassSurface.Profile.PANEL,
        LightPollutionGlassSurface.GuiVariant.BLOOM_PANEL,
    )
    val fbEl = mCtx.guiRenderState.elements.last() as CssBoxGuiElementRenderState
    expect((fbEl.colorTL and 0x00FFFFFF) == 0x5E6470, "Null-FBO fallback uses legible slate frost tint, not raw transport")
    expect((fbEl.shadowColor and 0x00FFFFFF) == 0xFFFFFF, "Null-FBO fallback emits soft white CssBox glow")

    // 7. Non-finite / zero-size safety across all variants
    val countBeforeBad = mCtx.guiRenderState.elements.size
    for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -5f)) {
        LightPollutionGlassSurface.draw(
            mCtx, 0f, 0f, bad, 40f, 6f, 6f, 6f, 6f,
            0x80000000.toInt(), 0x80000000.toInt(), 0x80000000.toInt(), 0x80000000.toInt(),
            1f, 1f, 1f, 0x50000000, 12f, setup
        )
    }
    expect(mCtx.guiRenderState.elements.size == countBeforeBad, "Invalid dimensions safely rejected")

    println("PASS: $checks JVM assertions across real compiled LightPollutionGlassSurface, ClickGuiGlassSurface, UiPalette, ModernGuiSurface, SwitchWidget, and OnyxUI.")
}
