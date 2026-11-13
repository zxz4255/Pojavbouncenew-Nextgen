package net.ccbluex.liquidbounce.features.module.modules.misc.music

import com.mojang.blaze3d.audio.Library
import net.ccbluex.liquidbounce.injection.mixins.minecraft.client.sounds.MixinSoundEngineAccessor
import net.ccbluex.liquidbounce.injection.mixins.minecraft.client.sounds.MixinSoundManagerAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.sounds.AudioStream
import net.minecraft.client.sounds.ChannelAccess
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat

/** Experimental MC-managed streaming channel. The decoder never runs on Minecraft's sound thread. */
internal class MinecraftAudioOutput(private val format: AudioFormat, private val cancelled: () -> Boolean) {
    @Volatile private var pipe: Pipe? = null
    @Volatile private var handle: ChannelAccess.ChannelHandle? = null
    @Volatile private var paused = false
    private val access: ChannelAccess
    private var generation = MusicSoundLifecycle.generation()

    init {
        val manager = Minecraft.getInstance().soundManager as MixinSoundManagerAccessor
        access = (manager.`liquidbounce$getSoundEngine`() as MixinSoundEngineAccessor).`liquidbounce$getChannelAccess`()
        open()
    }

    private fun open() {
        var channel: ChannelAccess.ChannelHandle? = null
        // After seek, the old stopped handle is returned to the pool by the next MC sound tick.
        repeat(8) {
            if (channel != null) return@repeat
            if (cancelled()) throw MinecraftOutputCancelled()
            val pending = access.createHandle(Library.Pool.STREAMING)
            channel = try { pending.get(3, TimeUnit.SECONDS) } catch (e: Exception) {
                pending.thenAccept { late -> late?.execute { it.stop() } }
                throw IOException("Minecraft sound channel unavailable", e)
            }
            if (channel == null) Thread.sleep(75)
        }
        val acquired = channel ?: throw IOException("Minecraft streaming channel pool is full or sound engine is unavailable")
        generation = MusicSoundLifecycle.generation()
        val newPipe = Pipe(format)
        pipe = newPipe
        handle = acquired
        // attachBufferStream performs four reads synchronously: Pipe.read never blocks or decodes.
        acquired.execute { c ->
            try {
                c.disableAttenuation()
                c.setRelative(true)
                c.setLooping(false)
                c.setVolume(1f)
                c.attachBufferStream(newPipe)
                c.play()
                if (paused) c.pause()
            } catch (e: Exception) {
                newPipe.fail(e)
                c.stop()
            }
        }
    }

    fun write(data: ByteArray, size: Int) {
        checkGeneration()
        val p = pipe ?: throw MinecraftOutputInterrupted("Minecraft stream closed")
        val copy = data.copyOf(size)
        while (!p.offer(copy)) {
            if (cancelled()) return
            checkGeneration()
            p.checkOpen()
            if (!paused && System.nanoTime() - p.lastReadNanos > TimeUnit.SECONDS.toNanos(8)) {
                throw MinecraftOutputInterrupted("Minecraft audio stream stopped receiving sound ticks")
            }
        }
        checkGeneration()
        p.checkOpen()
    }

    fun playedFrames(): Long = pipe?.playedFrames() ?: 0L

    private fun checkGeneration() {
        if (MusicSoundLifecycle.generation() != generation) {
            throw MinecraftOutputInterrupted("Minecraft sound engine restarted")
        }
    }

    fun pause(value: Boolean) {
        paused = value
        pipe?.setPaused(value)
        if (!value) pipe?.touch()
        handle?.execute { if (value) it.pause() else it.unpause() }
    }

    fun flush() {
        close()
        open()
    }

    fun drain() {
        val p = pipe ?: return
        p.finish()
        while (!p.closed && !cancelled()) {
            checkGeneration()
            p.checkFailure()
            if (!paused && System.nanoTime() - p.lastReadNanos > TimeUnit.SECONDS.toNanos(8)) {
                throw MinecraftOutputInterrupted("Minecraft audio stream stopped receiving sound ticks")
            }
            Thread.sleep(20)
        }
        checkGeneration()
        p.checkFailure()
        if (p.closed && p.premature) throw MinecraftOutputInterrupted("Minecraft audio channel stopped before end of stream")
    }

