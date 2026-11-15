package net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.FilterMode
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.logger
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.font.GlyphVector
import java.awt.font.LineMetrics
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

/**
 * TrueType renderer with a glyph atlas, ported from Onyx's `render.font.Ayg` (NeoRaven
 * `gui.onyx.render.TtfFont`).
 *
 * <p>Rasterisation, metrics and packing are kept identical to the original: glyphs are rasterised
 * through `java.awt` at up to 192 px, expressed in hmtx-independent AWT pixel bounds, padded by the
 * same 8 px margin and packed by the same 8 px-aligned shelf packer. Advances, ascent and descent
 * come from the *same* AWT call sequence, so the NeoRaven type scale produces the same widths here —
 * which is what keeps an 840×520 layout from drifting.</p>
 *
 * <p>Two deliberate differences from the GL11 original, both about the texture, neither about the
 * metrics:</p>
 * <ol>
 *   <li>The original uploaded a `GL_ALPHA` page and let the fixed-function pipeline tint it. Core
 *       profile has no single-channel-alpha format, so the page is RGBA with white RGB and coverage
 *       in alpha; the text shader samples `.a` and multiplies the vertex colour.</li>
 *   <li>The original relied on a GPU mip chain for minification (glyphs are rasterised at 4× and
 *       drawn at 1×, so the sampler blended mip 1 and 2). A `DynamicTexture` has no mip chain, so
 *       the page stores **mip level 1** — produced by the original's own `downsample()` maths —
 *       and the remaining ~1.5× minification is a plain bilinear fetch. At 9–17 px this is
 *       visually indistinguishable, halves atlas memory, and keeps every bearing/quad size
 *       computed from the level-0 raster exactly as before.</li>
 * </ol>
 */
