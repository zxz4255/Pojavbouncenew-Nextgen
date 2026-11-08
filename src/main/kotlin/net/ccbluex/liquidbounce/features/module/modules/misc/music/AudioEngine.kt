package net.ccbluex.liquidbounce.features.module.modules.misc.music

import net.ccbluex.liquidbounce.utils.audio.codec.jlayer.Bitstream
import net.ccbluex.liquidbounce.utils.audio.codec.jlayer.Decoder
import net.ccbluex.liquidbounce.utils.audio.codec.jlayer.Header
import net.ccbluex.liquidbounce.utils.audio.codec.jlayer.Obuffer
import net.ccbluex.liquidbounce.utils.audio.codec.jlayer.SampleBuffer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * MP3 playback for the music player.
 *
 * <p>Ported 1:1 from NeoRaven's `music.AudioEngine`. Minecraft owns the OpenAL device through its own
 * sound engine, and making a second context current from another thread fights it (either the game's
 * sounds or the music goes silent, depending on which thread called `alcMakeContextCurrent` last), so
 * the engine keeps a private audio thread, streams chunks and writes the PCM to a
 * [SourceDataLine] — a separate Java Sound output that coexists with the game's mixer.</p>
 *
 * <p>The engine only ever plays a local file; downloading (and resolving the signed NetEase URL)
 * belongs to [MusicManager], so a playlist change never blocks the audio thread. Position comes from
 * the frames actually written, which is what keeps the lyrics and the progress bar on the audio clock
 * instead of on wall time.</p>
 */
class AudioEngine {

    /** Notified when a track reaches its end without being stopped or replaced. */
    fun interface Listener {
        fun onTrackEnded()
    }

    private val lock = java.util.concurrent.locks.ReentrantLock()
    private val wake = lock.newCondition()

    @Volatile private var disposed = false

    @Volatile private var file: File? = null

    @Volatile private var wantPlay = false

    @Volatile private var seekRequestMs = -1L

    @Volatile private var volume = 0.8F

    @Volatile private var gain = 0.0F

    @Volatile private var positionMs = 0L

    @Volatile private var durationMs = 0L

    @Volatile private var level = 0.0F

    @Volatile private var peak = 0.0F

    @Volatile private var error: String? = null

    @Volatile private var buffering = false

    @Volatile private var listener: Listener? = null

    private var thread: Thread? = null

    @Volatile private var line: SourceDataLine? = null

    @Volatile private var trackSerial = 0L

    fun setListener(value: Listener?) {
        listener = value
    }

    fun setVolume(value: Float) {
        volume = clamp(value, 0.0F, 1.0F)
    }

    fun volume(): Float = volume

    fun setDurationMs(value: Long) {
        durationMs = max(0L, value)
    }

    fun durationMs(): Long = durationMs

    fun positionMs(): Long = positionMs

    /** Smoothed RMS of the most recent chunk (0..1), used to pulse the lyric view. */
    fun level(): Float = level

    /** Instantaneous peak of the most recent chunk (0..1), decays over ~200 ms. */
    fun peak(): Float = peak

    fun isBuffering(): Boolean = buffering

    fun error(): String? = error

    fun isPlaying(): Boolean = wantPlay && file != null

    fun hasTrack(): Boolean = file != null

    /** Starts (or replaces) the current track at `startAtMs`. */
    fun play(target: File?, startAtMs: Long) {
        if (target == null || !target.isFile) {
            error = "missing audio file"
            return
        }
        lock.withLock {
            file = target
            seekRequestMs = if (startAtMs > 0L) startAtMs else -1L
            positionMs = max(0L, startAtMs)
            error = null
            buffering = true
            wantPlay = true
            gain = 0.0F
            trackSerial++
            ensureThread()
            wake.signalAll()
        }
    }

    fun pause() {
        wantPlay = false
    }

    fun resume() {
        if (file != null) wantPlay = true
    }

    fun stop() {
        lock.withLock {
            wantPlay = false
            file = null
            buffering = false
            positionMs = 0L
            level = 0.0F
            peak = 0.0F
            trackSerial++
            wake.signalAll()
        }
    }

