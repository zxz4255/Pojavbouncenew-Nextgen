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
import net.ccbluex.liquidbounce.ui.clickgui.glass.ClickGuiGlassSurface
import net.ccbluex.liquidbounce.ui.common.UiAnimation
import net.ccbluex.liquidbounce.ui.common.UiDraw
import net.ccbluex.liquidbounce.ui.common.UiFontWeight
import net.ccbluex.liquidbounce.ui.common.UiPalette
import net.ccbluex.liquidbounce.ui.common.UiTextEditState
import net.ccbluex.liquidbounce.ui.common.UiTextUtils
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.PreeditEvent
import kotlin.math.max
import kotlin.math.min

/**
 * Manages top-level ClickGUI overlays (`Dropdown.svelte` portal popups), active text input focus,
 * and active keybind/mousebind capture so portals never clip inside scrollable panels.
 */
object SettingOverlayManager {

    class DropdownPopup(
        val ownerKey: Any,
        var x: Float,
        var y: Float,
        var width: Float,
        val options: List<String>,
        var currentValue: String,
        val onSelect: (String) -> Unit,
    ) {
        var targetScrollOffset: Float = 0f
        var scrollOffset: Float = 0f
    }

    interface KeyCaptureTarget {
        fun onKeyboardKeyCaptured(event: KeyEvent): Boolean
        fun onKeyboardKeyReleased(event: KeyEvent): Boolean = false
        fun onMouseButtonCaptured(button: Int): Boolean
        fun cancelCapture()
    }

    var activeDropdown: DropdownPopup? = null
        private set

    var activeTextInput: UiTextEditState? = null
        private set

    var activeKeyCapture: KeyCaptureTarget? = null
        private set

    val isCapturingKeyboard: Boolean
        get() = (activeTextInput?.focused == true) || activeKeyCapture != null

    val hasOpenDropdown: Boolean
        get() = activeDropdown != null

    fun syncNativeTextInputFocus(screen: Screen? = mc.gui.screen()) {
        if (screen != null) {
            mc.onTextInputFocusChange(screen, activeTextInput?.focused == true)
        }
    }

    fun focusTextInput(state: UiTextEditState, selectAll: Boolean = false) {
        if (activeTextInput !== state) {
            activeTextInput?.unfocus(commit = true)
        }
        activeKeyCapture?.cancelCapture()
        activeKeyCapture = null
        activeTextInput = state
        state.focus(selectAll)
        syncNativeTextInputFocus()
    }

    fun clearTextInputFocus(commit: Boolean = true, screen: Screen? = mc.gui.screen()) {
        activeTextInput?.unfocus(commit = commit)
        activeTextInput = null
        if (screen != null) {
            mc.onTextInputFocusChange(screen, false)
        }
    }

    fun startKeyCapture(target: KeyCaptureTarget) {
        clearTextInputFocus(commit = true)
        activeDropdown = null
        if (activeKeyCapture !== target) {
            activeKeyCapture?.cancelCapture()
        }
        activeKeyCapture = target
    }

    fun stopKeyCapture(target: KeyCaptureTarget? = null) {
        if (target == null || activeKeyCapture === target) {
            activeKeyCapture = null
        }
    }

    fun toggleDropdown(
        ownerKey: Any,
        x: Float,
        y: Float,
        width: Float,
        options: List<String>,
        currentValue: String,
        onSelect: (String) -> Unit,
    ) {
        if (activeDropdown?.ownerKey == ownerKey) {
            activeDropdown = null
        } else {
            activeDropdown = DropdownPopup(ownerKey, x, y, width, options, currentValue, onSelect)
        }
    }

    fun updateDropdownAnchor(ownerKey: Any, x: Float, y: Float, width: Float, currentValue: String) {
        val popup = activeDropdown ?: return
        if (popup.ownerKey == ownerKey) {
            popup.x = x
            popup.y = y
            popup.width = width
            popup.currentValue = currentValue
        }
    }

    fun closeDropdown() {
        activeDropdown = null
    }

    fun resetAll() {
        clearTextInputFocus(commit = true)
        activeKeyCapture?.cancelCapture()
        activeKeyCapture = null
        activeDropdown = null
    }

    fun renderDropdownOverlay(
        ctx: GuiGraphicsExtractor,
        mouseX: Float,
        mouseY: Float,
        canvasHeight: Float,
        dtSeconds: Float = 1f / 60f,
    ) {
        renderDropdownOverlay(ctx, mouseX, mouseY, 4096f, canvasHeight, dtSeconds)
    }

