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
@file:Suppress(
    "LongParameterList",
    "TooManyFunctions",
    "CognitiveComplexMethod",
    "ReturnCount",
    "ComplexCondition",
    "NamedArguments",
    "NestedBlockDepth",
    "MaxLineLength",
)

package net.ccbluex.liquidbounce.ui.clickgui.glass

import net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui
import net.ccbluex.liquidbounce.render.copyPosePooled
import net.ccbluex.liquidbounce.render.getBounds
import net.ccbluex.liquidbounce.render.gui.element.ClickGuiGlassGuiElementRenderState
import net.ccbluex.liquidbounce.ui.common.UiDraw
import net.ccbluex.liquidbounce.ui.common.UiPalette
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import kotlin.math.roundToInt

/**
 * High-level fused `blur + physical glass + shadow` surface renderer for Native ClickGUI Glass Mode.
 */
object ClickGuiGlassSurface {

    @Volatile
    var isRenderingGlassFrame: Boolean = false
        private set

    private var frameTextureSetup: TextureSetup? = null

    val currentBackdropTexture: TextureSetup?
        get() = frameTextureSetup

    val isActive: Boolean
        get() = isRenderingGlassFrame && ModuleClickGui.isGlassStyle

    fun beginFrame(): Boolean {
        if (!ModuleClickGui.isGlassStyle) {
            isRenderingGlassFrame = false
            frameTextureSetup = null
            return false
        }
        frameTextureSetup = if (ModuleClickGui.isLightPollutionGlass) {
            ClickGuiGlassBackdrop.request(
                (ModuleClickGui.glassBlurValue * 1.15f).coerceIn(0.20f, 3.0f),
                vibrance = 0.96f,
            )
        } else {
            ClickGuiGlassBackdrop.request(ModuleClickGui.glassBlurValue)
        }
        isRenderingGlassFrame = true
        return true
    }

    fun endFrame() {
        isRenderingGlassFrame = false
        frameTextureSetup = null
    }

    fun drawGlassBox(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        rTL: Float,
        rTR: Float = rTL,
        rBR: Float = rTL,
        rBL: Float = rTL,
        cTL: Int,
        cTR: Int = cTL,
        cBR: Int = cTL,
        cBL: Int = cTL,
        layerOpacity: Float = 1f,
        refractionStrength: Float = ModuleClickGui.glassRefractionValue,
        rimColor: Int = 0,
        rimWidth: Float = 1.15f,
        shadowColor: Int = 0,
        shadowSpread: Float = 0f,
        variant: LightPollutionGlassSurface.GuiVariant = LightPollutionGlassSurface.GuiVariant.BLOOM_PANEL,
    ) {
        if (w <= 0f || h <= 0f || layerOpacity <= 0.004f) return

        val gaussian = ModuleClickGui.isGaussianGlass
        if (gaussian || ModuleClickGui.isLightPollutionGlass) {
            LightPollutionGlassSurface.draw(
                ctx, x, y, w, h, rTL, rTR, rBR, rBL,
                cTL, cTR, cBR, cBL, layerOpacity, refractionStrength,
                ModuleClickGui.glassRimValue,
                scaleAlpha(shadowColor, ModuleClickGui.glassShadowValue), shadowSpread, frameTextureSetup,
                profile = LightPollutionGlassSurface.Profile.PANEL,
                // 098 GaussianGlass: every surface (panels, capsules, wells) is the quiet Gaussian card.
                variant = if (gaussian) LightPollutionGlassSurface.GuiVariant.QUIET else variant,
            )
            return
        }
        val scaledRim = scaleAlpha(rimColor, ModuleClickGui.glassRimValue * layerOpacity)
        val scaledShadow = scaleAlpha(shadowColor, ModuleClickGui.glassShadowValue * layerOpacity)
        val setup = frameTextureSetup
        if (setup == null) {
            // Graceful fallback if FBO allocation is unavailable
            UiDraw.fillCssBox(
                ctx = ctx,
                x = x, y = y, w = w, h = h,
                rTL = rTL, rTR = rTR, rBR = rBR, rBL = rBL,
                cTL = scaleAlpha(cTL, (layerOpacity * 1.45f).coerceAtMost(0.92f)),
                cTR = scaleAlpha(cTR, (layerOpacity * 1.45f).coerceAtMost(0.92f)),
                cBR = scaleAlpha(cBR, (layerOpacity * 1.45f).coerceAtMost(0.92f)),
                cBL = scaleAlpha(cBL, (layerOpacity * 1.45f).coerceAtMost(0.92f)),
                borderColor = scaledRim,
                borderWidth = rimWidth,
                shadowColor = scaledShadow,
                shadowBlur = shadowSpread,
            )
            return
        }

        val pad = if ((scaledShadow ushr 24) != 0 && shadowSpread > 0.05f) shadowSpread * 2.5f + 4f else 0f
        val bounds = ctx.getBounds(x - pad, y - pad, x + w + pad, y + h + pad)
        ctx.guiRenderState.addGuiElement(
            ClickGuiGlassGuiElementRenderState(
                x, y, x + w, y + h,
                rTL, rTR, rBR, rBL,
                cTL, cTR, cBR, cBL,
                layerOpacity.coerceIn(0f, 1f),
                refractionStrength.coerceIn(0f, 3f),
                scaledRim,
                rimWidth,
                scaledShadow,
                shadowSpread,
                false,
                0,
                setup,
                ctx.copyPosePooled(),
                ctx.scissorStack.peek(),
                bounds,
            )
        )
    }