    fun seek(ms: Long) {
        val target = max(0L, ms)
        seekRequestMs = target
        // The worker has to decode-skip to the target; reflect it immediately so the bar does not
        // snap back to the old position while that happens.
        positionMs = target
    }

    fun dispose() {
        disposed = true
        stop()
        thread?.interrupt()
    }

    private fun ensureThread() {
        val existing = thread
        if (existing != null && existing.isAlive) return
        val worker = Thread({ loop() }, "liquidbounce-music")
        worker.isDaemon = true
        worker.priority = Thread.NORM_PRIORITY + 1
        thread = worker
        worker.start()
    }

    private fun loop() {
        while (!disposed) {
            var target: File? = null
            var serial = 0L
            lock.withLock {
                while (!disposed && file == null) {
                    try {
                        wake.await(200L, java.util.concurrent.TimeUnit.MILLISECONDS)
                    } catch (e: InterruptedException) {
                        if (disposed) break
                    }
                }
                target = file
                serial = trackSerial
            }
            val next = target
            if (disposed || next == null) continue
            try {
                runTrack(next, serial)
            } catch (t: Throwable) {
                error = t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")
                buffering = false
                wantPlay = false
                closeLine()
                sleep(160L)
            }
        }
        closeLine()
    }

    private fun runTrack(target: File, serial: Long) {
        buffering = true
        error = null
        level = 0.0F
        peak = 0.0F

        var stream: Bitstream? = null
        var output: SourceDataLine? = null
        try {
            stream = Bitstream(BufferedInputStream(FileInputStream(target), 256 * 1024))
            var header: Header? = stream.readFrame()
            if (header == null) throw java.io.IOException("no mp3 frame")
            var decoder = Decoder()
            var buffer: SampleBuffer? = decode(decoder, header, stream)

            val frequency = if (decoder.outputFrequency > 0) decoder.outputFrequency else 44100
            val channels = max(1, min(2, decoder.outputChannels))
            var baseMs = 0L

            val seek = seekRequestMs
            seekRequestMs = -1L
            if (seek > 0L) {
                val aligned = LongArray(1)
                val targetHeader = skipTo(stream!!, frequency, seek, aligned)
                baseMs = aligned[0]
                if (targetHeader == null) return
                buffer = decode(decoder, targetHeader, stream) ?: return
            }

            val format = AudioFormat(frequency.toFloat(), 16, channels, true, false)
            val info = DataLine.Info(SourceDataLine::class.java, format)
            output = AudioSystem.getLine(info) as SourceDataLine
            output.open(format, LINE_BUFFER_FRAMES * channels * 2)
            output.start()
            line = output
            buffering = false
            positionMs = baseMs

            var playedFrames = 0L
            val pcm = ByteArray(1152 * 2 * 2)
            val gainStep = 256.0F / max(1, frequency)

            while (!disposed) {
                if (serial != trackSerial) return
                val pendingSeek = seekRequestMs
                if (pendingSeek >= 0L) {
                    seekRequestMs = -1L
                    output.flush()
                    // Re-open the file: skipTo() counts frames from wherever the stream currently is,
                    // so skipping on the live stream made every mid-song seek land at a position that
                    // drifted further the longer the song had played.
                    val fresh = Bitstream(BufferedInputStream(FileInputStream(target), 256 * 1024))
                    val freshHeader = fresh.readFrame()
                    if (freshHeader == null) {
                        fresh.close()
                        return
                    }
                    decoder = Decoder()
                    buffer = decode(decoder, freshHeader, fresh)
                    if (buffer == null) {
                        fresh.close()
                        return
                    }
                    val freshChannels = max(1, min(2, decoder.outputChannels))
                    if (freshChannels != channels) {
                        // A different channel layout cannot be written to the open line; let the track
                        // restart with its own format instead of feeding mismatched PCM.
                        fresh.close()
                        return
                    }
                    closeQuietly(stream)
                    stream = fresh
                    val aligned = LongArray(1)
                    val targetHeader = skipTo(stream!!, frequency, pendingSeek, aligned)
                    baseMs = aligned[0]
                    playedFrames = 0L
                    positionMs = baseMs
                    gain = 0.0F
                    if (targetHeader == null) return
                    buffer = decode(decoder, targetHeader, stream) ?: return
                    continue
                }
                if (!wantPlay) {
                    sleep(25L)
                    level *= 0.70F
                    peak *= 0.70F
                    continue
                }

                val targetGain = volume
                gain = if (gain < targetGain) min(targetGain, gain + gainStep) else max(targetGain, gain - gainStep)

                // JLayer's bufferLength counts *interleaved* samples: bufferp advances by `channels`
                // for every frame. Treating it as a frame count made the position run at 2x on stereo,
                // which is what put the progress bar (and the lyrics) far ahead of the audio.
                val interleaved = buffer!!.bufferLength
                val frames = max(0, interleaved / max(1, channels))
                val bytes = toPcm16(buffer!!.buffer, interleaved, gain, pcm)
                if (bytes > 0) output.write(pcm, 0, bytes)
                playedFrames += frames
                positionMs = baseMs + playedFrames * 1000L / frequency
                updateLevel(buffer!!.buffer, interleaved)

                val next = stream!!.readFrame() ?: break
                buffer = decode(decoder, next, stream) ?: break
            }
            output.drain()
        } finally {
            closeQuietly(stream)
            if (line === output) line = null
            close(output)
        }

        val ended = !disposed && serial == trackSerial && wantPlay
        if (ended) {
            positionMs = if (durationMs > 0L) durationMs else positionMs
            buffering = false
            try {
                listener?.onTrackEnded()
            } catch (ignored: Throwable) {
            }
        }
    }

