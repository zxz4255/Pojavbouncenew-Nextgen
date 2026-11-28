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

package net.ccbluex.liquidbounce.ui.clickgui.setting

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.config.types.list.ItemListValue
import net.ccbluex.liquidbounce.config.types.list.MutableListValue
import net.ccbluex.liquidbounce.config.types.list.RegistryListValue
import net.ccbluex.liquidbounce.config.types.list.RegistryMutableListValue
import net.ccbluex.liquidbounce.ui.common.UiAnimation
import net.ccbluex.liquidbounce.ui.common.UiDraw
import net.ccbluex.liquidbounce.ui.common.UiFontWeight
import net.ccbluex.liquidbounce.ui.common.UiIcons
import net.ccbluex.liquidbounce.ui.common.UiLocalization
import net.ccbluex.liquidbounce.ui.common.UiPalette
import net.ccbluex.liquidbounce.ui.common.UiTextEditState
import net.ccbluex.liquidbounce.ui.common.UiTextUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.util.SequencedSet
import kotlin.math.max
import kotlin.math.min

/**
 * 20. MUTABLE_LIST (`MutableListSetting.svelte`: user-editable string/typed list with add box, remove row, reset, smooth expand & scroll)
 */
class MutableListSettingNode<T : MutableCollection<E>, E>(
    override val value: MutableListValue<T, E>,
) : SettingNode(value) {

    private var expanded by rememberedBoolean("expanded")
    private var expandAnim = if (expanded) 1f else 0f
    private var targetScroll by rememberedScroll()
    private var scrollOffset = targetScroll
    private val addEditor = UiTextEditState(
        placeholderInitial(),
        maxLength = 512,
        onCommit = { raw -> tryAddEntry(raw) },
    )

    companion object {
        private fun placeholderInitial() = ""
    }

    override fun snapLayoutState() {
        expandAnim = if (expanded) 1f else 0f
        scrollOffset = targetScroll
    }

    override fun stepAnimations(dtSeconds: Float) {
        expandAnim = UiAnimation.expLerp(expandAnim, if (expanded) 1f else 0f, 16f, dtSeconds)
        scrollOffset = UiAnimation.expLerp(scrollOffset, targetScroll, 18f, dtSeconds)
    }

    @Suppress("UNCHECKED_CAST")
    private fun tryAddEntry(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        val parsed: E = runCatching {
            val d = value.innerValueType.deserializer
            if (value.innerType == Regex::class.java) {
                // Regex lists also advertise TEXT; the TEXT deserializer returns String.
                (TextSettingCodec.regex(raw) ?: return) as E
            } else if (d != null) {
                d.deserializeThrowing(trimmed) as E
            } else {
                trimmed as E
            }
        }.getOrNull() ?: return

        val col = value.get()
        col.add(parsed)
        value.set(col)
        addEditor.forceText("")
    }

    private fun fullBodyHeight(): Float {
        val rowCount = value.get().size
        val listH = min(132f, max(24f, rowCount * 22f))
        return 28f + listH + 6f
    }

    override fun measureHeight(width: Float): Float {
        if (!expanded && expandAnim <= 0.002f) return 28f
        return 28f + fullBodyHeight() * expandAnim
    }

    override fun render(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        width: Float,
        mouseX: Float,
        mouseY: Float,
        dtSeconds: Float,
        canvasWidth: Float,
    ): Float {
        val h = measureHeight(width)
        recordBounds(x, y, width, h)

        val entries = value.get().toList()
        UiDraw.drawText(ctx, UiTextUtils.displayName(value.name), x, y + 6f, 12f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM)

        val countStr = entries.size.toString()
        val cw = UiDraw.measureText(countStr, 11f, UiFontWeight.MEDIUM)
        val rightShift = 18f + 14f * expandAnim
        UiDraw.drawText(ctx, countStr, x + width - rightShift - cw, y + 6.5f, 11f, UiPalette.TEXT_DIMMED_COLOR, UiFontWeight.MEDIUM)

        if (expandAnim > 0.2f) {
            UiDraw.drawIcon(ctx, UiIcons.RESET, x + width - 29f, y + 7f, 11f, 11f, UiPalette.white(0.75f * expandAnim))
        }
        val chevronRot = (-Math.PI.toFloat() * 0.5f) * (1f - expandAnim)
        val chevronCol = UiPalette.lerpArgb(UiPalette.white(0.5f), UiPalette.TEXT_COLOR, expandAnim)
        UiDraw.drawIcon(
            ctx, UiIcons.SETTINGS_EXPAND,
            x + width - 13f, y + 9f, 9f, 7f,
            argb = chevronCol,
            rotationRad = chevronRot,
        )

        val animBodyH = fullBodyHeight() * expandAnim
        if (animBodyH > 0.5f) {
            val childMx = if (expanded && expandAnim > 0.85f) mouseX else -10000f
            val childMy = if (expanded && expandAnim > 0.85f) mouseY else -10000f
            UiDraw.withCssScissor(ctx, x - 4f, y + 27f, width + 8f, animBodyH + 1f) {
                val addY = y + 28f
                val addW = width - 26f
                UiDraw.fillBox(
                    ctx, x, addY, addW, 22f,
                    UiPalette.inputBg, radius = 3f,
                    borderColor = if (addEditor.focused) UiPalette.accent(1f) else UiPalette.inputBorder,
                    borderWidth = 1f,
                )
                UiDraw.drawTextFieldContent(
                    ctx, addEditor, x, addY, addW, 22f, 11.5f,
                    placeholder = UiLocalization.text("Add value...", "输入并添加条目..."),
                )
                UiDraw.fillBox(ctx, x + width - 22f, addY, 22f, 22f, UiPalette.accent(0.85f), radius = 3f)
                val plusW = UiDraw.measureText("+", 14f, UiFontWeight.BOLD)
                UiDraw.drawText(ctx, "+", x + width - 22f + (22f - plusW) * 0.5f, addY + 2f, 14f, UiPalette.TEXT_COLOR, UiFontWeight.BOLD)

                val listY = addY + 26f
                val listH = min(132f, max(24f, entries.size * 22f))
                val maxScroll = max(0f, entries.size * 22f - listH)
                targetScroll = targetScroll.coerceIn(0f, maxScroll)
                scrollOffset = scrollOffset.coerceIn(0f, maxScroll)

                UiDraw.fillBox(ctx, x, listY, width, listH, UiPalette.surface(0.35f), radius = 3f)
                if (entries.isEmpty()) {
                    UiDraw.drawText(
                        ctx, UiLocalization.text("Empty", "列表为空"),
                        x + 8f, listY + 5f, 11f, UiPalette.TEXT_MUTED_COLOR, UiFontWeight.REGULAR,
                        italic = !UiLocalization.isChinese,
                    )
                } else {
                    UiDraw.withCssScissor(ctx, x, listY, width, listH) {
                        entries.forEachIndexed { idx, item ->
                            val ry = listY + idx * 22f - scrollOffset
                            if (ry + 22f < listY || ry > listY + listH) return@forEachIndexed
                            val hovered = childMx in x..(x + width) && childMy in max(listY, ry)..min(listY + listH, ry + 22f)
                            if (hovered) {
                                UiDraw.fillBox(ctx, x + 2f, ry + 1f, width - 4f, 20f, UiPalette.white(0.06f), radius = 2f)
                            }
                            val str = UiDraw.ellipsize(item.toString(), width - 28f, 11.5f, UiFontWeight.REGULAR)
                            UiDraw.drawText(ctx, str, x + 8f, ry + 4f, 11.5f, UiPalette.TEXT_COLOR, UiFontWeight.REGULAR)
                            UiDraw.drawIcon(ctx, UiIcons.CROSS, x + width - 16f, ry + 6f, 9f, 9f, UiPalette.white(if (hovered) 0.9f else 0.5f))
                        }
                    }
                }
            }
        }

        maybeShowTooltip(mouseX, mouseY, x, y, width - 34f, 24f, canvasWidth)
        return h
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int, shiftHeld: Boolean): Boolean {
        if (mouseX !in lastX..(lastX + lastWidth) || mouseY !in lastY..(lastY + lastHeight)) return false
        if (mouseY <= lastY + 26f) {
            if (expanded && button == InputConstants.MOUSE_BUTTON_LEFT &&
                mouseX in (lastX + lastWidth - 32f)..(lastX + lastWidth - 17f)
            ) {
                value.restore()
                return true
            }
            expanded = !expanded
            return true
        }
        if (!expanded || expandAnim <= 0.5f || button != InputConstants.MOUSE_BUTTON_LEFT) return false

        val addY = lastY + 28f
        if (mouseY in addY..(addY + 22f)) {
            if (mouseX >= lastX + lastWidth - 22f) {
                tryAddEntry(addEditor.text)
            } else {
                SettingOverlayManager.focusTextInput(addEditor, selectAll = false)
                addEditor.onMouseDown(mouseX - (lastX + 8f), 11.5f, UiFontWeight.REGULAR, shiftHeld)
            }
            return true
        }

        val entries = value.get().toMutableList()
        val listY = addY + 26f
        val listH = min(132f, max(24f, entries.size * 22f))
        if (mouseY in listY..(listY + listH) && mouseX >= lastX + lastWidth - 20f) {
            val idx = ((mouseY - listY + scrollOffset) / 22f).toInt()
            if (idx in entries.indices) {
                entries.removeAt(idx)
                val col = value.get()
                col.clear()
                col.addAll(entries)
                value.set(col)
                return true
            }
        }
        return false
    }

    override fun mouseScrolled(mouseX: Float, mouseY: Float, scrollY: Double): Boolean {
        if (!expanded || expandAnim <= 0.5f) return false
        val listY = lastY + 54f
        val entriesSize = value.get().size
        val listH = min(132f, max(24f, entriesSize * 22f))
        if (mouseX in lastX..(lastX + lastWidth) && mouseY in listY..(listY + listH)) {
            val maxScroll = max(0f, entriesSize * 22f - listH)
            if (maxScroll > 0f) {
                val prev = targetScroll
                targetScroll = (targetScroll - scrollY.toFloat() * 30f).coerceIn(0f, maxScroll)
                if (kotlin.math.abs(targetScroll - prev) > 0.01f) {
                    return true
                }
            }
        }
        return false
    }
}

