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
package net.ccbluex.liquidbounce.ui.hud.components

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.KeyboardKeyEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.ui.common.UiAnimation
import net.ccbluex.liquidbounce.ui.common.UiDraw
import net.ccbluex.liquidbounce.ui.common.UiFontWeight
import net.ccbluex.liquidbounce.ui.common.UiIcons
import net.ccbluex.liquidbounce.ui.common.UiLocalization
import net.ccbluex.liquidbounce.ui.common.UiPalette
import net.ccbluex.liquidbounce.ui.common.UiTextUtils
import net.ccbluex.liquidbounce.ui.hud.HudComponent
import net.ccbluex.liquidbounce.ui.hud.HudSize
import net.ccbluex.liquidbounce.utils.render.Alignment
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.max
import kotlin.math.min

/**
 * 1. Watermark (`Watermark.svelte` + `LiquidBounceLogo.svelte`: `165 x 61.8917px`)
 */
class WatermarkHudComponent : HudComponent(
    name = "Watermark",
    enabledByDefault = true,
    defaultAlignment = Alignment(Alignment.ScreenAxisX.LEFT, 15, Alignment.ScreenAxisY.TOP, 15),
    componentDescription = "Displays LiquidBounce branding and client information.",
) {
    override fun renderHud(
        ctx: GuiGraphicsExtractor,
        tickDelta: Float,
        dtSeconds: Float,
        hudWidth: Float,
        hudHeight: Float,
        inEditor: Boolean,
    ): HudSize {
        val w = 165f
        val h = 61.8917f
        UiDraw.drawIconStretched(ctx, UiIcons.WATERMARK_BADGE, 0f, 0f, w, h, UiPalette.accent(1f))
        UiDraw.drawIconStretched(ctx, UiIcons.WATERMARK_FG, 0f, 0f, w, h, UiPalette.TEXT_COLOR)
        return HudSize(w, h)
    }
}

/**
 * 2. ArrayList (`ArrayList.svelte`: enabled non-hidden modules with tag, smooth fly/flip animation, 4px accent border)
 */
