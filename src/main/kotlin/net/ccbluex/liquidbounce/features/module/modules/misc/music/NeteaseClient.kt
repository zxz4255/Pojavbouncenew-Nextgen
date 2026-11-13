package net.ccbluex.liquidbounce.features.module.modules.misc.music

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLEncoder
import kotlin.math.min

/**
 * The NetEase Cloud Music endpoints the player needs: search, playlists, stream URLs, lyrics, the
 * signed-in profile and the QR login handshake.
 *
 * <p>Ported 1:1 from NeoRaven's `music.NeteaseClient`. Everything is synchronous and blocking;
 * [MusicManager] calls it from its I/O pool. The request shape (weapi paths and bodies) matches the
 * web client — the server rejects anything else.</p>
 */
object NeteaseClient {

    private val GSON = Gson()

    // -------------------------------------------------- search / discovery

    @Throws(Exception::class)
    fun search(keyword: String, limit: Int): List<Song> {
        val body = JsonObject()
        body.addProperty("s", keyword)
        body.addProperty("type", 1)
        body.addProperty("limit", limit)
        body.addProperty("offset", 0)
        val root = weapi("search/get", body)
        requireSuccess(root, "歌曲搜索")
        val songs = ArrayList<Song>()
        val result = obj(root, "result") ?: return songs
        val array = arr(result, "songs") ?: return songs
        for (element in array) {
            parseSong(element)?.let { songs.add(it) }
        }
        return songs
    }

    /** NetEase web search type 1000: public playlists, subject to platform visibility. */
    @Throws(Exception::class)
    fun searchPlaylists(keyword: String, limit: Int): List<Playlist> {
        val body = JsonObject()
        body.addProperty("s", keyword)
        body.addProperty("type", 1000)
        body.addProperty("limit", limit)
        body.addProperty("offset", 0)
        val root = weapi("search/get", body)
        requireSuccess(root, "歌单搜索")
        val results = ArrayList<Playlist>()
        val array = arr(obj(root, "result"), "playlists") ?: return results
        for (element in array) {
            if (results.size >= limit) break
            parsePlaylist(element)?.let { results.add(it) }
        }
        return results
    }

    /** Enrich an independent copy after the initial search results are visible. */
    fun searchCovers(songs: List<Song>): List<Song> = ArrayList(songs).also { fillMissingCovers(it) }

    @Throws(Exception::class)
    fun personalized(limit: Int): List<Playlist> {
        val body = JsonObject()
        body.addProperty("limit", limit)
        body.addProperty("offset", 0)
        body.addProperty("total", true)
        body.addProperty("n", 1000)
        val root = weapi("personalized/playlist", body)
        val playlists = ArrayList<Playlist>()
        val array = arr(root, "result") ?: return playlists
        for (element in array) {
            if (playlists.size >= limit) break
            parsePlaylist(element)?.let { playlists.add(it) }
        }
        return playlists
    }

    @Throws(Exception::class)
    fun userPlaylists(uid: Long, limit: Int): List<Playlist> {
        val playlists = ArrayList<Playlist>()
        val seen = HashSet<Long>()
        var offset = 0
        while (playlists.size < limit) {
            val pageSize = min(50, limit - playlists.size)
            val body = JsonObject()
            body.addProperty("uid", uid)
            body.addProperty("limit", pageSize)
            body.addProperty("offset", offset)
            body.addProperty("includeVideo", true)
            val root = weapi("user/playlist", body)
            requireSuccess(root, "歌单")
            val array = arr(root, "playlist") ?: throw IllegalStateException("网易云未返回歌单列表")
            var added = 0
            for (element in array) {
                val list = parsePlaylist(element) ?: continue
                if (seen.add(list.id)) { playlists.add(list); added++ }
                if (playlists.size >= limit) break
            }
            if (array.size() < pageSize || added == 0) break
            offset += array.size()
        }
        return playlists
    }

    private fun requireSuccess(root: JsonObject, action: String) {
        val code = root.get("code")?.asInt ?: -1
        if (code != 200) throw IllegalStateException("$action：网易云返回 $code")
    }

