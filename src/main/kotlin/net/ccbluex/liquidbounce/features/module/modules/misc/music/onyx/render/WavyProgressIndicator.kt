package net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render

import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Material
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Theme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Material 3 Expressive style wavy progress indicators for the player.
 *
 * <p>Ported from Onyx's `render.WavyProgressIndicator`. Every number that shapes the indicator — the
 * 24 px wavelength, the stroke rule `min(height, clamp(height * 0.36, 1, 4))`, the amplitude envelope
 * (`0` below 10 %, ramps to a plateau at 14 %, back to `0` from 90 % to 95 %), the indeterminate
 * travel/segment curves, the phase advance `speed / wavelength * TAU` — is unchanged.</p>
 *
 * <p>The original evaluated a signed-distance field for the stroked sine in
 * `onyx_wavy_progress.fsh` and let `fwidth` antialias it. A GUI module has no custom-fragment stage,
 * so the same path is rasterised on the CPU instead: the curve is sampled at sub-stroke spacing and
 * each sample stamps a disc of `stroke / 2`, which is the union formulation the shader's
 * `abs(dot(delta, normal)) - thickness * 0.5` describes, with the round cap for free. Sampling is
 * capped so a very long bar cannot flood the frame.</p>
 */
object WavyProgressIndicator {

    private const val TAU = (PI * 2.0).toFloat()
    private const val DEFAULT_WAVELENGTH = 24.0F
    private const val DEFAULT_AMPLITUDE = 1.0F
    private const val DEFAULT_SPEED = DEFAULT_WAVELENGTH

    /** Wave settings and colours. Amplitude is a 0..1 fraction; speed is measured in GUI px/sec. */
    class Style(
        wavelength: Float,
        amplitude: Float,
        speed: Float,
        val indicatorColor: Int,
        val trackColor: Int,
    ) {
        val wavelength: Float =
            if (wavelength.isNaN() || wavelength.isInfinite()) DEFAULT_WAVELENGTH else max(4.0F, wavelength)
        val amplitude: Float =
            if (amplitude.isNaN() || amplitude.isInfinite()) DEFAULT_AMPLITUDE else Material.clamp(amplitude, 0.0F, 1.0F)
        val speed: Float = if (speed.isNaN() || speed.isInfinite()) DEFAULT_SPEED else speed

        companion object {
            /** Uses the current Onyx Material palette. */
            fun material(): Style = Style(
                DEFAULT_WAVELENGTH, DEFAULT_AMPLITUDE, DEFAULT_SPEED,
                Theme.scheme().primary, Theme.scheme().primaryContainer,
            )
        }
    }

    /** Draws a determinate horizontal indicator inside the supplied bounds. */
    fun drawLinear(ui: UI, x: Float, y: Float, width: Float, height: Float, progress: Float) {
        drawLinear(ui, x, y, width, height, progress, Style.material())
    }

    /** Draws a determinate horizontal indicator with custom wave settings and colours. */
    fun drawLinear(ui: UI, x: Float, y: Float, width: Float, height: Float, progress: Float, style: Style?) {
        if (!valid(ui, width, height)) return
        val style0 = style ?: Style.material()
        val value = unit(progress)
        val stroke = linearStroke(height)
        val amplitude = style0.amplitude * max(0.0F, (height - stroke) * 0.5F)
        val trackY = y + (height - stroke) * 0.5F
        if (value <= 0.0F) {
            ui.rounded(x, trackY, width, stroke, stroke * 0.5F, style0.trackColor)
            return
        }
        if (value >= 1.0F) {
            val drawn = drawWave(ui, x, y, width, height, false, 0.0F, 1.0F, 0.0F, stroke, 0.0F, style0, phase(style0))
            if (!drawn) ui.rounded(x, trackY, width, stroke, stroke * 0.5F, style0.indicatorColor)
            return
        }

        val waveAmplitude = amplitude * determinateAmplitude(value)
        val wavePhase = phase(style0)
        val drawn = drawWave(
            ui, x, y, width, height, false, 0.0F, value,
            0.0F, stroke, waveAmplitude, style0, wavePhase,
        )
        val trackStartX: Float
        if (!drawn) {
            val fillWidth = max(0.0F, width * value)
            ui.rounded(x, trackY, fillWidth, stroke, stroke * 0.5F, style0.indicatorColor)
            trackStartX = x + fillWidth
        } else {
            // The wavy cap's rightmost point is where the straight, unplayed track begins.
            trackStartX = x + stroke + value * (width - stroke)
        }
        val remaining = x + width - trackStartX
        if (remaining > 0.0F) {
            ui.rounded(trackStartX, trackY, remaining, stroke, stroke * 0.5F, style0.trackColor)
        }
    }

    /** Draws an animated, indeterminate horizontal indicator. */
    fun drawLinearIndeterminate(ui: UI, x: Float, y: Float, width: Float, height: Float) {
        drawLinearIndeterminate(ui, x, y, width, height, Style.material())
    }

