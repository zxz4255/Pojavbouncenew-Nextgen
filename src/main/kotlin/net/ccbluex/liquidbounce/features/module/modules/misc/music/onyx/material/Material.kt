package net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Material You colour engine: HCT (CAM16 hue + chroma, L* tone), tonal palettes and ARGB helpers.
 *
 * <p>One to one port of NeoRaven's `xyz.daikirai.gui.onyx.material.Material` (itself a port of
 * OpenOnyx's `client.onyx.theme` package). The maths is bit-for-bit the original: every constant,
 * every clamp and even the bisection tolerances are kept, so the palette a seed produces here is the
 * exact palette the NeoRaven ClickGUI and music player produced.</p>
 *
 * <p>Nothing in this file touches Minecraft, so the whole colour engine is unit-testable and is
 * verified against the Java original by a golden-value harness.</p>
 */
object Material {

    /** D65 white point, as used by the original implementation. */
    val WHITE_POINT_D65 = doubleArrayOf(95.047, 100.0, 108.883)

    /** sRGB (0..100) to linear RGB (0..100). */
    private val SRGB_TO_LINEAR = arrayOf(
        doubleArrayOf(0.41233895, 0.35762064, 0.18051042),
        doubleArrayOf(0.2126, 0.7152, 0.0722),
        doubleArrayOf(0.01932141, 0.11916382, 0.95034478),
    )

    /** Linear RGB (0..100) to sRGB (0..100). */
    private val LINEAR_TO_SRGB = arrayOf(
        doubleArrayOf(3.2413774792388685, -1.5376652402851851, -0.49885366846268053),
        doubleArrayOf(-0.9691452513005321, 1.8758853451067872, 0.04156585616912061),
        doubleArrayOf(0.05562093689691305, -0.20395524564742123, 1.0571799111220335),
    )

    // ---------------------------------------------------------------- ARGB

    fun alpha(argb: Int): Int = argb ushr 24

    fun red(argb: Int): Int = argb shr 16 and 0xFF

    fun green(argb: Int): Int = argb shr 8 and 0xFF

