/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.features.module.modules.misc.music

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Pure-typographic kinetic timing, phrase decomposition, and 3D camera-frustum scatter engine
 * shared by [ScreenLyrics] (2D planar player lyrics) and [WorldLyrics] (3D MV forward-scattered lyrics).
 *
 * Strictly zero glow/bloom/halo and zero legacy GUI card chrome.
 */
internal object LyricLayout {

    data class Run(val text: String, val start: Int, val end: Int)

    data class ScreenBox(val x: Float, val y: Float, val scale: Float)

    /** Per-UTF16-offset syllable timing state evaluated at `nowMs`. */
    class LineKinetics(
        val fillByIndex: FloatArray,
        val liftByIndex: FloatArray,
        val lineProgress: Float,
    ) {
        fun fillAt(charIndex: Int): Float =
            if (fillByIndex.isEmpty()) lineProgress else fillByIndex[charIndex.coerceIn(0, fillByIndex.lastIndex)]

        fun liftAt(charIndex: Int): Float =
            if (liftByIndex.isEmpty()) 0f else liftByIndex[charIndex.coerceIn(0, liftByIndex.lastIndex)]
    }

    /** Sub-phrase sliced from a [LyricLine] for staggered 3D MV scattering. */
    data class TimedPhrase(
        val text: String,
        val charStart: Int,
        val charEnd: Int,
        val vocalStartMs: Long,
        val vocalEndMs: Long,
        val translation: String = "",
    )

    fun fitBox(sw: Float, sh: Float, w: Float, h: Float, size: Float, x: Float, y: Float): ScreenBox? {
        if (listOf(sw, sh, w, h, size, x, y).any { !it.isFinite() } ||
            sw < 32f || sh < 32f || w <= 0f || h <= 0f || size <= 0f
        ) {
            return null
        }
        val scale = min(size, min((sw - 20f) / w, (sh - 20f) / h)).coerceAtLeast(0.2f)
        val hw = w * scale * 0.5f
        val hh = h * scale * 0.5f
        val marginX = hw + 10f
        val marginY = hh + 10f
        val cx = (sw * x.coerceIn(0f, 100f) / 100f).coerceIn(marginX, max(marginX, sw - marginX))
        val cy = (sh * y.coerceIn(0f, 100f) / 100f).coerceIn(marginY, max(marginY, sh - marginY))
        return ScreenBox(cx, cy, scale)
    }

    fun index(lines: List<LyricLine>, position: Long): Int {
        var lo = 0
        var hi = lines.lastIndex
        var found = -1
        while (lo <= hi) {
            val m = (lo + hi) ushr 1
            if (lines[m].timeMs <= position) {
                found = m
                lo = m + 1
            } else {
                hi = m - 1
            }
        }
        return found
    }

    fun smooth(t: Float): Float {
        val v = t.coerceIn(0f, 1f)
        return v * v * (3f - 2f * v)
    }

    fun smoother(t: Float): Float {
        val v = t.coerceIn(0f, 1f)
        return v * v * v * (v * (v * 6f - 15f) + 10f)
    }

