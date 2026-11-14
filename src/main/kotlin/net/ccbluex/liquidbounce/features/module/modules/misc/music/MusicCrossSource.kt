package net.ccbluex.liquidbounce.features.module.modules.misc.music

import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

/** Conservative alternate-track matching. This is not an entitlement bypass. */
internal object MusicCrossSource {
    private const val MAX_CANDIDATES = 5
    private const val CACHE_LIMIT = 512
    private const val NEGATIVE_CACHE_MS = 10L * 60L * 1000L

    private data class TrackKey(val provider: MusicProvider, val id: Long)
    private data class RankedCandidate(
        val song: Song,
        val score: Double,
        val durationDeltaMs: Long,
        val searchOrder: Int,
    )

    /** Re-use a previously verified alternate without persisting short-lived stream URLs. */
    private val knownAlternates = ConcurrentHashMap<TrackKey, Song>()
    /** Repeated retries of a dead track must not re-run the same failed search every time. */
    private val unavailableUntil = ConcurrentHashMap<TrackKey, Long>()

    private fun key(song: Song) = TrackKey(song.provider, song.id)

    private fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun artists(value: String): Set<String> = value
        .split(Regex("(?i)\\s+(?:feat(?:uring)?\\.?|with)\\s+|[,，、;/＆&+／|·]+"))
        .map(::normalized)
        .filter { it.isNotEmpty() }
        .toSet()

    private fun allowedDurationDelta(original: Song): Long =
        max(10_000L, (original.durationMs.coerceAtLeast(0L) * 0.15).toLong())

    private fun matchScore(original: Song, candidate: Song, searchOrder: Int): RankedCandidate? {
        if (original.provider == candidate.provider) return null
        val title = normalized(original.name)
        if (title.isEmpty() || title != normalized(candidate.name)) return null

        val sourceArtists = artists(original.artist)
        val candidateArtists = artists(candidate.artist)
        if (sourceArtists.isEmpty() || candidateArtists.isEmpty()) return null
        val shared = sourceArtists.intersect(candidateArtists)
        if (shared.isEmpty()) return null
        val artistScore = shared.size.toDouble() / max(sourceArtists.size, candidateArtists.size)

        val durationKnown = original.durationMs > 0L && candidate.durationMs > 0L
        val delta = if (durationKnown) abs(original.durationMs - candidate.durationMs) else Long.MAX_VALUE
        val durationScore = if (durationKnown) {
            val allowed = allowedDurationDelta(original)
            if (delta > allowed) return null
            1.0 - delta.toDouble() / allowed.toDouble()
        } else {
            // Without a duration gate, require a strong artist overlap in addition to the exact title.
            if (artistScore < 0.5) return null
            0.85
        }

        // Exact normalized title dominates; artist agreement and duration closeness rank near-duplicates.
        val score = 0.70 + artistScore * 0.20 + durationScore * 0.10
        return RankedCandidate(candidate, score, if (durationKnown) delta else Long.MAX_VALUE, searchOrder)
    }

    fun matches(original: Song, candidate: Song): Boolean = matchScore(original, candidate, 0) != null

    /**
     * Find tracks from the other configured provider. The primary query includes the artist; if that
     * yields no high-confidence match, retry once with the title alone to tolerate provider indexing.
     */
    fun candidates(original: Song): List<Song> {
        if (original.name.isBlank() || original.artist.isBlank()) return emptyList()
        val trackKey = key(original)
        val cached = knownAlternates[trackKey]?.takeIf { matches(original, it) }
        val now = System.currentTimeMillis()
        val blockedUntil = unavailableUntil[trackKey]
        if (blockedUntil != null) {
            if (blockedUntil > now) return if (cached != null) listOf(cached) else emptyList()
            unavailableUntil.remove(trackKey, blockedUntil)
        }

        val otherProvider = if (original.provider == MusicProvider.NETEASE)
            MusicProvider.KUGOU else MusicProvider.NETEASE
        val queries = linkedSetOf("${original.name} ${original.artist}".trim(), original.name.trim())
        var ranked = emptyList<RankedCandidate>()
        for (query in queries) {
            val results = runCatching {
                if (otherProvider == MusicProvider.KUGOU) KugouClient.search(query, 30)
                else NeteaseClient.search(query, 30)
            }.getOrDefault(emptyList())
            ranked = results.mapIndexedNotNull { index, song -> matchScore(original, song, index) }
            if (ranked.isNotEmpty()) break
        }

        val sorted = ranked.sortedWith(
            compareByDescending<RankedCandidate> { it.score }
                .thenBy { it.durationDeltaMs }
                .thenBy { it.searchOrder },
        )
        val output = ArrayList<Song>(MAX_CANDIDATES)
        if (cached != null) output.add(cached)
        for (candidate in sorted) {
            if (output.size >= MAX_CANDIDATES) break
            if (output.none { it.sameTrack(candidate.song) }) output.add(candidate.song)
        }
        return output
    }

    fun rememberAlternate(original: Song, alternate: Song) {
        if (!matches(original, alternate)) return
        if (knownAlternates.size > CACHE_LIMIT) knownAlternates.clear()
        knownAlternates[key(original)] = alternate
        unavailableUntil.remove(key(original))
    }

    fun forgetAlternate(original: Song, alternate: Song) {
        knownAlternates.remove(key(original), alternate)
    }

    fun markUnavailable(original: Song) {
        if (unavailableUntil.size > CACHE_LIMIT) unavailableUntil.clear()
        unavailableUntil[key(original)] = System.currentTimeMillis() + NEGATIVE_CACHE_MS
    }

    fun clearUnavailable(original: Song) {
        unavailableUntil.remove(key(original))
    }
}