    /**
     * Primary floating crystal slab (Category Panels, Search Bar, Top Tabs Pill, Global Settings Window,
     * HUD Component Drawer, Component Settings Popup, Dropdown Portals).
     */
    fun drawElevatedPanel(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        rTL: Float,
        rTR: Float = rTL,
        rBR: Float = rTL,
        rBL: Float = rTL,
        densityBoost: Float = 1.0f,
        shadowSpread: Float = 14f,
        shadowAlpha: Float = 0.50f,
        accentRimMix: Float = 0.22f,
        rimAlpha: Float = 0.44f,
        refractionScale: Float = 1.0f,
    ) {
        val baseDensity = (ModuleClickGui.glassTintOpacityValue * densityBoost).coerceIn(0.08f, 0.88f)
        val surf = UiPalette.surfaceBase
        val ac = UiPalette.accentColor

        // Top-left cool crystal highlight tint -> bottom-right deeper smoked crystal tint
        val topTint = UiPalette.packArgb(
            (baseDensity * 0.90f * 255f).roundToInt().coerceIn(0, 255),
            (surf.r + 22 + (ac.r * 0.08f).roundToInt()).coerceIn(0, 255),
            (surf.g + 26 + (ac.g * 0.08f).roundToInt()).coerceIn(0, 255),
            (surf.b + 34 + (ac.b * 0.10f).roundToInt()).coerceIn(0, 255),
        )
        val botTint = UiPalette.packArgb(
            (baseDensity * 1.12f * 255f).roundToInt().coerceIn(0, 255),
            (surf.r + 8).coerceIn(0, 255),
            (surf.g + 10).coerceIn(0, 255),
            (surf.b + 16).coerceIn(0, 255),
        )
        val midTint = UiPalette.lerpArgb(topTint, botTint, 0.5f)
        val rimCol = UiPalette.lerpArgb(UiPalette.white(rimAlpha), UiPalette.accent(rimAlpha), accentRimMix.coerceIn(0f, 1f))

        drawGlassBox(
            ctx = ctx,
            x = x, y = y, w = w, h = h,
            rTL = rTL, rTR = rTR, rBR = rBR, rBL = rBL,
            cTL = topTint, cTR = midTint, cBR = botTint, cBL = midTint,
            layerOpacity = 1f,
            refractionStrength = ModuleClickGui.glassRefractionValue * refractionScale,
            rimColor = rimCol,
            rimWidth = 1.15f,
            shadowColor = UiPalette.black(shadowAlpha),
            shadowSpread = shadowSpread,
        )
    }

