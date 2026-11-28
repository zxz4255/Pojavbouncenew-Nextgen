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
package net.ccbluex.liquidbounce.ui.clickgui

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.config.types.ValueType
import net.ccbluex.liquidbounce.features.global.GlobalManager
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui
import net.ccbluex.liquidbounce.ui.clickgui.glass.ClickGuiGlassSurface
import net.ccbluex.liquidbounce.ui.clickgui.setting.ConfigurableGroupNode
import net.ccbluex.liquidbounce.ui.clickgui.setting.SettingNode
import net.ccbluex.liquidbounce.ui.clickgui.setting.SettingRenderer
import net.ccbluex.liquidbounce.ui.common.UiAnimation
import net.ccbluex.liquidbounce.ui.common.UiDraw
import net.ccbluex.liquidbounce.ui.common.UiFontWeight
import net.ccbluex.liquidbounce.ui.common.UiIcons
import net.ccbluex.liquidbounce.ui.common.UiLocalization
import net.ccbluex.liquidbounce.ui.common.UiPalette
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.max
import kotlin.math.min

/**
 * Tab 2 (`GlobalSettings.svelte` + `WindowPanel.svelte`): balanced 2-column Global Settings window.
 */
class GlobalSettingsTabView {

    private var scrollTarget by ClickGuiLayoutStore.scrollState({ "tab:globalSettings" })
    private var scrollOffset = scrollTarget
    private var lastContentHeight = 0f

    private fun groupValues(): List<Value<*>> {
        return GlobalManager.inner.filter {
            !it.notAnOption &&
                it.visibleCondition.asBoolean &&
                (it.valueType == ValueType.CONFIGURABLE || it.valueType == ValueType.TOGGLEABLE)
        }
    }

    private fun preparedNode(v: Value<*>): SettingNode {
        val node = SettingRenderer.nodeFor(v)
        if (node is ConfigurableGroupNode) {
            node.alwaysExpanded = true
        }
        return node
    }

    private fun windowGeometry(canvasW: Float, canvasH: Float): FloatArray {
        val winW = min(820f, canvasW * 0.92f)
        val winX = (canvasW - winW) * 0.5f
        val winY = 70f
        val maxWinH = max(180f, canvasH * 0.72f)
        return floatArrayOf(winX, winY, winW, maxWinH)
    }

