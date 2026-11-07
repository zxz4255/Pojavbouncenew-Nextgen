package net.ccbluex.liquidbounce.features.module.modules.misc.music

import com.mojang.blaze3d.platform.NativeImage
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.drawCustomMesh
import net.ccbluex.liquidbounce.render.drawCustomMeshTextured
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.asTexture
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import java.awt.Font
import java.awt.RenderingHints
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.awt.image.BufferedImage
import java.text.AttributedString
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

/** One distance-field texture per verse. Core and halo use the SAME field in ONE shader pass.
 * No enlarged glyph copies, stacked draws, blur, atlas-neighbour sampling or line truncation.
 * Text shaping, rasterization and linear-time Euclidean distance transforms run off-thread.
 */
object MusicWorldLyrics {
    private data class Ready(val key: String, val texture: DynamicTexture, val width: Int, val height: Int,
                             val fontPixels: Float)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "Music-LyricSDF").apply { isDaemon = true } }
    private val generation = AtomicLong()
    private var requested = ""
    private var ready: Ready? = null
    private var closed = false
    @Volatile var notice = ""
        private set
    private val textureId = Identifier.fromNamespaceAndPath("liquidbounce", "music_verse_sdf")
    private val font: Font get() = MusicFont.face ?: error("缺少指定的音乐 CJK 字体")

    fun orientation(camera: net.minecraft.client.Camera) = Quaternionf(camera.rotation())
    fun anchorInFront(distance: Double): Vec3 {
        val player = net.ccbluex.liquidbounce.utils.client.player
        return player.eyePosition.add(player.lookAngle.scale(distance))
    }

    /** Stable per-verse random placement, updated from the current player/camera each frame. */
    fun followingAnchor(camera: net.minecraft.client.Camera, key: String, distance: Float, surround: Boolean): Vec3 {
        val seed = key.hashCode().toLong() xor 0x51A7E31DL
        val random = java.util.Random(seed)
        val angle = if (surround) (random.nextDouble() * 2.0 - 1.0) * Math.PI
            else (random.nextDouble() * 2.0 - 1.0) * 0.38
        val local = org.joml.Vector3f((sin(angle) * distance).toFloat(),
            ((random.nextDouble() - 0.5) * 0.7).toFloat(), (-cos(angle) * distance).toFloat())
        local.rotate(camera.rotation())
        val eye = camera.position()
        return eye.add(local.x.toDouble(), local.y.toDouble(), local.z.toDouble())
    }

    private fun request(key: String, text: String, translation: String) {
        if (closed || requested == key) return
        requested = key
        val token = generation.incrementAndGet()
        worker.execute {
            if (generation.get() != token || closed) return@execute
            try {
                val result = raster(text, translation)
                if (generation.get() != token) return@execute
                val image = result.first
                val pixels = image.width * image.height
                val coverage = FloatArray(pixels) { i -> (image.getRGB(i % image.width, i / image.width) ushr 24) / 255f }
                val inside = distances(coverage, image.width, image.height, true)
                val outside = distances(coverage, image.width, image.height, false)
                if (generation.get() != token) return@execute
                val native = NativeImage(image.width, image.height, true)
                for (i in 0 until pixels) {
                    val signed = sqrt(outside[i]) - sqrt(inside[i])
                    val edge = if (abs(signed) <= 1.5f) coverage[i] - 0.5f else 0f
                    val value = ((0.5f + (signed + edge) / 64f).coerceIn(0f, 1f) * 255f).roundToInt()
                    native.setPixel(i % image.width, i / image.width,
                        (255 shl 24) or (value shl 16) or (value shl 8) or value)
                }
                image.flush()
                mc.execute {
                    if (closed || generation.get() != token) { native.close(); return@execute }
                    mc.textureManager.release(textureId)
                    val iw = native.width; val ih = native.height
                    val texture = native.asTexture("Music verse signed distance field")
                    mc.textureManager.register(textureId, texture)
                    ready = Ready(key, texture, iw, ih, result.second)
                    notice = ""
                }
            } catch (e: Exception) { notice = e.message?.take(160) ?: "世界歌词生成失败" }
        }
    }

    private fun raster(text: String, translation: String): Pair<BufferedImage, Float> {
        var size = 48f
        val frc = java.awt.font.FontRenderContext(null, true, true)
        data class Row(val layout: TextLayout, val y: Float)
        var rows: List<Row>
        var totalHeight: Float
        var usedWidth: Float
        do {
            val built = mutableListOf<Row>()
            var y = 36f
            usedWidth = 1f
            listOf(text to size, translation to size * 0.70f).forEach { (block, px) ->
                if (block.isNotEmpty()) block.split('\n').forEach paragraph@ { paragraph ->
                    if (paragraph.isEmpty()) { y += px; return@paragraph }
                    val attributed = AttributedString(paragraph).apply { addAttribute(TextAttribute.FONT, font.deriveFont(px)) }
                    val iterator = attributed.iterator
                    val measure = LineBreakMeasurer(iterator, frc)
                    while (measure.position < iterator.endIndex) {
                        val layout = measure.nextLayout(840f)
                        y += layout.ascent
                        built += Row(layout, y)
                        usedWidth = max(usedWidth, layout.advance)
                        y += layout.descent + layout.leading + px * 0.18f
                    }
                }
            }
            rows = built
            totalHeight = y + 36f
            if (totalHeight <= 2048f) break
            size *= 0.82f
        } while (size >= 2f)
        // Full text stays present; long verses wrap and reduce physical scale instead of ellipsizing.
        val image = BufferedImage(ceil(usedWidth + 72f).toInt().coerceAtLeast(80),
            ceil(totalHeight).toInt().coerceAtLeast(80), BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            g.color = java.awt.Color.WHITE
            rows.forEach { it.layout.draw(g, (image.width - it.layout.advance) / 2f, it.y) }
        } finally { g.dispose() }
        return image to size
    }

    /** Squared distance to the chosen coverage region. Separable lower-envelope EDT, O(w*h). */
    private fun distances(alpha: FloatArray, w: Int, h: Int, toInside: Boolean): FloatArray {
        val data = FloatArray(w * h) { i -> if ((alpha[i] >= 0.5f) == toInside) 0f else 1e12f }
        val n = max(w, h)
        val f = FloatArray(n); val d = FloatArray(n); val v = IntArray(n); val z = FloatArray(n + 1)
        fun transform(length: Int) {
            var k = 0; v[0] = 0; z[0] = Float.NEGATIVE_INFINITY; z[1] = Float.POSITIVE_INFINITY
            for (q in 1 until length) {
                var cross: Float
                while (true) {
                    val p = v[k]
                    cross = ((f[q] + q.toFloat() * q) - (f[p] + p.toFloat() * p)) / (2f * (q - p))
                    if (cross > z[k] || k == 0) break
                    k--
                }
                k++; v[k] = q; z[k] = cross; z[k + 1] = Float.POSITIVE_INFINITY
            }
            k = 0
            for (q in 0 until length) {
                while (z[k + 1] < q) k++
                val delta = q - v[k]; d[q] = delta.toFloat() * delta + f[v[k]]
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) f[x] = data[y * w + x]
            transform(w)
            for (x in 0 until w) data[y * w + x] = d[x]
        }
        for (x in 0 until w) {
            for (y in 0 until h) f[y] = data[y * w + x]
            transform(h)
            for (y in 0 until h) data[y * w + x] = d[y]
        }
        return data
    }

    fun render(event: WorldRenderEvent, document: LyricDocument, index: Int, offset: Long,
               anchor: Vec3, orientation: Quaternionf, fixed: Boolean, lineHeight: Float,
               depthTest: Boolean, glow: ModuleMusic.GlowSettings, textColor: Color4b) {
        val line = document.lines.getOrNull(index) ?: return
        if (line.text.isBlank()) return
        val translation = if (ModuleMusic.showTranslation) line.translation else ""
        val key = "${document.trackKey}:$index:${line.text}:$translation"
        request(key, line.text, translation)
        val plane = ready?.takeIf { it.key == key } ?: return
        val pipeline = MusicRenderPipelines.glowQuads(!depthTest) ?: return
        val enabled = glow.enabled && glow.mode != ModuleMusic.GlowMode.OFF
        val pulse = if (glow.pulse) (sin(ModuleMusic.engine.positionMs * Math.PI / 1200) * 0.5 + 0.5).toFloat() else 0f
        val uniforms = MusicRenderPipelines.glowUniforms(if (enabled) glow.intensity * glow.mode.strength else 0f,
            glow.softness, pulse, glow.color, glow.radius * glow.mode.spread)
        val physicalScale = min(lineHeight / plane.fontPixels, min(7f / plane.width, 3.6f / plane.height))
        event.environment.apply {
            poseStack.withPush {
                val camera = event.camera.position()
                translate(anchor.x - camera.x, anchor.y - camera.y, anchor.z - camera.z)
                rotate(if (fixed) orientation else event.camera.rotation())
                scale(physicalScale, -physicalScale, physicalScale)
                val x0 = -plane.width / 2f; val x1 = plane.width / 2f
                val y0 = -plane.height / 2f; val y1 = plane.height / 2f
                if (ModuleMusic.worldBackdropOpacity > 0) {
                    drawCustomMesh(ClientRenderPipelines.quads(noDepthTest = !depthTest)) { pose ->
                        val color = MusicUi.backingColor(ModuleMusic.worldBackdropOpacity)
                        addVertex(pose, x0, y0, -0.5f).setColor(color)
                        addVertex(pose, x0, y1, -0.5f).setColor(color)
                        addVertex(pose, x1, y1, -0.5f).setColor(color)
                        addVertex(pose, x1, y0, -0.5f).setColor(color)
                    }
                }
                drawCustomMeshTextured(plane.texture, pipeline, uniforms) { pose ->
                    addVertex(pose, x0, y0, 0f).setUv(0f, 0f).setColor(textColor.argb)
                    addVertex(pose, x0, y1, 0f).setUv(0f, 1f).setColor(textColor.argb)
                    addVertex(pose, x1, y1, 0f).setUv(1f, 1f).setColor(textColor.argb)
                    addVertex(pose, x1, y0, 0f).setUv(1f, 0f).setColor(textColor.argb)
                }
            }
        }
    }
    fun close() {
        closed = true; generation.incrementAndGet(); worker.shutdownNow()
        mc.execute { mc.textureManager.release(textureId); ready = null }
    }
}