    fun mouseClickedDropdown(mouseX: Float, mouseY: Float, button: Int, canvasHeight: Float): Boolean =
        handleDropdownMouseClick(mouseX, mouseY, button, 4096f, canvasHeight)

    fun mouseScrolledDropdown(mouseX: Float, mouseY: Float, scrollY: Double, canvasHeight: Float): Boolean =
        handleDropdownScroll(mouseX, mouseY, scrollY, 4096f, canvasHeight)

    fun renderDropdownOverlay(
        ctx: GuiGraphicsExtractor,
        mouseX: Float,
        mouseY: Float,
        canvasWidth: Float,
        canvasHeight: Float,
        dtSeconds: Float = 1f / 60f,
    ) {
        val popup = activeDropdown ?: return
        if (popup.options.isEmpty()) return

        val itemH = 24f
        val padY = 6f
        val contentH = popup.options.size * itemH
        val maxH = min(240f, canvasHeight - 24f)
        val boxH = min(contentH, maxH) + padY * 2f
        val boxW = max(popup.width, 90f)
        val bx = popup.x.coerceIn(4f, max(4f, canvasWidth - boxW - 4f))
        val by = if (popup.y + boxH > canvasHeight - 4f && popup.y - boxH - 26f > 4f) {
            popup.y - boxH - 26f
        } else {
            popup.y.coerceAtMost(max(4f, canvasHeight - boxH - 4f))
        }

        val maxScroll = max(0f, contentH - (boxH - padY * 2f))
        popup.targetScrollOffset = popup.targetScrollOffset.coerceIn(0f, maxScroll)
        popup.scrollOffset = UiAnimation.expLerp(popup.scrollOffset, popup.targetScrollOffset, 18f, dtSeconds)
            .coerceIn(0f, maxScroll)

        val glassMode = ClickGuiGlassSurface.isActive
        if (glassMode) {
            ClickGuiGlassSurface.drawElevatedPanel(
                ctx = ctx,
                x = bx, y = by, w = boxW, h = boxH,
                rTL = 7f, rTR = 7f, rBR = 7f, rBL = 7f,
                densityBoost = 1.48f,
                shadowSpread = 14f,
                shadowAlpha = 0.56f,
                accentRimMix = 0.34f,
                rimAlpha = 0.62f,
            )
        } else {
            UiDraw.fillCssBox(
                ctx = ctx,
                x = bx, y = by, w = boxW, h = boxH,
                rTL = 0f, rTR = 0f, rBR = 3f, rBL = 3f,
                cTL = UiPalette.dropdownBg,
                borderColor = UiPalette.dropdownBorder,
                borderWidth = 1f,
                shadowColor = UiPalette.black(0.45f),
                shadowBlur = 6f,
            )
        }

        val innerY = by + padY
        val innerH = boxH - padY * 2f
        val lh = UiDraw.lineHeight(12f, UiFontWeight.MEDIUM)

        UiDraw.withCssScissor(ctx, bx + 1f, innerY, boxW - 2f, innerH) {
            popup.options.forEachIndexed { idx, opt ->
                val oy = innerY + idx * itemH - popup.scrollOffset
                if (oy + itemH < innerY || oy > innerY + innerH) return@forEachIndexed
                val hovered = mouseX in bx..(bx + boxW) && mouseY in max(innerY, oy)..min(innerY + innerH, oy + itemH)
                val active = opt == popup.currentValue
                if (glassMode && (active || hovered)) {
                    ClickGuiGlassSurface.drawAccentCapsule(
                        ctx = ctx,
                        x = bx + 4f, y = oy + 1.5f, w = boxW - 8f, h = itemH - 3f,
                        radius = 4.5f,
                        accentMix = if (active) 0.85f else 0.16f,
                        tintDensity = if (active) 0.46f else 0.28f,
                        layerOpacity = if (active) 0.90f else 0.65f,
                        rimAlpha = if (active) 0.72f else 0.38f,
                        shadowSpread = 0f,
                    )
                } else if (hovered) {
                    UiDraw.fillBox(ctx, bx + 3f, oy + 1f, boxW - 6f, itemH - 2f, UiPalette.white(0.06f), radius = 2f)
                }
                val color = when {
                    active -> if (glassMode) UiPalette.TEXT_COLOR else UiPalette.accent(1f)
                    hovered -> UiPalette.TEXT_COLOR
                    else -> UiPalette.TEXT_DIMMED_COLOR
                }
                val label = UiTextUtils.displayName(opt)
                val clipped = UiDraw.ellipsize(label, boxW - 16f, 12f, UiFontWeight.MEDIUM)
                val tw = UiDraw.measureText(clipped, 12f, UiFontWeight.MEDIUM)
                UiDraw.drawText(
                    ctx = ctx,
                    text = clipped,
                    x = bx + (boxW - tw) * 0.5f,
                    y = oy + (itemH - lh) * 0.5f,
                    fontSizePx = 12f,
                    argb = color,
                    weight = UiFontWeight.MEDIUM,
                    shadow = glassMode,
                )
            }
        }
    }