    /**
     * Luminous accent-infused Liquid Glass capsule (Active Tab, Enabled/Hovered Module Row,
     * `+ Add Component` Pill, Glass Tooltip).
     */
    fun drawAccentCapsule(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        radius: Float,
        accentMix: Float = 0.85f,
        tintDensity: Float = 0.44f,
        layerOpacity: Float = 1f,
        rimAlpha: Float = 0.72f,
        shadowSpread: Float = 6f,
        shadowAlpha: Float = 0.28f,
    ) {
        if (layerOpacity <= 0.005f) return
        val ac = UiPalette.accentColor
        val surf = UiPalette.surfaceBase
        val mixT = accentMix.coerceIn(0f, 1f)

        val rTop = ((surf.r + 36) * (1f - mixT) + (ac.r + 24).coerceAtMost(255) * mixT).roundToInt().coerceIn(0, 255)
        val gTop = ((surf.g + 40) * (1f - mixT) + (ac.g + 24).coerceAtMost(255) * mixT).roundToInt().coerceIn(0, 255)
        val bTop = ((surf.b + 48) * (1f - mixT) + (ac.b + 28).coerceAtMost(255) * mixT).roundToInt().coerceIn(0, 255)

        val rBot = ((surf.r + 18) * (1f - mixT) + (ac.r * 0.84f).roundToInt() * mixT).roundToInt().coerceIn(0, 255)
        val gBot = ((surf.g + 20) * (1f - mixT) + (ac.g * 0.84f).roundToInt() * mixT).roundToInt().coerceIn(0, 255)
        val bBot = ((surf.b + 26) * (1f - mixT) + (ac.b * 0.88f).roundToInt() * mixT).roundToInt().coerceIn(0, 255)

        val density = (tintDensity * (0.65f + 0.55f * ModuleClickGui.glassTintOpacityValue)).coerceIn(0.12f, 0.86f)
        val alphaInt = (density * 255f).roundToInt().coerceIn(1, 255)
        val cTop = UiPalette.packArgb(alphaInt, rTop, gTop, bTop)
        val cBot = UiPalette.packArgb((alphaInt * 1.08f).roundToInt().coerceIn(1, 255), rBot, gBot, bBot)
        val cMid = UiPalette.lerpArgb(cTop, cBot, 0.5f)
        val rimCol = UiPalette.lerpArgb(UiPalette.white(rimAlpha), UiPalette.accent(rimAlpha), mixT * 0.65f)

        drawGlassBox(
            ctx = ctx,
            x = x, y = y, w = w, h = h,
            rTL = radius, rTR = radius, rBR = radius, rBL = radius,
            cTL = cTop, cTR = cMid, cBR = cBot, cBL = cMid,
            layerOpacity = layerOpacity.coerceIn(0f, 1f),
            refractionStrength = ModuleClickGui.glassRefractionValue * 0.72f,
            rimColor = rimCol,
            rimWidth = 1.1f,
            shadowColor = if (shadowSpread > 0.1f) UiPalette.lerpArgb(UiPalette.black(shadowAlpha), UiPalette.accent(shadowAlpha * 0.65f), mixT * 0.45f) else 0,
            shadowSpread = shadowSpread,
            variant = LightPollutionGlassSurface.GuiVariant.BLOOM_CAPSULE,
        )
    }

