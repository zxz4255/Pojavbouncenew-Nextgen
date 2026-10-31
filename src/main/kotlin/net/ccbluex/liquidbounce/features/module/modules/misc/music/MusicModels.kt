package net.ccbluex.liquidbounce.features.module.modules.misc.music

data class MusicTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    val coverUrl: String? = null,
    val source: Source = Source.NETEASE,
) {
    enum class Source { NETEASE, LOCAL }
}

data class LyricLine(val timeMs: Long, val text: String)

data class LyricSheet(val lines: List<LyricLine>) {
    fun lineAt(positionMs: Long): LyricLine? {
        if (lines.isEmpty()) return null
        var best: LyricLine? = lines.first()
        for (line in lines) {
            if (line.timeMs <= positionMs) best = line else break
        }
        return best
    }

    fun indexAt(positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var idx = 0
        for (i in lines.indices) {
            if (lines[i].timeMs <= positionMs) idx = i else break
        }
        return idx
    }
}
