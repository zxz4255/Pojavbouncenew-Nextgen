/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.ccbluex.liquidbounce.features.module.modules.misc.music.worldlyrics

import com.mojang.blaze3d.vertex.VertexConsumer
import net.ccbluex.liquidbounce.event.events.WorldFeatureSubmitEvent
import net.ccbluex.liquidbounce.features.module.modules.misc.music.LyricLayout
import net.ccbluex.liquidbounce.features.module.modules.misc.music.LyricLine
import net.ccbluex.liquidbounce.features.module.modules.misc.music.ModuleMusic
import net.ccbluex.liquidbounce.features.module.modules.misc.music.MusicManager
import net.ccbluex.liquidbounce.features.module.modules.misc.music.SpatialFrustumStage
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.TtfFont
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Typeface
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.renderer.rendertype.RenderTypes
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * MV-style 3D camera-frustum scattered world lyrics.
 *
 * - Always anchored in the forward view frustum ahead of the player (never left behind at a fixed world block,
 *   and never stuck at one rigid follower offset).
 * - Scatters phrases randomly across UP / DOWN / LEFT / RIGHT and multiple forward depth planes with
 *   collision avoidance, staggered vocal entry, outgoing depth dissolve, and subtle 3D perspective convergence.
 * - Strictly ZERO glow, bloom, halo, or legacy GUI card chrome.
 */
object WorldLyrics {

    data class Options(
        val distance: Float,
        val scale: Float,
        val spread: Float,
        val split: Boolean,
        val preset: ModuleMusic.WorldPreset,
        val colorMode: ModuleMusic.LyricColorMode = ModuleMusic.LyricColorMode.PRESET,
        val customColor: Int = 0xFFFFFFFF.toInt(),
        val activeColor: Int = 0xFFA8C7FA.toInt(),
        val activeHighlight: Boolean = true,
        val inactiveDim: Float = 0.35f,
        val shadow: Float = 1f,
    )

    private const val NOMINAL_FONT_SIZE = 22f
    private const val LINE_STEP = 28f
    private const val SUB_SCALE = 0.60f
    private const val SUB_STEP = 18f

    private val stage = SpatialFrustumStage()
    private var font: TtfFont? = null
    private var fontAttempted = false
    private var lastLevel: Any? = null

    fun clear() {
        stage.clear()
        lastLevel = null
    }

    private fun face(): TtfFont? {
        if (!fontAttempted) {
            fontAttempted = true
            font = TtfFont.loadOrNull(Typeface.BUNDLED_FONT, NOMINAL_FONT_SIZE, 4f, 2048, "worldlyrics100")
            if (font == null) {
                logger.warn("[Music] 3D world lyrics bundled font unavailable")
            }
        }
        return font
    }

    private class GlyphQuad(
        val glyph: TtfFont.Glyph,
        val x: Float,
        val y: Float,
        val z: Float,
        val unitScale: Float,
        val fill: Float,
        val isTranslation: Boolean,
    )