    fun close() {
        val old = pipe
        pipe = null
        old?.close()
        handle?.execute { it.stop() }
        handle = null
    }

    private class Pipe(private val audioFormat: AudioFormat) : AudioStream {
        private val chunks = ArrayBlockingQueue<ByteArray>(16)
        // Metadata follows OpenAL's four queued buffers. Access is serialized by clockLock.
        private val clockLock = Any()
        private val queuedFrames = java.util.ArrayDeque<Int>()
        private var initialReads = 0
        private var completedFrames = 0L
        private var headStartedNanos = System.nanoTime()
        private var pausedAtNanos = 0L
        private var monotonicFrames = 0L
        @Volatile var closed = false
            private set
        @Volatile private var finished = false
        @Volatile var premature = false
            private set
        @Volatile private var failure: Exception? = null
        @Volatile var lastReadNanos = System.nanoTime()
            private set
        // 23 ms of silence is supplied on starvation; sound thread never waits for producer.
        private val silence = ByteArray((audioFormat.sampleRate.toInt() / 44).coerceAtLeast(1) * audioFormat.frameSize)

        override fun getFormat(): AudioFormat = audioFormat
        override fun read(size: Int): ByteBuffer {
            // AudioStream.read is non-null in 26.3. Like vanilla FloatSampleSource,
            // an empty buffer represents EOF; never block the sound thread.
            if (closed) return ByteBuffer.allocateDirect(0)
            lastReadNanos = System.nanoTime()
            val chunk = chunks.poll()
            val data = chunk ?: if (finished) null else silence
            synchronized(clockLock) {
                if (initialReads < 4) {
                    if (initialReads == 0) headStartedNanos = System.nanoTime()
                    initialReads++
                } else if (queuedFrames.isNotEmpty()) {
                    completedFrames += queuedFrames.removeFirst().toLong()
                    headStartedNanos = if (pausedAtNanos != 0L) pausedAtNanos else System.nanoTime()
                }
                if (data != null) queuedFrames.addLast(if (chunk == null) 0 else data.size / audioFormat.frameSize)
            }
            if (data == null) return ByteBuffer.allocateDirect(0)
            return ByteBuffer.allocateDirect(data.size).put(data).flip() as ByteBuffer
        }
        override fun close() {
            synchronized(clockLock) { premature = !finished || chunks.isNotEmpty() || queuedFrames.isNotEmpty() }
            closed = true
            chunks.clear()
        }
        fun finish() { finished = true }
        fun setPaused(value: Boolean) = synchronized(clockLock) {
            val now = System.nanoTime()
            if (value && pausedAtNanos == 0L) pausedAtNanos = now
            else if (!value && pausedAtNanos != 0L) {
                headStartedNanos += now - pausedAtNanos
                pausedAtNanos = 0L
            }
        }
        fun playedFrames(): Long = synchronized(clockLock) {
            val now = if (pausedAtNanos != 0L) pausedAtNanos else System.nanoTime()
            val head = queuedFrames.peekFirst() ?: 0
            val elapsed = ((now - headStartedNanos).coerceAtLeast(0L) * audioFormat.sampleRate / 1_000_000_000.0).toLong()
            val estimate = completedFrames + elapsed.coerceAtMost(head.toLong())
            monotonicFrames = maxOf(monotonicFrames, estimate)
            monotonicFrames
        }
        fun touch() { lastReadNanos = System.nanoTime() }
        fun fail(e: Exception) { failure = e; close() }
        fun checkFailure() { failure?.let { throw IOException("Minecraft audio stream failed", it) } }
        fun checkOpen() { checkFailure(); if (closed) throw MinecraftOutputInterrupted("Minecraft sound channel closed or reloaded") }
        fun offer(data: ByteArray): Boolean = chunks.offer(data, 20, TimeUnit.MILLISECONDS)
    }
}
