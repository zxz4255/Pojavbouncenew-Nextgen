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
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleHud
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
import net.ccbluex.liquidbounce.ui.common.UiTooltipManager
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.KeyEvent
import java.text.Collator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tab 0 (`ClickGui.svelte` + `Search.svelte` + `Panel.svelte` + `Module.svelte`).
 */
class ClickGuiTabView {

    companion object {
        const val PANEL_WIDTH = 250f
        const val PANEL_HEADER_HEIGHT = 40f
        const val MAX_MODULES_HEIGHT = 545f
        const val MODULE_ROW_HEIGHT = 34f
        const val SEARCH_WIDTH = 600f
        const val SEARCH_TOP = 70f
        const val SEARCH_INPUT_HEIGHT = 50f
        const val SEARCH_MAX_RESULTS_HEIGHT = 250f
    }

    private class ModuleRowState(val module: ClientModule) {
        var expanded by ClickGuiLayoutStore.booleanState({ "module:${module.name}" }, "expanded")
        // Restore the full height BEFORE the first scroll-limit calculation.
        var expandAnim = if (expanded) 1f else 0f
        var enabledAnim = if (module.enabled) 1f else 0f
        var hoverAnim = 0f
        var chevronRot = if (expanded) 0f else -Math.PI.toFloat() * 0.5f
        var visibleInViewport = false

        fun measureAnimatedSettingsHeight(settingsInnerW: Float): Float {
            if (!expanded && expandAnim <= 0.002f) return 0f
            val innerH = SettingRenderer.measureGroupHeight(module, settingsInnerW)
            if (innerH <= 0.01f) return 0f
            return (16f + innerH) * expandAnim
        }
    }

    private inner class CategoryPanelState(
        val category: ModuleCategory,
        val panelIndex: Int,
        val modules: MutableList<ModuleRowState>,
    ) {
        val config: ClickGuiLayoutStore.PanelConfig = ClickGuiLayoutStore.panelFor(category.tag, panelIndex)
        var openAnim: Float = if (config.expanded) 1f else 0f
        var plusRotAnim: Float = if (config.expanded) 1f else 0f
        var smoothScrollTop: Float = config.scrollTop
        var dragging: Boolean = false
        var dragOffsetX: Float = 0f
        var dragOffsetY: Float = 0f
        var draggingScrollbar: Boolean = false
        var scrollbarGrabOffsetY: Float = 0f
        var bodyDragScrollCandidate: Boolean = false
        var bodyDragScrolling: Boolean = false
        var bodyDragStartY: Float = 0f
        var bodyDragStartScroll: Float = 0f
        var pendingClickRow: ModuleRowState? = null
        var pendingExpandToggle: Boolean = false
        var lastBodyHeight: Float = 0f
        var lastContentHeight: Float = 0f

        fun stepAnimations(dtSeconds: Float) {
            openAnim = UiAnimation.expLerp(openAnim, if (config.expanded) 1f else 0f, 15f, dtSeconds)
            plusRotAnim = UiAnimation.expLerp(plusRotAnim, if (config.expanded) 1f else 0f, 15f, dtSeconds)
            for (row in modules) {
                val hasSettings = SettingRenderer.hasAnyActiveChildren(row.module)
                val target = if (row.expanded && hasSettings) 1f else 0f
                row.expandAnim = UiAnimation.expLerp(row.expandAnim, target, 16f, dtSeconds)
                row.enabledAnim = UiAnimation.expLerp(row.enabledAnim, if (row.module.enabled) 1f else 0f, 16f, dtSeconds)
                row.chevronRot = (-Math.PI.toFloat() * 0.5f) * (1f - row.expandAnim)
                if (row.expanded || row.expandAnim > 0.002f) {
                    SettingRenderer.stepGroupAnimations(row.module, dtSeconds)
                }
            }
        }

        fun measureContentHeight(): Float {
            var sum = 0f
            val settingsInnerW = PANEL_WIDTH - 22f
            for (row in modules) {
                sum += MODULE_ROW_HEIGHT + row.measureAnimatedSettingsHeight(settingsInnerW)
            }
            return sum
        }
    }

    private val panels = ArrayList<CategoryPanelState>()
    private var draggingAnyPanel = false

    // Search bar state (`Search.svelte`)
    val searchEditor = UiTextEditState(
        initialText = "",
        maxLength = 128,
        onChanged = { onSearchQueryChanged() },
    )
    private var filteredSearchModules: List<ClientModule> = emptyList()
    private var searchSelectedIndex = 0
    private var searchScrollTarget = 0f
    private var searchScroll = 0f
    private var lastSearchChinese = UiLocalization.isChinese
    private var lastPanelSortChinese = UiLocalization.isChinese
    private var lastPanelSortSpaced = ModuleHud.spaceSeperatedNames
    private val zhCollator: Collator = Collator.getInstance(Locale.CHINA)

    init {
        rebuildPanels()
        if (ModuleClickGui.searchBarAutoFocusValue) {
            SettingOverlayManager.focusTextInput(searchEditor, selectAll = false)
        }
    }

    private fun sortPanelModulesInPlace(list: MutableList<ModuleRowState>) {
        if (UiLocalization.isChinese) {
            list.sortWith { a, b ->
                val za = UiLocalization.moduleName(a.module.name)
                val zb = UiLocalization.moduleName(b.module.name)
                val cmp = zhCollator.compare(za, zb)
                if (cmp != 0) cmp else a.module.name.compareTo(b.module.name, ignoreCase = true)
            }
        } else {
            list.sortWith { a, b ->
                val ea = UiTextUtils.formatEnglishName(a.module.name)
                val eb = UiTextUtils.formatEnglishName(b.module.name)
                val cmp = String.CASE_INSENSITIVE_ORDER.compare(ea, eb)
                if (cmp != 0) cmp else a.module.name.compareTo(b.module.name, ignoreCase = true)
            }
        }
    }

    private fun computeAvailableBodyHeight(panelTop: Float, canvasH: Float): Float {
        if (canvasH <= 0f) return MAX_MODULES_HEIGHT
        val avail = (canvasH - panelTop - PANEL_HEADER_HEIGHT - 8f).coerceAtLeast(0f)
        return min(MAX_MODULES_HEIGHT, avail)
    }

    fun onLanguageOrFormatChanged(canvasW: Float = 0f, canvasH: Float = 0f) {
        lastPanelSortChinese = UiLocalization.isChinese
        lastPanelSortSpaced = ModuleHud.spaceSeperatedNames
        lastSearchChinese = UiLocalization.isChinese

        for (panel in panels) {
            sortPanelModulesInPlace(panel.modules)
            if (canvasH > 0f) {
                val contentH = panel.measureContentHeight()
                panel.lastContentHeight = contentH
                val maxBodyH = computeAvailableBodyHeight(panel.config.top, canvasH)
                val bodyH = if (!panel.config.expanded) 0f else min(maxBodyH, contentH)
                panel.lastBodyHeight = bodyH
                val maxScroll = max(0f, contentH - bodyH)
                panel.config.scrollTop = panel.config.scrollTop.coerceIn(0f, maxScroll)
                panel.smoothScrollTop = panel.smoothScrollTop.coerceIn(0f, maxScroll)
            }
        }
        if (searchEditor.text.isNotBlank()) {
            onSearchQueryChanged()
        }
    }

