/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.features.module.modules.render.cs2mvp

import java.util.UUID

/** No game dependencies: evidence is collected by the client-thread adapter, not inferred from removals. */
internal data class MvpLife(val uuid: UUID, val entityId: Int)

internal class Cs2MvpKillTracker {
    private data class Evidence(var attackAt: Long? = null, var damageAt: Long? = null,
                                var otherSource: Boolean = false, var touchedAt: Long)
    private val evidence = linkedMapOf<MvpLife, Evidence>()
    private val consumed = linkedMapOf<MvpLife, Long>()
    val size: Int get() = evidence.size

    fun attack(life: MvpLife, now: Long) {
        prune(now)
        if (life in consumed) return
        val entry = evidence.getOrPut(life) { Evidence(touchedAt = now) }
        entry.attackAt = now
        entry.touchedAt = now
        bound()
    }

    fun damage(life: MvpLife, byLocalPlayer: Boolean, now: Long) {
        prune(now)
        if (life in consumed) return
        // A known competing/environmental source invalidates a recent-hit fallback as well.
        if (!byLocalPlayer && life !in evidence) return
        val entry = evidence.getOrPut(life) { Evidence(touchedAt = now) }
        entry.damageAt = if (byLocalPlayer) now else null
        entry.otherSource = !byLocalPlayer
        entry.touchedAt = now
        bound()
    }

    fun death(life: MvpLife, now: Long, windowMs: Long, allowRecentHit: Boolean): Boolean {
        prune(now)
        if (life in consumed) return false
        val entry = evidence.remove(life)
        consumed[life] = now
        bound()
        if (entry == null || entry.otherSource) return false
        fun recent(stamp: Long?) = stamp != null && now >= stamp && now - stamp <= windowMs
        return recent(entry.damageAt) || (allowRecentHit && recent(entry.attackAt))
    }

    fun contains(life: MvpLife): Boolean = life in evidence
    fun prune(now: Long) {
        evidence.entries.removeIf { now < it.value.touchedAt || now - it.value.touchedAt > 10_000L }
        consumed.entries.removeIf { now < it.value || now - it.value > 30_000L }
    }
    private fun bound() {
        while (evidence.size > 128) evidence.remove(evidence.keys.first())
        while (consumed.size > 128) consumed.remove(consumed.keys.first())
    }
    fun reset() { evidence.clear(); consumed.clear() }
}

internal data class Cs2MvpFrame(val name: String, val unfold: Float, val alpha: Float, val whiteFlash: Float)

internal class Cs2MvpTimeline {
    private var name: String? = null
    private var startedAt = 0L
    fun show(winner: String, now: Long) { name = winner; startedAt = now }
    fun reset() { name = null; startedAt = 0L }
    fun sample(now: Long, holdMs: Long): Cs2MvpFrame? {
        val winner = name ?: return null
        val elapsed = now - startedAt
        if (elapsed < 0 || elapsed >= holdMs + 300L) { reset(); return null }
        val reveal = cs2Ease((elapsed / 250f).coerceIn(0f, 1f))
        val alpha = if (elapsed <= holdMs) 1f else 1f - ((elapsed - holdMs) / 300f).coerceIn(0f, 1f)
        return Cs2MvpFrame(winner, reveal, alpha, 1f - reveal)
    }
}

/** CSS ease-in-out cubic-bezier(.42,0,.58,1), solving x(t) rather than treating time as the curve parameter. */
internal fun cs2Ease(progress: Float): Float {
    val x = progress.coerceIn(0f, 1f)
    if (x == 0f || x == 1f) return x
    var low = 0f
    var high = 1f
    repeat(20) {
        val t = (low + high) * .5f
        val u = 1f - t
        val curveX = 3f * u * u * t * .42f + 3f * u * t * t * .58f + t * t * t
        if (curveX < x) low = t else high = t
    }
    val t = (low + high) * .5f
    return t * t * (3f - 2f * t)
}

/** Width-based ellipsis without splitting surrogate pairs; server names cannot introduce control lines. */
internal fun mvpEllipsis(text: String, maxWidth: Float, measure: (String) -> Float): String {
    val clean = buildString {
        text.codePoints().filter { !Character.isISOControl(it) }.limit(128).forEach { appendCodePoint(it) }
    }
    if (measure(clean) <= maxWidth) return clean
    var end = clean.length
    while (end > 0) {
        end = clean.offsetByCodePoints(end, -1)
        val candidate = clean.substring(0, end) + "…"
        if (measure(candidate) <= maxWidth) return candidate
    }
    return if (measure("…") <= maxWidth) "…" else ""
}
