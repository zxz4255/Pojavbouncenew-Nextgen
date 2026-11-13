package net.ccbluex.liquidbounce.features.module.modules.misc.music

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Conservative alternate-track matching. This is not an entitlement bypass. */
internal object MusicCrossSource {
    private fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun artists(value: String): Set<String> = value.split(Regex("[,，、;/＆&+／]"))
        .map(::normalized).filter { it.isNotEmpty() }.toSet()

    fun matches(original: Song, candidate: Song): Boolean {
        val title = normalized(original.name)
        if (title.isEmpty() || title != normalized(candidate.name)) return false
        val a = artists(original.artist)
        val b = artists(candidate.artist)
        if (a.isEmpty() || b.isEmpty() || a.intersect(b).isEmpty()) return false
        if (original.durationMs <= 0L || candidate.durationMs <= 0L) return false
        return abs(original.durationMs - candidate.durationMs) <= 5_000L
    }

    fun candidates(original: Song): List<Song> {
        if (original.name.isBlank() || original.artist.isBlank()) return emptyList()
        val query = "${original.name} ${original.artist}"
        val results = if (original.provider == MusicProvider.NETEASE) KugouClient.search(query, 30)
            else NeteaseClient.search(query, 30)
        return results.filter { matches(original, it) }.take(5)
    }
}