class ArrayListHudComponent : HudComponent(
    name = "ArrayList",
    enabledByDefault = true,
    defaultAlignment = Alignment(Alignment.ScreenAxisX.RIGHT, 0, Alignment.ScreenAxisY.TOP, 0),
    componentDescription = "Shows currently enabled modules.",
) {
    private val showTags by boolean("ShowTags", true)
    private val itemAlignment by enumChoice("ItemAlignment", Side.RIGHT)
    private val order by enumChoice("Order", SortOrder.DESCENDING)

    enum class Side(override val tag: String) : Tagged {
        LEFT("Left"),
        RIGHT("Right"),
    }

    enum class SortOrder(override val tag: String) : Tagged {
        ASCENDING("Ascending"),
        DESCENDING("Descending"),
    }

    private class AnimEntry(var y: Float, var slideProgress: Float)
    private val animMap = HashMap<String, AnimEntry>()

    override fun renderHud(
        ctx: GuiGraphicsExtractor,
        tickDelta: Float,
        dtSeconds: Float,
        hudWidth: Float,
        hudHeight: Float,
        inEditor: Boolean,
    ): HudSize {
        data class Row(
            val module: ClientModule,
            val nameStr: String,
            val tagStr: String?,
            val textW: Float,
        )

        val rows = ArrayList<Row>()
        for (m in ModuleManager) {
            if (!m.enabled || m.hidden) continue
            val nameStr = UiLocalization.moduleName(m.name)
            val rawTag = if (showTags) m.tag else null
            val tagVal = rawTag?.let { if (UiLocalization.isChinese) UiLocalization.displayName(it) else it }
            val nw = UiDraw.measureText(nameStr, 14f, UiFontWeight.MEDIUM)
            val tw = if (!tagVal.isNullOrEmpty()) UiDraw.measureText(" $tagVal", 14f, UiFontWeight.MEDIUM) else 0f
            rows.add(Row(m, nameStr, tagVal, nw + tw))
        }

        if (order == SortOrder.ASCENDING) {
            rows.sortBy { it.textW }
        } else {
            rows.sortByDescending { it.textW }
        }

        val activeNames = rows.mapTo(HashSet(rows.size * 2)) { it.module.name }
        animMap.keys.retainAll(activeNames)

        if (rows.isEmpty()) {
            if (inEditor) {
                val sampleLabel = UiLocalization.hudName("ArrayList")
                val sampleW = max(110f, UiDraw.measureText(sampleLabel, 14f, UiFontWeight.MEDIUM) + 24f)
                val sampleH = 26f
                UiDraw.fillCssBox(ctx, 0f, 0f, sampleW, sampleH, 4f, 0f, 0f, 4f, UiPalette.surface(0.68f))
                UiDraw.fillBox(ctx, 0f, 0f, 4f, sampleH, UiPalette.accent(1f), radius = 2f)
                UiDraw.drawText(ctx, sampleLabel, 12f, 5f, 14f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM)
                return HudSize(sampleW, sampleH)
            }
            return HudSize(100f, 26f)
        }

        val padX = 8f
        val borderL = 4f
        val lh = UiDraw.lineHeight(14f, UiFontWeight.MEDIUM)
        val rowH = lh + 10f
        var maxRowW = 0f
        for (r in rows) {
            val w = borderL + padX * 2f + r.textW
            if (w > maxRowW) maxRowW = w
        }

        rows.forEachIndexed { idx, row ->
            val targetY = idx * rowH
            val anim = animMap.getOrPut(row.module.name) { AnimEntry(targetY, 0f) }
            anim.y = UiAnimation.expLerp(anim.y, targetY, 16f, dtSeconds)
            anim.slideProgress = UiAnimation.expLerp(anim.slideProgress, 1f, 15f, dtSeconds)

            val boxW = borderL + padX * 2f + row.textW
            val baseX = if (itemAlignment == Side.LEFT) 0f else (maxRowW - boxW)
            val flyOffX = (1f - anim.slideProgress) * (if (itemAlignment == Side.LEFT) -36f else 36f)
            val rx = baseX + flyOffX
            val ry = anim.y

            UiDraw.fillCssBox(ctx, rx, ry, boxW, rowH, 4f, 0f, 0f, 4f, UiPalette.surface(0.68f))
            UiDraw.fillCssBox(ctx, rx, ry, borderL, rowH, 4f, 0f, 0f, 4f, UiPalette.accent(1f))

            val tx = rx + borderL + padX
            val ty = ry + (rowH - lh) * 0.5f
            val nw = UiDraw.drawText(ctx, row.nameStr, tx, ty, 14f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM)
            if (!row.tagStr.isNullOrEmpty()) {
                UiDraw.drawText(ctx, " ${row.tagStr}", tx + nw, ty, 14f, UiPalette.ARRAYLIST_TAG_COLOR, UiFontWeight.MEDIUM)
            }
        }

        return HudSize(maxRowW, rows.size * rowH)
    }
}

/**
 * 3. TabGui (`TabGui.svelte`: arrow-key category & module navigator with masked tinted category icons)
 */
