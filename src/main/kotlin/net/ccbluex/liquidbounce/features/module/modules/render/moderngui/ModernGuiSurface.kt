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
    "TooManyFunctions",
    "LongMethod",
    "LongParameterList",
    "CognitiveComplexMethod",
    "ReturnCount",
    "ComplexCondition",
    "NamedArguments",
    "BracesOnIfStatements",
    "NestedBlockDepth",
    "UnusedParameter",
    "MaxLineLength",
)

package net.ccbluex.liquidbounce.features.module.modules.render.moderngui

import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Material
import net.ccbluex.liquidbounce.render.copyPosePooled
import net.ccbluex.liquidbounce.render.getBounds
import net.ccbluex.liquidbounce.render.gui.element.ClickGuiGlassGuiElementRenderState
import net.ccbluex.liquidbounce.render.gui.element.CssBoxGuiElementRenderState
import net.ccbluex.liquidbounce.ui.clickgui.glass.LightPollutionGlassSurface
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.util.ARGB
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Resolved per-frame colour tokens shared by both Liquid Glass and Solid/Translucent Card modes.
 */
data class ResolvedGuiTheme(
    val isLight: Boolean,
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val secondaryContainer: Int,
    val surfaceLowest: Int,
    val surfaceLow: Int,
    val surface: Int,
    val surfaceHigh: Int,
    val surfaceHighest: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val borderBase: Int,
    val borderSubtle: Int,
    val scrim: Int,
)

/**
 * Low-level rounded card & optical Liquid Glass emitter for all upgraded in-game GUIs.
 */
object ModernGuiSurface {

    fun resolveTheme(monetPalette: MonetPalette?): ResolvedGuiTheme {
        if (ModuleModernGui.isLightPollutionGlass) {
            return ResolvedGuiTheme(
                isLight = false,
                primary = 0xFFF4F7FF.toInt(), onPrimary = 0xFF1A1E26.toInt(),
                primaryContainer = 0xFF68707D.toInt(), secondaryContainer = 0xFF5A606B.toInt(),
                surfaceLowest = 0xFF4C515A.toInt(), surfaceLow = 0xFF6B717C.toInt(),
                surface = 0xFF7E8591.toInt(), surfaceHigh = 0xFF949BA7.toInt(),
                surfaceHighest = 0xFFAAB1BD.toInt(), textPrimary = 0xFFFFFFFF.toInt(),
                textSecondary = 0xFFD2D8E3.toInt(), borderBase = 0xFFFFFFFF.toInt(),
                borderSubtle = 0xFFCFD6E2.toInt(), scrim = 0xFF000000.toInt(),
            )
        }
        if (monetPalette != null) {
            return ResolvedGuiTheme(
                isLight = monetPalette.isLight,
                primary = monetPalette.primary,
                onPrimary = monetPalette.onPrimary,
                primaryContainer = monetPalette.primaryContainer,
                secondaryContainer = monetPalette.secondaryContainer,
                surfaceLowest = monetPalette.surfaceLowest,
                surfaceLow = monetPalette.surfaceLow,
                surface = monetPalette.surface,
                surfaceHigh = monetPalette.surfaceHigh,
                surfaceHighest = monetPalette.surfaceHighest,
                textPrimary = monetPalette.onSurface,
                textSecondary = monetPalette.onSurfaceVariant,
                borderBase = monetPalette.outline,
                borderSubtle = monetPalette.outlineVariant,
                scrim = monetPalette.scrim,
            )
        }

        val surf = ModuleModernGui.CustomColors.surfaceColor.argb
        val acc = ModuleModernGui.CustomColors.accentColor.argb
        val brd = ModuleModernGui.CustomColors.borderColor.argb
        val light = Material.lstarFromArgb(surf) > 58.0

        val low = Material.mix(surf, 0xFF000000.toInt(), 0.22f)
        val lowest = Material.mix(surf, 0xFF000000.toInt(), 0.42f)
        val high = Material.mix(surf, 0xFFFFFFFF.toInt(), if (light) 0.14f else 0.08f)
        val highest = Material.mix(surf, 0xFFFFFFFF.toInt(), if (light) 0.24f else 0.15f)
        val primContainer = Material.mix(surf, acc, 0.38f)

        return ResolvedGuiTheme(
            isLight = light,
            primary = ARGB.opaque(acc),
            onPrimary = if (Material.lstarFromArgb(acc) > 60.0) 0xFF10141C.toInt() else 0xFFFFFFFF.toInt(),
            primaryContainer = ARGB.opaque(primContainer),
            secondaryContainer = ARGB.opaque(Material.mix(surf, acc, 0.22f)),
            surfaceLowest = ARGB.opaque(lowest),
            surfaceLow = ARGB.opaque(low),
            surface = ARGB.opaque(surf),
            surfaceHigh = ARGB.opaque(high),
            surfaceHighest = ARGB.opaque(highest),
            textPrimary = if (light) 0xFF1A1E26.toInt() else 0xFFECF0F8.toInt(),
            textSecondary = if (light) 0xFF565E6E.toInt() else 0xFFAAB4C8.toInt(),
            borderBase = ARGB.opaque(brd),
            borderSubtle = ARGB.opaque(Material.mix(surf, brd, 0.45f)),
            scrim = 0xFF05070C.toInt(),
        )
    }

