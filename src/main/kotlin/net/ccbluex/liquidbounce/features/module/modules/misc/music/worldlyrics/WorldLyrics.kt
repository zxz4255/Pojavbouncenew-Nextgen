package net.ccbluex.liquidbounce.features.module.modules.misc.music.worldlyrics

import com.mojang.blaze3d.vertex.VertexConsumer
import net.ccbluex.liquidbounce.event.events.WorldFeatureSubmitEvent
import net.ccbluex.liquidbounce.features.module.modules.misc.music.MusicManager
import net.ccbluex.liquidbounce.features.module.modules.misc.music.ModuleMusic
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.TtfFont
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Typeface
import net.ccbluex.liquidbounce.common.LyricLine
import net.ccbluex.liquidbounce.common.LyricsInput
import net.ccbluex.liquidbounce.common.WorldLyricsState
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.renderer.rendertype.RenderTypes
import org.joml.Matrix4f
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** An intentionally limited first integration of MuoniumPlayer's world lyric state engine.
 * The world glyphs use the player's bundled TTF. The halo is multi-sampled, not Muonium's
 * Skija Gaussian atlas. See README-WorldLyrics.md for unimplemented effects and render limits.
 */
object WorldLyrics {
    data class Options(val distance: Float, val scale: Float, val spread: Float, val glow: Float,
                       val split: Boolean, val wordPop: Boolean, val keepDistance: Boolean,
                       val preset: ModuleMusic.WorldPreset)

    private val state = WorldLyricsState.get()
    private var source: List<net.ccbluex.liquidbounce.features.module.modules.misc.music.LyricLine>? = null
    private var trackId = -1L
    private var trackKey: String? = null
    private var lastPreset: ModuleMusic.WorldPreset? = null
    private var fontAttempted = false
    private var font: TtfFont? = null
    private var lastLevel: Any? = null

    fun clear() {
        state.resetForTest()
        source = null
        trackId = -1L
        trackKey = null
        lastPreset = null
        lastLevel = null
        LyricsInput.lyrics = emptyList()
        LyricsInput.lyricsTrackKey = null
        LyricsInput.lyricsRevision++
    }

    private fun getFont(): TtfFont? {
        if (!fontAttempted) {
            fontAttempted = true
            font = TtfFont.loadOrNull(Typeface.BUNDLED_FONT, 10.0F, 4.0F, 2048, "worldlyrics")
            if (font == null) logger.warn("[Music] World lyrics font missing: ${Typeface.BUNDLED_FONT}")
        }
        return font
    }