    /** Sends a real account like/unlike; only code 200 counts as success. */
    @Throws(Exception::class)
    fun like(songId: Long, like: Boolean) {
        val body = JsonObject()
        body.addProperty("alg", "itembased")
        body.addProperty("trackId", songId)
        body.addProperty("like", like)
        body.addProperty("time", "3")
        val result = weapi("radio/like", body)
        val code = result.get("code")?.asInt ?: -1
        if (code != 200) throw IllegalStateException("网易云返回 $code（未同步）")
    }

    /** Loads a playlist's tracks; falls back to the id list when the response omits them. */
    @Throws(Exception::class)
    fun playlistTracks(playlistId: Long, limit: Int): List<Song> {
        val body = JsonObject()
        body.addProperty("id", playlistId)
        body.addProperty("n", 100000)
        body.addProperty("s", 8)
        val root = weapi("v6/playlist/detail", body)
        requireSuccess(root, "歌单详情")
        val playlist = obj(root, "playlist") ?: throw IllegalStateException("网易云未返回歌单详情")
        val songs = ArrayList<Song>()
        val tracks = arr(playlist, "tracks")
        if (tracks != null) {
            for (element in tracks) {
                if (songs.size >= limit) break
                parseSong(element)?.let { songs.add(it) }
            }
        }
        val ids = arr(playlist, "trackIds") ?: return songs
        if (songs.size >= min(limit, ids.size())) return songs
        val list = ArrayList<Long>()
        for (element in ids) {
            if (list.size >= limit) break
            val entry = if (element.isJsonObject()) element.asJsonObject else null
            if (entry != null && entry.has("id") && !entry.get("id")!!.isJsonNull()) {
                list.add(entry.get("id")!!.asLong)
            }
        }
        val byId = (songs + songDetails(list)).associateBy { it.id }
        return list.mapNotNull { byId[it] }
    }

    @Throws(Exception::class)
    fun songDetails(ids: List<Long>?): List<Song> {
        val songs = ArrayList<Song>()
        if (ids.isNullOrEmpty()) return songs
        var start = 0
        while (start < ids.size) {
            val end = min(start + 500, ids.size)
            val c = StringBuilder("[")
            for (i in start until end) {
                if (i > start) c.append(',')
                c.append("{\"id\":").append(ids[i]).append('}')
            }
            c.append(']')
            val body = JsonObject()
            body.addProperty("c", c.toString())
            val root = weapi("v3/song/detail", body)
            requireSuccess(root, "歌曲详情")
            val array = arr(root, "songs")
            if (array != null) {
                for (element in array) {
                    parseSong(element)?.let { songs.add(it) }
                }
            }
            start += 500
        }
        return songs
    }

    // ------------------------------------------------------------ playback

    /** Result of a stream URL lookup: the signed URL and the server-side track length. */
    class Stream(url: String?, val durationMs: Long, val preview: Boolean = false) {
        val url: String = url ?: ""

        fun valid(): Boolean = url.isNotEmpty()
    }

    /**
     * Resolves a playable URL. `level` maps to the API's quality names: `standard`, `higher`,
     * `exhigh`, `lossless`. The port always asks for `mp3` because the bundled JLayer decoder only
     * understands MPEG audio.
     */
    @Throws(Exception::class)
    fun streamUrl(songId: Long, level: String?): Stream {
        val first = streamOnce(songId, level ?: "exhigh")
        if ((first.valid() && !first.preview) || "standard" == level) return first
        // Higher qualities can be gated; try standard quality when available.
        return streamOnce(songId, "standard")
    }

