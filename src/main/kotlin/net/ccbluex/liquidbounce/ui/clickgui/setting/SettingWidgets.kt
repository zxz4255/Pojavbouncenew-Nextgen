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
@file:Suppress("TooManyFunctions", "LongMethod", "CognitiveComplexMethod")

package net.ccbluex.liquidbounce.ui.clickgui.setting

import com.mojang.blaze3d.Blaze3D
import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.config.types.BindValue
import net.ccbluex.liquidbounce.config.types.CurveValue
import net.ccbluex.liquidbounce.config.types.FileValue
import net.ccbluex.liquidbounce.config.types.RangedValue
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.config.types.Vec3Value
import net.ccbluex.liquidbounce.config.types.group.Mode
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.ChoiceListValue
import net.ccbluex.liquidbounce.config.types.list.MultiChoiceListValue
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleClickGui
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.ui.clickgui.ClickGuiLayoutStore
import net.ccbluex.liquidbounce.ui.clickgui.glass.ClickGuiGlassSurface
import net.ccbluex.liquidbounce.ui.common.UiAnimation
import net.ccbluex.liquidbounce.ui.common.UiDraw
import net.ccbluex.liquidbounce.ui.common.UiFontWeight
import net.ccbluex.liquidbounce.ui.common.UiIcons
import net.ccbluex.liquidbounce.ui.common.UiLocalization
import net.ccbluex.liquidbounce.ui.common.UiPalette
import net.ccbluex.liquidbounce.ui.common.UiTextEditState
import net.ccbluex.liquidbounce.ui.common.UiTextUtils
import net.ccbluex.liquidbounce.ui.common.UiTooltipManager
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.input.InputBind
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.KeyEvent
import net.minecraft.core.Vec3i
import net.minecraft.world.phys.Vec3
import org.joml.Vector2f
import org.joml.Vector2fc
import java.awt.Color
import java.io.File
import java.util.EnumSet
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Base stateful setting node matching `043` `src-theme/src/routes/clickgui/setting/` widgets.
 */
abstract class SettingNode(private val layoutValue: Value<*>) {
    open val value: Value<*> get() = layoutValue
    var lastX: Float = 0f
    var lastY: Float = 0f
    var lastWidth: Float = 0f
    var lastHeight: Float = 0f

    var visibilityInitialized: Boolean = false
    var visibilityAnim: Float = 1f

    // Never access an overridden open value property during base construction.
    protected fun rememberedBoolean(field: String, default: Boolean = false) =
        ClickGuiLayoutStore.booleanState({ layoutValue.key?.let { "setting:$it" } }, field, default)
    protected fun rememberedScroll(field: String = "scrollTop") =
        ClickGuiLayoutStore.scrollState({ layoutValue.key?.let { "setting:$it" } }, field)

    /** Settle cached layout transitions before measuring a newly opened screen. */
    open fun snapLayoutState() {}

    open fun stepAnimations(dtSeconds: Float) {}

    abstract fun measureHeight(width: Float): Float

    abstract fun render(
        ctx: GuiGraphicsExtractor,
        x: Float,
        y: Float,
        width: Float,
        mouseX: Float,
        mouseY: Float,
        dtSeconds: Float,
        canvasWidth: Float,
    ): Float

    open fun mouseClicked(mouseX: Float, mouseY: Float, button: Int, shiftHeld: Boolean = false): Boolean = false
    open fun mouseDragged(mouseX: Float, mouseY: Float, button: Int): Boolean = false
    open fun mouseReleased(mouseX: Float, mouseY: Float, button: Int) {}
    open fun mouseScrolled(mouseX: Float, mouseY: Float, scrollY: Double): Boolean = false

    protected fun maybeShowTooltip(
        mouseX: Float,
        mouseY: Float,
        headerX: Float,
        headerY: Float,
        headerW: Float,
        headerH: Float,
        canvasWidth: Float,
    ) {
        if (mouseX in headerX..(headerX + headerW) && mouseY in headerY..(headerY + headerH)) {
            val desc = runCatching { value.description.get() }.getOrNull()
            if (!desc.isNullOrBlank() && !desc.startsWith("liquidbounce.")) {
                UiTooltipManager.request(
                    text = desc,
                    anchorRightX = headerX + headerW,
                    centerY = headerY + headerH * 0.5f,
                    fallbackLeftX = headerX,
                    canvasWidth = canvasWidth,
                )
            }
        }
    }

    protected fun recordBounds(x: Float, y: Float, w: Float, h: Float) {
        lastX = x
        lastY = y
        lastWidth = w
        lastHeight = h
    }
}

/**
 * Exact `Switch.svelte` reproduction:
 * - `.switch`: `width: 22px; height: 12px`
 * - `.slider` track: `top: 2px; height: 8px; border-radius: 4px`
 *   (`--clickgui-switch-track-color` -> `--clickgui-switch-track-active-color` = 40% accent + 60% black)
 * - `.slider::before` thumb: `width: 12px; height: 12px; top: 0; left: 0 -> translateX(10px); border-radius: 50%`
 *   (`#ffffff` -> `--accent-color`, pure solid circle, no border)
 * - `.name`: `margin-left: 7px; font-size: 12px; font-weight: 500; color: #ffffff`
 */
internal object SwitchWidget {
    const val SWITCH_W = 22f
    const val SWITCH_H = 12f
    const val TRACK_H = 8f
    const val THUMB_SIZE = 12f
    const val LABEL_GAP = 7f

    fun render(
        ctx: GuiGraphicsExtractor,
        x: Float,
        rowCenterY: Float,
        width: Float,
        label: String,
        progress: Float,
        extraRightReserve: Float = 0f,
    ) {
        val p = progress.coerceIn(0f, 1f)
        val switchY = rowCenterY - SWITCH_H * 0.5f
        val trackY = switchY + (SWITCH_H - TRACK_H) * 0.5f
        val lpGlass = ClickGuiGlassSurface.isActive && ModuleClickGui.isLightPollutionGlass
        val rightReserve = if (extraRightReserve > 0f) max(extraRightReserve, 21f) else 2f
        val switchX = if (lpGlass) max(x, x + width - rightReserve - SWITCH_W) else x

        run {
            val trackBg = UiPalette.lerpArgb(UiPalette.switchTrackUnchecked, UiPalette.switchTrackChecked, p)
            val thumbCol = UiPalette.lerpArgb(UiPalette.switchThumbUnchecked, UiPalette.switchThumbChecked, p)
            UiDraw.fillBox(ctx, switchX, trackY, SWITCH_W, TRACK_H, trackBg, radius = TRACK_H * 0.5f)
            val thumbX = switchX + p * (SWITCH_W - THUMB_SIZE)
            if (lpGlass) {
                val glowScale = ModuleClickGui.glassRimValue.coerceIn(0f, 1.6f)
                UiDraw.fillBox(
                    ctx, thumbX, switchY, THUMB_SIZE, THUMB_SIZE,
                    thumbCol, radius = THUMB_SIZE * 0.5f,
                    shadowColor = UiPalette.white(((0.22f + 0.50f * p) * glowScale).coerceIn(0f, 0.92f)),
                    shadowBlur = 2.2f + 2.6f * p,
                )
            } else {
                UiDraw.fillBox(ctx, thumbX, switchY, THUMB_SIZE, THUMB_SIZE, thumbCol, radius = THUMB_SIZE * 0.5f)
            }
        }

        val lh = UiDraw.lineHeight(12f, UiFontWeight.MEDIUM)
        val textX = if (lpGlass) x else x + SWITCH_W + LABEL_GAP
        val availW = max(10f, width - (SWITCH_W + LABEL_GAP) - (if (lpGlass) rightReserve else extraRightReserve))
        val clipped = UiDraw.ellipsize(label, availW, 12f, UiFontWeight.MEDIUM)
        UiDraw.drawText(
            ctx = ctx,
            text = clipped,
            x = textX,
            y = rowCenterY - lh * 0.5f,
            fontSizePx = 12f,
            argb = UiPalette.TEXT_COLOR,
            weight = UiFontWeight.MEDIUM,
            shadow = lpGlass,
        )
    }
}

/**
 * 1. BOOLEAN (`BooleanSetting.svelte` + `Switch.svelte`)
 */
class BooleanSettingNode(override val value: Value<Boolean>) : SettingNode(value) {
    private var knobProgress = if (value.get()) 1f else 0f

    override fun stepAnimations(dtSeconds: Float) {
        knobProgress = UiAnimation.expLerp(knobProgress, if (value.get()) 1f else 0f, 13f, dtSeconds)
    }

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
        val h = 24f
        recordBounds(x, y, width, h)
        SwitchWidget.render(ctx, x, y + h * 0.5f, width, UiTextUtils.displayName(value.name), knobProgress)
        maybeShowTooltip(mouseX, mouseY, x, y, width, h, canvasWidth)
        return h
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int, shiftHeld: Boolean): Boolean {
        if (button == InputConstants.MOUSE_BUTTON_LEFT &&
            mouseX in lastX..(lastX + lastWidth) &&
            mouseY in lastY..(lastY + lastHeight)
        ) {
            value.set(!value.get())
            return true
        }
        return false
    }
}

/**
 * 2 & 3. INT & FLOAT single-value slider (`FloatSetting.svelte` / `IntSetting.svelte` + `nouislider.scss`)
 */