    fun rebuildPanels() {
        SettingRenderer.prepareForOpen()
        panels.clear()
        lastPanelSortChinese = UiLocalization.isChinese
        lastPanelSortSpaced = ModuleHud.spaceSeperatedNames
        val cats = ModuleCategories.entries.toList()
        cats.forEachIndexed { idx, cat ->
            val mods = ModuleManager
                .filter { it.category == cat }
                .map(::ModuleRowState)
                .toMutableList()
            if (mods.isNotEmpty()) {
                sortPanelModulesInPlace(mods)
                panels.add(CategoryPanelState(cat, idx, mods))
            }
        }
    }

    private fun matchesAcronym(queryLower: String, rawCamelName: String): Boolean {
        if (queryLower.length < 2) return false
        val initials = buildString {
            rawCamelName.forEachIndexed { i, c ->
                if (i == 0 || c.isUpperCase() || c.isDigit()) {
                    append(c.lowercaseChar())
                }
            }
        }
        return initials.contains(queryLower)
    }

    private fun onSearchQueryChanged() {
        val rawQuery = searchEditor.text.trim()
        if (rawQuery.isEmpty()) {
            filteredSearchModules = emptyList()
            searchSelectedIndex = 0
            searchScrollTarget = 0f
            searchScroll = 0f
            return
        }
        val lowerQ = rawQuery.lowercase(Locale.ROOT)
        val compactQ = lowerQ.replace(" ", "")
        filteredSearchModules = ModuleManager.filter { m ->
            val cleanName = m.name.lowercase(Locale.ROOT).replace(" ", "")
            cleanName.contains(compactQ) ||
                UiLocalization.matchesQuery(rawQuery, m.name) ||
                matchesAcronym(compactQ, m.name) ||
                m.aliases.any {
                    it.lowercase(Locale.ROOT).replace(" ", "").contains(compactQ) ||
                        UiLocalization.matchesQuery(rawQuery, it)
                }
        }.sortedWith { a, b ->
            fun rank(mod: ClientModule): Int {
                val enLower = mod.name.lowercase(Locale.ROOT)
                val dispLower = UiLocalization.moduleName(mod.name).lowercase(Locale.ROOT)
                return when {
                    enLower == compactQ || dispLower == lowerQ -> 0
                    enLower.startsWith(compactQ) || dispLower.startsWith(lowerQ) -> 1
                    enLower.contains(compactQ) || dispLower.contains(lowerQ) -> 2
                    else -> 3
                }
            }
            val ra = rank(a)
            val rb = rank(b)
            if (ra != rb) {
                ra.compareTo(rb)
            } else {
                String.CASE_INSENSITIVE_ORDER.compare(
                    UiLocalization.moduleName(a.name),
                    UiLocalization.moduleName(b.name),
                )
            }
        }
        searchSelectedIndex = 0
        searchScrollTarget = 0f
        searchScroll = 0f
    }

    private fun locateAndHighlightModule(moduleName: String) {
        ClickGuiLayoutStore.triggerHighlight(moduleName)
        for (panel in panels) {
            val targetIdx = panel.modules.indexOfFirst { it.module.name.equals(moduleName, ignoreCase = true) }
            if (targetIdx >= 0) {
                panel.config.expanded = true
                ClickGuiLayoutStore.maxPanelZIndex++
                panel.config.zIndex = ClickGuiLayoutStore.maxPanelZIndex
                var yBefore = 0f
                val settingsInnerW = PANEL_WIDTH - 22f
                for (i in 0 until targetIdx) {
                    yBefore += MODULE_ROW_HEIGHT + panel.modules[i].measureAnimatedSettingsHeight(settingsInnerW)
                }
                val visibleBody = panel.lastBodyHeight.takeIf { it > MODULE_ROW_HEIGHT } ?: MAX_MODULES_HEIGHT
                val maxScroll = max(0f, panel.measureContentHeight() - visibleBody)
                panel.config.scrollTop = (yBefore - visibleBody * 0.35f).coerceIn(0f, maxScroll)
                ClickGuiLayoutStore.save()
                break
            }
        }
    }

    fun render(
        ctx: GuiGraphicsExtractor,
        canvasW: Float,
        canvasH: Float,
        mouseX: Float,
        mouseY: Float,
        dtSeconds: Float,
        shiftHeld: Boolean,
    ) {
        if (UiLocalization.isChinese != lastPanelSortChinese ||
            ModuleHud.spaceSeperatedNames != lastPanelSortSpaced
        ) {
            onLanguageOrFormatChanged(canvasW, canvasH)
        }

        if (draggingAnyPanel && ModuleClickGui.snappingEnabledValue && !shiftHeld) {
            val step = ModuleClickGui.gridSizeValue.coerceAtLeast(4f)
            var gx = 0f
            while (gx <= canvasW) {
                UiDraw.fillBox(ctx, gx, 0f, 1f, canvasH, UiPalette.GRID_LINE_COLOR)
                gx += step
            }
            var gy = 0f
            while (gy <= canvasH) {
                UiDraw.fillBox(ctx, 0f, gy, canvasW, 1f, UiPalette.GRID_LINE_COLOR)
                gy += step
            }
        }

        val searchBoxW = min(SEARCH_WIDTH, canvasW - 40f)
        val searchBoxX = (canvasW - searchBoxW) * 0.5f
        val hasQuery = searchEditor.text.isNotBlank()
        val resultsBodyH = if (!hasQuery) 0f else {
            val count = max(1, filteredSearchModules.size)
            min(SEARCH_MAX_RESULTS_HEIGHT, 10f + count * 36f)
        }
        val searchTotalH = SEARCH_INPUT_HEIGHT + resultsBodyH
        val mouseInSearch = mouseX in searchBoxX..(searchBoxX + searchBoxW) &&
            mouseY in SEARCH_TOP..(SEARCH_TOP + searchTotalH)

        val sortedPanels = panels.sortedBy { it.config.zIndex }
        var topHitPanel: CategoryPanelState? = null
        if (!mouseInSearch && !SettingOverlayManager.hasOpenDropdown) {
            for (p in sortedPanels.asReversed()) {
                val ph = PANEL_HEADER_HEIGHT + p.lastBodyHeight
                if (mouseX in p.config.left..(p.config.left + PANEL_WIDTH) &&
                    mouseY in p.config.top..(p.config.top + ph)
                ) {
                    topHitPanel = p
                    break
                }
            }
        }

        for (panel in sortedPanels) {
            renderPanel(
                ctx = ctx,
                panel = panel,
                canvasW = canvasW,
                canvasH = canvasH,
                mouseX = if (panel === topHitPanel) mouseX else -10000f,
                mouseY = if (panel === topHitPanel) mouseY else -10000f,
                dtSeconds = dtSeconds,
            )
        }

        renderSearchBar(ctx, searchBoxX, SEARCH_TOP, searchBoxW, mouseX, mouseY, dtSeconds)
    }