    @Throws(Exception::class)
    private fun streamOnce(songId: Long, level: String): Stream {
        val body = JsonObject()
        body.addProperty("ids", "[$songId]")
        body.addProperty("level", level)
        body.addProperty("encodeType", "mp3")
        val root = weapi("song/enhance/player/url/v1", body)
        val data = arr(root, "data")
        if (data == null || data.size() == 0) return Stream("", 0L)
        val first = if (data.get(0).isJsonObject()) data.get(0).asJsonObject else null
        if (first == null) return Stream("", 0L)
        val url = str(first, "url")
        val time = if (first.has("time") && !first.get("time")!!.isJsonNull()) first.get("time")!!.asLong else 0L
        val preview = first.has("freeTrialInfo") && !first.get("freeTrialInfo").isJsonNull
        return Stream(url, time, preview)
    }

    /** Raw lyric payload: word-level `yrc` plus the plain, translated and romanised LRCs. */
    class LyricPayload(val yrc: String?, val lrc: String?, val translation: String?, val romanisation: String?)

    @Throws(Exception::class)
    fun lyrics(songId: Long): LyricPayload {
        val body = JsonObject()
        body.addProperty("id", songId)
        body.addProperty("lv", 0)
        body.addProperty("kv", 0)
        body.addProperty("tv", 0)
        body.addProperty("rv", 0)
        body.addProperty("yv", 0)
        val root = weapi("song/lyric/v1", body)
        return LyricPayload(lyricField(root, "yrc"), lyricField(root, "lrc"), lyricField(root, "tlyric"), lyricField(root, "romalrc"))
    }

    // ------------------------------------------------------------- account

    @Throws(Exception::class)
    fun account(): Profile? {
        if (!NeteaseHttp.loggedIn()) return null
        val root = weapi("w/nuser/account/get", JsonObject())
        val profile = obj(root, "profile") ?: return null
        val userId = if (profile.has("userId") && !profile.get("userId")!!.isJsonNull()) profile.get("userId")!!.asLong else 0L
        val vip = if (profile.has("vipType") && !profile.get("vipType")!!.isJsonNull()) profile.get("vipType")!!.asInt else 0
        return Profile(userId, str(profile, "nickname"), str(profile, "avatarUrl"), vip)
    }

    /** Creates the QR payload; the returned key is what the phone scans. */
    @Throws(Exception::class)
    fun qrKey(): String? {
        val body = JsonObject()
        body.addProperty("type", 1)
        val root = weapi("login/qrcode/unikey", body)
        val key = str(root, "unikey")
        return if (key.isEmpty()) null else key
    }

    fun qrContent(unikey: String): String = "https://music.163.com/login?codekey=$unikey"

    /**
     * Polls the QR state.
     *
     * @return 800 expired, 801 waiting, 802 scanned (awaiting confirmation), 803 logged in, -1 on a
     *   transport error
     */
    data class QrCheck(val code: Int, val cookie: String)

    @Throws(Exception::class)
    fun qrCheck(unikey: String): QrCheck {
        val body = JsonObject()
        body.addProperty("key", unikey)
        body.addProperty("type", 1)
        val root = weapi("login/qrcode/client/login", body, false)
        val code = if (root.has("code") && !root.get("code")!!.isJsonNull()) root.get("code")!!.asInt else -1
        // Manager commits the cookie only if this poll still belongs to the active QR flow.
        return QrCheck(code, if (code == 803) str(root, "cookie") else "")
    }

    @Throws(Exception::class)
    fun logout() {
        try {
            weapi("logout", JsonObject())
        } catch (ignored: Exception) {
        }
        NeteaseHttp.logout()
    }

    // ------------------------------------------------------------ plumbing

    @Throws(Exception::class)
    private fun weapi(path: String, body: JsonObject, capture: Boolean = true): JsonObject {
        val json = GSON.toJson(body)
        val csrf = NeteaseHttp.csrf()
        val response = NeteaseHttp.weapi("$path?csrf_token=${percent(csrf)}", json, capture)
        if (!response.ok()) {
            throw IllegalStateException("HTTP ${response.status} from $path")
        }
        if (response.body.isEmpty()) return JsonObject()
        val parsed = JsonParser.parseString(response.body)
        return if (parsed.isJsonObject()) parsed.asJsonObject else JsonObject()
    }

