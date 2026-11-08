package net.ccbluex.liquidbounce.features.module.modules.misc.music

import com.mojang.blaze3d.platform.NativeImage
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.asTexture
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Album art / avatar cache.
 *
 * <p>Ported 1:1 from NeoRaven's `music.CoverCache`: images are fetched on a small daemon pool and
 * uploaded on the render thread (texture creation must not happen on a network thread, and doing it
 * in the draw path would stall the frame for the length of a decode). [get] therefore returns null
 * for the first few frames and the caller draws the placeholder card until it resolves.</p>
 *
 * <p>One addition the original did not need: 26.3's GUI has no on-the-fly corner masking, so the
 * rounded mask is baked into the texture here — the caller's radius is part of the cache key, exactly
 * like the previous module did it. Everything else (bounded LRU, byte cache for instant re-upload,
 * dominant-hue sampling) matches the original.</p>
 */
object CoverCache {

    /** Resident texture budget: a long playlist plus the recommendation grid passes a hundred. */
    private const val MAX_TEXTURES = 256

    /** How many payloads stay around so an evicted texture can be re-uploaded instantly. */
    private const val MAX_BYTES = 512
    private const val TIMEOUT_CONNECT = 8000
    private const val TIMEOUT_READ = 12000

    private val textures = object : LinkedHashMap<String, Identifier>(16, 0.75F, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Identifier>?): Boolean {
            if (size > MAX_TEXTURES) {
                eldest?.value?.let { runCatching { mc.textureManager.release(it) } }
                return true
            }
            return false
        }
    }

    private val inFlight = HashSet<String>()
    private val failed = HashSet<String>()
    private val ready = ConcurrentHashMap<String, ByteArray>()
    private val bytes = object : LinkedHashMap<String, ByteArray>(16, 0.75F, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > MAX_BYTES
    }
    private val dominant = ConcurrentHashMap<String, Int>()

    private val pool = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "liquidbounce-covers").apply { isDaemon = true }
    }

    /** Texture for `url` at the given corner radius, or null while it is still being fetched. */
    fun get(url: String?, radius: Int = 0): Identifier? {
        if (url.isNullOrEmpty()) return null
        val key = key(url, radius)
        if (failed.contains(key)) return null
        textures[key]?.let { return it }
        val payload = ready.remove(key) ?: bytes[key]
        if (payload != null) {
            upload(key, url, radius, payload)?.let { return it }
        } else if (!inFlight.contains(key)) {
            download(key, url)
        }
        return null
    }

    private fun key(url: String, radius: Int): String = if (radius <= 0) url else "$url@$radius"

    /** Uploads any pending payload; call once per frame from the render thread before drawing. */
    fun tick() {
        // Uploads happen inline in get() so a cover never shows a one-frame hole; kept for parity.
    }

    private fun upload(key: String, url: String, radius: Int, payload: ByteArray): Identifier? {
        return try {
            var image = ImageIO.read(ByteArrayInputStream(payload))
            if (image == null) {
                failed.add(key)
                return null
            }
            if (radius > 0) image = round(image, radius)
            val id = location(key)
            val native = toNativeImage(image)
            val texture: DynamicTexture = native.asTexture("Music cover")
            mc.textureManager.register(id, texture)
            textures[key] = id
            bytes[key] = payload
            val accent = computeDominant(image)
            if (accent != 0) dominant[key] = accent
            id
        } catch (t: Throwable) {
            failed.add(key)
            logger.warn("[Music] Cover upload failed for $url", t)
            null
        }
    }

    private fun location(key: String): Identifier =
        Identifier.fromNamespaceAndPath("liquidbounce_music", "cover/" + Integer.toHexString(key.hashCode()))

    private fun download(key: String, url: String) {
        inFlight.add(key)
        pool.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = TIMEOUT_CONNECT
                connection.readTimeout = TIMEOUT_READ
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                connection.setRequestProperty("Referer", "https://music.163.com")
                val stream = connection.inputStream
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val read = stream.read(chunk)
                    if (read <= 0) break
                    buffer.write(chunk, 0, read)
                }
                stream.close()
                ready[key] = buffer.toByteArray()
            } catch (t: Throwable) {
                failed.add(key)
            } finally {
                connection?.disconnect()
                inFlight.remove(key)
            }
        }
    }

    /** Stats used by the debug HUD: how many textures are resident. */
    fun resident(): Int = textures.size

    /** Accent colour sampled from the artwork, or 0 while the image is not resident. */
    fun dominant(url: String?): Int {
        if (url.isNullOrEmpty()) return 0
        return dominant[url] ?: dominant[key(url, 0)] ?: 0
    }

    /** Same accent lookup for a radius-qualified entry. */
    fun dominant(url: String?, radius: Int): Int {
        if (url.isNullOrEmpty()) return 0
        return dominant[key(url, radius)] ?: dominant[url] ?: 0
    }

    /**
     * Picks a usable accent from the artwork.
     *
     * <p>A plain average turns every cover grey, so this takes the circular mean of the hue weighted
     * by saturation, ignores near-neutral pixels (shadows, text, frames) and finally asks for a
     * mid-tone of that hue: the Material palette needs a chromatic seed, not the average brightness.</p>
     */
    private fun computeDominant(image: BufferedImage): Int {
        return try {
            val stepX = max(1, image.width / 32)
            val stepY = max(1, image.height / 32)
            var sinSum = 0.0
            var cosSum = 0.0
            var weight = 0.0
            var y = 0
            while (y < image.height) {
                var x = 0
                while (x < image.width) {
                    val rgb = image.getRGB(x, y)
                    val hsb = Color.RGBtoHSB((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, null)
                    if (hsb[1] >= 0.14F && hsb[2] >= 0.10F) {
                        val w = hsb[1] * (0.35 + hsb[2])
                        val angle = hsb[0] * Math.PI * 2.0
                        sinSum += sin(angle) * w
                        cosSum += cos(angle) * w
                        weight += w
                    }
                    x += stepX
                }
                y += stepY
            }
            if (weight < 0.75) return 0
            var hue = atan2(sinSum, cosSum) / (Math.PI * 2.0)
            if (hue < 0.0) hue += 1.0
            val argb = Color.HSBtoRGB(hue.toFloat(), 0.62F, 0.78F)
            0xFF000000.toInt() or (argb and 0xFFFFFF)
        } catch (t: Throwable) {
            0
        }
    }

    /** Bakes an anti-aliased rounded alpha mask (the shader the original used does not exist here). */
    private fun round(source: BufferedImage, radius: Int): BufferedImage {
        val w = source.width
        val h = source.height
        val r = radius.coerceIn(0, minOf(w, h) / 2)
        if (r <= 0) return source
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.composite = AlphaComposite.Src
        g.clip = RoundRectangle2D.Float(0f, 0f, w.toFloat(), h.toFloat(), r * 2f, r * 2f)
        g.drawImage(source, 0, 0, null)
        g.dispose()
        return out
    }

    /**
     * BufferedImage to NativeImage.
     *
     * <p>`NativeImage.setPixel` takes ABGR, so the channels are swapped here rather than left to
     * chance — a wrong order turns every album cover blue.</p>
     */
    private fun toNativeImage(image: BufferedImage): NativeImage {
        val width = image.width
        val height = image.height
        val native = NativeImage(width, height, true)
        val pixels = IntArray(width * height)
        image.getRGB(0, 0, width, height, pixels, 0, width)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val argb = pixels[y * width + x]
                val a = (argb ushr 24) and 0xFF
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                native.setPixel(x, y, (a shl 24) or (b shl 16) or (g shl 8) or r)
            }
        }
        return native
    }
}
