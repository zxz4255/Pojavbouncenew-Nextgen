package net.ccbluex.liquidbounce.features.module.modules.misc.music

import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.material.Material
import net.ccbluex.liquidbounce.features.module.modules.misc.music.onyx.render.UI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Song-only FFT capture. The producer is AudioEngine's private decoder worker, not Minecraft's
 * global sound mixer, so block sounds and other game audio can neither enter the spectrum nor be
 * affected by its rendering/analysis.
 */
internal object MusicSpectrumAnalyzer {
    const val BANDS = 48
    private const val FFT_SIZE = 2048
    private const val FFT_MASK = FFT_SIZE - 1
    private const val EMPTY = 0
    private const val FILLING = 1
    private const val READY = 2
    private const val READING = 3
    private const val REQUEST_LEASE_NANOS = 350_000_000L
    private const val STALE_CAPTURE_NANOS = 250_000_000L

    private val ringLeft = FloatArray(FFT_SIZE)
    private val ringRight = FloatArray(FFT_SIZE)
    private val frameLeft = FloatArray(FFT_SIZE)
    private val frameRight = FloatArray(FFT_SIZE)
    private val workerLeft = FloatArray(FFT_SIZE)
    private val workerRight = FloatArray(FFT_SIZE)
    private val outputBands = FloatArray(BANDS)
    private val captureSlot = AtomicInteger(EMPTY)
    private val analysisPending = AtomicBoolean(false)
    private val transform = SpectrumTransform()

    @Volatile private var leaseUntilNanos = 0L
    @Volatile private var trackGeneration = 0L
    @Volatile private var latest = Frame(FloatArray(BANDS), 0L, 0L)
    @Volatile private var disabled = false