    /**
     * Emits a unified rounded surface (Liquid Glass or Monet/Custom Card) directly into [GuiGraphicsExtractor.guiRenderState].
     */
    fun emitSurface(
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
        borderColor: Int = 0,
        borderWidth: Float = 0f,
        shadowColor: Int = 0,
        shadowSpread: Float = 0f,
        refractionScale: Float = 1f,
        layerOpacity: Float = 1f,
        glassSetup: TextureSetup? = null,
        advanceStratum: Boolean = false,
        referenceProfile: LightPollutionGlassSurface.Profile = LightPollutionGlassSurface.Profile.PANEL,
        lightPollutionVariant: LightPollutionGlassSurface.GuiVariant = LightPollutionGlassSurface.GuiVariant.BLOOM_PANEL,
        suppressBloom: Boolean = false,
    ) {
        if (w <= 0.5f || h <= 0.5f || layerOpacity <= 0.005f) return

        if (ModuleModernGui.isLightPollutionGlass) {
            net.ccbluex.liquidbounce.ui.clickgui.glass.LightPollutionGlassSurface.draw(
                ctx, x, y, w, h, rTL, rTR, rBR, rBL,
                cTL, cTR, cBR, cBL, layerOpacity,
                ModuleModernGui.GlassOptics.refraction * refractionScale,
                if (suppressBloom) 0f else ModuleModernGui.GlassOptics.specularRim,
                shadowColor, shadowSpread, glassSetup,
                referenceProfile,
                lightPollutionVariant,
            )
            if (advanceStratum) ctx.nextStratum()
            return
        }
        val maxR = min(w, h) * 0.5f
        val crTL = rTL.coerceIn(0f, maxR)
        val crTR = rTR.coerceIn(0f, maxR)
        val crBR = rBR.coerceIn(0f, maxR)
        val crBL = rBL.coerceIn(0f, maxR)

        val useGlass = ModuleModernGui.usesGlass && glassSetup != null
        if (useGlass) {
            val rimAlphaScale = ModuleModernGui.GlassOptics.specularRim * layerOpacity
            val scaledRim = scaleAlpha(borderColor, rimAlphaScale)
            val scaledShadow = scaleAlpha(shadowColor, layerOpacity)
            val refract = (ModuleModernGui.GlassOptics.refraction * refractionScale).coerceIn(0f, 3f)
            val pad = if ((scaledShadow ushr 24) != 0 && shadowSpread > 0.05f) shadowSpread * 2.5f + 4f else 0f
            val bounds = ctx.getBounds(x - pad, y - pad, x + w + pad, y + h + pad)

            ctx.guiRenderState.addGuiElement(
                ClickGuiGlassGuiElementRenderState(
                    x, y, x + w, y + h,
                    crTL, crTR, crBR, crBL,
                    cTL, cTR, cBR, cBL,
                    layerOpacity.coerceIn(0f, 1f),
                    refract,
                    scaledRim,
                    borderWidth,
                    scaledShadow,
                    shadowSpread,
                    false,
                    0,
                    glassSetup,
                    ctx.copyPosePooled(),
                    ctx.scissorStack.peek(),
                    bounds,
                )
            )
        } else {
            // Card mode (or graceful Glass fallback when world FBO is not active on out-of-world screens)
            val opacityBoost = if (ModuleModernGui.usesGlass) 1.55f else 1.85f
            val fTL = boostCardAlpha(cTL, opacityBoost, layerOpacity)
            val fTR = boostCardAlpha(cTR, opacityBoost, layerOpacity)
            val fBR = boostCardAlpha(cBR, opacityBoost, layerOpacity)
            val fBL = boostCardAlpha(cBL, opacityBoost, layerOpacity)
            val fBorder = scaleAlpha(borderColor, layerOpacity)
            val fShadow = scaleAlpha(shadowColor, layerOpacity)
            val pad = if ((fShadow ushr 24) != 0 && shadowSpread > 0.05f) shadowSpread * 2.2f else 0f
            val bounds = ctx.getBounds(x - pad, y - pad, x + w + pad, y + h + pad)

            ctx.guiRenderState.addGuiElement(
                CssBoxGuiElementRenderState(
                    x, y, x + w, y + h,
                    crTL, crTR, crBR, crBL,
                    fTL, fTR, fBR, fBL,
                    fBorder, borderWidth,
                    fShadow, shadowSpread,
                    ctx.copyPosePooled(),
                    ctx.scissorStack.peek(),
                    bounds,
                )
            )
        }

        if (advanceStratum) {
            ctx.nextStratum()
        }
    }