class SingleSliderSettingNode(
    override val value: RangedValue<*>,
    private val isInteger: Boolean,
) : SettingNode(value) {

    private var dragging = false
    private var editingInline = false
    private var displayRatio: Float = Float.NaN

    private val inlineEditor = UiTextEditState(
        numericOnly = true,
        allowFloat = !isInteger,
        maxLength = 20,
        onCommit = { raw ->
            commitInlineValue(raw)
            editingInline = false
        },
        onCancel = {
            editingInline = false
        },
    )

    private fun minBound(): Double = (value.range.start as Number).toDouble()
    private fun maxBound(): Double = (value.range.endInclusive as Number).toDouble()
    private fun currentNumber(): Double = (value.get() as Number).toDouble()

    private fun targetRatio(): Float {
        val span = (maxBound() - minBound()).takeIf { it > 1e-9 } ?: 1.0
        return ((currentNumber() - minBound()) / span).toFloat().coerceIn(0f, 1f)
    }

    override fun stepAnimations(dtSeconds: Float) {
        val target = targetRatio()
        displayRatio = if (displayRatio.isNaN()) {
            target
        } else {
            UiAnimation.expLerp(displayRatio, target, if (dragging) 45f else 22f, dtSeconds)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyNumber(num: Double) {
        val clamped = num.coerceIn(minBound(), maxBound())
        val rawVal = value.get()
        when (rawVal) {
            is Int -> (value as Value<Int>).set(clamped.roundToInt())
            is Long -> (value as Value<Long>).set(clamped.roundToInt().toLong())
            is Float -> {
                val rounded = (clamped * 100.0).roundToInt() / 100.0f
                (value as Value<Float>).set(rounded.coerceIn(minBound().toFloat(), maxBound().toFloat()))
            }
            is Double -> (value as Value<Double>).set((clamped * 100.0).roundToInt() / 100.0)
        }
    }

    private fun commitInlineValue(raw: String) {
        val parsed = raw.trim().toDoubleOrNull() ?: return
        applyNumber(parsed)
    }

    private fun formatCurrent(): String {
        val n = currentNumber()
        return if (isInteger) {
            n.roundToInt().toString()
        } else {
            String.format(Locale.US, "%.2f", n).trimEnd('0').trimEnd('.')
        }
    }

    override fun measureHeight(width: Float): Float = 38f

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
        val h = 38f
        recordBounds(x, y, width, h)

        val title = UiTextUtils.displayName(value.name)
        val suffixStr = if (value.suffix.isNotEmpty()) " ${value.suffix}" else ""
        val valStr = formatCurrent()

        UiDraw.drawText(ctx, title, x, y + 3f, 12f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM)

        if (editingInline && inlineEditor.focused) {
            val boxW = max(48f, UiDraw.measureText(inlineEditor.text, 12f, UiFontWeight.MEDIUM) + 18f)
            val boxX = x + width - boxW
            UiDraw.fillBox(
                ctx, boxX, y + 1f, boxW, 18f,
                UiPalette.inputBg, radius = 3f,
                borderColor = UiPalette.accent(1f), borderWidth = 1f,
            )
            UiDraw.drawTextFieldContent(
                ctx, inlineEditor, boxX, y + 1f, boxW, 18f,
                fontSizePx = 12f, weight = UiFontWeight.MEDIUM, paddingX = 4f,
            )
        } else {
            editingInline = false
            val fullRight = "$valStr$suffixStr"
            val rw = UiDraw.measureText(fullRight, 12f, UiFontWeight.MEDIUM)
            val rx = x + width - rw
            val vw = UiDraw.drawText(ctx, valStr, rx, y + 3f, 12f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM)
            if (suffixStr.isNotEmpty()) {
                UiDraw.drawText(ctx, suffixStr, rx + vw, y + 3f, 12f, UiPalette.TEXT_DIMMED_COLOR, UiFontWeight.REGULAR)
            }
        }

        // Exact `nouislider.scss` geometry: `height: 2px` track + pure solid `12x12px` borderless handle
        val trackX = x + 6f
        val trackW = max(12f, width - 12f)
        val trackY = y + 27f
        val trackH = 2f
        val ratio = (if (displayRatio.isNaN()) targetRatio() else displayRatio).coerceIn(0f, 1f)

        run {
            if (ClickGuiGlassSurface.isActive && ModuleClickGui.isLightPollutionGlass) {
                val glowScale = ModuleClickGui.glassRimValue.coerceIn(0f, 1.6f)
                val lpTrackH = 3.0f
                val lpTrackY = trackY - 0.5f
                UiDraw.fillBox(ctx, trackX, lpTrackY, trackW, lpTrackH, UiPalette.white(0.18f), radius = 1.5f)
                if (ratio > 0.001f) {
                    UiDraw.fillBox(
                        ctx, trackX, lpTrackY, max(2.5f, trackW * ratio), lpTrackH,
                        UiPalette.white(0.60f), radius = 1.5f,
                        shadowColor = UiPalette.white((0.24f * glowScale).coerceIn(0f, 0.5f)),
                        shadowBlur = 2.6f,
                    )
                }
                val pillW = 6f
                val pillH = 13f
                val knobX = trackX + trackW * ratio - pillW * 0.5f
                val knobY = lpTrackY + lpTrackH * 0.5f - pillH * 0.5f
                UiDraw.fillBox(
                    ctx, knobX, knobY, pillW, pillH,
                    UiPalette.TEXT_COLOR, radius = pillW * 0.5f,
                    shadowColor = UiPalette.white((0.72f * glowScale).coerceIn(0f, 0.95f)),
                    shadowBlur = 4.2f,
                )
            } else {
                UiDraw.fillBox(ctx, trackX, trackY, trackW, trackH, UiPalette.sliderTrackBg, radius = 1f)
                if (ratio > 0.001f) {
                    UiDraw.fillBox(ctx, trackX, trackY, max(2f, trackW * ratio), trackH, UiPalette.accent(1f), radius = 1f)
                }
                val knobX = trackX + trackW * ratio - 6f
                val knobY = trackY + trackH * 0.5f - 6f
                UiDraw.fillBox(
                    ctx, knobX, knobY, 12f, 12f,
                    UiPalette.accent(1f), radius = 6f,
                )
            }
        }

        maybeShowTooltip(mouseX, mouseY, x, y, width, 19f, canvasWidth)
        return h
    }

    private fun updateFromMouse(mouseX: Float) {
        val trackX = lastX + 6f
        val trackW = max(12f, lastWidth - 12f)
        val t = ((mouseX - trackX) / trackW).coerceIn(0f, 1f).toDouble()
        applyNumber(minBound() + t * (maxBound() - minBound()))
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int, shiftHeld: Boolean): Boolean {
        if (button != InputConstants.MOUSE_BUTTON_LEFT) return false
        if (mouseX !in lastX..(lastX + lastWidth) || mouseY !in lastY..(lastY + lastHeight)) return false

        // Top row right half -> click-to-edit inline numeric value (`ValueInput.svelte`)
        if (mouseY <= lastY + 19f && mouseX >= lastX + lastWidth * 0.48f) {
            editingInline = true
            inlineEditor.forceText(formatCurrent())
            SettingOverlayManager.focusTextInput(inlineEditor, selectAll = true)
            return true
        }

        if (mouseY >= lastY + 18f) {
            SettingOverlayManager.clearTextInputFocus(commit = true)
            dragging = true
            updateFromMouse(mouseX)
            return true
        }
        return false
    }

    override fun mouseDragged(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (dragging && button == InputConstants.MOUSE_BUTTON_LEFT) {
            updateFromMouse(mouseX)
            return true
        }
        return false
    }

    override fun mouseReleased(mouseX: Float, mouseY: Float, button: Int) {
        dragging = false
    }
}

/**
 * 4 & 5. INT_RANGE & FLOAT_RANGE dual-handle slider (`FloatRangeSetting.svelte` / `IntRangeSetting.svelte` + `nouislider.scss`)
 */
class RangeSliderSettingNode(
    override val value: RangedValue<*>,
    private val isInteger: Boolean,
) : SettingNode(value) {

    private var activeThumb: Int = 0 // 0=none, 1=low, 2=high
    private var editingEnd: Int = 0 // 0=none, 1=low, 2=high
    private var displayLo: Float = Float.NaN
    private var displayHi: Float = Float.NaN

    private val inlineEditor = UiTextEditState(
        numericOnly = true,
        allowFloat = !isInteger,
        maxLength = 20,
        onCommit = { raw ->
            commitInline(raw)
            editingEnd = 0
        },
        onCancel = { editingEnd = 0 },
    )

    private fun minBound(): Double = (value.range.start as Number).toDouble()
    private fun maxBound(): Double = (value.range.endInclusive as Number).toDouble()

    private fun currentPair(): Pair<Double, Double> {
        val r = value.get() as ClosedRange<*>
        return (r.start as Number).toDouble() to (r.endInclusive as Number).toDouble()
    }

    private fun targetRatios(): Pair<Float, Float> {
        val (lo, hi) = currentPair()
        val span = (maxBound() - minBound()).takeIf { it > 1e-9 } ?: 1.0
        val rLo = ((lo - minBound()) / span).toFloat().coerceIn(0f, 1f)
        val rHi = ((hi - minBound()) / span).toFloat().coerceIn(0f, 1f)
        return rLo to rHi
    }

    override fun stepAnimations(dtSeconds: Float) {
        val (tLo, tHi) = targetRatios()
        val spd = if (activeThumb != 0) 45f else 22f
        displayLo = if (displayLo.isNaN()) tLo else UiAnimation.expLerp(displayLo, tLo, spd, dtSeconds)
        displayHi = if (displayHi.isNaN()) tHi else UiAnimation.expLerp(displayHi, tHi, spd, dtSeconds)
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyPair(loRaw: Double, hiRaw: Double) {
        val lo = min(loRaw, hiRaw).coerceIn(minBound(), maxBound())
        val hi = max(loRaw, hiRaw).coerceIn(minBound(), maxBound())
        val cur = value.get() as ClosedRange<*>
        when (cur.start) {
            is Int -> (value as Value<IntRange>).set(lo.roundToInt()..hi.roundToInt())
            is Float -> {
                val fLo = ((lo * 100.0).roundToInt() / 100.0f).coerceIn(minBound().toFloat(), maxBound().toFloat())
                val fHi = ((hi * 100.0).roundToInt() / 100.0f).coerceIn(fLo, maxBound().toFloat())
                (value as Value<ClosedFloatingPointRange<Float>>).set(fLo..fHi)
            }
            is Double -> {
                val dLo = ((lo * 100.0).roundToInt() / 100.0).coerceIn(minBound(), maxBound())
                val dHi = ((hi * 100.0).roundToInt() / 100.0).coerceIn(dLo, maxBound())
                (value as Value<ClosedFloatingPointRange<Double>>).set(dLo..dHi)
            }
        }
    }

    private fun commitInline(raw: String) {
        val parsed = raw.trim().toDoubleOrNull() ?: return
        val (lo, hi) = currentPair()
        if (editingEnd == 1) {
            applyPair(min(parsed, hi), hi)
        } else if (editingEnd == 2) {
            applyPair(lo, max(lo, parsed))
        }
    }

    private fun fmt(v: Double): String = if (isInteger) {
        v.roundToInt().toString()
    } else {
        String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
    }

    override fun measureHeight(width: Float): Float = 38f

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
        val h = 38f
        recordBounds(x, y, width, h)

        val (lo, hi) = currentPair()
        val title = UiTextUtils.displayName(value.name)
        val suffixStr = if (value.suffix.isNotEmpty()) " ${value.suffix}" else ""
        UiDraw.drawText(ctx, title, x, y + 3f, 12f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM)

        if (editingEnd != 0 && inlineEditor.focused) {
            val boxW = max(52f, UiDraw.measureText(inlineEditor.text, 12f, UiFontWeight.MEDIUM) + 18f)
            val boxX = x + width - boxW
            UiDraw.fillBox(
                ctx, boxX, y + 1f, boxW, 18f,
                UiPalette.inputBg, radius = 3f,
                borderColor = UiPalette.accent(1f), borderWidth = 1f,
            )
            UiDraw.drawTextFieldContent(ctx, inlineEditor, boxX, y + 1f, boxW, 18f, 12f, weight = UiFontWeight.MEDIUM, paddingX = 4f)
        } else {
            editingEnd = 0
            val rangeLabel = "${fmt(lo)} - ${fmt(hi)}$suffixStr"
            val rw = UiDraw.measureText(rangeLabel, 12f, UiFontWeight.MEDIUM)
            val rx = x + width - rw
            val mainPart = "${fmt(lo)} - ${fmt(hi)}"
            val mw = UiDraw.drawText(ctx, mainPart, rx, y + 3f, 12f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM)
            if (suffixStr.isNotEmpty()) {
                UiDraw.drawText(ctx, suffixStr, rx + mw, y + 3f, 12f, UiPalette.TEXT_DIMMED_COLOR, UiFontWeight.REGULAR)
            }
        }

        // Exact `nouislider.scss` geometry: `height: 2px` track + two pure solid `12x12px` borderless handles
        val trackX = x + 6f
        val trackW = max(12f, width - 12f)
        val trackY = y + 27f
        val trackH = 2f
        val (tLo, tHi) = targetRatios()
        val rLo = (if (displayLo.isNaN()) tLo else displayLo).coerceIn(0f, 1f)
        val rHi = (if (displayHi.isNaN()) tHi else displayHi).coerceIn(0f, 1f)

        if (ClickGuiGlassSurface.isActive && ModuleClickGui.isLightPollutionGlass) {
            val glowScale = ModuleClickGui.glassRimValue.coerceIn(0f, 1.6f)
            val lpTrackH = 3.0f
            val lpTrackY = trackY - 0.5f
            UiDraw.fillBox(ctx, trackX, lpTrackY, trackW, lpTrackH, UiPalette.white(0.18f), radius = 1.5f)
            val connX = trackX + trackW * min(rLo, rHi)
            val connW = max(2.5f, trackW * abs(rHi - rLo))
            UiDraw.fillBox(
                ctx, connX, lpTrackY, connW, lpTrackH,
                UiPalette.white(0.60f), radius = 1.5f,
                shadowColor = UiPalette.white((0.24f * glowScale).coerceIn(0f, 0.5f)),
                shadowBlur = 2.6f,
            )
            val pillW = 6f
            val pillH = 13f
            for (r in floatArrayOf(rLo, rHi)) {
                UiDraw.fillBox(
                    ctx,
                    trackX + trackW * r - pillW * 0.5f,
                    lpTrackY + lpTrackH * 0.5f - pillH * 0.5f,
                    pillW, pillH,
                    UiPalette.TEXT_COLOR,
                    radius = pillW * 0.5f,
                    shadowColor = UiPalette.white((0.72f * glowScale).coerceIn(0f, 0.95f)),
                    shadowBlur = 4.2f,
                )
            }
        } else {
            UiDraw.fillBox(ctx, trackX, trackY, trackW, trackH, UiPalette.sliderTrackBg, radius = 1f)
            val connX = trackX + trackW * min(rLo, rHi)
            val connW = max(2f, trackW * abs(rHi - rLo))
            UiDraw.fillBox(ctx, connX, trackY, connW, trackH, UiPalette.accent(1f), radius = 1f)

            for (r in floatArrayOf(rLo, rHi)) {
                UiDraw.fillBox(
                    ctx,
                    trackX + trackW * r - 6f,
                    trackY + trackH * 0.5f - 6f,
                    12f, 12f,
                    UiPalette.accent(1f),
                    radius = 6f,
                )
            }
        }

        maybeShowTooltip(mouseX, mouseY, x, y, width, 19f, canvasWidth)
        return h
    }

    private fun updateThumb(mouseX: Float) {
        val trackX = lastX + 6f
        val trackW = max(12f, lastWidth - 12f)
        val t = ((mouseX - trackX) / trackW).coerceIn(0f, 1f).toDouble()
        val v = minBound() + t * (maxBound() - minBound())
        val (lo, hi) = currentPair()
        if (activeThumb == 1) {
            applyPair(min(v, hi), hi)
        } else if (activeThumb == 2) {
            applyPair(lo, max(lo, v))
        }
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int, shiftHeld: Boolean): Boolean {
        if (button != InputConstants.MOUSE_BUTTON_LEFT) return false
        if (mouseX !in lastX..(lastX + lastWidth) || mouseY !in lastY..(lastY + lastHeight)) return false

        val (lo, hi) = currentPair()
        if (mouseY <= lastY + 19f && mouseX >= lastX + lastWidth * 0.45f) {
            val midSplit = lastX + lastWidth * 0.74f
            editingEnd = if (mouseX < midSplit) 1 else 2
            inlineEditor.forceText(if (editingEnd == 1) fmt(lo) else fmt(hi))
            SettingOverlayManager.focusTextInput(inlineEditor, selectAll = true)
            return true
        }

        if (mouseY >= lastY + 18f) {
            SettingOverlayManager.clearTextInputFocus(commit = true)
            val trackX = lastX + 6f
            val trackW = max(12f, lastWidth - 12f)
            val span = (maxBound() - minBound()).takeIf { it > 1e-9 } ?: 1.0
            val xLo = trackX + trackW * ((lo - minBound()) / span).toFloat()
            val xHi = trackX + trackW * ((hi - minBound()) / span).toFloat()
            activeThumb = if (abs(mouseX - xLo) <= abs(mouseX - xHi)) 1 else 2
            updateThumb(mouseX)
            return true
        }
        return false
    }

    override fun mouseDragged(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (activeThumb != 0 && button == InputConstants.MOUSE_BUTTON_LEFT) {
            updateThumb(mouseX)
            return true
        }
        return false
    }

    override fun mouseReleased(mouseX: Float, mouseY: Float, button: Int) {
        activeThumb = 0
    }
}

/**
 * 6. TEXT (`TextSetting.svelte`)
 */
class TextSettingNode(override val value: Value<*>) : SettingNode(value) {
    private var invalidPattern = false
    private val editor = UiTextEditState(
        initialText = TextSettingCodec.text(value),
        maxLength = 2048,
        onChanged = { next -> applyText(next) },
        onCommit = { next -> applyText(next) },
    )

    private fun applyText(next: String) {
        invalidPattern = !TextSettingCodec.apply(value, next)
    }

    override fun measureHeight(width: Float): Float = if (invalidPattern && editor.focused) 62f else 48f

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
        // Keep an invalid draft editable while focused; blur restores the last valid value.
        if (!editor.focused) invalidPattern = false
        editor.setTextExternal(TextSettingCodec.text(value))
        val h = measureHeight(width)
        recordBounds(x, y, width, h)

        UiDraw.drawText(ctx, UiTextUtils.displayName(value.name), x, y + 3f, 12f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM)

        val boxY = y + 20f
        val boxH = 24f
        val borderCol = when {
            invalidPattern -> UiPalette.ERROR_COLOR
            editor.focused -> UiPalette.accent(1f)
            else -> UiPalette.inputBorder
        }
        UiDraw.fillBox(ctx, x, boxY, width, boxH, UiPalette.inputBg, radius = 3f, borderColor = borderCol, borderWidth = 1f)
        UiDraw.drawTextFieldContent(
            ctx = ctx,
            state = editor,
            x = x, y = boxY, w = width, h = boxH,
            fontSizePx = 12f,
            placeholder = "${UiTextUtils.displayName(value.name)}...",
        )

        if (invalidPattern && editor.focused) {
            UiDraw.drawText(ctx, UiLocalization.text("Invalid regex; last valid value kept", "正则无效，已保留上个有效值"),
                x, y + 47f, 10f, UiPalette.ERROR_COLOR, UiFontWeight.REGULAR)
        }
        maybeShowTooltip(mouseX, mouseY, x, y, width, 18f, canvasWidth)
        return h
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int, shiftHeld: Boolean): Boolean {
        val boxY = lastY + 20f
        if (button == InputConstants.MOUSE_BUTTON_LEFT &&
            mouseX in lastX..(lastX + lastWidth) &&
            mouseY in boxY..(boxY + 24f)
        ) {
            SettingOverlayManager.focusTextInput(editor, selectAll = false)
            editor.onMouseDown(mouseX - (lastX + 8f), 12f, UiFontWeight.REGULAR, shiftHeld)
            return true
        }
        return false
    }

    override fun mouseDragged(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (editor.focused && button == InputConstants.MOUSE_BUTTON_LEFT) {
            editor.onMouseDrag(mouseX - (lastX + 8f), 12f, UiFontWeight.REGULAR)
            return true
        }
        return false
    }

    override fun mouseReleased(mouseX: Float, mouseY: Float, button: Int) {
        editor.onMouseUp()
    }
}