    // These fields have one owner: AudioEngine's decoder worker.
    private var writeIndex = 0
    private var ringCount = 0
    private var samplesSinceFrame = 0
    private var ringRate = 0
    private var lastAcceptedNanos = 0L
    private var capturedRate = 0
    private var capturedAtNanos = 0L
    private var capturedGeneration = 0L

    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "liquidbounce-music-spectrum").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    class Frame internal constructor(
        private val bands: FloatArray,
        val capturedAtNanos: Long,
        val generation: Long,
    ) {
        fun copyTo(destination: FloatArray) {
            val count = min(BANDS, destination.size)
            System.arraycopy(bands, 0, destination, 0, count)
            if (count < destination.size) java.util.Arrays.fill(destination, count, destination.size, 0.0F)
        }

        fun band(index: Int): Float = if (index in 0 until BANDS) bands[index] else 0.0F
    }

    /** Reset the rolling capture at a track/seek boundary; it never touches Minecraft audio state. */
    fun beginTrack() {
        trackGeneration++
        writeIndex = 0
        ringCount = 0
        samplesSinceFrame = 0
        ringRate = 0
        lastAcceptedNanos = 0L
        latest = Frame(FloatArray(BANDS), 0L, trackGeneration)
    }

    /** Called only while a visible Music UI is being drawn. */
    fun requestFrame(nowNanos: Long = System.nanoTime()): Frame {
        if (!disabled) {
            leaseUntilNanos = nowNanos + REQUEST_LEASE_NANOS
            if (captureSlot.get() == READY) scheduleAnalysis()
        }
        return latest
    }

    fun suspend() {
        leaseUntilNanos = 0L
    }

    /**
     * Copy one music PCM chunk into the bounded ring. No FFT, blocking wait, or global-mixer access
     * occurs here. Work is published to a low-priority worker only when a visible UI requested it.
     */
    fun accept(samples: ShortArray, interleavedSamples: Int, channels: Int, sampleRate: Int) {
        try {
            val now = System.nanoTime()
            if (disabled || !requested(now) || sampleRate !in 8_000..192_000 || channels !in 1..2 ||
                interleavedSamples <= 0 || interleavedSamples > samples.size || interleavedSamples % channels != 0) {
                return
            }

            if (lastAcceptedNanos == 0L || now - lastAcceptedNanos > STALE_CAPTURE_NANOS || ringRate != sampleRate) {
                writeIndex = 0
                ringCount = 0
                samplesSinceFrame = 0
                ringRate = sampleRate
            }
            lastAcceptedNanos = now

            val frames = interleavedSamples / channels
            for (frame in 0 until frames) {
                val offset = frame * channels
                val left = samples[offset].toFloat() / 32768.0F
                val right = if (channels > 1) samples[offset + 1].toFloat() / 32768.0F else left
                ringLeft[writeIndex] = if (left.isFinite()) left else 0.0F
                ringRight[writeIndex] = if (right.isFinite()) right else 0.0F
                writeIndex = (writeIndex + 1) and FFT_MASK
                ringCount = min(FFT_SIZE, ringCount + 1)
                if (samplesSinceFrame < sampleRate) samplesSinceFrame++
            }

            if (ringCount < FFT_SIZE || samplesSinceFrame < max(1, sampleRate / 30) ||
                !captureSlot.compareAndSet(EMPTY, FILLING)) {
                return
            }

            // Publish the newest 2048-frame stereo window. If analysis is busy, intermediate frames
            // are intentionally dropped rather than making the decoder wait for the visualizer.
            val tail = FFT_SIZE - writeIndex
            System.arraycopy(ringLeft, writeIndex, frameLeft, 0, tail)
            System.arraycopy(ringLeft, 0, frameLeft, tail, writeIndex)
            System.arraycopy(ringRight, writeIndex, frameRight, 0, tail)
            System.arraycopy(ringRight, 0, frameRight, tail, writeIndex)
            capturedRate = sampleRate
            capturedAtNanos = now
            capturedGeneration = trackGeneration
            samplesSinceFrame = 0
            captureSlot.set(READY)
            scheduleAnalysis()
        } catch (_: Throwable) {
            // A visualization failure must never escape into or interrupt music playback.
            disabled = true
            suspend()
            captureSlot.compareAndSet(FILLING, EMPTY)
        }
    }

    private fun requested(nowNanos: Long): Boolean {
        val until = leaseUntilNanos
        return until != 0L && nowNanos < until
    }

    private fun scheduleAnalysis() {
        if (disabled || !analysisPending.compareAndSet(false, true)) return
        try {
            worker.execute { analyzeOne() }
        } catch (_: RuntimeException) {
            analysisPending.set(false)
            disabled = true
            suspend()
            captureSlot.set(EMPTY)
        }
    }

    private fun analyzeOne() {
        try {
            if (captureSlot.compareAndSet(READY, READING)) {
                val sampleRate = capturedRate
                val capturedAt = capturedAtNanos
                val generation = capturedGeneration
                System.arraycopy(frameLeft, 0, workerLeft, 0, FFT_SIZE)
                System.arraycopy(frameRight, 0, workerRight, 0, FFT_SIZE)
                captureSlot.set(EMPTY)

                if (sampleRate > 0 && generation == trackGeneration && requested(System.nanoTime())) {
                    transform.analyze(workerLeft, workerRight, sampleRate, outputBands)
                    if (generation == trackGeneration && requested(System.nanoTime())) {
                        latest = Frame(outputBands.copyOf(), capturedAt, generation)
                    }
                }
            }
        } catch (_: Throwable) {
            // Keep the playback path independent even if an FFT or worker operation fails.
            disabled = true
            suspend()
            captureSlot.compareAndSet(READING, EMPTY)
        } finally {
            analysisPending.set(false)
            if (!disabled && captureSlot.get() == READY && requested(System.nanoTime())) scheduleAnalysis()
        }
    }

    private class SpectrumTransform {
        private val window = DoubleArray(FFT_SIZE)
        private val real = DoubleArray(FFT_SIZE)
        private val imaginary = DoubleArray(FFT_SIZE)
        private val power = DoubleArray(FFT_SIZE / 2)
        private val cosine = DoubleArray(FFT_SIZE / 2)
        private val sine = DoubleArray(FFT_SIZE / 2)
        private val starts = IntArray(BANDS)
        private val ends = IntArray(BANDS)
        private var mappedRate = 0

        init {
            for (i in 0 until FFT_SIZE) {
                window[i] = 0.5 - 0.5 * cos(2.0 * Math.PI * i / (FFT_SIZE - 1))
            }
            for (i in cosine.indices) {
                val angle = -2.0 * Math.PI * i / FFT_SIZE
                cosine[i] = cos(angle)
                sine[i] = sin(angle)
            }
        }

        fun analyze(left: FloatArray, right: FloatArray, sampleRate: Int, result: FloatArray) {
            if (sampleRate !in 8_000..192_000 || left.size < FFT_SIZE || right.size < FFT_SIZE) {
                throw IllegalArgumentException("Invalid song spectrum frame")
            }
            if (mappedRate != sampleRate) mapBands(sampleRate)
            java.util.Arrays.fill(power, 0.0)
            transformChannel(left)
            transformChannel(right)

            for (band in 0 until BANDS) {
                var strongest = 0.0
                for (bin in starts[band] until ends[band]) strongest = max(strongest, power[bin])
                val amplitude = sqrt(strongest * 0.5) * 4.0 / FFT_SIZE
                val decibels = 20.0 * ln(max(1.0e-9, amplitude)) / ln(10.0)
                result[band] = ((decibels + 72.0) / 66.0).toFloat().coerceIn(0.0F, 1.0F)
            }
        }

        private fun transformChannel(channel: FloatArray) {
            var mean = 0.0
            for (i in 0 until FFT_SIZE) {
                val sample = if (channel[i].isFinite()) channel[i].toDouble() else 0.0
                mean += sample
            }
            mean /= FFT_SIZE

            var energy = 0.0
            for (i in 0 until FFT_SIZE) {
                val sample = (if (channel[i].isFinite()) channel[i].toDouble() else 0.0) - mean
                energy += sample * sample
                real[i] = sample * window[i]
                imaginary[i] = 0.0
            }
            if (energy < 1.0e-12) return

            fft()
            for (bin in 1 until power.size) {
                power[bin] += real[bin] * real[bin] + imaginary[bin] * imaginary[bin]
            }
        }

        private fun fft() {
            var reversed = 0
            for (index in 1 until FFT_SIZE) {
                var bit = FFT_SIZE ushr 1
                while ((reversed and bit) != 0) {
                    reversed = reversed xor bit
                    bit = bit ushr 1
                }
                reversed = reversed xor bit
                if (index < reversed) {
                    val realValue = real[index]
                    real[index] = real[reversed]
                    real[reversed] = realValue
                    val imaginaryValue = imaginary[index]
                    imaginary[index] = imaginary[reversed]
                    imaginary[reversed] = imaginaryValue
                }
            }

            var size = 2
            while (size <= FFT_SIZE) {
                val half = size ushr 1
                val twiddleStep = FFT_SIZE / size
                var base = 0
                while (base < FFT_SIZE) {
                    for (offset in 0 until half) {
                        val twiddle = offset * twiddleStep
                        val even = base + offset
                        val odd = even + half
                        val transformedReal = real[odd] * cosine[twiddle] - imaginary[odd] * sine[twiddle]
                        val transformedImaginary = real[odd] * sine[twiddle] + imaginary[odd] * cosine[twiddle]
                        val evenReal = real[even]
                        val evenImaginary = imaginary[even]
                        real[even] = evenReal + transformedReal
                        imaginary[even] = evenImaginary + transformedImaginary
                        real[odd] = evenReal - transformedReal
                        imaginary[odd] = evenImaginary - transformedImaginary
                    }
                    base += size
                }
                size = size shl 1
            }
        }

        private fun mapBands(sampleRate: Int) {
            mappedRate = sampleRate
            val top = min(16_000.0, sampleRate * 0.48)
            for (band in 0 until BANDS) {
                val low = 40.0 * Math.pow(top / 40.0, band.toDouble() / BANDS)
                val high = 40.0 * Math.pow(top / 40.0, (band + 1).toDouble() / BANDS)
                starts[band] = floor(low * FFT_SIZE / sampleRate).toInt().coerceIn(1, power.size - 1)
                ends[band] = ceil(high * FFT_SIZE / sampleRate).toInt()
                    .coerceAtLeast(starts[band] + 1).coerceAtMost(power.size)
            }
        }
    }
}