    /**
     * Primary floating container / menu / HUD / scoreboard / tab-list card.
     */
    fun drawElevatedCard(
        ctx: GuiGraphicsExtractor,
        theme: ResolvedGuiTheme,
        glassSetup: TextureSetup?,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        radius: Float = ModuleModernGui.CardDesign.panelRadius,
        opacityMultiplier: Float = 1f,
        shadowMultiplier: Float = 1f,
        accentMix: Float = 0.10f,
        refractionScale: Float = 1f,
        advanceStratum: Boolean = true,
        drawHeaderLine: Boolean = false,
        headerOffsetY: Float = 20f,
    ) {
        val baseOpacity = (ModuleModernGui.CardDesign.cardOpacity * opacityMultiplier).coerceIn(0.06f, 0.96f)
        val topBase = Material.mix(theme.surfaceHigh, theme.primaryContainer, accentMix.coerceIn(0f, 1f))
        val botBase = theme.surfaceLow
        val midBase = Material.mix(topBase, botBase, 0.5f)

        val cTL = withAlpha(topBase, (baseOpacity * 0.94f).coerceIn(0.04f, 0.98f))
        val cBR = withAlpha(botBase, (baseOpacity * 1.08f).coerceIn(0.05f, 0.98f))
        val cMid = withAlpha(midBase, baseOpacity)

        val rimMix = if (ModuleModernGui.usesGlass) ModuleModernGui.GlassOptics.accentRimMix else 0.38f
        val rimBase = if (ModuleModernGui.usesGlass && !theme.isLight) {
            Material.mix(0xFFFFFFFF.toInt(), theme.primary, rimMix)
        } else {
            Material.mix(theme.borderBase, theme.primary, rimMix)
        }
        val borderCol = withAlpha(rimBase, ModuleModernGui.CardDesign.borderOpacity)
        val shadowCol = withAlpha(0xFF000000.toInt(), ModuleModernGui.CardDesign.shadowOpacity * shadowMultiplier)

        emitSurface(
            ctx = ctx,
            x = x, y = y, w = w, h = h,
            rTL = radius, rTR = radius, rBR = radius, rBL = radius,
            cTL = cTL, cTR = cMid, cBR = cBR, cBL = cMid,
            borderColor = borderCol,
            borderWidth = ModuleModernGui.CardDesign.borderWidth,
            shadowColor = shadowCol,
            shadowSpread = ModuleModernGui.CardDesign.shadowSpread * shadowMultiplier,
            refractionScale = refractionScale,
            layerOpacity = 1f,
            glassSetup = glassSetup,
            advanceStratum = false,
        )

        if (drawHeaderLine && h > headerOffsetY + 16f && w > 28f) {
            val lineW = w - 20f
            val lineX = x + 10f
            val lineY = y + headerOffsetY
            val lineCenter = withAlpha(theme.primary, 0.42f)
            val lineEdge = withAlpha(theme.primary, 0.04f)
            val bounds = ctx.getBounds(lineX, lineY, lineX + lineW, lineY + 1.2f)
            ctx.guiRenderState.addGuiElement(
                CssBoxGuiElementRenderState(
                    lineX, lineY, lineX + lineW, lineY + 1.1f,
                    0.5f, 0.5f, 0.5f, 0.5f,
                    lineEdge, lineCenter, lineEdge, lineCenter,
                    0, 0f,
                    0, 0f,
                    ctx.copyPosePooled(),
                    ctx.scissorStack.peek(),
                    bounds,
                )
            )
        }

        if (advanceStratum) {
            ctx.nextStratum()
        }
    }