/**
 * 7. COLOR (`ColorSetting.svelte`: hex `#RRGGBBAA` input + swatch + smoothly animated SV square + Hue + Alpha bars)
 */
class ColorSettingNode(override val value: Value<Color4b>) : SettingNode(value) {
    private var expanded by rememberedBoolean("expanded")
    private var expandAnim = if (expanded) 1f else 0f
    private var dragMode = 0 // 0=none, 1=SV, 2=Hue, 3=Alpha
    private var cachedHue = 0f
    private var cachedSat = 1f
    private var cachedBri = 1f
    private var lastSyncedArgb: Int = Int.MIN_VALUE

    private val hexEditor = UiTextEditState(
        initialText = formatHex(value.get()),
        maxLength = 9,
        onChanged = { raw -> tryApplyHex(raw) },
        onCommit = { raw -> tryApplyHex(raw) },
    )

    override fun snapLayoutState() {
        expandAnim = if (expanded) 1f else 0f
        dragMode = 0
    }

    override fun stepAnimations(dtSeconds: Float) {
        expandAnim = UiAnimation.expLerp(expandAnim, if (expanded) 1f else 0f, 16f, dtSeconds)
    }

    private fun syncHsvFromValue() {
        val c = value.get()
        if (c.argb == lastSyncedArgb) return
        lastSyncedArgb = c.argb
        val hsb = Color.RGBtoHSB(c.r, c.g, c.b, null)
        if (hsb[1] > 0.001f && hsb[2] > 0.001f) {
            cachedHue = hsb[0]
        }
        cachedSat = hsb[1]
        cachedBri = hsb[2]
        hexEditor.setTextExternal(formatHex(c))
    }

    private fun pushHsvToValue(alpha: Int = value.get().a) {
        val rgb = Color.HSBtoRGB(cachedHue.coerceIn(0f, 1f), cachedSat.coerceIn(0f, 1f), cachedBri.coerceIn(0f, 1f))
        val r = (rgb ushr 16) and 0xFF
        val g = (rgb ushr 8) and 0xFF
        val b = rgb and 0xFF
        val newColor = Color4b(r, g, b, alpha.coerceIn(0, 255))
        lastSyncedArgb = newColor.argb
        value.set(newColor)
        hexEditor.setTextExternal(formatHex(newColor))
    }

    private fun tryApplyHex(raw: String) {
        val clean = raw.trim().removePrefix("#")
        if (clean.length != 6 && clean.length != 8) return
        val parsed = clean.toLongOrNull(16) ?: return
        val c = if (clean.length == 6) {
            Color4b(
                ((parsed ushr 16) and 0xFF).toInt(),
                ((parsed ushr 8) and 0xFF).toInt(),
                (parsed and 0xFF).toInt(),
                value.get().a,
            )
        } else {
            Color4b(
                ((parsed ushr 24) and 0xFF).toInt(),
                ((parsed ushr 16) and 0xFF).toInt(),
                ((parsed ushr 8) and 0xFF).toInt(),
                (parsed and 0xFF).toInt(),
            )
        }
        lastSyncedArgb = c.argb
        val hsb = Color.RGBtoHSB(c.r, c.g, c.b, null)
        cachedHue = hsb[0]
        cachedSat = hsb[1]
        cachedBri = hsb[2]
        value.set(c)
    }

    companion object {
        private const val PICKER_EXTRA_H = 140f
        private fun formatHex(c: Color4b): String =
            String.format(Locale.US, "#%02X%02X%02X%02X", c.r, c.g, c.b, c.a)
    }

    override fun measureHeight(width: Float): Float {
        if (!expanded && expandAnim <= 0.002f) return 28f
        return 28f + PICKER_EXTRA_H * expandAnim
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
        syncHsvFromValue()
        val h = measureHeight(width)
        recordBounds(x, y, width, h)

        val col = value.get()
        UiDraw.drawText(ctx, UiTextUtils.displayName(value.name), x, y + 6f, 12f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM)

        val swatchW = 20f
        val swatchH = 18f
        val swatchX = x + width - swatchW
        val swatchY = y + 4f

        val hexW = 78f
        val hexX = swatchX - 5f - hexW
        UiDraw.fillBox(
            ctx, hexX, swatchY, hexW, swatchH,
            UiPalette.inputBg, radius = 3f,
            borderColor = if (hexEditor.focused) UiPalette.accent(1f) else UiPalette.inputBorder,
            borderWidth = 1f,
        )
        UiDraw.drawTextFieldContent(
            ctx, hexEditor, hexX, swatchY, hexW, swatchH,
            fontSizePx = 11f, weight = UiFontWeight.MONOSPACE, paddingX = 4f,
        )

        UiDraw.drawAlphaCheckerBar(ctx, swatchX, swatchY, swatchW, swatchH, col.argb)
        UiDraw.fillBox(
            ctx, swatchX, swatchY, swatchW, swatchH,
            col.argb, radius = 3f,
            borderColor = UiPalette.white(0.35f), borderWidth = 1f,
        )

        val bodyAnimH = PICKER_EXTRA_H * expandAnim
        if (bodyAnimH > 0.5f) {
            UiDraw.withCssScissor(ctx, x - 6f, y + 26f, width + 12f, bodyAnimH + 2f) {
                val svY = y + 28f
                val svH = 98f
                UiDraw.drawSvPickerSquare(ctx, x, svY, width, svH, cachedHue, radius = 3f)
                val thumbX = x + cachedSat * width - 5f
                val thumbY = svY + (1f - cachedBri) * svH - 5f
                UiDraw.fillBox(
                    ctx, thumbX, thumbY, 10f, 10f,
                    col.with(a = 255).argb, radius = 5f,
                    borderColor = UiPalette.TEXT_COLOR, borderWidth = 1.5f,
                )

                val hueY = svY + svH + 8f
                val barH = 10f
                UiDraw.drawHueBar(ctx, x, hueY, width, barH)
                UiDraw.fillBox(
                    ctx, x + cachedHue * width - 5f, hueY - 1f, 10f, barH + 2f,
                    (Color.HSBtoRGB(cachedHue, 1f, 1f) or (0xFF shl 24)),
                    radius = 5f, borderColor = UiPalette.TEXT_COLOR, borderWidth = 1.5f,
                )

                val alphaY = hueY + barH + 8f
                UiDraw.drawAlphaCheckerBar(ctx, x, alphaY, width, barH, col.argb)
                val aRatio = col.a / 255f
                UiDraw.fillBox(
                    ctx, x + aRatio * width - 5f, alphaY - 1f, 10f, barH + 2f,
                    col.argb, radius = 5f, borderColor = UiPalette.TEXT_COLOR, borderWidth = 1.5f,
                )
            }
        }

        maybeShowTooltip(mouseX, mouseY, x, y, hexX - x, 24f, canvasWidth)
        return h
    }

    private fun updateDrag(mouseX: Float, mouseY: Float) {
        val svY = lastY + 28f
        val svH = 98f
        when (dragMode) {
            1 -> {
                cachedSat = ((mouseX - lastX) / max(1f, lastWidth)).coerceIn(0f, 1f)
                cachedBri = (1f - (mouseY - svY) / svH).coerceIn(0f, 1f)
                pushHsvToValue()
            }
            2 -> {
                cachedHue = ((mouseX - lastX) / max(1f, lastWidth)).coerceIn(0f, 1f)
                pushHsvToValue()
            }
            3 -> {
                val a = (((mouseX - lastX) / max(1f, lastWidth)).coerceIn(0f, 1f) * 255f).roundToInt()
                pushHsvToValue(alpha = a)
            }
        }
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int, shiftHeld: Boolean): Boolean {
        if (mouseX !in lastX..(lastX + lastWidth) || mouseY !in lastY..(lastY + lastHeight)) return false

        val swatchW = 20f
        val swatchX = lastX + lastWidth - swatchW
        val hexW = 78f
        val hexX = swatchX - 5f - hexW

        if (mouseY in (lastY + 2f)..(lastY + 24f)) {
            if (button == InputConstants.MOUSE_BUTTON_LEFT && mouseX in hexX..(hexX + hexW)) {
                SettingOverlayManager.focusTextInput(hexEditor, selectAll = true)
                return true
            }
            if ((button == InputConstants.MOUSE_BUTTON_LEFT && mouseX >= swatchX) ||
                button == InputConstants.MOUSE_BUTTON_RIGHT
            ) {
                expanded = !expanded
                return true
            }
        }

        if (expanded && expandAnim > 0.5f && button == InputConstants.MOUSE_BUTTON_LEFT) {
            val svY = lastY + 28f
            val svH = 98f
            val hueY = svY + svH + 8f
            val alphaY = hueY + 18f
            when {
                mouseY in svY..(svY + svH) -> {
                    dragMode = 1
                    updateDrag(mouseX, mouseY)
                    return true
                }
                mouseY in (hueY - 3f)..(hueY + 13f) -> {
                    dragMode = 2
                    updateDrag(mouseX, mouseY)
                    return true
                }
                mouseY in (alphaY - 3f)..(alphaY + 13f) -> {
                    dragMode = 3
                    updateDrag(mouseX, mouseY)
                    return true
                }
            }
        }
        return false
    }