class TtfFont private constructor(
    private val source: Font,
    val size: Float,
    supersample: Float,
    atlasForce: Int,
    private val name: String,
) {

    /** One atlas entry. */
    class Glyph(
        val u0: Float,
        val v0: Float,
        val u1: Float,
        val v1: Float,
        val bearingX: Float,
        val bearingY: Float,
        val width: Float,
        val height: Float,
        val advance: Float,
    ) {
        fun isEmpty(): Boolean = width <= 0.0F || height <= 0.0F
    }

    private val glyphs = HashMap<Char, Glyph>()
    private val advances = HashMap<Char, Float>()
    private val renderContext = FontRenderContext(null, true, true)

    /** The AWT face at raster resolution; every metric below is measured through it. */
    private val font: Font

    /** Nominal pixels per raster pixel (`size / raster`). */
    private val scale: Float

    val atlasSize: Int

    var ascent: Float = 0.0F
        private set
    var descent: Float = 0.0F
        private set

    private var image: NativeImage? = null
    private var texture: DynamicTexture? = null
    private var identifier: Identifier? = null
    private var penX = 0
    private var penY = 0
    private var rowHeight = 0
    private var full = false
    private var dirty = false

    init {
        val raster = min(size * supersample, 192.0F)
        val metrics: LineMetrics = source.deriveFont(100.0F).getLineMetrics("Ayg", renderContext)
        val nominal = metrics.ascent + metrics.descent
        val ratio = if (nominal <= 0.0F) 1.0F else 100.0F / nominal
        font = source.deriveFont(raster * ratio)
        scale = size / raster

        val pad = raster.toInt() + 16
        val heuristic = max(512, Integer.highestOneBit(pad * 12 - 1) * 2)
        atlasSize = if (atlasForce > 0) atlasForce else min(2048, heuristic)

        val capHeight = -pixelBounds('H').y * scale
        val descender = (pixelBounds('p').y + pixelBounds('p').height) * scale
        ascent = if (capHeight > 0.0F) capHeight else size * 0.72F
        descent = if (descender > 0.0F) descender else size * 0.21F
    }

    fun lineHeight(): Float = ascent + descent

    /** Width of a string at this font's nominal pixel size. */
    fun width(text: String): Float {
        var total = 0.0F
        for (i in text.indices) {
            total += advance(text[i])
        }
        return total
    }

    fun has(c: Char): Boolean = font.canDisplay(c)

    /** Measures without allocating or rasterising a glyph. */
    fun advance(c: Char): Float = advances.getOrPut(c) {
        if (font.canDisplay(c)) {
            font.createGlyphVector(renderContext, charArrayOf(c)).getGlyphMetrics(0).advanceX * scale
        } else {
            0.0F
        }
    }

    /** The atlas entry for `c`, rasterising and packing it on first use. */
    fun glyph(c: Char): Glyph = glyphs.getOrPut(c) { createGlyph(c) }

    /** Texture id of the page, registering it on first use. Null until a glyph has been packed. */
    fun textureId(): Identifier? {
        flush()
        return identifier
    }

    /** Uploads the page if glyphs were added since the last call. Call once per frame at most. */
    fun flush() {
        val tex = texture ?: return
        if (!dirty) return
        try {
            tex.upload()
            dirty = false
        } catch (t: Throwable) {
            logger.warn("[Music] Glyph atlas upload failed for '$name'", t)
        }
    }

    private fun pixelBounds(c: Char): Rectangle =
        font.createGlyphVector(renderContext, charArrayOf(c)).getPixelBounds(renderContext, 0.0F, 0.0F)

    private fun createGlyph(c: Char): Glyph {
        if (!font.canDisplay(c)) return EMPTY
        val vector: GlyphVector = font.createGlyphVector(renderContext, charArrayOf(c))
        val advance = advance(c)
        val bounds = vector.getPixelBounds(renderContext, 0.0F, 0.0F)
        if (bounds.width <= 0 || bounds.height <= 0) {
            return Glyph(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, advance)
        }

        // Level-0 raster exactly as the original drew it.
        val cellWidth = bounds.width + PADDING * 2
        val cellHeight = bounds.height + PADDING * 2
        val level0 = BufferedImage(cellWidth, cellHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics: Graphics2D = level0.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        graphics.color = Color.WHITE
        graphics.font = font
        graphics.drawGlyphVector(vector, (PADDING - bounds.x).toFloat(), (PADDING - bounds.y).toFloat())
        graphics.dispose()

        // Store mip level 1 through the original's own 2x2 average.
        // Round up so odd-sized glyph cells retain their last row/column.
        val storedWidth = max(1, (cellWidth + 1) / 2)
        val storedHeight = max(1, (cellHeight + 1) / 2)
        val coverage = alphaOf(level0)
        val stored = downsample(coverage, cellWidth, cellHeight, storedWidth, storedHeight)

        if (storedWidth > atlasSize || storedHeight > atlasSize) return NO_ATLAS
        val slot = allocate(storedWidth, storedHeight) ?: return NO_ATLAS
        blit(stored, storedWidth, storedHeight, slot[0], slot[1])

        return Glyph(
            slot[0].toFloat() / atlasSize,
            slot[1].toFloat() / atlasSize,
            (slot[0] + storedWidth).toFloat() / atlasSize,
            (slot[1] + storedHeight).toFloat() / atlasSize,
            (bounds.x - PADDING) * scale,
            (bounds.y - PADDING) * scale,
            cellWidth * scale,
            cellHeight * scale,
            advance,
        )
    }

    private fun ensurePage(): NativeImage {
        image?.let { return it }
        val page = NativeImage(atlasSize, atlasSize, true)
        image = page
        val id = Identifier.fromNamespaceAndPath(NAMESPACE, "onyx/font/$name")
        try {
            // DynamicTexture defaults to NEAREST in Minecraft 26.3; text needs a linear
            // sampler when the prefiltered atlas is minified or the GUI scale is fractional.
            val tex = object : DynamicTexture(java.util.function.Supplier { "Onyx glyph atlas $name" }, page) {
                init { sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR) }
            }
            mc.textureManager.register(id, tex)
            texture = tex
            identifier = id
            // A fully transparent page must exist in VRAM before the first sub-upload.
            dirty = true
            tex.upload()
            dirty = false
        } catch (t: Throwable) {
            logger.warn("[Music] Could not register glyph atlas '$name'", t)
        }
        return page
    }

    private fun allocate(width: Int, height: Int): IntArray? {
        if (full) return null
        val w = align(width)
        val h = align(height)
        if (penX + w > atlasSize) {
            penX = 0
            penY += rowHeight
            rowHeight = 0
        }
        if (penY + h > atlasSize) {
            full = true
            logger.warn("[Music] Glyph atlas full at ${atlasSize}px ($name)")
            return null
        }
        val slot = intArrayOf(penX, penY)
        penX += w
        rowHeight = max(rowHeight, h)
        return slot
    }

    private fun blit(coverage: ByteArray, width: Int, height: Int, x: Int, y: Int) {
        val page = ensurePage()
        for (dy in 0 until height) {
            for (dx in 0 until width) {
                val alpha = coverage[dy * width + dx].toInt() and 0xFF
                // Keep RGB white even for transparent padding. Straight-alpha interpolation
                // against transparent black otherwise darkens/softens glyph edges twice.
                // White RGB with coverage in alpha: R=G=B, so int channel order cannot matter.
                page.setPixel(x + dx, y + dy, (alpha shl 24) or 0x00FFFFFF)
            }
        }
        dirty = true
    }

    companion object {
        private const val PADDING = 8

        /**
         * The original's vertical quirk: `TtfFont.draw` places a glyph at
         * `PADDING - 1 + bearingY`, not at `bearingY`. Kept because the type scale's line boxes were
         * tuned against it; see [UI.run].
         */
        const val GLYPH_TOP_OFFSET = PADDING - 1
        private const val NAMESPACE = "liquidbounce_music"

        private val EMPTY = Glyph(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F)
        private val NO_ATLAS = Glyph(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F)

        /** Loads a TTF from the module's resource pack, falling back to a dialog face when absent. */
        fun load(resource: String, size: Float, supersample: Float = 4.0F, atlasForce: Int = 0, name: String = resource): TtfFont {
            try {
                val stream = TtfFont::class.java.getResourceAsStream(resource)
                    ?: throw IllegalStateException("Missing font $resource")
                stream.use {
                    return TtfFont(Font.createFont(Font.TRUETYPE_FONT, it), size, supersample, atlasForce, name)
                }
            } catch (t: Throwable) {
                logger.warn("[Music] Failed to load font '$resource': ${t.message}")
                return TtfFont(Font("SansSerif", Font.PLAIN, 1), size, supersample, atlasForce, "$name+fallback")
            }
        }

        /** Loads a bundled TTF; returns null instead of a stand-in, so callers can try a file next. */
        fun loadOrNull(resource: String, size: Float, supersample: Float, atlasForce: Int, name: String): TtfFont? {
            return try {
                val stream: InputStream = TtfFont::class.java.getResourceAsStream(resource) ?: return null
                stream.use {
                    TtfFont(Font.createFont(Font.TRUETYPE_FONT, it), size, supersample, atlasForce, name)
                }
            } catch (t: Throwable) {
                logger.warn("[Music] Failed to load bundled font '$resource': ${t.message}")
                null
            }
        }

        /** Loads a TTF from disk; returns null when the file cannot be read. */
        fun loadFile(file: File, size: Float, supersample: Float = 4.0F, atlasForce: Int = 0): TtfFont? {
            if (!file.isFile) return null
            return try {
                FileInputStream(file).use {
                    TtfFont(Font.createFont(Font.TRUETYPE_FONT, it), size, supersample, atlasForce, "file/${file.name}")
                }
            } catch (t: Throwable) {
                null
            }
        }

        private fun align(value: Int): Int = value + 8 - 1 and -8

        private fun alphaOf(image: BufferedImage): ByteArray {
            val pixels: IntArray = (image.raster.dataBuffer as DataBufferInt).data
            val alpha = ByteArray(pixels.size)
            for (i in pixels.indices) {
                alpha[i] = (pixels[i] ushr 24).toByte()
            }
            return alpha
        }

        /** The original's own mip downsample: 2x2 box average with clamped edges. */
        private fun downsample(source: ByteArray, width: Int, height: Int, targetWidth: Int, targetHeight: Int): ByteArray {
            val result = ByteArray(targetWidth * targetHeight)
            for (y in 0 until targetHeight) {
                val s0 = min(y * 2, height - 1)
                val s1 = min(y * 2 + 1, height - 1)
                for (x in 0 until targetWidth) {
                    val c0 = min(x * 2, width - 1)
                    val c1 = min(x * 2 + 1, width - 1)
                    val sum = (source[s0 * width + c0].toInt() and 0xFF) +
                        (source[s0 * width + c1].toInt() and 0xFF) +
                        (source[s1 * width + c0].toInt() and 0xFF) +
                        (source[s1 * width + c1].toInt() and 0xFF)
                    result[y * targetWidth + x] = (sum shr 2).toByte()
                }
            }
            return result
        }
    }
}