class TabGuiHudComponent : HudComponent(
    name = "TabGui",
    enabledByDefault = false,
    defaultAlignment = Alignment(Alignment.ScreenAxisX.LEFT, 15, Alignment.ScreenAxisY.TOP, 90),
    componentDescription = "Provides keyboard navigation for modules.",
) {
    private var selectedCategoryIdx = 0
    private var modulesOpen = false
    private var selectedModuleIdx = 0
    private var moduleScroll = 0f
    private var openSlide = 0f

    private fun categories(): List<ModuleCategory> = ModuleCategories.entries.toList()

    private fun modulesInSelectedCategory(cats: List<ModuleCategory>): List<ClientModule> {
        if (cats.isEmpty()) return emptyList()
        val cat = cats[selectedCategoryIdx.coerceIn(0, cats.lastIndex)]
        return ModuleManager.filter { it.category == cat }.sortedBy { UiTextUtils.displayName(it.name) }
    }

    @Suppress("unused")
    private val keyHandler = handler<KeyboardKeyEvent> { event ->
        if (!running || event.screen != null) return@handler
        if (!event.isPressed && !event.isRepeat) return@handler

        val cats = categories()
        if (cats.isEmpty()) return@handler
        selectedCategoryIdx = selectedCategoryIdx.coerceIn(0, cats.lastIndex)

        when (event.keyCode) {
            InputConstants.KEY_DOWN -> {
                if (!modulesOpen) {
                    selectedCategoryIdx = (selectedCategoryIdx + 1) % cats.size
                } else {
                    val mods = modulesInSelectedCategory(cats)
                    if (mods.isNotEmpty()) {
                        selectedModuleIdx = (selectedModuleIdx + 1) % mods.size
                    }
                }
            }
            InputConstants.KEY_UP -> {
                if (!modulesOpen) {
                    selectedCategoryIdx = (selectedCategoryIdx - 1 + cats.size) % cats.size
                } else {
                    val mods = modulesInSelectedCategory(cats)
                    if (mods.isNotEmpty()) {
                        selectedModuleIdx = (selectedModuleIdx - 1 + mods.size) % mods.size
                    }
                }
            }
            InputConstants.KEY_RIGHT -> {
                if (!modulesOpen) {
                    modulesOpen = true
                    selectedModuleIdx = 0
                }
            }
            InputConstants.KEY_LEFT -> {
                modulesOpen = false
                selectedModuleIdx = 0
            }
            InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                if (modulesOpen) {
                    val mods = modulesInSelectedCategory(cats)
                    if (selectedModuleIdx in mods.indices) {
                        val m = mods[selectedModuleIdx]
                        m.enabled = !m.enabled
                    }
                }
            }
        }
    }

    override fun renderHud(
        ctx: GuiGraphicsExtractor,
        tickDelta: Float,
        dtSeconds: Float,
        hudWidth: Float,
        hudHeight: Float,
        inEditor: Boolean,
    ): HudSize {
        val cats = categories()
        if (cats.isEmpty()) return HudSize(150f, 120f)
        selectedCategoryIdx = selectedCategoryIdx.coerceIn(0, cats.lastIndex)

        val iconColW = 42f
        var maxCatTextW = 78f
        for (c in cats) {
            maxCatTextW = max(maxCatTextW, UiDraw.measureText(UiLocalization.categoryName(c.tag), 14f, UiFontWeight.MEDIUM) + 24f)
        }
        val catRowH = 31f
        val catPanelW = iconColW + maxCatTextW
        val catPanelH = cats.size * catRowH

        UiDraw.withCssScissor(ctx, 0f, 0f, catPanelW, catPanelH) {
            cats.forEachIndexed { idx, cat ->
                val ry = idx * catRowH
                val selected = idx == selectedCategoryIdx
                val rTL = if (idx == 0) 5f else 0f
                val rBL = if (idx == cats.lastIndex) 5f else 0f
                val rTR = if (idx == 0) 5f else 0f
                val rBR = if (idx == cats.lastIndex) 5f else 0f

                UiDraw.fillCssBox(ctx, 0f, ry, iconColW, catRowH, rTL, 0f, 0f, rBL, UiPalette.surface(0.68f))
                val nameBg = if (selected) UiPalette.accent(1f) else UiPalette.surface(0.50f)
                UiDraw.fillCssBox(ctx, iconColW, ry, maxCatTextW, catRowH, 0f, rTR, rBR, 0f, nameBg)

                UiDraw.drawIcon(
                    ctx = ctx,
                    id = UiIcons.tabGuiIcon(cat.tag),
                    x = (iconColW - 16f) * 0.5f,
                    y = ry + (catRowH - 16f) * 0.5f,
                    boxW = 16f,
                    boxH = 16f,
                    argb = if (selected) UiPalette.accent(1f) else UiPalette.TEXT_COLOR,
                )
                val lh = UiDraw.lineHeight(14f, UiFontWeight.MEDIUM)
                UiDraw.drawText(
                    ctx, UiLocalization.categoryName(cat.tag),
                    iconColW + 12f, ry + (catRowH - lh) * 0.5f,
                    14f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM,
                )
            }
        }

        openSlide = UiAnimation.expLerp(openSlide, if (modulesOpen) 1f else 0f, 16f, dtSeconds)
        var totalW = catPanelW

        if (modulesOpen || openSlide > 0.02f) {
            val mods = modulesInSelectedCategory(cats)
            if (mods.isNotEmpty()) {
                selectedModuleIdx = selectedModuleIdx.coerceIn(0, mods.lastIndex)
                val modRowH = 26f
                var modPanelW = 110f
                for (m in mods) {
                    val dn = UiLocalization.moduleName(m.name)
                    modPanelW = max(modPanelW, UiDraw.measureText(dn, 12f, UiFontWeight.MEDIUM) + 30f)
                }

                // Smooth scroll selected module into view
                val selTop = selectedModuleIdx * modRowH
                val selBottom = selTop + modRowH
                if (selTop < moduleScroll) moduleScroll = selTop
                if (selBottom > moduleScroll + catPanelH) moduleScroll = selBottom - catPanelH
                moduleScroll = moduleScroll.coerceIn(0f, max(0f, mods.size * modRowH - catPanelH))

                val mx = catPanelW + 6f - (1f - openSlide) * 10f
                UiDraw.fillBox(ctx, mx, 0f, modPanelW, catPanelH, UiPalette.surface(0.50f), radius = 5f)
                UiDraw.withCssScissor(ctx, mx, 0f, modPanelW, catPanelH) {
                    val lh = UiDraw.lineHeight(12f, UiFontWeight.MEDIUM)
                    mods.forEachIndexed { idx, mod ->
                        val ry = idx * modRowH - moduleScroll
                        if (ry + modRowH < 0f || ry > catPanelH) return@forEachIndexed
                        val sel = idx == selectedModuleIdx
                        if (sel) {
                            UiDraw.fillBox(ctx, mx, ry, modPanelW, modRowH, UiPalette.surface(0.36f))
                        }
                        val dn = UiLocalization.moduleName(mod.name)
                        val shiftX = if (sel) 5f else 0f
                        val col = if (mod.enabled) UiPalette.TEXT_COLOR else UiPalette.TEXT_DIMMED_COLOR
                        UiDraw.drawText(ctx, dn, mx + 10f + shiftX, ry + (modRowH - lh) * 0.5f, 12f, col, UiFontWeight.MEDIUM)
                    }
                }
                totalW = catPanelW + 6f + modPanelW
            }
        }

        return HudSize(totalW, catPanelH)
    }
}

