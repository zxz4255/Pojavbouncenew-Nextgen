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
@file:Suppress("LongMethod", "CognitiveComplexMethod")

package net.ccbluex.liquidbounce.ui.clickgui

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui
import net.ccbluex.liquidbounce.ui.clickgui.glass.ClickGuiGlassSurface
import net.ccbluex.liquidbounce.ui.clickgui.setting.SettingOverlayManager
import net.ccbluex.liquidbounce.ui.clickgui.setting.SettingRenderer
import net.ccbluex.liquidbounce.ui.common.UiAnimation
import net.ccbluex.liquidbounce.ui.common.UiDraw
import net.ccbluex.liquidbounce.ui.common.UiFontWeight
import net.ccbluex.liquidbounce.ui.common.UiIcons
import net.ccbluex.liquidbounce.ui.common.UiLocalization
import net.ccbluex.liquidbounce.ui.common.UiPalette
import net.ccbluex.liquidbounce.ui.common.UiTextEditState
import net.ccbluex.liquidbounce.ui.common.UiTextUtils
import net.ccbluex.liquidbounce.ui.hud.HudComponent
import net.ccbluex.liquidbounce.ui.hud.HudComponentManager
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.Alignment
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tab 1 (`HudEditor.svelte` + `DraggableComponent.svelte` + `ComponentDrawer.svelte` + `ComponentSettings.svelte`).
 *
 * Operates in HUD CSS coordinates (`hudW = guiScaledWidth * 2f`, `hudH = guiScaledHeight * 2f`).
 */
class HudEditorTabView {

    companion object {
        const val HUD_EDITOR_GRID_SIZE = 10f
        const val HUD_EDITOR_MAGNET_THRESHOLD = 6f
        const val SETTINGS_POPUP_WIDTH = 220f
        const val DRAWER_BTN_Y = 70f
        const val DRAWER_WIDTH = 600f
    }

    private var draggedComponent: HudComponent? = null
    private var dragPointerCenterOffX = 0f
    private var dragPointerCenterOffY = 0f
    private var activeHorizontalZone = 0 // 0=left, 1=center, 2=right
    private var activeVerticalZone = 0 // 0=upper, 1=center, 2=lower
    private var magneticVerticalGuide: Float? = null
    private var magneticHorizontalGuide: Float? = null
    private val magneticTargetSet = HashSet<HudComponent>()

    // Selected component for floating `ComponentSettings.svelte` popup
    private var selectedComponent: HudComponent? = null
    private var lastSettingsX = 0f
    private var lastSettingsY = 0f
    private var lastSettingsW = SETTINGS_POPUP_WIDTH
    private var lastSettingsH = 0f

    // `ComponentDrawer.svelte` state (+ Add Component button + searchable 2-column grid)
    private var drawerOpen = false
    private var drawerScrollTarget = 0f
    private var drawerScroll = 0f
    private val drawerSearch = UiTextEditState("", maxLength = 128, onChanged = {
        drawerScrollTarget = 0f
        drawerScroll = 0f
    })

    private fun allEditableComponentValues(comp: HudComponent): List<Value<*>> =
        comp.inner.filter { it !== comp.enabledValue && it !== comp.alignment && !it.notAnOption }

    private fun editableComponentValues(comp: HudComponent): List<Value<*>> =
        comp.inner.filter { it !== comp.enabledValue && it !== comp.alignment && !it.notAnOption && it.visibleCondition.asBoolean }

    private fun drawerButtonLabel(): String =
        if (drawerOpen) {
            UiLocalization.text("Close Components", "收起组件列表")
        } else {
            UiLocalization.text("+ Add Component", "+ 添加 HUD 组件")
        }

    private fun filteredCatalog() = HudComponentManager.catalog().filter { entry ->
        UiLocalization.matchesQuery(drawerSearch.text, entry.name, entry.description)
    }

