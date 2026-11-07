package net.ccbluex.liquidbounce.features.module.modules.misc.music

import com.mojang.blaze3d.platform.NativeImage
import net.ccbluex.liquidbounce.render.drawBlitOnCurrentLayer
import net.ccbluex.liquidbounce.render.drawCircle
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.asTexture
import net.ccbluex.liquidbounce.utils.render.textureSetup
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Cover art cache.
 *
 * Search results used to be text only; every row now shows the platform artwork. Decoding happens
 * on a worker thread, the upload and every texture handle stay on the client thread, and the LRU
 * keeps at most [MAX_TEXTURES] GPU textures alive so a long result list cannot leak.
 *
 * Rounded corners are baked into the uploaded alpha channel instead of a per-element shader: the
 * GUI render state is batched by pipeline + texture, so a per-element uniform would be shared by
 * every cover of the same batch. Baking keeps one blit per cover and stays crisp at any UI scale.
 */
object MusicArtwork {
    private const val MAX_TEXTURES = 24
    private const val MAX_UPLOAD = 96

    private class Cover(val id: Identifier, val texture: DynamicTexture, val accent: Color4b?) {
        var lastUsed: Long = System.currentTimeMillis()
    }

    private val io = Executors.newFixedThreadPool(2) { r -> Thread(r, "MonetMusic-Cover").apply { isDaemon = true } }
    private val closed = AtomicBoolean()
    private val lock = Any()
    private val cache = object : LinkedHashMap<String, Cover>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cover>?): Boolean {
            if (size <= MAX_TEXTURES) return false
            val victim = eldest?.value ?: return false
            runCatching { mc.textureManager.release(victim.id) }
            return true
        }
    }
    private val pending = HashSet<String>()
    private val failed = HashMap<String, Long>()
    private val metadata = java.util.concurrent.ConcurrentHashMap<String, MusicTrack>()
    private val metadataPending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var heroUrl = ""

    private var heroKey = ""
    private var heroAccent: Color4b? = null

    fun accentFor(trackKey: String): Color4b? = if (trackKey == heroKey) heroAccent else null

    /** Marks the currently playing track; its accent drives the HUD / lyrics tint. */
    fun request(track: MusicTrack?) {
        val key = track?.key.orEmpty()
        val url = track?.artworkUrl.orEmpty()
        if (key == heroKey && url == heroUrl) return
        heroUrl = url
        heroKey = key
        heroAccent = null
        ensure(track)
        synchronized(lock) { cache[track?.artworkUrl.orEmpty()]?.let { if (key.isNotEmpty()) heroAccent = it.accent } }
    }

    fun draw(ctx: GuiGraphicsExtractor, x: Float, y: Float, size: Float, track: MusicTrack?) {
        val radius = max(4f, size * 0.26f)
        val cover = ensure(track)
        if (cover != null) {
            // Two pixel inset hides the bilinear edge bleed of the rounded alpha mask.
            val inset = 0.5f
            ctx.drawBlitOnCurrentLayer(
                cover.texture.textureSetup,
                (x + inset).toInt(), (y + inset).toInt(),
                (x + size - inset).toInt(), (y + size - inset).toInt(),
                0f, 1f, 1f, 0f,
            )
            return
        }
        // Designed placeholder: a record silhouette, never a stretched stock image.
        MusicUi.rect(ctx, x, y, size, size, radius, MonetPalette.surfaceHighest)
        val c = size / 2f
        ctx.drawCircle(x + c, y + c, size * 0.34f) { MonetPalette.onPrimary.argb }
        ctx.drawCircle(x + c, y + c, size * 0.25f) { MonetPalette.surfaceHighest.argb }
        ctx.drawCircle(x + c, y + c, size * 0.12f) { MonetPalette.primary.argb }
        ctx.drawCircle(x + c, y + c, size * 0.027f) { MonetPalette.background.argb }
    }

    /** Returns a ready cover or schedules a fetch; always safe on the render thread. */
    private fun ensure(track: MusicTrack?): Cover? {
        if (closed.get()) return null
        var candidate = track ?: return null
        if (candidate.artworkUrl.isBlank() && candidate.source == MusicSource.KUGOU) {
            metadata[candidate.key]?.let { candidate = it }
            if (candidate.artworkUrl.isBlank() && metadataPending.add(candidate.key)) {
                val captured = candidate
                io.execute {
                    runCatching { captured.copy(artworkUrl = KugouApi.artwork(captured)) }
                        .getOrNull()?.let { metadata[captured.key] = it }
                    // Negative entries are retained to avoid a render-frame request storm.
                    if (metadata.size > 160) { metadata.clear(); metadataPending.clear() }
                }
            }
        }
        val url = candidate.artworkUrl.replace("{size}", "240").replace("http://", "https://")
        if (url.isBlank()) return null
        if (candidate.key == heroKey) heroUrl = url
        synchronized(lock) {
            cache[url]?.let { it.lastUsed = System.currentTimeMillis(); return it }
            if (System.currentTimeMillis() - (failed[url] ?: 0L) < 60_000L) return null
            if (!pending.add(url)) return null
        }
        io.execute {
            var image: NativeImage? = null
            var scaled: NativeImage? = null
            try {
                val body = MusicHttp.bytes(url, cover = true, limit = 6 * 1024 * 1024)
                image = NativeImage.read(body.inputStream())
                if (image.width < 8 || image.height < 8 || image.width > 4096 || image.height > 4096) error("封面尺寸异常")
                val side = min(MAX_UPLOAD, max(image.width, image.height))
                scaled = NativeImage(side, side, true)
                val crop = min(image.width, image.height)
                image.resizeSubRectTo(
                    (image.width - crop) / 2, (image.height - crop) / 2, crop, crop, scaled,
                )
                applyRoundedCornerMask(scaled)
                val accent = sampleAccent(scaled)
                val owned = scaled
                scaled = null
                mc.execute {
                    if (closed.get()) {
                        owned.close()
                        synchronized(lock) { pending.remove(url) }
                        return@execute
                    }
                    val id = Identifier.fromNamespaceAndPath(
                        "liquidbounce",
                        "music_cover_" + MusicHttp.hash(url).take(16),
                    )
                    runCatching { mc.textureManager.release(id) }
                    val uploaded = owned.asTexture("Monet Music cover")
                    mc.textureManager.register(id, uploaded)
                    synchronized(lock) {
                        cache[url] = Cover(id, uploaded, accent)
                        pending.remove(url)
                        failed.remove(url)
                        if (heroUrl == url) heroAccent = accent
                    }
                }
            } catch (_: Exception) {
                // A missing cover is not an audio error; the record silhouette stays visible.
                synchronized(lock) { failed[url] = System.currentTimeMillis(); pending.remove(url)
                    if (failed.size > 256) failed.clear() }
            } finally {
                image?.close()
                scaled?.close()
            }
        }
        return null
    }

    /** Alpha-only rounded mask with a one pixel analytic edge, applied once at upload time. */
    private fun applyRoundedCornerMask(image: NativeImage) {
        val size = image.width
        if (size <= 0) return
        val radius = size * 0.26f
        val half = size / 2f
        for (y in 0 until size) {
            for (x in 0 until size) {
                val px = x + 0.5f - half
                val py = y + 0.5f - half
                val qx = kotlin.math.abs(px) - (half - radius)
                val qy = kotlin.math.abs(py) - (half - radius)
                val outside = sqrt(max(qx, 0f) * max(qx, 0f) + max(qy, 0f) * max(qy, 0f))
                val distance = outside + min(max(qx, qy), 0f) - radius
                val coverage = (0.5f - distance).coerceIn(0f, 1f)
                if (coverage >= 0.999f) continue
                val argb = image.getPixel(x, y)
                val alpha = ((argb ushr 24) and 255) * coverage
                image.setPixel(x, y, (argb and 0xFFFFFF) or (alpha.toInt().coerceIn(0, 255) shl 24))
            }
        }
    }

    /** Sample at most 32x32 points; prefer the most common chromatic bucket. */
    private fun sampleAccent(image: NativeImage): Color4b? {
        val weight = IntArray(512)
        val red = LongArray(512)
        val green = LongArray(512)
        val blue = LongArray(512)
        val stepsX = min(32, image.width)
        val stepsY = min(32, image.height)
        if (stepsX <= 0 || stepsY <= 0) return null
        var fallbackR = 0L
        var fallbackG = 0L
        var fallbackB = 0L
        var fallbackN = 0
        for (sy in 0 until stepsY) {
            for (sx in 0 until stepsX) {
                val x = ((sx + 0.5) * image.width / stepsX).toInt().coerceIn(0, image.width - 1)
                val y = ((sy + 0.5) * image.height / stepsY).toInt().coerceIn(0, image.height - 1)
                val argb = image.getPixel(x, y)
                if ((argb ushr 24) < 128) continue
                val r = (argb ushr 16) and 255
                val g = (argb ushr 8) and 255
                val b = argb and 255
                val hi = maxOf(r, g, b)
                val lo = minOf(r, g, b)
                if (hi < 20 || lo > 242) continue
                fallbackR += r
                fallbackG += g
                fallbackB += b
                fallbackN++
                if (hi - lo < 24) continue
                val bin = (r shr 5) * 64 + (g shr 5) * 8 + (b shr 5)
                weight[bin]++
                red[bin] += r
                green[bin] += g
                blue[bin] += b
            }
        }
        val best = weight.indices.maxByOrNull { weight[it] } ?: return null
        if (weight[best] > 0) {
            val n = weight[best]
            return Color4b((red[best] / n).toInt(), (green[best] / n).toInt(), (blue[best] / n).toInt())
        }
        return if (fallbackN > 0) {
            Color4b((fallbackR / fallbackN).toInt(), (fallbackG / fallbackN).toInt(), (fallbackB / fallbackN).toInt())
        } else {
            null
        }
    }

    fun clear() {
        synchronized(lock) {
            cache.values.forEach { runCatching { mc.textureManager.release(it.id) } }
            cache.clear()
            pending.clear()
            failed.clear()
            metadata.clear()
            metadataPending.clear()
        }
        heroKey = ""
        heroAccent = null
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        io.shutdownNow()
        if (mc.isSameThread) clear() else mc.execute { clear() }
    }
}
