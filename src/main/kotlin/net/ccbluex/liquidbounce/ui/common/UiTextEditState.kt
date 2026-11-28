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
package net.ccbluex.liquidbounce.ui.common

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.PreeditEvent
import kotlin.math.max
import kotlin.math.min

/**
 * Full-featured single-line text editor controller for Search, TextSetting, Color hex input,
 * slider inline numeric inputs, vector inputs, and searchable lists.
 *
 * Uses Minecraft 26.3 SDL3 [InputConstants] key codes and [KeyEvent] / [CharacterEvent].
 */
class UiTextEditState(
    initialText: String = "",
    private val numericOnly: Boolean = false,
    private val allowFloat: Boolean = true,
    private val maxLength: Int = 512,
    var onChanged: ((String) -> Unit)? = null,
    var onCommit: ((String) -> Unit)? = null,
    var onCancel: (() -> Unit)? = null,
) {
    var text: String = initialText
        private set

    var cursor: Int = initialText.length
        private set

    var selectionAnchor: Int = initialText.length
        private set

    var focused: Boolean = false
        private set

    var preeditText: String = ""
        private set

    var scrollOffsetPx: Float = 0f

    private var lastBlinkResetMs: Long = System.currentTimeMillis()
    private var draggingSelection: Boolean = false

    val hasSelection: Boolean
        get() = cursor != selectionAnchor

    val selectionStart: Int
        get() = min(cursor, selectionAnchor)

    val selectionEnd: Int
        get() = max(cursor, selectionAnchor)

    val selectedText: String
        get() = if (hasSelection) text.substring(selectionStart, selectionEnd) else ""

    val isCaretVisible: Boolean
        get() = focused && (((System.currentTimeMillis() - lastBlinkResetMs) / 500L) % 2L == 0L)

    fun setTextExternal(newText: String) {
        if (!focused && text != newText) {
            text = newText
            cursor = newText.length
            selectionAnchor = cursor
        }
    }

    fun forceText(newText: String) {
        text = newText
        preeditText = ""
        cursor = cursor.coerceIn(0, newText.length)
        selectionAnchor = cursor
    }

    fun focus(selectAll: Boolean = false) {
        focused = true
        lastBlinkResetMs = System.currentTimeMillis()
        if (selectAll) {
            selectionAnchor = 0
            cursor = text.length
        } else {
            cursor = cursor.coerceIn(0, text.length)
            selectionAnchor = cursor
        }
    }

    fun unfocus(commit: Boolean = true) {
        if (!focused) return
        focused = false
        preeditText = ""
        draggingSelection = false
        if (commit) {
            onCommit?.invoke(text)
        }
    }

    fun preeditUpdated(event: PreeditEvent?): Boolean {
        if (!focused) return false
        preeditText = event?.fullText().orEmpty()
        lastBlinkResetMs = System.currentTimeMillis()
        return true
    }

    fun onMouseDown(
        localX: Float,
        fontSizePx: Float,
        weight: UiFontWeight = UiFontWeight.REGULAR,
        shiftHeld: Boolean = false,
    ) {
        focused = true
        lastBlinkResetMs = System.currentTimeMillis()
        val idx = hitTestIndex(localX + scrollOffsetPx, fontSizePx, weight)
        cursor = idx
        if (!shiftHeld) {
            selectionAnchor = idx
        }
        draggingSelection = true
    }

    fun onMouseDrag(localX: Float, fontSizePx: Float, weight: UiFontWeight = UiFontWeight.REGULAR) {
        if (!focused || !draggingSelection) return
        lastBlinkResetMs = System.currentTimeMillis()
        cursor = hitTestIndex(localX + scrollOffsetPx, fontSizePx, weight)
    }

    fun onMouseUp() {
        draggingSelection = false
    }

    private fun hitTestIndex(targetX: Float, fontSizePx: Float, weight: UiFontWeight): Int {
        if (targetX <= 0f || text.isEmpty()) return 0
        var prevW = 0f
        for (i in 1..text.length) {
            val w = UiDraw.measureText(text.substring(0, i), fontSizePx, weight)
            if (targetX < (prevW + w) * 0.5f) {
                return i - 1
            }
            prevW = w
        }
        return text.length
    }

    fun ensureCaretVisible(viewportWidthPx: Float, fontSizePx: Float, weight: UiFontWeight = UiFontWeight.REGULAR) {
        if (viewportWidthPx <= 4f) return
        val caretX = UiDraw.measureText(text.substring(0, cursor.coerceIn(0, text.length)), fontSizePx, weight)
        if (caretX - scrollOffsetPx > viewportWidthPx - 4f) {
            scrollOffsetPx = caretX - (viewportWidthPx - 4f)
        } else if (caretX - scrollOffsetPx < 0f) {
            scrollOffsetPx = caretX
        }
        val totalW = UiDraw.measureText(text, fontSizePx, weight)
        if (totalW <= viewportWidthPx - 4f) {
            scrollOffsetPx = 0f
        } else {
            scrollOffsetPx = scrollOffsetPx.coerceIn(0f, max(0f, totalW - viewportWidthPx + 6f))
        }
    }

    fun charTyped(event: CharacterEvent): Boolean {
        if (!focused) return false
        val str = event.codepointAsString()
        if (str.isEmpty()) return false
        val filtered = buildString {
            for (ch in str) {
                if (ch.code < 32 || ch.code == 127) continue
                if (numericOnly) {
                    val ok = ch.isDigit() || ch == '-' || ch == '+' || (allowFloat && (ch == '.' || ch == 'e' || ch == 'E'))
                    if (!ok) continue
                }
                append(ch)
            }
        }
        if (filtered.isEmpty()) return false
        insertText(filtered)
        return true
    }

    fun keyPressed(event: KeyEvent): Boolean {
        if (!focused) return false
        lastBlinkResetMs = System.currentTimeMillis()
        val shift = event.hasShiftDown()
        val ctrl = event.hasControlDownWithQuirk()

        if (event.isSelectAll()) {
            selectionAnchor = 0
            cursor = text.length
            return true
        }
        if (event.isCopy()) {
            if (hasSelection) {
                mc.keyboardHandler.clipboard = selectedText
            }
            return true
        }
        if (event.isCut()) {
            if (hasSelection) {
                mc.keyboardHandler.clipboard = selectedText
                deleteSelection()
            }
            return true
        }
        if (event.isPaste()) {
            val clip = mc.keyboardHandler.clipboard
            if (clip.isNotEmpty()) {
                val clean = clip.replace("\r", "").replace("\n", " ")
                val toInsert = if (numericOnly) {
                    clean.filter { it.isDigit() || it == '-' || it == '+' || (allowFloat && it == '.') }
                } else {
                    clean
                }
                if (toInsert.isNotEmpty()) {
                    insertText(toInsert)
                }
            }
            return true
        }

        when (event.key()) {
            InputConstants.KEY_ESCAPE -> {
                focused = false
                preeditText = ""
                draggingSelection = false
                onCancel?.invoke()
                return true
            }
            InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                onCommit?.invoke(text)
                return true
            }
            InputConstants.KEY_BACKSPACE -> {
                if (hasSelection) {
                    deleteSelection()
                } else if (cursor > 0) {
                    val target = if (ctrl) findPrevWordBoundary(cursor) else cursor - 1
                    text = text.removeRange(target, cursor)
                    cursor = target
                    selectionAnchor = cursor
                    onChanged?.invoke(text)
                }
                return true
            }
            InputConstants.KEY_DELETE -> {
                if (hasSelection) {
                    deleteSelection()
                } else if (cursor < text.length) {
                    val target = if (ctrl) findNextWordBoundary(cursor) else cursor + 1
                    text = text.removeRange(cursor, target)
                    selectionAnchor = cursor
                    onChanged?.invoke(text)
                }
                return true
            }
            InputConstants.KEY_LEFT -> {
                if (hasSelection && !shift) {
                    cursor = selectionStart
                    selectionAnchor = cursor
                } else {
                    cursor = if (ctrl) findPrevWordBoundary(cursor) else (cursor - 1).coerceAtLeast(0)
                    if (!shift) selectionAnchor = cursor
                }
                return true
            }
            InputConstants.KEY_RIGHT -> {
                if (hasSelection && !shift) {
                    cursor = selectionEnd
                    selectionAnchor = cursor
                } else {
                    cursor = if (ctrl) findNextWordBoundary(cursor) else (cursor + 1).coerceAtMost(text.length)
                    if (!shift) selectionAnchor = cursor
                }
                return true
            }
            InputConstants.KEY_HOME -> {
                cursor = 0
                if (!shift) selectionAnchor = 0
                return true
            }
            InputConstants.KEY_END -> {
                cursor = text.length
                if (!shift) selectionAnchor = cursor
                return true
            }
        }
        return true
    }

    private fun insertText(str: String) {
        preeditText = ""
        val start = selectionStart
        val end = selectionEnd
        val remaining = (maxLength - (text.length - (end - start))).coerceAtLeast(0)
        if (remaining <= 0) return
        val clipped = str.take(remaining)
        text = text.substring(0, start) + clipped + text.substring(end)
        cursor = start + clipped.length
        selectionAnchor = cursor
        lastBlinkResetMs = System.currentTimeMillis()
        onChanged?.invoke(text)
    }

    private fun deleteSelection() {
        val start = selectionStart
        val end = selectionEnd
        if (start == end) return
        text = text.removeRange(start, end)
        cursor = start
        selectionAnchor = start
        onChanged?.invoke(text)
    }

    private fun findPrevWordBoundary(from: Int): Int {
        var i = from.coerceIn(0, text.length)
        while (i > 0 && text[i - 1].isWhitespace()) i--
        while (i > 0 && !text[i - 1].isWhitespace()) i--
        return i
    }

    private fun findNextWordBoundary(from: Int): Int {
        var i = from.coerceIn(0, text.length)
        while (i < text.length && !text[i].isWhitespace()) i++
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }
}