    private fun renderPanel(
        ctx: GuiGraphicsExtractor,
        panel: CategoryPanelState,
        canvasW: Float,
        canvasH: Float,
        mouseX: Float,
        mouseY: Float,
        dtSeconds: Float,
    ) {
        panel.stepAnimations(dtSeconds)

        val contentH = panel.measureContentHeight()
        panel.lastContentHeight = contentH

        val minVisibleBody = if (panel.config.expanded && contentH > 0f) {
            min(contentH, MODULE_ROW_HEIGHT * 2.5f) + 8f
        } else {
            0f
        }
        panel.config.left = panel.config.left.coerceIn(0f, max(0f, canvasW - PANEL_WIDTH))
        panel.config.top = panel.config.top.coerceIn(0f, max(0f, canvasH - PANEL_HEADER_HEIGHT - minVisibleBody))

        val maxBodyH = computeAvailableBodyHeight(panel.config.top, canvasH)
        val bodyH = if (panel.openAnim < 0.005f && !panel.config.expanded) {
            0f
        } else {
            min(maxBodyH, contentH) * panel.openAnim
        }
        panel.lastBodyHeight = bodyH

        val px = panel.config.left
        val py = panel.config.top
        val totalH = PANEL_HEADER_HEIGHT + bodyH
        val glassMode = ClickGuiGlassSurface.isActive

        if (glassMode) {
            val panelRadius = if (ModuleClickGui.isLightPollutionGlass) 6f else 10f
            ClickGuiGlassSurface.drawElevatedPanel(
                ctx = ctx,
                x = px, y = py, w = PANEL_WIDTH, h = totalH,
                rTL = panelRadius, rTR = panelRadius, rBR = panelRadius, rBL = panelRadius,
                densityBoost = 1.0f,
                shadowSpread = 16f,
                shadowAlpha = 0.52f,
                accentRimMix = 0.24f,
                rimAlpha = 0.50f,
            )
            if (ModuleClickGui.isLightPollutionGlass) {
                ClickGuiGlassSurface.drawAccentCapsule(
                    ctx = ctx,
                    x = px + 5f,
                    y = py + 4f,
                    w = PANEL_WIDTH - 10f,
                    h = PANEL_HEADER_HEIGHT - 8f,
                    radius = 4.5f,
                    accentMix = 0.25f,
                    tintDensity = 0.20f,
                    layerOpacity = 0.34f,
                    rimAlpha = 0.18f,
                    shadowSpread = 12f,
                    shadowAlpha = 0.26f,
                )
            } else if (!ModuleClickGui.isGaussianGlass) {
                // 098 GaussianGlass keeps no extra header glaze over the Gaussian-blurred card.
                val headerBottomR = if (bodyH <= 0.5f) panelRadius else 0f
                UiDraw.fillCssBox(
                    ctx = ctx,
                    x = px, y = py, w = PANEL_WIDTH, h = PANEL_HEADER_HEIGHT,
                    rTL = panelRadius, rTR = panelRadius, rBR = headerBottomR, rBL = headerBottomR,
                    cTL = UiPalette.white(0.055f),
                    cTR = UiPalette.white(0.035f),
                    cBR = UiPalette.black(0.10f),
                    cBL = UiPalette.black(0.08f),
                )
            }
            ClickGuiGlassSurface.drawGlowingAccentBar(ctx, px + 8f, py + PANEL_HEADER_HEIGHT - 2.2f, PANEL_WIDTH - 16f, 1.8f)
        } else {
            UiDraw.drawSoftShadow(ctx, px, py, PANEL_WIDTH, totalH, radius = 5f, spread = 8f, maxAlpha = 0.42f)

            val bottomRadius = if (bodyH <= 0.5f) 5f else 0f
            UiDraw.fillCssBox(ctx, px, py, PANEL_WIDTH, PANEL_HEADER_HEIGHT, 5f, 5f, bottomRadius, bottomRadius, UiPalette.panelHeaderBg)
            UiDraw.fillBox(ctx, px, py + PANEL_HEADER_HEIGHT - 2f, PANEL_WIDTH, 2f, UiPalette.accent(1f))
        }

        UiDraw.drawIcon(
            ctx = ctx,
            id = UiIcons.categoryIcon(panel.category.tag),
            x = px + 15f,
            y = py + (PANEL_HEADER_HEIGHT - 16f) * 0.5f,
            boxW = 16f,
            boxH = 16f,
            argb = UiPalette.TEXT_COLOR,
        )
        val catLh = UiDraw.lineHeight(14f, UiFontWeight.MEDIUM)
        UiDraw.drawText(
            ctx, UiLocalization.categoryName(panel.category.tag),
            px + 43f, py + (PANEL_HEADER_HEIGHT - catLh) * 0.5f,
            14f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM,
            shadow = glassMode,
        )

        val iconCx = px + PANEL_WIDTH - 21f
        val iconCy = py + PANEL_HEADER_HEIGHT * 0.5f
        UiDraw.fillBox(ctx, iconCx - 6f, iconCy - 1f, 12f, 2f, UiPalette.TEXT_COLOR, radius = 1f)
        val vertAlpha = (1f - panel.plusRotAnim).coerceIn(0f, 1f)
        if (vertAlpha > 0.02f) {
            val halfH = 6f * vertAlpha
            UiDraw.fillBox(ctx, iconCx - 1f, iconCy - halfH, 2f, halfH * 2f, UiPalette.withAlphaFactor(UiPalette.TEXT_COLOR, vertAlpha), radius = 1f)
        }

        if (bodyH <= 0.5f) {
            for (row in panel.modules) {
                row.visibleInViewport = false
                row.hoverAnim = 0f
            }
            return
        }

        val bodyY = py + PANEL_HEADER_HEIGHT
        if (!glassMode) {
            UiDraw.fillCssBox(ctx, px, bodyY, PANEL_WIDTH, bodyH, 0f, 0f, 5f, 5f, UiPalette.panelBodyBg)
        }

        val maxScroll = max(0f, contentH - bodyH)
        panel.config.scrollTop = panel.config.scrollTop.coerceIn(0f, maxScroll)
        panel.smoothScrollTop = UiAnimation.expLerp(panel.smoothScrollTop, panel.config.scrollTop, 18f, dtSeconds)
            .coerceIn(0f, maxScroll)

        val now = System.currentTimeMillis()
        val highlightName = ClickGuiLayoutStore.highlightModuleName
        if (highlightName != null && now - ClickGuiLayoutStore.highlightTriggeredMs > 4000L) {
            ClickGuiLayoutStore.highlightModuleName = null
        }

        val clipH = if (glassMode) max(1f, bodyH - 3f) else bodyH
        val scissorPadX = if (glassMode && ModuleClickGui.isLightPollutionGlass) 26f else 0f
        UiDraw.withCssScissor(ctx, px - scissorPadX, bodyY, PANEL_WIDTH + scissorPadX * 2f, clipH) {
            var rowY = bodyY - panel.smoothScrollTop
            val settingsInnerW = PANEL_WIDTH - 22f

            for (row in panel.modules) {
                val mod = row.module
                val hasSettings = SettingRenderer.hasAnyActiveChildren(mod)
                val settingsBlockH = row.measureAnimatedSettingsHeight(settingsInnerW)

                if (rowY + MODULE_ROW_HEIGHT + settingsBlockH >= bodyY && rowY <= bodyY + bodyH) {
                    row.visibleInViewport = true
                    val visTop = max(bodyY, rowY)
                    val visBot = min(bodyY + bodyH, rowY + MODULE_ROW_HEIGHT)
                    val hovered = mouseX in px..(px + PANEL_WIDTH) && mouseY in visTop..visBot
                    row.hoverAnim = UiAnimation.expLerp(row.hoverAnim, if (hovered) 1f else 0f, 18f, dtSeconds)

                    if (glassMode) {
                        val capsuleFade = max(row.enabledAnim * 0.94f, row.hoverAnim * 0.62f).coerceIn(0f, 1f)
                        if (ModuleClickGui.isLightPollutionGlass) {
                            val bloomFade = max(row.enabledAnim * 0.96f, row.hoverAnim * 0.46f).coerceIn(0f, 1f)
                            if (bloomFade > 0.015f) {
                                ClickGuiGlassSurface.drawAccentCapsule(
                                    ctx = ctx,
                                    x = px + 4f,
                                    y = rowY + 3f,
                                    w = PANEL_WIDTH - 8f,
                                    h = MODULE_ROW_HEIGHT - 6f,
                                    radius = 4.5f,
                                    accentMix = 1f,
                                    tintDensity = 0.24f + 0.14f * row.enabledAnim,
                                    layerOpacity = bloomFade,
                                    rimAlpha = 0.28f + 0.36f * row.enabledAnim,
                                    shadowSpread = 11.5f,
                                    shadowAlpha = 0.24f * row.hoverAnim * (1f - row.enabledAnim) + 0.56f * row.enabledAnim,
                                )
                            }
                        } else if (ModuleClickGui.isGaussianGlass) {
                            // 098 quiet rows: faint hover wash + functional accent bar for enabled modules.
                            if (capsuleFade > 0.015f) {
                                if (row.hoverAnim > 0.01f) {
                                    UiDraw.fillBox(ctx, px + 3f, rowY, PANEL_WIDTH - 6f, MODULE_ROW_HEIGHT,
                                        UiPalette.white(0.045f * row.hoverAnim))
                                }
                                if (row.enabledAnim > 0.01f) {
                                    UiDraw.fillBox(ctx, px + 7f, rowY + 5f, 2.5f, MODULE_ROW_HEIGHT - 10f,
                                        UiPalette.accent(row.enabledAnim))
                                }
                            }
                        } else if (capsuleFade > 0.015f) {
                            ClickGuiGlassSurface.drawAccentCapsule(
                                ctx = ctx,
                                x = px + 5f,
                                y = rowY + 2f,
                                w = PANEL_WIDTH - 10f,
                                h = MODULE_ROW_HEIGHT - 4f,
                                radius = 6.5f,
                                accentMix = (row.enabledAnim * 0.88f + row.hoverAnim * 0.08f).coerceIn(0f, 1f),
                                tintDensity = 0.30f + 0.22f * row.enabledAnim,
                                layerOpacity = capsuleFade,
                                rimAlpha = 0.38f + 0.44f * row.enabledAnim,
                                shadowSpread = if (row.enabledAnim > 0.15f) 5f * row.enabledAnim else 0f,
                                shadowAlpha = 0.26f * row.enabledAnim,
                            )
                        }
                    }  else if (row.hoverAnim > 0.01f) {
                        UiDraw.fillBox(
                            ctx, px, rowY, PANEL_WIDTH, MODULE_ROW_HEIGHT,
                            UiPalette.withAlphaFactor(UiPalette.moduleHoverBg, row.hoverAnim),
                        )
                    }
                    if (hovered) {
                        val desc = runCatching { mod.description.get() }.getOrNull()
                        if (!desc.isNullOrBlank() && !desc.startsWith("liquidbounce.")) {
                            UiTooltipManager.request(
                                text = desc,
                                anchorRightX = px + PANEL_WIDTH,
                                centerY = rowY + MODULE_ROW_HEIGHT * 0.5f,
                                fallbackLeftX = px,
                                canvasWidth = canvasW,
                            )
                        }
                    }

                    if (highlightName != null && mod.name.equals(highlightName, ignoreCase = true)) {
                        val hx = if (glassMode) px + 5f else px + 1f
                        val hw = if (glassMode) PANEL_WIDTH - 10f else PANEL_WIDTH - 2f
                        val hr = if (glassMode) 6.5f else 2f
                        UiDraw.fillBox(
                            ctx, hx, rowY + 1f, hw, MODULE_ROW_HEIGHT - 2f,
                            UiPalette.accent(0.16f), radius = hr,
                            borderColor = UiPalette.MODULE_HIGHLIGHT_BORDER, borderWidth = 2f,
                        )
                    }

                    val displayName = UiLocalization.moduleName(mod.name)
                    val tw = UiDraw.measureText(displayName, 12f, UiFontWeight.MEDIUM)
                    val lh = UiDraw.lineHeight(12f, UiFontWeight.MEDIUM)
                    val baseDisabledCol = if (glassMode && ModuleClickGui.isLightPollutionGlass) {
                        UiPalette.white(0.54f)
                    } else {
                        UiPalette.TEXT_DIMMED_COLOR
                    }
                    val disabledCol = UiPalette.lerpArgb(baseDisabledCol, UiPalette.TEXT_COLOR, row.hoverAnim)
                    val nameCol = if (glassMode) {
                        UiPalette.lerpArgb(disabledCol, UiPalette.TEXT_COLOR, row.enabledAnim)
                    } else {
                        UiPalette.lerpArgb(disabledCol, UiPalette.accent(1f), row.enabledAnim)
                    }
                    UiDraw.drawText(
                        ctx, displayName,
                        if (ModuleClickGui.isLightPollutionGlass || ModuleClickGui.isGaussianGlass) {
                            px + 17f
                        } else {
                            px + (PANEL_WIDTH - tw) * 0.5f
                        },
                        rowY + (MODULE_ROW_HEIGHT - lh) * 0.5f,
                        12f, nameCol, UiFontWeight.MEDIUM,
                        shadow = glassMode,
                    )

                    if (hasSettings) {
                        val chevronBase = UiPalette.white(0.55f)
                        val chevronCol = UiPalette.lerpArgb(chevronBase, UiPalette.TEXT_COLOR, row.expandAnim)
                        UiDraw.drawIcon(
                            ctx = ctx,
                            id = UiIcons.SETTINGS_EXPAND,
                            x = px + PANEL_WIDTH - 25f,
                            y = rowY + (MODULE_ROW_HEIGHT - 9f) * 0.5f,
                            boxW = 10f,
                            boxH = 9f,
                            argb = chevronCol,
                            rotationRad = row.chevronRot,
                        )
                    }

                    if (settingsBlockH > 0.5f) {
                        val setY = rowY + MODULE_ROW_HEIGHT
                        UiDraw.withCssScissor(ctx, px - scissorPadX, setY, PANEL_WIDTH + scissorPadX * 2f, settingsBlockH) {
                            if (glassMode) {
                                ClickGuiGlassSurface.drawSubtleWell(
                                    ctx = ctx,
                                    x = px + 5f,
                                    y = setY + 1f,
                                    w = PANEL_WIDTH - 10f,
                                    h = max(2f, settingsBlockH - 3f),
                                    rTL = 6f, rTR = 6f, rBR = 6f, rBL = 6f,
                                    darknessAlpha = 0.26f,
                                    borderAlpha = 0.12f,
                                )
                                if (!ModuleClickGui.isLightPollutionGlass) {
                                    UiDraw.fillBox(
                                        ctx, px + 6.5f, setY + 5f, 2.5f, max(2f, settingsBlockH - 11f),
                                        UiPalette.accent(0.92f), radius = 1.25f,
                                    )
                                }
                            } else {
                                UiDraw.fillBox(ctx, px, setY, PANEL_WIDTH, settingsBlockH, UiPalette.moduleSettingsBg)
                                UiDraw.fillBox(ctx, px, setY, 4f, settingsBlockH, UiPalette.accent(1f))
                            }

                            val interactive = row.expanded && row.expandAnim > 0.85f && mouseY in bodyY..(bodyY + bodyH)
                            val childMouseX = if (interactive) mouseX else -10000f
                            val childMouseY = if (interactive) mouseY else -10000f
                            SettingRenderer.renderGroup(
                                ctx = ctx,
                                group = mod,
                                x = px + 11f,
                                y = setY + 8f,
                                width = settingsInnerW,
                                mouseX = childMouseX,
                                mouseY = childMouseY,
                                dtSeconds = dtSeconds,
                                canvasWidth = canvasW,
                            )
                        }
                    }
                } else {
                    row.visibleInViewport = false
                    row.hoverAnim = 0f
                }

                rowY += MODULE_ROW_HEIGHT + settingsBlockH
            }

            if (maxScroll > 1f) {
                val trackX = px + PANEL_WIDTH - 4.5f
                UiDraw.fillBox(ctx, trackX, bodyY + 3f, 3f, max(2f, bodyH - 6f), UiPalette.white(0.08f), radius = 1.5f)
                val thumbH = max(20f, bodyH * (bodyH / contentH))
                val thumbY = bodyY + (panel.smoothScrollTop / maxScroll) * max(0f, bodyH - thumbH)
                val thumbCol = if (panel.draggingScrollbar) UiPalette.accent(1f) else UiPalette.accent(0.85f)
                UiDraw.fillBox(ctx, trackX, thumbY, 3f, thumbH, thumbCol, radius = 1.5f)
            }
        }
    }

