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
 * The supplied source archive does not contain jelly_music.mp3 or the JLayer decoder. To keep
 * this port Kotlin-only and dependency-free, optional music reads a user-provided PCM WAV at
 * <Minecraft game directory>/jelly_music.wav. It does not bundle or download a song. Java Sound
 * Clip is used instead of the original MP3 engine; decoding/line availability is platform-dependent.
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
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.FloatControl
import kotlin.math.abs
import kotlin.math.log10

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
 * A single local clip. Opening is off the render/tick thread; generation invalidates late results
 * when music is switched off, the module is disabled, or the client disconnects.
 */
private object LocalMusic {
    private val lock = Any()
    private var clip: Clip? = null
    private var generation = 0L
    private var attempted = false
    private var loading = false
    private var currentVolume = 50f

    fun ensureStarted(file: File, volumePercent: Float) {
        val token = synchronized(lock) {
            currentVolume = volumePercent.coerceIn(0f, 100f)
            clip?.let {
                setVolume(it, currentVolume)
                return
            }
            if (attempted || loading) return
            attempted = true
            loading = true
            generation
        }

        Thread({
            var opened: Clip? = null
            try {
                require(file.isFile) { "Missing ${file.absolutePath}" }
                // Clip stores decoded audio in memory; bound the user-supplied input size.
                require(file.length() in 1..MAX_FILE_BYTES) { "WAV is empty or larger than 64 MiB" }
                val candidate = AudioSystem.getClip()
                opened = candidate
                AudioSystem.getAudioInputStream(file).use { candidate.open(it) }
                synchronized(lock) {
                    if (token == generation) {
                        setVolume(candidate, currentVolume)
                        candidate.loop(Clip.LOOP_CONTINUOUSLY)
                        clip = candidate
                        opened = null
                    }
                }
            } catch (error: Exception) {
                System.err.println("[Friendswine] Unable to play ${file.absolutePath}: ${error.message}")
            } finally {
                opened?.close()
                synchronized(lock) {
                    if (token == generation) loading = false
                }
            }
        }, "Friendswine-AudioLoader").apply {
            isDaemon = true
            start()
        }
    }

    fun playingPositionUs(): Long? = synchronized(lock) {
        val current = clip ?: return@synchronized null
        if (current.isRunning) current.microsecondPosition else null
    }

    fun resetIfActive() {
        synchronized(lock) {
            if (clip == null && !loading && !attempted) return
            resetLocked()
        }
    }

    fun reset() = synchronized(lock) { resetLocked() }

    private fun resetLocked() {
        generation++
        loading = false
        attempted = false
        clip?.let {
            it.stop()
            it.close()
        }
        clip = null
    }

    private fun setVolume(clip: Clip, percent: Float) {
        // Do not play unexpectedly at full volume if this mixer cannot respect the setting.
        check(clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            "This audio device does not support master gain"
        }
        val gain = clip.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
        val db = if (percent <= 0f) gain.minimum else (20f * log10(percent / 100f))
        gain.value = db.coerceIn(gain.minimum, gain.maximum)
    }

    private const val MAX_FILE_BYTES = 64L * 1024L * 1024L
}
