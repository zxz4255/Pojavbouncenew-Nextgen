package net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render

import net.ccbluex.liquidbounce.utils.client.logger
import java.io.File

/**
 * The Material type scale, ported from NeoRaven's `gui.onyx.render.Typeface` (itself `theme.Util2` +
 * `render.font.Util`).
 *
 * <p>Onyx bundled Google Sans Flex at five optical sizes; only 9pt an 24pt were used, so those are
 * the two families shipped with this port. Weight and pixel size map exactly to the original
 * records, so `Typeface.BODY` here is the same 9pt-regular-at-12px face it is there.</p>
 *
 * <p>The one structural difference is drawing: the original drew straight into a GL11 matrix stack,
 * this port hands every run to a [TextSink] so the caller owns the vertex path (LiquidBounce has no
 * fixed-function pipeline to draw through). The run splitting, the per-run relative scale and the
 * baseline maths — including the original's `PADDING - 1` glyph offset — are unchanged, because those
 * are what the layout depends on.</p>
 */
object Typeface {

    /**
     * The one bundled text face for the whole module.
     *
     * <p>Onyx shipped Google Sans Flex at five optical sizes plus a separate MiSans Medium for CJK,
     * and the music player had to keep every string in a single face anyway, or the Han runs sat a
     * couple of pixels off the Latin ones (each [TtfFont] lays glyphs out from its own em-box top).
     * This build collapses all of that into one CJK-capable file, so Latin, Han, kana and hangul are
     * always one run and one baseline. It is still rasterised at two optical sizes — 9 pt for body
     * text and 24 pt for headings — which is what keeps 8–11 px text crisp; the file is the same.</p>
     */
    const val BUNDLED_FONT = "/assets/liquidbounce/fonts/music-cjk-fallback.ttf"

    private const val ICONS_PATH = "/assets/onyx/font/ttf/materialicons-regular.ttf"

    /** Material Icons glyphs (16px reference size, drawn through the UI's icon path). */
    val ICONS: TtfFont by lazy { TtfFont.load(ICONS_PATH, 16.0F, 4.0F, 2048, "materialicons") }

    private var face9: TtfFont? = null
    private var face24: TtfFont? = null

    /** Wallpaper clock: 24pt regular blown up to 42px (the atlas rasters at 96px, so it stays sharp). */
    val DISPLAY by lazy { Style(24.0F, 42.0F, 48.0F, false) }

    /** Android greeting: 24pt regular at 22px. */
    val HEADLINE_LARGE by lazy { Style(24.0F, 22.0F, 28.0F, false) }

    /** Heading / module name: 24pt medium at 14px with a 20px line box. */
    val HEADLINE by lazy { Style(24.0F, 14.0F, 20.0F, true) }

    /** Dialogue text: 24pt medium at 14px. */
    val TITLE: Style get() = HEADLINE

    /** Body text: 9pt regular at 12px, 16px line box. */
    val BODY by lazy { Style(9.0F, 12.0F, 16.0F, false) }

    /** Emphasised body text: 9pt medium at 12px. */
    val BODY_MEDIUM by lazy { Style(9.0F, 12.0F, 16.0F, true) }

    /** Labels (category names, card descriptions): 9pt medium at 11px. */
    val LABEL by lazy { Style(9.0F, 11.0F, 16.0F, true) }

    /** Tight labels (setting values): 9pt medium at 11px, 14px line box. */
    val LABEL_TIGHT by lazy { Style(9.0F, 11.0F, 14.0F, true) }

    /** Small captions: 9pt medium at 10px. */
    val CAPTION by lazy { Style(9.0F, 10.0F, 12.0F, true) }

    /** Micro text (badges): 9pt medium at 9px. */
    val MICRO by lazy { Style(9.0F, 9.0F, 12.0F, true) }

    /** Tiny text (numeric suffix): 9pt medium at 8px. */
    val TINY by lazy { Style(9.0F, 8.0F, 10.0F, true) }

    private var fallback: TtfFont? = null
    private var fallbackLoaded = false

