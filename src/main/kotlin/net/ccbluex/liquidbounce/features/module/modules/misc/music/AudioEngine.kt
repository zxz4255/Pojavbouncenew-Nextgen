package net.ccbluex.liquidbounce.features.module.modules.misc.music

import net.ccbluex.liquidbounce.utils.audio.parser.MusicAudioParserFactory
import net.ccbluex.liquidbounce.utils.audio.parser.PcmAudioParser
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
 * File-backed music playback for the music player.
 *
 * <p>Decoding runs on a private worker. MP3 and FLAC have independent parser sessions; Ogg/Vorbis
 * uses Minecraft's JOrbis decoder. The default output is [SourceDataLine]; the Android/Pojav path
 * uses a managed, module-owned Minecraft streaming channel and never changes the global listener.</p>
 *
 * <p>The engine only ever plays a local file; downloading (and resolving the signed NetEase URL)
 * belongs to [MusicManager], so a playlist change never blocks the audio thread. Position comes from
 * frames submitted to the output rather than wall time; buffered audio can make position lead what
 * the listener hears, particularly with the managed Minecraft output.</p>
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

    // Android: a dedicated channel on Minecraft's existing mixer avoids opening a second
    // launcher Java Sound/OpenAL output. Desktop retains the previous Java Sound default.
    @Volatile private var minecraftOutput = androidRuntime()
    @Volatile private var activeMinecraftOutput: MinecraftAudioOutput? = null
    @Volatile private var minecraftBaseMs = 0L
    @Volatile private var minecraftFrequency = 1
    @Volatile private var seeking = false
    @Volatile private var outputDescription = if (androidRuntime()) "Minecraft 独立声道（待启动）" else "Java Sound"
    fun setMinecraftOutput(value: Boolean) {
        // Pojav/Android must use the client's own managed channel; opening Java Sound can create a
        // second launcher mixer and interfere with the device/OpenAL route.
        minecraftOutput = value || androidRuntime()
    }
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
        // Java Sound uses the submitted-frame clock, as in the working DroidBridge version.
        // Do not call provider methods on the render/UI thread.
        val estimated = if (active != null)
            minecraftBaseMs + active.playedFrames() * 1000L / max(1, minecraftFrequency)
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
        activeMinecraftOutput?.pause(true)
        // On Android keep the known-working baseline behaviour: stop submitting samples,
        // rather than invoking a launcher audio provider synchronously on the UI thread.
        if (!androidRuntime()) try { line?.stop() } catch (_: Exception) { }
    }

    fun resume() {
        if (file != null) {
            wantPlay = true
            activeMinecraftOutput?.pause(false)
            if (!androidRuntime()) try { line?.start() } catch (_: Exception) { }
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

    fun dispose() {
        disposed = true
        stop()
        thread?.interrupt()
        diagnostics.shutdownNow()
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
                if (t is MinecraftOutputInterrupted && !disposed && serial == trackSerial && recoveryCount < 6) {
                    recoveryCount++
                    // Preserve a newer explicit user seek instead of replacing it with recovery position.
                    requestSeekIfUnset(positionMs)
                    buffering = true
                    // A sound-device/channel reload can outlast one tick; use bounded backoff rather
                    // than failing the whole track after the first transient Minecraft channel loss.
                    sleep((250L * recoveryCount).coerceAtMost(1_500L))
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
        MusicSpectrumAnalyzer.beginTrack()
        stage(serial, "检查音频格式")
        when (MusicAudioParserFactory.detect(target)) {
            MusicAudioParserFactory.Codec.OGG_VORBIS -> runOggTrack(target, serial)
            MusicAudioParserFactory.Codec.MP3,
            MusicAudioParserFactory.Codec.FLAC,
            MusicAudioParserFactory.Codec.MP4_AAC -> runPcmTrack(target, serial)
            else -> throw java.io.IOException("音频格式无法识别：仅支持 MP3、FLAC、MP4/AAC 与 Ogg/Vorbis")
        }
    }

    /**
     * Compressed tracks use independent, signature-selected parser instances. On Android the
     * factory prefers the platform MediaCodec native decoders and falls back to Java parsers;
     * Ogg/Vorbis remains on Minecraft's JOrbis path. Decoders read only the requested file, while
     * Minecraft's sound effects remain unrelated output-only mixer sources.
     */
    private fun runPcmTrack(target: File, serial: Long) {
        lock.withLock {
            if (serial != trackSerial) return
            buffering = true
            error = null
            level = 0.0F
            peak = 0.0F
        }

        var parser: PcmAudioParser? = null
        var output: SourceDataLine? = null
        var mcOutput: MinecraftAudioOutput? = null
        try {
            val requestedStartMs = consumeSeekRequest().coerceAtLeast(0L)
            stage(serial, "初始化隔离解码器")
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

            val format = AudioFormat(frequency.toFloat(), 16, channels, true, false)
            if (minecraftOutput) {
                try {
                    stage(serial, "初始化 Minecraft 独立声道")
                    mcOutput = MinecraftAudioOutput(format) { disposed || serial != trackSerial || seekRequestMs >= 0L }
                    minecraftFrequency = frequency
                    minecraftBaseMs = baseMs
                    activeMinecraftOutput = mcOutput
                    MusicSoundLifecycle.resumeHook = { if (!wantPlay) activeMinecraftOutput?.pause(true) }
                    if (!wantPlay) mcOutput.pause(true)
                    outputDescription = "Minecraft 独立声道"
                } catch (e: Exception) {
                    if (disposed || serial != trackSerial || seekRequestMs >= 0L) return
                    if (androidRuntime()) throw java.io.IOException("Minecraft 音频输出不可用，请检查游戏声音或重启", e)
                    System.err.println("[Music audio] Minecraft output unavailable, trying Java Sound: $e")
                    outputDescription = "Java Sound（Minecraft 输出初始化失败）"
                }
            } else {
                outputDescription = "Java Sound"
            }

            if (mcOutput == null) {
                stage(serial, "初始化 Java Sound 输出")
                val info = DataLine.Info(SourceDataLine::class.java, format)
                output = AudioSystem.getLine(info) as SourceDataLine
                output.open(format, LINE_BUFFER_FRAMES * channels * 2)
                if (disposed || serial != trackSerial) return
                if (wantPlay || androidRuntime()) output.start()
                if (disposed || serial != trackSerial) return
                line = output
                if (!wantPlay && !androidRuntime()) output.stop()
            }

            stage(serial, if (mcOutput != null) "Minecraft PCM 输出已就绪" else "Java Sound PCM 输出已就绪")
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
                        if (mcOutput != null) mcOutput.write(pcm, bytes) else output?.write(pcm, 0, bytes)
                        if (disposed || serial != trackSerial) return
                        MusicSpectrumAnalyzer.accept(processed, interleaved, channels, frequency)
                        if (firstWrite && (mcOutput?.isStarted() ?: true)) {
                            firstWrite = false
                            buffering = false
                        }
                    }

                    val frames = interleaved / channels
                    playedFrames += frames
                    if (seekRequestMs < 0L) {
                        positionMs = if (mcOutput == null) baseMs + playedFrames * 1000L / frequency else positionMs()
                    }
                    updateLevel(raw, interleaved)
                    offset += interleaved
                }
            }

            // A stopped JavaSound line cannot drain; wait for resume, or retire immediately for a seek/stop.
            while (!wantPlay && !disposed && serial == trackSerial && seekRequestMs < 0L) sleep(20L)
            if (disposed || serial != trackSerial || seekRequestMs >= 0L) return
            if (mcOutput != null) mcOutput.drain() else output?.drain()
            if (seekRequestMs >= 0L) return
        } finally {
            seeking = false
            try { parser?.close() } catch (_: Throwable) { }
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

    /** Ogg/Vorbis PCM supplied by the real Minecraft 26.3 JOrbis decoder. */
    private fun runOggTrack(target: File, serial: Long) {
        lock.withLock {
            if (serial != trackSerial) return
            buffering = true; error = null; level = 0f; peak = 0f
        }
        stage(serial, "读取 Ogg 头")
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
            val requested = consumeSeekRequest().coerceAtLeast(0L)
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
                    stage(serial, "初始化 Minecraft 独立声道")
                    mcOutput = MinecraftAudioOutput(format) { disposed || serial != trackSerial || seekRequestMs >= 0L }
                    minecraftFrequency = frequency; minecraftBaseMs = baseMs; activeMinecraftOutput = mcOutput
                    MusicSoundLifecycle.resumeHook = { if (!wantPlay) activeMinecraftOutput?.pause(true) }
                    if (!wantPlay) mcOutput.pause(true)
                    outputDescription = "Minecraft 独立声道"
                } catch (e: Exception) {
                    if (disposed || serial != trackSerial || seekRequestMs >= 0L) return
                    if (androidRuntime()) throw java.io.IOException("Minecraft 音频输出不可用，请检查游戏声音或重启", e)
                    outputDescription = "Java Sound（Minecraft 输出初始化失败）"
                }
            } else outputDescription = "Java Sound"
            if (mcOutput == null) {
                stage(serial, "初始化 Java Sound 输出")
                stage(serial, "Java Sound 获取设备")
                output = AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine
                stage(serial, "Java Sound 打开设备")
                output.open(format, LINE_BUFFER_FRAMES * channels * 2)
                if (disposed || serial != trackSerial) return
                stage(serial, "Java Sound 启动设备")
                if (wantPlay || androidRuntime()) output.start()
                if (disposed || serial != trackSerial) return
                line = output
                if (!wantPlay && !androidRuntime()) output.stop()
            }
            stage(serial, if (mcOutput != null) "Minecraft 输出已提交" else "Java Sound 输出已初始化")
            positionMs = baseMs; seeking = false
            var firstWrite = true
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
                if (bytes > 0) {
                    if (firstWrite) stage(serial, "写入首段音频")
                    if (mcOutput != null) mcOutput.write(pcm, bytes) else output?.write(pcm, 0, bytes)
                    if (disposed || serial != trackSerial) return
                    MusicSpectrumAnalyzer.accept(processed, aligned, channels, frequency)
                    if (firstWrite) { firstWrite = false; buffering = false }
                }
                playedFrames += aligned / channels
                if (seekRequestMs < 0L) {
                    positionMs = if (mcOutput == null)
                        baseMs + playedFrames * 1000L / frequency else positionMs()
                }
                updateLevel(samples, aligned)
            }
            while (!wantPlay && !disposed && serial == trackSerial && seekRequestMs < 0L) sleep(20L)
            if (seekRequestMs >= 0L || disposed || serial != trackSerial) return
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

    private fun close(output: SourceDataLine?) {
        if (output == null) return
        try {
            output.stop()
            output.flush()
            output.close()
        } catch (ignored: Throwable) {
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
        internal fun androidRuntime(): Boolean {
            // ANDROID_ROOT is exported by Android launchers even for a desktop OpenJDK runtime.
            val root = System.getenv("ANDROID_ROOT")
            val version = System.getProperty("os.version", "")
            return !root.isNullOrBlank() || version.contains("android", ignoreCase = true)
        }

        private const val LINE_BUFFER_FRAMES = 8192
    }
}