    override fun mouseDragged(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (dragMode != 0 && button == InputConstants.MOUSE_BUTTON_LEFT) {
            updateDrag(mouseX, mouseY)
            return true
        }
        return false
    }

    override fun mouseReleased(mouseX: Float, mouseY: Float, button: Int) {
        dragMode = 0
    }
}

/**
 * 8. CHOOSE (`ChooseSetting.svelte` + `Dropdown.svelte` portal)
 */
class ChooseSettingNode(override val value: ChoiceListValue<*>) : SettingNode(value) {
    private var chevronRot = -Math.PI.toFloat() * 0.5f

    override fun stepAnimations(dtSeconds: Float) {
        val isOpen = SettingOverlayManager.activeDropdown?.ownerKey === value
        chevronRot = UiAnimation.expLerp(chevronRot, if (isOpen) 0f else (-Math.PI.toFloat() * 0.5f), 16f, dtSeconds)
    }

    override fun measureHeight(width: Float): Float = 30f

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
        val h = 30f
        recordBounds(x, y, width, h)

        val currentTag = (value.get() as Tagged).tag
        val isOpen = SettingOverlayManager.activeDropdown?.ownerKey === value

        if (isOpen) {
            SettingOverlayManager.updateDropdownAnchor(value, x, y + 27f, width, currentTag)
        }

        val boxY = y + 2f
        val boxH = 25f
        val rBottom = if (isOpen) 0f else 3f
        UiDraw.fillCssBox(
            ctx, x, boxY, width, boxH,
            rTL = 3f, rTR = 3f, rBR = rBottom, rBL = rBottom,
            cTL = UiPalette.dropdownTriggerBg,
        )

        val label = "${UiTextUtils.displayName(value.name)} \u2022 ${UiTextUtils.displayName(currentTag)}"
        val clipped = UiDraw.ellipsize(label, width - 28f, 12f, UiFontWeight.MEDIUM)
        val lh = UiDraw.lineHeight(12f, UiFontWeight.MEDIUM)
        UiDraw.drawText(ctx, clipped, x + 10f, boxY + (boxH - lh) * 0.5f, 12f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM)

        UiDraw.drawIcon(
            ctx = ctx,
            id = UiIcons.SETTINGS_EXPAND,
            x = x + width - 19f,
            y = boxY + (boxH - 8f) * 0.5f,
            boxW = 10f,
            boxH = 8f,
            argb = if (isOpen) UiPalette.TEXT_COLOR else UiPalette.white(0.65f),
            rotationRad = chevronRot,
        )

        maybeShowTooltip(mouseX, mouseY, x, y, width, h, canvasWidth)
        return h
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int, shiftHeld: Boolean): Boolean {
        if (button == InputConstants.MOUSE_BUTTON_LEFT &&
            mouseX in lastX..(lastX + lastWidth) &&
            mouseY in (lastY + 2f)..(lastY + 27f)
        ) {
            val opts = value.choices.map { it.tag }
            val currentTag = (value.get() as Tagged).tag
            SettingOverlayManager.toggleDropdown(
                ownerKey = value,
                x = lastX,
                y = lastY + 27f,
                width = lastWidth,
                options = opts,
                currentValue = currentTag,
            ) { chosen ->
                runCatching { value.setByString(chosen) }
            }
            return true
        }
        return false
    }
}

/**
 * 9. MULTI_CHOOSE (`MultiChooseSetting.svelte`: chips grid + order-sensitive drag reorder + smooth expand/collapse)
 */
class MultiChooseSettingNode<T : Tagged>(override val value: MultiChoiceListValue<T>) : SettingNode(value) {
    private var expanded by rememberedBoolean("expanded")
    private var expandAnim = if (expanded) 1f else 0f
    private var draggingChoice: T? = null
    private val chipHitBoxes = ArrayList<Pair<T, FloatArray>>()

    override fun snapLayoutState() {
        expandAnim = if (expanded) 1f else 0f
        draggingChoice = null
    }

    override fun stepAnimations(dtSeconds: Float) {
        expandAnim = UiAnimation.expLerp(expandAnim, if (expanded) 1f else 0f, 16f, dtSeconds)
    }

    private fun fullExpandedBodyHeight(width: Float): Float {
        if (value.isOrderSensitive) {
            return value.choices.size * 24f + 6f
        }
        var curX = 0f
        var rows = 1
        val gapX = 5f
        for (choice in value.choices) {
            val cw = min(width, UiDraw.measureText(UiTextUtils.displayName(choice.tag), 11.5f, UiFontWeight.MEDIUM) + 14f)
            if (curX > 0f && curX + cw > width) {
                rows++
                curX = cw + gapX
            } else {
                curX += cw + gapX
            }
        }
        return rows * 25f + 4f
    }

    override fun measureHeight(width: Float): Float {
        if (!expanded && expandAnim <= 0.002f) return 28f
        return 28f + fullExpandedBodyHeight(width) * expandAnim
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
        chipHitBoxes.clear()

        val activeSet = value.get()
        val headerH = 26f
        UiDraw.drawText(ctx, UiTextUtils.displayName(value.name), x, y + 5f, 12f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM)

        val countText = "${activeSet.size}/${value.choices.size}"
        val cw = UiDraw.measureText(countText, 11f, UiFontWeight.MEDIUM)
        UiDraw.drawText(ctx, countText, x + width - 18f - cw, y + 6f, 11f, UiPalette.TEXT_DIMMED_COLOR, UiFontWeight.MEDIUM)

        val chevronRot = (-Math.PI.toFloat() * 0.5f) * (1f - expandAnim)
        val chevronCol = UiPalette.lerpArgb(UiPalette.white(0.5f), UiPalette.TEXT_COLOR, expandAnim)
        UiDraw.drawIcon(
            ctx, UiIcons.SETTINGS_EXPAND,
            x + width - 13f, y + 9f, 9f, 7f,
            argb = chevronCol,
            rotationRad = chevronRot,
        )

        val animBodyH = fullExpandedBodyHeight(width) * expandAnim
        if (animBodyH > 0.5f) {
            val childMx = if (expanded && expandAnim > 0.8f) mouseX else -10000f
            val childMy = if (expanded && expandAnim > 0.8f) mouseY else -10000f
            UiDraw.withCssScissor(ctx, x - 4f, y + 27f, width + 8f, animBodyH + 1f) {
                if (value.isOrderSensitive) {
                    val ordered = ArrayList<T>(value.choices.size)
                    ordered.addAll(activeSet)
                    for (c in value.choices) {
                        if (c !in activeSet) ordered.add(c)
                    }
                    ordered.forEachIndexed { idx, choice ->
                        val ry = y + 28f + idx * 24f
                        val selected = choice in activeSet
                        val bg = if (selected) UiPalette.accent(0.24f) else UiPalette.inputBg
                        val border = if (selected) UiPalette.accent(0.85f) else UiPalette.white(0.08f)
                        UiDraw.fillBox(ctx, x, ry, width, 21f, bg, radius = 3f, borderColor = border, borderWidth = 1f)
                        UiDraw.drawIcon(ctx, UiIcons.DRAG, x + 5f, ry + 4f, 8f, 13f, UiPalette.white(0.65f))
                        val prefix = if (selected) "${activeSet.indexOf(choice) + 1}. " else ""
                        UiDraw.drawText(
                            ctx,
                            "$prefix${UiTextUtils.displayName(choice.tag)}",
                            x + 18f, ry + 3.5f, 11.5f,
                            if (selected) UiPalette.TEXT_COLOR else UiPalette.TEXT_DIMMED_COLOR,
                            UiFontWeight.MEDIUM,
                        )
                        UiDraw.drawIcon(
                            ctx,
                            UiIcons.TICK,
                            x + width - 17f, ry + 5.5f, 10f, 10f,
                            if (selected) UiPalette.accent(1f) else UiPalette.white(0.25f),
                        )
                        chipHitBoxes.add(choice to floatArrayOf(x, ry, width, 21f))
                    }
                } else {
                    var cx = x
                    var cy = y + 28f
                    val gapX = 5f
                    val gapY = 5f
                    for (choice in value.choices) {
                        val label = UiTextUtils.displayName(choice.tag)
                        val boxW = min(width, UiDraw.measureText(label, 11.5f, UiFontWeight.MEDIUM) + 14f)
                        if (cx > x && cx + boxW > x + width) {
                            cx = x
                            cy += 20f + gapY
                        }
                        val selected = choice in activeSet
                        val hovered = childMx in cx..(cx + boxW) && childMy in cy..(cy + 20f)
                        val bg = when {
                            selected -> UiPalette.accent(0.14f)
                            hovered -> UiPalette.white(0.12f)
                            else -> UiPalette.surfaceLightened(24, 0.72f)
                        }
                        val textCol = when {
                            selected -> UiPalette.accent(1f)
                            hovered -> UiPalette.TEXT_COLOR
                            else -> UiPalette.TEXT_DIMMED_COLOR
                        }
                        UiDraw.fillBox(
                            ctx, cx, cy, boxW, 20f, bg, radius = 3f,
                            borderColor = if (selected) UiPalette.accent(0.75f) else 0,
                            borderWidth = if (selected) 1f else 0f,
                        )
                        val clipped = UiDraw.ellipsize(label, boxW - 8f, 11.5f, UiFontWeight.MEDIUM)
                        val tw = UiDraw.measureText(clipped, 11.5f, UiFontWeight.MEDIUM)
                        val lh = UiDraw.lineHeight(11.5f, UiFontWeight.MEDIUM)
                        UiDraw.drawText(
                            ctx, clipped,
                            cx + (boxW - tw) * 0.5f,
                            cy + (20f - lh) * 0.5f,
                            11.5f,
                            textCol,
                            UiFontWeight.MEDIUM,
                        )
                        chipHitBoxes.add(choice to floatArrayOf(cx, cy, boxW, 20f))
                        cx += boxW + gapX
                    }
                }
            }
        }

        maybeShowTooltip(mouseX, mouseY, x, y, width, headerH, canvasWidth)
        return h
    }

    private fun toggleChoice(choice: T) {
        val current = LinkedHashSet(value.get())
        if (choice in current) {
            if (!value.canBeNone && current.size <= 1) return
            current.remove(choice)
        } else {
            current.add(choice)
        }
        val target = value.get()
        target.clear()
        target.addAll(current)
        value.set(target)
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int, shiftHeld: Boolean): Boolean {
        if (mouseX !in lastX..(lastX + lastWidth) || mouseY !in lastY..(lastY + lastHeight)) return false
        if (mouseY <= lastY + 26f && (button == InputConstants.MOUSE_BUTTON_LEFT || button == InputConstants.MOUSE_BUTTON_RIGHT)) {
            expanded = !expanded
            return true
        }
        if (expanded && expandAnim > 0.5f && button == InputConstants.MOUSE_BUTTON_LEFT) {
            for ((choice, rect) in chipHitBoxes) {
                if (mouseX in rect[0]..(rect[0] + rect[2]) && mouseY in rect[1]..(rect[1] + rect[3])) {
                    if (value.isOrderSensitive && mouseX <= rect[0] + 18f && choice in value.get()) {
                        draggingChoice = choice
                    } else {
                        toggleChoice(choice)
                    }
                    return true
                }
            }
        }
        return false
    }

    override fun mouseDragged(mouseX: Float, mouseY: Float, button: Int): Boolean {
        val dragged = draggingChoice ?: return false
        if (!value.isOrderSensitive || button != InputConstants.MOUSE_BUTTON_LEFT) return false
        val activeList = value.get().toMutableList()
        val fromIdx = activeList.indexOf(dragged)
        if (fromIdx < 0) return false
        val toIdx = ((mouseY - (lastY + 28f)) / 24f).toInt().coerceIn(0, max(0, activeList.size - 1))
        if (toIdx != fromIdx) {
            activeList.removeAt(fromIdx)
            activeList.add(toIdx, dragged)
            val set = value.get()
            set.clear()
            set.addAll(activeList)
            value.set(set)
        }
        return true
    }

    override fun mouseReleased(mouseX: Float, mouseY: Float, button: Int) {
        draggingChoice = null
    }
}

/**
 * 10. BIND (`BindSetting.svelte` + `BindDisplay.svelte` + `SwitchBindAction.svelte`):
 * - Full-width transparent button box with `border: solid 2px var(--accent-color)`, `border-radius: 3px`, `padding: 4px`
 * - Top row: centered `.name` + top-right `SwitchBindAction` (`Toggle` / `Hold` / `Smart` + right arrow) when bound
 * - Bottom row: centered `BindDisplay` (`Modifiers +` bold `Key` or dimmed `None` / `Press any key...`)
 * - Full support for solo modifier binds (`RShift`, `LCtrl`, `LAlt`) via key-release OR 1000ms hold, plus `Ctrl+Key` combos & mouse buttons!
 */
