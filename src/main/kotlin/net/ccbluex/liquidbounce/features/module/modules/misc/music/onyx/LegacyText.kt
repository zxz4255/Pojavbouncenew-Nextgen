package net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx

import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Typeface

/**
 * Minecraft legacy (`§`) text, split into coloured runs for the Material font.
 *
 * <p>Ported from NeoRaven's `gui.onyx.LegacyText`. The Onyx faces have no colour support of their
 * own, and stripping the codes would flatten a scoreboard into one grey wall, so `§x` is parsed into
 * runs and each run is drawn with its own colour. `§m` / `§n` are honoured as well (separators drawn
 * as struck-through spaces); bold/italic/obfuscated are ignored, and codes are never measured.</p>
 */
object LegacyText {

    private const val ELLIPSIS = "..."

    /** One styled run of a legacy string. */
    class Run(
        val text: String,
        val color: Int,
        val strikethrough: Boolean,
        val underline: Boolean,
    )

    /** Splits `text` into runs; `baseColor` is used until the first colour code. */
    fun parse(text: String?, baseColor: Int): List<Run> {
        val runs = ArrayList<Run>()
        if (text.isNullOrEmpty()) return runs
        val current = StringBuilder()
        var color = baseColor
        var strikethrough = false
        var underline = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\u00a7') {
                // A trailing lone code indicator would otherwise be rasterised as a glyph.
                if (i + 1 >= text.length) break
                i++
                val code = text[i].lowercaseChar()
                val parsed = colorOf(code)
                if (parsed != 0) {
                    flush(runs, current, color, strikethrough, underline)
                    color = parsed
                    // A colour code resets every formatting flag, exactly like the vanilla font.
                    strikethrough = false
                    underline = false
                } else if (code == 'm') {
                    flush(runs, current, color, strikethrough, underline)
                    strikethrough = true
                } else if (code == 'n') {
                    flush(runs, current, color, strikethrough, underline)
                    underline = true
                } else if (code == 'r') {
                    flush(runs, current, color, strikethrough, underline)
                    color = baseColor
                    strikethrough = false
                    underline = false
                }
                i++
                continue
            }
            current.append(c)
            i++
        }
        flush(runs, current, color, strikethrough, underline)
        return runs
    }

    private fun flush(runs: MutableList<Run>, current: StringBuilder, color: Int, strikethrough: Boolean, underline: Boolean) {
        if (current.isNotEmpty()) {
            runs.add(Run(current.toString(), color, strikethrough, underline))
            current.setLength(0)
        }
    }

    /** Width of the visible text, ignoring the formatting codes. */
    fun width(style: Typeface.Style, text: String?): Float {
        if (text.isNullOrEmpty()) return 0.0F
        var width = 0.0F
        var start = 0
        var i = 0
        while (i < text.length) {
            if (text[i] == '\u00a7' && i + 1 < text.length) {
                if (i > start) width += style.width(text.substring(start, i))
                i++
                start = i + 1
            }
            i++
        }
        if (start < text.length) width += style.width(text.substring(start))
        return width
    }

    /** Shortens the visible text to `maxWidth`, keeping the colour codes before the cut. */
    fun truncate(style: Typeface.Style, text: String?, maxWidth: Float): String? {
        if (text.isNullOrEmpty() || width(style, text) <= maxWidth) return text
        val limit = maxWidth - style.width(ELLIPSIS)
        if (limit <= 0.0F) return ELLIPSIS
        val out = StringBuilder(text.length)
        var used = 0.0F
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\u00a7' && i + 1 < text.length) {
                out.append(c).append(text[i + 1])
                i += 2
                continue
            }
            val cell = style.width(c.toString())
            if (used + cell > limit) break
            out.append(c)
            used += cell
            i++
        }
        return out.append(ELLIPSIS).toString()
    }

    private fun colorOf(code: Char): Int = when (code) {
        '0' -> 0xFF000000.toInt()
        '1' -> 0xFF0000AA.toInt()
        '2' -> 0xFF00AA00.toInt()
        '3' -> 0xFF00AAAA.toInt()
        '4' -> 0xFFAA0000.toInt()
        '5' -> 0xFFAA00AA.toInt()
        '6' -> 0xFFFFAA00.toInt()
        '7' -> 0xFFAAAAAA.toInt()
        '8' -> 0xFF555555.toInt()
        '9' -> 0xFF5555FF.toInt()
        'a' -> 0xFF55FF55.toInt()
        'b' -> 0xFF55FFFF.toInt()
        'c' -> 0xFFFF5555.toInt()
        'd' -> 0xFFFF55FF.toInt()
        'e' -> 0xFFFFFF55.toInt()
        'f' -> 0xFFFFFFFF.toInt()
        else -> 0
    }
}
