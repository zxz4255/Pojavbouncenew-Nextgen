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
package net.ccbluex.liquidbounce.ui.clickgui.setting

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.config.types.BindValue
import net.ccbluex.liquidbounce.config.types.CurveValue
import net.ccbluex.liquidbounce.config.types.FileValue
import net.ccbluex.liquidbounce.config.types.RangedValue
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.config.types.ValueType
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.ChoiceListValue
import net.ccbluex.liquidbounce.config.types.list.ItemListValue
import net.ccbluex.liquidbounce.config.types.list.MultiChoiceListValue
import net.ccbluex.liquidbounce.config.types.list.MutableListValue
import net.ccbluex.liquidbounce.config.types.list.RegistryListValue
import net.ccbluex.liquidbounce.config.types.list.RegistryMutableListValue
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.ui.common.UiAnimation
import net.ccbluex.liquidbounce.ui.common.UiDraw
import net.ccbluex.liquidbounce.ui.common.UiFontWeight
import net.ccbluex.liquidbounce.ui.common.UiPalette
import net.ccbluex.liquidbounce.ui.common.UiTextUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.util.WeakHashMap

/**
 * Central recursive dispatcher and WeakHashMap cache for all 23 `ValueType` setting widgets,
 * including `GenericSetting.svelte` (`in:slide / out:slide`) dynamic child visibility transitions.
 */
object SettingRenderer {

    private val nodeCache = WeakHashMap<Value<*>, SettingNode>()

    /** Existing cached widgets may still be half-way through an expansion from the previous screen. */
    fun prepareForOpen() {
        for ((value, node) in nodeCache) {
            node.visibilityInitialized = true
            node.visibilityAnim = if (value.visibleCondition.asBoolean) 1f else 0f
            node.snapLayoutState()
        }
    }

    fun allOptionChildrenOf(group: ValueGroup, exclude: Value<*>? = null): List<Value<*>> =
        group.inner.filter { it !== exclude && !it.notAnOption }

    fun visibleChildrenOf(group: ValueGroup): List<Value<*>> =
        group.inner.filter { !it.notAnOption && it.visibleCondition.asBoolean }

    fun hasAnyActiveChildren(group: ValueGroup, exclude: Value<*>? = null): Boolean =
        group.inner.any { v ->
            v !== exclude && !v.notAnOption && (
                v.visibleCondition.asBoolean ||
                    (nodeCache[v]?.let { it.visibilityInitialized && it.visibilityAnim > 0.002f } == true)
                )
        }