class BindSettingNode(override val value: BindValue) : SettingNode(value), SettingOverlayManager.KeyCaptureTarget {
    private var listening = false
    private val pendingModifiers: MutableSet<InputBind.Modifier> = EnumSet.noneOf(InputBind.Modifier::class.java)
    private var pendingModifierKeyCode: Int = -1
    private var modifierDeadlineMs: Long = 0L

    private var actionSlideAnim: Float = 0f
    private var arrowJiggleAnim: Float = 0f
    private var lastActionRect = FloatArray(4)

    override fun stepAnimations(dtSeconds: Float) {
        actionSlideAnim = UiAnimation.expLerp(actionSlideAnim, 0f, 16f, dtSeconds)
        arrowJiggleAnim = UiAnimation.expLerp(arrowJiggleAnim, 0f, 14f, dtSeconds)
        if (listening && pendingModifierKeyCode != -1 && System.currentTimeMillis() >= modifierDeadlineMs) {
            commitPendingSoloModifier()
        }
    }

    private fun resetPendingModifiers() {
        pendingModifiers.clear()
        pendingModifierKeyCode = -1
        modifierDeadlineMs = 0L
    }

    private fun commitPendingSoloModifier() {
        val code = pendingModifierKeyCode
        if (code == -1) return
        val mod = InputBind.Modifier.of(code)
        val remainingMods = EnumSet.noneOf(InputBind.Modifier::class.java).apply {
            addAll(pendingModifiers)
            if (mod != null) {
                remove(mod)
            }
        }
        val key = InputConstants.Type.KEYBOARD.getOrCreate(code)
        value.set(InputBind(key, value.get().action, remainingMods))
        ConfigSystem.store(ModuleManager.modulesConfig)
        listening = false
        resetPendingModifiers()
        SettingOverlayManager.stopKeyCapture(this)
    }

    override fun cancelCapture() {
        listening = false
        resetPendingModifiers()
    }

    override fun onKeyboardKeyCaptured(event: KeyEvent): Boolean {
        if (!listening) return false
        val code = event.key()
        if (code == InputConstants.KEY_ESCAPE || code == InputConstants.KEY_DELETE || code == InputConstants.KEY_BACKSPACE) {
            value.set(InputBind.UNBOUND)
            ConfigSystem.store(ModuleManager.modulesConfig)
            listening = false
            resetPendingModifiers()
            SettingOverlayManager.stopKeyCapture(this)
            return true
        }

        val asMod = InputBind.Modifier.of(code)
        if (asMod != null) {
            pendingModifiers.add(asMod)
            pendingModifierKeyCode = code
            modifierDeadlineMs = System.currentTimeMillis() + 1000L
            return true
        }

        val key = InputConstants.Type.KEYBOARD.getOrCreate(code)
        val mods = EnumSet.noneOf(InputBind.Modifier::class.java).apply {
            addAll(pendingModifiers)
            addAll(InputBind.Modifier.fromRawValue(event.modifiers()))
        }
        value.set(InputBind(key, value.get().action, mods))
        ConfigSystem.store(ModuleManager.modulesConfig)
        listening = false
        resetPendingModifiers()
        SettingOverlayManager.stopKeyCapture(this)
        return true
    }

    override fun onKeyboardKeyReleased(event: KeyEvent): Boolean {
        if (!listening) return false
        val code = event.key()
        if (pendingModifierKeyCode != -1 && code == pendingModifierKeyCode) {
            commitPendingSoloModifier()
            return true
        }
        return false
    }

    override fun onMouseButtonCaptured(button: Int): Boolean {
        if (!listening) return false
        if (button != InputConstants.MOUSE_BUTTON_LEFT) {
            val key = InputConstants.Type.MOUSE.getOrCreate(button)
            value.set(InputBind(key, value.get().action, pendingModifiers.toSet()))
            ConfigSystem.store(ModuleManager.modulesConfig)
        }
        listening = false
        resetPendingModifiers()
        SettingOverlayManager.stopKeyCapture(this)
        return true
    }

    private fun drawCenteredBindDisplay(
        ctx: GuiGraphicsExtractor,
        centerX: Float,
        y: Float,
        modifiers: Set<InputBind.Modifier>,
        trailingKeyText: String?,
        trailingEllipsis: Boolean,
    ) {
        val orderedMods = InputBind.Modifier.entries.filter { it in modifiers }
        val modWidths = FloatArray(orderedMods.size) { idx ->
            UiDraw.measureText(orderedMods[idx].platformRenderName, 12f, UiFontWeight.MEDIUM)
        }
        val plusW = UiDraw.measureText("+", 12f, UiFontWeight.MONOSPACE)
        val sepGap = 2f

        var totalW = 0f
        for (mw in modWidths) {
            totalW += mw + sepGap + plusW + sepGap
        }
        val keyW = if (trailingKeyText != null) {
            UiDraw.measureText(trailingKeyText, 12f, UiFontWeight.BOLD)
        } else if (trailingEllipsis) {
            UiDraw.measureText("...", 12f, UiFontWeight.MEDIUM)
        } else {
            0f
        }
        totalW += keyW

        var curX = centerX - totalW * 0.5f
        for (i in orderedMods.indices) {
            val modName = orderedMods[i].platformRenderName
            UiDraw.drawText(ctx, modName, curX, y, 12f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM)
            curX += modWidths[i] + sepGap
            UiDraw.drawText(ctx, "+", curX, y, 12f, UiPalette.TABGUI_DISABLED_COLOR, UiFontWeight.MONOSPACE)
            curX += plusW + sepGap
        }

        if (trailingKeyText != null) {
            UiDraw.drawText(ctx, trailingKeyText, curX, y, 12f, UiPalette.TEXT_COLOR, UiFontWeight.BOLD)
        } else if (trailingEllipsis) {
            UiDraw.drawText(ctx, "...", curX, y, 12f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM)
        }
    }

    override fun measureHeight(width: Float): Float = 44f

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
        val h = 44f
        recordBounds(x, y, width, h)

        val bind = value.get()
        val boxY = y + 3f
        val boxH = 38f

        // `.change-bind { background-color: transparent; border: solid 2px var(--accent-color); border-radius: 3px; padding: 4px; }`
        UiDraw.fillBox(
            ctx = ctx,
            x = x,
            y = boxY,
            w = width,
            h = boxH,
            color = 0x00000000,
            radius = 3f,
            borderColor = UiPalette.accent(1f),
            borderWidth = 2f,
        )

        // Top-right `SwitchBindAction.svelte` when bound
        var actionReserveEachSide = 6f
        lastActionRect.fill(0f)
        if (!bind.isUnbound) {
            val actLabel = bind.action.tag
            val actTextW = UiDraw.measureText(actLabel, 12f, UiFontWeight.MEDIUM)
            val arrowW = 8f
            val actW = actTextW + 3f + arrowW
            val actX = x + width - 5f - actW
            val actY = boxY + 4.5f
            val actHovered = mouseX in (actX - 2f)..(x + width - 2f) && mouseY in (boxY + 2f)..(boxY + 20f)

            lastActionRect[0] = actX - 3f
            lastActionRect[1] = boxY + 1f
            lastActionRect[2] = actW + 7f
            lastActionRect[3] = 19f
            actionReserveEachSide = actW + 10f

            val slideX = -5f * actionSlideAnim
            val textAlpha = (1f - actionSlideAnim * 0.6f).coerceIn(0.25f, 1f)
            val actTextCol = UiPalette.withAlphaFactor(
                if (actHovered) UiPalette.TEXT_COLOR else UiPalette.TABGUI_DISABLED_COLOR,
                textAlpha,
            )
            UiDraw.drawText(ctx, actLabel, actX + slideX, actY, 12f, actTextCol, UiFontWeight.MEDIUM)

            val jiggle = sin(arrowJiggleAnim * Math.PI.toFloat()) * 2f
            UiDraw.drawIcon(
                ctx = ctx,
                id = UiIcons.SETTINGS_EXPAND,
                x = actX + actTextW + 3f + jiggle,
                y = actY + 3.5f,
                boxW = 8f,
                boxH = 6.5f,
                argb = if (actHovered) UiPalette.TEXT_COLOR else UiPalette.TABGUI_DISABLED_COLOR,
                rotationRad = -Math.PI.toFloat() * 0.5f,
            )
        }

        // Top row centered `.name`
        val rawName = UiTextUtils.displayName(value.name)
        val availNameW = max(32f, width - actionReserveEachSide * 2f)
        val nameStr = UiDraw.ellipsize(rawName, availNameW, 12f, UiFontWeight.MEDIUM)
        val nameW = UiDraw.measureText(nameStr, 12f, UiFontWeight.MEDIUM)
        UiDraw.drawText(
            ctx = ctx,
            text = nameStr,
            x = x + (width - nameW) * 0.5f,
            y = boxY + 4.5f,
            fontSizePx = 12f,
            argb = UiPalette.TEXT_COLOR,
            weight = UiFontWeight.MEDIUM,
        )

        // Bottom row centered `.bind` (`BindDisplay.svelte` / `Press any key...` / dimmed `None`)
        val bindRowY = boxY + 20f
        val centerX = x + width * 0.5f
        if (!listening) {
            if (bind.isUnbound) {
                val noneText = UiLocalization.text("None", "无")
                val nw = UiDraw.measureText(noneText, 12f, UiFontWeight.MEDIUM)
                UiDraw.drawText(ctx, noneText, centerX - nw * 0.5f, bindRowY, 12f, UiPalette.TABGUI_DISABLED_COLOR, UiFontWeight.MEDIUM)
            } else {
                drawCenteredBindDisplay(
                    ctx = ctx,
                    centerX = centerX,
                    y = bindRowY,
                    modifiers = bind.modifiers,
                    trailingKeyText = UiTextUtils.formatKeyName(bind.boundKey),
                    trailingEllipsis = false,
                )
            }
        } else {
            if (pendingModifiers.isNotEmpty()) {
                drawCenteredBindDisplay(
                    ctx = ctx,
                    centerX = centerX,
                    y = bindRowY,
                    modifiers = pendingModifiers,
                    trailingKeyText = null,
                    trailingEllipsis = true,
                )
            } else {
                val prompt = UiLocalization.text("Press any key...", "请按任意键...")
                val pw = UiDraw.measureText(prompt, 12f, UiFontWeight.MEDIUM)
                UiDraw.drawText(ctx, prompt, centerX - pw * 0.5f, bindRowY, 12f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM)
            }
        }

        maybeShowTooltip(mouseX, mouseY, x, boxY, width, boxH, canvasWidth)
        return h
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int, shiftHeld: Boolean): Boolean {
        val boxY = lastY + 3f
        val boxH = 38f
        if (mouseX !in lastX..(lastX + lastWidth) || mouseY !in boxY..(boxY + boxH)) return false

        val bind = value.get()
        // Top-right `SwitchBindAction` click (`Toggle` -> `Hold` -> `Smart`)
        if (!bind.isUnbound && !listening && button == InputConstants.MOUSE_BUTTON_LEFT &&
            lastActionRect[2] > 0f &&
            mouseX in lastActionRect[0]..(lastActionRect[0] + lastActionRect[2]) &&
            mouseY in lastActionRect[1]..(lastActionRect[1] + lastActionRect[3])
        ) {
            val actions = InputBind.BindAction.entries
            val next = actions[(actions.indexOf(bind.action) + 1) % actions.size]
            value.set(InputBind(bind.boundKey, next, bind.modifiers))
            actionSlideAnim = 1f
            arrowJiggleAnim = 1f
            return true
        }

        if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
            value.set(InputBind.UNBOUND)
            listening = false
            resetPendingModifiers()
            SettingOverlayManager.stopKeyCapture(this)
            return true
        }

        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
            listening = true
            resetPendingModifiers()
            SettingOverlayManager.startKeyCapture(this)
            return true
        }
        return false
    }
}

/**
 * 11. KEY (`KeySetting.svelte`: full-width `2px solid var(--accent-color)` transparent button box)
 */
class KeySettingNode(override val value: Value<InputConstants.Key>) : SettingNode(value), SettingOverlayManager.KeyCaptureTarget {
    private var listening = false

    override fun cancelCapture() {
        listening = false
    }

    override fun onKeyboardKeyCaptured(event: KeyEvent): Boolean {
        if (!listening) return false
        val code = event.key()
        if (code == InputConstants.KEY_ESCAPE || code == InputConstants.KEY_DELETE || code == InputConstants.KEY_BACKSPACE) {
            value.set(InputConstants.UNKNOWN)
        } else {
            value.set(InputConstants.Type.KEYBOARD.getOrCreate(code))
        }
        ConfigSystem.store(ModuleManager.modulesConfig)
        listening = false
        SettingOverlayManager.stopKeyCapture(this)
        return true
    }

    override fun onMouseButtonCaptured(button: Int): Boolean {
        if (!listening) return false
        if (button != InputConstants.MOUSE_BUTTON_LEFT) {
            value.set(InputConstants.Type.MOUSE.getOrCreate(button))
            ConfigSystem.store(ModuleManager.modulesConfig)
        }
        listening = false
        SettingOverlayManager.stopKeyCapture(this)
        return true
    }