    /**
     * Evaluates sub-pixel fill [0..1] and kinetic syllable lift [0..1] for every character offset in [line].
     */
    fun evaluateKinetics(line: LyricLine, nowMs: Long): LineKinetics {
        val text = line.text
        val n = text.length
        val lineProg = line.progress(nowMs).coerceIn(0f, 1f)
        if (n == 0) return LineKinetics(FloatArray(0), FloatArray(0), lineProg)

        val fills = FloatArray(n)
        val lifts = FloatArray(n)

        val words = line.words
        var matchedExact = words.isNotEmpty()
        if (matchedExact) {
            var totalLen = 0
            for (w in words) totalLen += w.text.length
            if (totalLen != n) matchedExact = false
        }

        if (matchedExact) {
            var cursor = 0
            for (w in words) {
                val len = w.text.length
                if (len <= 0) continue
                val wp = w.progress(nowMs).coerceIn(0f, 1f)
                // Smooth wave crest while singing + subtle settled lift once sung.
                val wave = sin(wp * PI.toFloat()) * 0.74f + smooth(wp) * 0.26f
                var k = 0
                while (k < len && cursor + k < n) {
                    val localStart = k.toFloat() / len.toFloat()
                    val localEnd = (k + 1).toFloat() / len.toFloat()
                    val span = max(1e-4f, localEnd - localStart)
                    fills[cursor + k] = ((wp - localStart) / span).coerceIn(0f, 1f)
                    lifts[cursor + k] = wave.coerceIn(0f, 1f)
                    k++
                }
                cursor += len
            }
        } else {
            // Fallback smooth sweep across codepoints when word fragments do not match 1:1.
            var i = 0
            while (i < n) {
                val charCount = Character.charCount(text.codePointAt(i))
                val cStart = i.toFloat() / n.toFloat()
                val cEnd = (i + charCount).toFloat() / n.toFloat()
                val span = max(1e-4f, cEnd - cStart)
                val cp = ((lineProg - cStart) / span).coerceIn(0f, 1f)
                // Wider soft wave window around the active sweep frontier.
                val dist = ((lineProg - (cStart + cEnd) * 0.5f) / 0.16f).coerceIn(-1f, 1f)
                val crest = if (lineProg <= 0.001f) 0f else max(0f, 1f - dist * dist) * 0.72f + smooth(cp) * 0.28f
                for (j in 0 until charCount) {
                    if (i + j < n) {
                        fills[i + j] = cp
                        lifts[i + j] = crest.coerceIn(0f, 1f)
                    }
                }
                i += charCount
            }
        }
        return LineKinetics(fills, lifts, lineProg)
    }

    /** Unicode-safe soft wrapping that never breaks surrogate pairs and prefers word boundaries. */
    fun wrap(text: String, width: Float, measure: (String) -> Float): List<Run> {
        if (text.isBlank()) return emptyList()
        val limit = width.coerceAtLeast(1f)
        val out = ArrayList<Run>()
        var start = 0
        while (start < text.length) {
            if (text[start] == '\n' || text[start] == '\r') {
                start++
                continue
            }
            var end = start
            var boundary = -1
            while (end < text.length && text[end] != '\n' && text[end] != '\r') {
                val next = end + Character.charCount(text.codePointAt(end))
                if (end > start && measure(text.substring(start, next)) > limit) break
                end = next
                if (text[end - 1].isWhitespace()) boundary = end
            }
            if (end < text.length && text[end] != '\n' && text[end] != '\r' && boundary > start) {
                end = boundary
            }
            if (end == start) {
                end = start + Character.charCount(text.codePointAt(start))
            }
            out.add(Run(text.substring(start, end), start, end))
            start = end
        }
        return out
    }

    /**
     * Splits a [LyricLine] into 1..4 timed sub-phrases for MV-style spatial scattering in 3D space.
     * Phrases split on punctuation / natural pauses first, and fall back to balanced clause chunks
     * on long unbroken lines.
     */
    fun splitIntoPhrases(line: LyricLine, splitEnabled: Boolean): List<TimedPhrase> {
        val raw = line.text
        if (raw.isBlank()) return emptyList()
        if (!splitEnabled) {
            return listOf(
                TimedPhrase(
                    text = raw.trim(),
                    charStart = 0,
                    charEnd = raw.length,
                    vocalStartMs = line.timeMs,
                    vocalEndMs = line.endMs,
                    translation = line.translation.trim(),
                )
            )
        }

        // 1) Collect candidate clause ranges along punctuation or multi-space breaks.
        val rawRanges = ArrayList<IntRange>()
        var segStart = 0
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            val isHardBreak = ch == '，' || ch == '。' || ch == '！' || ch == '？' || ch == '、' ||
                ch == '；' || ch == ',' || ch == ';' || ch == '!' || ch == '?' || ch == '—' ||
                ch == '/' || ch == '\u3000'
            val isSpacePhraseBreak = ch == ' ' && (i - segStart) >= 11 && (raw.length - i) >= 7
            if (isHardBreak || isSpacePhraseBreak) {
                val endExclusive = i + 1
                if (raw.substring(segStart, endExclusive).any { !it.isWhitespace() && !isDelimiterOnly(it) }) {
                    rawRanges.add(segStart until endExclusive)
                }
                segStart = endExclusive
            }
            i++
        }
        if (segStart < raw.length && raw.substring(segStart).any { !it.isWhitespace() }) {
            rawRanges.add(segStart until raw.length)
        }