/** Compact animated band renderer shared by the HUD island and the player's mini-bar. */
internal object MusicSpectrumRenderer {
    private val target = FloatArray(MusicSpectrumAnalyzer.BANDS)
    private val display = FloatArray(MusicSpectrumAnalyzer.BANDS)
    private var lastRenderNanos = 0L
    private var lastGeneration = Long.MIN_VALUE

    fun draw(ui: UI, x: Float, bottom: Float, width: Float, height: Float, color: Int, alpha: Float = 1.0F) {
        if (!x.isFinite() || !bottom.isFinite() || !width.isFinite() || !height.isFinite() ||
            width < 4.0F || height < 1.0F) return

        val now = System.nanoTime()
        val manager = MusicManager
        val audible = manager.current != null && manager.isPlaying()
        val frame = if (audible) MusicSpectrumAnalyzer.requestFrame(now) else {
            MusicSpectrumAnalyzer.suspend()
            null
        }

        if (frame != null) {
            if (frame.generation != lastGeneration) {
                display.fill(0.0F)
                target.fill(0.0F)
                lastGeneration = frame.generation
            }
            if (frame.capturedAtNanos > 0L && now - frame.capturedAtNanos in 0L..250_000_000L) {
                frame.copyTo(target)
            } else {
                target.fill(0.0F)
            }
        } else {
            target.fill(0.0F)
        }

        val elapsed = if (lastRenderNanos == 0L) 1.0 / 60.0
            else ((now - lastRenderNanos).coerceAtLeast(0L) / 1_000_000_000.0).coerceAtMost(0.10)
        if (lastRenderNanos != 0L && now - lastRenderNanos > 250_000_000L) display.fill(0.0F)
        lastRenderNanos = now

        for (index in display.indices) {
            val rising = target[index] > display[index]
            val rate = if (rising) 22.0 else 9.0
            val blend = (1.0 - exp(-elapsed * rate)).toFloat()
            display[index] += (target[index] - display[index]) * blend
        }

        val safeAlpha = if (alpha.isFinite()) alpha.coerceIn(0.0F, 1.0F) else 1.0F
        ui.rounded(x, bottom - 0.7F, width, 0.7F, 0.35F,
            Material.withAlpha(color, safeAlpha * 0.16F))

        val count = min(MusicSpectrumAnalyzer.BANDS, max(1, (width / 4.0F).toInt()))
        val gap = if (count > 1) min(1.5F, width / (count * 7.0F)) else 0.0F
        val barWidth = (width - gap * (count - 1)) / count
        if (barWidth <= 0.0F) return

        for (bar in 0 until count) {
            val firstBand = bar * display.size / count
            val endBand = max(firstBand + 1, (bar + 1) * display.size / count)
            var level = 0.0F
            for (band in firstBand until min(endBand, display.size)) level = max(level, display[band])
            if (level < 0.012F) continue
            val barHeight = max(1.0F, level * height).coerceAtMost(height)
            val xPosition = x + bar * (barWidth + gap)
            val barAlpha = safeAlpha * (0.45F + level * 0.55F)
            ui.rounded(
                xPosition, bottom - barHeight, barWidth, barHeight,
                min(barWidth * 0.5F, barHeight * 0.5F), Material.withAlpha(color, barAlpha),
            )
        }
    }
}
