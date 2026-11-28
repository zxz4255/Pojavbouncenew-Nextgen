package net.ccbluex.liquidbounce.features.module.modules.misc.music

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64

/** KuGou public endpoints. Responses and entitlement decisions remain controlled by KuGou. */
internal object KugouClient {
    private const val ANDROID_SALT = "OIlwieks28dk2k092lksi2UIkp"
    private const val WEB_SALT = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"
    @Volatile var deviceMid: String = java.util.UUID.randomUUID().toString().replace("-", "")
    private const val UA = "Android-AndroidPhone-11430-0-0-NetMusic-wifi"
    @Volatile var deviceDfid: String = "-"
    private val playback = KugouPlayback()
    @Volatile var token: String = ""
    @Volatile var userId: Long = 0L
    @Volatile var conceptSession: Boolean = false
    @Volatile private var accountEpoch: Long = 0L

    private fun str(o: JsonObject, key: String): String = runCatching { o.get(key)?.asString ?: "" }.getOrDefault("")
    private fun long(o: JsonObject, key: String): Long = str(o, key).toLongOrNull() ?: 0L
    private fun obj(o: JsonObject, key: String): JsonObject? = o.get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun arr(o: JsonObject, key: String): JsonArray? = o.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
    private fun md5(s: String): String = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun encoded(s: String): String = URLEncoder.encode(s, Charsets.UTF_8)