/**
 * 4. KeyBinds (`KeyBinds.svelte`: `Binds` header + `icon-keybinds.png` + bound module rows `[Ctrl+R]`)
 */
class KeyBindsHudComponent : HudComponent(
    name = "KeyBinds",
    enabledByDefault = true,
    defaultAlignment = Alignment(Alignment.ScreenAxisX.LEFT, 10, Alignment.ScreenAxisY.TOP, 90),
    componentDescription = "Shows active module key bindings.",
) {
    override fun renderHud(
        ctx: GuiGraphicsExtractor,
        tickDelta: Float,
        dtSeconds: Float,
        hudWidth: Float,
        hudHeight: Float,
        inEditor: Boolean,
    ): HudSize {
        val boundModules = ModuleManager.filter { !it.bind.isUnbound }

        val keyFontWeight = if (UiLocalization.isChinese) UiFontWeight.MEDIUM else UiFontWeight.MONOSPACE
        data class BindRow(val name: String, val keyText: String, val enabled: Boolean)
        val rows = boundModules.map { m ->
            BindRow(
                name = UiLocalization.moduleName(m.name),
                keyText = "[${UiTextUtils.formatInputBind(m.bind)}]",
                enabled = m.enabled,
            )
        }

        var boxW = 150f
        for (r in rows) {
            val rw = UiDraw.measureText(r.name, 14f, UiFontWeight.MEDIUM) +
                UiDraw.measureText(r.keyText, 11f, keyFontWeight) + 32f
            boxW = max(boxW, min(200f, rw))
        }

        val headerH = 30f
        val rowH = 20f
        val bodyRows = max(1, rows.size)
        val bodyH = 12f + bodyRows * rowH - (if (rows.isNotEmpty()) 2f else 0f)
        val totalH = headerH + bodyH

        UiDraw.fillCssBox(ctx, 0f, 0f, boxW, headerH, 5f, 5f, 0f, 0f, UiPalette.surface(0.68f))
        val titleLh = UiDraw.lineHeight(14f, UiFontWeight.SEMIBOLD)
        UiDraw.drawText(
            ctx, UiLocalization.text("Binds", "快捷键绑定"),
            10f, (headerH - titleLh) * 0.5f, 14f, UiPalette.TEXT_COLOR, UiFontWeight.SEMIBOLD,
        )
        UiDraw.drawIcon(ctx, UiIcons.KEYBINDS, boxW - 26f, (headerH - 16f) * 0.5f, 16f, 16f, UiPalette.TEXT_COLOR)

        UiDraw.fillCssBox(ctx, 0f, headerH, boxW, bodyH, 0f, 0f, 5f, 5f, UiPalette.surface(0.50f))

        if (rows.isEmpty()) {
            UiDraw.drawText(
                ctx, UiLocalization.text("No key bindings", "暂无按键绑定"),
                10f, headerH + 6f, 13f, UiPalette.TEXT_DIMMED_COLOR,
                italic = !UiLocalization.isChinese,
            )
        } else {
            rows.forEachIndexed { idx, r ->
                val ry = headerH + 6f + idx * rowH
                val kw = UiDraw.measureText(r.keyText, 11f, keyFontWeight)
                val maxNameW = max(30f, boxW - 20f - kw - 12f)
                val clipped = UiDraw.ellipsize(r.name, maxNameW, 14f, UiFontWeight.MEDIUM)
                val nameColor = if (r.enabled) UiPalette.SUCCESS_COLOR else UiPalette.TEXT_COLOR
                UiDraw.drawText(ctx, clipped, 10f, ry, 14f, nameColor, if (r.enabled) UiFontWeight.MEDIUM else UiFontWeight.REGULAR)

                val keyColor = if (r.enabled) UiPalette.accent(1f) else UiPalette.white(0.65f)
                UiDraw.drawText(ctx, r.keyText, boxW - 10f - kw, ry + 2f, 11f, keyColor, keyFontWeight)
            }
        }

        return HudSize(boxW, totalH)
    }
}