    fun render(event: WorldFeatureSubmitEvent, options: Options) {
        val level = mc.level ?: run { clear(); return }
        if (mc.player == null) { clear(); return }
        if (lastLevel !== level) { clear(); lastLevel = level }
        val song = MusicManager.current ?: run { clear(); return }
        val raw = MusicManager.lyrics()
        if (MusicManager.lyricsSongId() != song.id || raw.isEmpty() || MusicManager.current?.id != song.id) {
            clear(); lastLevel = level; return
        }
        val face = getFont() ?: return
        val key = "${song.provider.name.lowercase()}:${song.id}"
        if (trackId != song.id || trackKey != key || source !== raw) {
            val lines = raw.map { line ->
                LyricLine(line.timeMs, line.text).apply {
                    duration = (line.endMs - line.timeMs).coerceAtLeast(0)
                    translationText = line.translation
                    romanizationText = line.romanisation
                    line.words.forEach { word ->
                        words.add(LyricLine.Word(word.text, word.startMs, (word.endMs - word.startMs).coerceAtLeast(0)))
                    }
                }
            }
            LyricsInput.lyrics = lines
            LyricsInput.lyricsTrackKey = "${song.provider.name.lowercase()}:${song.id}"
            LyricsInput.lyricsRevision++
            LyricsInput.setAdvance { c -> face.advance(c.toChar()).toDouble() }
            source = raw
            trackId = song.id
            trackKey = key
        }
        if (lastPreset != options.preset) {
            state.resetForTest()
            lastPreset = options.preset
        }
        val camera = event.camera
        val pos = camera.position()
        val forward = camera.forwardVector()
        val view = object : WorldLyricsState.CameraView {
            override fun eyeX() = pos.x
            override fun eyeY() = pos.y
            override fun eyeZ() = pos.z
            override fun lookX() = forward.x().toDouble()
            override fun lookY() = forward.y().toDouble()
            override fun lookZ() = forward.z().toDouble()
            override fun yawDeg() = camera.yRot().toDouble()
            override fun pitchDeg() = camera.xRot().toDouble()
        }
        val params = object : WorldLyricsState.Params {
            override fun distance() = options.distance.toDouble()
            override fun scale() = options.scale.toDouble()
            override fun spreadDeg() = options.spread.toDouble()
            override fun facePlayer() = true
            override fun keepAway() = true
            override fun splitLines() = options.split
            override fun wordPop() = options.wordPop || options.preset == ModuleMusic.WorldPreset.PULSE
            override fun cadenza() = options.preset == ModuleMusic.WorldPreset.CADENZA
            override fun huiguang() = options.preset == ModuleMusic.WorldPreset.LIGHT
            override fun keepDistance() = options.keepDistance
        }
        val now = MusicManager.positionMs()
        val frame = state.update(view, params, System.nanoTime(), MusicManager.isPlaying(), now)
        for (row in frame.rows) {
            if (row.alpha <= 0.01F) continue
            val glyphs = WorldLyricsState.buildGlyphs(row.floating, now, row.alpha,
                params.wordPop(), !row.active, { text -> face.width(text) }, params.cadenza(), params.huiguang(), null)
            if (glyphs.glyphs.isEmpty()) continue
            // Pack all needed glyphs before flushing; callbacks run later in the frame.
            val entries = glyphs.glyphs.map { g -> g to face.glyph(g.c) }
            val texture = face.textureId() ?: continue
            val pose = event.poseStack
            pose.pushPose()
            try {
                val m = pose.last().pose()
                m.translate((row.x - row.camX).toFloat(), (row.y - row.camY).toFloat(),
                    (row.z - row.camZ).toFloat())
                m.rotateY(Math.toRadians(-row.yawDeg).toFloat())
                m.rotateX(Math.toRadians(row.pitchDeg).toFloat())
                val elapsed = (now - row.floating.startMs).coerceAtLeast(0L) / 1000f
                val pulse = if (options.preset == ModuleMusic.WorldPreset.PULSE && row.active)
                    1f + 0.07f * sin(elapsed * 3.4f) else 1f
                val presetSize = when (options.preset) {
                    ModuleMusic.WorldPreset.CLASSIC -> 1f
                    ModuleMusic.WorldPreset.CADENZA -> 1.12f
                    ModuleMusic.WorldPreset.LIGHT -> 1.2f
                    ModuleMusic.WorldPreset.PULSE -> 1.32f
                }
                m.scale(-row.scale * presetSize * pulse, -row.scale * presetSize * pulse, row.scale)
                val halo = options.glow.coerceIn(0F, 1F)
                event.submitNodeStorage.submitCustomGeometry(pose, RenderTypes.textSeeThrough(texture)) { submitted, vc ->
                    val matrix = submitted.pose()
                    // Small translucent offset copies: approximate halo, not a Gaussian blur.
                    if (halo > 0F) {
                        val offsets = floatArrayOf(-1.4F, 0F, 1.4F, 0F, 0F, -1.4F, 0F, 1.4F,
                            -0.8F, -0.8F, 0.8F, -0.8F, -0.8F, 0.8F, 0.8F, 0.8F)
                        for (i in offsets.indices step 2) for ((g, glyph) in entries) {
                            quad(vc, matrix, g, glyph, offsets[i], offsets[i + 1], g.alpha * (0.12F + halo * 0.18F), options.preset)
                        }
                    }
                    for ((g, glyph) in entries) quad(vc, matrix, g, glyph, 0F, 0F, g.alpha, options.preset)
                }
            } finally {
                pose.popPose()
            }
        }
    }

    private fun quad(vc: VertexConsumer, mat: Matrix4f, g: WorldLyricsState.LyricGlyph,
                     glyph: TtfFont.Glyph, dx: Float, dy: Float, alpha: Float, preset: ModuleMusic.WorldPreset) {
        if (glyph.isEmpty() || alpha <= 0.001F) return
        val x0 = g.x + glyph.bearingX + dx
        val y0 = g.y + TtfFont.GLYPH_TOP_OFFSET + glyph.bearingY + dy
        val x1 = x0 + glyph.width
        val y1 = y0 + glyph.height
        val centerX = g.x + glyph.advance * 0.5F + dx
        val centerY = g.y + 5F + dy
        val factor = g.pop
        val rgb = when (preset) {
            ModuleMusic.WorldPreset.CLASSIC -> 0xFFFFFF
            ModuleMusic.WorldPreset.CADENZA -> if (g.tokenRgb != 0) g.tokenRgb and 0xFFFFFF else 0xF4D9FF
            ModuleMusic.WorldPreset.LIGHT -> {
                val t = g.huiguangLit.coerceIn(0f, 1f)
                val red = (155 + 100 * t).roundToInt().coerceIn(0, 255)
                val green = (190 + 55 * t).roundToInt().coerceIn(0, 255)
                (red shl 16) or (green shl 8) or 255
            }
            ModuleMusic.WorldPreset.PULSE -> 0xCCF8FF
        }
        val color = (alpha.coerceIn(0F, 1F) * 255F).roundToInt().shl(24) or rgb
        val angle = Math.toRadians(g.rot.toDouble())
        val co = cos(angle).toFloat()
        val si = sin(angle).toFloat()
        fun point(x: Float, y: Float, u: Float, v: Float) {
            val ox = (x - centerX) * factor
            val oy = (y - centerY) * factor
            vc.addVertex(mat, centerX + ox * co - oy * si + g.dx,
                centerY + ox * si + oy * co + g.dy, 0F)
                .setColor(color).setUv(u, v).setUv2(240, 240).setNormal(0F, 0F, 1F).setUv1(0, 0)
        }
        point(x0, y0, glyph.u0, glyph.v0)
        point(x0, y1, glyph.u0, glyph.v1)
        point(x1, y1, glyph.u1, glyph.v1)
        point(x1, y0, glyph.u1, glyph.v0)
    }
}