    fun render(
        ctx: GuiGraphicsExtractor,
        tickDelta: Float,
        guiMouseX: Float,
        guiMouseY: Float,
        dtSeconds: Float,
        shiftHeld: Boolean,
    ) {
        val guiW = mc.window.guiScaledWidth.toFloat()
        val guiH = mc.window.guiScaledHeight.toFloat()
        val hudW = guiW * HudComponentManager.WEB_HUD_BASE_SCALE
        val hudH = guiH * HudComponentManager.WEB_HUD_BASE_SCALE
        val hudMouseX = guiMouseX * HudComponentManager.WEB_HUD_BASE_SCALE
        val hudMouseY = guiMouseY * HudComponentManager.WEB_HUD_BASE_SCALE
        val cssToGui = 1f / HudComponentManager.WEB_HUD_BASE_SCALE

        // 1. Render grid + 3x3 anchor zones + magnetic guides while dragging (`HudEditor.svelte`)
        if (draggedComponent != null) {
            val prev = UiDraw.currentCanvasScale
            UiDraw.currentCanvasScale = prev * cssToGui
            ctx.pose().pushMatrix()
            ctx.pose().scale(cssToGui, cssToGui)

            if (!shiftHeld) {
                var gx = 0f
                while (gx <= hudW) {
                    UiDraw.fillBox(ctx, gx, 0f, 1f, hudH, UiPalette.GRID_LINE_COLOR)
                    gx += HUD_EDITOR_GRID_SIZE
                }
                var gy = 0f
                while (gy <= hudH) {
                    UiDraw.fillBox(ctx, 0f, gy, hudW, 1f, UiPalette.GRID_LINE_COLOR)
                    gy += HUD_EDITOR_GRID_SIZE
                }
            }

            // 3x3 Anchor Zones
            val zoneW = hudW / 3f
            val zoneH = hudH / 3f
            for (vz in 0..2) {
                for (hz in 0..2) {
                    val zx = hz * zoneW
                    val zy = vz * zoneH
                    val isActive = hz == activeHorizontalZone && vz == activeVerticalZone
                    UiDraw.fillBox(
                        ctx, zx, zy, zoneW, zoneH,
                        if (isActive) UiPalette.ANCHOR_ZONE_ACTIVE_BG else UiPalette.ANCHOR_ZONE_BG,
                        borderColor = if (isActive) UiPalette.ANCHOR_ZONE_ACTIVE_BORDER else UiPalette.ANCHOR_ZONE_BORDER,
                        borderWidth = 1f,
                    )
                    if (isActive) {
                        val ax = when (hz) {
                            0 -> zx + 12f
                            1 -> zx + zoneW * 0.5f - 7f
                            else -> zx + zoneW - 26f
                        }
                        val ay = when (vz) {
                            0 -> zy + 12f
                            1 -> zy + zoneH * 0.5f - 7f
                            else -> zy + zoneH - 26f
                        }
                        UiDraw.fillBox(ctx, ax, ay, 14f, 14f, UiPalette.accent(1f), radius = 7f, borderColor = UiPalette.TEXT_COLOR, borderWidth = 2f)
                    }
                }
            }

            magneticVerticalGuide?.let { vx ->
                UiDraw.fillBox(ctx, vx - 0.75f, 0f, 1.5f, hudH, UiPalette.MAGNETIC_GUIDE_COLOR)
            }
            magneticHorizontalGuide?.let { hy ->
                UiDraw.fillBox(ctx, 0f, hy - 0.75f, hudW, 1.5f, UiPalette.MAGNETIC_GUIDE_COLOR)
            }

            ctx.pose().popMatrix()
            UiDraw.currentCanvasScale = prev
        }

        // 2. Render all active HUD components (`inEditor = true`)
        HudComponentManager.renderAll(ctx, tickDelta, inEditor = true)

        // 3. Draw editor selection/hover outlines, magnetic highlights, floating `ComponentSettings`, and `ComponentDrawer`
        val prevScale = UiDraw.currentCanvasScale
        UiDraw.currentCanvasScale = prevScale * cssToGui
        ctx.pose().pushMatrix()
        ctx.pose().scale(cssToGui, cssToGui)

        val enabledComps = HudComponentManager.components.filter { it.enabled }
        for (comp in enabledComps) {
            val rx = comp.lastHudX
            val ry = comp.lastHudY
            val rw = comp.lastWidth
            val rh = comp.lastHeight
            val hovered = hudMouseX in rx..(rx + rw) && hudMouseY in ry..(ry + rh) && !drawerOpen
            val isDrag = comp === draggedComponent
            val isSel = comp === selectedComponent
            val isMag = comp in magneticTargetSet

            if (hovered || isDrag || isSel || isMag) {
                val borderCol = when {
                    isDrag || isSel -> UiPalette.accent(1f)
                    isMag -> UiPalette.MAGNETIC_GUIDE_COLOR
                    else -> UiPalette.white(0.45f)
                }
                UiDraw.fillBox(
                    ctx, rx - 2f, ry - 2f, rw + 4f, rh + 4f,
                    if (isDrag) UiPalette.accent(0.10f) else 0,
                    radius = 4f,
                    borderColor = borderCol,
                    borderWidth = if (isDrag || isSel) 1.5f else 1f,
                )
            }

            if (isDrag) {
                val coordWeight = if (UiLocalization.isChinese) UiFontWeight.MEDIUM else UiFontWeight.MONOSPACE
                val coordText = "${UiLocalization.hudName(comp.name)} (${rx.roundToInt()}, ${ry.roundToInt()})"
                val cw = UiDraw.measureText(coordText, 11f, coordWeight) + 12f
                val badgeY = if (ry > 24f) (ry - 21f) else (ry + rh + 4f)
                UiDraw.fillBox(ctx, rx, badgeY, cw, 18f, UiPalette.surface(0.88f), radius = 3f)
                UiDraw.drawText(ctx, coordText, rx + 6f, badgeY + 2.5f, 11f, UiPalette.TEXT_COLOR, coordWeight)
            }
        }

        // Floating `ComponentSettings.svelte` card for `selectedComponent`
        val sel = selectedComponent
        if (sel != null && sel.enabled && draggedComponent == null && !drawerOpen) {
            renderComponentSettingsPopup(ctx, sel, hudW, hudH, hudMouseX, hudMouseY, dtSeconds)
        } else if (sel != null && !sel.enabled) {
            selectedComponent = null
        }

        // Top `ComponentDrawer.svelte` (`+ Add Component` pill + 600px drawer)
        renderComponentDrawer(ctx, hudW, hudH, hudMouseX, hudMouseY, dtSeconds)

        ctx.pose().popMatrix()
        UiDraw.currentCanvasScale = prevScale
    }

