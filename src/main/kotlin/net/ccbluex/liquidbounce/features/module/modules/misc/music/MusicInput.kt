package net.ccbluex.liquidbounce.features.module.modules.misc.music

import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.input.PreeditEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.util.Util

/**
 * Native EditBox input with smooth text presentation. Coordinates are REAL GUI coordinates,
 * never scaled design coordinates: SDL receives the correct caret rectangle on HiDPI / GUI scale.
 * The superclass still owns selection, grapheme deletion, clipboard, shortcuts and char commits.
 * Preedit is kept separate from the committed query, so pinyin cannot trigger a search.
 */
class MusicInput(label: String, private val hint: String) : EditBox(mc.font, 0, 0, 100, 28, Component.literal(label)) {
    private var preedit: PreeditEvent? = null
    private var first = 0
    var uiScale = 1f
    var secret = false
    private fun shown(text: String) = if (secret) "•".repeat(text.length) else text
    private val fontSize get() = 14f * uiScale
    private val padding get() = 10f * uiScale

    init {
        setBordered(false)
        setTextColor(0xFFFFFF)
        setTextColorUneditable(0xFFA6BAB9.toInt())
        setTextShadow(false)
        setMaxLength(120)
    }

    override fun preeditUpdated(event: PreeditEvent?): Boolean {
        preedit = event
        return true
    }

    val composing: Boolean get() = preedit != null
    fun resetComposition() { preedit = null }

    // EditBox still owns editing and IME, but its stock hit test uses the vanilla font.
    // Map clicks and drags using the same LB metrics and scroll start as the displayed text.
    private fun hitPosition(mouseX: Double): Int {
        val text = value
        val start = first.coerceIn(0, text.length)
        val offset = (mouseX - x - padding).toFloat()
        if (offset <= 0f) return start
        var pos = start
        var previousWidth = 0f
        while (pos < text.length) {
            val next = pos + Character.charCount(text.codePointAt(pos))
            val nextWidth = MusicUi.width(shown(text.substring(start, next)), fontSize)
            if (offset < (previousWidth + nextWidth) / 2f) return pos
            previousWidth = nextWidth
            pos = next
        }
        return text.length
    }

    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
        val pos = hitPosition(event.x)
        if (doubleClick) {
            moveCursorTo(pos, false)
            val start = getWordPosition(-1)
            val end = getWordPosition(1)
            moveCursorTo(start, false)
            moveCursorTo(end, true)
        } else {
            moveCursorTo(pos, event.hasShiftDown())
        }
    }

    override fun onDrag(event: MouseButtonEvent, dx: Double, dy: Double) {
        moveCursorTo(hitPosition(event.x), true)
    }

    override fun extractWidgetRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        if (!visible) return
        val text = value
        val size = fontSize
        val usable = width - padding * 2
        val cursor = cursorPosition.coerceIn(0, text.length)
        if (cursor < first) first = cursor
        if (first > text.length) first = text.length
        while (first < cursor && MusicUi.width(shown(text.substring(first, cursor)), size) > usable) {
            first += Character.charCount(text.codePointAt(first))
        }
        while (first > 0) {
            val previous = first - Character.charCount(text.codePointBefore(first))
            if (MusicUi.width(shown(text.substring(previous, cursor)), size) + 2 >= usable) break
            first = previous
        }
        var last = first
        while (last < text.length) {
            val next = last + Character.charCount(text.codePointAt(last))
            if (MusicUi.width(shown(text.substring(first, next)), size) > usable) break
            last = next
        }
        val left = x + padding
        val top = y + (height - size) / 2f
        val selection = highlighted
        if (selection.isNotEmpty() && isFocused) {
            val len = selection.length
            val a = if (cursor >= len && text.substring(cursor - len, cursor) == selection) cursor - len else cursor
            val b = a + len
            if (b > first && a < last) {
                val x0 = left + MusicUi.width(shown(text.substring(first, a.coerceAtLeast(first))), size)
                val x1 = left + MusicUi.width(shown(text.substring(first, b.coerceAtMost(last))), size)
                MusicUi.rect(ctx, x0, top - 2, x1 - x0, size + 4, 2f, MonetPalette.primaryContainer)
            }
        }
        if (text.isEmpty()) MusicUi.text(ctx, hint, left, top, size, MonetPalette.muted, maxWidth = usable)
        else MusicUi.text(ctx, shown(text.substring(first, last)), left, top, size, MonetPalette.text, maxWidth = usable)
        val caretX = left + MusicUi.width(shown(text.substring(first, cursor.coerceIn(first, last))), size)
        if (isFocused && Util.getMillis() / 500 % 2 == 0L) {
            ctx.drawRoundedRect(caretX, top, caretX + 1.3f * uiScale, top + size, 0.6f, MonetPalette.primary)
        }
        val edit = preedit
        if (isFocused && edit != null) {
            val popupWidth = (MusicUi.width(edit.fullText(), size) + 20 * uiScale).coerceAtMost(ctx.guiWidth().toFloat() - 8)
            val popupX = caretX.coerceIn(4f, (ctx.guiWidth() - popupWidth - 4).coerceAtLeast(4f))
            val popupH = size + 16 * uiScale
            val popupY = if (y + height + popupH + 4 < ctx.guiHeight()) y + height + 4f else y - popupH - 4f
            MusicUi.rect(ctx, popupX, popupY, popupWidth, popupH, 9 * uiScale, MonetPalette.text, MonetPalette.primary)
            MusicUi.text(ctx, shown(edit.fullText()), popupX + 10 * uiScale, popupY + 8 * uiScale, size, MonetPalette.background,
                maxWidth = popupWidth - 20 * uiScale)
            var focusedStart = 0
            for (i in 0 until edit.focusedBlock()) focusedStart += edit.blocks().getOrNull(i).orEmpty().length
            val focusedText = edit.blocks().getOrNull(edit.focusedBlock()).orEmpty()
            val u0 = popupX + 10 * uiScale + MusicUi.width(edit.fullText().substring(0, focusedStart), size)
            val u1 = u0 + MusicUi.width(focusedText, size)
            ctx.drawRoundedRect(u0, popupY + popupH - 7 * uiScale, u1, popupY + popupH - 6 * uiScale,
                0.5f, MonetPalette.onPrimary)
            mc.textInputManager().setTextInputArea(popupX.toInt(), popupY.toInt(), (popupX + popupWidth).toInt(), (popupY + popupH).toInt())
        } else if (isFocused) {
            mc.textInputManager().setTextInputArea(caretX.toInt(), top.toInt(), caretX.toInt() + 1, (top + size).toInt())
        }
    }
}
