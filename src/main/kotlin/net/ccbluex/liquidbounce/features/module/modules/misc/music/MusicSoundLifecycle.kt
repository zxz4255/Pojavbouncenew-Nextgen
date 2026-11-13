package net.ccbluex.liquidbounce.features.module.modules.misc.music

import java.util.concurrent.atomic.AtomicLong

/** Incremented before Minecraft releases all sound channels, including on device reload. */
object MusicSoundLifecycle {
    private val generation = AtomicLong()
    @Volatile var resumeHook: (() -> Unit)? = null
    @JvmStatic fun afterResume() { resumeHook?.invoke() }
    @JvmStatic fun beforeStopAll() { generation.incrementAndGet() }
    fun generation(): Long = generation.get()
}

internal class MinecraftOutputInterrupted(message: String) : java.io.IOException(message)

internal class MinecraftOutputCancelled : java.io.IOException("Minecraft audio request cancelled")
