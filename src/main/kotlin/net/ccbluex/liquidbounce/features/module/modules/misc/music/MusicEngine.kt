package net.ccbluex.liquidbounce.features.module.modules.misc.music

import net.ccbluex.liquidbounce.utils.client.clientLogger
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.math.max

/**
 * One audio owner, one decoder/output thread, bounded JavaSound buffer.
 *
 * Rules kept from the original design because they are the reason playback is stable:
 *  - the render thread never decodes, resolves or downloads;
 *  - every job carries a generation token, so a stale download can never revive a stopped song;
 *  - the lyric clock is the *sound card* consumed-frame position, not elapsed wall time;
 *  - closing a session/line from another thread is what unblocks `read()` and `write()`.
 */
class MusicEngine(val library: MusicLibrary = MusicLibrary()) {
    private val logger = clientLogger("MonetMusic")
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "MonetMusic-Audio").apply { isDaemon = true } }
    private val generation = AtomicLong()
    private val closed = AtomicBoolean()
    private val lock = Any()

    @Volatile private var snapshot = PlaybackSnapshot()
    @Volatile private var activeLine: SourceDataLine? = null
    @Volatile private var activeInput: InputStream? = null
    @Volatile private var activeSession: AudioDecoder.Session? = null
    @Volatile private var baseSamples = 0L
    @Volatile private var rate = 48_000
    @Volatile private var pausedAtMs = 0L
    @Volatile private var pauseRequested = false
    @Volatile private var gain = 0.35f

    @Volatile var onTrackChanged: ((MusicTrack) -> Unit)? = null

    private val queue = mutableListOf<MusicTrack>()
    private var queueIndex = -1

    var repeat = false
    var autoNext = true

    val state: PlaybackSnapshot get() = snapshot

    val volume: Float get() = gain

    fun setVolume(value: Float) {
        gain = value.coerceIn(0f, 1f)
    }

    /** Consumed-frame clock, clamped to the known duration. */
    val positionMs: Long
        get() {
            val current = snapshot
            if (current.status == PlaybackStatus.PAUSED || current.status == PlaybackStatus.ENDED) return pausedAtMs
            val line = activeLine ?: return pausedAtMs
            val consumed = line.longFramePosition.coerceAtLeast(0)
            val position = (baseSamples + consumed) * 1000 / max(1, rate)
            return if (current.durationMs > 0) position.coerceAtMost(current.durationMs) else position
        }

    /** Snapshot for the queue list; cheap copy of keys only. */
    fun queueKeys(): List<String> = synchronized(lock) { queue.map { it.key } }

    val queueSize: Int get() = synchronized(lock) { queue.size }

    fun play(requested: MusicTrack) {
        if (closed.get()) return
        synchronized(lock) {
            val found = queue.indexOfFirst { it.key == requested.key }
            if (found >= 0) {
                queue[found] = requested
                queueIndex = found
            } else {
                queue.add(requested)
                queueIndex = queue.lastIndex
            }
        }
        pauseRequested = false
        start(requested, 0)
    }

    private fun start(track: MusicTrack, startMs: Long) {
        if (closed.get()) return
        val token = generation.incrementAndGet()
        baseSamples = 0L
        pausedAtMs = startMs.coerceAtLeast(0)
        snapshot = PlaybackSnapshot(track, PlaybackStatus.LOADING, durationMs = track.durationMs)
        // Closing these unblocks both read() and SourceDataLine.write() from a different thread.
        synchronized(lock) {
            runCatching { activeInput?.close() }
            activeInput = null
            runCatching { activeSession?.close() }
            runCatching { activeLine?.close() }
            activeSession = null
            activeLine = null
        }
        worker.execute {
            var session: AudioDecoder.Session? = null
            var line: SourceDataLine? = null
            var lastError: String? = null
            try {
                val cancelled = { token != generation.get() || closed.get() }
                val resolved = MusicProviders.resolveTrack(track)
                if (cancelled()) return@execute
                session = if (resolved.source == MusicSource.LOCAL) {
                    AudioDecoder.open(resolved.localPath ?: throw IOException("本地路径为空"), startMs, cancelled)
                } else if (ModuleMusic.cacheAudio) {
                    val path = MusicHttp.download(resolved.remoteUrl,
                        library.cache.resolve(MusicHttp.hash(resolved.key) + ".audio"), cancelled, opened = { input ->
                            synchronized(lock) {
                                if (cancelled()) input?.close() else activeInput = input
                            }
                        }) { bytes ->
                        if (!cancelled()) snapshot = snapshot.copy(error = "预缓存 ${bytes / 1024} KiB")
                    }
                    AudioDecoder.open(path, startMs, cancelled)
                } else {
                    val stream = MusicHttp.stream(resolved.remoteUrl, cancelled)
                    synchronized(lock) {
                        if (cancelled()) { stream.input.close(); return@execute }
                        activeInput = stream.input
                    }
                    AudioDecoder.open(stream.input, stream.length, startMs, cancelled)
                }
                synchronized(lock) {
                    if (cancelled()) return@execute
                    activeSession = session
                }
                val format = session.format
                rate = format.sampleRate
                baseSamples = session.positionSamples
                pausedAtMs = baseSamples * 1000 / format.sampleRate
                val outputChannels = 2
                val pcm = AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED, format.sampleRate.toFloat(), 16,
                    outputChannels, outputChannels * 2, format.sampleRate.toFloat(), false,
                )
                val info = DataLine.Info(SourceDataLine::class.java, pcm)
                if (!AudioSystem.isLineSupported(info)) {
                    throw IOException("声卡不支持 ${format.sampleRate} Hz 立体声 PCM16")
                }
                line = AudioSystem.getLine(info) as SourceDataLine
                val bufferBytes = ((format.sampleRate * 0.12).toInt() * outputChannels * 2) and 3.inv()
                line.open(pcm, bufferBytes)
                if (!pauseRequested) line.start()
                synchronized(lock) {
                    if (cancelled()) return@execute
                    activeLine = line
                    snapshot = PlaybackSnapshot(resolved,
                        if (pauseRequested) PlaybackStatus.PAUSED else PlaybackStatus.PLAYING,
                        format = format.label,
                        durationMs = if (format.durationMs > 0) format.durationMs else resolved.durationMs)
                }
                if (cancelled()) return@execute
                if (startMs == 0L) MusicUserLibrary.listened(resolved)
                onTrackChanged?.invoke(resolved)
                val processor = PcmProcessor(format.channels, outputChannels)
                while (token == generation.get() && !closed.get()) {
                    while (pauseRequested && !cancelled()) Thread.sleep(20)
                    if (cancelled()) break
                    val frame = session.nextFrame() ?: break
                    val bytes = processor.process(frame.data, frame.validBytes, gain)
                    var offset = 0
                    while (offset < bytes.size && token == generation.get() && !closed.get()) {
                        val written = line.write(bytes, offset, bytes.size - offset)
                        if (written <= 0) throw IOException("声卡写入失败")
                        offset += written
                    }
                }
                if (token == generation.get() && !closed.get()) {
                    // drain() on a stopped SourceDataLine can wait forever; wait for resume first.
                    while (pauseRequested && token == generation.get() && !closed.get()) Thread.sleep(20)
                    if (token != generation.get() || closed.get()) return@execute
                    line.drain()
                    pausedAtMs = if (snapshot.durationMs > 0) {
                        snapshot.durationMs
                    } else {
                        session.positionSamples * 1000 / format.sampleRate
                    }
                    snapshot = snapshot.copy(status = PlaybackStatus.ENDED)
                }
            } catch (e: Exception) {
                if (token == generation.get() && !closed.get()) {
                    lastError = e.message?.take(240) ?: e.javaClass.simpleName
                    snapshot = snapshot.copy(status = PlaybackStatus.ERROR, error = lastError ?: "播放失败")
                    logger.warn("Music playback failed: $lastError")
                }
            } finally {
                runCatching { session?.close() }
                runCatching { line?.close() }
                synchronized(lock) {
                    if (token == generation.get()) {
                        activeInput = null
                        if (activeLine === line) activeLine = null
                        if (activeSession === session) activeSession = null
                    }
                }
                if (lastError == null && token == generation.get() &&
                    snapshot.status == PlaybackStatus.ENDED && autoNext
                ) {
                    worker.execute { if (token == generation.get()) advanceAutomatically() }
                }
            }
        }
    }

    fun togglePause() {
        val token = generation.get()
        when (snapshot.status) {
            PlaybackStatus.PLAYING -> {
                pausedAtMs = positionMs
                pauseRequested = true
                snapshot = snapshot.copy(status = PlaybackStatus.PAUSED)
                activeLine?.stop()
            }
            PlaybackStatus.LOADING -> pauseRequested = true
            PlaybackStatus.PAUSED -> if (token == generation.get()) {
                pauseRequested = false
                activeLine?.start()
                snapshot = snapshot.copy(status = PlaybackStatus.PLAYING)
            }
            PlaybackStatus.ENDED, PlaybackStatus.ERROR -> snapshot.track?.let { play(it) }
            else -> Unit
        }
    }

    fun seek(ms: Long) {
        val current = snapshot
        val track = current.track ?: return
        if (current.status !in setOf(
                PlaybackStatus.PLAYING, PlaybackStatus.PAUSED,
                PlaybackStatus.ENDED, PlaybackStatus.LOADING,
            )
        ) {
            return
        }
        val duration = current.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        pauseRequested = current.status == PlaybackStatus.PAUSED
        start(track, ms.coerceIn(0, duration - 1))
    }

    private fun advanceAutomatically() {
        val next = synchronized(lock) {
            when {
                repeat && queueIndex in queue.indices -> queue[queueIndex]
                queueIndex + 1 in queue.indices -> queue[++queueIndex]
                else -> null
            }
        }
        if (next != null) start(next, 0)
    }

    fun next() {
        val next = synchronized(lock) {
            when {
                queue.isEmpty() -> null
                queueIndex + 1 in queue.indices -> queue[queueIndex + 1]
                repeat -> queue.first()
                else -> null
            }
        }
        if (next != null) play(next) else stop()
    }

    fun previous() {
        if (positionMs > 3000) {
            seek(0)
            return
        }
        val previous = synchronized(lock) { queue.getOrNull(queueIndex - 1) ?: queue.firstOrNull() }
        if (previous != null) play(previous) else stop()
    }

    fun stop() {
        generation.incrementAndGet()
        synchronized(lock) {
            runCatching { activeInput?.close() }
            activeInput = null
            runCatching { activeSession?.close() }
            runCatching { activeLine?.close() }
            activeSession = null
            activeLine = null
        }
        pauseRequested = false
        pausedAtMs = 0
        snapshot = PlaybackSnapshot()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        stop()
        worker.shutdownNow()
        runCatching { library.cleanupCache() }
    }
}