    enum class TabEdge {
        NONE,
        TOP,
        BOTTOM,
        LEFT,
        RIGHT,
    }

    /**
     * Interactive rounded widget pill (Button, CycleButton, Slider, EditBox, Tab, Recipe/Enchantment/Beacon button).
     */
    fun drawWidgetPill(
        ctx: GuiGraphicsExtractor,
        theme: ResolvedGuiTheme,
        glassSetup: TextureSetup?,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        radius: Float = ModuleModernGui.CardDesign.widgetRadius,
        hoveredOrFocused: Boolean = false,
        selected: Boolean = false,
        disabled: Boolean = false,
        alphaScale: Float = 1f,
        topOnlyRound: Boolean = false,
        tabEdge: TabEdge = TabEdge.NONE,
        advanceStratum: Boolean = true,
    ) {
        val baseOp = ModuleModernGui.CardDesign.cardOpacity
        val op = when {
            disabled -> (baseOp * 0.52f).coerceIn(0.06f, 0.55f)
            selected -> (baseOp * 1.25f).coerceIn(0.18f, 0.92f)
            hoveredOrFocused -> (baseOp * 1.18f).coerceIn(0.16f, 0.90f)
            else -> (baseOp * 0.92f).coerceIn(0.10f, 0.86f)
        } * alphaScale

        val topRgb: Int
        val botRgb: Int
        val rimRgb: Int
        val rimOpacity: Float
        val shadowFactor: Float

        when {
            disabled -> {
                topRgb = theme.surfaceLow
                botRgb = theme.surfaceLowest
                rimRgb = theme.borderSubtle
                rimOpacity = ModuleModernGui.CardDesign.borderOpacity * 0.35f
                shadowFactor = 0.0f
            }
            selected || hoveredOrFocused -> {
                val mixAmt = if (selected) 0.68f else 0.48f
                topRgb = Material.mix(theme.surfaceHighest, theme.primary, mixAmt)
                botRgb = Material.mix(theme.surfaceHigh, theme.primaryContainer, mixAmt + 0.12f)
                rimRgb = Material.mix(0xFFFFFFFF.toInt(), theme.primary, 0.78f)
                rimOpacity = (ModuleModernGui.CardDesign.borderOpacity * 1.38f).coerceIn(0f, 0.98f)
                shadowFactor = 0.48f
            }
            else -> {
                topRgb = Material.mix(theme.surfaceHighest, theme.primaryContainer, 0.14f)
                botRgb = theme.surface
                rimRgb = Material.mix(
                    if (theme.isLight) theme.borderBase else 0xFFFFFFFF.toInt(),
                    theme.primary,
                    0.28f,
                )
                rimOpacity = ModuleModernGui.CardDesign.borderOpacity * 0.85f
                shadowFactor = 0.28f
            }
        }

        val resolvedEdge = if (tabEdge != TabEdge.NONE) {
            tabEdge
        } else if (topOnlyRound) {
            TabEdge.TOP
        } else {
            TabEdge.NONE
        }

        val rTL: Float
        val rTR: Float
        val rBR: Float
        val rBL: Float
        when (resolvedEdge) {
            TabEdge.NONE -> {
                rTL = radius
                rTR = radius
                rBR = radius
                rBL = radius
            }
            TabEdge.TOP -> {
                rTL = radius
                rTR = radius
                rBR = 0f
                rBL = 0f
            }
            TabEdge.BOTTOM -> {
                rTL = 0f
                rTR = 0f
                rBR = radius
                rBL = radius
            }
            TabEdge.LEFT -> {
                rTL = radius
                rTR = 0f
                rBR = 0f
                rBL = radius
            }
            TabEdge.RIGHT -> {
                rTL = 0f
                rTR = radius
                rBR = radius
                rBL = 0f
            }
        }

        val outerTop = if (resolvedEdge == TabEdge.BOTTOM) botRgb else topRgb
        val outerBot = if (resolvedEdge == TabEdge.BOTTOM) topRgb else botRgb
        val tabShadowFactor = if (resolvedEdge != TabEdge.NONE && !selected) shadowFactor * 0.45f else shadowFactor
        val lpVariant = if (!disabled && (selected || hoveredOrFocused)) {
            LightPollutionGlassSurface.GuiVariant.BLOOM_CAPSULE
        } else {
            LightPollutionGlassSurface.GuiVariant.BLOOM_PANEL
        }
        val effectiveLayerOpacity = if (disabled && ModuleModernGui.isLightPollutionGlass) {
            alphaScale * 0.60f
        } else {
            alphaScale
        }
        val pillShadowCol = if (disabled) {
            0
        } else {
            withAlpha(0xFF000000.toInt(), ModuleModernGui.CardDesign.shadowOpacity * tabShadowFactor * alphaScale)
        }

        emitSurface(
            ctx = ctx,
            x = x, y = y, w = w, h = h,
            rTL = rTL, rTR = rTR, rBR = rBR, rBL = rBL,
            cTL = withAlpha(outerTop, op),
            cTR = withAlpha(Material.mix(outerTop, outerBot, 0.45f), op),
            cBR = withAlpha(outerBot, (op * 1.08f).coerceAtMost(0.96f)),
            cBL = withAlpha(Material.mix(outerTop, outerBot, 0.55f), op),
            borderColor = withAlpha(rimRgb, rimOpacity * alphaScale),
            borderWidth = (ModuleModernGui.CardDesign.borderWidth * if (hoveredOrFocused || selected) 1.15f else 0.95f),
            shadowColor = pillShadowCol,
            shadowSpread = if (disabled) 0f else ModuleModernGui.CardDesign.shadowSpread * tabShadowFactor,
            refractionScale = if (hoveredOrFocused || selected) 0.88f else 0.68f,
            layerOpacity = effectiveLayerOpacity,
            glassSetup = glassSetup,
            advanceStratum = advanceStratum,
            lightPollutionVariant = lpVariant,
            suppressBloom = disabled,
        )
    }