    fun render(
        ctx: GuiGraphicsExtractor,
        canvasW: Float,
        canvasH: Float,
        mouseX: Float,
        mouseY: Float,
        dtSeconds: Float,
    ) {
        val (winX, winY, winW, maxWinH) = windowGeometry(canvasW, canvasH)
        val headerH = 54f
        val padX = 22f
        val padTop = 12f
        val padBot = 18f
        val innerW = winW - padX * 2f
        val twoColumns = canvasW > 900f && innerW >= 460f
        val colGap = 25f
        val colW = if (twoColumns) (innerW - colGap) * 0.5f else innerW

        val groups = groupValues()
        val leftCol = ArrayList<SettingNode>()
        val rightCol = ArrayList<SettingNode>()
        var leftH = 0f
        var rightH = 0f

        for (v in groups) {
            val node = preparedNode(v)
            node.stepAnimations(dtSeconds)
            val nh = node.measureHeight(colW) + 15f
            if (!twoColumns || leftH <= rightH) {
                leftCol.add(node)
                leftH += nh
            } else {
                rightCol.add(node)
                rightH += nh
            }
        }

        val contentH = max(leftH, rightH) + padTop + padBot
        lastContentHeight = contentH
        val maxBodyH = maxWinH - headerH
        val bodyH = min(maxBodyH, max(80f, contentH))
        val totalWinH = headerH + bodyH
        val glassMode = ClickGuiGlassSurface.isActive

        if (glassMode) {
            ClickGuiGlassSurface.drawElevatedPanel(
                ctx = ctx,
                x = winX, y = winY, w = winW, h = totalWinH,
                rTL = 12f,
                densityBoost = 1.05f,
                shadowSpread = 18f,
                shadowAlpha = 0.55f,
                accentRimMix = 0.25f,
                rimAlpha = 0.52f,
            )
            if (!ModuleClickGui.isLightPollutionGlass && !ModuleClickGui.isGaussianGlass) {
                UiDraw.fillCssBox(
                    ctx = ctx,
                    x = winX, y = winY, w = winW, h = headerH,
                    rTL = 12f, rTR = 12f, rBR = 0f, rBL = 0f,
                    cTL = UiPalette.white(0.055f),
                    cTR = UiPalette.white(0.035f),
                    cBR = UiPalette.black(0.10f),
                    cBL = UiPalette.black(0.08f),
                )
            }
            ClickGuiGlassSurface.drawGlowingAccentBar(ctx, winX + 16f, winY + headerH - 2.2f, winW - 32f, 1.8f)
        } else {
            UiDraw.drawSoftShadow(ctx, winX, winY, winW, totalWinH, radius = 5f, spread = 10f, maxAlpha = 0.45f)
            UiDraw.fillCssBox(ctx, winX, winY, winW, headerH, 5f, 5f, 0f, 0f, UiPalette.panelHeaderBg)
            UiDraw.fillBox(ctx, winX, winY + headerH - 2f, winW, 2f, UiPalette.accent(1f))
        }

        UiDraw.drawIcon(ctx, UiIcons.CLIENT, winX + 22f, winY + (headerH - 18f) * 0.5f, 18f, 18f, UiPalette.TEXT_COLOR)
        val titleLh = UiDraw.lineHeight(16f, UiFontWeight.SEMIBOLD)
        UiDraw.drawText(
            ctx, UiLocalization.text("Global Settings", "全局设置"),
            winX + 52f, winY + (headerH - titleLh) * 0.5f,
            16f, UiPalette.TEXT_COLOR, UiFontWeight.SEMIBOLD,
            shadow = glassMode,
        )

        val bodyY = winY + headerH
        if (!glassMode) {
            UiDraw.fillCssBox(ctx, winX, bodyY, winW, bodyH, 0f, 0f, 5f, 5f, UiPalette.panelBodyBg)
        }

        val maxScroll = max(0f, contentH - bodyH)
        scrollTarget = scrollTarget.coerceIn(0f, maxScroll)
        scrollOffset = UiAnimation.expLerp(scrollOffset, scrollTarget, 18f, dtSeconds).coerceIn(0f, maxScroll)

        UiDraw.withCssScissor(ctx, winX, bodyY, winW, if (glassMode) max(1f, bodyH - 2f) else bodyH) {
            val childMouseX = if (mouseY in bodyY..(bodyY + bodyH)) mouseX else -10000f
            val childMouseY = if (mouseY in bodyY..(bodyY + bodyH)) mouseY else -10000f

            var cyL = bodyY + padTop - scrollOffset
            for (node in leftCol) {
                if (glassMode) {
                    val nh = node.measureHeight(colW)
                    ClickGuiGlassSurface.drawSubtleWell(
                        ctx, winX + padX - 8f, cyL - 4f, colW + 16f, nh + 10f,
                        rTL = 8f, darknessAlpha = 0.20f, borderAlpha = 0.11f,
                    )
                }
                cyL += node.render(ctx, winX + padX, cyL, colW, childMouseX, childMouseY, dtSeconds, canvasW) + 15f
            }

            if (twoColumns) {
                val divX = winX + padX + colW + colGap * 0.5f
                if (!glassMode) {
                    UiDraw.fillBox(ctx, divX, bodyY + padTop - scrollOffset, 1f, max(leftH, rightH), UiPalette.white(0.08f))
                }

                var cyR = bodyY + padTop - scrollOffset
                val rx = winX + padX + colW + colGap
                for (node in rightCol) {
                    if (glassMode) {
                        val nh = node.measureHeight(colW)
                        ClickGuiGlassSurface.drawSubtleWell(
                            ctx, rx - 8f, cyR - 4f, colW + 16f, nh + 10f,
                            rTL = 8f, darknessAlpha = 0.20f, borderAlpha = 0.11f,
                        )
                    }
                    cyR += node.render(ctx, rx, cyR, colW, childMouseX, childMouseY, dtSeconds, canvasW) + 15f
                }
            }

            if (maxScroll > 1f) {
                val thumbH = max(24f, bodyH * (bodyH / contentH))
                val thumbY = bodyY + (scrollOffset / maxScroll) * (bodyH - thumbH)
                UiDraw.fillBox(ctx, winX + winW - 4f, thumbY, 3f, thumbH, UiPalette.accent(0.85f), radius = 1.5f)
            }
        }
    }

    fun mouseClicked(mouseX: Float, mouseY: Float, button: Int, shiftHeld: Boolean, canvasW: Float, canvasH: Float): Boolean {
        val (winX, winY, winW, maxWinH) = windowGeometry(canvasW, canvasH)
        val headerH = 54f
        val bodyH = min(maxWinH - headerH, max(80f, lastContentHeight))
        if (mouseX !in winX..(winX + winW) || mouseY !in winY..(winY + headerH + bodyH)) return false
        if (mouseY <= winY + headerH) return true

        for (v in groupValues()) {
            if (preparedNode(v).mouseClicked(mouseX, mouseY, button, shiftHeld)) {
                ConfigSystem.store(GlobalManager)
                return true
            }
        }
        return true
    }

    fun mouseDragged(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (button != InputConstants.MOUSE_BUTTON_LEFT) return false
        for (v in groupValues()) {
            if (preparedNode(v).mouseDragged(mouseX, mouseY, button)) {
                return true
            }
        }
        return false
    }

    fun mouseReleased(mouseX: Float, mouseY: Float, button: Int) {
        for (v in groupValues()) {
            preparedNode(v).mouseReleased(mouseX, mouseY, button)
        }
        ConfigSystem.store(GlobalManager)
    }

    fun mouseScrolled(mouseX: Float, mouseY: Float, scrollY: Double, canvasW: Float, canvasH: Float): Boolean {
        val (winX, winY, winW, maxWinH) = windowGeometry(canvasW, canvasH)
        val headerH = 54f
        val bodyH = min(maxWinH - headerH, max(80f, lastContentHeight))
        if (mouseX !in winX..(winX + winW) || mouseY !in (winY + headerH)..(winY + headerH + bodyH)) return false

        for (v in groupValues()) {
            if (preparedNode(v).mouseScrolled(mouseX, mouseY, scrollY)) {
                return true
            }
        }
        val maxScroll = max(0f, lastContentHeight - bodyH)
        scrollTarget = (scrollTarget - scrollY.toFloat() * 38f).coerceIn(0f, maxScroll)
        return true
    }
}
