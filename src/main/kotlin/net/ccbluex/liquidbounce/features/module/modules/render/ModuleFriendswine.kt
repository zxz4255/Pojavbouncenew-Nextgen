/*
 * Friendswine / Jelly port for the LiquidBounce nextgen + Minecraft 26.3 sources in Drive Work.
 *
 * Installation (two Kotlin source files; no Java source required):
 * 1. Put this file in src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/.
 * 2. Put MixinFriendswine.kt in
 *    src/main/kotlin/net/ccbluex/liquidbounce/injection/mixins/minecraft/render/entity/.
 * 3. In ModuleManager.kt import this object and add ModuleFriendswine in the Render module list.
 * 4. In src/main/resources/liquidbounce.mixins.json, add the following string to client:
 *      "minecraft.render.entity.MixinFriendswine"
 *    A new source file alone is NOT enough to register a Mixin.
 *
 * The supplied source archive does not contain jelly_music.mp3 and this fork no longer uses any Java
 * Sound class at all. Optional music therefore reads a user-provided PCM WAV at
 * <Minecraft game directory>/jelly_music.wav and plays it through the same Android `AudioTrack`
 * sink the music player uses (see net.ccbluex.liquidbounce.utils.audio.android). It does not bundle
 * or download a song, and it never opens the launcher's second audio device.
 *
 * Setting defaults below are port choices: the original client loads its defaults externally,
 * and those defaults were not present in the supplied source archive.
 */

package net.ccbluex.liquidbounce.features.module.modules.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.DisconnectEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.render.entity
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.min

object ModuleFriendswine : ClientModule(
    "Friendswine", ModuleCategories.RENDER, disableOnQuit = true, aliases = listOf("Jelly")
) {
    private val compression by float("Compression", 50f, 0f..90f, "%")
    private val width by float("Width", 50f, 0f..200f, "%")
    private val rotateSpeed by float("RotateSpeed", 1f, 0f..5f)
    private val range by float("Range", 16f, 0f..128f, "blocks")
    private val volume by float("Volume", 50f, 0f..100f, "%")
    private val players by boolean("Players", true)
    private val mobs by boolean("Mobs", true)
    private val self by boolean("Self", false)
    private val music by boolean("Music", false)

    private var clockOrigin = System.nanoTime()
    private var musicAnchor = 0L

    override val tag: String
        get() = "${compression.toInt()}%"

    override fun onEnabled() {
        clockOrigin = System.nanoTime()
        musicAnchor = 0L
        LocalMusic.reset()
    }

    override fun onDisabled() {
        LocalMusic.reset()
        musicAnchor = 0L
    }

    // Event handlers normally stop running when not in-game. A separate always-running listener
    // closes a clip on disconnect even if the module's onDisabled callback can no longer run.
    private object Lifecycle : EventListener {
        @Suppress("unused")
        private val disconnectHandler = handler<DisconnectEvent> {
            LocalMusic.reset()
        }
    }

    init {
        Lifecycle
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (music) {
            LocalMusic.ensureStarted(mc.gameDirectory.resolve("jelly_music.wav"), volume)
        } else {
            LocalMusic.resetIfActive()
            musicAnchor = 0L
        }
    }

    /** Used by the Mixin immediately before the model and its feature layers are submitted. */
    fun transformIfApplicable(state: LivingEntityRenderState, pose: PoseStack) {
        if (!running) return
        val target = state.entity as? LivingEntity ?: return
        val player = mc.player ?: return
        if (!target.isAlive) return
        if (target === player) {
            if (!self) return
        } else {
            if (target is Player && !players) return
            if (target !is Player && !mobs) return
            if (target.distanceToSqr(player) > range.toDouble() * range.toDouble()) return
        }

        val seconds = animationSeconds()
        val squash = JellyCurve.squash(seconds)
        val height = 1f - compression.coerceIn(0f, 90f) / 100f * squash
        val horizontal = 1f + width.coerceIn(0f, 200f) / 100f * squash
        val speed = rotateSpeed.coerceAtLeast(0f)

        // LivingEntityRenderer has already mirrored Y and translated the model by -1.501.
        // The original 1.8 module negated its negative angle in this mirrored space.
        if (speed > 0f) {
            pose.rotateDegrees(Axis.YP, -JellyCurve.angle(seconds * speed))
        }
        // Translate before scale so y = MODEL_FOOT_LINE remains fixed in model space.
        pose.translate(0f, MODEL_FOOT_LINE * (1f - height), 0f)
        pose.scale(horizontal, height, 1f)
    }

    private fun animationSeconds(): Double {
        val now = System.nanoTime()
        val positionUs = if (music) LocalMusic.playingPositionUs() else null
        if (positionUs == null || positionUs <= 0L) {
            musicAnchor = 0L
            return (now - clockOrigin).coerceAtLeast(0L) / 1.0e9
        }
        val positionSeconds = positionUs / 1.0e6
        if (musicAnchor == 0L || abs(positionSeconds - (now - musicAnchor) / 1.0e9) > 1.0) {
            // Also handles the audio clip wrapping to the beginning on loop.
            musicAnchor = now - positionUs * 1_000L
        }
        return (now - musicAnchor).coerceAtLeast(0L) / 1.0e9
    }

    private const val MODEL_FOOT_LINE = 1.501f
}

