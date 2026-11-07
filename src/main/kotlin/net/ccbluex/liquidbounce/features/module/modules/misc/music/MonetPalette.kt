package net.ccbluex.liquidbounce.features.module.modules.misc.music

import net.ccbluex.liquidbounce.render.drawCircle
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.logger
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One resolved colour scheme for a frame.
 *
 * Every role is derived from three user pickable colours (background / surface / accent) so the
 * "取色" settings stay expressive without asking the user to pick thirteen tones by hand.
 */
data class MusicScheme(
    val background: Color4b,
    val surface: Color4b,
    val surfaceHigh: Color4b,
    val surfaceHighest: Color4b,
    val primary: Color4b,
    val onPrimary: Color4b,
    val primaryContainer: Color4b,
    val secondary: Color4b,
    val tertiary: Color4b,
    val text: Color4b,
    val muted: Color4b,
    val outline: Color4b,
    val error: Color4b,
    val accent: Color4b,
)

/** Default, restrained Water Lilies / iris / willow tonal palette. */
object MonetPalette {
    private const val BACKGROUND = 0x111B20
    private const val SURFACE = 0x19262B
    private const val ACCENT = 0xB2D7C7
    private const val TEXT = 0xE5EDEB
    private const val ERROR = 0xF2B8B5

    val defaultBackground: Color4b = rgb(BACKGROUND)
    val defaultSurface: Color4b = rgb(SURFACE)
    val defaultAccent: Color4b = rgb(ACCENT)
    val defaultText: Color4b = rgb(TEXT)
    val defaultError: Color4b = rgb(ERROR)

    /** Live roles; refreshed once per frame by the HUD, the screen and the world lyric pass. */
    val background: Color4b get() = MusicTheme.current.background
    val surface: Color4b get() = MusicTheme.current.surface
    val surfaceHigh: Color4b get() = MusicTheme.current.surfaceHigh
    val surfaceHighest: Color4b get() = MusicTheme.current.surfaceHighest
    val primary: Color4b get() = MusicTheme.current.primary
    val onPrimary: Color4b get() = MusicTheme.current.onPrimary
    val primaryContainer: Color4b get() = MusicTheme.current.primaryContainer
    val secondary: Color4b get() = MusicTheme.current.secondary
    val tertiary: Color4b get() = MusicTheme.current.tertiary
    val text: Color4b get() = MusicTheme.current.text
    val muted: Color4b get() = MusicTheme.current.muted
    val outline: Color4b get() = MusicTheme.current.outline
    val error: Color4b get() = MusicTheme.current.error
    val accent: Color4b get() = MusicTheme.current.accent

    fun rgb(value: Int, alpha: Int = 255) =
        Color4b((value shr 16) and 255, (value shr 8) and 255, value and 255, alpha)

    fun mix(from: Color4b, to: Color4b, amount: Float): Color4b {
        val t = amount.coerceIn(0f, 1f)
        fun part(shift: Int): Int {
            val a = (from.argb ushr shift) and 255
            val b = (to.argb ushr shift) and 255
            return (a + (b - a) * t).roundToInt().coerceIn(0, 255)
        }
        return Color4b(part(16), part(8), part(0), part(24))
    }

    fun alpha(color: Color4b, alpha: Float): Color4b =
        Color4b(color.r, color.g, color.b, (alpha.coerceIn(0f, 1f) * 255).roundToInt())

    fun lighten(color: Color4b, amount: Float) = mix(color, Color4b.WHITE, amount)
    fun darken(color: Color4b, amount: Float) = mix(color, Color4b.BLACK, amount)

    /** Perceptual-ish luminance, used to pick a readable foreground on a picked accent. */
    fun luminance(color: Color4b): Float = (0.2126f * color.r + 0.7152f * color.g + 0.0722f * color.b) / 255f

    fun readableOn(color: Color4b): Color4b = if (luminance(color) > 0.55f) darken(color, 0.78f) else Color4b.WHITE
}

/** Builds [MusicScheme] from the module settings. Cheap enough to rebuild whenever they change. */
object MusicTheme {
    @Volatile
    private var cached: MusicScheme = compute(null)

    val current: MusicScheme get() = cached