    override fun measureHeight(width: Float): Float = 32f

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
        val h = 32f
        recordBounds(x, y, width, h)

        val key = value.get()
        val boxY = y + 3f
        val boxH = 26f

        UiDraw.fillBox(
            ctx = ctx,
            x = x,
            y = boxY,
            w = width,
            h = boxH,
            color = 0x00000000,
            radius = 3f,
            borderColor = UiPalette.accent(1f),
            borderWidth = 2f,
        )

        val lh = UiDraw.lineHeight(12f, UiFontWeight.MEDIUM)
        val ty = boxY + (boxH - lh) * 0.5f

        if (!listening) {
            val prefix = "${UiTextUtils.displayName(value.name)}: "
            val keyStr = UiTextUtils.formatKeyName(key)
            val isUnbound = key == InputConstants.UNKNOWN
            val keyWeight = if (isUnbound) UiFontWeight.MEDIUM else UiFontWeight.BOLD
            val prefixW = UiDraw.measureText(prefix, 12f, UiFontWeight.MEDIUM)
            val keyW = UiDraw.measureText(keyStr, 12f, keyWeight)
            val startX = x + (width - (prefixW + keyW)) * 0.5f

            UiDraw.drawText(ctx, prefix, startX, ty, 12f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM)
            UiDraw.drawText(
                ctx, keyStr, startX + prefixW, ty, 12f,
                if (isUnbound) UiPalette.TABGUI_DISABLED_COLOR else UiPalette.TEXT_COLOR,
                keyWeight,
            )
        } else {
            val prompt = UiLocalization.text("Press any key", "请按任意键")
            val pw = UiDraw.measureText(prompt, 12f, UiFontWeight.MEDIUM)
            UiDraw.drawText(ctx, prompt, x + (width - pw) * 0.5f, ty, 12f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM)
        }

        maybeShowTooltip(mouseX, mouseY, x, boxY, width, boxH, canvasWidth)
        return h
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int, shiftHeld: Boolean): Boolean {
        val boxY = lastY + 3f
        val boxH = 26f
        if (mouseX !in lastX..(lastX + lastWidth) || mouseY !in boxY..(boxY + boxH)) return false

        if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
            value.set(InputConstants.UNKNOWN)
            listening = false
            SettingOverlayManager.stopKeyCapture(this)
            return true
        }
        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
            listening = true
            SettingOverlayManager.startKeyCapture(this)
            return true
        }
        return false
    }
}

/**
 * 12. FILE (`FileSetting.svelte`: SDL3 file dialog + reset + open folder)
 */
class FileSettingNode(override val value: FileValue) : SettingNode(value) {
    @Volatile
    private var selecting = false

    override fun measureHeight(width: Float): Float = 48f

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
        val h = 48f
        recordBounds(x, y, width, h)

        UiDraw.drawText(ctx, UiTextUtils.displayName(value.name), x, y + 3f, 12f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM)

        UiDraw.drawIcon(ctx, UiIcons.RESET, x + width - 32f, y + 3f, 12f, 12f, UiPalette.white(0.75f))
        UiDraw.drawIcon(ctx, UiIcons.OPEN_FILE, x + width - 14f, y + 3f, 13f, 12f, UiPalette.white(0.75f))

        val boxY = y + 20f
        val boxH = 24f
        val rawPath = value.get().path
        val isEmptyPath = rawPath.isBlank() || value.absoluteFile == ConfigSystem.rootFolder
        val display = when {
            selecting -> UiLocalization.text("Selecting...", "正在选择文件...")
            isEmptyPath -> UiLocalization.text("<empty>", "<未选择>")
            else -> rawPath
        }
        UiDraw.fillBox(ctx, x, boxY, width, boxH, UiPalette.inputBg, radius = 3f, borderColor = UiPalette.inputBorder, borderWidth = 1f)
        val fileWeight = if (UiLocalization.isChinese && (selecting || isEmptyPath)) UiFontWeight.REGULAR else UiFontWeight.MONOSPACE
        val clipped = UiDraw.ellipsize(display, width - 16f, 11f, fileWeight)
        val lh = UiDraw.lineHeight(11f, fileWeight)
        UiDraw.drawText(
            ctx, clipped, x + 8f, boxY + (boxH - lh) * 0.5f, 11f,
            if (isEmptyPath && !selecting) UiPalette.TEXT_MUTED_COLOR else UiPalette.TEXT_COLOR,
            fileWeight,
        )

        maybeShowTooltip(mouseX, mouseY, x, y, width - 38f, 18f, canvasWidth)
        return h
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int, shiftHeld: Boolean): Boolean {
        if (button != InputConstants.MOUSE_BUTTON_LEFT) return false
        if (mouseX !in lastX..(lastX + lastWidth) || mouseY !in lastY..(lastY + lastHeight)) return false

        if (mouseY in lastY..(lastY + 18f) && mouseX in (lastX + lastWidth - 35f)..(lastX + lastWidth - 18f)) {
            value.restore()
            return true
        }
        if (mouseY in lastY..(lastY + 18f) && mouseX >= lastX + lastWidth - 17f) {
            runCatching {
                val f = value.absoluteFile
                val dir = when {
                    f.isDirectory -> f
                    f.parentFile?.exists() == true -> f.parentFile
                    else -> ConfigSystem.rootFolder
                }
                Blaze3D.openPath(dir.toPath())
            }
            return true
        }
        if (mouseY >= lastY + 20f && !selecting) {
            selecting = true
            runCatching {
                value.dialogMode.selectFiles(value.supportedExtensions).whenComplete { files, _ ->
                    selecting = false
                    val picked = files?.firstOrNull()
                    if (!picked.isNullOrBlank()) {
                        mc.execute {
                            value.set(File(picked))
                        }
                    }
                }
            }.onFailure {
                selecting = false
            }
            return true
        }
        return false
    }
}

/**
 * 13, 14, 15. VECTOR3_I, VECTOR3_D, VECTOR2_F (`VectorSetting.svelte` with X/Y/Z inputs + Locate button)
 */
