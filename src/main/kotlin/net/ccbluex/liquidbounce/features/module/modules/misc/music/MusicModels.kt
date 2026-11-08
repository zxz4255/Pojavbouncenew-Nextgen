package net.ccbluex.liquidbounce.features.module.modules.misc.music

import kotlin.math.max

/**
 * The data the NetEase web API hands the player.
 *
 * <p>Ported 1:1 from NeoRaven's `music.Song`, `Playlist`, `Profile`, `LyricWord` and `LyricLine`,
 * including the `?param=NyN` cover-resize convention and the word-timing maths the karaoke sweep
 * runs on.</p>
 */

/** One track as the NetEase web API describes it. */
class Song(
    val id: Long,
    name: String?,
    artist: String?,
    album: String?,
    coverUrl: String?,
    val durationMs: Long,
) {
    val name: String = name ?: ""
    val artist: String = artist ?: ""
    val album: String = album ?: ""
    val coverUrl: String = coverUrl ?: ""

    /** Cover URL for a square thumbnail of `size` pixels. */
    fun cover(size: Int): String = if (coverUrl.isEmpty()) "" else "$coverUrl?param=${size}y$size"

    fun label(): String = if (artist.isEmpty()) name else name

    override fun toString(): String = name
}

/** A NetEase playlist row (recommendations, the user's own playlists, search results). */
class Playlist(
    val id: Long,
    name: String?,
    val trackCount: Int,
    coverUrl: String?,
) {
    val name: String = name ?: ""
    val coverUrl: String = coverUrl ?: ""

    fun cover(size: Int): String = if (coverUrl.isEmpty()) "" else "$coverUrl?param=${size}y$size"
}

/** The signed-in NetEase account, as shown on the account page. */
class Profile(
    val userId: Long,
    nickname: String?,
    avatarUrl: String?,
    val vipType: Int,
) {
    val nickname: String = nickname ?: ""
    val avatarUrl: String = avatarUrl ?: ""

    fun avatar(size: Int): String = if (avatarUrl.isEmpty()) "" else "$avatarUrl?param=${size}y$size"
}

/** One sung fragment inside a lyric line (a Chinese character or a Latin word). */
class LyricWord(val startMs: Long, endMsRaw: Long, textRaw: String?) {
    val endMs: Long = max(endMsRaw, startMs)
    val text: String = textRaw ?: ""

    /** Filled in by the view once the font is known; kept here so layout is measured once. */
    var x: Float = 0.0F
    var width: Float = 0.0F

    fun duration(): Long = max(1L, endMs - startMs)

    /** 0..1 sung fraction of this fragment at `positionMs`. */
    fun progress(positionMs: Long): Float {
        if (positionMs <= startMs) return 0.0F
        if (positionMs >= endMs) return 1.0F
        return (positionMs - startMs).toFloat() / duration().toFloat()
    }
}

/** One displayed lyric line: text, optional translation and the word timings used for the sweep. */
class LyricLine(
    val timeMs: Long,
    endMsRaw: Long,
    textRaw: String?,
    wordsRaw: List<LyricWord>?,
) {
    var endMs: Long = max(endMsRaw, timeMs + 400L)
    val text: String = textRaw ?: ""
    var translation: String = ""
    var romanisation: String = ""
    val words: MutableList<LyricWord> = (wordsRaw ?: ArrayList()).toMutableList()

    /** 0..1 progress through the whole line, used for the line-level glow. */
    fun progress(positionMs: Long): Float {
        val duration = max(1L, endMs - timeMs)
        if (positionMs <= timeMs) return 0.0F
        if (positionMs >= endMs) return 1.0F
        return (positionMs - timeMs).toFloat() / duration.toFloat()
    }

    /** Index of the fragment being sung, or -1 before the line starts. */
    fun activeWord(positionMs: Long): Int {
        for (i in words.indices) {
            val word = words[i]
            if (positionMs < word.endMs) {
                return if (positionMs < word.startMs) -1 else i
            }
        }
        return if (words.isEmpty()) -1 else words.size - 1
    }

    fun hasWords(): Boolean = words.isNotEmpty()

    companion object {
        fun plain(timeMs: Long, text: String?): LyricLine =
            LyricLine(timeMs, timeMs + 400L, text, ArrayList())
    }
}