/**
 * 5. Taco (`Taco.svelte`: 12-frame animated taco strolling across the screen!)
 */
class TacoHudComponent : HudComponent(
    name = "Taco",
    enabledByDefault = false,
    defaultAlignment = Alignment(Alignment.ScreenAxisX.LEFT, 0, Alignment.ScreenAxisY.BOTTOM, 65),
    componentDescription = "Displays a friendly taco.",
) {
    private var walkX = -100f

    override fun renderHud(
        ctx: GuiGraphicsExtractor,
        tickDelta: Float,
        dtSeconds: Float,
        hudWidth: Float,
        hudHeight: Float,
        inEditor: Boolean,
    ): HudSize {
        val h = 60f
        val tacoW = 86f
        // 043 advances 100px per 500ms -> 200px/sec linear
        walkX += dtSeconds * 200f
        if (walkX > hudWidth + 100f) {
            walkX = -100f
        }
        val frameIdx = ((System.currentTimeMillis() / 70L) % 12L).toInt()
        val drawX = if (inEditor && (walkX < 0f || walkX > hudWidth - tacoW)) 16f else walkX
        UiDraw.drawIcon(ctx, UiIcons.tacoFrame(frameIdx), drawX, 0f, tacoW, h, UiPalette.TEXT_COLOR)
        return HudSize(max(120f, hudWidth), h)
    }
}
