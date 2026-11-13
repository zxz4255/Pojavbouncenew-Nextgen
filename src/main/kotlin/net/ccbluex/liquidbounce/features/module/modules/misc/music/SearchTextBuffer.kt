package net.ccbluex.liquidbounce.features.module.modules.misc.music

/** Committed search text and IME composition are deliberately separate.
 * SDL TEXT_EDITING replaces the composition; only TEXT_INPUT commits characters.
 */
internal class SearchTextBuffer(private val maxCodePoints: Int = 48) {
    var text: String = ""
        private set
    var composition: String = ""
        private set
    /** UTF-16 offset as supplied by Minecraft's PreeditEvent. */
    var compositionCaret: Int = 0
        private set

    fun updateComposition(value: String?, caret: Int = 0) {
        composition = value ?: ""
        compositionCaret = caret.coerceIn(0, composition.length)
        if (compositionCaret > 0 && compositionCaret < composition.length &&
            Character.isHighSurrogate(composition[compositionCaret - 1]) &&
            Character.isLowSurrogate(composition[compositionCaret])) {
            compositionCaret--
        }
    }

    fun appendCodePoint(codePoint: Int): Boolean {
        if (!Character.isValidCodePoint(codePoint) || codePoint in 0xD800..0xDFFF ||
            Character.isISOControl(codePoint) || codePoint == 0xA7 ||
            text.codePointCount(0, text.length) >= maxCodePoints) return false
        text += String(Character.toChars(codePoint))
        return true
    }

    fun paste(value: String): Boolean {
        var changed = false
        value.codePoints().forEach { cp ->
            val mapped = if (cp == '\n'.code || cp == '\r'.code) ' '.code else cp
            if (appendCodePoint(mapped)) changed = true
        }
        return changed
    }

    fun replace(value: String) {
        text = ""
        updateComposition(null)
        paste(value)
    }

    fun backspace(): Boolean {
        if (text.isEmpty()) return false
        val start = text.offsetByCodePoints(text.length, -1)
        text = text.substring(0, start)
        return true
    }
}