    private fun request(base: String, path: String, args: Map<String, String>, salt: String? = ANDROID_SALT,
                        router: String? = null): JsonObject {
        val q = linkedMapOf("appid" to "1005", "clientver" to "20489", "dfid" to "-", "mid" to deviceMid,
            "uuid" to "-", "clienttime" to (System.currentTimeMillis() / 1000).toString())
        q.putAll(args.filterValues { it.isNotEmpty() })
        if (salt != null) q["signature"] = md5(salt + q.toSortedMap().entries.joinToString("") { "${it.key}=${it.value}" } + salt)
        val url = base + path + "?" + q.entries.joinToString("&") { encoded(it.key) + "=" + encoded(it.value) }
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = 12000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Referer", "https://www.kugou.com/")
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("dfid", "-"); c.setRequestProperty("mid", deviceMid)
            if (router != null) c.setRequestProperty("x-router", router)
            if (c.responseCode !in 200..299) throw IllegalStateException("酷狗 HTTP ${c.responseCode}")
            val text = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JsonParser.parseString(text).asJsonObject
        } finally { c.disconnect() }
    }
    private fun gateway(path: String, params: Map<String, String>, router: String? = null): JsonObject =
        request("https://gateway.kugou.com", path, params, router = router)
    private fun check(root: JsonObject, action: String) {
        val code = long(root, "error_code")
        if (code != 0L || long(root, "status") == 0L) {
            throw IllegalStateException("$action：${str(root, "error_msg").ifEmpty { str(root, "errmsg") }.ifEmpty { "酷狗错误 $code" }}")
        }
    }
    private fun image(url: String): String = url.replace("http://", "https://")
    private fun validId(value: String): String = value.toLongOrNull()?.takeIf { it > 0L }?.toString() ?: ""
    private fun albumAudioId(song: Song): String = validId(song.providerAlbumAudioId).ifEmpty { "0" }

    private fun responseParts(root: JsonObject): List<JsonObject> = listOfNotNull(root, obj(root, "data"))

    /** KuGou may return each URL field either as a string or as an array of mirrors. */
    private fun stringValues(o: JsonObject, key: String): List<String> {
        val value = o.get(key) ?: return emptyList()
        return when {
            value.isJsonArray -> value.asJsonArray.mapNotNull { element ->
                if (element.isJsonNull) null else runCatching { element.asString }.getOrNull()
            }
            value.isJsonPrimitive -> listOfNotNull(runCatching { value.asString }.getOrNull())
            else -> emptyList()
        }
    }

    private fun streamUrls(root: JsonObject): List<String> = responseParts(root)
        .flatMap { part -> listOf("url", "backupdownurl", "backup_url").flatMap { stringValues(part, it) } }
        .map { it.trim() }
        .filter { it.startsWith("https://", ignoreCase = true) }

    private fun requireMatchingHash(root: JsonObject, expected: String, action: String) {
        val hashes = responseParts(root).flatMap { part ->
            listOf("hash", "filehash", "file_hash").map { str(part, it) }.filter { it.isNotBlank() }
        }
        if (hashes.any { !it.equals(expected, ignoreCase = true) }) {
            throw IllegalStateException("$action：酷狗返回的文件 hash 与所请求歌曲不匹配")
        }
    }

    private fun apiFailure(root: JsonObject, action: String, fallback: String): IllegalStateException {
        val reasons = responseParts(root).flatMap { part ->
            listOf("error_msg", "errmsg", "error", "message").map { str(part, it) }
        }.firstOrNull { it.isNotBlank() }
        return IllegalStateException("$action：${reasons ?: fallback}")
    }

    private fun responseDurationMs(root: JsonObject): Long {
        val secondsOrMillis = responseParts(root).asSequence()
            .mapNotNull { part -> long(part, "timeLength").takeIf { it > 0L } ?: long(part, "timelength").takeIf { it > 0L } }
            .firstOrNull() ?: 0L
        // Keep the API's existing timeLength conversion; its unit still needs live verification.
        return secondsOrMillis * 1000L
    }

    private fun authorizedStream(root: JsonObject, hash: String, action: String): NeteaseClient.Stream {
        if (long(root, "status") != 1L) throw apiFailure(root, action, "酷狗未提供授权播放地址")
        requireMatchingHash(root, hash, action)
        val url = streamUrls(root).firstOrNull()
            ?: throw IllegalStateException("$action：酷狗未返回安全的播放地址")
        return NeteaseClient.Stream(url, responseDurationMs(root))
    }

    private fun searchSong(o: JsonObject): Song? {
        val id = long(o, "ID").takeIf { it > 0 } ?: long(o, "MixSongID")
        val audioId = validId(str(o, "MixSongID"))
        val hash = str(o, "FileHash")
        if (id <= 0 || !hash.matches(Regex("[a-fA-F0-9]{32}"))) return null
        return Song(id, str(o, "SongName"), str(o, "SingerName"), str(o, "AlbumName"), image(str(o, "Image")),
            long(o, "Duration") * 1000L, MusicProvider.KUGOU, hash, str(o, "AlbumID"), audioId)
    }
    private fun listSong(o: JsonObject): Song? {
        val mixSongId = validId(str(o, "mixsongid"))
        val id = long(o, "mixsongid").takeIf { it > 0 } ?: long(o, "audio_id")
        val audioId = validId(str(o, "album_audio_id")).ifEmpty { mixSongId }
        val hash = str(o, "hash")
        if (id <= 0 || !hash.matches(Regex("[a-fA-F0-9]{32}"))) return null
        val singer = arr(o, "singerinfo")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject?.let { str(it, "name") } ?: ""
        val title = str(o, "name").removePrefix(if (singer.isEmpty()) "" else "$singer - ")
        return Song(id, title, singer, obj(o, "albuminfo")?.let { str(it, "name") } ?: "", image(str(o, "cover")),
            long(o, "timelen"), MusicProvider.KUGOU, hash, str(o, "album_id"), audioId)
    }
    fun search(query: String, limit: Int): List<Song> {
        val root = gateway("/v2/search/song", mapOf("keyword" to query, "page" to "1", "pagesize" to limit.toString(),
            "platform" to "AndroidFilter", "iscorrection" to "1", "privilegefilter" to "0", "area_code" to "1", "dopicfull" to "1"), "complexsearch.kugou.com")
        check(root, "歌曲搜索")
        return arr(obj(root, "data") ?: return emptyList(), "lists")?.mapNotNull { if (it.isJsonObject) searchSong(it.asJsonObject) else null } ?: emptyList()
    }
    fun searchPlaylists(query: String, limit: Int): List<Playlist> {
        val root = gateway("/v1/search/special", mapOf("keyword" to query, "page" to "1", "pagesize" to limit.toString(),
            "platform" to "AndroidFilter", "iscorrection" to "1", "albumhide" to "0", "nocollect" to "0"), "complexsearch.kugou.com")
        check(root, "歌单搜索")
        return arr(obj(root, "data") ?: return emptyList(), "lists")?.mapNotNull {
            if (!it.isJsonObject) return@mapNotNull null
            val o = it.asJsonObject; val id = long(o, "specialid"); val key = str(o, "gid")
            if (id <= 0 || key.isEmpty()) null else Playlist(id, str(o, "specialname"), long(o, "song_count").toInt(), image(str(o, "img")), MusicProvider.KUGOU, key)
        } ?: emptyList()
    }
    fun playlistTracks(key: String, limit: Int): List<Song> {
        require(key.matches(Regex("collection_[0-9_]+"))) { "酷狗歌单标识无效" }
        val out = ArrayList<Song>(); var offset = 0
        while (out.size < limit) {
            val pageSize = minOf(100, limit - out.size)
            val root = gateway("/pubsongs/v2/get_other_list_file_nofilt", mapOf("area_code" to "1", "begin_idx" to offset.toString(), "plat" to "1", "type" to "1", "mode" to "1", "personal_switch" to "1", "extend_fields" to "abtags,hot_cmt,popularization", "pagesize" to pageSize.toString(), "global_collection_id" to key))
            if (long(root, "error_code") != 0L) throw IllegalStateException("酷狗歌单加载失败：${str(root, "errmsg")}")
            val songs = arr(obj(root, "data") ?: break, "songs") ?: break
            songs.forEach { if (it.isJsonObject) listSong(it.asJsonObject)?.let(out::add) }
            offset += songs.size(); if (songs.size() < pageSize) break
        }
        return out
    }
    private class AccountSnapshot(val uid: Long, val token: String, val concept: Boolean, val mid: String, val dfid: String, val epoch: Long)

    /** In-flight import/decrypt must not publish after logout, device or account switch. */
    internal fun accountGuard(): () -> Boolean {
        val before = synchronized(this) { AccountSnapshot(userId, token, conceptSession, deviceMid, deviceDfid, accountEpoch) }
        return { synchronized(this) {
            userId == before.uid && token == before.token && conceptSession == before.concept &&
                deviceMid == before.mid && deviceDfid == before.dfid && accountEpoch == before.epoch
        } }
    }

    /** Called only after a v5 header's exact hash is absent from explicit local keys. */
    internal fun onlineKey(hash: String, cancel: java.util.function.BooleanSupplier): String? {
        val snapshot = synchronized(this) { AccountSnapshot(userId, token, conceptSession, deviceMid, deviceDfid, accountEpoch) }
        if (snapshot.uid <= 0L && snapshot.token.isBlank() && !snapshot.concept) return null
        if (snapshot.uid <= 0L || snapshot.token.isBlank())
            throw KugouPlayback.Failure(KugouPlayback.FailureKind.LOGIN, "在线取钥", detail = "酷狗登录态不完整，请重新扫码")
        val session = KugouPlayback.Session(snapshot.uid, snapshot.token, snapshot.mid, snapshot.dfid)
        return KugouOnlineKeys().fetch(hash, session, snapshot.concept, cancel) {
            synchronized(this) {
                userId == snapshot.uid && token == snapshot.token && conceptSession == snapshot.concept &&
                    deviceMid == snapshot.mid && deviceDfid == snapshot.dfid && accountEpoch == snapshot.epoch
            }
        }
    }

    @Synchronized fun cacheName(song: Song, level: String): String {
        val mode = if (conceptSession) "concept" else "standard"
        val quality = level.takeIf { it in listOf("standard", "higher", "exhigh", "lossless") } ?: "exhigh"
        val hash = song.providerHash.lowercase().takeIf { it.matches(Regex("[a-f0-9]{32}")) } ?: "unknown"
        return "kugou-v2-$mode-${userId.coerceAtLeast(0)}-$quality-${song.id}-$hash.audio"
    }

    fun stream(song: Song, level: String = "exhigh"): NeteaseClient.Stream {
        require(song.providerHash.matches(Regex("[a-fA-F0-9]{32}"))) { "酷狗歌曲缺少有效 hash" }
        val snapshot = synchronized(this) { AccountSnapshot(userId, token, conceptSession, deviceMid, deviceDfid, accountEpoch) }
        val uid = snapshot.uid
        val sessionToken = snapshot.token
        val hasAnyCredential = uid > 0L || sessionToken.isNotBlank()
        if (hasAnyCredential || snapshot.concept) {
            if (uid <= 0L || sessionToken.isBlank()) {
                throw IllegalStateException("酷狗登录态不完整，请重新扫码登录；不会回退到匿名取流")
            }
            return if (snapshot.concept) conceptStream(song, level, uid, sessionToken, snapshot.mid, snapshot.dfid) else standardStream(song, uid, sessionToken)
        }

        // Without a signed-in account, the public endpoint can return only what KuGou itself permits.
        // A failed authenticated request never reaches this branch and therefore cannot downgrade to
        // anonymous playback or evade an account/region/copyright decision.
        val root = try {
            request("https://m.kugou.com", "/api/v1/song/get_song_info",
                mapOf("hash" to song.providerHash, "cmd" to "playInfo"), salt = null)
        } catch (e: Exception) {
            throw IllegalStateException("酷狗公开取流请求失败：${e.message ?: e.javaClass.simpleName}", e)
        }
        if (long(root, "status") != 1L) throw apiFailure(root, "酷狗公开取流", "可能需要会员、验证或地区权限")
        requireMatchingHash(root, song.providerHash, "酷狗公开取流")
        val url = streamUrls(root).firstOrNull() ?: throw IllegalStateException("酷狗未提供安全播放地址")
        return NeteaseClient.Stream(url, responseDurationMs(root))
    }

    private fun standardStream(song: Song, uid: Long, sessionToken: String): NeteaseClient.Stream {
        val hash = song.providerHash.lowercase()
        val key = md5(hash + "57ae12eb6890223e355ccfcb74edf70d" + "1005" + deviceMid + uid)
        val root = try {
            gateway("/v5/url", mapOf("hash" to hash, "key" to key, "userid" to uid.toString(), "token" to sessionToken,
                "album_id" to song.providerAlbumId.ifEmpty { "0" }, "album_audio_id" to albumAudioId(song),
                "area_code" to "1", "ssa_flag" to "is_fromtrack", "version" to "11430", "page_id" to "151369488",
                "quality" to "128", "behavior" to "play", "pid" to "2", "cmd" to "26", "pidversion" to "3001",
                "IsFreePart" to "0", "need_ogg" to "1", "ppage_id" to "463467626,350369493,788954147", "cdnBackup" to "1", "clientver" to "11430"), "trackercdn.kugou.com")
        } catch (e: Exception) {
            throw IllegalStateException("酷狗标准版授权取流请求失败：${e.message ?: e.javaClass.simpleName}", e)
        }
        return authorizedStream(root, hash, "酷狗标准版授权取流")
    }

    fun lyrics(song: Song): String {
        val root = request("https://lyrics.kugou.com", "/v1/search", mapOf("hash" to song.providerHash, "album_audio_id" to albumAudioId(song), "duration" to song.durationMs.toString(), "lrctxt" to "1", "man" to "no"))
        val candidate = arr(root, "candidates")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return ""
        val lyric = request("https://lyrics.kugou.com", "/download", mapOf("ver" to "1", "client" to "android", "id" to str(candidate, "id"), "accesskey" to str(candidate, "accesskey"), "fmt" to "lrc", "charset" to "utf8"))
        return String(Base64.getDecoder().decode(str(lyric, "content")), Charsets.UTF_8)
    }
    // Concept playback is a distinct protocol; never substitute a standard/anonymous identity.
    private fun conceptStream(song: Song, level: String, uid: Long, sessionToken: String, mid: String, dfid: String): NeteaseClient.Stream {
        val result = playback.resolve(song, level, KugouPlayback.Session(uid, sessionToken, mid, dfid))
        return NeteaseClient.Stream(result.url, result.durationMs)
    }

    private data class QrIdentity(val concept: Boolean, val mid: String, val guid: String)
    private val qrIdentities = java.util.concurrent.ConcurrentHashMap<String, QrIdentity>()

    @Synchronized fun acceptLogin(result: QrResult, concept: Boolean) {
        require(result.userId > 0 && result.token.isNotBlank()) { "酷狗未返回有效登录凭据" }
        accountEpoch++
        token = result.token; userId = result.userId; conceptSession = concept
        deviceMid = result.mid; deviceDfid = "-"
        qrIdentities.clear()
    }

    @Synchronized fun clearLogin() {
        accountEpoch++
        token = ""; userId = 0L; conceptSession = false
        qrIdentities.clear()
    }

    fun qrKey(concept: Boolean = false): String {
        val guid = java.util.UUID.randomUUID().toString()
        // One decimal device id belongs to this QR flow; publish it only after successful login.
        val mid = java.math.BigInteger(md5(guid), 16).toString(10)
        val identity = QrIdentity(concept, mid, guid)
        val request = KugouPlayback.loginRequest("/v2/qrcode", concept, identity.mid, identity.guid)
        val root = KugouPlayback.HttpTransport.exchange(request)
        check(root, "酷狗二维码")
        val key = str(obj(root, "data") ?: JsonObject(), "qrcode").ifEmpty { throw IllegalStateException("酷狗未返回二维码") }
        if (qrIdentities.size >= 8) qrIdentities.clear()
        qrIdentities[key] = identity
        return key
    }
    fun qrContent(key: String, concept: Boolean = false): String =
        "https://h5.kugou.com/apps/loginQRCode/html/index.html?" + (if (concept) "appid=3116&" else "") + "qrcode=" + encoded(key)
    data class QrResult(val status: Int, val token: String, val userId: Long, val nickname: String, val mid: String)
    fun qrCheck(key: String, concept: Boolean = false): QrResult {
        val identity = qrIdentities[key] ?: throw IllegalStateException("酷狗二维码会话已失效，请刷新")
        check(identity.concept == concept) { "酷狗二维码客户端身份不匹配，请刷新" }
        val request = KugouPlayback.loginRequest("/v2/get_userinfo_qrcode", concept, identity.mid, identity.guid, key)
        val root = KugouPlayback.HttpTransport.exchange(request)
        check(root, "酷狗扫码")
        val data = obj(root, "data") ?: throw IllegalStateException("酷狗未返回扫码状态")
        return QrResult(long(data, "status").toInt(), str(data, "token"), long(data, "userid"), str(data, "nickname"), identity.mid)
    }
}