    private fun renderSearchBar(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        w: Float,
        mouseX: Float,
        mouseY: Float,
        dtSeconds: Float,
    ) {
        val zhNow = UiLocalization.isChinese
        if (zhNow != lastSearchChinese) {
            lastSearchChinese = zhNow
            if (searchEditor.text.isNotBlank()) {
                onSearchQueryChanged()
            }
        }
        val hasQuery = searchEditor.text.isNotBlank()
        val glassMode = ClickGuiGlassSurface.isActive
        val radius = if (hasQuery) (if (glassMode) 14f else 10f) else 25f
        val resultsH = if (!hasQuery) 0f else {
            val count = max(1, filteredSearchModules.size)
            min(SEARCH_MAX_RESULTS_HEIGHT, 10f + count * 36f)
        }
        val totalH = SEARCH_INPUT_HEIGHT + resultsH

        if (glassMode) {
            ClickGuiGlassSurface.drawElevatedPanel(
                ctx = ctx,
                x = x, y = y, w = w, h = totalH,
                rTL = radius,
                densityBoost = if (hasQuery) 1.25f else 1.05f,
                shadowSpread = 15f,
                shadowAlpha = 0.54f,
                accentRimMix = if (searchEditor.focused) 0.52f else 0.22f,
                rimAlpha = if (searchEditor.focused) 0.68f else 0.48f,
                refractionScale = 1.1f,
            )
        } else {
            UiDraw.drawSoftShadow(ctx, x, y, w, totalH, radius = radius, spread = 8f, maxAlpha = 0.45f)
            UiDraw.fillBox(ctx, x, y, w, totalH, UiPalette.searchBg, radius = radius)
        }

        val textAreaW = if (hasQuery) max(40f, w - 34f) else w
        UiDraw.drawTextFieldContent(
            ctx = ctx,
            state = searchEditor,
            x = x,
            y = y,
            w = textAreaW,
            h = SEARCH_INPUT_HEIGHT,
            fontSizePx = 16f,
            weight = UiFontWeight.REGULAR,
            placeholder = UiLocalization.text("Search", "搜索模块（支持中文 / 英文）"),
            paddingX = 25f,
        )

        if (hasQuery) {
            val clrCx = x + w - 26f
            val clrCy = y + SEARCH_INPUT_HEIGHT * 0.5f
            val clrHover = mouseX in (clrCx - 12f)..(clrCx + 12f) && mouseY in (clrCy - 12f)..(clrCy + 12f)
            if (clrHover) {
                UiDraw.fillBox(ctx, clrCx - 11f, clrCy - 11f, 22f, 22f, UiPalette.white(0.12f), radius = 11f)
            }
            val xCol = if (clrHover) UiPalette.TEXT_COLOR else UiPalette.TEXT_DIMMED_COLOR
            val xLh = UiDraw.lineHeight(15f, UiFontWeight.SEMIBOLD)
            val xTw = UiDraw.measureText("×", 15f, UiFontWeight.SEMIBOLD)
            UiDraw.drawText(ctx, "×", clrCx - xTw * 0.5f, clrCy - xLh * 0.5f, 15f, xCol, UiFontWeight.SEMIBOLD)
        }

        if (!hasQuery) return

        val resTop = y + SEARCH_INPUT_HEIGHT
        if (glassMode) {
            ClickGuiGlassSurface.drawGlowingAccentBar(ctx, x + 12f, resTop - 1f, w - 24f, 1.8f)
        } else {
            UiDraw.fillBox(ctx, x, resTop, w, 2f, UiPalette.accent(1f))
        }

        if (filteredSearchModules.isEmpty()) {
            UiDraw.drawText(
                ctx,
                UiLocalization.text("No modules found", "未找到匹配的模块"),
                x + 25f, resTop + 11f, 16f, UiPalette.TEXT_DIMMED_COLOR, shadow = glassMode,
            )
            return
        }

        searchSelectedIndex = searchSelectedIndex.coerceIn(0, filteredSearchModules.lastIndex)
        val rowH = 36f
        val maxScroll = max(0f, filteredSearchModules.size * rowH + 10f - resultsH)
        searchScrollTarget = searchScrollTarget.coerceIn(0f, maxScroll)
        searchScroll = UiAnimation.expLerp(searchScroll, searchScrollTarget, 18f, dtSeconds).coerceIn(0f, maxScroll)

        val searchPadX = if (glassMode && ModuleClickGui.isLightPollutionGlass) 22f else 0f
        UiDraw.withCssScissor(ctx, x - searchPadX, resTop + 2f, w + searchPadX * 2f, resultsH - 2f) {
            filteredSearchModules.forEachIndexed { idx, mod ->
                val ry = resTop + 5f + idx * rowH - searchScroll
                if (ry + rowH < resTop || ry > resTop + resultsH) return@forEachIndexed

                val hovered = mouseX in x..(x + w) && mouseY in max(resTop, ry)..min(resTop + resultsH, ry + rowH)
                val selected = idx == searchSelectedIndex
                if (glassMode && (selected || hovered || mod.enabled)) {
                    ClickGuiGlassSurface.drawAccentCapsule(
                        ctx = ctx,
                        x = x + 10f,
                        y = ry + 2f,
                        w = w - 20f,
                        h = rowH - 4f,
                        radius = 8f,
                        accentMix = if (mod.enabled) 0.85f else 0.18f,
                        tintDensity = if (mod.enabled) 0.44f else 0.26f,
                        layerOpacity = if (mod.enabled) 0.88f else if (selected) 0.72f else 0.55f,
                        rimAlpha = if (mod.enabled || selected) 0.65f else 0.35f,
                        shadowSpread = 0f,
                    )
                }
                val padLeft = if (selected) 35f else 25f

                val dn = UiLocalization.moduleName(mod.name)
                val nameCol = when {
                    mod.enabled -> if (glassMode) UiPalette.TEXT_COLOR else UiPalette.accent(1f)
                    hovered -> UiPalette.TEXT_COLOR
                    else -> UiPalette.TEXT_DIMMED_COLOR
                }
                val nw = UiDraw.drawText(ctx, dn, x + padLeft, ry + 8f, 16f, nameCol, UiFontWeight.REGULAR, shadow = glassMode)

                if (mod.aliases.isNotEmpty()) {
                    val joined = mod.aliases.joinToString(", ") { UiTextUtils.displayName(it) }
                    val aliasText = UiLocalization.text("(aka $joined)", "（别名：$joined）")
                    UiDraw.drawText(ctx, aliasText, x + padLeft + nw + 10f, ry + 8f, 16f, UiPalette.SEARCH_ALIAS_COLOR, shadow = glassMode)
                }

                if (hovered) {
                    val hint = UiLocalization.text("Right-click to locate", "右键定位至面板")
                    val hw = UiDraw.measureText(hint, 12f, UiFontWeight.REGULAR)
                    UiDraw.drawText(ctx, hint, x + w - 25f - hw, ry + 11f, 12f, UiPalette.TEXT_DIMMED_COLOR, shadow = glassMode)
                }
            }
        }
    }