/**
 * 21. NAMED_ITEM_LIST (`ItemListSetting.svelte`: virtualized searchable checklist + smooth expand & scroll)
 */
class NamedItemListSettingNode<T : MutableSet<E>, E>(
    override val value: ItemListValue<T, E>,
) : SettingNode(value) {

    private var expanded by rememberedBoolean("expanded")
    private var expandAnim = if (expanded) 1f else 0f
    private var targetScroll by rememberedScroll()
    private var scrollOffset = targetScroll
    private val searchEditor = UiTextEditState("", maxLength = 128, onChanged = {
        targetScroll = 0f
        scrollOffset = 0f
    })

    override fun snapLayoutState() {
        expandAnim = if (expanded) 1f else 0f
        scrollOffset = targetScroll
    }

    override fun stepAnimations(dtSeconds: Float) {
        expandAnim = UiAnimation.expLerp(expandAnim, if (expanded) 1f else 0f, 16f, dtSeconds)
        scrollOffset = UiAnimation.expLerp(scrollOffset, targetScroll, 18f, dtSeconds)
    }

    private fun filteredItems(): List<ItemListValue.NamedItem<E>> {
        val q = searchEditor.text.trim()
        if (q.isEmpty()) return value.items.toList()
        return value.items.filter { UiLocalization.matchesQuery(q, it.name, it.value.toString()) }
    }

    private fun fullBodyHeight(): Float {
        val filtered = filteredItems()
        val listH = min(154f, max(24f, filtered.size * 22f))
        return 26f + listH + 6f
    }

    override fun measureHeight(width: Float): Float {
        if (!expanded && expandAnim <= 0.002f) return 28f
        return 28f + fullBodyHeight() * expandAnim
    }

    override fun render(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        width: Float,
        mouseX: Float,
        mouseY: Float,
        dtSeconds: Float,
        canvasWidth: Float,
    ): Float {
        val h = measureHeight(width)
        recordBounds(x, y, width, h)

        val selectedSet = value.get()
        UiDraw.drawText(ctx, UiTextUtils.displayName(value.name), x, y + 6f, 12f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM)
        val countStr = "${selectedSet.size}/${value.items.size}"
        val cw = UiDraw.measureText(countStr, 11f, UiFontWeight.MEDIUM)
        UiDraw.drawText(ctx, countStr, x + width - 18f - cw, y + 6.5f, 11f, UiPalette.TEXT_DIMMED_COLOR, UiFontWeight.MEDIUM)

        val chevronRot = (-Math.PI.toFloat() * 0.5f) * (1f - expandAnim)
        val chevronCol = UiPalette.lerpArgb(UiPalette.white(0.5f), UiPalette.TEXT_COLOR, expandAnim)
        UiDraw.drawIcon(
            ctx, UiIcons.SETTINGS_EXPAND,
            x + width - 13f, y + 9f, 9f, 7f,
            argb = chevronCol,
            rotationRad = chevronRot,
        )

        val animBodyH = fullBodyHeight() * expandAnim
        if (animBodyH > 0.5f) {
            val childMx = if (expanded && expandAnim > 0.85f) mouseX else -10000f
            val childMy = if (expanded && expandAnim > 0.85f) mouseY else -10000f
            UiDraw.withCssScissor(ctx, x - 4f, y + 27f, width + 8f, animBodyH + 1f) {
                val searchY = y + 28f
                UiDraw.fillBox(
                    ctx, x, searchY, width, 22f,
                    UiPalette.inputBg, radius = 3f,
                    borderColor = if (searchEditor.focused) UiPalette.accent(1f) else UiPalette.inputBorder,
                    borderWidth = 1f,
                )
                UiDraw.drawTextFieldContent(
                    ctx, searchEditor, x, searchY, width, 22f, 11.5f,
                    placeholder = UiLocalization.text("Search...", "搜索..."),
                )

                val items = filteredItems()
                val listY = searchY + 26f
                val listH = min(154f, max(24f, items.size * 22f))
                val maxScroll = max(0f, items.size * 22f - listH)
                targetScroll = targetScroll.coerceIn(0f, maxScroll)
                scrollOffset = scrollOffset.coerceIn(0f, maxScroll)

                UiDraw.fillBox(ctx, x, listY, width, listH, UiPalette.surface(0.35f), radius = 3f)
                if (items.isEmpty()) {
                    UiDraw.drawText(ctx, UiLocalization.text("No results", "无匹配结果"), x + 8f, listY + 5f, 11f, UiPalette.TEXT_MUTED_COLOR)
                } else {
                    UiDraw.withCssScissor(ctx, x, listY, width, listH) {
                        val firstIdx = (scrollOffset / 22f).toInt().coerceAtLeast(0)
                        val lastIdx = ((scrollOffset + listH) / 22f).toInt().coerceAtMost(items.lastIndex)
                        for (idx in firstIdx..lastIdx) {
                            val item = items[idx]
                            val ry = listY + idx * 22f - scrollOffset
                            val checked = item.value in selectedSet
                            val hovered = childMx in x..(x + width) && childMy in max(listY, ry)..min(listY + listH, ry + 22f)
                            if (hovered) {
                                UiDraw.fillBox(ctx, x + 2f, ry + 1f, width - 4f, 20f, UiPalette.white(0.06f), radius = 2f)
                            }
                            val label = UiDraw.ellipsize(UiTextUtils.displayName(item.name), width - 28f, 11.5f, UiFontWeight.REGULAR)
                            UiDraw.drawText(
                                ctx, label, x + 8f, ry + 4f, 11.5f,
                                if (checked) UiPalette.TEXT_COLOR else UiPalette.TEXT_DIMMED_COLOR,
                                if (checked) UiFontWeight.MEDIUM else UiFontWeight.REGULAR,
                            )
                            UiDraw.drawIcon(
                                ctx, UiIcons.TICK,
                                x + width - 17f, ry + 6f, 10f, 10f,
                                if (checked) UiPalette.accent(1f) else UiPalette.white(0.22f),
                            )
                        }
                    }
                }
            }
        }

        maybeShowTooltip(mouseX, mouseY, x, y, width, 24f, canvasWidth)
        return h
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int, shiftHeld: Boolean): Boolean {
        if (mouseX !in lastX..(lastX + lastWidth) || mouseY !in lastY..(lastY + lastHeight)) return false
        if (mouseY <= lastY + 26f) {
            expanded = !expanded
            return true
        }
        if (!expanded || expandAnim <= 0.5f || button != InputConstants.MOUSE_BUTTON_LEFT) return false

        val searchY = lastY + 28f
        if (mouseY in searchY..(searchY + 22f)) {
            SettingOverlayManager.focusTextInput(searchEditor, selectAll = false)
            searchEditor.onMouseDown(mouseX - (lastX + 8f), 11.5f, UiFontWeight.REGULAR, shiftHeld)
            return true
        }

        val items = filteredItems()
        val listY = searchY + 26f
        val listH = min(154f, max(24f, items.size * 22f))
        if (mouseY in listY..(listY + listH)) {
            val idx = ((mouseY - listY + scrollOffset) / 22f).toInt()
            if (idx in items.indices) {
                val chosen = items[idx].value
                val set = value.get()
                if (!set.remove(chosen)) {
                    set.add(chosen)
                }
                value.set(set)
                return true
            }
        }
        return false
    }

    override fun mouseScrolled(mouseX: Float, mouseY: Float, scrollY: Double): Boolean {
        if (!expanded || expandAnim <= 0.5f) return false
        val items = filteredItems()
        val listY = lastY + 54f
        val listH = min(154f, max(24f, items.size * 22f))
        if (mouseX in lastX..(lastX + lastWidth) && mouseY in listY..(listY + listH)) {
            val maxScroll = max(0f, items.size * 22f - listH)
            if (maxScroll > 0f) {
                val prev = targetScroll
                targetScroll = (targetScroll - scrollY.toFloat() * 32f).coerceIn(0f, maxScroll)
                if (kotlin.math.abs(targetScroll - prev) > 0.01f) {
                    return true
                }
            }
        }
        return false
    }
}