    private fun renderComponentSettingsPopup(
        ctx: GuiGraphicsExtractor,
        comp: HudComponent,
        hudW: Float,
        hudH: Float,
        hudMouseX: Float,
        hudMouseY: Float,
        dtSeconds: Float,
    ) {
        val vals = allEditableComponentValues(comp)
        SettingRenderer.stepChildrenAnimations(vals, dtSeconds)
        val innerW = SETTINGS_POPUP_WIDTH - 20f
        val valsH = SettingRenderer.measureListHeight(vals, innerW)
        val headerH = 28f
        val popW = SETTINGS_POPUP_WIDTH
        val popH = headerH + (if (valsH > 0.01f) valsH + 12f else 4f)

        val compCenterX = comp.lastHudX + comp.lastWidth * 0.5f
        val compCenterY = comp.lastHudY + comp.lastHeight * 0.5f
        val placeBelow = compCenterY < hudH * 0.5f
        val rawX = compCenterX - popW * 0.5f
        val rawY = if (placeBelow) (comp.lastHudY + comp.lastHeight + 14f) else (comp.lastHudY - popH - 14f)

        val px = rawX.coerceIn(10f, max(10f, hudW - popW - 10f))
        val py = rawY.coerceIn(10f, max(10f, hudH - popH - 10f))
        lastSettingsX = px
        lastSettingsY = py
        lastSettingsW = popW
        lastSettingsH = popH

        val glassMode = ClickGuiGlassSurface.isActive
        if (glassMode) {
            ClickGuiGlassSurface.drawElevatedPanel(
                ctx = ctx,
                x = px, y = py, w = popW, h = popH,
                rTL = 10f,
                densityBoost = 1.2f,
                shadowSpread = 15f,
                shadowAlpha = 0.52f,
                accentRimMix = 0.28f,
                rimAlpha = 0.55f,
            )
        } else {
            UiDraw.drawSoftShadow(ctx, px, py, popW, popH, radius = 5f, spread = 8f, maxAlpha = 0.45f)
            UiDraw.fillBox(ctx, px, py, popW, popH, UiPalette.surface(0.90f), radius = 5f, borderColor = UiPalette.white(0.10f), borderWidth = 1f)
        }

        // Header with `icon-cross` remove/disable button + component name (`ComponentSettings.svelte`)
        val closeHover = hudMouseX in (px + 8f)..(px + 26f) && hudMouseY in (py + 5f)..(py + 23f)
        if (closeHover) {
            UiDraw.fillBox(ctx, px + 7f, py + 5f, 18f, 18f, UiPalette.ERROR_COLOR, radius = 3f)
        }
        UiDraw.drawIcon(ctx, UiIcons.CROSS, px + 11f, py + 9f, 10f, 10f, UiPalette.TEXT_COLOR)
        UiDraw.drawText(ctx, UiLocalization.hudName(comp.name), px + 30f, py + 6.5f, 12.5f, UiPalette.TEXT_COLOR, UiFontWeight.SEMIBOLD)

        if (valsH > 0.01f) {
            UiDraw.fillBox(ctx, px + 10f, py + headerH - 2f, innerW, 1f, UiPalette.white(0.08f))
            SettingRenderer.renderList(ctx, vals, px + 10f, py + headerH + 4f, innerW, hudMouseX, hudMouseY, dtSeconds, hudW)
        }
    }