    fun mouseClicked(
        mouseX: Float,
        mouseY: Float,
        button: Int,
        shiftHeld: Boolean,
        canvasW: Float,
        canvasH: Float = 0f,
    ): Boolean {
        val searchW = min(SEARCH_WIDTH, canvasW - 40f)
        val searchX = (canvasW - searchW) * 0.5f
        val hasQuery = searchEditor.text.isNotBlank()
        val resultsH = if (!hasQuery) 0f else min(SEARCH_MAX_RESULTS_HEIGHT, 10f + max(1, filteredSearchModules.size) * 36f)
        if (mouseX in searchX..(searchX + searchW) && mouseY in SEARCH_TOP..(SEARCH_TOP + SEARCH_INPUT_HEIGHT + resultsH)) {
            if (mouseY <= SEARCH_TOP + SEARCH_INPUT_HEIGHT) {
                val clrCx = searchX + searchW - 26f
                val clrCy = SEARCH_TOP + SEARCH_INPUT_HEIGHT * 0.5f
                if (hasQuery && button == InputConstants.MOUSE_BUTTON_LEFT &&
                    mouseX in (clrCx - 13f)..(clrCx + 13f) && mouseY in (clrCy - 13f)..(clrCy + 13f)
                ) {
                    searchEditor.forceText("")
                    onSearchQueryChanged()
                    SettingOverlayManager.focusTextInput(searchEditor, selectAll = false)
                    return true
                }
                if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                    SettingOverlayManager.focusTextInput(searchEditor, selectAll = false)
                    searchEditor.onMouseDown(mouseX - (searchX + 25f), 16f, UiFontWeight.REGULAR, shiftHeld)
                } else if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
                    searchEditor.forceText("")
                    onSearchQueryChanged()
                }
                return true
            }
            if (filteredSearchModules.isNotEmpty()) {
                val idx = ((mouseY - (SEARCH_TOP + SEARCH_INPUT_HEIGHT + 5f) + searchScroll) / 36f).toInt()
                if (idx in filteredSearchModules.indices) {
                    val mod = filteredSearchModules[idx]
                    searchSelectedIndex = idx
                    if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                        mod.enabled = !mod.enabled
                    } else if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
                        locateAndHighlightModule(mod.name)
                    }
                    return true
                }
            }
            return true
        }

        for (panel in panels.sortedByDescending { it.config.zIndex }) {
            val px = panel.config.left
            val py = panel.config.top
            val bodyH = panel.lastBodyHeight
            if (mouseX !in px..(px + PANEL_WIDTH) || mouseY !in py..(py + PANEL_HEADER_HEIGHT + bodyH)) {
                continue
            }

            ClickGuiLayoutStore.maxPanelZIndex++
            panel.config.zIndex = ClickGuiLayoutStore.maxPanelZIndex

            if (mouseY <= py + PANEL_HEADER_HEIGHT) {
                if (button == InputConstants.MOUSE_BUTTON_RIGHT ||
                    (button == InputConstants.MOUSE_BUTTON_LEFT && mouseX >= px + PANEL_WIDTH - 36f)
                ) {
                    panel.config.expanded = !panel.config.expanded
                    ClickGuiLayoutStore.save()
                } else if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                    panel.dragging = true
                    draggingAnyPanel = true
                    panel.dragOffsetX = mouseX - px
                    panel.dragOffsetY = mouseY - py
                }
                return true
            }

            if (panel.config.expanded && bodyH > 0.5f) {
                val bodyY = py + PANEL_HEADER_HEIGHT
                val contentH = panel.lastContentHeight.takeIf { it > 0f } ?: panel.measureContentHeight()
                val maxScroll = max(0f, contentH - bodyH)

                // Direct scrollbar strip click/drag
                if (button == InputConstants.MOUSE_BUTTON_LEFT && maxScroll > 1f && mouseX >= px + PANEL_WIDTH - 9f) {
                    val thumbH = max(20f, bodyH * (bodyH / contentH))
                    val thumbY = bodyY + (panel.smoothScrollTop / maxScroll) * max(0f, bodyH - thumbH)
                    panel.draggingScrollbar = true
                    if (mouseY in thumbY..(thumbY + thumbH)) {
                        panel.scrollbarGrabOffsetY = mouseY - thumbY
                    } else {
                        panel.scrollbarGrabOffsetY = thumbH * 0.5f
                        val ratio = ((mouseY - bodyY - panel.scrollbarGrabOffsetY) / max(1f, bodyH - thumbH)).coerceIn(0f, 1f)
                        panel.config.scrollTop = ratio * maxScroll
                        panel.smoothScrollTop = panel.config.scrollTop
                    }
                    return true
                }

                var rowY = bodyY - panel.smoothScrollTop
                val settingsInnerW = PANEL_WIDTH - 22f
                for (row in panel.modules) {
                    val hasSettings = SettingRenderer.hasAnyActiveChildren(row.module)
                    val settingsBlockH = row.measureAnimatedSettingsHeight(settingsInnerW)

                    if (mouseY in rowY..(rowY + MODULE_ROW_HEIGHT)) {
                        if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
                            if (hasSettings) {
                                row.expanded = !row.expanded
                            }
                        } else if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                            val expandChevronHit = hasSettings && mouseX >= px + PANEL_WIDTH - 38f
                            if (maxScroll > 1f) {
                                // Support vertical swipe/drag scrolling on touchscreens & mouse without blocking click
                                panel.bodyDragScrollCandidate = true
                                panel.bodyDragScrolling = false
                                panel.bodyDragStartY = mouseY
                                panel.bodyDragStartScroll = panel.config.scrollTop
                                panel.pendingClickRow = row
                                panel.pendingExpandToggle = expandChevronHit
                            } else if (expandChevronHit) {
                                row.expanded = !row.expanded
                            } else {
                                row.module.enabled = !row.module.enabled
                            }
                        }
                        return true
                    }

                    if (settingsBlockH > 0.5f && mouseY in (rowY + MODULE_ROW_HEIGHT)..(rowY + MODULE_ROW_HEIGHT + settingsBlockH)) {
                        if (row.expanded && row.expandAnim > 0.5f) {
                            val allOpts = SettingRenderer.allOptionChildrenOf(row.module)
                            if (SettingRenderer.mouseClickedList(allOpts, mouseX, mouseY, button, shiftHeld)) {
                                ConfigSystem.store(ModuleManager.modulesConfig)
                                return true
                            }
                        }
                        if (button == InputConstants.MOUSE_BUTTON_LEFT && maxScroll > 1f) {
                            panel.bodyDragScrollCandidate = true
                            panel.bodyDragScrolling = false
                            panel.bodyDragStartY = mouseY
                            panel.bodyDragStartScroll = panel.config.scrollTop
                            panel.pendingClickRow = null
                            panel.pendingExpandToggle = false
                        }
                        return true
                    }

                    rowY += MODULE_ROW_HEIGHT + settingsBlockH
                }
                return true
            }
        }
        return false
    }

    fun mouseDragged(
        mouseX: Float,
        mouseY: Float,
        button: Int,
        shiftHeld: Boolean,
        canvasW: Float,
        canvasH: Float,
    ): Boolean {
        if (searchEditor.focused && button == InputConstants.MOUSE_BUTTON_LEFT) {
            val searchW = min(SEARCH_WIDTH, canvasW - 40f)
            val searchX = (canvasW - searchW) * 0.5f
            if (mouseY in SEARCH_TOP..(SEARCH_TOP + SEARCH_INPUT_HEIGHT)) {
                searchEditor.onMouseDrag(mouseX - (searchX + 25f), 16f, UiFontWeight.REGULAR)
                return true
            }
        }

        for (panel in panels) {
            if (panel.draggingScrollbar && button == InputConstants.MOUSE_BUTTON_LEFT) {
                val bodyY = panel.config.top + PANEL_HEADER_HEIGHT
                val bodyH = panel.lastBodyHeight
                val contentH = panel.lastContentHeight.takeIf { it > 0f } ?: panel.measureContentHeight()
                val maxScroll = max(0f, contentH - bodyH)
                if (maxScroll > 0f && bodyH > 1f) {
                    val thumbH = max(20f, bodyH * (bodyH / contentH))
                    val ratio = ((mouseY - bodyY - panel.scrollbarGrabOffsetY) / max(1f, bodyH - thumbH)).coerceIn(0f, 1f)
                    panel.config.scrollTop = ratio * maxScroll
                    panel.smoothScrollTop = panel.config.scrollTop
                }
                return true
            }

            if (panel.bodyDragScrollCandidate && button == InputConstants.MOUSE_BUTTON_LEFT) {
                val dy = mouseY - panel.bodyDragStartY
                if (!panel.bodyDragScrolling && abs(dy) > 5f) {
                    panel.bodyDragScrolling = true
                    panel.pendingClickRow = null
                }
                if (panel.bodyDragScrolling) {
                    val bodyH = panel.lastBodyHeight
                    val contentH = panel.lastContentHeight.takeIf { it > 0f } ?: panel.measureContentHeight()
                    val maxScroll = max(0f, contentH - bodyH)
                    panel.config.scrollTop = (panel.bodyDragStartScroll - dy).coerceIn(0f, maxScroll)
                    panel.smoothScrollTop = panel.config.scrollTop
                    return true
                }
            }

            if (panel.dragging && button == InputConstants.MOUSE_BUTTON_LEFT) {
                val contentH = panel.lastContentHeight.takeIf { it > 0f } ?: panel.measureContentHeight()
                val minVisibleBody = if (panel.config.expanded && contentH > 0f) {
                    min(contentH, MODULE_ROW_HEIGHT * 2.5f) + 8f
                } else {
                    0f
                }
                val maxTop = max(0f, canvasH - PANEL_HEADER_HEIGHT - minVisibleBody)
                var nx = (mouseX - panel.dragOffsetX).coerceIn(0f, max(0f, canvasW - PANEL_WIDTH))
                var ny = (mouseY - panel.dragOffsetY).coerceIn(0f, maxTop)
                if (ModuleClickGui.snappingEnabledValue && !shiftHeld) {
                    val step = ModuleClickGui.gridSizeValue.coerceAtLeast(1f)
                    nx = (nx / step).roundToInt() * step
                    ny = (ny / step).roundToInt() * step
                }
                panel.config.left = nx.coerceIn(0f, max(0f, canvasW - PANEL_WIDTH))
                panel.config.top = ny.coerceIn(0f, maxTop)
                return true
            }
        }

        for (panel in panels.sortedByDescending { it.config.zIndex }) {
            if (!panel.config.expanded) continue
            for (row in panel.modules) {
                if (!row.expanded || row.expandAnim <= 0.5f || !row.visibleInViewport) continue
                val vals = SettingRenderer.allOptionChildrenOf(row.module)
                if (SettingRenderer.mouseDraggedList(vals, mouseX, mouseY, button)) {
                    return true
                }
            }
        }
        return false
    }

    fun mouseReleased(mouseX: Float, mouseY: Float, button: Int) {
        searchEditor.onMouseUp()
        var releasedDrag = false
        for (panel in panels) {
            if (panel.dragging) {
                panel.dragging = false
                releasedDrag = true
            }
            if (panel.draggingScrollbar) {
                panel.draggingScrollbar = false
                releasedDrag = true
            }
            if (panel.bodyDragScrollCandidate) {
                val row = panel.pendingClickRow
                if (!panel.bodyDragScrolling && row != null && button == InputConstants.MOUSE_BUTTON_LEFT) {
                    if (panel.pendingExpandToggle) {
                        if (SettingRenderer.hasAnyActiveChildren(row.module)) {
                            row.expanded = !row.expanded
                        }
                    } else {
                        row.module.enabled = !row.module.enabled
                    }
                } else if (panel.bodyDragScrolling) {
                    releasedDrag = true
                }
                panel.bodyDragScrollCandidate = false
                panel.bodyDragScrolling = false
                panel.pendingClickRow = null
                panel.pendingExpandToggle = false
            }
            if (panel.config.expanded) {
                for (row in panel.modules) {
                    if (row.expanded || row.expandAnim > 0.01f) {
                        SettingRenderer.mouseReleasedList(SettingRenderer.allOptionChildrenOf(row.module), mouseX, mouseY, button)
                    }
                }
            }
        }
        draggingAnyPanel = false
        if (releasedDrag) {
            ClickGuiLayoutStore.save()
        }
    }

    fun mouseScrolled(
        mouseX: Float,
        mouseY: Float,
        scrollY: Double,
        canvasW: Float,
        canvasH: Float = 0f,
    ): Boolean {
        val searchW = min(SEARCH_WIDTH, canvasW - 40f)
        val searchX = (canvasW - searchW) * 0.5f
        if (searchEditor.text.isNotBlank() && filteredSearchModules.isNotEmpty()) {
            val resultsH = min(SEARCH_MAX_RESULTS_HEIGHT, 10f + filteredSearchModules.size * 36f)
            val resTop = SEARCH_TOP + SEARCH_INPUT_HEIGHT
            if (mouseX in searchX..(searchX + searchW) && mouseY in resTop..(resTop + resultsH)) {
                val maxScroll = max(0f, filteredSearchModules.size * 36f + 10f - resultsH)
                searchScrollTarget = (searchScrollTarget - scrollY.toFloat() * 36f).coerceIn(0f, maxScroll)
                return true
            }
        }

        for (panel in panels.sortedByDescending { it.config.zIndex }) {
            val px = panel.config.left
            val py = panel.config.top + PANEL_HEADER_HEIGHT
            val contentH = panel.measureContentHeight()
            panel.lastContentHeight = contentH
            val bh = if (canvasH > 0f && panel.config.expanded) {
                min(computeAvailableBodyHeight(panel.config.top, canvasH), contentH)
            } else {
                panel.lastBodyHeight
            }
            if (!panel.config.expanded || bh <= 0.5f) continue
            if (mouseX in px..(px + PANEL_WIDTH) && mouseY in py..(py + bh)) {
                for (row in panel.modules) {
                    if (row.expanded && row.expandAnim > 0.5f && row.visibleInViewport &&
                        SettingRenderer.mouseScrolledList(SettingRenderer.allOptionChildrenOf(row.module), mouseX, mouseY, scrollY)
                    ) {
                        return true
                    }
                }
                val maxScroll = max(0f, contentH - bh)
                panel.config.scrollTop = (panel.config.scrollTop - scrollY.toFloat() * 38f).coerceIn(0f, maxScroll)
                return true
            }
        }
        return false
    }

    fun handleSearchHotkeys(event: KeyEvent): Boolean {
        if (filteredSearchModules.isEmpty()) return false
        when (event.key()) {
            InputConstants.KEY_DOWN -> {
                searchSelectedIndex = (searchSelectedIndex + 1) % filteredSearchModules.size
                ensureSearchResultVisible()
                return true
            }
            InputConstants.KEY_UP -> {
                searchSelectedIndex = (searchSelectedIndex - 1 + filteredSearchModules.size) % filteredSearchModules.size
                ensureSearchResultVisible()
                return true
            }
            InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                if (searchEditor.focused && searchSelectedIndex in filteredSearchModules.indices) {
                    val m = filteredSearchModules[searchSelectedIndex]
                    m.enabled = !m.enabled
                    return true
                }
            }
            InputConstants.KEY_TAB -> {
                if (searchSelectedIndex in filteredSearchModules.indices) {
                    locateAndHighlightModule(filteredSearchModules[searchSelectedIndex].name)
                    return true
                }
            }
        }
        return false
    }

    private fun ensureSearchResultVisible() {
        val rowH = 36f
        val resultsH = min(SEARCH_MAX_RESULTS_HEIGHT, 10f + filteredSearchModules.size * rowH)
        val top = searchSelectedIndex * rowH
        val bot = top + rowH
        if (top < searchScrollTarget) searchScrollTarget = top
        if (bot > searchScrollTarget + resultsH - 10f) searchScrollTarget = bot - (resultsH - 10f)
    }
}