/**
 * 22. REGISTRY_LIST (`RegistryListSetting.svelte`: virtualized searchable registry checklist + smooth expand & scroll)
 */
class RegistryListSettingNode<T : SequencedSet<E>, E>(
    override val value: RegistryListValue<T, E>,
) : SettingNode(value) {

    private var expanded by rememberedBoolean("expanded")
    private var expandAnim = if (expanded) 1f else 0f
    private var onlySelected by rememberedBoolean("onlySelected")
    private var targetScroll by rememberedScroll()
    private var scrollOffset = targetScroll
    private var cachedCatalog: List<RegistryCatalog.CatalogEntry<E>>? = null

    private val searchEditor = UiTextEditState("", maxLength = 128, onChanged = {
        targetScroll = 0f
        scrollOffset = 0f
    })

    override fun snapLayoutState() {
        expandAnim = if (expanded) 1f else 0f
        scrollOffset = targetScroll
    }

    override fun stepAnimations(dtSeconds: Float) {
        expandAnim = UiAnimation.expLerp(expandAnim, if (expanded) 1f else 0f, 16f, dtSeconds)
        scrollOffset = UiAnimation.expLerp(scrollOffset, targetScroll, 18f, dtSeconds)
    }

    private fun catalog(): List<RegistryCatalog.CatalogEntry<E>> {
        val existing = cachedCatalog
        if (existing != null && existing.isNotEmpty()) return existing
        return RegistryCatalog.entriesFor<E>(value.innerValueType).also {
            if (it.isNotEmpty()) cachedCatalog = it
        }
    }

    private fun filteredEntries(): List<RegistryCatalog.CatalogEntry<E>> {
        val all = catalog()
        val selected = value.get()
        val q = searchEditor.text.trim().lowercase()
        return all.filter { entry ->
            if (onlySelected && entry.value !in selected) return@filter false
            q.isEmpty() || entry.searchLower.contains(q)
        }
    }

    private fun fullBodyHeight(): Float {
        val listH = min(168f, max(24f, filteredEntries().size * 24f))
        return 26f + listH + 6f
    }

    override fun measureHeight(width: Float): Float {
        if (!expanded && expandAnim <= 0.002f) return 28f
        return 28f + fullBodyHeight() * expandAnim
    }

    override fun render(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        width: Float,
        mouseX: Float,
        mouseY: Float,
        dtSeconds: Float,
        canvasWidth: Float,
    ): Float {
        val h = measureHeight(width)
        recordBounds(x, y, width, h)

        val selected = value.get()
        UiDraw.drawText(ctx, UiTextUtils.displayName(value.name), x, y + 6f, 12f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM)

        val countStr = "${selected.size}"
        val cw = UiDraw.measureText(countStr, 11f, UiFontWeight.MEDIUM)
        val rightReserve = 18f + 16f * expandAnim
        UiDraw.drawText(ctx, countStr, x + width - rightReserve - cw, y + 6.5f, 11f, UiPalette.accent(1f), UiFontWeight.SEMIBOLD)
        if (expandAnim > 0.2f) {
            UiDraw.drawIcon(ctx, UiIcons.RESET, x + width - 29f, y + 7f, 11f, 11f, UiPalette.white(0.75f * expandAnim))
        }
        val chevronRot = (-Math.PI.toFloat() * 0.5f) * (1f - expandAnim)
        val chevronCol = UiPalette.lerpArgb(UiPalette.white(0.5f), UiPalette.TEXT_COLOR, expandAnim)
        UiDraw.drawIcon(
            ctx, UiIcons.SETTINGS_EXPAND,
            x + width - 13f, y + 9f, 9f, 7f,
            argb = chevronCol,
            rotationRad = chevronRot,
        )

        val animBodyH = fullBodyHeight() * expandAnim
        if (animBodyH > 0.5f) {
            val childMx = if (expanded && expandAnim > 0.85f) mouseX else -10000f
            val childMy = if (expanded && expandAnim > 0.85f) mouseY else -10000f
            UiDraw.withCssScissor(ctx, x - 4f, y + 27f, width + 8f, animBodyH + 1f) {
                val searchY = y + 28f
                val filterBtnW = 24f
                val searchW = width - filterBtnW - 4f
                UiDraw.fillBox(
                    ctx, x, searchY, searchW, 22f,
                    UiPalette.inputBg, radius = 3f,
                    borderColor = if (searchEditor.focused) UiPalette.accent(1f) else UiPalette.inputBorder,
                    borderWidth = 1f,
                )
                val regName = UiTextUtils.displayName(value.registry)
                UiDraw.drawTextFieldContent(
                    ctx, searchEditor, x, searchY, searchW, 22f, 11.5f,
                    placeholder = UiLocalization.text("Search ${value.registry}...", "搜索$regName..."),
                )

                val fx = x + width - filterBtnW
                UiDraw.fillBox(
                    ctx, fx, searchY, filterBtnW, 22f,
                    if (onlySelected) UiPalette.accent(0.85f) else UiPalette.dropdownTriggerBg,
                    radius = 3f,
                )
                UiDraw.drawIcon(ctx, UiIcons.TICK, fx + 7f, searchY + 6f, 10f, 10f, UiPalette.TEXT_COLOR)

                val entries = filteredEntries()
                val rowH = 24f
                val listY = searchY + 26f
                val listH = min(168f, max(24f, entries.size * rowH))
                val maxScroll = max(0f, entries.size * rowH - listH)
                targetScroll = targetScroll.coerceIn(0f, maxScroll)
                scrollOffset = scrollOffset.coerceIn(0f, maxScroll)

                UiDraw.fillBox(ctx, x, listY, width, listH, UiPalette.surface(0.35f), radius = 3f)
                if (entries.isEmpty()) {
                    UiDraw.drawText(ctx, UiLocalization.text("No matching entries", "无匹配条目"), x + 8f, listY + 5f, 11f, UiPalette.TEXT_MUTED_COLOR)
                } else {
                    UiDraw.withCssScissor(ctx, x, listY, width, listH) {
                        val firstIdx = (scrollOffset / rowH).toInt().coerceAtLeast(0)
                        val lastIdx = ((scrollOffset + listH) / rowH).toInt().coerceAtMost(entries.lastIndex)
                        for (idx in firstIdx..lastIdx) {
                            val entry = entries[idx]
                            val ry = listY + idx * rowH - scrollOffset
                            val checked = entry.value in selected
                            val hovered = childMx in x..(x + width) && childMy in max(listY, ry)..min(listY + listH, ry + rowH)
                            if (hovered) {
                                UiDraw.fillBox(ctx, x + 2f, ry + 1f, width - 4f, rowH - 2f, UiPalette.white(0.06f), radius = 2f)
                            }
                            val hasIcon = RegistryCatalog.drawEntryIcon(ctx, entry, x + 5f, ry + 4f, 16f)
                            val textX = if (hasIcon) x + 25f else x + 8f
                            val label = UiDraw.ellipsize(entry.resolvedLabel(), width - (textX - x) - 22f, 11.5f, UiFontWeight.REGULAR)
                            UiDraw.drawText(
                                ctx, label, textX, ry + 5f, 11.5f,
                                if (checked) UiPalette.TEXT_COLOR else UiPalette.TEXT_DIMMED_COLOR,
                                if (checked) UiFontWeight.MEDIUM else UiFontWeight.REGULAR,
                            )
                            UiDraw.drawIcon(
                                ctx, UiIcons.TICK,
                                x + width - 17f, ry + 7f, 10f, 10f,
                                if (checked) UiPalette.accent(1f) else UiPalette.white(0.22f),
                            )
                        }
                    }
                }
            }
        }

        maybeShowTooltip(mouseX, mouseY, x, y, width - 36f, 24f, canvasWidth)
        return h
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int, shiftHeld: Boolean): Boolean {
        if (mouseX !in lastX..(lastX + lastWidth) || mouseY !in lastY..(lastY + lastHeight)) return false
        if (mouseY <= lastY + 26f) {
            if (expanded && button == InputConstants.MOUSE_BUTTON_LEFT &&
                mouseX in (lastX + lastWidth - 32f)..(lastX + lastWidth - 17f)
            ) {
                value.restore()
                return true
            }
            expanded = !expanded
            return true
        }
        if (!expanded || expandAnim <= 0.5f || button != InputConstants.MOUSE_BUTTON_LEFT) return false

        val searchY = lastY + 28f
        if (mouseY in searchY..(searchY + 22f)) {
            if (mouseX >= lastX + lastWidth - 24f) {
                onlySelected = !onlySelected
                targetScroll = 0f
                scrollOffset = 0f
            } else {
                SettingOverlayManager.focusTextInput(searchEditor, selectAll = false)
                searchEditor.onMouseDown(mouseX - (lastX + 8f), 11.5f, UiFontWeight.REGULAR, shiftHeld)
            }
            return true
        }

        val entries = filteredEntries()
        val rowH = 24f
        val listY = searchY + 26f
        val listH = min(168f, max(24f, entries.size * rowH))
        if (mouseY in listY..(listY + listH)) {
            val idx = ((mouseY - listY + scrollOffset) / rowH).toInt()
            if (idx in entries.indices) {
                val chosen = entries[idx].value
                val set = value.get()
                if (!set.remove(chosen)) {
                    set.add(chosen)
                }
                value.set(set)
                return true
            }
        }
        return false
    }

    override fun mouseScrolled(mouseX: Float, mouseY: Float, scrollY: Double): Boolean {
        if (!expanded || expandAnim <= 0.5f) return false
        val entries = filteredEntries()
        val listY = lastY + 54f
        val listH = min(168f, max(24f, entries.size * 24f))
        if (mouseX in lastX..(lastX + lastWidth) && mouseY in listY..(listY + listH)) {
            val maxScroll = max(0f, entries.size * 24f - listH)
            if (maxScroll > 0f) {
                val prev = targetScroll
                targetScroll = (targetScroll - scrollY.toFloat() * 34f).coerceIn(0f, maxScroll)
                if (kotlin.math.abs(targetScroll - prev) > 0.01f) {
                    return true
                }
            }
        }
        return false
    }
}