    private fun percent(value: String?): String = try {
        URLEncoder.encode(value ?: "", "UTF-8")
    } catch (t: Throwable) {
        ""
    }

    private fun parseSong(element: JsonElement?): Song? {
        if (element == null || !element.isJsonObject()) return null
        val song = element.asJsonObject
        return try {
            val id = song.get("id")!!.asLong
            val name = str(song, "name")
            var artist = ""
            var artists = arr(song, "ar")
            if (artists == null) artists = arr(song, "artists")
            if (artists != null) artist = joinArtists(artists)
            var album = ""
            var cover = ""
            var al = obj(song, "al")
            if (al == null) al = obj(song, "album")
            if (al != null) {
                album = str(al, "name")
                cover = str(al, "picUrl")
            }
            var duration = 0L
            if (song.has("dt") && !song.get("dt")!!.isJsonNull()) {
                duration = song.get("dt")!!.asLong
            } else if (song.has("duration") && !song.get("duration")!!.isJsonNull()) {
                duration = song.get("duration")!!.asLong
            }
            Song(id, name, artist, album, cover, duration)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Search results come back in the "album/artists/duration" shape, which carries no cover art;
     * the detail endpoint does. For the thumbnails in the result list the missing covers are filled
     * with one batched detail call instead of one call per row.
     */
    private fun fillMissingCovers(songs: MutableList<Song>) {
        val missing = ArrayList<Long>()
        for (song in songs) {
            if (song.coverUrl.isEmpty() && missing.size < 50) missing.add(song.id)
        }
        if (missing.isEmpty()) return
        try {
            val covers = HashMap<Long, String>()
            for (song in songDetails(missing)) {
                if (song.coverUrl.isNotEmpty()) covers[song.id] = song.coverUrl
            }
            for (i in songs.indices) {
                val song = songs[i]
                val cover = covers[song.id]
                if (cover != null && song.coverUrl.isEmpty()) {
                    songs[i] = Song(song.id, song.name, song.artist, song.album, cover, song.durationMs)
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun parsePlaylist(element: JsonElement?): Playlist? {
        if (element == null || !element.isJsonObject()) return null
        val playlist = element.asJsonObject
        return try {
            val id = playlist.get("id")!!.asLong
            val name = str(playlist, "name")
            var count = 0
            if (playlist.has("trackCount") && !playlist.get("trackCount")!!.isJsonNull()) {
                count = playlist.get("trackCount")!!.asInt
            }
            var cover = str(playlist, "picUrl")
            if (cover.isEmpty()) cover = str(playlist, "coverImgUrl")
            Playlist(id, name, count, cover)
        } catch (t: Throwable) {
            null
        }
    }

    private fun joinArtists(artists: JsonArray): String {
        val out = StringBuilder()
        for (element in artists) {
            if (!element.isJsonObject()) continue
            val name = str(element.asJsonObject, "name")
            if (name.isEmpty()) continue
            if (out.isNotEmpty()) out.append(" / ")
            out.append(name)
        }
        return out.toString()
    }

    private fun lyricField(root: JsonObject, key: String): String? {
        val node = obj(root, key) ?: return null
        return emptyToNull(str(node, "lyric"))
    }

    private fun emptyToNull(value: String?): String? = if (value.isNullOrEmpty()) null else value

    private fun str(obj: JsonObject?, key: String): String {
        if (obj == null || !obj.has(key)) return ""
        val value = obj.get(key) ?: return ""
        if (value.isJsonNull()) return ""
        return try {
            value.asString
        } catch (t: Throwable) {
            ""
        }
    }

    private fun obj(parent: JsonObject?, key: String): JsonObject? {
        if (parent == null || !parent.has(key)) return null
        val value = parent.get(key) ?: return null
        return if (value.isJsonObject()) value.asJsonObject else null
    }

    private fun arr(parent: JsonObject?, key: String): JsonArray? {
        if (parent == null || !parent.has(key)) return null
        val value = parent.get(key) ?: return null
        return if (value.isJsonArray()) value.asJsonArray else null
    }
}