        // If the line had no punctuation breaks but is visually long, split into 2..3 balanced clauses.
        if (rawRanges.size <= 1) {
            rawRanges.clear()
            val cjkCount = raw.count { it.code in 0x2E80..0x9FFF || it.code in 0xAC00..0xD7AF }
            val targetChunks = when {
                cjkCount >= 15 || raw.length >= 34 -> 3
                cjkCount >= 9 || raw.length >= 20 -> 2
                else -> 1
            }
            if (targetChunks == 1) {
                rawRanges.add(0 until raw.length)
            } else {
                var cStart = 0
                for (chunkIdx in 0 until targetChunks) {
                    val remainingChunks = targetChunks - chunkIdx
                    if (remainingChunks <= 1) {
                        rawRanges.add(cStart until raw.length)
                        break
                    }
                    val ideal = cStart + (raw.length - cStart) / remainingChunks
                    var cut = snapToCodepoint(raw, ideal)
                    // Prefer nearby whitespace for Latin words.
                    var bestSpace = -1
                    for (probe in max(cStart + 3, ideal - 6)..min(raw.length - 3, ideal + 6)) {
                        if (raw[probe] == ' ') {
                            bestSpace = probe + 1
                            break
                        }
                    }
                    if (bestSpace > cStart) cut = bestSpace
                    if (cut <= cStart) cut = min(raw.length, cStart + 1)
                    rawRanges.add(cStart until cut)
                    cStart = cut
                }
            }
        }

        // Cap at most 4 phrases per line by merging overflow into the last groups.
        val mergedRanges = if (rawRanges.size <= 4) {
            rawRanges
        } else {
            val groupSize = (rawRanges.size + 3) / 4
            rawRanges.chunked(groupSize).map { group -> group.first().first..group.last().last }
        }

        // Map character ranges to exact vocal start/end timestamps using LyricLine.words when available.
        val wordCharStarts = IntArray(line.words.size)
        val wordCharEnds = IntArray(line.words.size)
        var exactWords = line.words.isNotEmpty()
        if (exactWords) {
            var pos = 0
            for (wi in line.words.indices) {
                wordCharStarts[wi] = pos
                pos += line.words[wi].text.length
                wordCharEnds[wi] = pos
            }
            if (pos != raw.length) exactWords = false
        }

        val totalDuration = max(1L, line.endMs - line.timeMs)
        val out = ArrayList<TimedPhrase>(mergedRanges.size)
        for ((idx, range) in mergedRanges.withIndex()) {
            // Trim outer whitespace & trailing punctuation marks for clean MV typography.
            var s = range.first
            var e = range.last + 1
            while (s < e && (raw[s].isWhitespace() || isDelimiterOnly(raw[s]))) s++
            while (e > s && (raw[e - 1].isWhitespace() || isTrailingPunct(raw[e - 1]))) e--
            if (s >= e) continue

            val vStart: Long
            val vEnd: Long
            if (exactWords) {
                var minStart = Long.MAX_VALUE
                var maxEnd = Long.MIN_VALUE
                for (wi in line.words.indices) {
                    if (wordCharEnds[wi] > s && wordCharStarts[wi] < e) {
                        minStart = min(minStart, line.words[wi].startMs)
                        maxEnd = max(maxEnd, line.words[wi].endMs)
                    }
                }
                vStart = if (minStart != Long.MAX_VALUE) minStart else line.timeMs + (totalDuration * s) / max(1, raw.length)
                vEnd = if (maxEnd != Long.MIN_VALUE) max(vStart + 180L, maxEnd) else line.endMs
            } else {
                vStart = line.timeMs + (totalDuration * s) / max(1, raw.length)
                vEnd = max(vStart + 220L, line.timeMs + (totalDuration * e) / max(1, raw.length))
            }

            val trans = if (idx == mergedRanges.lastIndex) line.translation.trim() else ""
            out.add(TimedPhrase(raw.substring(s, e), s, e, vStart, vEnd, trans))
        }
        return out
    }

    private fun isDelimiterOnly(c: Char): Boolean =
        c == '，' || c == '。' || c == '、' || c == '；' || c == ',' || c == ';'

    private fun isTrailingPunct(c: Char): Boolean =
        c == '，' || c == '。' || c == '、' || c == '；' || c == ',' || c == ';'

    private fun snapToCodepoint(s: String, index: Int): Int {
        val clamped = index.coerceIn(0, s.length)
        if (clamped in 1 until s.length && Character.isLowSurrogate(s[clamped])) {
            return clamped - 1
        }
        return clamped
    }
}