/**
 * 23. REGISTRY_MUTABLE_LIST (`RegistryMutableListSetting.svelte`: ordered mutable list + smooth expand & scroll!)
 */
class RegistryMutableListSettingNode<T : MutableList<E>, E>(
    override val value: RegistryMutableListValue<T, E>,
) : SettingNode(value) {

    private var expanded by rememberedBoolean("expanded")
    private var expandAnim = if (expanded) 1f else 0f
    private var pickerOpen by rememberedBoolean("pickerOpen")
    private var pickerAnim = if (pickerOpen) 1f else 0f
    private var targetListScroll by rememberedScroll("listScroll")
    private var listScroll = targetListScroll
    private var targetPickerScroll by rememberedScroll("pickerScroll")
    private var pickerScroll = targetPickerScroll
    private var dragIndex = -1
    private var cachedCatalog: List<RegistryCatalog.CatalogEntry<E>>? = null
    private var byValueMap: Map<E, RegistryCatalog.CatalogEntry<E>>? = null

    private val searchEditor = UiTextEditState("", maxLength = 128, onChanged = {
        targetPickerScroll = 0f
        pickerScroll = 0f
    })

    override fun snapLayoutState() {
        expandAnim = if (expanded) 1f else 0f
        pickerAnim = if (pickerOpen) 1f else 0f
        listScroll = targetListScroll
        pickerScroll = targetPickerScroll
        dragIndex = -1
    }

    override fun stepAnimations(dtSeconds: Float) {
        expandAnim = UiAnimation.expLerp(expandAnim, if (expanded) 1f else 0f, 16f, dtSeconds)
        pickerAnim = UiAnimation.expLerp(pickerAnim, if (pickerOpen) 1f else 0f, 16f, dtSeconds)
        listScroll = UiAnimation.expLerp(listScroll, targetListScroll, 18f, dtSeconds)
        pickerScroll = UiAnimation.expLerp(pickerScroll, targetPickerScroll, 18f, dtSeconds)
    }

    private fun catalog(): List<RegistryCatalog.CatalogEntry<E>> {
        val existing = cachedCatalog
        if (existing != null && existing.isNotEmpty()) return existing
        return RegistryCatalog.entriesFor<E>(value.innerValueType).also {
            if (it.isNotEmpty()) {
                cachedCatalog = it
                byValueMap = it.associateBy { e -> e.value }
            }
        }
    }

    private fun entryFor(v: E): RegistryCatalog.CatalogEntry<E>? {
        catalog()
        return byValueMap?.get(v)
    }

    private fun filteredCatalog(): List<RegistryCatalog.CatalogEntry<E>> {
        val q = searchEditor.text.trim().lowercase()
        val all = catalog()
        return if (q.isEmpty()) all else all.filter { it.searchLower.contains(q) }
    }

    private fun fullBodyHeight(): Float {
        val activeListH = min(120f, max(24f, value.get().size * 24f))
        val pickerExtraH = (2f + min(132f, max(24f, filteredCatalog().size * 24f))) * pickerAnim
        val pickerH = 24f + pickerExtraH
        return activeListH + 6f + pickerH + 6f
    }

    override fun measureHeight(width: Float): Float {
        if (!expanded && expandAnim <= 0.002f) return 28f
        return 28f + fullBodyHeight() * expandAnim
    }

    override fun render(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        width: Float,
        mouseX: Float,
        mouseY: Float,
        dtSeconds: Float,
        canvasWidth: Float,
    ): Float {
        val h = measureHeight(width)
        recordBounds(x, y, width, h)

        val currentList = value.get()
        UiDraw.drawText(ctx, UiTextUtils.displayName(value.name), x, y + 6f, 12f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM)

        val countStr = currentList.size.toString()
        val cw = UiDraw.measureText(countStr, 11f, UiFontWeight.MEDIUM)
        val rightReserve = 18f + 16f * expandAnim
        UiDraw.drawText(ctx, countStr, x + width - rightReserve - cw, y + 6.5f, 11f, UiPalette.accent(1f), UiFontWeight.SEMIBOLD)
        if (expandAnim > 0.2f) {
            UiDraw.drawIcon(ctx, UiIcons.RESET, x + width - 29f, y + 7f, 11f, 11f, UiPalette.white(0.75f * expandAnim))
        }
        val chevronRot = (-Math.PI.toFloat() * 0.5f) * (1f - expandAnim)
        val chevronCol = UiPalette.lerpArgb(UiPalette.white(0.5f), UiPalette.TEXT_COLOR, expandAnim)
        UiDraw.drawIcon(
            ctx, UiIcons.SETTINGS_EXPAND,
            x + width - 13f, y + 9f, 9f, 7f,
            argb = chevronCol,
            rotationRad = chevronRot,
        )

        val animBodyH = fullBodyHeight() * expandAnim
        if (animBodyH > 0.5f) {
            val childMx = if (expanded && expandAnim > 0.85f) mouseX else -10000f
            val childMy = if (expanded && expandAnim > 0.85f) mouseY else -10000f
            UiDraw.withCssScissor(ctx, x - 4f, y + 27f, width + 8f, animBodyH + 1f) {
                val rowH = 24f
                val listY = y + 28f
                val listH = min(120f, max(24f, currentList.size * rowH))
                val maxListScroll = max(0f, currentList.size * rowH - listH)
                targetListScroll = targetListScroll.coerceIn(0f, maxListScroll)
                listScroll = listScroll.coerceIn(0f, maxListScroll)

                UiDraw.fillBox(ctx, x, listY, width, listH, UiPalette.surface(0.35f), radius = 3f)
                if (currentList.isEmpty()) {
                    UiDraw.drawText(
                        ctx, UiLocalization.text("Empty sequence", "暂无已选项目"),
                        x + 8f, listY + 5f, 11f, UiPalette.TEXT_MUTED_COLOR,
                        italic = !UiLocalization.isChinese,
                    )
                } else {
                    UiDraw.withCssScissor(ctx, x, listY, width, listH) {
                        val first = (listScroll / rowH).toInt().coerceAtLeast(0)
                        val last = ((listScroll + listH) / rowH).toInt().coerceAtMost(currentList.lastIndex)
                        for (idx in first..last) {
                            val itemVal = currentList[idx]
                            val meta = entryFor(itemVal)
                            val ry = listY + idx * rowH - listScroll
                            val hovered = childMx in x..(x + width) && childMy in max(listY, ry)..min(listY + listH, ry + rowH)
                            UiDraw.fillBox(
                                ctx, x + 2f, ry + 1f, width - 4f, rowH - 2f,
                                if (idx == dragIndex) UiPalette.accent(0.25f) else if (hovered) UiPalette.white(0.06f) else 0,
                                radius = 2f,
                            )
                            UiDraw.drawIcon(ctx, UiIcons.DRAG, x + 5f, ry + 5f, 8f, 13f, UiPalette.white(0.65f))
                            var textX = x + 17f
                            if (meta != null && RegistryCatalog.drawEntryIcon(ctx, meta, textX, ry + 4f, 16f)) {
                                textX += 19f
                            }
                            val label = UiDraw.ellipsize(meta?.resolvedLabel() ?: itemVal.toString(), width - (textX - x) - 20f, 11.5f, UiFontWeight.REGULAR)
                            UiDraw.drawText(ctx, label, textX, ry + 5f, 11.5f, UiPalette.TEXT_COLOR)
                            UiDraw.drawIcon(ctx, UiIcons.CROSS, x + width - 15f, ry + 7f, 9f, 9f, UiPalette.white(if (hovered) 0.95f else 0.5f))
                        }
                    }
                }

                val pickerTop = listY + listH + 6f
                if (!pickerOpen && pickerAnim <= 0.02f) {
                    UiDraw.fillBox(ctx, x, pickerTop, width, 22f, UiPalette.dropdownTriggerBg, radius = 3f)
                    val addLabel = UiLocalization.text("+ Add Item", "+ 添加项目")
                    val aw = UiDraw.measureText(addLabel, 11.5f, UiFontWeight.MEDIUM)
                    UiDraw.drawText(ctx, addLabel, x + (width - aw) * 0.5f, pickerTop + 4f, 11.5f, UiPalette.accent(1f), UiFontWeight.MEDIUM)
                } else {
                    UiDraw.fillBox(
                        ctx, x, pickerTop, width - 24f, 22f,
                        UiPalette.inputBg, radius = 3f,
                        borderColor = if (searchEditor.focused) UiPalette.accent(1f) else UiPalette.inputBorder,
                        borderWidth = 1f,
                    )
                    val regName = UiTextUtils.displayName(value.registry)
                    UiDraw.drawTextFieldContent(
                        ctx, searchEditor, x, pickerTop, width - 24f, 22f, 11.5f,
                        placeholder = UiLocalization.text("Add ${value.registry}...", "添加$regName..."),
                    )
                    UiDraw.fillBox(ctx, x + width - 20f, pickerTop, 20f, 22f, UiPalette.dropdownTriggerBg, radius = 3f)
                    UiDraw.drawIcon(ctx, UiIcons.CROSS, x + width - 14.5f, pickerTop + 6.5f, 9f, 9f, UiPalette.TEXT_COLOR)

                    val cat = filteredCatalog()
                    val pListY = pickerTop + 26f
                    val pListFullH = min(132f, max(24f, cat.size * rowH))
                    val pListAnimH = pListFullH * pickerAnim
                    val maxPickerScroll = max(0f, cat.size * rowH - pListFullH)
                    targetPickerScroll = targetPickerScroll.coerceIn(0f, maxPickerScroll)
                    pickerScroll = pickerScroll.coerceIn(0f, maxPickerScroll)
                    if (pListAnimH > 0.5f) {
                        UiDraw.withCssScissor(ctx, x, pListY, width, pListAnimH) {
                            UiDraw.fillBox(ctx, x, pListY, width, pListFullH, UiPalette.surface(0.45f), radius = 3f)
                            val first = (pickerScroll / rowH).toInt().coerceAtLeast(0)
                            val last = ((pickerScroll + pListFullH) / rowH).toInt().coerceAtMost(cat.lastIndex)
                            for (idx in first..last) {
                                val entry = cat[idx]
                                val ry = pListY + idx * rowH - pickerScroll
                                val hovered = childMx in x..(x + width) && childMy in max(pListY, ry)..min(pListY + pListAnimH, ry + rowH)
                                if (hovered) {
                                    UiDraw.fillBox(ctx, x + 2f, ry + 1f, width - 4f, rowH - 2f, UiPalette.white(0.08f), radius = 2f)
                                }
                                val hasIcon = RegistryCatalog.drawEntryIcon(ctx, entry, x + 5f, ry + 4f, 16f)
                                val tx = if (hasIcon) x + 25f else x + 8f
                                val lbl = UiDraw.ellipsize(entry.resolvedLabel(), width - (tx - x) - 20f, 11.5f, UiFontWeight.REGULAR)
                                UiDraw.drawText(ctx, lbl, tx, ry + 5f, 11.5f, UiPalette.TEXT_DIMMED_COLOR)
                                UiDraw.drawText(ctx, "+", x + width - 14f, ry + 3f, 13f, UiPalette.accent(1f), UiFontWeight.BOLD)
                            }
                        }
                    }
                }
            }
        }

        maybeShowTooltip(mouseX, mouseY, x, y, width - 36f, 24f, canvasWidth)
        return h
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int, shiftHeld: Boolean): Boolean {
        if (mouseX !in lastX..(lastX + lastWidth) || mouseY !in lastY..(lastY + lastHeight)) return false
        if (mouseY <= lastY + 26f) {
            if (expanded && button == InputConstants.MOUSE_BUTTON_LEFT &&
                mouseX in (lastX + lastWidth - 32f)..(lastX + lastWidth - 17f)
            ) {
                value.restore()
                return true
            }
            expanded = !expanded
            return true
        }
        if (!expanded || expandAnim <= 0.5f || button != InputConstants.MOUSE_BUTTON_LEFT) return false

        val rowH = 24f
        val currentList = value.get()
        val listY = lastY + 28f
        val listH = min(120f, max(24f, currentList.size * rowH))
        if (mouseY in listY..(listY + listH) && currentList.isNotEmpty()) {
            val idx = ((mouseY - listY + listScroll) / rowH).toInt()
            if (idx in currentList.indices) {
                if (mouseX >= lastX + lastWidth - 20f) {
                    currentList.removeAt(idx)
                    value.set(currentList)
                } else {
                    dragIndex = idx
                }
                return true
            }
        }

        val pickerTop = listY + listH + 6f
        if (!pickerOpen) {
            if (mouseY in pickerTop..(pickerTop + 22f)) {
                pickerOpen = true
                return true
            }
        } else {
            if (mouseY in pickerTop..(pickerTop + 22f)) {
                if (mouseX >= lastX + lastWidth - 20f) {
                    pickerOpen = false
                } else {
                    SettingOverlayManager.focusTextInput(searchEditor, selectAll = false)
                    searchEditor.onMouseDown(mouseX - (lastX + 8f), 11.5f, UiFontWeight.REGULAR, shiftHeld)
                }
                return true
            }
            val cat = filteredCatalog()
            val pListY = pickerTop + 26f
            val pListH = min(132f, max(24f, cat.size * rowH))
            if (pickerAnim > 0.5f && mouseY in pListY..(pListY + pListH)) {
                val idx = ((mouseY - pListY + pickerScroll) / rowH).toInt()
                if (idx in cat.indices) {
                    currentList.add(cat[idx].value)
                    value.set(currentList)
                    return true
                }
            }
        }
        return false
    }

    override fun mouseDragged(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (dragIndex < 0 || button != InputConstants.MOUSE_BUTTON_LEFT) return false
        val list = value.get()
        if (dragIndex !in list.indices) return false
        val listY = lastY + 28f
        val target = ((mouseY - listY + listScroll) / 24f).toInt().coerceIn(0, list.lastIndex)
        if (target != dragIndex) {
            val moved = list.removeAt(dragIndex)
            list.add(target, moved)
            dragIndex = target
            value.set(list)
        }
        return true
    }

    override fun mouseReleased(mouseX: Float, mouseY: Float, button: Int) {
        dragIndex = -1
    }

    override fun mouseScrolled(mouseX: Float, mouseY: Float, scrollY: Double): Boolean {
        if (!expanded || expandAnim <= 0.5f) return false
        val rowH = 24f
        val currentList = value.get()
        val listY = lastY + 28f
        val listH = min(120f, max(24f, currentList.size * rowH))
        if (mouseX in lastX..(lastX + lastWidth) && mouseY in listY..(listY + listH)) {
            val maxScroll = max(0f, currentList.size * rowH - listH)
            if (maxScroll > 0f) {
                val prev = targetListScroll
                targetListScroll = (targetListScroll - scrollY.toFloat() * 32f).coerceIn(0f, maxScroll)
                if (kotlin.math.abs(targetListScroll - prev) > 0.01f) {
                    return true
                }
            }
        }
        if (pickerOpen && pickerAnim > 0.5f) {
            val cat = filteredCatalog()
            val pListY = listY + listH + 32f
            val pListH = min(132f, max(24f, cat.size * rowH))
            if (mouseX in lastX..(lastX + lastWidth) && mouseY in pListY..(pListY + pListH)) {
                val maxScroll = max(0f, cat.size * rowH - pListH)
                if (maxScroll > 0f) {
                    val prev = targetPickerScroll
                    targetPickerScroll = (targetPickerScroll - scrollY.toFloat() * 32f).coerceIn(0f, maxScroll)
                    if (kotlin.math.abs(targetPickerScroll - prev) > 0.01f) {
                        return true
                    }
                }
            }
        }
        return false
    }
}
