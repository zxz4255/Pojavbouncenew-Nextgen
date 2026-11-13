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
 * <p>Based on NeoRaven's audio engine. Decoding runs on a private worker. The default output is
 * [SourceDataLine]; an optional experimental output uses Minecraft's managed streaming channel and
 * does not create or switch an OpenAL context on the decoder thread.</p>
 *
 * <p>The engine only ever plays a local file; downloading (and resolving the signed NetEase URL)
 * belongs to [MusicManager], so a playlist change never blocks the audio thread. Position comes from
 * frames submitted to the output rather than wall time; buffered audio can make position lead what
 * the listener hears, particularly with the experimental Minecraft output.</p>
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

    @Volatile private var minecraftOutput = false
    @Volatile private var activeMinecraftOutput: MinecraftAudioOutput? = null
    @Volatile private var minecraftBaseMs = 0L
    @Volatile private var minecraftFrequency = 1
    @Volatile private var seeking = false
    @Volatile private var javaBaseFrame = 0L
    @Volatile private var javaBaseMs = 0L
    @Volatile private var javaFrequency = 1
    @Volatile private var outputDescription = "Java Sound"
    fun setMinecraftOutput(value: Boolean) { minecraftOutput = value }
    fun minecraftOutput(): Boolean = minecraftOutput
    fun outputDescription(): String = outputDescription

    @Volatile private var viper = ViperSettings()
    @Volatile private var viperStatus = "关闭"
    internal fun viperSettings(): ViperSettings = viper
    fun viperStatus(): String = viperStatus
    internal fun setViperSettings(value: ViperSettings) {
        fun safe(v: Float, low: Float, high: Float, fallback: Float) =
            if (v.isFinite()) v.coerceIn(low, high) else fallback
        viper = value.copy(
            bassMode = value.bassMode.coerceIn(0, 2), bassFrequency = value.bassFrequency.coerceIn(15, 150),
            bassGain = safe(value.bassGain, 0.5f, 10f, 0.5f), clarityMode = value.clarityMode.coerceIn(0, 2),
            clarityGain = safe(value.clarityGain, 0f, 4.5f, 0.5f), reverbWet = safe(value.reverbWet, 0f, 1f, 0.2f),
            curePreset = value.curePreset.coerceIn(0, 2),
            agcStrength = safe(value.agcStrength, 0.5f, 3f, 1f),
            lufsTarget = safe(value.lufsTarget, -24f, -8f, -14f),
            spectrumFrequency = value.spectrumFrequency.coerceIn(2200, 8200),
            spectrumExciter = safe(value.spectrumExciter, 0f, 6f, 0f),
            fieldWidth = safe(value.fieldWidth, 0f, 8f, 0f),
            diffDelay = safe(value.diffDelay, 1f, 20f, 5f),
            stereoWidth = safe(value.stereoWidth, 0f, 200f, 100f),
            dynamicStrength = safe(value.dynamicStrength, 1f, 8f, 1f),
            analogMode = value.analogMode.coerceIn(0, 1), headphoneQuality = value.headphoneQuality.coerceIn(0, 4),
            jdspPreset = value.jdspPreset.coerceIn(0, 18),
            limiterThreshold = safe(value.limiterThreshold, 0.3f, 1f, 1f),
            channelPan = safe(value.channelPan, -1f, 1f, 0f),
            bands = List(10) { safe(value.bands.getOrElse(it) { 0f }, -12f, 12f, 0f) },
        )
        if (!value.enabled) viperStatus = "关闭"
        else if (file == null) viperStatus = "等待播放"
    }

    @Volatile private var tone = ToneSettings()

    internal fun toneSettings(): ToneSettings = tone

    internal fun setToneSettings(value: ToneSettings) {
        fun finite(value: Float) = if (value.isFinite()) value.coerceIn(-9f, 9f) else 0f
        tone = ToneSettings(value.enabled, finite(value.low), finite(value.mid), finite(value.high))
    }

    @Volatile private var gain = 0.0F

    @Volatile private var positionMs = 0L

    @Volatile private var durationMs = 0L

    @Volatile private var level = 0.0F

    @Volatile private var peak = 0.0F

    @Volatile private var error: String? = null

    @Volatile private var buffering = false
    @Volatile private var bufferStartedAt = 0L
    fun bufferingForMs(): Long = if (buffering && bufferStartedAt > 0L)
        (System.currentTimeMillis() - bufferStartedAt).coerceAtLeast(0L) else 0L

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

    fun positionMs(): Long {
        if (seekRequestMs >= 0L || seeking) return positionMs
        val active = activeMinecraftOutput
        val javaLine = line
        val estimated = when {
            active != null -> minecraftBaseMs + active.playedFrames() * 1000L / max(1, minecraftFrequency)
            javaLine != null -> {
                val frame = javaLine.longFramePosition
                if (frame < 0L) positionMs else javaBaseMs + max(0L, frame - javaBaseFrame) * 1000L / max(1, javaFrequency)
            }
            else -> positionMs
        }
        return if (durationMs > 0L) estimated.coerceIn(0L, durationMs) else max(0L, estimated)
    }

    /** Smoothed RMS of the most recent chunk (0..1), used to pulse the lyric view. */
    fun level(): Float = level

    /** Instantaneous peak of the most recent chunk (0..1), decays over ~200 ms. */
    fun peak(): Float = peak

    fun isBuffering(): Boolean = buffering

    fun error(): String? = error

    fun isPlaying(): Boolean = wantPlay && file != null

    fun hasTrack(): Boolean = file != null

    /** Cache currently held for playback or pause; used to protect it from periodic cleanup. */
    internal fun cachedFileInUse(): File? = file

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
            bufferStartedAt = System.currentTimeMillis()
            wantPlay = true
            gain = 0.0F
            trackSerial++
            ensureThread()
            wake.signalAll()
        }
    }

    fun pause() {
        wantPlay = false
        activeMinecraftOutput?.pause(true)
        try { line?.stop() } catch (_: Exception) { }
    }

    fun resume() {
        if (file != null) {
            wantPlay = true
            activeMinecraftOutput?.pause(false)
            try { line?.start() } catch (_: Exception) { }
        }
    }

    fun stop() {
        lock.withLock {
            wantPlay = false
            file = null
            buffering = false
            error = null
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
        var recoverySerial = -1L
        var recoveryCount = 0
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
            if (serial != recoverySerial) { recoverySerial = serial; recoveryCount = 0 }
            try {
                runTrack(next, serial)
            } catch (t: Throwable) {
                if (t is MinecraftOutputCancelled && !disposed && serial == trackSerial && seekRequestMs >= 0L) continue
                if (t is MinecraftOutputInterrupted && !disposed && serial == trackSerial && recoveryCount < 2) {
                    recoveryCount++
                    // Preserve a newer explicit user seek instead of replacing it with recovery position.
                    if (seekRequestMs < 0L) seekRequestMs = positionMs
                    buffering = true
                    sleep(300L)
                    continue
                }
                // A failed track must not stay in `file`: otherwise the loop immediately retries it,
                // resets `error`, and leaves the UI stuck on decoding indefinitely. A stale failure
                // from a replaced track must not clear the replacement's state.
                lock.withLock {
                    if (serial == trackSerial) {
                        error = t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")
                        System.err.println("[Music audio] " + error)
                        file = null
                        buffering = false
                        wantPlay = false
                        level = 0.0F
                        peak = 0.0F
                    }
                }
                closeLine()
            }
        }
        closeLine()
    }

    private fun runTrack(target: File, serial: Long) {
        val ogg = FileInputStream(target).use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 && magic.contentEquals(byteArrayOf(79, 103, 103, 83))
        }
        if (ogg) { runOggTrack(target, serial); return }
        lock.withLock {
            if (serial != trackSerial) return
            buffering = true
            error = null
            level = 0.0F
            peak = 0.0F
        }

        var stream: Bitstream? = null
        var output: SourceDataLine? = null
        var mcOutput: MinecraftAudioOutput? = null
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
                if (targetHeader == null) throw java.io.IOException("seek beyond available audio")
                buffer = decode(decoder, targetHeader, stream) ?: throw java.io.IOException("unable to decode seek target")
            }

            val format = AudioFormat(frequency.toFloat(), 16, channels, true, false)
            if (minecraftOutput) {
                try {
                    mcOutput = MinecraftAudioOutput(format) { disposed || serial != trackSerial || seekRequestMs >= 0L }
                    minecraftFrequency = frequency
                    minecraftBaseMs = baseMs
                    activeMinecraftOutput = mcOutput
                    MusicSoundLifecycle.resumeHook = { if (!wantPlay) activeMinecraftOutput?.pause(true) }
                    if (!wantPlay) mcOutput.pause(true)
                    outputDescription = "Minecraft（实验）"
                } catch (e: Exception) {
                    if (disposed || serial != trackSerial || seekRequestMs >= 0L) return
                    System.err.println("[Music audio] Minecraft output unavailable, trying Java Sound: $e")
                    outputDescription = "Java Sound（Minecraft 输出初始化失败）"
                }
            } else {
                outputDescription = "Java Sound"
            }
            if (mcOutput == null) {
                val info = DataLine.Info(SourceDataLine::class.java, format)
                output = AudioSystem.getLine(info) as SourceDataLine
                output.open(format, LINE_BUFFER_FRAMES * channels * 2)
                javaFrequency = frequency
                javaBaseMs = baseMs
                javaBaseFrame = output.longFramePosition
                if (wantPlay) output.start()
                line = output
                if (!wantPlay) output.stop()
            }
            buffering = false
            positionMs = baseMs

            var playedFrames = 0L
            val pcm = ByteArray(1152 * 2 * 2)
            val equalizer = MusicEqualizer(frequency, channels)
            var viperProcessor: MusicViper? = null
            var viperFailed = false
            val gainStep = 256.0F / max(1, frequency)

            while (!disposed) {
                if (serial != trackSerial) return
                val pendingSeek = seekRequestMs
                if (pendingSeek >= 0L) {
                    seeking = true
                    seekRequestMs = -1L
                    if (mcOutput != null) mcOutput.flush() else output?.flush()
                    equalizer.reset()
                    try { viperProcessor?.reset() } catch (e: Exception) {
                        viperProcessor = null; viperFailed = true
                        viperStatus = "重置失败，已旁路：${e.javaClass.simpleName}"
                    }
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
                    minecraftBaseMs = baseMs
                    javaBaseMs = baseMs
                    javaBaseFrame = output?.longFramePosition ?: 0L
                    playedFrames = 0L
                    positionMs = baseMs
                    gain = 0.0F
                    if (targetHeader == null) throw java.io.IOException("seek beyond available audio")
                    buffer = decode(decoder, targetHeader, stream) ?: throw java.io.IOException("unable to decode seek target")
                    seeking = false
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
                val settings = viper
                var input = buffer!!.buffer
                if (!settings.enabled) {
                    viperProcessor = null
                    viperFailed = false
                    viperStatus = "关闭"
                } else if (!viperFailed) {
                    try {
                        val processor = viperProcessor ?: MusicViper(frequency, channels).also { viperProcessor = it }
                        input = processor.process(input, interleaved, settings)
                        val limitations = mutableListOf<String>()
                        if (settings.eq && frequency < 44100) limitations.add("EQ")
                        if (settings.headphone && frequency != 44100 && frequency != 48000) limitations.add("耳机环绕")
                        if (settings.spectrum && settings.spectrumFrequency >= frequency / 2) limitations.add("频谱扩展")
                        viperStatus = if (limitations.isEmpty()) "运行中" else "运行中；${limitations.joinToString("、")} 旁路（采样率）"
                    } catch (e: Exception) {
                        viperProcessor = null
                        viperFailed = true
                        viperStatus = "处理失败，已旁路：${e.javaClass.simpleName}"
                        System.err.println("[Music ViPER] bypass: $e")
                    }
                }
                val bytes = equalizer.process(input, interleaved, gain, pcm, tone)
                if (bytes > 0) {
                    if (mcOutput != null) mcOutput.write(pcm, bytes) else output?.write(pcm, 0, bytes)
                }
                playedFrames += frames
                if (seekRequestMs < 0L) {
                    positionMs = if (mcOutput == null && (output?.longFramePosition ?: -1L) < 0L)
                        baseMs + playedFrames * 1000L / frequency else positionMs()
                }
                updateLevel(buffer!!.buffer, interleaved)

                val next = stream!!.readFrame() ?: break
                buffer = decode(decoder, next, stream) ?: break
            }
            if (mcOutput != null) mcOutput.drain() else output?.drain()
            if (seekRequestMs >= 0L) return
        } finally {
            seeking = false
            closeQuietly(stream)
            if (mcOutput != null && activeMinecraftOutput === mcOutput) {
                if (seekRequestMs < 0L) positionMs = positionMs()
                activeMinecraftOutput = null
                MusicSoundLifecycle.resumeHook = null
            }
            mcOutput?.close()
            if (line === output) {
                if (output != null && seekRequestMs < 0L) positionMs = positionMs()
                line = null
            }
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

    /** Ogg/Vorbis PCM supplied by the real Minecraft 26.3 JOrbis decoder. */
    private fun runOggTrack(target: File, serial: Long) {
        lock.withLock {
            if (serial != trackSerial) return
            buffering = true; error = null; level = 0f; peak = 0f
        }
        val input = BufferedInputStream(FileInputStream(target), 256 * 1024)
        val source = try { net.minecraft.client.sounds.JOrbisAudioStream(input) }
            catch (t: Throwable) { input.close(); throw t }
        var output: SourceDataLine? = null
        var mcOutput: MinecraftAudioOutput? = null
        try {
            val sourceFormat = source.format
            val frequency = sourceFormat.sampleRate.toInt()
            val channels = sourceFormat.channels
            if (frequency <= 0 || channels !in 1..2) throw java.io.IOException("unsupported Ogg audio format")
            val format = AudioFormat(frequency.toFloat(), 16, channels, true, false)
            val requested = seekRequestMs.coerceAtLeast(0L)
            seekRequestMs = -1L
            var skipFrames = requested * frequency / 1000L
            var remaining = java.nio.ByteBuffer.allocate(0)
            while (skipFrames > 0L) {
                if (disposed || serial != trackSerial || seekRequestMs >= 0L) return
                remaining = source.read(8192).order(java.nio.ByteOrder.nativeOrder())
                if (!remaining.hasRemaining()) throw java.io.IOException("seek beyond available Ogg audio")
                val skip = min(skipFrames, (remaining.remaining() / (channels * 2)).toLong()).toInt()
                remaining.position(remaining.position() + skip * channels * 2)
                skipFrames -= skip
            }
            val baseMs = requested
            if (minecraftOutput) {
                try {
                    mcOutput = MinecraftAudioOutput(format) { disposed || serial != trackSerial || seekRequestMs >= 0L }
                    minecraftFrequency = frequency; minecraftBaseMs = baseMs; activeMinecraftOutput = mcOutput
                    MusicSoundLifecycle.resumeHook = { if (!wantPlay) activeMinecraftOutput?.pause(true) }
                    if (!wantPlay) mcOutput.pause(true)
                    outputDescription = "Minecraft（实验）"
                } catch (e: Exception) {
                    if (disposed || serial != trackSerial || seekRequestMs >= 0L) return
                    outputDescription = "Java Sound（Minecraft 输出初始化失败）"
                }
            } else outputDescription = "Java Sound"
            if (mcOutput == null) {
                output = AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine
                output.open(format, LINE_BUFFER_FRAMES * channels * 2)
                javaFrequency = frequency; javaBaseMs = baseMs; javaBaseFrame = output.longFramePosition
                line = output
                if (wantPlay) output.start() else output.stop()
            }
            buffering = false; positionMs = baseMs; seeking = false
            val samples = ShortArray(1152 * channels)
            val pcm = ByteArray(samples.size * 2)
            val equalizer = MusicEqualizer(frequency, channels)
            var processor: MusicViper? = null
            var viperFailed = false
            var playedFrames = 0L
            val gainStep = 256.0F / max(1, frequency)
            while (!disposed && serial == trackSerial) {
                // Reopen and decode-skip on the next iteration of the worker loop.
                if (seekRequestMs >= 0L) { seeking = true; return }
                if (!wantPlay) { sleep(25L); level *= 0.70F; peak *= 0.70F; continue }
                if (remaining.remaining() < channels * 2) {
                    remaining = source.read(8192).order(java.nio.ByteOrder.nativeOrder())
                    if (!remaining.hasRemaining()) break
                }
                val count = min(samples.size, remaining.remaining() / 2)
                val aligned = count - count % channels
                if (aligned <= 0) throw java.io.IOException("unaligned Ogg audio data")
                for (i in 0 until aligned) samples[i] = remaining.short
                val targetGain = volume
                gain = if (gain < targetGain) min(targetGain, gain + gainStep) else max(targetGain, gain - gainStep)
                var processed = samples
                val settings = viper
                if (!settings.enabled) { processor = null; viperFailed = false; viperStatus = "关闭" }
                else if (!viperFailed) {
                    try {
                        val active = processor ?: MusicViper(frequency, channels).also { processor = it }
                        processed = active.process(samples, aligned, settings)
                        viperStatus = "运行中"
                    } catch (e: Exception) {
                        processor = null; viperFailed = true; viperStatus = "处理失败，已旁路：${e.javaClass.simpleName}"
                    }
                }
                val bytes = equalizer.process(processed, aligned, gain, pcm, tone)
                if (bytes > 0) { if (mcOutput != null) mcOutput.write(pcm, bytes) else output?.write(pcm, 0, bytes) }
                playedFrames += aligned / channels
                if (seekRequestMs < 0L) {
                    positionMs = if (mcOutput == null && (output?.longFramePosition ?: -1L) < 0L)
                        baseMs + playedFrames * 1000L / frequency else positionMs()
                }
                updateLevel(samples, aligned)
            }
            if (mcOutput != null) mcOutput.drain() else output?.drain()
            if (seekRequestMs >= 0L || disposed || serial != trackSerial) return
        } finally {
            seeking = false
            source.close()
            if (mcOutput != null && activeMinecraftOutput === mcOutput) {
                if (seekRequestMs < 0L) positionMs = positionMs()
                activeMinecraftOutput = null; MusicSoundLifecycle.resumeHook = null
            }
            mcOutput?.close()
            if (line === output) {
                if (output != null && seekRequestMs < 0L) positionMs = positionMs()
                line = null
            }
            close(output)
        }
        if (!disposed && serial == trackSerial && wantPlay) {
            positionMs = if (durationMs > 0L) durationMs else positionMs
            buffering = false
            try { listener?.onTrackEnded() } catch (_: Throwable) { }
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