    /** Draws an animated, indeterminate horizontal indicator with custom settings. */
    fun drawLinearIndeterminate(ui: UI, x: Float, y: Float, width: Float, height: Float, style: Style?) {
        if (!valid(ui, width, height)) return
        val style0 = style ?: Style.material()
        val stroke = linearStroke(height)
        val amplitude = style0.amplitude * max(0.0F, (height - stroke) * 0.5F)
        val trackY = y + (height - stroke) * 0.5F

        val t = cycle((seconds() * 0.34F).toFloat(), 2.0F)
        var travel = if (t < 1.0F) t else 2.0F - t
        travel = travel * travel * (3.0F - 2.0F * travel)
        val segment = 0.18F + 0.24F * (0.5F + 0.5F * sin(seconds() * 1.8F).toFloat())
        val start = travel * (1.0F - segment)
        ui.rounded(x, trackY, width, stroke, stroke * 0.5F, style0.trackColor)
        val drawn = drawWave(
            ui, x, y, width, height, false, start, start + segment,
            0.0F, stroke, amplitude, style0, phase(style0),
        )
        if (!drawn) {
            val fillWidth = width * segment
            ui.rounded(x + width * start, trackY, fillWidth, stroke, stroke * 0.5F, style0.indicatorColor)
        }
    }

    /** Draws a determinate circular indicator. The diameter includes the complete wavy track. */
    fun drawCircular(ui: UI, centerX: Float, centerY: Float, diameter: Float, progress: Float) {
        val stroke = min(4.0F, max(1.0F, diameter * 0.12F))
        drawCircular(ui, centerX, centerY, diameter, stroke, progress, Style.material())
    }

    /** Draws a determinate circular indicator with custom stroke, wave settings and colours. */
    fun drawCircular(
        ui: UI, centerX: Float, centerY: Float, diameter: Float,
        strokeRaw: Float, progress: Float, style: Style?,
    ) {
        if (!valid(ui, diameter, diameter) || strokeRaw <= 0.0F) return
        val style0 = style ?: Style.material()
        val value = unit(progress)
        val stroke = min(strokeRaw, diameter * 0.25F)
        val outer = diameter * 0.5F
        val maxAmplitude = min(max(0.0F, (outer - stroke) * 0.5F), diameter * 0.10F)
        val amplitude = style0.amplitude * maxAmplitude * determinateAmplitude(value)
        val baseRadius = max(stroke, outer - stroke * 0.5F - maxAmplitude)

        val trackRadius = baseRadius + stroke * 0.5F
        ui.outline(
            centerX - trackRadius, centerY - trackRadius, trackRadius * 2.0F,
            trackRadius * 2.0F, trackRadius, stroke, style0.trackColor,
        )
        if (value <= 0.0F) return
        if (value >= 1.0F) {
            ui.outline(
                centerX - trackRadius, centerY - trackRadius,
                trackRadius * 2.0F, trackRadius * 2.0F,
                trackRadius, stroke, style0.indicatorColor,
            )
            return
        }

        val drawn = drawWave(
            ui, centerX - outer, centerY - outer, diameter, diameter,
            true, 0.0F, value, baseRadius, stroke, amplitude, style0, phase(style0),
        )
        if (!drawn) drawFallbackArc(ui, centerX, centerY, baseRadius, stroke, 0.0F, value, style0.indicatorColor)
    }

    /** Draws an animated, indeterminate circular indicator. */
    fun drawCircularIndeterminate(ui: UI, centerX: Float, centerY: Float, diameter: Float) {
        val stroke = min(4.0F, max(1.0F, diameter * 0.12F))
        drawCircularIndeterminate(ui, centerX, centerY, diameter, stroke, Style.material())
    }

    /** Draws an animated, indeterminate circular indicator with custom settings. */
    fun drawCircularIndeterminate(
        ui: UI, centerX: Float, centerY: Float, diameter: Float,
        strokeRaw: Float, style: Style?,
    ) {
        if (!valid(ui, diameter, diameter) || strokeRaw <= 0.0F) return
        val style0 = style ?: Style.material()
        val stroke = min(strokeRaw, diameter * 0.25F)
        val outer = diameter * 0.5F
        val maxAmplitude = min(max(0.0F, (outer - stroke) * 0.5F), diameter * 0.10F)
        val amplitude = style0.amplitude * maxAmplitude
        val baseRadius = max(stroke, outer - stroke * 0.5F - maxAmplitude)

        val trackRadius = baseRadius + stroke * 0.5F
        ui.outline(
            centerX - trackRadius, centerY - trackRadius, trackRadius * 2.0F,
            trackRadius * 2.0F, trackRadius, stroke, style0.trackColor,
        )
        val time = seconds()
        val start = ((time * 0.18F) % 1.0F).toFloat()
        val sweep = 0.20F + 0.16F * (0.5F + 0.5F * sin(time * 1.7F).toFloat())
        val drawn = drawWave(
            ui, centerX - outer, centerY - outer, diameter, diameter,
            true, start, sweep, baseRadius, stroke, amplitude, style0, phase(style0),
        )
        if (!drawn) drawFallbackArc(ui, centerX, centerY, baseRadius, stroke, start, sweep, style0.indicatorColor)
    }

