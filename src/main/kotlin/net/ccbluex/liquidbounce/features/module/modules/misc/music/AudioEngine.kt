package net.ccbluex.liquidbounce.features.module.modules.misc.music

import net.ccbluex.liquidbounce.utils.audio.android.AndroidAudioSession
import net.ccbluex.liquidbounce.utils.audio.parser.MusicAudioParserFactory
import net.ccbluex.liquidbounce.utils.audio.parser.PcmAudioParser
import java.io.File
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * File-backed music playback for the music player.
 *
 * <p>Decoding runs on a private worker and always goes through the bundled native library behind
 * [MusicAudioParserFactory] (`libliquidbounce_audiocodec.so`): Ogg/Vorbis, Ogg/Opus, MP3, FLAC and
 * MP4/DASH audio. There is no Java decoder and no Java Sound in this path at all: the only output is
 * a module-owned native device ([AndroidAudioOutput], backed by `libliquidbounce_audioout.so`).</p>
 *
 * <p>Why the output is bound to the module instead of Minecraft's mixer: OpenAL streaming channels
 * live inside the game's sound library, and the game releases every one of them on a dimension
 * change, respawn, world join or resource reload — which showed up as playback stopping and then
 * recovering a moment later. The native sink has no relationship to the level, so a
 * dimension change, a long-distance teleport or a sound-device switch cannot interrupt it. The
 * worker is also told to ignore world-change notifications outright ([handleWorldReload]).</p>
 *
 * <p>The engine only ever plays a local file; downloading (and resolving the signed provider URL)
 * belongs to [MusicManager], so a playlist change never blocks the audio thread. Position comes from
 * frames the device reports as played.</p>
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

    // The only output is the Android platform sink. There is no desktop fallback on purpose: the
    // launcher's Java Sound / OpenAL device is what the module used to fight over.
    @Volatile private var activeAndroidOutput: AndroidAudioOutput? = null
    @Volatile private var androidBaseMs = 0L
    @Volatile private var androidFrequency = 1
    @Volatile private var seeking = false
    @Volatile private var outputDescription = if (AndroidAudioSession.isAndroidRuntime())
        "原生音频输出（待启动）" else "原生音频输出不可用（非 Android 运行时）"

    fun outputDescription(): String = outputDescription

    /** Kept for config compatibility; Android always uses the platform sink. */
    fun setMinecraftOutput(@Suppress("UNUSED_PARAMETER") value: Boolean) = Unit

    fun minecraftOutput(): Boolean = true

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
    @Volatile private var bufferStage = "等待音频线程"
    fun bufferingStage(): String = bufferStage
    private val diagnostics = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "liquidbounce-music-diagnostics").apply { isDaemon = true }
    }
    private fun stage(serial: Long, text: String) {
        if (serial != trackSerial) return
        bufferStage = text
        System.err.println("[Music audio] stage=$text")
    }
    private fun diagnoseIfStuck(serial: Long) {
        diagnostics.schedule({
            if (!disposed && serial == trackSerial && buffering) {
                val worker = thread
                System.err.println("[Music audio] startup still waiting; stage=$bufferStage, worker=" +
                    (worker?.state ?: "missing"))
                worker?.stackTrace?.take(12)?.forEach {
                    System.err.println("[Music audio] at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})")
                }
            }
        }, 15, java.util.concurrent.TimeUnit.SECONDS)
    }
    fun bufferingForMs(): Long = if (buffering && bufferStartedAt > 0L)
        (System.currentTimeMillis() - bufferStartedAt).coerceAtLeast(0L) else 0L

    @Volatile private var listener: Listener? = null

    private var thread: Thread? = null

    @Volatile private var trackSerial = 0L

    fun setListener(value: Listener?) {
        listener = value
    }

    fun setVolume(value: Float) {
        // The device source gain stays at 1.0; the module level is applied while decoding so the
        // device volume slider and the module slider do not multiply into each other.
        volume = clamp(value, 0.0F, 1.0F)
    }

    fun volume(): Float = volume

    fun setDurationMs(value: Long) {
        durationMs = max(0L, value)
    }

    fun durationMs(): Long = durationMs

    fun positionMs(): Long {
        if (seekRequestMs >= 0L || seeking) return positionMs
        val active = activeAndroidOutput
        // The device clock is the truth for what the listener actually hears.
        val estimated = if (active != null)
            androidBaseMs + active.playedFrames() * 1000L / max(1, androidFrequency)
        else positionMs
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
            bufferStage = "等待音频线程"
            ensureThread()
            diagnoseIfStuck(trackSerial)
            wake.signalAll()
        }
    }

    fun pause() {
        wantPlay = false
        activeAndroidOutput?.pause(true)
    }

    fun resume() {
        if (file != null) {
            wantPlay = true
            activeAndroidOutput?.pause(false)
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
        lock.withLock {
            seekRequestMs = target
            // The worker has to decode-skip to the target; reflect it immediately so the bar does not
            // snap back to the old position while that happens.
            positionMs = target
            wake.signalAll()
        }
    }

    /**
     * A world change is a no-op for this output.
     *
     * <p>The Android sink is not part of Minecraft's sound library, so nothing the game tears down on
     * a dimension change, respawn, world join or long-distance teleport can reach it. This hook is kept
     * because [MusicManager] and [ModuleMusic] call it; it deliberately does <b>not</b> close the
     * device, which is what used to cause the momentary stop-and-resume.</p>
     */
    fun handleWorldReload() {
        if (file == null) return
        // Reassert playback state only if the module believes it is playing; never rebuild the sink.
        val active = activeAndroidOutput ?: return
        if (wantPlay && active.isStarted() && !active.isPaused()) {
            // Nothing to do: PCM keeps flowing into the same device.
            return
        }
        if (!wantPlay) {
            active.pause(true)
        }
    }

    /**
     * The game released its sound library because the client is closing.
     *
     * <p>The Android sink is independent of that library, but the module still has to stop on exit;
     * otherwise the device would keep the decoded tail playing after the window is gone.</p>
     */
    fun handleClientShutdown() {
        disposed = true
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
        releaseOutputs()
        thread?.interrupt()
    }

    /** Releases the audio output and stops the worker. */
    fun dispose() {
        disposed = true
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
        releaseOutputs()
        thread?.interrupt()
        diagnostics.shutdownNow()
    }

    /** Closes whatever output is currently attached; safe to call repeatedly and from any thread. */
    private fun releaseOutputs() {
        val android = activeAndroidOutput
        activeAndroidOutput = null
        try {
            android?.close()
        } catch (_: Throwable) {
        }
        // Also silence a sink that was registered but not yet reached the engine fields.
        AndroidAudioSession.silenceActive()
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
                // A pending seek wins over an input-cancellation or output-reload exception.
                if (!disposed && serial == trackSerial && seekRequestMs >= 0L) continue
                // A device that was reset (or a write that failed) is a temporary output problem,
                // never a decode failure: keep the track, keep the position and rebuild the sink.
                if (isOutputFailure(t) && !disposed && serial == trackSerial) {
                    recoveryCount++
                    requestSeekIfUnset(positionMs)
                    buffering = true
                    bufferStartedAt = System.currentTimeMillis()
                    bufferStage = if (recoveryCount == 1) "音频设备重连中" else
                        "音频设备重连中（第 $recoveryCount 次）"
                    System.err.println("[Music audio] output interrupted, reopening sink: " + t.message)
                    // Never give the track up: an unrecovered output keeps retrying with a capped
                    // backoff, which is what keeps a scripted respawn or a dimension change inaudible.
                    sleep((120L * recoveryCount.coerceAtMost(8)).coerceAtMost(1_000L))
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
            }
        }
        releaseOutputs()
    }

    /**
     * True when the throwable comes from the output path rather than the decoder.
     *
     * <p>Only output problems are retried in place. A genuinely broken file must still be reported so
     * [MusicManager] can refresh its cache and re-resolve the source.</p>
     */
    private fun isOutputFailure(t: Throwable): Boolean {
        var cause: Throwable? = t
        repeat(8) {
            val current = cause ?: return false
            if (current is AndroidOutputInterrupted) return true
            if (current is java.io.IOException && isOutputMessage(current.message)) return true
            cause = current.cause
        }
        return false
    }

    /**
     * True when an [java.io.IOException] message describes an output problem rather than a decode
     * problem. Kept next to [isOutputFailure] so a wording change in the sink cannot silently turn a
     * recoverable device drop into "this file is broken".
     */
    private fun isOutputMessage(message: String?): Boolean {
        val text = message ?: return false
        return text.contains("原生音频") || text.contains("AudioTrack") || text.contains("音频输出")
    }

    private fun runTrack(target: File, serial: Long) {
        MusicSpectrumAnalyzer.beginTrack()
        stage(serial, "检查音频格式")
        when (MusicAudioParserFactory.detect(target)) {
            // Every supported format goes through the same PCM pipeline, decoded by the bundled
            // native library (libliquidbounce_audiocodec.so); no platform codec or Java decoder.
            MusicAudioParserFactory.Codec.MP3,
            MusicAudioParserFactory.Codec.FLAC,
            MusicAudioParserFactory.Codec.MP4_AAC,
            MusicAudioParserFactory.Codec.OGG_VORBIS -> runPcmTrack(target, serial)
            else -> throw java.io.IOException("音频格式无法识别：仅支持 MP3、FLAC、MP4/AAC 与 Ogg/Vorbis")
        }
    }

    /** Compressed tracks use independent, signature-selected parser instances (all native). */
    private fun runPcmTrack(target: File, serial: Long) {
        lock.withLock {
            if (serial != trackSerial) return
            buffering = true
            error = null
            level = 0.0F
            peak = 0.0F
        }

        var parser: PcmAudioParser? = null
        var output: AndroidAudioOutput? = null
        try {
            val requestedStartMs = consumeSeekRequest().coerceAtLeast(0L)
            // Ogg/Vorbis and MP4/AAC come from the bundled .so; the stage text names which decoder
            // is actually in charge so a Pojav log is enough to diagnose a format complaint.
            stage(serial, "初始化解码器")
            parser = MusicAudioParserFactory.open(
                target,
                requestedStartMs,
                java.util.function.BooleanSupplier { disposed || serial != trackSerial || seekRequestMs >= 0L },
            )
            if (disposed || serial != trackSerial) return

            val frequency = parser.sampleRate
            val channels = parser.channels
            if (frequency !in 8_000..192_000 || channels !in 1..2) {
                throw java.io.IOException("不支持的 PCM 参数：$frequency Hz / $channels 声道")
            }
            val baseMs = parser.positionSamples * 1000L / frequency
            val totalSamples = parser.totalSamples
            if (durationMs <= 0L && totalSamples > 0L) durationMs = totalSamples * 1000L / frequency

            stage(serial, "打开原生音频输出")
            val sink = try {
                AndroidAudioOutput(frequency, channels) { disposed || serial != trackSerial || seekRequestMs >= 0L }
            } catch (failure: Exception) {
                if (disposed || serial != trackSerial || seekRequestMs >= 0L) return
                // Surface the real reason (no native .so, no AAudio, no OpenSL ES, ...) instead of a
                // generic line, which is what made this retry loop undiagnosable from a log.
                throw java.io.IOException(
                    "音频输出不可用：${failure.message ?: failure.javaClass.simpleName}", failure)
            }
            output = sink
            androidFrequency = frequency
            androidBaseMs = baseMs
            activeAndroidOutput = sink
            if (AndroidAudioSession.isAndroidRuntime()) {
                outputDescription = net.ccbluex.liquidbounce.utils.audio.android.AndroidAudioTrackOutput.describe()
            }
            if (!wantPlay) sink.pause(true)

            stage(serial, "Android PCM 输出已就绪")
            positionMs = baseMs
            var firstWrite = true
            var playedFrames = 0L
            val maxChunkSamples = 1152 * channels
            val pcm = ByteArray(maxChunkSamples * 2)
            val equalizer = MusicEqualizer(frequency, channels)
            var viperProcessor: MusicViper? = null
            var viperFailed = false
            val gainStep = 256.0F / max(1, frequency)

            while (!disposed && serial == trackSerial) {
                if (seekRequestMs >= 0L) {
                    seeking = true
                    return
                }
                if (!wantPlay) {
                    sleep(25L)
                    level *= 0.70F
                    peak *= 0.70F
                    continue
                }

                val decoded = parser.readFrame() ?: break
                if (decoded.isEmpty()) continue
                if (decoded.size % channels != 0) throw java.io.IOException("解码器返回了未对齐的 PCM 帧")

                var offset = 0
                while (offset < decoded.size) {
                    if (disposed || serial != trackSerial) return
                    if (seekRequestMs >= 0L) {
                        seeking = true
                        return
                    }
                    if (!wantPlay) {
                        sleep(25L)
                        level *= 0.70F
                        peak *= 0.70F
                        continue
                    }

                    val count = min(maxChunkSamples, decoded.size - offset)
                    val interleaved = count - count % channels
                    if (interleaved <= 0) throw java.io.IOException("PCM 帧声道对齐失败")
                    val raw = if (offset == 0 && interleaved == decoded.size) decoded
                        else decoded.copyOfRange(offset, offset + interleaved)

                    val targetGain = volume
                    gain = if (gain < targetGain) min(targetGain, gain + gainStep) else max(targetGain, gain - gainStep)

                    val settings = viper
                    var processed = raw
                    if (!settings.enabled) {
                        viperProcessor = null
                        viperFailed = false
                        viperStatus = "关闭"
                    } else if (!viperFailed) {
                        try {
                            val processor = viperProcessor ?: MusicViper(frequency, channels).also { viperProcessor = it }
                            processed = processor.process(raw, interleaved, settings)
                            val limitations = mutableListOf<String>()
                            if (settings.eq && frequency < 44100) limitations.add("EQ")
                            if (settings.headphone && frequency != 44100 && frequency != 48000) limitations.add("耳机环绕")
                            if (settings.spectrum && settings.spectrumFrequency >= frequency / 2) limitations.add("频谱扩展")
                            viperStatus = if (limitations.isEmpty()) "运行中" else
                                "运行中；${limitations.joinToString("、")} 旁路（采样率）"
                        } catch (e: Exception) {
                            viperProcessor = null
                            viperFailed = true
                            viperStatus = "处理失败，已旁路：${e.javaClass.simpleName}"
                            System.err.println("[Music ViPER] bypass: $e")
                        }
                    }

                    val bytes = equalizer.process(processed, interleaved, gain, pcm, tone)
                    if (bytes > 0) {
                        if (firstWrite) stage(serial, "写入首段 PCM")
                        sink.write(pcm, bytes)
                        if (disposed || serial != trackSerial) return
                        MusicSpectrumAnalyzer.accept(processed, interleaved, channels, frequency)
                        if (firstWrite && sink.isStarted()) {
                            firstWrite = false
                            buffering = false
                        }
                    }

                    val frames = interleaved / channels
                    playedFrames += frames
                    if (seekRequestMs < 0L) positionMs = positionMs()
                    updateLevel(raw, interleaved)
                    offset += interleaved
                }
            }

            // A paused device cannot drain; wait for resume, or retire immediately for a seek/stop.
            while (!wantPlay && !disposed && serial == trackSerial && seekRequestMs < 0L) sleep(20L)
            if (disposed || serial != trackSerial || seekRequestMs >= 0L) return
            sink.drain()
            if (seekRequestMs >= 0L) return
        } finally {
            seeking = false
            try { parser?.close() } catch (_: Throwable) { }
            if (output != null && activeAndroidOutput === output) {
                if (seekRequestMs < 0L) positionMs = positionMs()
                activeAndroidOutput = null
            }
            try { output?.close() } catch (_: Throwable) { }
        }

        val ended = !disposed && serial == trackSerial && wantPlay && seekRequestMs < 0L
        if (ended) {
            positionMs = if (durationMs > 0L) durationMs else positionMs
            buffering = false
            try { listener?.onTrackEnded() } catch (_: Throwable) { }
        }
    }

    /** Atomically consumes one seek request so a newer drag/seek cannot be overwritten. */
    private fun consumeSeekRequest(): Long = lock.withLock {
        val requested = seekRequestMs
        if (requested >= 0L) seekRequestMs = -1L
        requested
    }

    private fun requestSeekIfUnset(position: Long) = lock.withLock {
        if (seekRequestMs < 0L) seekRequestMs = max(0L, position)
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

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (ignored: InterruptedException) {
        }
    }

    private fun clamp(value: Float, min: Float, max: Float): Float =
        if (value < min) min else if (value > max) max else value

    companion object {
        internal fun androidRuntime(): Boolean = AndroidAudioSession.isAndroidRuntime()
    }
}
