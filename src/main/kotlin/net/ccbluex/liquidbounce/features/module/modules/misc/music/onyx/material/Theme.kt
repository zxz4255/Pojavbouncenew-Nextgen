package net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material

import kotlin.math.min

/**
 * Live theme state for the ported Material You interface, mirroring Onyx's `theme.Util4`.
 *
 * <p>The accent, background tint and interface scale are driven by the module settings (see
 * `ModuleMusic`), so the whole palette is regenerated whenever one of them changes. The cache is
 * keyed on seed + tint exactly like the original, which keeps the tone solve (it bisects in HCT
 * space, so it is not cheap) off the per-frame path.</p>
 */
object Theme {

    /** Material 3 baseline seed (also Onyx's shipped default accent: `0xFF6750A4`). */
    const val DEFAULT_ACCENT: Int = 0xFF6750A4.toInt()

    /** Seed presets offered in the module settings. */
    val ACCENTS = intArrayOf(
        0xFF6750A4.toInt(), // purple (Material baseline)
        0xFF3F5F90.toInt(), // blue
        0xFF3F6B62.toInt(), // teal
        0xFF4F6B45.toInt(), // green
        0xFF7A5C1E.toInt(), // amber
        0xFF8C4A32.toInt(), // orange
        0xFF8C4767.toInt(), // pink
        0xFF6B5A4E.toInt(), // mocha
    )

    val ACCENT_NAMES = arrayOf("Purple", "Blue", "Teal", "Green", "Amber", "Orange", "Pink", "Mocha")

    private var accent: Int = DEFAULT_ACCENT
    private var tint: Float = 0.25F
    private var scale: Float = 1.0F
    private var frost: Boolean = true
    private var followGuiScale: Boolean = true

    private var cached: Scheme? = null
    private var builtAccent: Int = 0
    private var builtTint: Float = -1.0F

    fun accent(argb: Int) {
        accent = argb or 0xFF000000.toInt()
    }

    fun accent(): Int = accent

    fun tint(value: Float) {
        tint = Material.clamp(value, 0.0F, 1.0F)
    }

    fun tint(): Float = tint

    fun scale(value: Float) {
        scale = Material.clamp(value, 0.3F, 1.5F)
    }

    fun scale(): Float = scale

    fun frost(value: Boolean) {
        frost = value
    }

    fun frost(): Boolean = frost

    /**
     * Whether the screens measure their design canvases in vanilla GUI units.
     *
     * <p>On (default): one design unit is one GUI unit, so the whole interface grows and shrinks with
     * the GUI scale option exactly like the game's own screens, and only shrinks when the window can
     * no longer hold it. Off: the canvas is stretched to fill the window at any GUI scale.</p>
     */
    fun followGuiScale(value: Boolean) {
        followGuiScale = value
    }

    fun followGuiScale(): Boolean = followGuiScale

    /**
     * Resolves the zoom a screen draws its design canvas at.
     *
     * @param fit zoom at which the design exactly fills the window
     */
    fun screenScale(fit: Float): Float = if (followGuiScale) min(fit, scale) else fit

    /** Current colour roles (rebuilt lazily when the seed changes). */
    fun scheme(): Scheme {
        val current = cached
        if (current == null || builtAccent != accent || builtTint != tint) {
            val built = Scheme.of(accent, tint)
            cached = built
            builtAccent = accent
            builtTint = tint
            return built
        }
        return current
    }

    /** `withAlpha` using design-time alpha in 0..1. */
    fun alpha(argb: Int, alpha: Float): Int = Material.withAlpha(argb, alpha)

    fun surfaceLevel(level: Int): Int = scheme().surfaceLevel(level)

    fun shadowForLevel(level: Int): Float = Scheme.shadowForLevel(level)

    /** Corner radius clamped to half of the smallest side (Onyx's `theme.Util3`). */
    fun radius(radius: Float, width: Float, height: Float): Float =
        min(radius, min(width, height) / 2.0F)

    /** Scrim colour over the (blurred) world, `0.32 * fade` alpha in Onyx. */
    fun scrim(fade: Float): Int =
        Material.withAlpha(scheme().scrim or 0xFF000000.toInt(), Material.clamp(0.32F * fade, 0.0F, 1.0F))
}
