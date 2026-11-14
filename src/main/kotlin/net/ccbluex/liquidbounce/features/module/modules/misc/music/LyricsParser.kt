package net.ccbluex.liquidbounce.features.module.modules.misc.music

import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min

/**
 * Turns the NetEase lyric payload into timed lines.
 *
 * <p>Ported 1:1 from NeoRaven's `music.LyricsParser`. Two formats matter: `yrc` carries per-fragment
 * timing (NetEase's karaoke format, one timing pair per Chinese character or Latin word), and plain
 * `lrc` only marks the start of each line. When only line timing exists the parser still produces
 * fragments — the line's duration is split over the characters (CJK) or words (Latin) by width, which
 * is what makes the sweep effect work for older tracks instead of snapping whole lines at once.</p>
 */
object LyricsParser {

    private val YRC_LINE = Pattern.compile("^\\[(\\d+),(\\d+)(?:,-?\\d+)?\\]")
    private val YRC_WORD = Pattern.compile("\\((\\d+),(\\d+)(?:,-?\\d+)?\\)([^(]*)")
    private val LRC_TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val OFFSET = Pattern.compile("\\[offset:\\s*([-+]?\\d+)\\s*]", Pattern.CASE_INSENSITIVE)

    /** Parses whichever source is available, preferring the word-level one. */
    fun parse(yrc: String?, lrc: String?, translation: String?, romanisation: String?): List<LyricLine> {
        var lines = parseYrc(yrc)
        if (lines.isEmpty()) {
            lines = parseLrc(lrc, offsetOf(lrc))
        }
        if (lines.isEmpty()) return emptyList()
        mergeAux(lines, parseLrc(translation, 0L), translation = true)
        mergeAux(lines, parseLrc(romanisation, 0L), translation = false)
        finish(lines)
        return lines
    }

    // ------------------------------------------------------------------ yrc