    /**
     * Softly recessed crystal well inside a Glass panel (expanded module settings well, header glaze,
     * Global Settings sub-cards).
     */
    fun drawSubtleWell(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        rTL: Float = 6f,
        rTR: Float = rTL,
        rBR: Float = rTL,
        rBL: Float = rTL,
        darknessAlpha: Float = 0.24f,
        borderAlpha: Float = 0.10f,
    ) {
        if (ModuleClickGui.isGaussianGlass) {
            // 098 quiet well: a flat dim tray, no rim and no bloom.
            UiDraw.fillBox(ctx, x, y, w, h, UiPalette.black(darknessAlpha * 0.35f), radius = 3f)
            return
        }
        if (ModuleClickGui.isLightPollutionGlass) {
            val rimScale = ModuleClickGui.glassRimValue.coerceIn(0f, 1.6f)
            UiDraw.fillCssBox(
                ctx = ctx,
                x = x, y = y, w = w, h = h,
                rTL = rTL.coerceAtMost(6f), rTR = rTR.coerceAtMost(6f),
                rBR = rBR.coerceAtMost(6f), rBL = rBL.coerceAtMost(6f),
                cTL = UiPalette.black(darknessAlpha * 0.46f),
                cTR = UiPalette.black(darknessAlpha * 0.50f),
                cBR = UiPalette.black(darknessAlpha * 0.58f),
                cBL = UiPalette.black(darknessAlpha * 0.52f),
                borderColor = UiPalette.white((borderAlpha * 0.72f * rimScale).coerceIn(0f, 0.24f)),
                borderWidth = 0.75f,
                shadowColor = UiPalette.white((0.10f * rimScale).coerceIn(0f, 0.22f)),
                shadowBlur = 4.2f,
            )
            return
        }
        UiDraw.fillCssBox(
            ctx = ctx,
            x = x, y = y, w = w, h = h,
            rTL = rTL, rTR = rTR, rBR = rBR, rBL = rBL,
            cTL = UiPalette.black(darknessAlpha * 0.85f),
            cTR = UiPalette.black(darknessAlpha * 0.92f),
            cBR = UiPalette.black(darknessAlpha * 1.08f),
            cBL = UiPalette.black(darknessAlpha),
            borderColor = UiPalette.white(borderAlpha * ModuleClickGui.glassRimValue.coerceIn(0f, 1.5f)),
            borderWidth = 0.9f,
        )
    }

    /**
     * Luminous glass accent light-bar with soft ambient bloom.
     */
    fun drawGlowingAccentBar(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        h: Float = 1.8f,
    ) {
        // 098 quiet Gaussian glass has no decorative light bar; enabled-row indicators stay functional.
        if (ModuleClickGui.isGaussianGlass) return
        val inset = 1f
        val barW = (w - inset * 2f).coerceAtLeast(2f)
        if (ModuleClickGui.isLightPollutionGlass) {
            val glowScale = ModuleClickGui.glassRimValue.coerceIn(0f, 1.6f)
            if (glowScale <= 0.01f) return
            val centerCol = UiPalette.white(0.28f * glowScale)
            val edgeCol = UiPalette.white(0.05f * glowScale)
            UiDraw.fillCssBox(
                ctx = ctx,
                x = x + inset, y = y, w = barW, h = h,
                rTL = h * 0.5f, rTR = h * 0.5f, rBR = h * 0.5f, rBL = h * 0.5f,
                cTL = edgeCol, cTR = centerCol, cBR = edgeCol, cBL = centerCol,
                shadowColor = UiPalette.white(0.24f * glowScale * ModuleClickGui.glassShadowValue.coerceIn(0f, 1.5f)),
                shadowBlur = 5.2f,
            )
            return
        }
        val centerCol = UiPalette.lerpArgb(UiPalette.accent(0.95f), UiPalette.white(0.95f), 0.28f)
        val edgeCol = UiPalette.accent(0.55f)
        UiDraw.fillCssBox(
            ctx = ctx,
            x = x + inset, y = y, w = barW, h = h,
            rTL = h * 0.5f, rTR = h * 0.5f, rBR = h * 0.5f, rBL = h * 0.5f,
            cTL = edgeCol, cTR = centerCol, cBR = edgeCol, cBL = centerCol,
            shadowColor = UiPalette.accent(0.42f * ModuleClickGui.glassShadowValue.coerceIn(0f, 1.5f)),
            shadowBlur = 4.5f,
        )
    }

    private fun scaleAlpha(argb: Int, factor: Float): Int {
        if (argb == 0) return 0
        val a = (argb ushr 24) and 0xFF
        if (a == 0) return 0
        val outA = (a * factor.coerceAtLeast(0f)).roundToInt().coerceIn(0, 255)
        return (argb and 0x00FFFFFF) or (outA shl 24)
    }
}