class VectorSettingNode(
    override val value: Value<*>,
    private val dimensions: Int,
    private val integerMode: Boolean,
) : SettingNode(value) {

    private val hasLocate: Boolean = (value as? Vec3Value<*>)?.useLocateButton == true

    private val editors: Array<UiTextEditState> = Array(dimensions) {
        UiTextEditState(
            numericOnly = true,
            allowFloat = !integerMode,
            maxLength = 16,
            onCommit = { commitAxes() },
        )
    }

    private fun readComponents(): DoubleArray {
        return when (val v = value.get()) {
            is Vec3i -> doubleArrayOf(v.x.toDouble(), v.y.toDouble(), v.z.toDouble())
            is Vec3 -> doubleArrayOf(v.x, v.y, v.z)
            is Vector2fc -> doubleArrayOf(v.x().toDouble(), v.y().toDouble())
            else -> DoubleArray(dimensions)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun commitAxes() {
        val current = readComponents()
        val parsed = DoubleArray(dimensions) { idx ->
            editors[idx].text.trim().toDoubleOrNull() ?: current[idx]
        }
        when (value.get()) {
            is Vec3i -> (value as Value<Vec3i>).set(Vec3i(parsed[0].roundToInt(), parsed[1].roundToInt(), parsed[2].roundToInt()))
            is Vec3 -> (value as Value<Vec3>).set(Vec3(parsed[0], parsed[1], parsed[2]))
            is Vector2fc -> (value as Value<Vector2fc>).set(Vector2f(parsed[0].toFloat(), parsed[1].toFloat()))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun locatePlayer() {
        val player = mc.player ?: return
        when (value.get()) {
            is Vec3i -> (value as Value<Vec3i>).set(player.blockPosition())
            is Vec3 -> {
                val p = player.position()
                val rx = (p.x * 100.0).roundToInt() / 100.0
                val ry = (p.y * 100.0).roundToInt() / 100.0
                val rz = (p.z * 100.0).roundToInt() / 100.0
                (value as Value<Vec3>).set(Vec3(rx, ry, rz))
            }
        }
    }

    private fun fmtAxis(d: Double): String =
        if (integerMode) d.roundToInt().toString() else String.format(Locale.US, "%.2f", d).trimEnd('0').trimEnd('.')

    override fun measureHeight(width: Float): Float = 48f

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
        val h = 48f
        recordBounds(x, y, width, h)

        val comps = readComponents()
        for (i in 0 until dimensions) {
            editors[i].setTextExternal(fmtAxis(comps[i]))
        }

        UiDraw.drawText(ctx, UiTextUtils.displayName(value.name), x, y + 3f, 12f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM)

        val boxY = y + 20f
        val boxH = 24f
        val locateReserve = if (hasLocate) 26f else 0f
        val gap = 4f
        val cellW = (width - locateReserve - gap * (dimensions - 1)) / dimensions
        val labels = arrayOf("X", "Y", "Z")

        for (i in 0 until dimensions) {
            val cx = x + i * (cellW + gap)
            val ed = editors[i]
            UiDraw.fillBox(
                ctx, cx, boxY, cellW, boxH,
                UiPalette.inputBg, radius = 3f,
                borderColor = if (ed.focused) UiPalette.accent(1f) else UiPalette.inputBorder,
                borderWidth = 1f,
            )
            UiDraw.drawText(ctx, labels[i], cx + 5f, boxY + 5.5f, 10.5f, UiPalette.TEXT_MUTED_COLOR, UiFontWeight.SEMIBOLD)
            UiDraw.drawTextFieldContent(
                ctx, ed,
                x = cx + 14f, y = boxY, w = cellW - 14f, h = boxH,
                fontSizePx = 11f, paddingX = 3f,
            )
        }

        if (hasLocate) {
            val lx = x + width - 22f
            UiDraw.fillBox(ctx, lx, boxY, 22f, boxH, UiPalette.dropdownTriggerBg, radius = 3f)
            UiDraw.drawIcon(ctx, UiIcons.LOCATE, lx + 4f, boxY + 5f, 14f, 14f, UiPalette.TEXT_COLOR)
        }

        maybeShowTooltip(mouseX, mouseY, x, y, width, 18f, canvasWidth)
        return h
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int, shiftHeld: Boolean): Boolean {
        if (button != InputConstants.MOUSE_BUTTON_LEFT) return false
        val boxY = lastY + 20f
        if (mouseX !in lastX..(lastX + lastWidth) || mouseY !in boxY..(boxY + 24f)) return false

        if (hasLocate && mouseX >= lastX + lastWidth - 22f) {
            SettingOverlayManager.clearTextInputFocus(commit = true)
            locatePlayer()
            return true
        }

        val locateReserve = if (hasLocate) 26f else 0f
        val gap = 4f
        val cellW = (lastWidth - locateReserve - gap * (dimensions - 1)) / dimensions
        for (i in 0 until dimensions) {
            val cx = lastX + i * (cellW + gap)
            if (mouseX in cx..(cx + cellW)) {
                SettingOverlayManager.focusTextInput(editors[i], selectAll = true)
                return true
            }
        }
        return false
    }
}

/**
 * 16. CURVE (`CurveSetting.svelte`: interactive Catmull-Rom spline chart + smooth expand/collapse)
 */
class CurveSettingNode(override val value: CurveValue) : SettingNode(value) {
    private var expanded by rememberedBoolean("expanded")
    private var expandAnim = if (expanded) 1f else 0f
    private var draggedIndex = -1

    companion object {
        private const val EXTRA_H = 140f
    }

    override fun snapLayoutState() {
        expandAnim = if (expanded) 1f else 0f
        draggedIndex = -1
    }

    override fun stepAnimations(dtSeconds: Float) {
        expandAnim = UiAnimation.expLerp(expandAnim, if (expanded) 1f else 0f, 16f, dtSeconds)
    }

    override fun measureHeight(width: Float): Float {
        if (!expanded && expandAnim <= 0.002f) return 28f
        return 28f + EXTRA_H * expandAnim
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

        UiDraw.drawText(ctx, UiTextUtils.displayName(value.name), x, y + 6f, 12f, UiPalette.TEXT_COLOR, UiFontWeight.MEDIUM)
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

        val bodyAnimH = EXTRA_H * expandAnim
        if (bodyAnimH > 0.5f) {
            UiDraw.withCssScissor(ctx, x - 4f, y + 26f, width + 8f, bodyAnimH + 2f) {
                val padL = 6f
                val padR = 6f
                val chartX = x + padL
                val chartY = y + 28f
                val chartW = width - padL - padR
                val chartH = 116f

                UiDraw.fillBox(ctx, chartX, chartY, chartW, chartH, UiPalette.inputBg, radius = 4f, borderColor = UiPalette.inputBorder, borderWidth = 1f)

                for (g in 1..3) {
                    val gx = chartX + chartW * (g / 4f)
                    val gy = chartY + chartH * (g / 4f)
                    UiDraw.fillBox(ctx, gx, chartY + 1f, 1f, chartH - 2f, UiPalette.white(0.06f))
                    UiDraw.fillBox(ctx, chartX + 1f, gy, chartW - 2f, 1f, UiPalette.white(0.06f))
                }

                val xMin = value.xAxis.range.start
                val xMax = value.xAxis.range.endInclusive
                val yMin = value.yAxis.range.start
                val yMax = value.yAxis.range.endInclusive
                val xSpan = (xMax - xMin).takeIf { abs(it) > 1e-6f } ?: 1f
                val ySpan = (yMax - yMin).takeIf { abs(it) > 1e-6f } ?: 1f

                val steps = 40
                var prevPx = chartX
                var prevPy = chartY + chartH * (1f - ((value.transform(xMin) - yMin) / ySpan).coerceIn(0f, 1f))
                for (s in 1..steps) {
                    val t = s.toFloat() / steps
                    val sampleX = xMin + t * xSpan
                    val sampleY = value.transform(sampleX)
                    val px = chartX + t * chartW
                    val py = chartY + chartH * (1f - ((sampleY - yMin) / ySpan).coerceIn(0f, 1f))
                    UiDraw.drawLineSegment(ctx, prevPx, prevPy, px, py, 2f, UiPalette.accent(1f))
                    prevPx = px
                    prevPy = py
                }

                val pts = value.get()
                pts.forEachIndexed { idx, pt ->
                    val px = chartX + ((pt.x - xMin) / xSpan).coerceIn(0f, 1f) * chartW
                    val py = chartY + (1f - ((pt.y - yMin) / ySpan).coerceIn(0f, 1f)) * chartH
                    UiDraw.fillBox(
                        ctx, px - 4.5f, py - 4.5f, 9f, 9f,
                        if (idx == draggedIndex) UiPalette.TEXT_COLOR else UiPalette.accent(1f),
                        radius = 4.5f,
                        borderColor = UiPalette.TEXT_COLOR,
                        borderWidth = 1.5f,
                    )
                }

                val axisLabel = "${value.xAxis.label} \u2192 ${value.yAxis.label}"
                UiDraw.drawText(ctx, axisLabel, chartX, chartY + chartH + 4f, 10f, UiPalette.TEXT_MUTED_COLOR, UiFontWeight.REGULAR)
            }
        }

        maybeShowTooltip(mouseX, mouseY, x, y, width - 34f, 24f, canvasWidth)
        return h
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int, shiftHeld: Boolean): Boolean {
        if (mouseX !in lastX..(lastX + lastWidth) || mouseY !in lastY..(lastY + lastHeight)) return false

        if (mouseY <= lastY + 25f) {
            if (expanded && button == InputConstants.MOUSE_BUTTON_LEFT &&
                mouseX in (lastX + lastWidth - 32f)..(lastX + lastWidth - 17f)
            ) {
                value.restore()
                return true
            }
            expanded = !expanded
            return true
        }

        if (!expanded || expandAnim <= 0.5f) return false
        val chartX = lastX + 6f
        val chartY = lastY + 28f
        val chartW = lastWidth - 12f
        val chartH = 116f
        if (mouseX !in chartX..(chartX + chartW) || mouseY !in chartY..(chartY + chartH)) return false

        val xMin = value.xAxis.range.start
        val xMax = value.xAxis.range.endInclusive
        val yMin = value.yAxis.range.start
        val yMax = value.yAxis.range.endInclusive
        val xSpan = (xMax - xMin).takeIf { abs(it) > 1e-6f } ?: 1f
        val ySpan = (yMax - yMin).takeIf { abs(it) > 1e-6f } ?: 1f

        val pts = value.get()
        var hitIdx = -1
        var bestDist = 9f
        pts.forEachIndexed { idx, pt ->
            val px = chartX + ((pt.x - xMin) / xSpan) * chartW
            val py = chartY + (1f - ((pt.y - yMin) / ySpan)) * chartH
            val d = hypot(mouseX - px, mouseY - py)
            if (d <= bestDist) {
                bestDist = d
                hitIdx = idx
            }
        }

        if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
            if (hitIdx > 0 && hitIdx < pts.lastIndex && pts.size > 2) {
                pts.removeAt(hitIdx)
                value.set(pts)
            }
            return true
        }

        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
            if (hitIdx >= 0) {
                draggedIndex = hitIdx
            } else {
                val nx = (xMin + ((mouseX - chartX) / chartW).coerceIn(0.01f, 0.99f) * xSpan).coerceIn(xMin, xMax)
                val ny = (yMin + (1f - ((mouseY - chartY) / chartH).coerceIn(0f, 1f)) * ySpan).coerceIn(yMin, yMax)
                val newPt = Vector2f(nx, ny)
                pts.add(newPt)
                pts.sortBy { it.x }
                value.set(pts)
                draggedIndex = pts.indexOf(newPt)
            }
            return true
        }
        return false
    }

    override fun mouseDragged(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (draggedIndex < 0 || button != InputConstants.MOUSE_BUTTON_LEFT) return false
        val pts = value.get()
        if (draggedIndex !in pts.indices) return false

        val chartX = lastX + 6f
        val chartY = lastY + 28f
        val chartW = max(1f, lastWidth - 12f)
        val chartH = 116f

        val xMin = value.xAxis.range.start
        val xMax = value.xAxis.range.endInclusive
        val yMin = value.yAxis.range.start
        val yMax = value.yAxis.range.endInclusive
        val xSpan = (xMax - xMin).takeIf { abs(it) > 1e-6f } ?: 1f
        val ySpan = (yMax - yMin).takeIf { abs(it) > 1e-6f } ?: 1f

        val ny = (yMin + (1f - ((mouseY - chartY) / chartH).coerceIn(0f, 1f)) * ySpan).coerceIn(yMin, yMax)
        val nx = when (draggedIndex) {
            0 -> xMin
            pts.lastIndex -> xMax
            else -> {
                val loBound = pts[draggedIndex - 1].x + xSpan * 0.005f
                val hiBound = pts[draggedIndex + 1].x - xSpan * 0.005f
                (xMin + ((mouseX - chartX) / chartW).coerceIn(0f, 1f) * xSpan).coerceIn(min(loBound, hiBound), max(loBound, hiBound))
            }
        }
        pts[draggedIndex] = Vector2f(nx, ny)
        value.set(pts)
        return true
    }

    override fun mouseReleased(mouseX: Float, mouseY: Float, button: Int) {
        draggedIndex = -1
    }
}

/**
 * 17. CHOICE (`ChoiceSetting.svelte`: `ModeValueGroup` mode dropdown + right-side `ExpandArrow` + smooth recursive expand/collapse!)
 */
class ChoiceModeGroupNode(override val value: ModeValueGroup<*>) : SettingNode(value) {
    private var expanded by rememberedBoolean("expanded", true)
    private var expandAnim: Float = if (expanded) 1f else 0f
    private var dropdownChevronRot: Float = -Math.PI.toFloat() * 0.5f
    private var lastObservedMode: Mode? = null
    private var modeSwitchAnim: Float = 1f

    private fun hasActiveModeChildren(): Boolean =
        SettingRenderer.hasAnyActiveChildren(value.activeMode)

    override fun snapLayoutState() {
        expandAnim = if (expanded) 1f else 0f
        modeSwitchAnim = 1f
    }

    override fun stepAnimations(dtSeconds: Float) {
        val currentMode = value.activeMode
        if (lastObservedMode == null) {
            lastObservedMode = currentMode
        } else if (lastObservedMode !== currentMode) {
            lastObservedMode = currentMode
            modeSwitchAnim = 0f
        }
        modeSwitchAnim = UiAnimation.expLerp(modeSwitchAnim, 1f, 16f, dtSeconds)

        val hasChildren = hasActiveModeChildren()
        val targetExpand = if (expanded && hasChildren) 1f else 0f
        expandAnim = UiAnimation.expLerp(expandAnim, targetExpand, 16f, dtSeconds)

        val isOpen = SettingOverlayManager.activeDropdown?.ownerKey === value
        dropdownChevronRot = UiAnimation.expLerp(dropdownChevronRot, if (isOpen) 0f else (-Math.PI.toFloat() * 0.5f), 16f, dtSeconds)

        if (expanded || expandAnim > 0.002f) {
            SettingRenderer.stepGroupAnimations(currentMode, dtSeconds)
        }
    }

    private fun effectiveAnim(): Float = (expandAnim * modeSwitchAnim).coerceIn(0f, 1f)

    override fun measureHeight(width: Float): Float {
        val hasChildren = hasActiveModeChildren()
        if (!hasChildren || (!expanded && expandAnim <= 0.002f)) return 30f
        val nestedW = max(40f, width - 9f)
        val rawSubH = SettingRenderer.measureGroupHeight(value.activeMode, nestedW)
        if (rawSubH <= 0.01f) return 30f
        return 30f + (8f + rawSubH) * effectiveAnim()
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
        val hasChildren = hasActiveModeChildren()
        val totalH = measureHeight(width)
        recordBounds(x, y, width, totalH)

        val activeTag = value.activeMode.tag
        val isOpen = SettingOverlayManager.activeDropdown?.ownerKey === value

        val dropW = if (hasChildren) max(40f, width - 20f) else width
        val boxY = y + 2f
        val boxH = 25f
        if (isOpen) {
            SettingOverlayManager.updateDropdownAnchor(value, x, boxY + boxH, dropW, activeTag)
        }
        val rBottom = if (isOpen) 0f else 3f
        UiDraw.fillCssBox(
            ctx, x, boxY, dropW, boxH,
            rTL = 3f, rTR = 3f, rBR = rBottom, rBL = rBottom,
            cTL = UiPalette.dropdownTriggerBg,
        )

        val label = "${UiTextUtils.displayName(value.name)} \u2022 ${UiTextUtils.displayName(activeTag)}"
        val clipped = UiDraw.ellipsize(label, dropW - 28f, 12f, UiFontWeight.MEDIUM)
        val lh = UiDraw.lineHeight(12f, UiFontWeight.MEDIUM)
        val textCol = if (activeTag.equals("None", ignoreCase = true)) UiPalette.TEXT_DIMMED_COLOR else UiPalette.TEXT_COLOR
        UiDraw.drawText(ctx, clipped, x + 10f, boxY + (boxH - lh) * 0.5f, 12f, textCol, UiFontWeight.MEDIUM)
        UiDraw.drawIcon(
            ctx, UiIcons.SETTINGS_EXPAND,
            x + dropW - 19f, boxY + (boxH - 8f) * 0.5f, 10f, 8f,
            argb = if (isOpen) UiPalette.TEXT_COLOR else UiPalette.white(0.65f),
            rotationRad = dropdownChevronRot,
        )

        if (hasChildren) {
            val expRot = (-Math.PI.toFloat() * 0.5f) * (1f - expandAnim)
            val expCol = UiPalette.lerpArgb(UiPalette.white(0.5f), UiPalette.TEXT_COLOR, expandAnim)
            UiDraw.drawIcon(
                ctx, UiIcons.SETTINGS_EXPAND,
                x + width - 11f, boxY + (boxH - 8f) * 0.5f, 10f, 8f,
                argb = expCol,
                rotationRad = expRot,
            )
        }

        maybeShowTooltip(mouseX, mouseY, x, y, dropW, 28f, canvasWidth)

        if (hasChildren) {
            val nestedW = max(40f, width - 9f)
            val rawSubH = SettingRenderer.measureGroupHeight(value.activeMode, nestedW)
            val eff = effectiveAnim()
            val animBlockH = (8f + rawSubH) * eff
            if (rawSubH > 0.01f && animBlockH > 0.5f) {
                val childMx = if (expanded && eff > 0.85f) mouseX else -10000f
                val childMy = if (expanded && eff > 0.85f) mouseY else -10000f
                UiDraw.withCssScissor(ctx, x - 8f, y + 30f, width + 16f, animBlockH) {
                    val nestedY = y + 36f
                    val renderedH = SettingRenderer.renderGroup(
                        ctx = ctx,
                        group = value.activeMode,
                        x = x + 9f,
                        y = nestedY,
                        width = nestedW,
                        mouseX = childMx,
                        mouseY = childMy,
                        dtSeconds = dtSeconds,
                        canvasWidth = canvasWidth,
                    )
                    // `.nested-settings { border-left: solid 2px var(--clickgui-setting-group-border-color); padding-left: 7px; }`
                    UiDraw.fillBox(ctx, x, nestedY, 2f, max(2f, renderedH), UiPalette.accent(1f))
                }
            }
        }

        return totalH
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int, shiftHeld: Boolean): Boolean {
        if (mouseX !in lastX..(lastX + lastWidth) || mouseY !in lastY..(lastY + lastHeight)) return false
        val hasChildren = hasActiveModeChildren()
        val dropW = if (hasChildren) max(40f, lastWidth - 20f) else lastWidth

        if (mouseY <= lastY + 29f) {
            if (hasChildren && (button == InputConstants.MOUSE_BUTTON_RIGHT ||
                    (button == InputConstants.MOUSE_BUTTON_LEFT && mouseX > lastX + dropW + 2f))
            ) {
                expanded = !expanded
                return true
            }
            if (button == InputConstants.MOUSE_BUTTON_LEFT && mouseX <= lastX + dropW) {
                val opts = value.modes.map { it.tag }
                SettingOverlayManager.toggleDropdown(
                    ownerKey = value,
                    x = lastX,
                    y = lastY + 27f,
                    width = dropW,
                    options = opts,
                    currentValue = value.activeMode.tag,
                ) { chosen ->
                    runCatching { value.setByString(chosen) }
                }
                return true
            }
            return false
        }

        if (expanded && effectiveAnim() > 0.5f && hasChildren && mouseY >= lastY + 30f) {
            return SettingRenderer.mouseClickedList(
                SettingRenderer.allOptionChildrenOf(value.activeMode),
                mouseX, mouseY, button, shiftHeld,
            )
        }
        return false
    }

    override fun mouseDragged(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (!expanded || effectiveAnim() <= 0.5f) return false
        return SettingRenderer.mouseDraggedList(SettingRenderer.allOptionChildrenOf(value.activeMode), mouseX, mouseY, button)
    }

    override fun mouseReleased(mouseX: Float, mouseY: Float, button: Int) {
        SettingRenderer.mouseReleasedList(SettingRenderer.allOptionChildrenOf(value.activeMode), mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Float, mouseY: Float, scrollY: Double): Boolean {
        if (!expanded || effectiveAnim() <= 0.5f) return false
        return SettingRenderer.mouseScrolledList(SettingRenderer.allOptionChildrenOf(value.activeMode), mouseX, mouseY, scrollY)
    }
}

/**
 * 18. TOGGLEABLE (`TogglableSetting.svelte`: `ToggleableValueGroup` `Switch.svelte` header + smooth recursive nested children!)
 */
class ToggleableGroupNode(override val value: ToggleableValueGroup) : SettingNode(value) {
    private var expanded by rememberedBoolean("expanded")
    private var expandAnim = if (expanded) 1f else 0f
    private var knobProgress = if (value.enabled) 1f else 0f

    private fun hasSubChildren(): Boolean =
        SettingRenderer.hasAnyActiveChildren(value, exclude = value.enabledValue)

    override fun snapLayoutState() {
        expandAnim = if (expanded) 1f else 0f
    }

    override fun stepAnimations(dtSeconds: Float) {
        knobProgress = UiAnimation.expLerp(knobProgress, if (value.enabled) 1f else 0f, 13f, dtSeconds)
        val hasExtra = hasSubChildren()
        expandAnim = UiAnimation.expLerp(expandAnim, if (expanded && hasExtra) 1f else 0f, 16f, dtSeconds)
        if (expanded || expandAnim > 0.002f) {
            SettingRenderer.stepGroupAnimations(value, dtSeconds, exclude = value.enabledValue)
        }
    }

    override fun measureHeight(width: Float): Float {
        val hasExtra = hasSubChildren()
        if (!hasExtra || (!expanded && expandAnim <= 0.002f)) return 26f
        val nestedW = max(40f, width - 9f)
        val rawSubH = SettingRenderer.measureGroupHeight(value, nestedW, exclude = value.enabledValue)
        if (rawSubH <= 0.01f) return 26f
        return 26f + (8f + rawSubH) * expandAnim
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
        val hasExtra = hasSubChildren()
        val totalH = measureHeight(width)
        recordBounds(x, y, width, totalH)

        SwitchWidget.render(
            ctx = ctx,
            x = x,
            rowCenterY = y + 13f,
            width = width,
            label = UiTextUtils.displayName(value.name),
            progress = knobProgress,
            extraRightReserve = if (hasExtra) 18f else 0f,
        )

        if (hasExtra) {
            val chevronRot = (-Math.PI.toFloat() * 0.5f) * (1f - expandAnim)
            val chevronCol = UiPalette.lerpArgb(UiPalette.white(0.5f), UiPalette.TEXT_COLOR, expandAnim)
            UiDraw.drawIcon(
                ctx, UiIcons.SETTINGS_EXPAND,
                x + width - 11f, y + 9f, 10f, 8f,
                argb = chevronCol,
                rotationRad = chevronRot,
            )
        }

        maybeShowTooltip(mouseX, mouseY, x, y, width, 26f, canvasWidth)

        if (hasExtra && (expanded || expandAnim > 0.002f)) {
            val nestedW = max(40f, width - 9f)
            val rawSubH = SettingRenderer.measureGroupHeight(value, nestedW, exclude = value.enabledValue)
            val animBlockH = (8f + rawSubH) * expandAnim
            if (rawSubH > 0.01f && animBlockH > 0.5f) {
                val childMx = if (expanded && expandAnim > 0.85f) mouseX else -10000f
                val childMy = if (expanded && expandAnim > 0.85f) mouseY else -10000f
                UiDraw.withCssScissor(ctx, x - 8f, y + 26f, width + 16f, animBlockH) {
                    val nestedY = y + 32f
                    val renderedH = SettingRenderer.renderGroup(
                        ctx = ctx,
                        group = value,
                        x = x + 9f,
                        y = nestedY,
                        width = nestedW,
                        mouseX = childMx,
                        mouseY = childMy,
                        dtSeconds = dtSeconds,
                        canvasWidth = canvasWidth,
                        exclude = value.enabledValue,
                    )
                    UiDraw.fillBox(ctx, x, nestedY, 2f, max(2f, renderedH), UiPalette.accent(1f))
                }
            }
        }

        return totalH
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int, shiftHeld: Boolean): Boolean {
        if (mouseX !in lastX..(lastX + lastWidth) || mouseY !in lastY..(lastY + lastHeight)) return false
        val hasExtra = hasSubChildren()
        if (mouseY <= lastY + 26f) {
            if (button == InputConstants.MOUSE_BUTTON_RIGHT && hasExtra) {
                expanded = !expanded
                return true
            }
            if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                if (hasExtra && mouseX >= lastX + lastWidth - 20f) {
                    expanded = !expanded
                } else {
                    value.enabled = !value.enabled
                }
                return true
            }
        }
        if (expanded && expandAnim > 0.5f && hasExtra && mouseY > lastY + 26f) {
            return SettingRenderer.mouseClickedList(
                SettingRenderer.allOptionChildrenOf(value, exclude = value.enabledValue),
                mouseX, mouseY, button, shiftHeld,
            )
        }
        return false
    }

    override fun mouseDragged(mouseX: Float, mouseY: Float, button: Int): Boolean {
        if (!expanded || expandAnim <= 0.5f) return false
        return SettingRenderer.mouseDraggedList(
            SettingRenderer.allOptionChildrenOf(value, exclude = value.enabledValue),
            mouseX, mouseY, button,
        )
    }

    override fun mouseReleased(mouseX: Float, mouseY: Float, button: Int) {
        SettingRenderer.mouseReleasedList(
            SettingRenderer.allOptionChildrenOf(value, exclude = value.enabledValue),
            mouseX, mouseY, button,
        )
    }

    override fun mouseScrolled(mouseX: Float, mouseY: Float, scrollY: Double): Boolean {
        if (!expanded || expandAnim <= 0.5f) return false
        return SettingRenderer.mouseScrolledList(
            SettingRenderer.allOptionChildrenOf(value, exclude = value.enabledValue),
            mouseX, mouseY, scrollY,
        )
    }
}

/**
 * 19. CONFIGURABLE (`ConfigurableSetting.svelte`: expandable `ValueGroup` with smooth recursive expand/collapse!)
 */
class ConfigurableGroupNode(
    override val value: ValueGroup,
    var alwaysExpanded: Boolean = false,
) : SettingNode(value) {
    var expanded by rememberedBoolean("expanded")
    private var expandAnim: Float = if (alwaysExpanded || expanded) 1f else 0f

    private fun hasSubChildren(): Boolean =
        SettingRenderer.hasAnyActiveChildren(value)

    override fun snapLayoutState() {
        expandAnim = if (alwaysExpanded || expanded) 1f else 0f
    }

    override fun stepAnimations(dtSeconds: Float) {
        val open = alwaysExpanded || (expanded && hasSubChildren())
        expandAnim = if (alwaysExpanded) {
            1f
        } else {
            UiAnimation.expLerp(expandAnim, if (open) 1f else 0f, 16f, dtSeconds)
        }
        if (open || expandAnim > 0.002f) {
            SettingRenderer.stepGroupAnimations(value, dtSeconds)
        }
    }

    override fun measureHeight(width: Float): Float {
        val hasChildren = hasSubChildren()
        if (alwaysExpanded) {
            if (!hasChildren) return 26f
            return 28f + SettingRenderer.measureGroupHeight(value, width) + 4f
        }
        if (!hasChildren || (!expanded && expandAnim <= 0.002f)) return 26f
        val nestedW = max(40f, width - 9f)
        val rawSubH = SettingRenderer.measureGroupHeight(value, nestedW)
        if (rawSubH <= 0.01f) return 26f
        return 26f + (8f + rawSubH) * expandAnim
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
        val hasChildren = hasSubChildren()
        val totalH = measureHeight(width)
        recordBounds(x, y, width, totalH)

        if (alwaysExpanded) {
            UiDraw.drawText(ctx, UiTextUtils.displayName(value.name), x, y + 4f, 13f, UiPalette.TEXT_COLOR, UiFontWeight.SEMIBOLD)
            UiDraw.fillBox(ctx, x, y + 23f, width, 1f, UiPalette.white(0.10f))
            maybeShowTooltip(mouseX, mouseY, x, y, width, 24f, canvasWidth)
            if (hasChildren) {
                SettingRenderer.renderGroup(ctx, value, x, y + 28f, width, mouseX, mouseY, dtSeconds, canvasWidth)
            }
            return totalH
        }

        UiDraw.drawText(ctx, UiTextUtils.displayName(value.name), x, y + 5f, 12f, UiPalette.TEXT_COLOR, UiFontWeight.SEMIBOLD)
        if (hasChildren) {
            val chevronRot = (-Math.PI.toFloat() * 0.5f) * (1f - expandAnim)
            val chevronCol = UiPalette.lerpArgb(UiPalette.white(0.5f), UiPalette.TEXT_COLOR, expandAnim)
            UiDraw.drawIcon(
                ctx, UiIcons.SETTINGS_EXPAND,
                x + width - 11f, y + 9f, 10f, 8f,
                argb = chevronCol,
                rotationRad = chevronRot,
            )
        }

        maybeShowTooltip(mouseX, mouseY, x, y, width, 26f, canvasWidth)

        if (hasChildren && (expanded || expandAnim > 0.002f)) {
            val nestedW = max(40f, width - 9f)
            val rawSubH = SettingRenderer.measureGroupHeight(value, nestedW)
            val animBlockH = (8f + rawSubH) * expandAnim
            if (rawSubH > 0.01f && animBlockH > 0.5f) {
                val childMx = if (expanded && expandAnim > 0.85f) mouseX else -10000f
                val childMy = if (expanded && expandAnim > 0.85f) mouseY else -10000f
                UiDraw.withCssScissor(ctx, x - 8f, y + 26f, width + 16f, animBlockH) {
                    val nestedY = y + 32f
                    val renderedH = SettingRenderer.renderGroup(
                        ctx = ctx,
                        group = value,
                        x = x + 9f,
                        y = nestedY,
                        width = nestedW,
                        mouseX = childMx,
                        mouseY = childMy,
                        dtSeconds = dtSeconds,
                        canvasWidth = canvasWidth,
                    )
                    UiDraw.fillBox(ctx, x, nestedY, 2f, max(2f, renderedH), UiPalette.accent(1f))
                }
            }
        }
        return totalH
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, button: Int, shiftHeld: Boolean): Boolean {
        if (mouseX !in lastX..(lastX + lastWidth) || mouseY !in lastY..(lastY + lastHeight)) return false
        val open = alwaysExpanded || expanded
        if (!alwaysExpanded && mouseY <= lastY + 26f &&
            (button == InputConstants.MOUSE_BUTTON_LEFT || button == InputConstants.MOUSE_BUTTON_RIGHT)
        ) {
            if (hasSubChildren()) {
                expanded = !expanded
            }
            return true
        }
        if (open && (alwaysExpanded || expandAnim > 0.5f) && mouseY > lastY + 26f) {
            return SettingRenderer.mouseClickedList(SettingRenderer.allOptionChildrenOf(value), mouseX, mouseY, button, shiftHeld)
        }
        return false
    }

    override fun mouseDragged(mouseX: Float, mouseY: Float, button: Int): Boolean {
        val open = alwaysExpanded || (expanded && expandAnim > 0.5f)
        return open && SettingRenderer.mouseDraggedList(SettingRenderer.allOptionChildrenOf(value), mouseX, mouseY, button)
    }

    override fun mouseReleased(mouseX: Float, mouseY: Float, button: Int) {
        SettingRenderer.mouseReleasedList(SettingRenderer.allOptionChildrenOf(value), mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Float, mouseY: Float, scrollY: Double): Boolean {
        val open = alwaysExpanded || (expanded && expandAnim > 0.5f)
        return open && SettingRenderer.mouseScrolledList(SettingRenderer.allOptionChildrenOf(value), mouseX, mouseY, scrollY)
    }
}