    /**
     * Recessed inner groove/well for scrollbar tracks, 3D entity doll showcases, and inner sub-panels.
     */
    fun drawRecessedWell(
        ctx: GuiGraphicsExtractor,
        theme: ResolvedGuiTheme,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        radius: Float = ModuleModernGui.CardDesign.slotRadius,
        opacityScale: Float = 1f,
    ) {
        if (w <= 1f || h <= 1f) return
        val baseOp = (ModuleModernGui.CardDesign.slotOpacity * opacityScale).coerceIn(0.08f, 0.78f)
        val topCol = withAlpha(theme.surfaceLowest, baseOp)
        val botCol = withAlpha(theme.surfaceLow, (baseOp * 0.88f).coerceIn(0.06f, 0.75f))
        val borderCol = withAlpha(
            Material.mix(theme.borderSubtle, theme.primary, 0.14f),
            (ModuleModernGui.CardDesign.borderOpacity * 0.52f * opacityScale).coerceIn(0f, 0.68f),
        )
        val bWidth = (ModuleModernGui.CardDesign.borderWidth * 0.75f).coerceAtMost(1.0f)
        val r = min(radius, min(w, h) * 0.5f)
        val bounds = ctx.getBounds(x, y, x + w, y + h)
        ctx.guiRenderState.addGuiElement(
            CssBoxGuiElementRenderState(
                x, y, x + w, y + h,
                r, r, r, r,
                topCol, topCol, botCol, botCol,
                borderCol, bWidth,
                0, 0f,
                ctx.copyPosePooled(),
                ctx.scissorStack.peek(),
                bounds,
            )
        )
    }

