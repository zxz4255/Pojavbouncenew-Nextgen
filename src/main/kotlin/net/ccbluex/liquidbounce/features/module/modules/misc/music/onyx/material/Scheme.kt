package net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material

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

        /** Port of `theme.Util5.getFloatForInt`: shadow spread per elevation level. */
        fun shadowForLevel(level: Int): Float {
            val levels = floatArrayOf(0.0F, 1.0F, 3.0F, 6.0F, 8.0F, 12.0F)
            return levels[Material.clamp(level, 0, 5)]
        }
    }
}
