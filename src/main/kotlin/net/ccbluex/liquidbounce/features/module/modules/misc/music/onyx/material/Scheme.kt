package net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material

import kotlin.math.pow

/**
 * Material 3 colour roles, generated from a single accent seed exactly like the Onyx ClickGUI did
 * (`theme.PrimaryOnPrimaryRecord`).
 *
 * <p>The scheme is always the dark variant: surfaces sit between tone 4 and 24, the accent roles
 * between tone 20 and 90. `tint` controls how much accent colour bleeds into the neutral (surface)
 * palettes, `neutralCut`/`surfaceCut` desaturate very bright or very dim seeds so the seed stays
 * readable. Every role name matches the Material 3 spec, so the screen code reads like the original
 * NeoRaven screen code.</p>
 */
class Scheme(
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondary: Int,
    val onSecondary: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    val tertiary: Int,
    val onTertiary: Int,
    val tertiaryContainer: Int,
    val onTertiaryContainer: Int,
    val error: Int,
    val onError: Int,
    val errorContainer: Int,
    val onErrorContainer: Int,
    val surface: Int,
    val onSurface: Int,
    val surfaceVariant: Int,
    val onSurfaceVariant: Int,
    val surfaceDim: Int,
    val surfaceBright: Int,
    val surfaceContainerLowest: Int,
    val surfaceContainerLow: Int,
    val surfaceContainer: Int,
    val surfaceContainerHigh: Int,
    val surfaceContainerHighest: Int,
    val outline: Int,
    val outlineVariant: Int,
    val inverseSurface: Int,
    val inverseOnSurface: Int,
    val inversePrimary: Int,
    val scrim: Int,
    val shadow: Int,
) {

    /** Port of `theme.Util5.getIntForInt`: Material elevation levels 0..5. */
    fun surfaceLevel(level: Int): Int = when (Material.clamp(level, 0, 5)) {
        1 -> surfaceContainerLow
        2 -> surfaceContainer
        3 -> surfaceContainerHigh
        4, 5 -> surfaceContainerHighest
        else -> surface
    }

    companion object {

        /**
         * @param accent seed colour (alpha ignored)
         * @param tint   0..1, how much accent the neutral surfaces keep
         */
        fun of(accent: Int, tint: Float): Scheme {
            val hsv = Material.hsv(accent)
            val base = Material.Hct.fromInt(Material.fromHsv(hsv[0], hsv[1], 1.0F, 255))

            val hue = base.hue
            val chroma = base.chroma
            val accentStrength = Material.clamp(tint, 0.0F, 1.0F) * Material.clamp(hsv[1], 0.0F, 1.0F)
            val neutralCut = 30.0 * (1.0 - Material.clamp(hsv[2], 0.0F, 1.0F))
            val surfaceCut = (1.0 - Material.clamp(hsv[2], 0.0F, 1.0F)) * 50.0

            val primary = Material.TonalPalette.of(hue, chroma)
            val secondary = Material.TonalPalette.of(hue, chroma / 3.0)
            val tertiary = Material.TonalPalette.of(hue + 60.0, chroma / 2.0)
            val neutral = Material.TonalPalette.of(hue, 36.0 * accentStrength)
            val neutralVariant = Material.TonalPalette.of(hue, 56.0 * accentStrength)
            val neutralBright = Material.TonalPalette.of(hue, 64.0 * accentStrength)
            val error = Material.TonalPalette.of(25.0, 84.0)

            return Scheme(
                primary.tone(80.0, neutralCut),
                primary.tone(20.0),
                primary.tone(30.0, neutralCut),
                primary.tone(90.0),
                secondary.tone(80.0, neutralCut),
                secondary.tone(20.0),
                secondary.tone(30.0, neutralCut),
                secondary.tone(90.0),
                tertiary.tone(80.0, neutralCut),
                tertiary.tone(20.0),
                tertiary.tone(30.0, neutralCut),
                tertiary.tone(90.0),
                error.tone(80.0),
                error.tone(20.0),
                error.tone(30.0),
                error.tone(90.0),
                neutralVariant.tone(6.0, surfaceCut),
                neutral.tone(90.0),
                neutralBright.tone(30.0, surfaceCut),
                neutralBright.tone(80.0),
                neutralVariant.tone(6.0, surfaceCut),
                neutralVariant.tone(24.0, surfaceCut),
                neutralVariant.tone(4.0, surfaceCut),
                neutralVariant.tone(10.0, surfaceCut),
                neutralVariant.tone(12.0, surfaceCut),
                neutralVariant.tone(17.0, surfaceCut),
                neutralVariant.tone(22.0, surfaceCut),
                neutralBright.tone(60.0, surfaceCut),
                neutralBright.tone(30.0, surfaceCut),
                neutral.tone(90.0),
                neutral.tone(20.0),
                primary.tone(40.0, neutralCut),
                neutral.tone(0.0),
                neutral.tone(0.0),
            )
        }

        /** Light Material roles, including text/controls, not just a pale background. */
        fun light(accent: Int): Scheme {
            val hsv = Material.hsv(accent)
            val base = Material.Hct.fromInt(Material.fromHsv(hsv[0], hsv[1], 1.0F, 255))
            val p = Material.TonalPalette.of(base.hue, base.chroma)
            val s = Material.TonalPalette.of(base.hue, base.chroma / 3.0)
            val t = Material.TonalPalette.of(base.hue + 60.0, base.chroma / 2.0)
            val n = Material.TonalPalette.of(base.hue, 6.0)
            val nv = Material.TonalPalette.of(base.hue, 12.0)
            val e = Material.TonalPalette.of(25.0, 84.0)
            return Scheme(
                p.tone(40.0), p.tone(100.0), p.tone(90.0), p.tone(10.0),
                s.tone(40.0), s.tone(100.0), s.tone(90.0), s.tone(10.0),
                t.tone(40.0), t.tone(100.0), t.tone(90.0), t.tone(10.0),
                e.tone(40.0), e.tone(100.0), e.tone(90.0), e.tone(10.0),
                n.tone(98.0), n.tone(10.0), nv.tone(90.0), nv.tone(30.0),
                n.tone(87.0), n.tone(98.0), n.tone(100.0), n.tone(96.0),
                n.tone(94.0), n.tone(92.0), n.tone(90.0), nv.tone(50.0),
                nv.tone(80.0), n.tone(20.0), n.tone(95.0), p.tone(80.0),
                n.tone(0.0), n.tone(0.0),
            )
        }

        private fun luminance(c: Int): Double {
            fun linear(v: Int): Double {
                val x = v / 255.0
                return if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
            }
            return .2126 * linear(Material.red(c)) + .7152 * linear(Material.green(c)) + .0722 * linear(Material.blue(c))
        }

        private fun readable(foreground: Int, background: Int, minimum: Double): Int {
            val a = luminance(foreground)
            val b = luminance(background)
            val contrast = (maxOf(a, b) + .05) / (minOf(a, b) + .05)
            if (contrast >= minimum) return foreground
            return if ((1.05 / (b + .05)) >= ((b + .05) / .05)) 0xffffffff.toInt() else 0xff000000.toInt()
        }

        /** Blend role colours; protect legibility on a light/dark transition. */
        fun blend(from: Scheme, to: Scheme, t: Float, protectContrast: Boolean = false): Scheme {
            val raw = Scheme(
                Material.mix(from.primary, to.primary, t),
                Material.mix(from.onPrimary, to.onPrimary, t),
                Material.mix(from.primaryContainer, to.primaryContainer, t),
                Material.mix(from.onPrimaryContainer, to.onPrimaryContainer, t),
                Material.mix(from.secondary, to.secondary, t),
                Material.mix(from.onSecondary, to.onSecondary, t),
                Material.mix(from.secondaryContainer, to.secondaryContainer, t),
                Material.mix(from.onSecondaryContainer, to.onSecondaryContainer, t),
                Material.mix(from.tertiary, to.tertiary, t),
                Material.mix(from.onTertiary, to.onTertiary, t),
                Material.mix(from.tertiaryContainer, to.tertiaryContainer, t),
                Material.mix(from.onTertiaryContainer, to.onTertiaryContainer, t),
                Material.mix(from.error, to.error, t),
                Material.mix(from.onError, to.onError, t),
                Material.mix(from.errorContainer, to.errorContainer, t),
                Material.mix(from.onErrorContainer, to.onErrorContainer, t),
                Material.mix(from.surface, to.surface, t),
                Material.mix(from.onSurface, to.onSurface, t),
                Material.mix(from.surfaceVariant, to.surfaceVariant, t),
                Material.mix(from.onSurfaceVariant, to.onSurfaceVariant, t),
                Material.mix(from.surfaceDim, to.surfaceDim, t),
                Material.mix(from.surfaceBright, to.surfaceBright, t),
                Material.mix(from.surfaceContainerLowest, to.surfaceContainerLowest, t),
                Material.mix(from.surfaceContainerLow, to.surfaceContainerLow, t),
                Material.mix(from.surfaceContainer, to.surfaceContainer, t),
                Material.mix(from.surfaceContainerHigh, to.surfaceContainerHigh, t),
                Material.mix(from.surfaceContainerHighest, to.surfaceContainerHighest, t),
                Material.mix(from.outline, to.outline, t),
                Material.mix(from.outlineVariant, to.outlineVariant, t),
                Material.mix(from.inverseSurface, to.inverseSurface, t),
                Material.mix(from.inverseOnSurface, to.inverseOnSurface, t),
                Material.mix(from.inversePrimary, to.inversePrimary, t),
                Material.mix(from.scrim, to.scrim, t),
                Material.mix(from.shadow, to.shadow, t)
            )
            if (!protectContrast) return raw
            val accent = readable(raw.primary, raw.surfaceContainer, 3.0)
            val secondary = readable(raw.secondary, raw.surfaceContainer, 3.0)
            val tertiary = readable(raw.tertiary, raw.surfaceContainer, 3.0)
            val error = readable(raw.error, raw.surfaceContainer, 3.0)
            return Scheme(
                accent,
                readable(raw.onPrimary, accent, 4.5),
                raw.primaryContainer,
                readable(raw.onPrimaryContainer, raw.primaryContainer, 4.5),
                secondary,
                readable(raw.onSecondary, secondary, 4.5),
                raw.secondaryContainer,
                readable(raw.onSecondaryContainer, raw.secondaryContainer, 4.5),
                tertiary,
                readable(raw.onTertiary, tertiary, 4.5),
                raw.tertiaryContainer,
                readable(raw.onTertiaryContainer, raw.tertiaryContainer, 4.5),
                error,
                readable(raw.onError, error, 4.5),
                raw.errorContainer,
                readable(raw.onErrorContainer, raw.errorContainer, 4.5),
                raw.surface,
                readable(raw.onSurface, raw.surfaceContainer, 4.5),
                raw.surfaceVariant,
                readable(raw.onSurfaceVariant, raw.surfaceContainerHigh, 4.5),
                raw.surfaceDim,
                raw.surfaceBright,
                raw.surfaceContainerLowest,
                raw.surfaceContainerLow,
                raw.surfaceContainer,
                raw.surfaceContainerHigh,
                raw.surfaceContainerHighest,
                raw.outline,
                raw.outlineVariant,
                raw.inverseSurface,
                raw.inverseOnSurface,
                raw.inversePrimary,
                raw.scrim,
                raw.shadow
            )
        }

        /** Port of `theme.Util5.getFloatForInt`: shadow spread per elevation level. */
        fun shadowForLevel(level: Int): Float {
            val levels = floatArrayOf(0.0F, 1.0F, 3.0F, 6.0F, 8.0F, 12.0F)
            return levels[Material.clamp(level, 0, 5)]
        }
    }
}