    fun blue(argb: Int): Int = argb and 0xFF

    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a and 0xFF shl 24) or (r and 0xFF shl 16) or (g and 0xFF shl 8) or (b and 0xFF)

    fun rgb(r: Int, g: Int, b: Int): Int = argb(255, r, g, b)

    /** `#RRGGBB` form of a colour, handy for logs and tooltips. */
    fun hex(argb: Int): String = String.format("#%06X", argb and 0xFFFFFF)

    fun withAlpha(argb: Int, alpha: Float): Int =
        argb and 0xFFFFFF or (clamp((alpha * 255.0F).roundToInt(), 0, 255) shl 24)

    fun withAlpha(argb: Int, alpha: Int): Int =
        argb and 0xFFFFFF or (clamp(alpha, 0, 255) shl 24)

    fun scaleAlpha(argb: Int, factor: Float): Int =
        withAlpha(argb, (alpha(argb) * clamp(factor, 0.0F, 1.0F)).roundToInt())

    /** Linear-RGB blend of two colours, matching `misc.Cls2.getIntForInt`. */
    fun mix(from: Int, to: Int, tRaw: Float): Int {
        val t = clamp(tRaw, 0.0F, 1.0F)
        val a = linearRgb(from)
        val b = linearRgb(to)
        val r = srgbChannel(a[0] + (b[0] - a[0]) * t)
        val g = srgbChannel(a[1] + (b[1] - a[1]) * t)
        val bl = srgbChannel(a[2] + (b[2] - a[2]) * t)
        val al = (alpha(from) + (alpha(to) - alpha(from)) * t).roundToInt()
        return argb(al, r, g, bl)
    }

    // ------------------------------------------------------------- numbers

    fun clamp(v: Double, lo: Double, hi: Double): Double = if (v < lo) lo else if (v > hi) hi else v

    fun clamp(v: Float, lo: Float, hi: Float): Float = if (v < lo) lo else if (v > hi) hi else v

    fun clamp(v: Int, lo: Int, hi: Int): Int = if (v < lo) lo else if (v > hi) hi else v

    fun clamp(v: Long, lo: Long, hi: Long): Long = if (v < lo) lo else if (v > hi) hi else v

    fun sanitizeDegrees(degrees: Double): Double {
        val d = degrees % 360.0
        return if (d < 0.0) d + 360.0 else d
    }

    /** sRGB channel byte to linear RGB (0..100). */
    fun linearize(channel: Int): Double {
        val v = channel / 255.0
        return if (v <= 0.040449936) v / 12.92 * 100.0 else ((v + 0.055) / 1.055).pow(2.4) * 100.0
    }

    /** Linear RGB (0..100) back to an sRGB channel byte. */
    fun srgbChannel(linear: Double): Int {
        val v = linear / 100.0
        val srgb = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(0.4166666666666667) - 0.055
        return clamp((srgb * 255.0).roundToInt(), 0, 255)
    }

    /** ARGB to linear RGB (0..100), matching `theme.impl.Util2.getDoubleArrayForInt`. */
    fun linearRgb(argb: Int): DoubleArray {
        val r = linearize(red(argb))
        val g = linearize(green(argb))
        val b = linearize(blue(argb))
        return doubleArrayOf(
            SRGB_TO_LINEAR[0][0] * r + SRGB_TO_LINEAR[0][1] * g + SRGB_TO_LINEAR[0][2] * b,
            SRGB_TO_LINEAR[1][0] * r + SRGB_TO_LINEAR[1][1] * g + SRGB_TO_LINEAR[1][2] * b,
            SRGB_TO_LINEAR[2][0] * r + SRGB_TO_LINEAR[2][1] * g + SRGB_TO_LINEAR[2][2] * b,
        )
    }

    /** Linear RGB (0..100) to ARGB. */
    fun fromLinearRgb(r: Double, g: Double, b: Double): Int = argb(
        255,
        srgbChannel(LINEAR_TO_SRGB[0][0] * r + LINEAR_TO_SRGB[0][1] * g + LINEAR_TO_SRGB[0][2] * b),
        srgbChannel(LINEAR_TO_SRGB[1][0] * r + LINEAR_TO_SRGB[1][1] * g + LINEAR_TO_SRGB[1][2] * b),
        srgbChannel(LINEAR_TO_SRGB[2][0] * r + LINEAR_TO_SRGB[2][1] * g + LINEAR_TO_SRGB[2][2] * b),
    )

    /** L* of a Y value (0..100). */
    fun lstarFromY(y: Double): Double {
        val yn = y / 100.0
        return if (yn > 0.008856451679035631) cbrt(yn) * 116.0 - 16.0 else 903.2962962962963 * yn
    }

    /** Y (0..100) of an L* value. */
    fun yFromLstar(lstar: Double): Double {
        val unit = lstar / 100.0
        val y = unit * unit * unit
        return 100.0 * if (y > 0.008856451679035631) y else (116.0 * unit - 16.0) / 903.2962962962963
    }

    /** L* of an ARGB colour. */
    fun lstarFromArgb(argb: Int): Double {
        val y = 0.2126 * linearize(red(argb)) + 0.7152 * linearize(green(argb)) + 0.0722 * linearize(blue(argb))
        return lstarFromY(y)
    }

    /** Neutral grey with the given L*. */
    fun argbFromLstar(lstar: Double): Int {
        val v = srgbChannel(yFromLstar(lstar))
        return argb(255, v, v, v)
    }

    // ----------------------------------------------------------------- HSV

    /** Returns {hue 0..360, saturation 0..1, value 0..1}. */
    fun hsv(argb: Int): FloatArray {
        val r = red(argb) / 255.0F
        val g = green(argb) / 255.0F
        val b = blue(argb) / 255.0F
        val max = max(r, max(g, b))
        val min = min(r, min(g, b))
        val delta = max - min
        var hue = 0.0F
        if (delta > 0.0F) {
            hue = when {
                max == r -> 60.0F * ((g - b) / delta % 6.0F)
                max == g -> 60.0F * ((b - r) / delta + 2.0F)
                else -> 60.0F * ((r - g) / delta + 4.0F)
            }
        }
        val saturation = if (max == 0.0F) 0.0F else delta / max
        return floatArrayOf(sanitizeDegrees(hue.toDouble()).toFloat(), saturation, max)
    }

    /** HSV (hue degrees, 0..1 saturation/value) to ARGB. */
    fun fromHsv(hue: Float, saturation: Float, value: Float, alpha: Int): Int {
        val h = sanitizeDegrees(hue.toDouble()).toFloat() / 60.0F
        val chroma = value * clamp(saturation, 0.0F, 1.0F)
        val x = chroma * (1.0F - abs(h % 2.0F - 1.0F))
        val m = value - chroma
        val r: Float
        val g: Float
        val b: Float
        when (h.toInt() % 6) {
            0 -> { r = chroma; g = x; b = 0.0F }
            1 -> { r = x; g = chroma; b = 0.0F }
            2 -> { r = 0.0F; g = chroma; b = x }
            3 -> { r = 0.0F; g = x; b = chroma }
            4 -> { r = x; g = 0.0F; b = chroma }
            else -> { r = chroma; g = 0.0F; b = x }
        }
        return argb(alpha, ((r + m) * 255.0F).roundToInt(), ((g + m) * 255.0F).roundToInt(), ((b + m) * 255.0F).roundToInt())
    }

    // ------------------------------------------------- viewing conditions

    /** Port of `theme.impl.Util`. */
    class ViewingConditions private constructor(
        /** "n" (chroma scaling). */
        val n: Double,
        /** "aw" (achromatic response of white). */
        val aw: Double,
        /** "nbb". */
        val nbb: Double,
        /** "c" (surround impact). */
        val c: Double,
        /** "nc" (chroma induction). */
        val nc: Double,
        /** "fl" (luminance level adaptation factor). */
        val fl: Double,
        /** pow(fl, 0.25). */
        val flRoot: Double,
        /** "z" (base exponential nonlinearity). */
        val z: Double,
        val rgbD: DoubleArray,
    ) {
        companion object {
            @JvmField
            val DEFAULT: ViewingConditions = create(
                WHITE_POINT_D65,
                63.66197723675813 * yFromLstar(50.0) / 100.0,
                50.0,
                2.0,
                false,
            )

            fun create(
                whitePoint: DoubleArray,
                adaptingLuminance: Double,
                backgroundLstar: Double,
                surround: Double,
                discountingIlluminant: Boolean,
            ): ViewingConditions {
                val rW = whitePoint[0] * 0.401288 + whitePoint[1] * 0.650173 + whitePoint[2] * -0.051461
                val gW = whitePoint[0] * -0.250268 + whitePoint[1] * 1.204414 + whitePoint[2] * 0.045854
                val bW = whitePoint[0] * -0.002079 + whitePoint[1] * 0.048952 + whitePoint[2] * 0.953127

                val f = 0.8 + surround / 10.0
                val c = if (f >= 0.9) lerp(0.59, 0.69, (f - 0.9) * 10.0) else lerp(0.525, 0.59, (f - 0.8) * 10.0)

                var d = if (discountingIlluminant) {
                    1.0
                } else {
                    f * (1.0 - 0.2777777777777778 * exp((-adaptingLuminance - 42.0) / 92.0))
                }
                d = clamp(d, 0.0, 1.0)

                val rgbD = doubleArrayOf(
                    d * (100.0 / rW) + 1.0 - d,
                    d * (100.0 / gW) + 1.0 - d,
                    d * (100.0 / bW) + 1.0 - d,
                )

                val k = 1.0 / (5.0 * adaptingLuminance + 1.0)
                val k4 = k * k * k * k
                val fl = k4 * adaptingLuminance + 0.1 * (1.0 - k4) * (1.0 - k4) * cbrt(5.0 * adaptingLuminance)

                val n = yFromLstar(backgroundLstar) / whitePoint[1]
                val z = 1.48 + sqrt(n)
                val nbb = 0.725 / n.pow(0.2)

                val rgbAFactors = doubleArrayOf(
                    (fl * rgbD[0] * rW / 100.0).pow(0.42),
                    (fl * rgbD[1] * gW / 100.0).pow(0.42),
                    (fl * rgbD[2] * bW / 100.0).pow(0.42),
                )
                val rgbA = doubleArrayOf(
                    400.0 * rgbAFactors[0] / (rgbAFactors[0] + 27.13),
                    400.0 * rgbAFactors[1] / (rgbAFactors[1] + 27.13),
                    400.0 * rgbAFactors[2] / (rgbAFactors[2] + 27.13),
                )
                val aw = (2.0 * rgbA[0] + rgbA[1] + 0.05 * rgbA[2]) * nbb

                return ViewingConditions(n, aw, nbb, c, f, fl, fl.pow(0.25), z, rgbD)
            }

            private fun lerp(start: Double, stop: Double, amount: Double): Double =
                (1.0 - amount) * start + amount * stop
        }
    }

    // --------------------------------------------------------------- HCT

    /** Port of `theme.impl.Cls3`. */
    class Hct private constructor(
        val hue: Double,
        val chroma: Double,
        val tone: Double,
        private val t: Double,
        private val alphaC: Double,
        private val alphaS: Double,
    ) {
        companion object {
            /** Creates a point in HCT space for a given tone / chroma / hue (a CAM16 guess). */
            fun of(tone: Double, chroma: Double, hue: Double): Hct {
                val t = 1.7 * tone / (1.0 + 0.007 * tone)
                val scaled = 43.859649122807014 * ln1p(0.0228 * chroma)
                val hueRad = Math.toRadians(hue)
                return Hct(hue, chroma, tone, t, scaled * cos(hueRad), scaled * sin(hueRad))
            }

            fun fromInt(argb: Int): Hct = fromLinearRgb(linearRgb(argb), ViewingConditions.DEFAULT)

            /** Port of `Cls3.getCls3ForInt`. */
            fun fromLinearRgb(rgb: DoubleArray, vc: ViewingConditions): Hct {
                val r = rgb[0]
                val g = rgb[1]
                val b = rgb[2]

                var x = 0.401288 * r + 0.650173 * g - 0.051461 * b
                var y = -0.250268 * r + 1.204414 * g + 0.045854 * b
                val z = -0.002079 * r + 0.048952 * g + 0.953127 * b

                val rA = connResponse(vc.rgbD[0] * x, vc.fl)
                val gA = connResponse(vc.rgbD[1] * y, vc.fl)
                val bA = connResponse(vc.rgbD[2] * z, vc.fl)

                x = (11.0 * rA - 12.0 * gA + bA) / 11.0
                y = (rA + gA - 2.0 * bA) / 9.0
                val zz = (20.0 * rA + 20.0 * gA + 21.0 * bA) / 20.0
                var p2 = (40.0 * rA + 20.0 * gA + bA) / 20.0

                val hue = sanitizeDegrees(Math.toDegrees(atan2(y, x)))
                val hueRad = Math.toRadians(hue)

                p2 *= vc.nbb
                val tone = 100.0 * (p2 / vc.aw).pow(vc.c * vc.z)

                val huePrime = if (hue < 20.14) hue + 360.0 else hue
                val eHue = 0.25 * (cos(Math.toRadians(huePrime) + 2.0) + 3.8)
                val chroma = (3846.153846153846 * eHue * vc.nc * vc.nbb * hypot(x, y) / (zz + 0.305)).pow(0.9) *
                    (1.64 - 0.29.pow(vc.n)).pow(0.73) *
                    sqrt(tone / 100.0)

                val t = 1.7 * tone / (1.0 + 0.007 * tone)
                val alpha = 43.859649122807014 * ln1p(0.0228 * chroma)
                return Hct(hue, chroma, tone, t, alpha * cos(hueRad), alpha * sin(hueRad))
            }

            /** HCT to ARGB, solving for the most chromatic in-gamut colour at that tone. */
            fun toInt(hueRaw: Double, chroma: Double, tone: Double): Int {
                if (chroma < 1.0 || Math.round(tone) <= 0L || Math.round(tone) >= 100L) {
                    return argbFromLstar(tone)
                }
                val hue = sanitizeDegrees(hueRaw)

                var lo = 0.0
                var hi = chroma
                var current = chroma
                var first = true
                var answer: Hct? = null

                while (abs(lo - hi) >= 0.4) {
                    val candidate = solve(hue, current, tone)
                    if (first) {
                        if (candidate != null) {
                            return candidate.toInt()
                        }
                        first = false
                        current = lo + (hi - lo) / 2.0
                    } else {
                        if (candidate == null) {
                            hi = current
                        } else {
                            answer = candidate
                            lo = current
                        }
                        current = lo + (hi - lo) / 2.0
                    }
                }
                return answer?.toInt() ?: argbFromLstar(tone)
            }

            /**
             * Port of `Cls.getCls3ForDouble`: searches for a colour of the requested hue and chroma
             * whose actual L* matches `tone`, and rejects candidates that drift too far from the
             * CAM16 estimate (i.e. that fell out of gamut).
             */
            private fun solve(hue: Double, chroma: Double, tone: Double): Hct? {
                var lo = 0.0
                var hi = 100.0
                var best = 1000.0
                var answer: Hct? = null

                while (abs(lo - hi) > 0.01) {
                    val mid = lo + (hi - lo) / 2.0
                    val argb = of(mid, chroma, hue).toInt()
                    val lstar = lstarFromArgb(argb)
                    if (abs(tone - lstar) < 0.2) {
                        val found = fromInt(argb)
                        val delta = found.distanceTo(of(found.tone, chroma, hue))
                        if (delta <= 1.0 && delta <= best) {
                            best = delta
                            answer = found
                        }
                    }
                    if (lstar < tone) {
                        lo = mid
                    } else {
                        hi = mid
                    }
                }
                return answer
            }

            /** Linear RGB to CAM16 cone response. */
            private fun connResponse(value: Double, fl: Double): Double {
                val v = (fl * abs(value) / 100.0).pow(0.42)
                return sign(value) * 400.0 * v / (v + 27.13)
            }

            /** CAM16 cone response back to linear RGB. */
            private fun inverseConn(value: Double, fl: Double): Double {
                val v = max(0.0, 27.13 * abs(value) / (400.0 - abs(value)))
                return sign(value) * (100.0 / fl) * v.pow(2.380952380952381)
            }
        }

        private fun distanceTo(other: Hct): Double {
            val dt = t - other.t
            val dc = alphaC - other.alphaC
            val ds = alphaS - other.alphaS
            return 1.41 * sqrt(dt * dt + dc * dc + ds * ds).pow(0.63)
        }

        fun toInt(): Int = toInt(ViewingConditions.DEFAULT)

        /** Port of `Cls3.getInt2`. */
        fun toInt(vc: ViewingConditions): Int {
            var a = if (chroma != 0.0 && tone != 0.0) chroma / sqrt(tone / 100.0) else 0.0
            a = (a / (1.64 - 0.29.pow(vc.n)).pow(0.73)).pow(1.1111111111111112)

            val hueRad = Math.toRadians(hue)
            var eHue = 0.25 * (cos(hueRad + 2.0) + 3.8)
            var awScaled = vc.aw * (tone / 100.0).pow(1.0 / vc.c / vc.z)
            eHue = eHue * 3846.153846153846 * vc.nc * vc.nbb
            awScaled /= vc.nbb

            val sin = sin(hueRad)
            val cos = cos(hueRad)
            val coef = 23.0 * (awScaled + 0.305) * a / (23.0 * eHue + 11.0 * a * cos + 108.0 * a * sin)
            val rA = coef * cos
            val gA = coef * sin

            var r = (460.0 * awScaled + 451.0 * rA + 288.0 * gA) / 1403.0
            var g = (460.0 * awScaled - 891.0 * rA - 261.0 * gA) / 1403.0
            var b = (460.0 * awScaled - 220.0 * rA - 6300.0 * gA) / 1403.0

            r = inverseConn(r, vc.fl) / vc.rgbD[0]
            g = inverseConn(g, vc.fl) / vc.rgbD[1]
            b = inverseConn(b, vc.fl) / vc.rgbD[2]

            val x = 1.86206786 * r - 1.01125463 * g + 0.14918677 * b
            val y = 0.38752654 * r + 0.62144744 * g - 0.00897398 * b
            val z = -0.0158415 * r - 0.03412294 * g + 1.04996444 * b
            return fromLinearRgb(x, y, z)
        }
    }

    // ------------------------------------------------------ tonal palette

    /** Port of `theme.impl.Cls2`: a hue/chroma pair sampled at any tone. */
    class TonalPalette private constructor(val hue: Double, val chroma: Double) {
        companion object {
            fun of(hue: Double, chroma: Double): TonalPalette = TonalPalette(hue, chroma)
        }

        fun tone(tone: Double): Int = Hct.toInt(hue, chroma, clamp(tone, 0.0, 100.0))

        /** Samples the palette while shrinking the tone by a percentage (Onyx's neutral cut). */
        fun tone(tone: Double, cut: Double): Int = tone(tone - cut * tone / 100.0)
    }
}