/** The five keyframes and smoothstep interpolation from the supplied JellyAnimation.java. */
private object JellyCurve {
    private const val LENGTH = 0.91667
    private val times = doubleArrayOf(0.0, 0.25, 0.45833, 0.70833, LENGTH)
    private val values = doubleArrayOf(0.0, 1.0, 0.0, 1.0, 0.0)

    private fun phase(seconds: Double): Double = seconds.coerceAtLeast(0.0) % LENGTH

    fun angle(seconds: Double): Float = (-360.0 * phase(seconds) / LENGTH).toFloat()

    fun squash(seconds: Double): Float {
        val time = phase(seconds)
        var i = 0
        while (i < times.size - 2 && time > times[i + 1]) i++
        val t = (time - times[i]) / (times[i + 1] - times[i])
        return (values[i] + (values[i + 1] - values[i]) * t * t * (3.0 - 2.0 * t)).toFloat()
    }
}

/**
 * A single local WAV clip, streamed into the module's own Android `AudioTrack` device.
 *
 * <p>This used to be a Java Sound `Clip`. Java Sound is gone from this fork: it opened a second
 * launcher audio device, and on Pojav that is exactly the device that fights with the game's mixer.
 * The clip is now decoded by a small RIFF reader and pushed to `AudioTrack` from a daemon thread, so
 * nothing in the render or game thread blocks and no desktop sound API is touched.</p>
 */
private object LocalMusic {
    private val lock = Any()
    private var session: Session? = null
    private var generation = 0L
    private var attempted = false
    private var loading = false
    private var currentVolume = 50f

    private class Session(val thread: Thread) {
        @Volatile
        var positionUs: Long = 0L

        @Volatile
        var failed: Boolean = false

        @Volatile
        var volume: Float = 50f

        @Volatile
        var sink: net.ccbluex.liquidbounce.utils.audio.android.AndroidAudioTrackOutput? = null
    }

    fun ensureStarted(file: File, volumePercent: Float) {
        val token = synchronized(lock) {
            currentVolume = volumePercent.coerceIn(0f, 100f)
            val existing = session
            if (existing != null) {
                existing.volume = currentVolume
                return
            }
            if (attempted || loading) return
            attempted = true
            loading = true
            generation
        }

        Thread({
            var stream: WavStream? = null
            var sink: net.ccbluex.liquidbounce.utils.audio.android.AndroidAudioTrackOutput? = null
            try {
                require(file.isFile) { "Missing ${file.absolutePath}" }
                // A streamed WAV, not an in-memory clip: bound the user-supplied input size.
                require(file.length() in 1..MAX_FILE_BYTES) { "WAV is empty or larger than 64 MiB" }
                stream = WavStream.open(file)
                sink = net.ccbluex.liquidbounce.utils.audio.android.AndroidAudioTrackOutput.open(
                    stream.sampleRate, stream.channels
                )
                val current = Session(Thread.currentThread())
                current.volume = currentVolume
                current.sink = sink
                synchronized(lock) {
                    if (token != generation) {
                        sink.close()
                        return@synchronized
                    }
                    session = current
                }
                playLoop(stream, sink, current)
            } catch (error: Exception) {
                System.err.println("[Friendswine] Unable to play ${file.absolutePath}: ${error.message}")
                synchronized(lock) {
                    session?.failed = true
                }
            } finally {
                try { stream?.close() } catch (_: Throwable) { }
                try { sink?.close() } catch (_: Throwable) { }
                synchronized(lock) {
                    if (token == generation) loading = false
                }
            }
        }, "Friendswine-AudioLoader").apply {
            isDaemon = true
            start()
        }
    }

