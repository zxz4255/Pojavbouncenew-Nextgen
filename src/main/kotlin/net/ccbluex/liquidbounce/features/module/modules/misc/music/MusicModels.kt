package net.ccbluex.liquidbounce.features.module.modules.misc.music

import java.nio.file.Path

/** A resolved, playable source. Search results never pretend that a metadata hit is playable. */
data class MusicTrack(
    val key: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0,
    val source: MusicSource,
    val remoteUrl: String = "",
    val localPath: Path? = null,
    val artworkUrl: String = "",
    val license: String = "",
    val licenseUrl: String = "",
    /** Platform specific opaque payload (Kugou hash / album id, NetEase id, ...). */
    val extra: String = "",
) {
    val subtitle: String get() = listOf(artist, album).filter { it.isNotBlank() }.joinToString(" · ")
    val playableHint: String
        get() = when (source) {
            MusicSource.LOCAL -> "本地文件"
            MusicSource.NETEASE -> if (license.isBlank()) "网易云" else "网易云 · $license"
            MusicSource.KUGOU -> if (license.isBlank()) "酷狗" else "酷狗 · $license"
        }
}

/**
 * Playable platforms.
 *
 * 开放音源 (Internet Archive) was intentionally removed: the catalogue had no reliable artwork,
 * lyrics or partial playback handling, and its anonymous licensing made the resolve step ambiguous.
 */
enum class MusicSource(val label: String, val short: String, val dot: Int) {
    LOCAL("本地", "LOCAL", 0xFFB2D7C7.toInt()),
    NETEASE("网易云", "163", 0xFFE05B5B.toInt()),
    KUGOU("酷狗", "KUGOU", 0xFF4FA8FF.toInt()),
    ;

    val supportsAccount: Boolean get() = this != LOCAL
    val supportsSearch: Boolean get() = true
}

enum class PlaybackStatus { IDLE, LOADING, PLAYING, PAUSED, ENDED, ERROR }

data class PlaybackSnapshot(
    val track: MusicTrack? = null,
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val error: String = "",
    val format: String = "",
    val durationMs: Long = 0,
)

data class LyricLine(val timeMs: Long, val text: String, val translation: String = "")

/** LRC is parsed independently of rendering and the audio backend. No wall-clock lyric timer. */
object LrcParser {
    private val stamp = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val metadata = Regex("^\\[(ar|ti|al|by|re|ve|length|offset|key|au):.*]$", RegexOption.IGNORE_CASE)

    fun parse(raw: String, translation: String = ""): List<LyricLine> {
        fun lines(input: String): List<Pair<Long, String>> {
            val offset = Regex("(?i)\\[offset:([+-]?\\d+)]").find(input)
                ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            return input.lineSequence().flatMap { line ->
                val clean = line.trim().removePrefix("\uFEFF")
                if (metadata.matches(clean)) return@flatMap emptySequence()
                val matches = stamp.findAll(clean).toList()
                val text = clean.replace(stamp, "").replace(Regex("<[^>]+>"), "").trim()
                // Empty timed lines are meaningful: they clear a previous verse in instrumental gaps.
                matches.asSequence().mapNotNull { match ->
                    val m = match.groupValues
                    val seconds = m[2].toIntOrNull() ?: return@mapNotNull null
                    if (seconds >= 60) return@mapNotNull null
                    val fraction = m[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0
                    (m[1].toLong() * 60000 + seconds * 1000 + fraction + offset).coerceAtLeast(0) to text
                }
            }.toList()
        }
        val translated = lines(translation).groupBy({ it.first }, { it.second })
        val times = translated.keys.sorted()
        return lines(raw).groupBy({ it.first }, { it.second }).toSortedMap().map { (time, text) ->
            val exact = translated[time]
            val near = if (exact == null) {
                val insertion = times.binarySearch(time).let { if (it >= 0) it else -it - 1 }
                listOfNotNull(times.getOrNull(insertion - 1), times.getOrNull(insertion))
                    .minByOrNull { kotlin.math.abs(it - time) }?.takeIf { kotlin.math.abs(it - time) <= 80 }
            } else null
            LyricLine(time, text.distinct().joinToString(" / "),
                (exact ?: translated[near]).orEmpty().distinct().joinToString(" "))
        }
    }

    fun indexAt(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty() || positionMs < lines.first().timeMs) return -1
        var low = 0
        var high = lines.lastIndex
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (lines[mid].timeMs <= positionMs) low = mid else high = mid - 1
        }
        return low
    }
}

data class LyricDocument(val trackKey: String, val lines: List<LyricLine>, val notice: String = "") {
    companion object {
        fun empty(key: String = "", notice: String = "暂无歌词") = LyricDocument(key, emptyList(), notice)
    }
}

/** Account state shared by the screen, the providers and the HTTP layer. */
data class MusicSession(
    val source: MusicSource,
    val userId: String,
    val nickname: String,
    val vip: Boolean = false,
    val detail: String = "",
)

/** Login methods offered for both cloud platforms. */
enum class LoginMethod(val label: String) {
    QR("二维码"),
    PHONE("手机号"),
    EMAIL("邮箱"),
    TOKEN("Cookie / Token"),
}

/** Phases of a QR hand-off, mapped from the platform specific status codes. */
enum class QrPhase(val label: String) {
    IDLE("准备中"),
    WAITING("等待扫码"),
    SCANNED("已扫码，请在手机上确认"),
    CONFIRMED("登录成功"),
    EXPIRED("二维码已过期"),
    FAILED("获取二维码失败"),
}

/** One QR hand-off: the opaque [key] the platform polls with, and the [payload] the app encodes. */
data class QrChallenge(val key: String, val payload: String)

/** Result of one QR poll, shared by both platforms. */
sealed interface QrPoll {
    data object Waiting : QrPoll
    data object Scanned : QrPoll
    data object Expired : QrPoll
    data class Success(val session: MusicSession) : QrPoll
    data class Failed(val message: String) : QrPoll
}