    private fun renderComponentDrawer(
        ctx: GuiGraphicsExtractor,
        hudW: Float,
        hudH: Float,
        hudMouseX: Float,
        hudMouseY: Float,
        dtSeconds: Float,
    ) {
        // Trigger button at `top: 70px; left: 50%`
        val btnLabel = drawerButtonLabel()
        val btnW = UiDraw.measureText(btnLabel, 13.5f, UiFontWeight.SEMIBOLD) + 32f
        val btnH = 34f
        val btnX = (hudW - btnW) * 0.5f
        val btnY = DRAWER_BTN_Y
        val btnHover = hudMouseX in btnX..(btnX + btnW) && hudMouseY in btnY..(btnY + btnH)

        val glassMode = ClickGuiGlassSurface.isActive
        if (glassMode) {
            if (drawerOpen || btnHover) {
                ClickGuiGlassSurface.drawAccentCapsule(
                    ctx = ctx, x = btnX, y = btnY, w = btnW, h = btnH, radius = 17f,
                    accentMix = 0.86f, tintDensity = 0.50f, rimAlpha = 0.85f,
                    shadowSpread = 10f, shadowAlpha = 0.44f,
                )
            } else {
                ClickGuiGlassSurface.drawElevatedPanel(
                    ctx = ctx, x = btnX, y = btnY, w = btnW, h = btnH, rTL = 17f,
                    densityBoost = 1.05f, shadowSpread = 10f, shadowAlpha = 0.45f,
                    accentRimMix = 0.45f, rimAlpha = 0.62f,
                )
            }
        } else {
            UiDraw.drawSoftShadow(ctx, btnX, btnY, btnW, btnH, radius = 17f, spread = 7f, maxAlpha = 0.42f)
            UiDraw.fillBox(
                ctx, btnX, btnY, btnW, btnH,
                if (drawerOpen || btnHover) UiPalette.accent(0.90f) else UiPalette.surface(0.84f),
                radius = 17f,
                borderColor = UiPalette.accent(1f),
                borderWidth = 1f,
            )
        }
        val blh = UiDraw.lineHeight(13.5f, UiFontWeight.SEMIBOLD)
        val btw = UiDraw.measureText(btnLabel, 13.5f, UiFontWeight.SEMIBOLD)
        UiDraw.drawText(ctx, btnLabel, btnX + (btnW - btw) * 0.5f, btnY + (btnH - blh) * 0.5f, 13.5f, UiPalette.TEXT_COLOR, UiFontWeight.SEMIBOLD, shadow = glassMode)

        if (!drawerOpen) return

        val dw = min(DRAWER_WIDTH, hudW - 40f)
        val dx = (hudW - dw) * 0.5f
        val dy = btnY + btnH + 14f
        val maxDrawerH = min(420f, hudH - dy - 20f)
        val searchH = 46f

        val catalog = filteredCatalog()

        val cols = if (dw >= 460f) 2 else 1
        val pad = 16f
        val gap = 10f
        val cardW = (dw - pad * 2f - gap * (cols - 1)) / cols
        val cardH = 66f
        val rows = ((catalog.size + cols - 1) / cols).coerceAtLeast(1)
        val contentH = pad * 2f + rows * cardH + max(0, rows - 1) * gap
        val bodyH = min(maxDrawerH - searchH, max(90f, contentH))
        val totalH = searchH + bodyH

        if (glassMode) {
            ClickGuiGlassSurface.drawElevatedPanel(
                ctx = ctx, x = dx, y = dy, w = dw, h = totalH, rTL = 12f,
                densityBoost = 1.22f, shadowSpread = 18f, shadowAlpha = 0.56f,
                accentRimMix = 0.26f, rimAlpha = 0.54f,
            )
        } else {
            UiDraw.drawSoftShadow(ctx, dx, dy, dw, totalH, radius = 5f, spread = 10f, maxAlpha = 0.50f)
            UiDraw.fillBox(ctx, dx, dy, dw, totalH, UiPalette.surface(0.90f), radius = 5f)
        }

        UiDraw.drawTextFieldContent(
            ctx, drawerSearch, dx, dy, dw, searchH,
            fontSizePx = 15f,
            placeholder = UiLocalization.text("Search HUD components...", "搜索 HUD 组件（支持中文 / 英文）..."),
            paddingX = 22f,
        )
        if (glassMode) {
            ClickGuiGlassSurface.drawGlowingAccentBar(ctx, dx + 14f, dy + searchH - 2f, dw - 28f, 1.8f)
        } else {
            UiDraw.fillBox(ctx, dx, dy + searchH - 2f, dw, 2f, UiPalette.accent(1f))
        }

        val bodyY = dy + searchH
        val maxScroll = max(0f, contentH - bodyH)
        drawerScrollTarget = drawerScrollTarget.coerceIn(0f, maxScroll)
        drawerScroll = UiAnimation.expLerp(drawerScroll, drawerScrollTarget, 18f, dtSeconds).coerceIn(0f, maxScroll)

        val drawerPadX = if (glassMode && ModuleClickGui.isLightPollutionGlass) 18f else 0f
        UiDraw.withCssScissor(ctx, dx - drawerPadX, bodyY, dw + drawerPadX * 2f, bodyH) {
            if (catalog.isEmpty()) {
                UiDraw.drawText(
                    ctx,
                    UiLocalization.text("No components found", "未找到匹配的 HUD 组件"),
                    dx + 22f, bodyY + 18f, 14f, UiPalette.TEXT_DIMMED_COLOR,
                )
            } else {
                catalog.forEachIndexed { idx, entry ->
                    val c = idx % cols
                    val r = idx / cols
                    val cx = dx + pad + c * (cardW + gap)
                    val cy = bodyY + pad + r * (cardH + gap) - drawerScroll
                    if (cy + cardH < bodyY || cy > bodyY + bodyH) return@forEachIndexed

                    val hovered = entry.canAdd &&
                        hudMouseX in cx..(cx + cardW) &&
                        hudMouseY in max(bodyY, cy)..min(bodyY + bodyH, cy + cardH)

                    val alphaMul = if (entry.canAdd) 1f else 0.45f
                    if (glassMode) {
                        if (hovered) {
                            ClickGuiGlassSurface.drawAccentCapsule(
                                ctx = ctx, x = cx, y = cy, w = cardW, h = cardH, radius = 8f,
                                accentMix = 0.72f, tintDensity = 0.42f, layerOpacity = alphaMul,
                                rimAlpha = 0.78f, shadowSpread = 0f,
                            )
                        } else {
                            ClickGuiGlassSurface.drawSubtleWell(
                                ctx = ctx, x = cx, y = cy, w = cardW, h = cardH, rTL = 8f,
                                darknessAlpha = 0.24f * alphaMul, borderAlpha = 0.14f * alphaMul,
                            )
                        }
                    } else {
                        val bg = if (hovered) UiPalette.accent(0.22f) else UiPalette.surfaceLightened(32, 0.78f * alphaMul)
                        val border = if (hovered) UiPalette.accent(1f) else UiPalette.white(0.10f * alphaMul)
                        UiDraw.fillBox(ctx, cx, cy, cardW, cardH, bg, radius = 5f, borderColor = border, borderWidth = 1.5f)
                    }
                    val multiSuffix = if (!entry.singleton) UiLocalization.text(" (Multi)", "（可多开）") else ""
                    val title = UiLocalization.hudName(entry.name) + multiSuffix
                    UiDraw.drawText(
                        ctx, title, cx + 10f, cy + 9f, 14f,
                        UiPalette.withAlphaFactor(UiPalette.TEXT_COLOR, alphaMul),
                        UiFontWeight.SEMIBOLD,
                    )
                    val rawDesc = UiLocalization.hudDescription(entry.name, entry.description)
                    val desc = UiDraw.ellipsize(rawDesc, cardW - 20f, 12f, UiFontWeight.MEDIUM)
                    UiDraw.drawText(
                        ctx, desc, cx + 10f, cy + 33f, 12f,
                        UiPalette.withAlphaFactor(UiPalette.TEXT_DIMMED_COLOR, alphaMul),
                        UiFontWeight.MEDIUM,
                    )
                }
            }
        }
    }