    /**
     * Reads and throws away frames until `targetMs`, leaving the bitstream on the first frame that
     * belongs to the target position. The returned header has *not* been decoded yet, so the caller
     * decodes it as the first frame to play.
     */
    private fun skipTo(stream: Bitstream, frequency: Int, targetMs: Long, aligned: LongArray): Header? {
        var frames = 0L
        var header: Header? = stream.readFrame()
        while (header != null) {
            val position = frames * 1000L / max(1, frequency)
            if (position >= targetMs) {
                aligned[0] = position
                return header
            }
            frames += if (header.version() == Header.MPEG1) 1152 else 576
            stream.closeFrame()
            header = stream.readFrame()
        }
        aligned[0] = frames * 1000L / max(1, frequency)
        return null
    }

    private fun toPcm16(source: ShortArray, interleaved: Int, gain: Float, out: ByteArray): Int {
        var count = interleaved
        if (count > source.size) count = source.size
        if (count * 2 > out.size) count = out.size / 2
        for (i in 0 until count) {
            var value = (source[i] * gain).toInt()
            if (value > 32767) value = 32767 else if (value < -32768) value = -32768
            out[i * 2] = (value and 0xFF).toByte()
            out[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return count * 2
    }

    private fun updateLevel(source: ShortArray, interleaved: Int) {
        val count = min(interleaved, source.size)
        if (count <= 0) return
        var sum = 0.0
        var peakValue = 0
        for (i in 0 until count) {
            val value = source[i].toInt()
            sum += value.toDouble() * value.toDouble()
            val absolute = abs(value)
            if (absolute > peakValue) peakValue = absolute
        }
        val rms = (sqrt(sum / count) / 32768.0).toFloat()
        level += (rms - level) * 0.35F
        peak = max(peakValue / 32768.0F, peak * 0.82F)
    }

    private fun closeLine() {
        val current = line
        line = null
        close(current)
    }

    private fun decode(decoder: Decoder, header: Header?, stream: Bitstream): SampleBuffer? {
        if (header == null) return null
        val decoded: Obuffer = decoder.decodeFrame(header, stream)
        stream.closeFrame()
        return decoded as? SampleBuffer
    }

    private fun close(output: SourceDataLine?) {
        if (output == null) return
        try {
            output.stop()
            output.flush()
            output.close()
        } catch (ignored: Throwable) {
        }
    }

    private fun closeQuietly(stream: Bitstream?) {
        if (stream != null) {
            try {
                stream.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (ignored: InterruptedException) {
        }
    }

    private fun clamp(value: Float, min: Float, max: Float): Float = if (value < min) min else if (value > max) max else value

    companion object {
        private const val LINE_BUFFER_FRAMES = 8192
    }
}
