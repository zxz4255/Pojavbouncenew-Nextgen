package net.ccbluex.liquidbounce.features.module.modules.misc.music

import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.TtfFont
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.Typeface
import net.ccbluex.liquidbounce.utils.client.logger
import java.io.File

/**
 * The music player's type scale, drawn entirely in MiSans Medium.
 *
 * <p>Ported 1:1 from NeoRaven's `music.MusicTypeface`. Everything *inside* the player UI (window,
 * lyric view, compact card) uses this one face for every language, so a single string is always a
 * single run. Mixed runs are what tears titles apart: each [TtfFont] lays its glyphs out from its own
 * em-box top, so "Google Sans English + system CJK fallback" never shares a baseline.</p>
 *
 * <p>The face is the module's single bundled font, `assets/liquidbounce/fonts/music-cjk-fallback.ttf`
 * ([Typeface.BUNDLED_FONT]); it covers Latin, Han, kana and hangul, so the player never needs a second
 * face and never splits a string into runs. Material Icons stays a separate face because those
 * codepoints are glyphs, not letters.</p>
 */
object MusicTypeface {

    private const val RASTER_SIZE = 12.0F
    private const val SUPERSAMPLE = 4.0F
    private const val ATLAS = 2048
    private const val BUNDLED = Typeface.BUNDLED_FONT
    private const val MEDIUM_FILE = "MiSans-Medium.ttf"

    private var loaded = false
    private var usable = false
    private var regular: TtfFont? = null
    private var bold: TtfFont? = null

    val DISPLAY: Typeface.Style get() = style(24.0F, 30.0F, true)
    val HEADLINE_LARGE: Typeface.Style get() = style(20.0F, 26.0F, true)
    val HEADLINE: Typeface.Style get() = style(14.0F, 18.0F, true)
    val BODY: Typeface.Style get() = style(12.0F, 16.0F, false)
    val LABEL: Typeface.Style get() = style(11.0F, 14.0F, true)
    val LABEL_TIGHT: Typeface.Style get() = style(11.0F, 14.0F, true)
    val CAPTION: Typeface.Style get() = style(10.0F, 13.0F, false)
    val MICRO: Typeface.Style get() = style(9.0F, 12.0F, true)
    val SEARCH: Typeface.Style get() = style(15.0F, 20.0F, false)
    val ROW: Typeface.Style get() = style(14.0F, 18.0F, true)
    val ROW_SUB: Typeface.Style get() = style(12.0F, 16.0F, false)
    val CARD_TITLE: Typeface.Style get() = style(12.0F, 16.0F, true)

    /*
     * The compact card's own ramp. It is one face end to end (MiSans), so the vertical centring that
     * UI.text derives from the style's ascent/descent matches the glyphs that are actually drawn —
     * mixing Google Sans Latin with MiSans CJK inside one row is what left the Han runs sitting a
     * couple of pixels low.
     */

    /** Collapsed island row: one MiSans run for every script (12px). */
    val ISLAND_ROW: Typeface.Style get() = style(12.0F, 15.0F, true)

    /** Compact card headline (lyric or 歌名): 17px. */
    val CARD_HEADLINE: Typeface.Style get() = style(17.0F, 22.0F, true)

    /** Compact card artist line: 12.5px. */
    val CARD_ARTIST: Typeface.Style get() = style(12.5F, 16.0F, false)

    /** Compact card clock/times: 10.5px. */
    val CARD_CLOCK: Typeface.Style get() = style(10.5F, 13.0F, true)

    /** Compact card: Latin in Google Sans, every other script in MiSans Medium. */
    val CARD_LYRIC: Typeface.Style get() = mixed(14.0F, 18.0F, true)
    val CARD_SUB: Typeface.Style get() = mixed(11.0F, 14.0F, true)
    val CARD_TIME: Typeface.Style get() = mixed(9.0F, 12.0F, true)

    /** Builds a style at an arbitrary size (the lyric view rescales with the font-size setting). */
    fun style(size: Float, lineHeight: Float, strong: Boolean): Typeface.Style {
        load()
        if (usable) {
            return Typeface.fromFont((if (strong) bold else regular)!!, size, lineHeight)
        }
        return Typeface.custom(if (size > 16.0F) 24.0F else 9.0F, size, lineHeight, strong)
    }

    /** Google Sans for Latin, MiSans Medium for the rest (the compact card only). */
    private fun mixed(size: Float, lineHeight: Float, strong: Boolean): Typeface.Style {
        load()
        // Same optical pick as the ClickGUI ramp: 24pt raster for 14px+ headings, 9pt below.
        val optical = if (size >= 13.0F) 24.0F else 9.0F
        if (usable) {
            return Typeface.mixed(optical, size, lineHeight, strong, if (strong) bold else regular)
        }
        return Typeface.custom(optical, size, lineHeight, strong)
    }

    @Synchronized
    fun load() {
        if (loaded) return
        loaded = true
        regular = loadMiSans()
        if (regular == null) {
            logger.info("[Music] typeface: MiSans Medium not found, falling back to a system CJK face")
            regular = loadFace(File("C:\\Windows\\Fonts", "msyh.ttc"))
        }
        if (regular == null) regular = loadFace(File("C:\\Windows\\Fonts", "Deng.ttf"))
        if (regular == null) regular = loadFace(File("C:\\Windows\\Fonts", "simhei.ttf"))
        if (regular == null) regular = loadFace(File("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"))
        if (regular == null) regular = loadFace(File("/usr/share/fonts/truetype/wqy/wqy-microhei.ttc"))
        // The player draws one weight: headings are separated by size, not by weight.
        bold = regular
        usable = regular != null
        if (!usable) {
            logger.info("[Music] typeface: no CJK face found, using the ClickGUI ramp")
        }
    }

    /** The bundled face first (the normal path), then a copy on disk for stripped builds. */
    private fun loadMiSans(): TtfFont? {
        val bundled = TtfFont.loadOrNull(BUNDLED, RASTER_SIZE, SUPERSAMPLE, ATLAS, "misans")
        if (bundled != null) {
            logger.info("[Music] typeface: MiSans Medium (bundled)")
            return bundled
        }
        val candidates = listOf(
            File("config/keystrokes/fonts/$MEDIUM_FILE"),
            File("config/fonts/$MEDIUM_FILE"),
        )
        for (file in candidates) {
            val font = loadFace(file) ?: continue
            logger.info("[Music] typeface: MiSans Medium (${file.path})")
            return font
        }
        return null
    }

    private fun loadFace(file: File): TtfFont? {
        if (!file.isFile) return null
        val font = TtfFont.loadFile(file, RASTER_SIZE, SUPERSAMPLE, ATLAS) ?: return null
        if (!(font.has('中') || font.has('\u3042') || font.has('\ud55c'))) return null
        return font
    }

    /** Whether a CJK-capable face was found (used by the debug output). */
    fun usable(): Boolean {
        load()
        return usable
    }
}