/**
 * Forward-frustum 3D MV spatial stage for [WorldLyrics].
 *
 * Every phrase node stays strictly in front of the player's camera (`forward * depth + right * x + up * y`),
 * scattered randomly across UP / DOWN / LEFT / RIGHT and varied depth layers with multi-node collision
 * avoidance, staggered vocal entry, outgoing depth dissolve, inertial camera lag, and 3D perspective tilt.
 */
internal class SpatialFrustumStage {

    data class NodePose(
        val lineIndex: Int,
        val phraseIndex: Int,
        val phrase: LyricLayout.TimedPhrase,
        val line: LyricLine,
        val nx: Float,
        val ny: Float,
        val depthMul: Float,
        val alpha: Float,
        val scaleMul: Float,
        val yawTiltDeg: Float,
        val pitchTiltDeg: Float,
        val rollTiltDeg: Float,
        val isOutgoing: Boolean,
    )

    private class LiveNode(
        val id: String,
        val lineIndex: Int,
        val phraseIndex: Int,
        val sector: Int,
        val baseX: Float,
        val baseY: Float,
        val baseDepth: Float,
        val rollSeed: Float,
        var smoothX: Float,
        var smoothY: Float,
        var smoothDepth: Float,
        var lastSeenMs: Long,
    )

    // 8 distinct spatial zones covering UP / DOWN / LEFT / RIGHT around the forward view.
    private val sectorCentersX = floatArrayOf(-0.68f, 0.68f, -0.64f, 0.64f, 0.0f, 0.0f, -0.84f, 0.84f)
    private val sectorCentersY = floatArrayOf(0.62f, 0.62f, -0.58f, -0.58f, 0.78f, -0.74f, 0.04f, -0.04f)

    private val nodes = LinkedHashMap<String, LiveNode>()
    private var lastTrackKey = ""
    private var lastPositionMs = Long.MIN_VALUE
    private var lastFrameNanos = 0L
    private var lastYaw = Float.NaN
    private var lastPitch = 0f
    private var lagX = 0f
    private var lagY = 0f
    private var recentSector = -1

    fun clear() {
        nodes.clear()
        lastTrackKey = ""
        lastPositionMs = Long.MIN_VALUE
        lastFrameNanos = 0L
        lastYaw = Float.NaN
        lastPitch = 0f
        lagX = 0f
        lagY = 0f
        recentSector = -1
    }