    @Suppress("UNCHECKED_CAST")
    fun nodeFor(value: Value<*>): SettingNode {
        return nodeCache.getOrPut(value) { createNode(value) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun createNode(value: Value<*>): SettingNode {
        return when (value.valueType) {
            ValueType.BOOLEAN -> BooleanSettingNode(value as Value<Boolean>)
            ValueType.INT -> SingleSliderSettingNode(value as RangedValue<*>, isInteger = true)
            ValueType.FLOAT -> SingleSliderSettingNode(value as RangedValue<*>, isInteger = false)
            ValueType.INT_RANGE -> RangeSliderSettingNode(value as RangedValue<*>, isInteger = true)
            ValueType.FLOAT_RANGE -> RangeSliderSettingNode(value as RangedValue<*>, isInteger = false)
            ValueType.TEXT -> if (TextSettingCodec.supports(value)) {
                TextSettingNode(value)
            } else {
                ReadOnlyFallbackSettingNode(value)
            }
            ValueType.COLOR -> ColorSettingNode(value as Value<Color4b>)
            ValueType.CHOOSE -> ChooseSettingNode(value as ChoiceListValue<*>)
            ValueType.MULTI_CHOOSE -> MultiChooseSettingNode(value as MultiChoiceListValue<Tagged>)
            ValueType.CHOICE -> ChoiceModeGroupNode(value as ModeValueGroup<*>)
            ValueType.TOGGLEABLE -> ToggleableGroupNode(value as ToggleableValueGroup)
            ValueType.CONFIGURABLE -> ConfigurableGroupNode(value as ValueGroup)
            ValueType.BIND -> BindSettingNode(value as BindValue)
            ValueType.KEY -> KeySettingNode(value as Value<InputConstants.Key>)
            ValueType.FILE -> FileSettingNode(value as FileValue)
            ValueType.VECTOR3_I -> VectorSettingNode(value, dimensions = 3, integerMode = true)
            ValueType.VECTOR3_D -> VectorSettingNode(value, dimensions = 3, integerMode = false)
            ValueType.VECTOR2_F -> VectorSettingNode(value, dimensions = 2, integerMode = false)
            ValueType.CURVE -> CurveSettingNode(value as CurveValue)
            ValueType.MUTABLE_LIST -> MutableListSettingNode(value as MutableListValue<MutableCollection<Any?>, Any?>)
            ValueType.NAMED_ITEM_LIST -> NamedItemListSettingNode(value as ItemListValue<MutableSet<Any?>, Any?>)
            ValueType.REGISTRY_LIST -> RegistryListSettingNode(value as RegistryListValue<java.util.SequencedSet<Any?>, Any?>)
            ValueType.REGISTRY_MUTABLE_LIST -> RegistryMutableListSettingNode(value as RegistryMutableListValue<MutableList<Any?>, Any?>)
            else -> ReadOnlyFallbackSettingNode(value)
        }
    }

    /**
     * Steps `visibilityAnim` (`GenericSetting.svelte` slide transition) + recursive `node.stepAnimations(dtSeconds)`
     * once per frame before layout measurement.
     */
    fun stepChildrenAnimations(allChildren: List<Value<*>>, dtSeconds: Float) {
        for (v in allChildren) {
            if (v.notAnOption) continue
            val node = nodeFor(v)
            val vis = v.visibleCondition.asBoolean
            if (!node.visibilityInitialized) {
                node.visibilityAnim = if (vis) 1f else 0f
                node.visibilityInitialized = true
            } else {
                node.visibilityAnim = UiAnimation.expLerp(node.visibilityAnim, if (vis) 1f else 0f, 18f, dtSeconds)
            }
            if (vis || node.visibilityAnim > 0.002f) {
                node.stepAnimations(dtSeconds)
            }
        }
    }

    fun stepGroupAnimations(group: ValueGroup, dtSeconds: Float, exclude: Value<*>? = null) {
        stepChildrenAnimations(allOptionChildrenOf(group, exclude), dtSeconds)
    }

    private fun effectiveVisibilityAnim(v: Value<*>, node: SettingNode): Float {
        return if (node.visibilityInitialized) {
            node.visibilityAnim
        } else {
            if (v.visibleCondition.asBoolean) 1f else 0f
        }
    }

    fun measureGroupHeight(
        group: ValueGroup,
        width: Float,
        gapY: Float = 4f,
        exclude: Value<*>? = null,
    ): Float = measureListHeight(allOptionChildrenOf(group, exclude), width, gapY)

    fun measureListHeight(values: List<Value<*>>, width: Float, gapY: Float = 4f): Float {
        if (values.isEmpty()) return 0f
        var sum = 0f
        var activeCount = 0
        for (v in values) {
            if (v.notAnOption) continue
            val node = nodeFor(v)
            val visAnim = effectiveVisibilityAnim(v, node)
            if (!v.visibleCondition.asBoolean && visAnim <= 0.002f) continue
            if (activeCount > 0) {
                sum += gapY * visAnim
            }
            sum += node.measureHeight(width) * visAnim
            activeCount++
        }
        return sum
    }

    fun renderGroup(
        ctx: GuiGraphicsExtractor,
        group: ValueGroup,
        x: Float,
        y: Float,
        width: Float,
        mouseX: Float,
        mouseY: Float,
        dtSeconds: Float,
        canvasWidth: Float,
        gapY: Float = 4f,
        exclude: Value<*>? = null,
    ): Float = renderList(ctx, allOptionChildrenOf(group, exclude), x, y, width, mouseX, mouseY, dtSeconds, canvasWidth, gapY)

    fun renderList(
        ctx: GuiGraphicsExtractor,
        values: List<Value<*>>,
        x: Float,
        y: Float,
        width: Float,
        mouseX: Float,
        mouseY: Float,
        dtSeconds: Float,
        canvasWidth: Float,
        gapY: Float = 4f,
    ): Float {
        if (values.isEmpty()) return 0f
        var cursorY = y
        var renderedAny = false
        for (v in values) {
            if (v.notAnOption) continue
            val node = nodeFor(v)
            val visAnim = effectiveVisibilityAnim(v, node)
            if (!v.visibleCondition.asBoolean && visAnim <= 0.002f) continue

            if (renderedAny) {
                cursorY += gapY * visAnim
            }
            renderedAny = true

            val fullH = node.measureHeight(width)
            val slotH = fullH * visAnim
            if (visAnim >= 0.998f) {
                node.render(ctx, x, cursorY, width, mouseX, mouseY, dtSeconds, canvasWidth)
            } else if (slotH > 0.3f) {
                UiDraw.withCssScissor(ctx, x - 8f, cursorY, width + 16f, slotH) {
                    node.render(ctx, x, cursorY, width, -10000f, -10000f, dtSeconds, canvasWidth)
                }
            }
            cursorY += slotH
        }
        return cursorY - y
    }

    private fun isInteractive(v: Value<*>): Boolean {
        if (v.notAnOption || !v.visibleCondition.asBoolean) return false
        val node = nodeFor(v)
        return !node.visibilityInitialized || node.visibilityAnim > 0.5f
    }

    fun mouseClickedList(
        values: List<Value<*>>,
        mouseX: Float,
        mouseY: Float,
        button: Int,
        shiftHeld: Boolean = false,
    ): Boolean {
        for (v in values) {
            if (!isInteractive(v)) continue
            if (nodeFor(v).mouseClicked(mouseX, mouseY, button, shiftHeld)) {
                return true
            }
        }
        return false
    }

    fun mouseDraggedList(values: List<Value<*>>, mouseX: Float, mouseY: Float, button: Int): Boolean {
        for (v in values) {
            if (!isInteractive(v)) continue
            if (nodeFor(v).mouseDragged(mouseX, mouseY, button)) {
                return true
            }
        }
        return false
    }

    fun mouseReleasedList(values: List<Value<*>>, mouseX: Float, mouseY: Float, button: Int) {
        for (v in values) {
            if (v.notAnOption) continue
            nodeCache[v]?.mouseReleased(mouseX, mouseY, button)
        }
    }

    fun mouseScrolledList(values: List<Value<*>>, mouseX: Float, mouseY: Float, scrollY: Double): Boolean {
        for (v in values) {
            if (!isInteractive(v)) continue
            if (nodeFor(v).mouseScrolled(mouseX, mouseY, scrollY)) {
                return true
            }
        }
        return false
    }

    private class ReadOnlyFallbackSettingNode(override val value: Value<*>) : SettingNode(value) {
        override fun measureHeight(width: Float): Float = 24f

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
            recordBounds(x, y, width, 24f)
            UiDraw.drawText(ctx, UiTextUtils.displayName(value.name), x, y + 4f, 12f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM)
            val valStr = UiDraw.ellipsize(value.get().toString(), width * 0.5f, 11.5f, UiFontWeight.REGULAR)
            val vw = UiDraw.measureText(valStr, 11.5f, UiFontWeight.REGULAR)
            UiDraw.drawText(ctx, valStr, x + width - vw, y + 4f, 11.5f, UiPalette.TEXT_DIMMED_COLOR)
            return 24f
        }
    }
}