    // -------------------------------------------------------------- raster

    /**
     * Stamps the stroked sine of the original fragment shader.
     *
     * @return true when the wave was drawn, so the caller skips its straight-fill fallback
     */
    private fun drawWave(
        ui: UI, x: Float, y: Float, width: Float, height: Float,
        circular: Boolean, start: Float, end: Float, baseRadius: Float,
        stroke: Float, amplitude: Float, style: Style, phase: Float,
    ): Boolean {
        if (ui.alpha() <= 0.0F || (!circular && end <= start) || (circular && end <= 0.0F)) return false

        val color = Material.scaleAlpha(style.indicatorColor, ui.alpha())
        if (Material.alpha(color) == 0) return false
        // One disc per ~0.4 stroke keeps the union gapless without flooding the frame.
        val step = max(0.45F, stroke * 0.4F)
        val radius = stroke * 0.5F
        return if (circular) {
            val centerX = x + width * 0.5F
            val centerY = y + height * 0.5F
            val startAngle = start * TAU - (PI * 0.5F).toFloat()
            val sweep = end * TAU
            val arcLength = sweep * baseRadius
            val count = max(2, ceil(arcLength / step).toInt().coerceAtMost(1024))
            for (i in 0..count) {
                val along = arcLength * i / count.toFloat()
                val angle = startAngle + along / baseRadius
                val wave = amplitude * sin(along * TAU / max(style.wavelength, 0.001F) - phase)
                val r = baseRadius + wave
                ui.circle(centerX + cos(angle) * r, centerY + sin(angle) * r, radius, color)
            }
            true
        } else {
            val halfWidth = width * 0.5F
            val startX = -halfWidth + stroke * 0.5F
            val endX = halfWidth - stroke * 0.5F
            val fromX = startX + (endX - startX) * Material.clamp(start, 0.0F, 1.0F)
            val toX = startX + (endX - startX) * Material.clamp(end, 0.0F, 1.0F)
            val span = toX - fromX
            if (span < 0.01F) return false
            val centerX = x + halfWidth
            val centerY = y + height * 0.5F
            val count = max(2, ceil(span / step).toInt().coerceAtMost(1024))
            for (i in 0..count) {
                val px = fromX + span * i / count.toFloat()
                val angle = (px - startX) * TAU / max(style.wavelength, 0.001F) - phase
                val py = amplitude * sin(angle)
                ui.circle(centerX + px, centerY + py, radius, color)
            }
            true
        }
    }

    /** Straight capsule along the arc; only used when the wave could not be rasterised. */
    private fun drawFallbackArc(
        ui: UI, centerX: Float, centerY: Float, radius: Float,
        stroke: Float, start: Float, sweep: Float, color: Int,
    ) {
        val segments = max(8, ceil(abs(sweep) * PI * 2.0 * radius / 2.0).toInt())
        val a0 = start * TAU - (PI * 0.5F).toFloat()
        val span = sweep * TAU
        val alphaColor = Material.scaleAlpha(color, ui.alpha())
        for (i in 0..segments) {
            val angle = a0 + span * (i / segments.toFloat())
            ui.circle(
                centerX + cos(angle) * radius, centerY + sin(angle) * radius,
                stroke * 0.5F, alphaColor,
            )
        }
    }

    private fun linearStroke(height: Float): Float = min(height, max(1.0F, min(4.0F, height * 0.36F)))

    private fun determinateAmplitude(progress: Float): Float {
        if (progress <= 0.1F || progress >= 0.95F) return 0.0F
        if (progress < 0.14F) return (progress - 0.1F) / 0.04F
        if (progress > 0.9F) return (0.95F - progress) / 0.05F
        return 1.0F
    }

    private fun unit(value: Float): Float = if (value.isNaN()) 0.0F else Material.clamp(value, 0.0F, 1.0F)

    private fun phase(style: Style): Float {
        val value = seconds() * style.speed / style.wavelength * TAU
        return (value % TAU).toFloat()
    }

    private fun seconds(): Double = System.nanoTime() / 1.0E9

    private fun cycle(value: Float, length: Float): Float {
        val result = value % length
        return if (result < 0.0F) result + length else result
    }

    private fun valid(ui: UI?, width: Float, height: Float): Boolean =
        ui != null && width > 0.0F && height > 0.0F &&
            !width.isNaN() && !height.isNaN() && !width.isInfinite() && !height.isInfinite()
}