    fun mouseClicked(guiMouseX: Float, guiMouseY: Float, button: Int, shiftHeld: Boolean): Boolean {
        val hudW = mc.window.guiScaledWidth * HudComponentManager.WEB_HUD_BASE_SCALE
        val hudH = mc.window.guiScaledHeight * HudComponentManager.WEB_HUD_BASE_SCALE
        val mx = guiMouseX * HudComponentManager.WEB_HUD_BASE_SCALE
        val my = guiMouseY * HudComponentManager.WEB_HUD_BASE_SCALE

        // 1. ComponentDrawer button & popup
        val btnLabel = drawerButtonLabel()
        val btnW = UiDraw.measureText(btnLabel, 13.5f, UiFontWeight.SEMIBOLD) + 32f
        val btnX = (hudW - btnW) * 0.5f
        if (mx in btnX..(btnX + btnW) && my in DRAWER_BTN_Y..(DRAWER_BTN_Y + 34f)) {
            if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                drawerOpen = !drawerOpen
                if (drawerOpen) {
                    drawerSearch.forceText("")
                    SettingOverlayManager.focusTextInput(drawerSearch, selectAll = false)
                } else {
                    SettingOverlayManager.clearTextInputFocus(commit = false)
                }
            }
            return true
        }

        if (drawerOpen) {
            val dw = min(DRAWER_WIDTH, hudW - 40f)
            val dx = (hudW - dw) * 0.5f
            val dy = DRAWER_BTN_Y + 48f
            val searchH = 46f
            val catalog = filteredCatalog()
            val cols = if (dw >= 460f) 2 else 1
            val pad = 16f
            val gap = 10f
            val cardW = (dw - pad * 2f - gap * (cols - 1)) / cols
            val cardH = 66f
            val rows = ((catalog.size + cols - 1) / cols).coerceAtLeast(1)
            val contentH = pad * 2f + rows * cardH + max(0, rows - 1) * gap
            val bodyH = min(min(420f, hudH - dy - 20f) - searchH, max(90f, contentH))

            if (mx in dx..(dx + dw) && my in dy..(dy + searchH + bodyH)) {
                if (my <= dy + searchH) {
                    SettingOverlayManager.focusTextInput(drawerSearch, selectAll = false)
                    drawerSearch.onMouseDown(mx - (dx + 22f), 15f, UiFontWeight.REGULAR, shiftHeld)
                    return true
                }
                if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                    val bodyY = dy + searchH
                    catalog.forEachIndexed { idx, entry ->
                        val c = idx % cols
                        val r = idx / cols
                        val cx = dx + pad + c * (cardW + gap)
                        val cy = bodyY + pad + r * (cardH + gap) - drawerScroll
                        if (entry.canAdd && mx in cx..(cx + cardW) && my in cy..(cy + cardH)) {
                            val added = HudComponentManager.addComponent(entry.name)
                            if (added != null) {
                                selectedComponent = added
                                ConfigSystem.store(ModuleManager.modulesConfig)
                            }
                            if (entry.singleton) {
                                drawerOpen = false
                            }
                            return true
                        }
                    }
                }
                return true
            } else {
                drawerOpen = false
            }
        }

        // 2. Floating `ComponentSettings` popup
        val sel = selectedComponent
        if (sel != null && sel.enabled && mx in lastSettingsX..(lastSettingsX + lastSettingsW) && my in lastSettingsY..(lastSettingsY + lastSettingsH)) {
            if (button == InputConstants.MOUSE_BUTTON_LEFT &&
                mx in (lastSettingsX + 7f)..(lastSettingsX + 26f) &&
                my in (lastSettingsY + 4f)..(lastSettingsY + 24f)
            ) {
                HudComponentManager.removeOrDisableComponent(sel)
                selectedComponent = null
                ConfigSystem.store(ModuleManager.modulesConfig)
                return true
            }
            val vals = editableComponentValues(sel)
            if (SettingRenderer.mouseClickedList(vals, mx, my, button, shiftHeld)) {
                ConfigSystem.store(ModuleManager.modulesConfig)
            }
            return true
        }

        // 3. Hit test active HUD components in reverse z-index order
        val ordered = HudComponentManager.components
            .filter { it.enabled }
            .sortedByDescending { it.zIndex }

        for (comp in ordered) {
            val rx = comp.lastHudX
            val ry = comp.lastHudY
            val rw = comp.lastWidth
            val rh = comp.lastHeight
            if (mx in rx..(rx + rw) && my in ry..(ry + rh)) {
                HudComponentManager.bringToFront(comp)
                if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
                    selectedComponent = if (selectedComponent === comp) null else comp
                    return true
                }
                if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                    selectedComponent = comp
                    draggedComponent = comp
                    val cx = rx + rw * 0.5f
                    val cy = ry + rh * 0.5f
                    dragPointerCenterOffX = mx - cx
                    dragPointerCenterOffY = my - cy
                    updateAnchorZones(cx, cy, hudW, hudH)
                    return true
                }
            }
        }

        // Clicked empty background -> dismiss selected popup
        selectedComponent = null
        return false
    }

    private fun updateAnchorZones(centerX: Float, centerY: Float, hudW: Float, hudH: Float) {
        activeHorizontalZone = when {
            centerX < hudW / 3f -> 0
            centerX > hudW * 2f / 3f -> 2
            else -> 1
        }
        activeVerticalZone = when {
            centerY < hudH / 3f -> 0
            centerY > hudH * 2f / 3f -> 2
            else -> 1
        }
    }

    fun mouseDragged(guiMouseX: Float, guiMouseY: Float, button: Int, shiftHeld: Boolean): Boolean {
        val hudW = mc.window.guiScaledWidth * HudComponentManager.WEB_HUD_BASE_SCALE
        val hudH = mc.window.guiScaledHeight * HudComponentManager.WEB_HUD_BASE_SCALE
        val mx = guiMouseX * HudComponentManager.WEB_HUD_BASE_SCALE
        val my = guiMouseY * HudComponentManager.WEB_HUD_BASE_SCALE

        val sel = selectedComponent
        if (draggedComponent == null && sel != null && sel.enabled) {
            if (SettingRenderer.mouseDraggedList(editableComponentValues(sel), mx, my, button)) {
                return true
            }
        }

        val comp = draggedComponent ?: return false
        if (button != InputConstants.MOUSE_BUTTON_LEFT) return false

        val elemW = comp.lastWidth
        val elemH = comp.lastHeight
        var centerX = (mx - dragPointerCenterOffX).coerceIn(elemW * 0.5f, max(elemW * 0.5f, hudW - elemW * 0.5f))
        var centerY = (my - dragPointerCenterOffY).coerceIn(elemH * 0.5f, max(elemH * 0.5f, hudH - elemH * 0.5f))

        magneticVerticalGuide = null
        magneticHorizontalGuide = null
        magneticTargetSet.clear()

        if (!shiftHeld) {
            // Magnetic snapping against screen center & other HUD components (`DraggableComponent.svelte`)
            var bestDx = HUD_EDITOR_MAGNET_THRESHOLD + 0.01f
            var bestDy = HUD_EDITOR_MAGNET_THRESHOLD + 0.01f

            // Screen center lines
            if (abs(centerX - hudW * 0.5f) <= bestDx) {
                bestDx = abs(centerX - hudW * 0.5f)
                centerX = hudW * 0.5f
                magneticVerticalGuide = hudW * 0.5f
            }
            if (abs(centerY - hudH * 0.5f) <= bestDy) {
                bestDy = abs(centerY - hudH * 0.5f)
                centerY = hudH * 0.5f
                magneticHorizontalGuide = hudH * 0.5f
            }

            val myXPoints = floatArrayOf(centerX - elemW * 0.5f, centerX, centerX + elemW * 0.5f)
            val myYPoints = floatArrayOf(centerY - elemH * 0.5f, centerY, centerY + elemH * 0.5f)

            for (other in HudComponentManager.components) {
                if (!other.enabled || other === comp) continue
                val ox = other.lastHudX
                val oy = other.lastHudY
                val ow = other.lastWidth
                val oh = other.lastHeight
                val otherXPoints = floatArrayOf(ox, ox + ow * 0.5f, ox + ow)
                val otherYPoints = floatArrayOf(oy, oy + oh * 0.5f, oy + oh)

                for (mp in myXPoints) {
                    for (op in otherXPoints) {
                        val d = abs(mp - op)
                        if (d < bestDx) {
                            bestDx = d
                            centerX += (op - mp)
                            magneticVerticalGuide = op
                            magneticTargetSet.add(other)
                        }
                    }
                }
                for (mp in myYPoints) {
                    for (op in otherYPoints) {
                        val d = abs(mp - op)
                        if (d < bestDy) {
                            bestDy = d
                            centerY += (op - mp)
                            magneticHorizontalGuide = op
                            magneticTargetSet.add(other)
                        }
                    }
                }
            }

            // Grid snap if not magnetically snapped
            if (magneticVerticalGuide == null) {
                val leftSnap = ((centerX - elemW * 0.5f) / HUD_EDITOR_GRID_SIZE).roundToInt() * HUD_EDITOR_GRID_SIZE
                centerX = leftSnap + elemW * 0.5f
            }
            if (magneticHorizontalGuide == null) {
                val topSnap = ((centerY - elemH * 0.5f) / HUD_EDITOR_GRID_SIZE).roundToInt() * HUD_EDITOR_GRID_SIZE
                centerY = topSnap + elemH * 0.5f
            }
        }

        updateAnchorZones(centerX, centerY, hudW, hudH)

        // Convert `(centerX, centerY)` + anchor zone -> `Alignment` (`DraggableComponent.svelte`)
        val hAlign = when (activeHorizontalZone) {
            0 -> Alignment.ScreenAxisX.LEFT
            1 -> Alignment.ScreenAxisX.CENTER_TRANSLATED
            else -> Alignment.ScreenAxisX.RIGHT
        }
        val vAlign = when (activeVerticalZone) {
            0 -> Alignment.ScreenAxisY.TOP
            1 -> Alignment.ScreenAxisY.CENTER_TRANSLATED
            else -> Alignment.ScreenAxisY.BOTTOM
        }

        // Account for `translateMinusFullWidth` (`BlockCounter`)
        val effectiveCenterX = if (comp.translateMinusFullWidth) (centerX + elemW) else centerX
        val hOffset = when (hAlign) {
            Alignment.ScreenAxisX.LEFT -> effectiveCenterX - elemW * 0.5f
            Alignment.ScreenAxisX.RIGHT -> hudW - effectiveCenterX - elemW * 0.5f
            Alignment.ScreenAxisX.CENTER_TRANSLATED -> effectiveCenterX - hudW * 0.5f
            Alignment.ScreenAxisX.CENTER -> effectiveCenterX - hudW * 0.5f - elemW * 0.5f
        }
        val vOffset = when (vAlign) {
            Alignment.ScreenAxisY.TOP -> centerY - elemH * 0.5f
            Alignment.ScreenAxisY.BOTTOM -> hudH - centerY - elemH * 0.5f
            Alignment.ScreenAxisY.CENTER_TRANSLATED -> centerY - hudH * 0.5f
            Alignment.ScreenAxisY.CENTER -> centerY - hudH * 0.5f - elemH * 0.5f
        }

        comp.alignment.horizontalAlignment = hAlign
        comp.alignment.horizontalOffset = hOffset.roundToInt().coerceIn(-1000, 1000)
        comp.alignment.verticalAlignment = vAlign
        comp.alignment.verticalOffset = vOffset.roundToInt().coerceIn(-1000, 1000)
        return true
    }

    fun mouseReleased(guiMouseX: Float, guiMouseY: Float, button: Int) {
        val mx = guiMouseX * HudComponentManager.WEB_HUD_BASE_SCALE
        val my = guiMouseY * HudComponentManager.WEB_HUD_BASE_SCALE

        val sel = selectedComponent
        if (sel != null && sel.enabled) {
            SettingRenderer.mouseReleasedList(editableComponentValues(sel), mx, my, button)
        }
        if (draggedComponent != null) {
            draggedComponent = null
            magneticVerticalGuide = null
            magneticHorizontalGuide = null
            magneticTargetSet.clear()
            ConfigSystem.store(ModuleManager.modulesConfig)
        }
    }

    fun mouseScrolled(guiMouseX: Float, guiMouseY: Float, scrollY: Double): Boolean {
        val hudW = mc.window.guiScaledWidth * HudComponentManager.WEB_HUD_BASE_SCALE
        val mx = guiMouseX * HudComponentManager.WEB_HUD_BASE_SCALE
        val my = guiMouseY * HudComponentManager.WEB_HUD_BASE_SCALE

        if (drawerOpen) {
            val dw = min(DRAWER_WIDTH, hudW - 40f)
            val dx = (hudW - dw) * 0.5f
            if (mx in dx..(dx + dw)) {
                drawerScrollTarget = (drawerScrollTarget - scrollY.toFloat() * 38f).coerceAtLeast(0f)
                return true
            }
        }
        val sel = selectedComponent
        if (sel != null && sel.enabled) {
            return SettingRenderer.mouseScrolledList(editableComponentValues(sel), mx, my, scrollY)
        }
        return false
    }
}
