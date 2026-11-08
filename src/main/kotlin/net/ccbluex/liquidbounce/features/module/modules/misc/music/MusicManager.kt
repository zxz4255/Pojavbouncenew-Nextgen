package net.ccbluex.liquidbounce.features.module.modules.misc.music

import net.ccbluex.liquidbounce.config.ConfigSystem
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.io.FileWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * State and orchestration for the music player: the queue, the current track, the lyrics, the NetEase
 * session and the QR login handshake.
 *
 * <p>Ported 1:1 from NeoRaven's `music.MusicManager`. The render thread only ever reads volatile
 * fields; everything that touches the network runs on the I/O pool and publishes its result, which
 * keeps `drawScreen` free of blocking calls.</p>
 */
object MusicManager {

    enum class Repeat { OFF, ALL, ONE }

    enum class QrState { IDLE, LOADING, WAITING, SCANNED, SUCCESS, EXPIRED, ERROR }

    val engine = AudioEngine()

    private val io = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "liquidbounce-music-io").apply { isDaemon = true }
    }

    private val poller: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "liquidbounce-music-qr").apply { isDaemon = true }
    }

    private val random = Random()
    private val queue: MutableList<Song> = Collections.synchronizedList(ArrayList())

    @Volatile
    var current: Song? = null
        private set

    @Volatile
    private var cursor = -1

    @Volatile
    private var loading = false

    /** Wall clock of the moment the current track was paused; 0 while it is playing. */
    @Volatile
    private var pausedSince = 0L

    @Volatile
    private var downloadProgress = 0.0F

    @Volatile
    private var playbackError: String? = null

    @Volatile
    private var repeat = Repeat.ALL

    @Volatile
    private var shuffle = false

    @Volatile
    private var playSerial = 0L

    @Volatile
    private var streamLevel = "exhigh"

    @Volatile
    private var lyrics: List<LyricLine> = emptyList()

    @Volatile
    private var lyricsLoading = false

    @Volatile
    private var lyricsSongId = -1L

    @Volatile
    var home: List<Playlist> = emptyList()
        private set

    @Volatile
    private var homeLoading = false

    @Volatile
    var myPlaylists: List<Playlist> = emptyList()
        private set

    @Volatile
    private var myPlaylistsLoading = false

    @Volatile
    var playlistTracks: List<Song> = emptyList()
        private set

    @Volatile
    var playlistTitle: String = ""
        private set

    @Volatile
    private var playlistId = -1L

    @Volatile
    private var playlistLoading = false

    @Volatile
    var searchResults: List<Song> = emptyList()
        private set

    @Volatile
    var searchQuery: String = ""
        private set

    @Volatile
    private var searching = false

    @Volatile
    private var searchError: String? = null

    @Volatile
    private var searchSerial = 0L

    @Volatile
    var profile: Profile? = null
        private set

    @Volatile
    private var profileLoading = false

    @Volatile
    var qrState: QrState = QrState.IDLE
        private set

    @Volatile
    var qrMatrix: Array<BooleanArray>? = null
        private set

    @Volatile
    private var qrCreatedAt = 0L

    @Volatile
    private var qrError: String? = null

    @Volatile
    private var sessionError: String? = null

    @Volatile
    private var lyricScale = 1.0F

    @Volatile
    private var lyricAnimation = true

    @Volatile
    private var coverTint = true

    private var settingsLoaded = false

    init {
        engine.setListener { handleTrackEnded() }
    }

    // ------------------------------------------------------------- playback

    fun isLoadingTrack(): Boolean = loading

    fun downloadProgress(): Float = downloadProgress

    fun playbackError(): String? = playbackError

    fun positionMs(): Long = engine.positionMs()

    fun durationMs(): Long {
        val duration = engine.durationMs()
        if (duration <= 0L && current != null) return current!!.durationMs
        return duration
    }

    fun isPlaying(): Boolean = engine.isPlaying()

    /**
     * Timestamps the moment playback stops on a loaded track, so the HUD can retire the now-playing
     * row instead of advertising a paused song forever. Call once per frame; cheap and idempotent.
     */
    fun observePlayback() {
        if (isPlaying() || current == null) {
            pausedSince = 0L
        } else if (pausedSince == 0L) {
            pausedSince = System.currentTimeMillis()
        }
    }

    /** True once a loaded track has been paused for at least `millis` milliseconds. */
    fun pausedFor(millis: Long): Boolean =
        pausedSince != 0L && System.currentTimeMillis() - pausedSince >= millis

    fun repeat(): Repeat = repeat

    fun cycleRepeat() {
        repeat = if (repeat == Repeat.OFF) Repeat.ALL else if (repeat == Repeat.ALL) Repeat.ONE else Repeat.OFF
        saveSettings()
    }

    fun shuffle(): Boolean = shuffle

    fun toggleShuffle() {
        shuffle = !shuffle
        saveSettings()
    }

    fun streamLevel(): String = streamLevel

    fun setStreamLevel(value: String) {
        streamLevel = value
        saveSettings()
    }

    /** Replaces the queue and starts playing `song`. */
    fun play(song: Song?, context: List<Song>?) {
        if (song == null) return
        queue.clear()
        if (!context.isNullOrEmpty()) queue.addAll(context)
        if (queue.isEmpty()) queue.add(song)
        var position = -1
        for (i in queue.indices) {
            if (queue[i].id == song.id) {
                position = i
                break
            }
        }
        if (position < 0) {
            queue.add(0, song)
            position = 0
        }
        cursor = position
        current = song
        startCurrent()
    }

    fun togglePlay() {
        if (current == null) return
        when {
            engine.isPlaying() -> engine.pause()
            engine.hasTrack() -> engine.resume()
            else -> startCurrent()
        }
    }

    fun next() = advance(1)

    fun previous() = advance(-1)

    private fun advance(step: Int) {
        if (queue.isEmpty()) return
        if (shuffle && queue.size > 1) {
            val next = random.nextInt(queue.size - 1)
            cursor = if (next >= cursor) next + 1 else next
        } else {
            cursor = (cursor + step + queue.size) % queue.size
        }
        current = queue[cursor]
        startCurrent()
    }

    private fun handleTrackEnded() {
        if (repeat == Repeat.ONE) {
            engine.seek(0L)
            engine.resume()
            return
        }
        if (cursor + 1 < queue.size) {
            advance(1)
        } else if (repeat == Repeat.ALL) {
            cursor = -1
            advance(1)
        }
    }

    fun seek(ms: Long) = engine.seek(max(0L, ms))

    fun setVolume(volume: Float) {
        engine.setVolume(volume)
        saveSettings()
    }

    fun volume(): Float = engine.volume()

    fun lyricScale(): Float = lyricScale

    fun setLyricScale(value: Float) {
        lyricScale = max(0.8F, min(1.4F, value))
        saveSettings()
    }

    fun lyricAnimation(): Boolean = lyricAnimation

    fun setLyricAnimation(value: Boolean) {
        lyricAnimation = value
        saveSettings()
    }

    fun coverTint(): Boolean = coverTint

    fun setCoverTint(value: Boolean) {
        coverTint = value
        saveSettings()
    }

    private fun startCurrent() {
        val song = current ?: return
        val serial = ++playSerial
        loading = true
        downloadProgress = 0.0F
        playbackError = null
        engine.setDurationMs(song.durationMs)
        lyrics = emptyList()
        lyricsSongId = -1L
        loadLyrics(song)
        io.execute {
            try {
                var file = cachedFile(song)
                if (!isUsable(file)) {
                    val stream = NeteaseClient.streamUrl(song.id, streamLevel)
                    if (!stream.valid()) {
                        throw IllegalStateException("无法获取播放地址（可能无版权或需要会员）")
                    }
                    download(stream.url, file, serial)
                    if (stream.durationMs > 0L) engine.setDurationMs(stream.durationMs)
                }
                if (serial != playSerial) return@execute
                engine.play(file, 0L)
            } catch (t: Throwable) {
                if (serial == playSerial) playbackError = message(t)
            } finally {
                if (serial == playSerial) {
                    loading = false
                    downloadProgress = 0.0F
                }
            }
        }
    }

    private fun loadLyrics(song: Song) {
        lyricsLoading = true
        io.execute {
            var parsed: List<LyricLine> = emptyList()
            try {
                val payload = NeteaseClient.lyrics(song.id)
                parsed = LyricsParser.parse(payload.yrc, payload.lrc, payload.translation, payload.romanisation)
            } catch (ignored: Throwable) {
            }
            val track = current
            if (track != null && track.id == song.id) {
                lyrics = parsed
                lyricsSongId = song.id
                lyricsLoading = false
            }
        }
    }

    fun lyrics(): List<LyricLine> = lyrics

    fun lyricsLoading(): Boolean = lyricsLoading

    fun lyricsSongId(): Long = lyricsSongId

    // -------------------------------------------------------------- library

    fun homeLoading(): Boolean = homeLoading

    fun myPlaylistsLoading(): Boolean = myPlaylistsLoading

    fun playlistLoading(): Boolean = playlistLoading

    fun searching(): Boolean = searching

    fun searchError(): String? = searchError

    fun loadHome() {
        if (homeLoading) return
        homeLoading = true
        io.execute {
            try {
                home = NeteaseClient.personalized(12)
            } catch (ignored: Throwable) {
            } finally {
                homeLoading = false
            }
        }
    }

    fun loadMyPlaylists() {
        val account = profile ?: return
        if (myPlaylistsLoading) return
        myPlaylistsLoading = true
        io.execute {
            try {
                myPlaylists = NeteaseClient.userPlaylists(account.userId, 50)
            } catch (ignored: Throwable) {
            } finally {
                myPlaylistsLoading = false
            }
        }
    }

    fun openPlaylist(id: Long, title: String?) {
        playlistId = id
        playlistTitle = title ?: ""
        playlistTracks = emptyList()
        playlistLoading = true
        io.execute {
            try {
                val tracks = NeteaseClient.playlistTracks(id, 300)
                if (playlistId == id) playlistTracks = tracks
            } catch (ignored: Throwable) {
            } finally {
                if (playlistId == id) playlistLoading = false
            }
        }
    }

    fun search(query: String?) {
        val trimmed = query?.trim() ?: ""
        val serial = ++searchSerial
        searchQuery = trimmed
        if (trimmed.isEmpty()) {
            searchResults = emptyList()
            searching = false
            return
        }
        searching = true
        searchError = null
        io.execute {
            try {
                val result = NeteaseClient.search(trimmed, 30)
                if (serial == searchSerial) searchResults = result
            } catch (t: Throwable) {
                if (serial == searchSerial) searchError = message(t)
            } finally {
                if (serial == searchSerial) searching = false
            }
        }
    }

    // -------------------------------------------------------------- account

    fun profileLoading(): Boolean = profileLoading

    fun qrAgeMs(): Long = if (qrCreatedAt == 0L) 0L else System.currentTimeMillis() - qrCreatedAt

    fun qrError(): String? = qrError

    fun sessionError(): String? = sessionError

    /** Restores the saved cookie jar and refreshes the profile. */
    fun boot() {
        if (settingsLoaded) {
            refreshProfile()
            return
        }
        settingsLoaded = true
        loadSettings()
        refreshProfile()
        loadHome()
    }

    fun refreshProfile() {
        if (!NeteaseHttp.loggedIn()) {
            profile = null
            return
        }
        profileLoading = true
        io.execute {
            try {
                val fetched = NeteaseClient.account()
                profile = fetched
                if (fetched != null) loadMyPlaylists()
            } catch (t: Throwable) {
                sessionError = message(t)
            } finally {
                profileLoading = false
            }
        }
    }

    fun startQrLogin() {
        if (qrState == QrState.LOADING || qrState == QrState.WAITING || qrState == QrState.SCANNED) return
        qrState = QrState.LOADING
        qrMatrix = null
        qrError = null
        qrCreatedAt = 0L
        io.execute {
            try {
                val key = NeteaseClient.qrKey()
                if (key.isNullOrEmpty()) {
                    qrState = QrState.ERROR
                    qrError = "无法创建二维码"
                    return@execute
                }
                val matrix = MusicQr.encode(NeteaseClient.qrContent(key), 4)
                if (matrix == null) {
                    qrState = QrState.ERROR
                    qrError = "二维码生成失败"
                    return@execute
                }
                qrMatrix = matrix
                qrCreatedAt = System.currentTimeMillis()
                qrState = QrState.WAITING
                beginPolling(key, matrix)
            } catch (t: Throwable) {
                qrState = QrState.ERROR
                qrError = message(t)
            }
        }
    }

    private fun beginPolling(key: String, matrix: Array<BooleanArray>) {
        val task = object : Runnable {
            override fun run() {
                if (qrMatrix !== matrix) return
                if (qrAgeMs() > 180000L) {
                    qrState = QrState.EXPIRED
                    return
                }
                val code = try {
                    NeteaseClient.qrCheck(key)
                } catch (t: Throwable) {
                    poller.schedule(this, 2500L, TimeUnit.MILLISECONDS)
                    return
                }
                if (code == 803) {
                    qrState = QrState.SUCCESS
                    refreshProfile()
                    loadHome()
                    return
                }
                if (code == 800) {
                    qrState = QrState.EXPIRED
                    return
                }
                if (code == 802) {
                    qrState = QrState.SCANNED
                } else if (code == 801 && qrState != QrState.SCANNED) {
                    qrState = QrState.WAITING
                }
                poller.schedule(this, 2000L, TimeUnit.MILLISECONDS)
            }
        }
        poller.schedule(task, 1800L, TimeUnit.MILLISECONDS)
    }

    fun cancelQrLogin() {
        if (qrState != QrState.SUCCESS) {
            qrState = QrState.IDLE
            qrMatrix = null
        }
    }

    fun logout() {
        io.execute {
            NeteaseClient.logout()
            profile = null
            myPlaylists = emptyList()
            qrState = QrState.IDLE
            qrMatrix = null
            saveSettings()
        }
    }

    // ------------------------------------------------------ files, settings

    private fun musicDir(): File {
        val dir = File(ConfigSystem.rootFolder, "music")
        if (!dir.isDirectory) dir.mkdirs()
        return dir
    }

    private fun cacheDir(): File {
        val dir = File(musicDir(), "cache")
        if (!dir.isDirectory) dir.mkdirs()
        return dir
    }

    private fun cachedFile(song: Song): File = File(cacheDir(), "${song.id}.mp3")

    private fun isUsable(file: File?): Boolean = file != null && file.isFile && file.length() > 65536L

    private fun download(url: String, target: File, serial: Long) {
        val part = File(target.path + ".part")
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 20000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            connection.setRequestProperty("Referer", "https://music.163.com")
            val expected = connection.contentLength
            val stream = connection.inputStream
            FileOutputStream(part).use { out ->
                val chunk = ByteArray(16384)
                var total = 0L
                while (true) {
                    val read = stream.read(chunk)
                    if (read <= 0) break
                    if (serial != playSerial) throw IllegalStateException("cancelled")
                    out.write(chunk, 0, read)
                    total += read
                    if (expected > 0) downloadProgress = total.toFloat() / expected.toFloat()
                }
            }
            stream.close()
            if (target.isFile) target.delete()
            if (!part.renameTo(target)) throw IllegalStateException("无法写入缓存文件")
        } finally {
            connection?.disconnect()
            if (part.isFile && !target.isFile) part.delete()
        }
    }

    fun cacheBytes(): Long {
        val files = cacheDir().listFiles() ?: return 0L
        var total = 0L
        for (f in files) total += f.length()
        return total
    }

    fun clearCache() {
        cacheDir().listFiles()?.forEach { it.delete() }
    }

    fun saveSettings() {
        try {
            val file = File(musicDir(), "music.txt")
            FileWriter(file, false).use { writer ->
                writer.write("volume: ${engine.volume()}\n")
                writer.write("repeat: ${repeat.name}\n")
                writer.write("shuffle: $shuffle\n")
                writer.write("level: $streamLevel\n")
                writer.write("lyricScale: $lyricScale\n")
                writer.write("lyricAnimation: $lyricAnimation\n")
                writer.write("coverTint: $coverTint\n")
                writer.write("cookie: ${NeteaseHttp.cookieHeader()}\n")
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun loadSettings() {
        try {
            val file = File(musicDir(), "music.txt")
            if (!file.isFile) {
                engine.setVolume(0.8F)
                return
            }
            BufferedReader(FileReader(file)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val colon = line.indexOf(':')
                    if (colon <= 0) continue
                    val key = line.substring(0, colon).trim()
                    val value = line.substring(colon + 1).trim()
                    when (key) {
                        "volume" -> value.toFloatOrNull()?.let { engine.setVolume(it) }
                        "repeat" -> runCatching { repeat = Repeat.valueOf(value) }
                        "shuffle" -> shuffle = value.toBoolean()
                        "level" -> if (value.isNotEmpty()) streamLevel = value
                        "lyricScale" -> value.toFloatOrNull()?.let { lyricScale = max(0.8F, min(1.4F, it)) }
                        "lyricAnimation" -> lyricAnimation = value.toBoolean()
                        "coverTint" -> coverTint = value.toBoolean()
                        "cookie" -> NeteaseHttp.storeAll(value)
                    }
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    /** The game is shutting down: stop the audio line so the process can exit cleanly. */
    fun shutdown() {
        engine.dispose()
        io.shutdownNow()
        poller.shutdownNow()
    }

    private fun message(t: Throwable): String {
        val text = t.message
        return if (text.isNullOrEmpty()) t.javaClass.simpleName else text
    }
}