    fun step(
        trackKey: String,
        lines: List<LyricLine>,
        activeIndex: Int,
        nowMs: Long,
        nanos: Long,
        spread: Float,
        split: Boolean,
        preset: ModuleMusic.WorldPreset,
        cameraYaw: Float,
        cameraPitch: Float,
    ): List<NodePose> {
        val dt = if (lastFrameNanos == 0L) 0.016f else ((nanos - lastFrameNanos) / 1e9f).coerceIn(0.001f, 0.1f)
        lastFrameNanos = nanos

        val seeked = lastPositionMs != Long.MIN_VALUE &&
            (nowMs < lastPositionMs - 220L || abs(nowMs - lastPositionMs) > 2600L)
        if (trackKey != lastTrackKey || seeked) {
            nodes.clear()
            lastTrackKey = trackKey
            recentSector = -1
        }
        lastPositionMs = nowMs

        // Smooth inertial suspension when the player turns their head — always bounded well inside the frustum.
        if (lastYaw.isFinite()) {
            val deltaYaw = ((cameraYaw - lastYaw + 540f) % 360f) - 180f
            val deltaPitch = cameraPitch - lastPitch
            lagX = ((lagX - deltaYaw * 0.0022f) * exp(-dt * 8.5f)).coerceIn(-0.085f, 0.085f)
            lagY = ((lagY + deltaPitch * 0.0020f) * exp(-dt * 8.5f)).coerceIn(-0.065f, 0.065f)
        }
        lastYaw = cameraYaw
        lastPitch = cameraPitch

        val spreadNorm = (spread.coerceIn(0f, 60f) / 60f)
        // Even at modest spread, give wide UP/DOWN/LEFT/RIGHT dispersion in front of the player.
        val reachX = (0.14f + spreadNorm * 0.52f).coerceIn(0.10f, 0.66f)
        val reachY = (0.12f + spreadNorm * 0.42f).coerceIn(0.08f, 0.54f)

        val outroMs = 440L
        val preLeadMs = 240L
        val result = ArrayList<NodePose>(6)

        // Inspect previous line (for smooth outgoing depth dissolve) + current line + upcoming anticipation.
        val startIdx = max(0, activeIndex - 1)
        val endIdx = min(lines.lastIndex, activeIndex + 1)

        for (li in startIdx..endIdx) {
            if (li < 0) continue
            val line = lines[li]
            if (line.text.isBlank()) continue

            // Quick temporal cull before phrase slicing.
            if (nowMs < line.timeMs - 260L || nowMs > line.endMs + outroMs) continue

            val phrases = LyricLayout.splitIntoPhrases(line, split)
            for ((pi, phrase) in phrases.withIndex()) {
                val entryStartMs = if (pi == 0) line.timeMs - 90L else phrase.vocalStartMs - preLeadMs
                val exitEndMs = line.endMs + outroMs
                if (nowMs < entryStartMs || nowMs >= exitEndMs) continue

                val enterDur = 280f
                val exitDur = outroMs.toFloat()
                val enterT = LyricLayout.smoother(((nowMs - entryStartMs).toFloat() / enterDur).coerceIn(0f, 1f))
                val exitT = if (nowMs <= line.endMs) {
                    1f
                } else {
                    1f - LyricLayout.smooth(((nowMs - line.endMs).toFloat() / exitDur).coerceIn(0f, 1f))
                }
                val alpha = (enterT * exitT).coerceIn(0f, 1f)
                if (alpha <= 0.004f) continue

                val nodeId = "$trackKey:$li:$pi:$split"
                val node = nodes.getOrPut(nodeId) {
                    spawnScatteredNode(nodeId, trackKey, li, pi, reachX, reachY, spreadNorm, preset, nowMs)
                }
                node.lastSeenMs = nowMs

                // Preset-driven 3D motion trajectory (pure spatial depth & drift — strictly zero glow).
                val introProgress = 1f - enterT
                val outroProgress = if (nowMs > line.endMs) (1f - exitT) else 0f
                val targetDepth = when (preset) {
                    ModuleMusic.WorldPreset.CADENZA ->
                        node.baseDepth + introProgress * 0.22f - outroProgress * 0.14f
                    ModuleMusic.WorldPreset.DIORAMA ->
                        node.baseDepth + introProgress * 0.14f + outroProgress * 0.18f
                    else ->
                        node.baseDepth + introProgress * 0.16f + outroProgress * 0.11f
                }
                val targetY = node.baseY - introProgress * 0.032f + outroProgress * 0.026f
                val targetX = node.baseX + (if (preset == ModuleMusic.WorldPreset.PENDOLO) introProgress * 0.025f * node.rollSeed else 0f)

                val k = (1f - exp(-dt * 13f)).coerceIn(0f, 1f)
                node.smoothX += (targetX - node.smoothX) * k
                node.smoothY += (targetY - node.smoothY) * k
                node.smoothDepth += (targetDepth - node.smoothDepth) * k

                val finalX = (node.smoothX + lagX).coerceIn(-0.74f, 0.74f)
                val finalY = (node.smoothY + lagY).coerceIn(-0.58f, 0.58f)

                // Natural inward 3D perspective convergence toward the viewer's eyes.
                val tiltScale = when (preset) {
                    ModuleMusic.WorldPreset.DIORAMA -> 1.25f
                    ModuleMusic.WorldPreset.PENDOLO -> 1.15f
                    else -> 1.0f
                }
                val yawTilt = (-finalX * 20f * tiltScale).coerceIn(-22f, 22f)
                val pitchTilt = (finalY * 13f * tiltScale).coerceIn(-15f, 15f)
                val rollTilt = when (preset) {
                    ModuleMusic.WorldPreset.PENDOLO -> node.rollSeed * 3.2f
                    ModuleMusic.WorldPreset.PARTITA -> node.rollSeed * 2.0f
                    else -> node.rollSeed * 1.1f
                }

                val scaleMul = (0.90f + 0.10f * enterT) * (1f - 0.06f * outroProgress)

                result.add(
                    NodePose(
                        lineIndex = li,
                        phraseIndex = pi,
                        phrase = phrase,
                        line = line,
                        nx = finalX,
                        ny = finalY,
                        depthMul = node.smoothDepth.coerceIn(0.68f, 1.55f),
                        alpha = alpha,
                        scaleMul = scaleMul,
                        yawTiltDeg = yawTilt,
                        pitchTiltDeg = pitchTilt,
                        rollTiltDeg = rollTilt,
                        isOutgoing = li < activeIndex || nowMs > line.endMs,
                    )
                )
            }
        }

        // Prune expired nodes so memory stays tiny across long playlists.
        val iter = nodes.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next().value
            if (abs(nowMs - e.lastSeenMs) > 1600L) {
                iter.remove()
            }
        }