    fun handleDropdownMouseClick(
        mouseX: Float,
        mouseY: Float,
        button: Int,
        canvasWidth: Float,
        canvasHeight: Float,
    ): Boolean {
        val popup = activeDropdown ?: return false
        val itemH = 24f
        val padY = 6f
        val contentH = popup.options.size * itemH
        val maxH = min(240f, canvasHeight - 24f)
        val boxH = min(contentH, maxH) + padY * 2f
        val boxW = max(popup.width, 90f)
        val bx = popup.x.coerceIn(4f, max(4f, canvasWidth - boxW - 4f))
        val by = if (popup.y + boxH > canvasHeight - 4f && popup.y - boxH - 26f > 4f) {
            popup.y - boxH - 26f
        } else {
            popup.y.coerceAtMost(max(4f, canvasHeight - boxH - 4f))
        }

        if (mouseX in bx..(bx + boxW) && mouseY in by..(by + boxH)) {
            if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                val innerY = by + padY
                val innerH = boxH - padY * 2f
                if (mouseY in innerY..(innerY + innerH)) {
                    val idx = ((mouseY - innerY + popup.scrollOffset) / itemH).toInt()
                    if (idx in popup.options.indices) {
                        val chosen = popup.options[idx]
                        activeDropdown = null
                        popup.onSelect(chosen)
                    }
                }
            }
            return true
        }

        // Clicked outside -> close dropdown without swallowing click on trigger/other elements
        activeDropdown = null
        return false
    }

    fun handleDropdownScroll(
        mouseX: Float,
        mouseY: Float,
        scrollY: Double,
        canvasWidth: Float,
        canvasHeight: Float,
    ): Boolean {
        val popup = activeDropdown ?: return false
        val itemH = 24f
        val padY = 6f
        val contentH = popup.options.size * itemH
        val maxH = min(240f, canvasHeight - 24f)
        val boxH = min(contentH, maxH) + padY * 2f
        val boxW = max(popup.width, 90f)
        val bx = popup.x.coerceIn(4f, max(4f, canvasWidth - boxW - 4f))
        val by = if (popup.y + boxH > canvasHeight - 4f && popup.y - boxH - 26f > 4f) {
            popup.y - boxH - 26f
        } else {
            popup.y.coerceAtMost(max(4f, canvasHeight - boxH - 4f))
        }

        if (mouseX in bx..(bx + boxW) && mouseY in by..(by + boxH)) {
            popup.targetScrollOffset = (popup.targetScrollOffset - scrollY.toFloat() * 32f)
                .coerceIn(0f, max(0f, contentH - (boxH - padY * 2f)))
            return true
        }
        return false
    }

    fun handleKeyPressed(event: KeyEvent): Boolean {
        activeKeyCapture?.let { target ->
            return target.onKeyboardKeyCaptured(event)
        }
        activeTextInput?.let { editor ->
            if (editor.focused) {
                val handled = editor.keyPressed(event)
                if (!editor.focused) {
                    activeTextInput = null
                    syncNativeTextInputFocus()
                }
                return handled
            } else {
                activeTextInput = null
                syncNativeTextInputFocus()
            }
        }
        if (event.key() == InputConstants.KEY_ESCAPE && activeDropdown != null) {
            activeDropdown = null
            return true
        }
        return false
    }

    fun handleKeyReleased(event: KeyEvent): Boolean {
        activeKeyCapture?.let { target ->
            return target.onKeyboardKeyReleased(event)
        }
        return false
    }

    fun handleCharTyped(event: CharacterEvent): Boolean {
        activeTextInput?.let { editor ->
            if (editor.focused) {
                return editor.charTyped(event)
            }
        }
        return false
    }

    fun handlePreeditUpdated(event: PreeditEvent?): Boolean {
        activeTextInput?.let { editor ->
            if (editor.focused) {
                return editor.preeditUpdated(event)
            }
        }
        return false
    }

    fun handleMouseCapture(button: Int): Boolean {
        val target = activeKeyCapture ?: return false
        return target.onMouseButtonCaptured(button)
    }
}