    private fun parseYrc(yrc: String?): MutableList<LyricLine> {
        val lines = ArrayList<LyricLine>()
        if (yrc.isNullOrEmpty()) return lines
        for (raw in yrc.split("\n")) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val header = YRC_LINE.matcher(line)
            if (!header.find()) continue
            val start: Long
            val duration: Long
            try {
                start = header.group(1).toLong()
                duration = header.group(2).toLong()
            } catch (t: NumberFormatException) {
                continue
            }
            val words = ArrayList<LyricWord>()
            val text = StringBuilder()
            val word = YRC_WORD.matcher(line.substring(header.end()))
            while (word.find()) {
                val part = word.group(3)
                if (part.isEmpty()) continue
                val wordStart: Long
                val wordDuration: Long
                try {
                    wordStart = word.group(1).toLong()
                    wordDuration = word.group(2).toLong()
                } catch (t: NumberFormatException) {
                    continue
                }
                words.add(LyricWord(wordStart, wordStart + max(1L, wordDuration), part))
                text.append(part)
            }
            var plain = text.toString().trim()
            if (plain.isEmpty() && words.isEmpty()) continue
            if (plain.isEmpty()) plain = joinWords(words)
            lines.add(LyricLine(start, if (duration > 0L) start + duration else start + 400L, plain, words))
        }
        return lines
    }

    // ------------------------------------------------------------------ lrc

    private fun offsetOf(lrc: String?): Long {
        if (lrc == null) return 0L
        val matcher = OFFSET.matcher(lrc)
        if (matcher.find()) {
            try {
                return matcher.group(1)!!.toLong()
            } catch (ignored: NumberFormatException) {
            }
        }
        return 0L
    }

    private fun parseLrc(lrc: String?, offset: Long): MutableList<LyricLine> {
        val lines = ArrayList<LyricLine>()
        if (lrc.isNullOrEmpty()) return lines
        for (raw in lrc.split("\n")) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val times = LRC_TIME.matcher(line)
            val stamps = ArrayList<Long>()
            var end = 0
            while (times.find()) {
                val minutes: Long
                val seconds: Long
                val fraction = times.group(3)
                try {
                    minutes = times.group(1)!!.toLong()
                    seconds = times.group(2)!!.toLong()
                } catch (t: NumberFormatException) {
                    break
                }
                var millis = 0L
                if (fraction != null) {
                    millis = fraction.toLong() * (if (fraction.length == 1) 100L else if (fraction.length == 2) 10L else 1L)
                }
                stamps.add(minutes * 60000L + seconds * 1000L + millis + offset)
                end = times.end()
            }
            if (stamps.isEmpty()) continue
            val text = line.substring(end).trim()
            if (text.isEmpty()) continue
            for (stamp in stamps) {
                lines.add(LyricLine.plain(stamp, text))
            }
        }
        lines.sortBy { it.timeMs }
        return lines
    }

    // ------------------------------------------------------- post-processing

    private fun mergeAux(lines: MutableList<LyricLine>, auxiliary: List<LyricLine>, translation: Boolean) {
        if (lines.isEmpty() || auxiliary.isEmpty()) return
        val byTime = HashMap<Long, String>()
        for (line in auxiliary) {
            if (line.text.isNotEmpty()) byTime[line.timeMs] = line.text
        }
        for (line in lines) {
            var text = byTime[line.timeMs]
            if (text == null && byTime.size == 1) text = byTime.values.first()
            if (text.isNullOrEmpty() || text == line.text) continue
            if (translation) line.translation = text else line.romanisation = text
        }
    }

    /**
     * Fills in the line end times and synthesises fragment timings for lines that only have a start
     * stamp.
     */
    private fun finish(lines: List<LyricLine>) {
        for (i in lines.indices) {
            val line = lines[i]
            val next = if (i + 1 < lines.size) lines[i + 1].timeMs else 0L
            if (next > line.timeMs) {
                line.endMs = min(next, line.timeMs + 12000L)
            }
            if (!line.hasWords()) {
                line.words.addAll(distribute(line))
            }
        }
    }

    /** Splits a line's duration across its characters/words so a plain LRC still sweeps. */
    private fun distribute(line: LyricLine): List<LyricWord> {
        val tokens = tokenise(line.text)
        val words = ArrayList<LyricWord>()
        if (tokens.isEmpty()) return words
        var totalWeight = 0
        val weights = IntArray(tokens.size)
        for (i in tokens.indices) {
            val token = tokens[i]
            var weight = 1
            for (c in token) {
                if (isWide(c)) {
                    weight = 2
                    break
                }
            }
            weights[i] = weight
            totalWeight += weight
        }
        val duration = max(400L, line.endMs - line.timeMs)
        var cursor = line.timeMs
        for (i in tokens.indices) {
            val span = duration * weights[i] / max(1, totalWeight)
            val end = if (i == tokens.size - 1) line.timeMs + duration else cursor + span
            words.add(LyricWord(cursor, end, tokens[i]))
            cursor = end
        }
        return words
    }

    /** Splits CJK text per character and Latin text per word, keeping the spaces attached. */
    private fun tokenise(text: String?): List<String> {
        val tokens = ArrayList<String>()
        if (text.isNullOrEmpty()) return tokens
        val current = StringBuilder()
        for (c in text) {
            if (isWide(c)) {
                if (current.isNotEmpty()) {
                    tokens.add(current.toString())
                    current.setLength(0)
                }
                tokens.add(c.toString())
            } else if (c == ' ') {
                current.append(c)
                tokens.add(current.toString())
                current.setLength(0)
            } else {
                current.append(c)
            }
        }
        if (current.isNotEmpty()) tokens.add(current.toString())
        return tokens.filter { it.isNotEmpty() }
    }

    private fun isWide(c: Char): Boolean {
        val code = c.code
        return (code in 0x2E80..0x9FFF) || (code in 0xAC00..0xD7AF) ||
            (code in 0xF900..0xFAFF) || (code in 0xFF00..0xFF60)
    }

    private fun joinWords(words: List<LyricWord>): String {
        val text = StringBuilder()
        for (word in words) text.append(word.text)
        return text.toString().trim()
    }
}
