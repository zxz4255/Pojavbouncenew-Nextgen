package net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render

import kotlin.math.abs

/**
 * Cubic-bezier easing, ported from Onyx's `render.misc.Util`.
 *
 * <p>Onyx solved the bezier numerically (Newton, then bisection) instead of using sampled tables;
 * that keeps every animation curve frame-accurate, so the same solver is used here.</p>
 */
class Ease private constructor(
    private val x1: Double,
    private val y1: Double,
    private val x2: Double,
    private val y2: Double,
) {

    /** Evaluates the curve for a normalized time in 0..1. */
    fun get(t: Float): Float {
        if (t <= 0.0F) return 0.0F
        if (t >= 1.0F) return 1.0F
        return bezierY(solveX(t.toDouble())).toFloat()
    }

    private fun solveX(x: Double): Double {
        var current = x
        for (i in 0 until 8) {
            val error = bezierX(current) - x
            if (abs(error) < 1.0E-6) return current
            val derivative = bezierDX(current)
            if (abs(derivative) < 1.0E-6) break
            current -= error / derivative
        }

        var lo = 0.0
        var hi = 1.0
        current = x
        while (lo < hi) {
            val error = bezierX(current) - x
            if (abs(error) < 1.0E-6) return current
            if (error < 0.0) lo = current else hi = current
            val next = (lo + hi) / 2.0
            if (abs(next - current) < 1.0E-9) return current
            current = next
        }
        return current
    }

    private fun bezierX(t: Double): Double {
        val inv = 1.0 - t
        return 3.0 * inv * inv * t * x1 + 3.0 * inv * t * t * x2 + t * t * t
    }

    private fun bezierY(t: Double): Double {
        val inv = 1.0 - t
        return 3.0 * inv * inv * t * y1 + 3.0 * inv * t * t * y2 + t * t * t
    }

    private fun bezierDX(t: Double): Double {
        val inv = 1.0 - t
        return 3.0 * inv * inv * x1 + 6.0 * inv * t * (x2 - x1) + 3.0 * t * t * (1.0 - x2)
    }

    companion object {
        /** Linear. */
        @JvmField
        val LINEAR = Ease(0.0, 0.0, 1.0, 1.0)

        /** Material 3 emphasized decelerate (Onyx's `IFACE2`/`IFACE6`). */
        @JvmField
        val EMPHASIZED_DECELERATE = Ease(0.2, 0.0, 0.0, 1.0)

        /** Decelerate (Onyx's `IFACE5`). */
        @JvmField
        val DECELERATE = Ease(0.0, 0.0, 0.0, 1.0)

        /** Material 3 emphasized (Onyx's `IFACE7`). */
        @JvmField
        val EMPHASIZED = Ease(0.05, 0.7, 0.1, 1.0)

        /** Material 3 standard (Onyx's `IFACE3`). */
        @JvmField
        val STANDARD = Ease(0.4, 0.0, 0.2, 1.0)

        /** Onyx's `IFACE4`. */
        @JvmField
        val EMPHASIZED_ACCELERATE = Ease(0.3, 0.0, 1.0, 1.0)
    }
}
