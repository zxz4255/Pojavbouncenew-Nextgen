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
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.LinkedBlockingQueue
import java.security.MessageDigest
import java.io.IOException
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

    @Volatile var provider: MusicProvider = MusicProvider.NETEASE
        private set
    @Volatile private var providerEpoch = 0L
    @Volatile private var qrSerial = 0L

    /** A switch invalidates in-flight requests; no cross-provider queue or account state is reused. */
    @Synchronized fun setProvider(value: MusicProvider) {
        if (provider == value) return
        provider = value
        providerEpoch++
        qrSerial++
        qrState = QrState.IDLE; qrMatrix = null; qrError = null
        ++playSerial; engine.stop(); current = null; queue.clear(); cursor = -1; queueRevision++
        loading = false; playbackError = null; alternateSource = null; lyrics = emptyList(); lyricsLoading = false; lyricsSongId = -1L
        profile = null; profileLoading = false; sessionError = null
        home = emptyList(); homeLoading = false; myPlaylists = emptyList(); myPlaylistsLoading = false
        cloudFavoriteSongs = emptyList(); cloudFavoriteUid = -1L; cloudFavoriteError = null; cloudFavoriteLoading = false
        playlistTracks = emptyList(); playlistId = -1; playlistTitle = ""; playlistError = null; playlistLoading = false
        synchronized(searchLock) { ++searchSerial; searchResults = emptyList(); playlistSearchResults = emptyList(); searching = false }
        saveSettings()
        refreshProfile(); loadHome()
    }

    enum class Repeat { OFF, ALL, ONE }

    enum class SearchKind { SONG, PLAYLIST }

    enum class QrState { IDLE, LOADING, WAITING, SCANNED, SUCCESS, EXPIRED, ERROR }

    val engine = AudioEngine()

    private val io = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "liquidbounce-music-io").apply { isDaemon = true }
    }

    // Playback does not queue behind lyrics/home/QR calls. Obsolete queued plays are dropped.
    private val playIo = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>()) { runnable ->
        Thread(runnable, "liquidbounce-music-playback-io").apply { isDaemon = true }
    }
    private val playTimeout = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "liquidbounce-music-playback-timeout").apply { isDaemon = true }
    }
    @Volatile private var loadMessage = "解析中…"
    fun loadMessage(): String = loadMessage

    private val cacheJanitor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "liquidbounce-music-cache-cleanup").apply { isDaemon = true }
    }
    private val cacheName = Regex("(?:[0-9]+|kugou-[0-9]+|kugou-concept-[0-9]+-[0-9]+)\\.mp3")
    private val partialCacheName = Regex("(?:[0-9]+|kugou-[0-9]+|kugou-concept-[0-9]+-[0-9]+)\\.mp3\\.[0-9]+(?:\\.proof)?\\.part")

    // Keep search responsive even when the general music I/O pool is busy with a stream or lyrics.
    private val searchIo = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "liquidbounce-music-search").apply { isDaemon = true }
    }
    private val searchLock = Any()

    private val poller: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "liquidbounce-music-qr").apply { isDaemon = true }
    }

    internal val library: LocalMusicLibrary by lazy {
        LocalMusicLibrary(File(musicDir(), "library.json")).apply { load() }
    }
    @Volatile var cloudFavoriteError: String? = null
        private set
    @Volatile var cloudFavoriteLoading = false
        private set
    @Volatile private var cloudFavoriteSongs: List<Song> = emptyList()
    @Volatile private var cloudFavoriteUid = -1L
    private val cloudFavoritePending = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    @Volatile private var localPlaylistName: String? = null

    private val random = Random()
    private val queue: MutableList<Song> = Collections.synchronizedList(ArrayList())
    private var queueRevision = 0L

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
    private var alternateSource: String? = null
    fun alternateSource(): String? = alternateSource

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
    private var playlistError: String? = null

    @Volatile
    var searchResults: List<Song> = emptyList()
        private set

    @Volatile
    var playlistSearchResults: List<Playlist> = emptyList()
        private set

    @Volatile
    var searchKind: SearchKind = SearchKind.SONG
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

    fun playbackError(): String? = playbackError ?: engine.error()?.let { "音频解码/输出失败：$it" }

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
    @Synchronized fun play(song: Song?, context: List<Song>?) {
        if (song == null) return
        queue.clear()
        queueRevision++
        if (!context.isNullOrEmpty()) queue.addAll(context)
        if (queue.isEmpty()) queue.add(song)
        var position = -1
        for (i in queue.indices) {
            if (queue[i].sameTrack(song)) {
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

    @Synchronized fun retryPlayback(clearAudioCache: Boolean = false) {
        if (current == null) return
        if (clearAudioCache) {
            ++playSerial
            engine.stop()
            clearCache()
        }
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

    /** A stable queue and cursor snapshot: revisions prevent a stale GUI action editing another row. */
    data class QueueView(val songs: List<Song>, val cursor: Int, val revision: Long)
    @Synchronized fun queueView(): QueueView = QueueView(queue.toList(), cursor, queueRevision)
    @Synchronized fun queueSnapshot(): List<Song> = queue.toList()

    @Synchronized fun queuePlay(index: Int, expectedRevision: Long): Boolean {
        if (queueRevision != expectedRevision || index !in queue.indices) return false
        cursor = index
        current = queue[index]
        queueRevision++
        startCurrent()
        return true
    }

    /** Swapping keeps the same track playing, including when it crosses the selected row. */
    @Synchronized fun queueMove(index: Int, direction: Int, expectedRevision: Long): Boolean {
        if (queueRevision != expectedRevision || direction !in listOf(-1, 1)) return false
        val other = index + direction
        if (index !in queue.indices || other !in queue.indices) return false
        Collections.swap(queue, index, other)
        cursor = when (cursor) { index -> other; other -> index; else -> cursor }
        queueRevision++
        return true
    }

    /** Move a row to its final index without replacing the active stream. */
    @Synchronized fun queueMoveTo(from: Int, to: Int, expectedRevision: Long): Boolean {
        if (queueRevision != expectedRevision || from !in queue.indices || to !in queue.indices) return false
        if (from == to) return true
        val song = queue.removeAt(from)
        queue.add(to, song)
        cursor = when {
            cursor == from -> to
            from < cursor && cursor <= to -> cursor - 1
            to <= cursor && cursor < from -> cursor + 1
            else -> cursor
        }
        queueRevision++
        return true
    }

    /** Queue after current, or start immediately if no song is active. */
    @Synchronized fun queueNext(song: Song) {
        if (current == null || cursor !in queue.indices) {
            play(song, listOf(song))
            return
        }
        queue.add(cursor + 1, song)
        queueRevision++
    }

    /** Keep the playing track in the queue; removing it would silently interrupt playback. */
    @Synchronized fun queueRemove(index: Int, expectedRevision: Long): Boolean {
        if (queueRevision != expectedRevision || index !in queue.indices || index == cursor) return false
        queue.removeAt(index)
        if (index < cursor) cursor--
        queueRevision++
        return true
    }

    fun next() = advance(1)

    fun previous() = advance(-1)

    @Synchronized private fun advance(step: Int) {
        if (queue.isEmpty()) return
        if (shuffle && queue.size > 1) {
            val next = random.nextInt(queue.size - 1)
            cursor = if (next >= cursor) next + 1 else next
        } else {
            cursor = (cursor + step + queue.size) % queue.size
        }
        current = queue[cursor]
        queueRevision++
        startCurrent()
    }

    @Synchronized private fun handleTrackEnded() {
        if (repeat == Repeat.ONE) {
            engine.seek(0L)
            engine.resume()
            return
        }
        if (cursor + 1 < queue.size) {
            advance(1)
        } else if (repeat == Repeat.ALL) {
            if (shuffle && queue.size > 1) advance(1)
            else { cursor = -1; advance(1) }
        }
    }

    fun seek(ms: Long) = engine.seek(max(0L, ms))

    fun setVolume(volume: Float) {
        engine.setVolume(volume)
        saveSettings()
    }

    fun volume(): Float = engine.volume()
    fun minecraftOutput(): Boolean = engine.minecraftOutput()
    fun outputDescription(): String = engine.outputDescription()
    fun setMinecraftOutput(value: Boolean) {
        engine.setMinecraftOutput(value)
        saveSettings()
    }

    internal fun viperSettings(): ViperSettings = engine.viperSettings()
    fun viperStatus(): String = engine.viperStatus()
    internal fun setViperSettings(value: ViperSettings) {
        engine.setViperSettings(value)
        saveSettings()
    }

    internal fun toneSettings(): ToneSettings = engine.toneSettings()

    internal fun setToneSettings(value: ToneSettings) {
        engine.setToneSettings(value)
        saveSettings()
    }

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

    @Synchronized private fun startCurrent() {
        val song = current ?: return
        val serial = ++playSerial
        engine.stop() // retire the previous track and clear its error before resolving the new one
        loading = true
        loadMessage = "解析中…"
        downloadProgress = 0.0F
        playbackError = null
        alternateSource = null
        engine.setDurationMs(song.durationMs)
        lyrics = emptyList()
        lyricsSongId = -1L
        loadLyrics(song, serial)
        playIo.queue.clear()
        // Bound the visible wait, even if a provider or a DNS implementation ignores timeouts.
        playTimeout.schedule({
            synchronized(this) {
                if (serial == playSerial && loading) {
                    ++playSerial
                    loading = false
                    downloadProgress = 0.0F
                    playbackError = "请求超时，请检查网络后重试"
                }
            }
        }, 90L, TimeUnit.SECONDS)
        playIo.execute {
            try {
                if (serial != playSerial) return@execute
                var file = cachedFile(song)
                var usedAlternate: Song? = null
                if (!isUsable(file)) {
                    // Resolve and download on the I/O thread; a failed lookup may use a
                    // separately available matching recording from the other provider.
                    var originalResult = runCatching { downloadTrack(song, file, serial) }
                    if (originalResult.isFailure && serial == playSerial && isNetworkFailure(originalResult.exceptionOrNull())) {
                        loadMessage = "网络异常，正在重试…"
                        Thread.sleep(1500L)
                        if (serial != playSerial) return@execute
                        originalResult = runCatching { downloadTrack(song, file, serial) }
                    }
                    if (originalResult.isFailure) {
                        loadMessage = "正在尝试备用音源…"
                        if (serial != playSerial) return@execute
                        val candidates = runCatching { MusicCrossSource.candidates(song) }.getOrDefault(emptyList())
                        for (candidate in candidates) {
                            if (serial != playSerial) return@execute
                            val candidateFile = cachedFile(candidate)
                            val success = isUsable(candidateFile) || runCatching {
                                downloadTrack(candidate, candidateFile, serial)
                            }.isSuccess
                            if (success) {
                                file = candidateFile
                                usedAlternate = candidate
                                break
                            }
                        }
                        if (usedAlternate == null) throw IllegalStateException(
                            if (isNetworkFailure(originalResult.exceptionOrNull()))
                                "网络请求失败，请联网后重试"
                            else "音源不可播放或请求失败；检查网络、账号权益或地区后重试",
                            originalResult.exceptionOrNull(),
                        )
                    }
                }
                synchronized(this) {
                    if (serial != playSerial) return@execute
                    val alternate = usedAlternate
                    alternateSource = alternate?.provider?.label
                    if (alternate != null && alternate.durationMs > 0L) engine.setDurationMs(alternate.durationMs)
                    // Treat a successful cache hit as recent use for the age-based janitor.
                    // It runs under this same lock and does not run while a track is active.
                    val usedAt = System.currentTimeMillis()
                    file.setLastModified(usedAt)
                    cacheProof(file).setLastModified(usedAt)
                    engine.play(file, 0L)
                    loading = false; downloadProgress = 0.0F
                    if (engine.hasTrack()) library.rememberSong(song)
                }
            } catch (t: Throwable) {
                synchronized(this) { if (serial == playSerial) playbackError = message(t) }
            } finally {
                synchronized(this) {
                    if (serial == playSerial) {
                        loading = false
                        downloadProgress = 0.0F
                    }
                }
            }
        }
    }

    private fun isNetworkFailure(error: Throwable?): Boolean {
        var cause = error
        repeat(8) {
            if (cause is IOException) return true
            cause = cause?.cause
        }
        return false
    }

    private fun downloadTrack(song: Song, file: File, serial: Long) {
        if (serial != playSerial) throw IOException("播放请求已取消")
        loadMessage = "正在解析音源…"
        val stream = if (song.provider == MusicProvider.KUGOU) KugouClient.stream(song)
            else NeteaseClient.streamUrl(song.id, streamLevel)
        if (!stream.valid() || stream.preview) throw IllegalStateException("无法获取完整播放地址")
        if (serial != playSerial) throw IOException("播放请求已取消")
        loadMessage = "正在下载音频…"
        download(stream.url, file, serial, song.provider)
        if (!isUsable(file)) {
            synchronized(this) {
                if (serial == playSerial) { file.delete(); cacheProof(file).delete() }
            }
            throw IllegalStateException("音频文件过短或无效")
        }
        synchronized(this) {
            if (serial == playSerial && stream.durationMs > 0L) engine.setDurationMs(stream.durationMs)
        }
    }

    private fun loadLyrics(song: Song, serial: Long) {
        lyricsLoading = true
        io.execute {
            var parsed: List<LyricLine> = emptyList()
            try {
                if (song.provider == MusicProvider.KUGOU) {
                    parsed = LyricsParser.parse(null, KugouClient.lyrics(song), null, null)
                } else {
                    val payload = NeteaseClient.lyrics(song.id)
                    parsed = LyricsParser.parse(payload.yrc, payload.lrc, payload.translation, payload.romanisation)
                }
            } catch (ignored: Throwable) {
            }
            synchronized(this) {
                val track = current
                if (serial == playSerial && track != null && track.sameTrack(song)) {
                    lyrics = parsed
                    lyricsSongId = song.id
                    lyricsLoading = false
                }
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

    fun playlistError(): String? = playlistError

    fun searching(): Boolean = searching

    fun searchError(): String? = searchError

    fun loadHome() {
        if (homeLoading) return
        homeLoading = true
        val epoch = providerEpoch
        val selected = provider
        io.execute {
            try {
                val results = if (selected == MusicProvider.KUGOU) KugouClient.searchPlaylists("热门", 12) else NeteaseClient.personalized(12)
                if (providerEpoch == epoch) home = results
            } catch (ignored: Throwable) {
            } finally {
                if (providerEpoch == epoch) homeLoading = false
            }
        }
    }

    fun loadMyPlaylists() {
        if (provider != MusicProvider.NETEASE) return
        val epoch = providerEpoch
        val account = profile ?: return
        if (myPlaylistsLoading) return
        myPlaylistsLoading = true
        io.execute {
            try {
                val lists = NeteaseClient.userPlaylists(account.userId, 1000)
                if (providerEpoch != epoch || profile?.userId != account.userId) return@execute
                myPlaylists = lists
                cloudFavoriteLoading = true
                if (lists.isEmpty()) throw IllegalStateException("未找到网易云我喜欢的音乐歌单")
                val favorites = NeteaseClient.playlistTracks(lists[0].id, 5000)
                if (providerEpoch == epoch && profile?.userId == account.userId) {
                    cloudFavoriteSongs = favorites
                    cloudFavoriteUid = account.userId
                    cloudFavoriteError = null
                }
            } catch (t: Throwable) {
                if (providerEpoch == epoch && profile?.userId == account.userId) cloudFavoriteError = "歌单/收藏加载失败：${message(t)}"
            } finally {
                if (providerEpoch == epoch) { cloudFavoriteLoading = false; myPlaylistsLoading = false }
            }
        }
    }

    fun openPlaylist(playlist: Playlist) {
        val id = playlist.id
        val title = playlist.name
        val epoch = providerEpoch
        localPlaylistName = null
        playlistId = id
        playlistTitle = title ?: ""
        playlistTracks = emptyList()
        playlistError = null
        playlistLoading = true
        io.execute {
            try {
                val tracks = if (playlist.provider == MusicProvider.KUGOU) KugouClient.playlistTracks(playlist.providerKey, 5000) else NeteaseClient.playlistTracks(id, 5000)
                if (providerEpoch == epoch && playlistId == id) playlistTracks = tracks
            } catch (t: Throwable) {
                if (providerEpoch == epoch && playlistId == id) playlistError = "歌单加载失败：${message(t)}"
            } finally {
                if (providerEpoch == epoch && playlistId == id) playlistLoading = false
            }
        }
    }

    fun isCloudFavorite(song: Song): Boolean = profile?.userId == cloudFavoriteUid && cloudFavoriteSongs.any { it.id == song.id }
    fun favoritePending(song: Song): Boolean = cloudFavoritePending.contains(song.id)
    fun toggleFavorite(song: Song) {
        val account = profile
        if (provider == MusicProvider.KUGOU || song.provider != MusicProvider.NETEASE) {
            cloudFavoriteError = null
            library.toggleFavorite(song)
            refreshLocalPlaylist()
            return
        }
        if (account == null) {
            // A persisted login cookie with an unresolved profile must not silently become a
            // local favourite: that would falsely suggest the account was updated.
            if (profileLoading || NeteaseHttp.loggedIn()) {
                cloudFavoriteError = "登录状态尚未确认，请稍后或重新登录"
                return
            }
            cloudFavoriteError = null
            library.toggleFavorite(song)
            refreshLocalPlaylist()
            return
        }
        if (cloudFavoriteUid != account.userId || cloudFavoriteLoading) {
            cloudFavoriteError = "网易云收藏尚未加载，请稍后再试"
            return
        }
        if (!cloudFavoritePending.add(song.id)) return
        val adding = !isCloudFavorite(song)
        val epoch = providerEpoch
        cloudFavoriteError = null
        io.execute {
            try {
                NeteaseClient.like(song.id, adding)
                if (providerEpoch == epoch && profile?.userId == account.userId && cloudFavoriteUid == account.userId) {
                    synchronized(this) {
                        cloudFavoriteSongs = if (adding) listOf(song) + cloudFavoriteSongs.filterNot { it.id == song.id }
                            else cloudFavoriteSongs.filterNot { it.id == song.id }
                    }
                }
            } catch (t: Throwable) {
                if (providerEpoch == epoch && profile?.userId == account.userId) cloudFavoriteError = "网易云收藏操作失败：${message(t)}"
            } finally { cloudFavoritePending.remove(song.id) }
        }
    }
    fun favoriteSelected(song: Song): Boolean = if (provider == MusicProvider.KUGOU || song.provider != MusicProvider.NETEASE || profile == null) library.isFavorite(song) else isCloudFavorite(song)
    fun openLocalPlaylist(name: String, songs: List<Song>) {
        playlistId = -2L
        localPlaylistName = name
        playlistTitle = name
        playlistError = null
        playlistLoading = false
        playlistTracks = songs
    }
    fun refreshLocalPlaylist() {
        val name = localPlaylistName ?: return
        playlistTracks = when (name) {
            "本地收藏" -> library.favoriteSongs()
            "最近播放" -> library.listeningHistory()
            else -> library.playlistSongs(name)
        }
    }

    /** Each new request invalidates every older response, including its later cover enrichment. */
    fun search(query: String?, kind: SearchKind = searchKind) {
        val trimmed = query?.trim() ?: ""
        val serial: Long
        synchronized(searchLock) {
            serial = ++searchSerial
            searchQuery = trimmed
            searchKind = kind
            searchResults = emptyList()
            playlistSearchResults = emptyList()
            searchError = null
            searching = trimmed.isNotEmpty()
        }
        if (trimmed.isEmpty()) return
        val selected = provider
        searchIo.execute {
            if (serial != searchSerial) return@execute
            try {
                if (kind == SearchKind.SONG) {
                    val songs = if (selected == MusicProvider.KUGOU) KugouClient.search(trimmed, 30) else NeteaseClient.search(trimmed, 30)
                    synchronized(searchLock) {
                        if (serial != searchSerial) return@execute
                        searchResults = songs
                        searching = false // publish results before the optional cover request
                    }
                    // Cover lookup is optional; never hold either dedicated search worker on it.
                    if (selected == MusicProvider.NETEASE) io.execute cover@{
                        if (serial != searchSerial) return@cover
                        val withCovers = NeteaseClient.searchCovers(songs)
                        synchronized(searchLock) {
                            if (serial == searchSerial) searchResults = withCovers
                        }
                    }
                } else {
                    val playlists = if (selected == MusicProvider.KUGOU) KugouClient.searchPlaylists(trimmed, 30) else NeteaseClient.searchPlaylists(trimmed, 30)
                    synchronized(searchLock) {
                        if (serial != searchSerial) return@execute
                        playlistSearchResults = playlists
                        searching = false
                    }
                }
            } catch (t: Throwable) {
                synchronized(searchLock) {
                    if (serial == searchSerial) {
                        searchError = message(t)
                        searching = false
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------- account

    fun profileLoading(): Boolean = profileLoading

    fun qrAgeMs(): Long = if (qrCreatedAt == 0L) 0L else System.currentTimeMillis() - qrCreatedAt

    fun qrError(): String? = qrError

    fun sessionError(): String? = sessionError

    /** Restores the saved cookie jar and refreshes the profile. */
    @Synchronized fun boot() {
        if (settingsLoaded) {
            refreshProfile()
            return
        }
        settingsLoaded = true
        loadSettings()
        // First check when Music boots, then check periodically while the game is running.
        cacheJanitor.scheduleWithFixedDelay({
            try { cleanupExpiredCache() } catch (e: Exception) {
                System.err.println("[Music cache] cleanup failed: ${e.javaClass.simpleName}")
            }
        }, 0L, 30L, TimeUnit.MINUTES)
        refreshProfile()
        loadHome()
    }

    fun refreshProfile() {
        val epoch = providerEpoch
        if (provider == MusicProvider.KUGOU) {
            profile = if (KugouClient.token.isNotEmpty() && KugouClient.userId > 0)
                Profile(KugouClient.userId, "酷狗用户 ${KugouClient.userId}", "", 0) else null
            return
        }
        if (!NeteaseHttp.loggedIn()) { profile = null; return }
        profileLoading = true
        io.execute {
            try {
                val fetched = NeteaseClient.account()
                if (epoch != providerEpoch) return@execute
                profile = fetched
                if (fetched != null) loadMyPlaylists()
            } catch (t: Throwable) {
                if (epoch == providerEpoch) sessionError = message(t)
            } finally {
                if (epoch == providerEpoch) profileLoading = false
            }
        }
    }

    @Volatile var qrConcept = false
        private set
    @Synchronized fun startQrLogin(concept: Boolean = false) {
        if (qrState == QrState.LOADING || qrState == QrState.WAITING || qrState == QrState.SCANNED) return
        val serial = ++qrSerial
        val selected = provider
        qrConcept = selected == MusicProvider.KUGOU && concept
        val requestedConcept = qrConcept
        qrState = QrState.LOADING; qrMatrix = null; qrError = null; qrCreatedAt = 0L
        io.execute {
            try {
                val key = if (selected == MusicProvider.KUGOU) KugouClient.qrKey(requestedConcept) else NeteaseClient.qrKey()
                if (key.isNullOrEmpty()) throw IllegalStateException("无法创建二维码")
                val content = if (selected == MusicProvider.KUGOU) KugouClient.qrContent(key, requestedConcept) else NeteaseClient.qrContent(key)
                val matrix = MusicQr.encode(content, 4) ?: throw IllegalStateException("二维码生成失败")
                synchronized(this) {
                    if (serial != qrSerial || selected != provider) return@execute
                    qrMatrix = matrix; qrCreatedAt = System.currentTimeMillis(); qrState = QrState.WAITING
                    beginPolling(key, matrix, selected, serial, requestedConcept)
                }
            } catch (t: Throwable) {
                if (serial == qrSerial && selected == provider) { qrState = QrState.ERROR; qrError = message(t) }
            }
        }
    }

    private fun beginPolling(key: String, matrix: Array<BooleanArray>, selected: MusicProvider, serial: Long, requestedConcept: Boolean) {
        val task = object : Runnable {
            override fun run() {
                if (serial != qrSerial || qrMatrix !== matrix || selected != provider) return
                if (qrAgeMs() > 180000L) { qrState = QrState.EXPIRED; return }
                try {
                    val kg = if (selected == MusicProvider.KUGOU) KugouClient.qrCheck(key, requestedConcept) else null
                    val netease = if (kg == null) NeteaseClient.qrCheck(key) else null
                    val code = kg?.status ?: netease!!.code
                    synchronized(this@MusicManager) {
                    if (serial != qrSerial || qrMatrix !== matrix || selected != provider) return
                    when (code) {
                        if (selected == MusicProvider.KUGOU) 4 else 803 -> {
                            if (kg != null) {
                                if (kg.userId <= 0 || kg.token.isEmpty()) throw IllegalStateException("酷狗未返回有效登录凭据")
                                KugouClient.token = kg.token; KugouClient.userId = kg.userId; KugouClient.conceptSession = requestedConcept
                                profile = Profile(kg.userId, kg.nickname.ifBlank { "酷狗用户 ${kg.userId}" }, "", 0)
                                saveSettings()
                            } else {
                                if (netease!!.cookie.isEmpty()) throw IllegalStateException("网易云未返回登录凭据")
                                NeteaseHttp.storeAll(netease.cookie)
                                saveSettings()
                                refreshProfile()
                            }
                            qrState = QrState.SUCCESS; loadHome(); return
                        }
                        if (selected == MusicProvider.KUGOU) 0 else 800 -> { qrState = QrState.EXPIRED; return }
                        if (selected == MusicProvider.KUGOU) 2 else 802 -> qrState = QrState.SCANNED
                        if (selected == MusicProvider.KUGOU) 1 else 801 -> if (qrState != QrState.SCANNED) qrState = QrState.WAITING
                        else -> { qrState = QrState.ERROR; qrError = "未知扫码状态：$code"; return }
                    }
                    }
                } catch (t: Throwable) {
                    if (serial != qrSerial || selected != provider) return
                    qrState = QrState.ERROR; qrError = message(t); return
                }
                poller.schedule(this, 2000L, TimeUnit.MILLISECONDS)
            }
        }
        poller.schedule(task, 1800L, TimeUnit.MILLISECONDS)
    }

    @Synchronized fun cancelQrLogin() { ++qrSerial; qrState = QrState.IDLE; qrMatrix = null }

    @Synchronized fun logout() {
        val selected = provider
        ++qrSerial; qrState = QrState.IDLE; qrMatrix = null
        if (selected == MusicProvider.KUGOU) {
            KugouClient.token = ""; KugouClient.userId = 0; KugouClient.conceptSession = false; profile = null; saveSettings(); return
        }
        // Clear the local session immediately; never let a late remote logout clear a newer login.
        NeteaseHttp.logout()
        providerEpoch++
        profile = null; profileLoading = false; cloudFavoriteUid = -1L; cloudFavoriteSongs = emptyList()
        cloudFavoriteError = null; cloudFavoriteLoading = false; myPlaylists = emptyList(); saveSettings()
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

    private fun cachedFile(song: Song): File = File(cacheDir(), if (song.provider == MusicProvider.NETEASE) "${song.id}.mp3" else if (KugouClient.conceptSession) "kugou-concept-${KugouClient.userId}-${song.id}.mp3" else "kugou-${song.id}.mp3")

    private fun cacheProof(file: File): File = File(file.path + ".verified")

    // Old versions accepted any file over 64 KiB, including interrupted downloads. Only use
    // caches produced after full-length, format and SHA-256 checks; legacy files are left in
    // place until a replacement succeeds, but cannot be trusted for playback.
    private fun isUsable(file: File?): Boolean {
        if (file == null || !file.isFile || file.length() <= 65536L) return false
        return try {
            val parts = cacheProof(file).readText().trim().split(" ")
            parts.size == 2 && parts[0].toLongOrNull() == file.length() &&
                looksLikeAudio(file) && parts[1] == fileHash(file)
        } catch (_: Exception) { false }
    }

    private fun fileHash(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(16384)
            while (true) {
                val n = input.read(bytes)
                if (n < 0) break
                if (n > 0) hash.update(bytes, 0, n)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun looksLikeAudio(file: File): Boolean {
        val bytes = ByteArray(65536)
        val n = file.inputStream().buffered().use { input ->
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read <= 0) break
                count += read
            }
            count
        }
        if (n >= 4 && bytes[0] == 79.toByte() && bytes[1] == 103.toByte() &&
            bytes[2] == 103.toByte() && bytes[3] == 83.toByte()) return true
        if (n >= 3 && bytes[0] == 73.toByte() && bytes[1] == 68.toByte() && bytes[2] == 51.toByte()) return true
        for (i in 0 until n - 1) {
            val a = bytes[i].toInt() and 255; val b = bytes[i + 1].toInt() and 255
            if (a == 255 && (b and 0xE0) == 0xE0 && (b and 0x18) != 0x08 && (b and 0x06) != 0) return true
        }
        return false
    }

    private fun download(url: String, target: File, serial: Long, source: MusicProvider) {
        val part = File(target.path + ".$serial.part")
        val proofPart = File(target.path + ".$serial.proof.part")
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 20000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            connection.setRequestProperty("Referer", if (source == MusicProvider.KUGOU) "https://www.kugou.com/" else "https://music.163.com")
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("音频下载 HTTP $status")
            val expected = connection.contentLengthLong
            val hash = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { stream ->
                FileOutputStream(part).use { out ->
                    val chunk = ByteArray(16384)
                    while (true) {
                        if (serial != playSerial) throw IOException("播放请求已取消")
                        val read = stream.read(chunk)
                        if (read < 0) break
                        if (read == 0) continue
                        total += read
                        if (total > 100L * 1024L * 1024L) throw IOException("音频文件过大")
                        out.write(chunk, 0, read); hash.update(chunk, 0, read)
                        if (expected > 0 && serial == playSerial) downloadProgress = (total.toFloat() / expected).coerceIn(0f, 1f)
                    }
                }
            }
            if (expected > 0 && total != expected) throw IOException("音频下载不完整")
            if (total <= 65536L || !looksLikeAudio(part)) throw IOException("音频内容无效或过短")
            val digest = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            proofPart.writeText("$total $digest")
            synchronized(this) {
                if (serial != playSerial) throw IOException("播放请求已取消")
                // Remove proof before replacing file; a crash can never validate partial content.
                cacheProof(target).delete()
                if (target.isFile && !target.delete()) throw IOException("无法替换旧缓存")
                if (!part.renameTo(target)) throw IOException("无法写入缓存文件")
                if (!proofPart.renameTo(cacheProof(target))) throw IOException("无法写入缓存校验")
            }
        } finally {
            connection?.disconnect()
            if (part.isFile) part.delete()
            if (proofPart.isFile) proofPart.delete()
        }
    }

    /**
     * Only delete cache names made by this player. Defer while a playback worker is resolving
     * or downloading; otherwise skip the file held by the audio engine, including while paused.
     * The manager lock serializes this pass with publishing/stopping the current track.
     */
    @Synchronized private fun cleanupExpiredCache() {
        if (loading || playIo.activeCount > 0) return
        val protectedFile = engine.cachedFileInUse()
        val now = System.currentTimeMillis()
        val files = cacheDir().listFiles() ?: return
        val audioAge = TimeUnit.DAYS.toMillis(7)
        val partialAge = TimeUnit.DAYS.toMillis(1)
        fun expired(file: File, age: Long): Boolean {
            val modified = file.lastModified()
            return modified > 0L && modified <= now && now - modified >= age
        }
        for (file in files) {
            if (!file.isFile) continue
            val name = file.name
            if (protectedFile != null &&
                (file == protectedFile || file == cacheProof(protectedFile))) continue
            when {
                cacheName.matches(name) && expired(file, audioAge) -> {
                    if (file.delete()) cacheProof(file).delete()
                }
                partialCacheName.matches(name) && expired(file, partialAge) -> file.delete()
                name.endsWith(".verified") && cacheName.matches(name.removeSuffix(".verified")) -> {
                    val base = File(file.parentFile, name.removeSuffix(".verified"))
                    if (!base.exists() && expired(file, audioAge)) file.delete()
                }
            }
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

    @Synchronized fun saveSettings() {
        try {
            val file = File(musicDir(), "music.txt")
            FileWriter(file, false).use { writer ->
                writer.write("volume: ${engine.volume()}\n")
                writer.write("minecraftOutput: ${engine.minecraftOutput()}\n")
                val v = engine.viperSettings()
                writer.write("viperEnabled: ${v.enabled}\n")
                writer.write("viperBass: ${v.bass}\n")
                writer.write("viperBassMode: ${v.bassMode}\n")
                writer.write("viperBassFrequency: ${v.bassFrequency}\n")
                writer.write("viperBassGain: ${v.bassGain}\n")
                writer.write("viperClarity: ${v.clarity}\n")
                writer.write("viperClarityMode: ${v.clarityMode}\n")
                writer.write("viperClarityGain: ${v.clarityGain}\n")
                writer.write("viperEq: ${v.eq}\n")
                writer.write("viperBands: ${v.bands.joinToString(",")}\n")
                writer.write("viperReverb: ${v.reverb}\n")
                writer.write("viperReverbWet: ${v.reverbWet}\n")
                writer.write("viperCure: ${v.cure}\n")
                writer.write("viperCurePreset: ${v.curePreset}\n")
                writer.write("viperTube: ${v.tube}\n")
                writer.write("viperX_agc: ${v.agc}\n")
                writer.write("viperX_agcStrength: ${v.agcStrength}\n")
                writer.write("viperX_lufs: ${v.lufs}\n")
                writer.write("viperX_lufsTarget: ${v.lufsTarget}\n")
                writer.write("viperX_spectrum: ${v.spectrum}\n")
                writer.write("viperX_spectrumFrequency: ${v.spectrumFrequency}\n")
                writer.write("viperX_spectrumExciter: ${v.spectrumExciter}\n")
                writer.write("viperX_field: ${v.field}\n")
                writer.write("viperX_fieldWidth: ${v.fieldWidth}\n")
                writer.write("viperX_diff: ${v.diff}\n")
                writer.write("viperX_diffDelay: ${v.diffDelay}\n")
                writer.write("viperX_stereo: ${v.stereo}\n")
                writer.write("viperX_stereoWidth: ${v.stereoWidth}\n")
                writer.write("viperX_dynamic: ${v.dynamic}\n")
                writer.write("viperX_dynamicStrength: ${v.dynamicStrength}\n")
                writer.write("viperX_analog: ${v.analog}\n")
                writer.write("viperX_analogMode: ${v.analogMode}\n")
                writer.write("viperX_speaker: ${v.speaker}\n")
                writer.write("viperX_headphone: ${v.headphone}\n")
                writer.write("viperX_headphoneQuality: ${v.headphoneQuality}\n")
                writer.write("viperX_jdsp: ${v.jdsp}\n")
                writer.write("viperX_jdspPreset: ${v.jdspPreset}\n")
                writer.write("viperX_limiterThreshold: ${v.limiterThreshold}\n")
                writer.write("viperX_channelPan: ${v.channelPan}\n")
                val tone = engine.toneSettings()
                writer.write("toneEnabled: ${tone.enabled}\n")
                writer.write("toneLow: ${tone.low}\n")
                writer.write("toneMid: ${tone.mid}\n")
                writer.write("toneHigh: ${tone.high}\n")
                writer.write("repeat: ${repeat.name}\n")
                writer.write("shuffle: $shuffle\n")
                writer.write("level: $streamLevel\n")
                writer.write("lyricScale: $lyricScale\n")
                writer.write("lyricAnimation: $lyricAnimation\n")
                writer.write("coverTint: $coverTint\n")
                writer.write("provider: ${provider.name}\n")
                writer.write("kugouMid: ${KugouClient.deviceMid}\n")
                writer.write("kugouUserId: ${KugouClient.userId}\n")
                writer.write("kugouConcept: ${KugouClient.conceptSession}\n")
                writer.write("kugouToken: ${KugouClient.token.replace("\n", "").replace("\r", "")}\n")
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
                        "minecraftOutput" -> engine.setMinecraftOutput(value.toBoolean())
                        "viperEnabled" -> engine.setViperSettings(engine.viperSettings().copy(enabled = value.toBoolean()))
                        "viperBass" -> engine.setViperSettings(engine.viperSettings().copy(bass = value.toBoolean()))
                        "viperBassMode" -> value.toIntOrNull()?.let { engine.setViperSettings(engine.viperSettings().copy(bassMode = it)) }
                        "viperBassFrequency" -> value.toIntOrNull()?.let { engine.setViperSettings(engine.viperSettings().copy(bassFrequency = it)) }
                        "viperBassGain" -> value.toFloatOrNull()?.let { engine.setViperSettings(engine.viperSettings().copy(bassGain = it)) }
                        "viperClarity" -> engine.setViperSettings(engine.viperSettings().copy(clarity = value.toBoolean()))
                        "viperClarityMode" -> value.toIntOrNull()?.let { engine.setViperSettings(engine.viperSettings().copy(clarityMode = it)) }
                        "viperClarityGain" -> value.toFloatOrNull()?.let { engine.setViperSettings(engine.viperSettings().copy(clarityGain = it)) }
                        "viperEq" -> engine.setViperSettings(engine.viperSettings().copy(eq = value.toBoolean()))
                        "viperBands" -> {
                            val bands = value.split(',').mapNotNull { it.toFloatOrNull() }
                            if (bands.size == 10) engine.setViperSettings(engine.viperSettings().copy(bands = bands))
                        }
                        "viperReverb" -> engine.setViperSettings(engine.viperSettings().copy(reverb = value.toBoolean()))
                        "viperReverbWet" -> value.toFloatOrNull()?.let { engine.setViperSettings(engine.viperSettings().copy(reverbWet = it)) }
                        "viperCure" -> engine.setViperSettings(engine.viperSettings().copy(cure = value.toBoolean()))
                        "viperCurePreset" -> value.toIntOrNull()?.let { engine.setViperSettings(engine.viperSettings().copy(curePreset = it)) }
                        "viperTube" -> engine.setViperSettings(engine.viperSettings().copy(tube = value.toBoolean()))
                        "viperX_agc" -> engine.setViperSettings(engine.viperSettings().copy(agc = value.toBoolean()))
                        "viperX_agcStrength" -> value.toFloatOrNull()?.let { engine.setViperSettings(engine.viperSettings().copy(agcStrength = it)) }
                        "viperX_lufs" -> engine.setViperSettings(engine.viperSettings().copy(lufs = value.toBoolean()))
                        "viperX_lufsTarget" -> value.toFloatOrNull()?.let { engine.setViperSettings(engine.viperSettings().copy(lufsTarget = it)) }
                        "viperX_spectrum" -> engine.setViperSettings(engine.viperSettings().copy(spectrum = value.toBoolean()))
                        "viperX_spectrumFrequency" -> value.toIntOrNull()?.let { engine.setViperSettings(engine.viperSettings().copy(spectrumFrequency = it)) }
                        "viperX_spectrumExciter" -> value.toFloatOrNull()?.let { engine.setViperSettings(engine.viperSettings().copy(spectrumExciter = it)) }
                        "viperX_field" -> engine.setViperSettings(engine.viperSettings().copy(field = value.toBoolean()))
                        "viperX_fieldWidth" -> value.toFloatOrNull()?.let { engine.setViperSettings(engine.viperSettings().copy(fieldWidth = it)) }
                        "viperX_diff" -> engine.setViperSettings(engine.viperSettings().copy(diff = value.toBoolean()))
                        "viperX_diffDelay" -> value.toFloatOrNull()?.let { engine.setViperSettings(engine.viperSettings().copy(diffDelay = it)) }
                        "viperX_stereo" -> engine.setViperSettings(engine.viperSettings().copy(stereo = value.toBoolean()))
                        "viperX_stereoWidth" -> value.toFloatOrNull()?.let { engine.setViperSettings(engine.viperSettings().copy(stereoWidth = it)) }
                        "viperX_dynamic" -> engine.setViperSettings(engine.viperSettings().copy(dynamic = value.toBoolean()))
                        "viperX_dynamicStrength" -> value.toFloatOrNull()?.let { engine.setViperSettings(engine.viperSettings().copy(dynamicStrength = it)) }
                        "viperX_analog" -> engine.setViperSettings(engine.viperSettings().copy(analog = value.toBoolean()))
                        "viperX_analogMode" -> value.toIntOrNull()?.let { engine.setViperSettings(engine.viperSettings().copy(analogMode = it)) }
                        "viperX_speaker" -> engine.setViperSettings(engine.viperSettings().copy(speaker = value.toBoolean()))
                        "viperX_headphone" -> engine.setViperSettings(engine.viperSettings().copy(headphone = value.toBoolean()))
                        "viperX_headphoneQuality" -> value.toIntOrNull()?.let { engine.setViperSettings(engine.viperSettings().copy(headphoneQuality = it)) }
                        "viperX_jdsp" -> engine.setViperSettings(engine.viperSettings().copy(jdsp = value.toBoolean()))
                        "viperX_jdspPreset" -> value.toIntOrNull()?.let { engine.setViperSettings(engine.viperSettings().copy(jdspPreset = it)) }
                        "viperX_limiterThreshold" -> value.toFloatOrNull()?.let { engine.setViperSettings(engine.viperSettings().copy(limiterThreshold = it)) }
                        "viperX_channelPan" -> value.toFloatOrNull()?.let { engine.setViperSettings(engine.viperSettings().copy(channelPan = it)) }
                        "toneEnabled" -> engine.setToneSettings(engine.toneSettings().copy(enabled = value.toBoolean()))
                        "toneLow" -> value.toFloatOrNull()?.let { engine.setToneSettings(engine.toneSettings().copy(low = it)) }
                        "toneMid" -> value.toFloatOrNull()?.let { engine.setToneSettings(engine.toneSettings().copy(mid = it)) }
                        "toneHigh" -> value.toFloatOrNull()?.let { engine.setToneSettings(engine.toneSettings().copy(high = it)) }
                        "repeat" -> runCatching { repeat = Repeat.valueOf(value) }
                        "shuffle" -> shuffle = value.toBoolean()
                        "level" -> if (value.isNotEmpty()) streamLevel = value
                        "lyricScale" -> value.toFloatOrNull()?.let { lyricScale = max(0.8F, min(1.4F, it)) }
                        "lyricAnimation" -> lyricAnimation = value.toBoolean()
                        "coverTint" -> coverTint = value.toBoolean()
                        "provider" -> runCatching { provider = MusicProvider.valueOf(value) }
                        "kugouMid" -> if (value.matches(Regex("[a-fA-F0-9]{32}"))) KugouClient.deviceMid = value
                        "kugouUserId" -> KugouClient.userId = value.toLongOrNull() ?: 0L
                        "kugouConcept" -> KugouClient.conceptSession = value == "true"
                        "kugouToken" -> KugouClient.token = value
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
        playIo.shutdownNow()
        playTimeout.shutdownNow()
        cacheJanitor.shutdownNow()
        searchIo.shutdownNow()
        poller.shutdownNow()
    }

    private fun message(t: Throwable): String {
        val text = t.message
        return if (text.isNullOrEmpty()) t.javaClass.simpleName else text
    }
}
