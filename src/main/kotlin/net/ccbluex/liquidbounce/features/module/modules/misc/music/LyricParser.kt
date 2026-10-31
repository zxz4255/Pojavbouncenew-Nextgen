package net.ccbluex.liquidbounce.features.module.modules.misc.music

object LyricParser {
    private val LINE = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?](.*)""")

    fun parse(raw: String?): LyricSheet {
        if (raw.isNullOrBlank()) return LyricSheet(emptyList())
        val out = ArrayList<LyricLine>()
        for (line in raw.lineSequence()) {
            val m = LINE.find(line.trim()) ?: continue
            val min = m.groupValues[1].toLongOrNull() ?: continue
            val sec = m.groupValues[2].toLongOrNull() ?: continue
            val frac = m.groupValues[3]
            val ms = when {
                frac.isEmpty() -> 0L
                frac.length == 1 -> frac.toLong() * 100L
                frac.length == 2 -> frac.toLong() * 10L
                else -> frac.take(3).toLong()
            }
            val text = m.groupValues[4].trim()
            if (text.isEmpty()) continue
            out += LyricLine(min * 60_000L + sec * 1000L + ms, text)
        }
        out.sortBy { it.timeMs }
        return LyricSheet(out)
    }
}