    private var lastSettings: ModuleMusic.ThemeSettings? = null
    private var lastAccent: Color4b? = null
    fun refresh(coverAccent: Color4b?) {
        val settings = ModuleMusic.themeSettings
        if (settings == lastSettings && coverAccent == lastAccent) return
        lastSettings = settings; lastAccent = coverAccent
        cached = compute(coverAccent)
    }

    private fun compute(coverAccent: Color4b?): MusicScheme {
        val settings = ModuleMusic.themeSettings
        val background = settings.background
        val surface = settings.surface
        val picked = settings.accent
        // A cover derived accent is mixed in softly, never allowed to break text contrast.
        val accent = if (settings.useCoverAccent && coverAccent != null) {
            MonetPalette.mix(picked, coverAccent, 0.45f)
        } else {
            picked
        }
        val primary = if (MonetPalette.luminance(accent) < 0.32f) MonetPalette.lighten(accent, 0.42f) else accent
        return MusicScheme(
            background = background,
            surface = surface,
            surfaceHigh = MonetPalette.mix(surface, MonetPalette.lighten(surface, 0.55f), 0.55f),
            surfaceHighest = MonetPalette.mix(surface, MonetPalette.lighten(surface, 0.62f), 0.85f),
            primary = primary,
            onPrimary = MonetPalette.readableOn(primary),
            primaryContainer = MonetPalette.mix(surface, primary, 0.30f),
            secondary = MonetPalette.mix(primary, MonetPalette.defaultAccent, 0.45f),
            tertiary = MonetPalette.mix(primary, MonetPalette.rgb(0xE1C6AB), 0.35f),
            text = settings.text,
            muted = MonetPalette.mix(settings.text, surface, 0.42f),
            outline = MonetPalette.mix(surface, primary, 0.22f),
            error = MonetPalette.defaultError,
            accent = accent,
        )
    }
}

/** Geometry + text helpers shared by the HUD, the screen and the small widgets. */
object MusicUi {
    val DESIGN_WIDTH: Float get() = ModuleMusic.uiWidth.toFloat()
    val DESIGN_HEIGHT: Float get() = ModuleMusic.uiHeight.toFloat()

    /** Screen scale: clamps the design canvas into the window and honours the UI size setting. */
    fun fit(width: Int, height: Int, userScale: Float = 1f): Float {
        val base = min((width - 16f) / DESIGN_WIDTH, (height - 16f) / DESIGN_HEIGHT)
        val wanted = min(1f, base) * userScale.coerceIn(0.6f, 1.4f)
        // Never exceed the window: growing past `base` would push the canvas off screen.
        return min(wanted, base).coerceAtLeast(0.1f)
    }

    fun inside(x: Float, y: Float, w: Float, h: Float, mx: Double, my: Double, scale: Float, ox: Float, oy: Float): Boolean =
        mx >= ox + x * scale && mx < ox + (x + w) * scale && my >= oy + y * scale && my < oy + (y + h) * scale

    fun text(ctx: GuiGraphicsExtractor, value: String, x: Float, y: Float, size: Float = 13f,
             color: Color4b = MonetPalette.text, centered: Boolean = false, maxWidth: Float = Float.MAX_VALUE) {
        if (value.isEmpty() || size < 1f || !size.isFinite() || color.argb ushr 24 == 0) return
        // LB glyph elements retain the active GUI scissor; never submit an empty one.
        if (!scissorOk(ctx)) return
        val shown = ellipsize(value, maxWidth, size)
        val textWidth = width(shown, size)
        val left = if (centered) x - textWidth / 2f else x
        MusicFont.draw(ctx, shown, left, y, size, color)
    }

    fun width(value: String, size: Float = 13f): Float {
        if (value.isEmpty() || size <= 0f) return 0f
        return MusicFont.width(value, size)
    }

    fun ellipsize(value: String, width: Float, size: Float): String {
        if (width <= 0 || width(value, size) <= width) return value
        val points = value.codePoints().toArray()
        val builder = StringBuilder()
        for (point in points) {
            builder.appendCodePoint(point)
            if (width(builder.toString() + "…", size) > width) {
                if (builder.length >= Character.charCount(point)) {
                    builder.delete(builder.length - Character.charCount(point), builder.length)
                }
                return builder.toString().trimEnd() + "…"
            }
        }
        return value
    }