    /** Adds an entry to the type scale for sizes the ClickGUI never used (the lyric ramp). */
    fun custom(optical: Float, size: Float, lineHeight: Float, medium: Boolean): Style =
        Style(optical, size, lineHeight, medium)

    /**
     * An entry pinned to one concrete face instead of a weight/optical pair.
     *
     * <p>Used by the music player, which draws everything in MiSans: mixing Google Sans with the CJK
     * fallback inside one string shifted the two runs vertically, because each face lays its glyphs
     * out from its own em-box top.</p>
     */
    fun fromFont(font: TtfFont, size: Float, lineHeight: Float): Style = Style(font, size, lineHeight)

    /**
     * A style that keeps Google Sans for Latin but borrows another face for everything it cannot
     * draw, before falling back to the global CJK face (the compact card only).
     */
    fun mixed(optical: Float, size: Float, lineHeight: Float, medium: Boolean, secondary: TtfFont?): Style =
        Style(optical, size, lineHeight, medium, null, secondary)

    /**
     * CJK fallback face. Google Sans Flex has no CJK coverage, so Chinese/Japanese/Korean text is
     * drawn with this face instead of rendering as blanks.
     *
     * <p>The bundled [BUNDLED_FONT] answers CJK itself, so the primary face is normally the
     * fallback as well; the chain exists for builds that strip the resource. Order: the bundled face,
     * then a copy next to the game's config folder, then the OS fonts the original listed. The first
     * candidate that actually answers `中`, `あ` or `한` wins.</p>
     */
    fun fallback(): TtfFont? {
        if (fallbackLoaded) return fallback
        fallbackLoaded = true
        TtfFont.loadOrNull(BUNDLED_FONT, 9.0F, 4.0F, 2048, "cjk9")?.let {
            if (it.has('中') || it.has('あ') || it.has('한')) {
                fallback = it
                return it
            }
        }
        val candidates = listOf(
            File("config/fonts/music-cjk-fallback.ttf"),
            File("config/fonts/MiSans-Medium.ttf"),
            File("C:\\Windows\\Fonts\\msyh.ttc"),
            File("C:\\Windows\\Fonts\\msyh.ttf"),
            File("C:\\Windows\\Fonts\\Deng.ttf"),
            File("C:\\Windows\\Fonts\\simhei.ttf"),
            File("C:\\Windows\\Fonts\\simsunb.ttf"),
            File("/Library/Fonts/Arial Unicode.ttf"),
            File("/System/Library/Fonts/Supplemental/Arial Unicode.ttf"),
            File("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"),
            File("/usr/share/fonts/truetype/wqy/wqy-microhei.ttc"),
        )
        for (file in candidates) {
            val loaded = TtfFont.loadFile(file, 9.0F, 4.0F, 2048) ?: continue
            if (loaded.has('中') || loaded.has('あ') || loaded.has('한')) {
                fallback = loaded
                logger.info("[Music] CJK fallback face: ${file.path}")
                break
            }
        }
        return fallback
    }

    private var symbols: TtfFont? = null
    private var symbolsLoaded = false