    fun render(event: WorldFeatureSubmitEvent, options: Options) {
        val level = mc.level ?: run {
            clear()
            return
        }
        if (mc.player == null) {
            clear()
            return
        }
        if (lastLevel !== level) {
            clear()
            lastLevel = level
        }

        val song = MusicManager.current ?: run {
            clear()
            return
        }
        val lines = MusicManager.lyrics()
        if (MusicManager.lyricsSongId() != song.id || lines.isEmpty()) {
            clear()
            return
        }

        val lyricFont = face() ?: return
        val now = MusicManager.positionMs()
        val activeIdx = LyricLayout.index(lines, now)
        if (activeIdx < 0 && lines.first().timeMs - now > 260L) return

        val trackKey = "${song.provider}:${song.id}:${song.providerHash}"
        val camera = event.camera
        val nodes = stage.step(
            trackKey = trackKey,
            lines = lines,
            activeIndex = activeIdx,
            nowMs = now,
            nanos = System.nanoTime(),
            spread = options.spread,
            split = options.split,
            preset = options.preset,
            cameraYaw = camera.yRot(),
            cameraPitch = camera.xRot(),
        )
        if (nodes.isEmpty()) return

        val camQuat = camera.rotation()
        val right = Vector3f(1f, 0f, 0f).rotate(camQuat)
        val up = Vector3f(0f, 1f, 0f).rotate(camQuat)
        val forward = camera.forwardVector()

        val baseDistance = options.distance.coerceIn(3f, 48f)
        val fovDeg = mc.options.fov().get().toDouble().coerceIn(35.0, 115.0)
        val halfFovTan = tan(Math.toRadians(fovDeg * 0.5)).toFloat()
        val aspect = (mc.window.guiScaledWidth.toFloat() / mc.window.guiScaledHeight.coerceAtLeast(1)).coerceIn(0.6f, 3.2f)

        val presetRgb = when (options.preset) {
            ModuleMusic.WorldPreset.CADENZA -> 0xEEF2FF
            ModuleMusic.WorldPreset.PARTITA -> 0xF7EFE2
            ModuleMusic.WorldPreset.PENDOLO -> 0xE5EEFA
            ModuleMusic.WorldPreset.DIORAMA -> 0xF5E7EE
            ModuleMusic.WorldPreset.SCATTER -> 0xF4F7FB
        }

        val customCol = Color4b(options.customColor)
        val activeCol = Color4b(options.activeColor)
        val activeRgb = ModuleMusic.resolveLyricColor(
            options.colorMode, presetRgb, customCol,
            active = true, activeColor = activeCol,
            activeHighlight = options.activeHighlight, dim = 0f,
        )
        val restingRgb = ModuleMusic.resolveLyricColor(
            options.colorMode, presetRgb, customCol,
            active = false, activeColor = activeCol,
            activeHighlight = options.activeHighlight,
            dim = options.inactiveDim.coerceIn(0f, 0.85f) * 0.55f,
        )
        val subRgb = ModuleMusic.blendRgb(restingRgb, 0xB6C0D2, 0.45f)

        // Cache per-line syllable kinetics across sibling phrases of the same line.
        var cachedLineRef: LyricLine? = null
        var cachedKinetics: LyricLayout.LineKinetics? = null

        for (node in nodes) {
            val kinetics = if (cachedLineRef === node.line && cachedKinetics != null) {
                cachedKinetics!!
            } else {
                LyricLayout.evaluateKinetics(node.line, now).also {
                    cachedLineRef = node.line
                    cachedKinetics = it
                }
            }

            val wrapLimit = if (options.split) 240f else 440f
            val mainRuns = LyricLayout.wrap(node.phrase.text, wrapLimit) { lyricFont.width(it) }.take(3)
            if (mainRuns.isEmpty()) continue

            val transRuns = if (node.phrase.translation.isNotBlank()) {
                LyricLayout.wrap(node.phrase.translation, wrapLimit / SUB_SCALE) { lyricFont.width(it) }.take(2)
            } else {
                emptyList()
            }

            // Pre-pack all glyphs into the atlas before querying textureId().
            prewarmGlyphs(lyricFont, mainRuns, transRuns)
            val texture = lyricFont.textureId() ?: continue

            val maxBlockW = max(
                mainRuns.maxOfOrNull { lyricFont.width(it.text) } ?: 1f,
                transRuns.maxOfOrNull { lyricFont.width(it.text) * SUB_SCALE } ?: 0f,
            ).coerceAtLeast(1f)
            val totalBlockH = mainRuns.size * LINE_STEP +
                (if (transRuns.isNotEmpty()) 6f + transRuns.size * SUB_STEP else 0f)

            val nodeDepth = (baseDistance * node.depthMul).coerceIn(2.4f, 64f)
            val frustumHalfY = nodeDepth * halfFovTan * 0.82f
            val frustumHalfX = frustumHalfY * aspect * 0.84f

            // Fit phrase comfortably inside the forward frustum even at large user scale.
            val maxWorldW = frustumHalfX * (if (options.split) 0.92f else 1.38f)
            val maxWorldH = frustumHalfY * 0.78f
            val desiredScale = (0.0135f * (nodeDepth / 10f) *
                options.scale.coerceIn(0.4f, 6f) *
                MusicManager.lyricScale() *
                node.scaleMul)
            val worldScale = min(desiredScale, min(maxWorldW / maxBlockW, maxWorldH / maxBlockH(totalBlockH)))
                .coerceAtLeast(0.0005f)

            // Keep edges inside the forward screen bounds by clamping anchor against half-extents.
            val halfWorldW = maxBlockW * worldScale * 0.5f
            val halfWorldH = totalBlockH * worldScale * 0.5f
            val safeSpanX = max(0.05f, frustumHalfX - halfWorldW)
            val safeSpanY = max(0.05f, frustumHalfY - halfWorldH)

            val offsetX = (node.nx * frustumHalfX).coerceIn(-safeSpanX, safeSpanX)
            val offsetY = (node.ny * frustumHalfY).coerceIn(-safeSpanY, safeSpanY)

            val wx = forward.x() * nodeDepth + right.x() * offsetX + up.x() * offsetY
            val wy = forward.y() * nodeDepth + right.y() * offsetX + up.y() * offsetY
            val wz = forward.z() * nodeDepth + right.z() * offsetX + up.z() * offsetY

            val quads = buildPhraseQuads(lyricFont, node, mainRuns, transRuns, totalBlockH, kinetics)
            if (quads.isEmpty()) continue

            val tiltQuat = Quaternionf().rotationYXZ(
                Math.toRadians(node.yawTiltDeg.toDouble()).toFloat(),
                Math.toRadians(node.pitchTiltDeg.toDouble()).toFloat(),
                Math.toRadians(node.rollTiltDeg.toDouble()).toFloat(),
            )

            val pose = event.poseStack
            pose.pushPose()
            try {
                pose.last().pose()
                    .translate(wx, wy, wz)
                    .rotate(camQuat)
                    .rotate(tiltQuat)
                    .scale(worldScale, -worldScale, worldScale)

                val shadowAmt = options.shadow.coerceIn(0f, 3f)
                val baseAlpha = node.alpha * (if (node.isOutgoing) 0.72f else 1f)
                val unsungAlpha = baseAlpha * (1f - options.inactiveDim.coerceIn(0f, 0.88f) * 0.62f)

                event.submitNodeStorage.submitCustomGeometry(pose, RenderTypes.textSeeThrough(texture)) { submitted, vc ->
                    val m = submitted.pose()
                    for (q in quads) {
                        if (q.isTranslation) {
                            val trAlpha = (baseAlpha * 0.78f).coerceIn(0f, 1f)
                            if (shadowAmt > 0.02f) {
                                emitGlyphQuad(vc, m, q, 0.78f * shadowAmt, 1.05f * shadowAmt, 0.08f, trAlpha * 0.42f, 0x000000, 0f, 1f)
                            }
                            emitGlyphQuad(vc, m, q, 0f, 0f, 0f, trAlpha, subRgb, 0f, 1f)
                        } else {
                            val f = q.fill.coerceIn(0f, 1f)
                            if (shadowAmt > 0.02f) {
                                val shA = max(unsungAlpha, baseAlpha * f) * (0.44f * min(1.3f, shadowAmt)).coerceIn(0f, 0.68f)
                                emitGlyphQuad(vc, m, q, 0.90f * shadowAmt, 1.22f * shadowAmt, 0.12f, shA, 0x000000, 0f, 1f)
                            }
                            if (f < 0.999f) {
                                emitGlyphQuad(vc, m, q, 0f, 0f, 0f, unsungAlpha, restingRgb, 0f, 1f)
                            }
                            if (f > 0.001f) {
                                emitGlyphQuad(vc, m, q, 0f, 0f, -0.02f, baseAlpha, activeRgb, 0f, f)
                            }
                        }
                    }
                }
            } finally {
                pose.popPose()
            }
        }
    }

