package net.ccbluex.liquidbounce.features.module.modules.misc.music

import net.ccbluex.liquidbounce.utils.audio.android.AndroidAudioSession
import net.ccbluex.liquidbounce.utils.audio.android.AndroidAudioTrackOutput
import java.io.IOException

/**
 * The music player's output on Android: a module-owned native PCM sink.
 *
 * <p>The sink is `libliquidbounce_audioout.so` (AAudio, or OpenSL ES as a fallback), not
 * `android.media.AudioTrack`. The latter is a *Java framework* class and a Pojav/Zalith launcher runs
 * the game on its own OpenJDK bootclasspath where no `android.*` class exists, so every attempt to
 * reach it — including reflection — failed with "Android AudioTrack API 不可用" even though the
 * native decoder had opened the file fine. The NDK audio APIs are plain shared libraries in the same
 * process and have no such problem.</p>
 *
 * <p>Why this replaces the old Minecraft streaming channel: that channel lived inside Minecraft's
 * OpenAL library, so `SoundEngine.stopAll()` — which a dimension change, a respawn, a world join and
 * a chunk-heavy teleport all trigger — released it, and playback only resumed after the engine had
 * re-attached a new one. Submitting PCM straight to `AudioTrack` removes that dependency entirely,
 * so the sink is never taken away mid-track and the audio has no gap to recover from.</p>
 *
 * <p>The class only adapts the engine's `write`/`pause`/`flush`/`drain` calls onto the sink. It does
 * not decode: decoding stays in `MusicAudioParserFactory`, which uses the bundled native library.</p>
 */
internal class AndroidAudioOutput(
    private val sampleRate: Int,
    private val channels: Int,
    private val cancelled: () -> Boolean,
) {
    private val sink: AndroidAudioTrackOutput = AndroidAudioTrackOutput.open(sampleRate, channels)
    private val generation = AndroidAudioSession.generation()
    @Volatile private var paused = false
    @Volatile private var started = false

    init {
        AndroidAudioSession.setActive(sink)
    }

    fun isStarted(): Boolean = started

    fun isPaused(): Boolean = paused

    /** Writes interleaved PCM16; returns false when the sink had to be abandoned. */
    fun write(data: ByteArray, size: Int) {
        if (cancelled()) return
        checkGeneration()
        if (!sink.isAlive) throw AndroidOutputInterrupted("原生音频设备已关闭")
        if (!started) {
            sink.start()
            started = true
        }
        if (paused) return
        if (!sink.write(data, 0, size)) {
            throw AndroidOutputInterrupted("原生音频设备写入失败")
        }
        if (sink.deviceStalled()) {
            throw AndroidOutputInterrupted("原生音频设备停止接受数据")
        }
    }

    fun playedFrames(): Long = if (sink.isAlive) sink.playedFrames() else 0L

    fun pause(value: Boolean) {
        if (!sink.isAlive) return
        paused = value
        sink.pause(value)
    }

    /** Drops queued device audio; the engine re-creates the sink after a seek. */
    fun flush() {
        if (!sink.isAlive) return
        sink.flushDevice()
    }

    /**
     * Waits until the device has played everything that was submitted.
     *
     * <p>A paused sink cannot drain, so this returns as soon as playback is paused; the engine's own
     * pause loop then decides whether to resume or retire the track.</p>
     */
    fun drain() {
        if (!sink.isAlive) return
        var quietTicks = 0
        var lastHead = -1L
        while (sink.isAlive && !cancelled()) {
            checkGeneration()
            if (paused) return
            val head = sink.playedFrames()
            if (head == lastHead) {
                quietTicks++
            } else {
                quietTicks = 0
                lastHead = head
            }
            // The head stops advancing once the queue is empty; a second of no movement is enough.
            if (quietTicks > 50) return
            try {
                Thread.sleep(20L)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /** Which native backend is producing the sound ({@code AAudio} / {@code OpenSL ES}). */
    fun backend(): String = sink.backend()

    fun close() {
        AndroidAudioSession.clearActive(sink)
        sink.close()
    }

    private fun checkGeneration() {
        if (AndroidAudioSession.generation() != generation) {
            throw AndroidOutputInterrupted("原生音频设备已重置")
        }
    }
}

internal class AndroidOutputInterrupted(message: String) : IOException(message)