    /**
     * Recessed rounded item-slot well + optional luminous hover highlight.
     */
    fun drawSlotWell(
        ctx: GuiGraphicsExtractor,
        theme: ResolvedGuiTheme,
        x: Float,
        y: Float,
        size: Float = 18f,
        hovered: Boolean = false,
        hasItem: Boolean = false,
    ) {
        val radius = ModuleModernGui.CardDesign.slotRadius
        val slotOp = ModuleModernGui.CardDesign.slotOpacity
        if (slotOp <= 0.005f && !hovered) return

        val fillRgb = when {
            hovered -> Material.mix(theme.primaryContainer, theme.primary, 0.52f)
            hasItem -> Material.mix(theme.surfaceLow, theme.primaryContainer, 0.16f)
            else -> theme.surfaceLowest
        }
        val fillAlpha = when {
            hovered -> (slotOp * 1.35f + 0.18f).coerceIn(0.24f, 0.78f)
            hasItem -> (slotOp * 0.92f).coerceIn(0f, 0.85f)
            else -> slotOp.coerceIn(0f, 0.85f)
        }
        val borderRgb = if (hovered) {
            theme.primary
        } else {
            Material.mix(theme.borderSubtle, theme.primary, if (hasItem) 0.25f else 0.08f)
        }
        val borderAlpha = if (hovered) {
            0.90f
        } else {
            (ModuleModernGui.CardDesign.borderOpacity * 0.48f).coerceIn(0f, 0.65f)
        }
        val bWidth = if (hovered) {
            (ModuleModernGui.CardDesign.borderWidth * 1.05f).coerceAtLeast(1.0f)
        } else {
            (ModuleModernGui.CardDesign.borderWidth * 0.72f).coerceAtMost(1.0f)
        }

        val slotGlowCol = if (hovered && ModuleModernGui.isLightPollutionGlass) {
            withAlpha(0xFFFFFFFF.toInt(), (0.42f * ModuleModernGui.GlassOptics.specularRim.coerceIn(0f, 1.6f)).coerceIn(0f, 0.85f))
        } else {
            0
        }
        val slotGlowSpread = if (slotGlowCol != 0) 3.8f else 0f
        val pad = if (slotGlowSpread > 0.05f) slotGlowSpread * 2.2f + 2f else 0f
        val bounds = ctx.getBounds(x - pad, y - pad, x + size + pad, y + size + pad)
        ctx.guiRenderState.addGuiElement(
            CssBoxGuiElementRenderState(
                x, y, x + size, y + size,
                radius, radius, radius, radius,
                withAlpha(fillRgb, fillAlpha),
                withAlpha(fillRgb, fillAlpha),
                withAlpha(Material.mix(fillRgb, theme.surfaceLowest, 0.28f), fillAlpha),
                withAlpha(fillRgb, fillAlpha),
                withAlpha(borderRgb, borderAlpha),
                bWidth,
                slotGlowCol, slotGlowSpread,
                ctx.copyPosePooled(),
                ctx.scissorStack.peek(),
                bounds,
            )
        )
    }

    fun withAlpha(rgb: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (rgb and 0x00FFFFFF)
    }

    fun scaleAlpha(argb: Int, scale: Float): Int {
        val baseA = (argb ushr 24) and 0xFF
        if (baseA == 0 || scale <= 0f) return 0
        val outA = (baseA * scale).roundToInt().coerceIn(0, 255)
        return (outA shl 24) or (argb and 0x00FFFFFF)
    }

    private fun boostCardAlpha(argb: Int, factor: Float, layerOpacity: Float): Int {
        val baseNorm = ((argb ushr 24) and 0xFF) / 255f
        val boosted = (1f - (1f - baseNorm * factor).coerceIn(0.04f, 1f)).coerceIn(0.08f, 0.96f)
        val outA = (boosted * layerOpacity.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        return (outA shl 24) or (argb and 0x00FFFFFF)
    }
}