    private fun maxBlockH(h: Float): Float = max(1f, h)

    private fun prewarmGlyphs(
        font: TtfFont,
        mainRuns: List<LyricLayout.Run>,
        transRuns: List<LyricLayout.Run>,
    ) {
        for (run in mainRuns) {
            var i = 0
            while (i < run.text.length) {
                val cp = run.text.codePointAt(i)
                font.glyph(if (cp > 0xFFFF) '\u25A1' else cp.toChar())
                i += Character.charCount(cp)
            }
        }
        for (run in transRuns) {
            var i = 0
            while (i < run.text.length) {
                val cp = run.text.codePointAt(i)
                font.glyph(if (cp > 0xFFFF) '\u25A1' else cp.toChar())
                i += Character.charCount(cp)
            }
        }
    }

    private fun buildPhraseQuads(
        font: TtfFont,
        node: SpatialFrustumStage.NodePose,
        mainRuns: List<LyricLayout.Run>,
        transRuns: List<LyricLayout.Run>,
        totalBlockH: Float,
        kinetics: LyricLayout.LineKinetics,
    ): List<GlyphQuad> {
        val out = ArrayList<GlyphQuad>(node.phrase.text.length + node.phrase.translation.length)
        var cursorY = -totalBlockH * 0.5f + font.ascent * 0.55f

        for (run in mainRuns) {
            val rowW = font.width(run.text)
            var penX = -rowW * 0.5f
            var localOffset = 0
            while (localOffset < run.text.length) {
                val cp = run.text.codePointAt(localOffset)
                val ch = if (cp > 0xFFFF) '\u25A1' else cp.toChar()
                val g = font.glyph(ch)
                val lineCharIdx = node.phrase.charStart + run.start + localOffset
                val fill = if (node.isOutgoing) 1f else kinetics.fillAt(lineCharIdx)
                val lift = if (node.isOutgoing) 0f else kinetics.liftAt(lineCharIdx)
                if (!g.isEmpty()) {
                    // Subtle kinetic micro-lift in Y and step toward camera in local -Z while being sung.
                    out.add(
                        GlyphQuad(
                            glyph = g,
                            x = penX,
                            y = cursorY - lift * 1.65f,
                            z = -lift * 0.55f,
                            unitScale = 1f,
                            fill = fill,
                            isTranslation = false,
                        )
                    )
                }
                penX += g.advance
                localOffset += Character.charCount(cp)
            }
            cursorY += LINE_STEP
        }

        if (transRuns.isNotEmpty()) {
            cursorY += 4f
            for (tr in transRuns) {
                val rowW = font.width(tr.text) * SUB_SCALE
                var penX = -rowW * 0.5f
                var localOffset = 0
                while (localOffset < tr.text.length) {
                    val cp = tr.text.codePointAt(localOffset)
                    val ch = if (cp > 0xFFFF) '\u25A1' else cp.toChar()
                    val g = font.glyph(ch)
                    if (!g.isEmpty()) {
                        out.add(
                            GlyphQuad(
                                glyph = g,
                                x = penX,
                                y = cursorY,
                                z = 0.08f,
                                unitScale = SUB_SCALE,
                                fill = 1f,
                                isTranslation = true,
                            )
                        )
                    }
                    penX += g.advance * SUB_SCALE
                    localOffset += Character.charCount(cp)
                }
                cursorY += SUB_STEP
            }
        }
        return out
    }