        // Render back-to-front by depthMul so nearer 3D phrases sit cleanly in front of deeper ones.
        result.sortByDescending { it.depthMul }
        return result
    }

    private fun spawnScatteredNode(
        nodeId: String,
        trackKey: String,
        lineIndex: Int,
        phraseIndex: Int,
        reachX: Float,
        reachY: Float,
        spreadNorm: Float,
        preset: ModuleMusic.WorldPreset,
        nowMs: Long,
    ): LiveNode {
        val seed = trackKey.hashCode().toLong() * 31L + lineIndex.toLong() * 131071L + phraseIndex.toLong() * 8191L
        val rng = Random(seed)

        var bestSector = 0
        var bestX = 0f
        var bestY = 0f
        var bestDepth = 1f
        var bestScore = Float.NEGATIVE_INFINITY

        // Evaluate 12 candidate positions across all 8 UP/DOWN/LEFT/RIGHT sectors and pick max clearance.
        for (attempt in 0 until 12) {
            val sector = (attempt + rng.nextInt(8)) and 7
            val cx = sectorCentersX[sector] * reachX
            val cy = sectorCentersY[sector] * reachY
            val jitterX = (rng.nextFloat() - 0.5f) * reachX * 0.58f
            val jitterY = (rng.nextFloat() - 0.5f) * reachY * 0.54f
            val candX = (cx + jitterX).coerceIn(-reachX, reachX)
            val candY = (cy + jitterY).coerceIn(-reachY, reachY)

            val depthSpan = if (preset == ModuleMusic.WorldPreset.DIORAMA) 0.56f else 0.38f
            val candDepth = 0.80f + rng.nextFloat() * depthSpan

            var minClearance = 2.5f
            for (existing in nodes.values) {
                if (abs(nowMs - existing.lastSeenMs) > 900L) continue
                // Normalize vertical separation stricter than horizontal so multi-line text blocks never touch.
                val dx = (candX - existing.baseX) / 0.34f
                val dy = (candY - existing.baseY) / 0.17f
                val dz = (candDepth - existing.baseDepth) * 0.45f
                val d = hypot(hypot(dx, dy), dz)
                if (d < minClearance) minClearance = d
            }

            // Keep a gentle breathing margin around the exact center reticle when spread > 0,
            // while rewarding fresh sectors (top/bottom/left/right variety).
            val centerRadius = hypot(candX / max(0.12f, reachX), candY / max(0.12f, reachY))
            val reticleBonus = if (spreadNorm > 0.12f && centerRadius < 0.32f) -0.45f else 0.14f * min(centerRadius, 0.95f)
            val sectorRepeatPenalty = if (sector == recentSector) -0.35f else 0f
            val score = minClearance + reticleBonus + sectorRepeatPenalty + rng.nextFloat() * 0.08f

            if (score > bestScore) {
                bestScore = score
                bestSector = sector
                bestX = candX
                bestY = candY
                bestDepth = candDepth
            }
        }

        recentSector = bestSector
        val rollSeed = (rng.nextFloat() * 2f - 1f)
        return LiveNode(
            id = nodeId,
            lineIndex = lineIndex,
            phraseIndex = phraseIndex,
            sector = bestSector,
            baseX = bestX,
            baseY = bestY,
            baseDepth = bestDepth,
            rollSeed = rollSeed,
            smoothX = bestX * 0.94f,
            smoothY = bestY - 0.028f,
            smoothDepth = bestDepth + 0.14f,
            lastSeenMs = nowMs,
        )
    }
}