    /** Loop until the module stops, the session is replaced or the file ends. */
    private fun playLoop(
        stream: WavStream,
        sink: net.ccbluex.liquidbounce.utils.audio.android.AndroidAudioTrackOutput,
        session: Session,
    ) {
        sink.setGain(session.volume / 100f)
        sink.start()
        while (true) {
            if (synchronized(lock) { this.session } !== session) break
            if (Thread.currentThread().isInterrupted) break
            sink.setGain(session.volume / 100f)
            val data = stream.read(4096) ?: break
            if (!sink.write(data, 0, data.size)) break
            session.positionUs = stream.positionUs()
        }
    }

    fun playingPositionUs(): Long? = synchronized(lock) {
        val current = session ?: return@synchronized null
        if (current.failed) null else current.positionUs
    }

    fun resetIfActive() {
        synchronized(lock) {
            if (session == null && !loading && !attempted) return
            resetLocked()
        }
    }

    fun reset() = synchronized(lock) { resetLocked() }

    private fun resetLocked() {
        generation++
        loading = false
        attempted = false
        val current = session
        session = null
        current?.thread?.interrupt()
        try { current?.sink?.close() } catch (_: Throwable) { }
    }

    private const val MAX_FILE_BYTES = 64L * 1024L * 1024L
}

/** Minimal RIFF/WAVE reader: PCM 16-bit mono or stereo only, which is what a user WAV will be. */
private class WavStream private constructor(
    private val input: RandomAccessFile,
    private var dataStart: Long,
    private val dataLength: Long,
    val sampleRate: Int,
    val channels: Int,
) {
    private var dataRead = 0L
    private var framesRead = 0L

    companion object {
        fun open(file: File): WavStream {
            val input = RandomAccessFile(file, "r")
            try {
                val header = ByteArray(12)
                input.readFully(header)
                require(String(header, 0, 4) == "RIFF" && String(header, 8, 4) == "WAVE") {
                    "Not a RIFF/WAVE file"
                }
                var sampleRate = 0
                var channels = 0
                var bits = 0
                var dataStart = -1L
                var dataLength = 0L
                while (input.filePointer + 8 <= input.length()) {
                    val chunkHeader = ByteArray(8)
                    input.readFully(chunkHeader)
                    val id = String(chunkHeader, 0, 4)
                    val size = (chunkHeader[4].toLong() and 0xFF) or
                        ((chunkHeader[5].toLong() and 0xFF) shl 8) or
                        ((chunkHeader[6].toLong() and 0xFF) shl 16) or
                        ((chunkHeader[7].toLong() and 0xFF) shl 24)
                    when (id) {
                        "fmt " -> {
                            val fmt = ByteArray(min(size, 16L).toInt())
                            input.readFully(fmt)
                            channels = ((fmt[2].toInt() and 0xFF) or ((fmt[3].toInt() and 0xFF) shl 8))
                            sampleRate = ((fmt[4].toInt() and 0xFF) or ((fmt[5].toInt() and 0xFF) shl 8) or
                                ((fmt[6].toInt() and 0xFF) shl 16) or ((fmt[7].toInt() and 0xFF) shl 24))
                            bits = ((fmt[14].toInt() and 0xFF) or ((fmt[15].toInt() and 0xFF) shl 8))
                            val skip = size - min(size, 16L)
                            if (skip > 0) input.seek(input.filePointer + skip)
                        }
                        "data" -> {
                            dataStart = input.filePointer
                            dataLength = min(size, input.length() - dataStart)
                            break
                        }
                        else -> input.seek(input.filePointer + size + (size and 1L))
                    }
                }
                require(sampleRate in 8_000..192_000) { "Unsupported WAV sample rate $sampleRate" }
                require(channels in 1..2) { "Unsupported WAV channel count $channels" }
                require(bits == 16) { "Only 16-bit PCM WAV is supported (found $bits bit)" }
                require(dataStart >= 0L && dataLength > 0L) { "WAV has no data chunk" }
                return WavStream(input, dataStart, dataLength, sampleRate, channels)
            } catch (failure: Throwable) {
                input.close()
                throw failure
            }
        }
    }

    /** Returns one frame-aligned block, or null at the end of the data chunk. */
    fun read(wanted: Int): ByteArray? {
        val frameSize = channels * 2
        val remaining = dataLength - dataRead
        if (remaining <= 0L) return null
        val size = min(remaining, wanted.toLong()).toInt() / frameSize * frameSize
        if (size <= 0) return null
        val buffer = ByteArray(size)
        input.seek(dataStart + dataRead)
        input.readFully(buffer)
        dataRead += size
        framesRead += size / frameSize
        return buffer
    }

    fun positionUs(): Long = framesRead * 1_000_000L / sampleRate.coerceAtLeast(1)

    fun close() {
        try {
            input.close()
        } catch (_: Throwable) {
        }
    }
}