    private fun emitGlyphQuad(
        vc: VertexConsumer,
        m: Matrix4f,
        q: GlyphQuad,
        dx: Float,
        dy: Float,
        dz: Float,
        alpha: Float,
        rgb: Int,
        uFracStart: Float,
        uFracEnd: Float,
    ) {
        val g = q.glyph
        if (g.isEmpty() || alpha <= 0.003f || uFracEnd <= uFracStart + 0.001f) return
        val s = q.unitScale
        val x0Full = q.x + g.bearingX * s + dx
        val y0 = q.y + (TtfFont.GLYPH_TOP_OFFSET + g.bearingY) * s + dy
        val gw = g.width * s
        val gh = g.height * s
        if (gw <= 1e-4f || gh <= 1e-4f) return

        val x0: Float
        val x1: Float
        val u0: Float
        val u1: Float
        if (uFracStart <= 0.001f && uFracEnd >= 0.999f) {
            x0 = x0Full
            x1 = x0Full + gw
            u0 = g.u0
            u1 = g.u1
        } else {
            // Map syllable fill across the visible ink advance so wide padding doesn't delay stroke entry.
            val inkLeft = q.x + dx
            val inkSpan = max(g.advance, g.bearingX + g.width * 0.76f) * s
            val clipL = (inkLeft + inkSpan * uFracStart).coerceIn(x0Full, x0Full + gw)
            val clipR = (inkLeft + inkSpan * uFracEnd).coerceIn(x0Full, x0Full + gw)
            if (clipR <= clipL + 0.01f) return
            x0 = clipL
            x1 = clipR
            val f0 = ((clipL - x0Full) / gw).coerceIn(0f, 1f)
            val f1 = ((clipR - x0Full) / gw).coerceIn(0f, 1f)
            u0 = g.u0 + (g.u1 - g.u0) * f0
            u1 = g.u0 + (g.u1 - g.u0) * f1
        }

        val y1 = y0 + gh
        val z = q.z + dz
        val argb = ((alpha.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255) shl 24) or (rgb and 0xFFFFFF)

        fun vtx(px: Float, py: Float, uu: Float, vv: Float) {
            vc.addVertex(m, px, py, z)
                .setColor(argb)
                .setUv(uu, vv)
                .setUv2(240, 240)
                .setNormal(0f, 0f, 1f)
                .setUv1(0, 0)
        }
        vtx(x0, y0, u0, g.v0)
        vtx(x0, y1, u0, g.v1)
        vtx(x1, y1, u1, g.v1)
        vtx(x1, y0, u1, g.v0)
    }
}
