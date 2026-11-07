package net.ccbluex.liquidbounce.features.module.modules.misc.music

import com.mojang.blaze3d.platform.NativeImage
import net.ccbluex.liquidbounce.render.drawBlitOnCurrentLayer
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.asTexture
import net.ccbluex.liquidbounce.utils.render.textureSetup
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import java.awt.Font
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.font.TextLayout
import java.awt.image.BufferedImage
import java.util.concurrent.Executors
import kotlin.math.ceil

/** All music text uses exactly the requested CJK TTF. Never changes the host's Material Icons.
 * Cache at a fixed raster size: GUI matrix changes do not trigger re-rasterization.
 */
object MusicFont {
    private const val PIXELS = 48f
    private const val PAD = 8
    private const val MAX_ENTRIES = 384
    private val frc = FontRenderContext(null, true, true)
    val face: Font? by lazy {
        runCatching {
        val stream = MusicFont::class.java.getResourceAsStream("/assets/liquidbounce/fonts/music-cjk-fallback.ttf")
            ?: error("Music requires /assets/liquidbounce/fonts/music-cjk-fallback.ttf")
        stream.use { Font.createFont(Font.TRUETYPE_FONT, it).deriveFont(PIXELS) }
        }.onFailure {
            failure = it.message ?: "音乐字体读取失败"
            net.ccbluex.liquidbounce.utils.client.logger.warn("[Music] $failure; no alternate font is substituted")
        }.getOrNull()
    }
    private data class Key(val text: String, val color: Int)
    private data class Image(val texture: DynamicTexture, val id: Identifier, val width: Int, val height: Int)
    private val cache = LinkedHashMap<Key, Image>(64, 0.75f, true)
    private val pending = hashSetOf<Key>()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "Music-Font").apply { isDaemon = true } }
    @Volatile private var closed = false
    @Volatile var failure = ""
        private set
    private val widths = object : LinkedHashMap<String, Float>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>?): Boolean = size > 2048
    }
    fun width(text: String, size: Float): Float {
        if (text.isEmpty()) return 0f
        val font = face ?: return 0f
        return synchronized(widths) {
            val measured = widths.getOrPut(text) { TextLayout(text, font, frc).advance }
            measured * size / PIXELS
        }
    }
    fun draw(ctx: GuiGraphicsExtractor, text: String, x: Float, y: Float, size: Float, color: Color4b) {
        if (text.isEmpty() || closed) return
        val font = face ?: return
        val key = Key(text, color.argb)
        val image = cache[key]
        if (image != null) {
            val factor = size / PIXELS
            ctx.drawBlitOnCurrentLayer(image.texture.textureSetup,
                (x - PAD * factor).toInt(), (y - PAD * factor).toInt(),
                (x + (image.width - PAD) * factor).toInt(), (y + (image.height - PAD) * factor).toInt(),
                0f, 1f, 1f, 0f)
            return
        }
        if (!pending.add(key)) return
        worker.execute {
            try {
                val layout = TextLayout(text, font, frc)
                val metrics = font.getLineMetrics(text, frc)
                val raster = BufferedImage(ceil(layout.advance).toInt() + PAD * 2,
                    ceil(metrics.height).toInt() + PAD * 2, BufferedImage.TYPE_INT_ARGB)
                val g = raster.createGraphics()
                try {
                    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                    g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
                    g.color = java.awt.Color(color.argb, true)
                    layout.draw(g, PAD.toFloat(), PAD + metrics.ascent)
                } finally { g.dispose() }
                val native = NativeImage(raster.width, raster.height, true)
                for (py in 0 until raster.height) for (px in 0 until raster.width) native.setPixel(px, py, raster.getRGB(px, py))
                val iw = raster.width; val ih = raster.height; raster.flush()
                mc.execute {
                    pending.remove(key)
                    if (closed) { native.close(); return@execute }
                    val id = Identifier.fromNamespaceAndPath("liquidbounce", "music_text_" + MusicHttp.hash("${key.color}:${key.text}"))
                    val texture = native.asTexture("Music CJK text")
                    mc.textureManager.register(id, texture)
                    cache[key] = Image(texture, id, iw, ih)
                    while (cache.size > MAX_ENTRIES) {
                        val entry = cache.entries.iterator().next()
                        cache.remove(entry.key); mc.textureManager.release(entry.value.id)
                    }
                }
            } catch (e: Exception) {
                failure = e.message?.take(160) ?: "音乐字体读取失败"
                // Keep the failed key pending; do not turn a missing font into a frame-rate retry loop.
            }
        }
    }
    fun close() {
        closed = true; worker.shutdownNow()
        mc.execute {
            cache.values.forEach { mc.textureManager.release(it.id) }
            cache.clear(); pending.clear(); synchronized(widths) { widths.clear() }
        }
    }
}