    fun wrap(value: String, width: Float, size: Float): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        value.codePoints().toArray().forEach { point ->
            val next = current.toString() + String(Character.toChars(point))
            if (current.isNotEmpty() && width(next, size) > width) {
                result += current.toString()
                current.setLength(0)
            }
            current.appendCodePoint(point)
        }
        if (current.isNotEmpty()) result += current.toString()
        return result.ifEmpty { listOf("") }
    }

    fun rect(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, radius: Float,
             color: Color4b, border: Color4b? = null) {
        if (!usable(w, h, color) || !scissorOk(ctx)) return
        ctx.drawRoundedRect(x, y, x + w, y + h, radius, color, null, border ?: Color4b.TRANSPARENT, 0.7f)
    }

    /** Degenerate or fully transparent shapes are never submitted to the GUI renderer. */
    private fun usable(w: Float, h: Float, color: Color4b): Boolean =
        w.isFinite() && h.isFinite() && w >= 0.5f && h >= 0.5f && (color.argb ushr 24) != 0

    /**
     * 26.3 stores the ACTIVE scissor on every submitted GUI element, and `GuiRenderer` throws
     * "Scissor size must be >0, was 0x0" when it meets an empty one. `ScissorStack` falls back to
     * an empty rectangle whenever an intersection ends up empty, so anything drawn while such a
     * rectangle is on the stack must be skipped.
     */
    private fun scissorOk(ctx: GuiGraphicsExtractor): Boolean {
        val scissor = ctx.scissorStack.peek() ?: return true
        if (scissor.width() > 0 && scissor.height() > 0) return true
        // Something upstream (vanilla widget, another mod, the HUD layer) pushed an empty
        // rectangle. Drawing now would submit an element carrying a 0x0 scissor and GuiRenderer
        // would kill the frame, so bail out — and leave a breadcrumb for whoever pushed it.
        if (!reportedEmptyScissor) {
            reportedEmptyScissor = true
            logger.warn("[Music] Skipped drawing under an empty GUI scissor ($scissor); this module pushes none.")
        }
        return false
    }

    private var reportedEmptyScissor = false

    /** Vertical two tone fill, used for hero surfaces and the player card. */
    fun gradientRect(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, radius: Float,
                     top: Color4b, bottom: Color4b, border: Color4b? = null) {
        if (!w.isFinite() || !h.isFinite() || w < 0.5f || h < 0.5f) return
        if ((top.argb ushr 24) == 0 && (bottom.argb ushr 24) == 0) return
        if (!scissorOk(ctx)) return
        ctx.drawRoundedRect(x, y, x + w, y + h, radius, top, bottom, border ?: Color4b.TRANSPARENT, 0.7f)
    }

    fun dot(ctx: GuiGraphicsExtractor, x: Float, y: Float, radius: Float, color: Color4b) {
        if (!radius.isFinite() || radius < 0.4f || (color.argb ushr 24) == 0 || !scissorOk(ctx)) return
        ctx.drawCircle(x, y, radius) { color.argb }
    }

    fun divider(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float) =
        rect(ctx, x, y, w, 1f, 0.5f, MonetPalette.alpha(MonetPalette.outline, 0.55f))

    fun clock(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / 1000
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    /**
     * Backdrop tint for the world lyric plane. The plane is translucent on purpose: the setting is a
     * percentage so the user can go from "barely there" to a readable card.
     */
    fun backingColor(opacityPercent: Int): Int {
        val base = MonetPalette.background
        val alpha = (opacityPercent.coerceIn(0, 100) / 100f * 255f).toInt()
        return Color4b(base.r, base.g, base.b, alpha).argb
    }

    /** Single line source badge, e.g. "网易云". */
    fun badge(ctx: GuiGraphicsExtractor, x: Float, y: Float, source: MusicSource, compact: Boolean = false) {
        val label = if (compact) source.short else source.label
        val size = if (compact) 8f else 9f
        val w = width(label, size) + 14f
        rect(ctx, x, y, w, 16f, 8f, MonetPalette.alpha(MonetPalette.mix(MonetPalette.surfaceHigh, Color4b(source.dot), 0.30f), 0.9f))
        text(ctx, label, x + w / 2f, y + 3.6f, size, MonetPalette.lighten(Color4b(source.dot), 0.35f), true, w - 6f)
    }
}