    /** Symbol fallback for dingbats the Latin face lacks (check marks, crosses). */
    fun symbols(): TtfFont? {
        if (symbolsLoaded) return symbols
        symbolsLoaded = true
        val candidates = listOf(
            File("C:\\Windows\\Fonts\\seguisym.ttf"),
            File("C:\\Windows\\Fonts\\segoeui.ttf"),
            File("C:\\Windows\\Fonts\\arialuni.ttf"),
            File("/System/Library/Fonts/Apple Symbols.ttf"),
            File("/Library/Fonts/Arial Unicode.ttf"),
            File("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
        )
        for (file in candidates) {
            val loaded = TtfFont.loadFile(file, 9.0F) ?: continue
            if (loaded.has('✔')) {
                symbols = loaded
                break
            }
        }
        return symbols
    }

    /** Picks the first font that can actually render `c`. */
    fun runFont(primary: TtfFont, c: Char): TtfFont {
        if (c == ' ' || primary.has(c)) return primary
        val cjk = fallback()
        if (cjk != null && cjk.has(c)) return cjk
        val symbol = symbols()
        if (symbol != null && symbol.has(c)) return symbol
        return primary
    }

    /**
     * The bundled face at the optical size the style asks for.
     *
     * <p>With one file there is no separate medium weight: `medium` no longer picks a different face,
     * so headings are separated by size and colour, not by stroke weight. That is a deliberate cost of
     * unifying the font, and the only place it shows is text the original set in "medium" against
     * "regular" at the same size — of the music player's own ramp, none of them.</p>
     */
    @Suppress("UNUSED_PARAMETER")
    private fun font(medium: Boolean, optical: Float): TtfFont {
        if (optical > 16.0F) {
            val cached = face24
            if (cached != null) return cached
            return TtfFont.load(BUNDLED_FONT, 24.0F, 4.0F, 2048, "cjk24").also { face24 = it }
        }
        val cached = face9
        if (cached != null) return cached
        return TtfFont.load(BUNDLED_FONT, 9.0F, 4.0F, 2048, "cjk9").also { face9 = it }
    }

    /** Receives one homogeneous run of text; the caller owns the vertex path. */
    fun interface TextSink {
        /**
         * @param font     face the run is drawn with
         * @param text     the run
         * @param x        pen position along the baseline, already in the style's pixel space
         * @param relative matrix scale applied to this run (primary.size / font.size)
         * @param color    ARGB
         */
        fun draw(font: TtfFont, text: String, x: Float, relative: Float, color: Int)
    }

    /** One entry of the type scale (Onyx's `OpticalWeightRecord`). */
    class Style internal constructor(
        private val optical: Float,
        val size: Float,
        val lineHeight: Float,
        private val medium: Boolean,
        private val explicit: TtfFont?,
        private val secondary: TtfFont?,
    ) {

        internal constructor(optical: Float, size: Float, lineHeight: Float, medium: Boolean) :
            this(optical, size, lineHeight, medium, null, null)

        internal constructor(font: TtfFont, size: Float, lineHeight: Float) :
            this(0.0F, size, lineHeight, false, font, null)

        fun font(): TtfFont = explicit ?: Typeface.font(medium, optical)

        /** The face used for one character: primary, then this style's second face, then global fallbacks. */
        private fun run(primary: TtfFont, c: Char): TtfFont {
            if (c == ' ' || primary.has(c)) return primary
            if (secondary != null && secondary.has(c)) return secondary
            return runFont(primary, c)
        }

        /** Matrix scale from the rasterised optical size to the requested pixel size. */
        fun scale(): Float = size / font().size

        fun width(text: String?): Float {
            if (text.isNullOrEmpty()) return 0.0F
            val primary = font()
            var total = 0.0F
            var i = 0
            while (i < text.length) {
                val face = run(primary, text[i])
                var j = i
                while (j < text.length && run(primary, text[j]) == face) {
                    j++
                }
                total += face.width(text.substring(i, j)) * (primary.size / face.size)
                i = j
            }
            return total * scale()
        }

        /**
         * Emits the string through [sink], splitting it into runs the way the original did: one call
         * per face, each with its own relative scale, advancing the pen by the run's width in primary
         * units.
         */
        fun draw(text: String?, color: Int, sink: TextSink) {
            if (text.isNullOrEmpty()) return
            val primary = font()
            var x = 0.0F
            var i = 0
            while (i < text.length) {
                val face = run(primary, text[i])
                var j = i
                while (j < text.length && run(primary, text[j]) == face) {
                    j++
                }
                val part = text.substring(i, j)
                val relative = primary.size / face.size
                sink.draw(face, part, x, relative, color)
                x += face.width(part) * relative
                i = j
            }
        }

        fun ascent(): Float = font().ascent * scale()

        fun descent(): Float = font().descent * scale()

        fun height(): Float = ascent() + descent()

        /** Whether every character can be rendered (primary, CJK fallback or symbol fallback). */
        fun supports(text: String?): Boolean {
            if (text.isNullOrEmpty()) return true
            val primary = font()
            for (i in text.indices) {
                val c = text[i]
                if (c == ' ') continue
                if (run(primary, c).has(c)) continue
                return false
            }
            return true
        }
    }
}
