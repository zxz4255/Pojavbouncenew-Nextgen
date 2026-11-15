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
    @Volatile var token: String = ""
    @Volatile var userId: Long = 0L
    @Volatile var conceptSession: Boolean = false

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
    fun stream(song: Song): NeteaseClient.Stream {
        require(song.providerHash.matches(Regex("[a-fA-F0-9]{32}"))) { "酷狗歌曲缺少有效 hash" }
        val uid = userId
        val sessionToken = token
        val hasAnyCredential = uid > 0L || sessionToken.isNotBlank()
        if (hasAnyCredential || conceptSession) {
            if (uid <= 0L || sessionToken.isBlank()) {
                throw IllegalStateException("酷狗登录态不完整，请重新扫码登录；不会回退到匿名取流")
            }
            return if (conceptSession) conceptStream(song, uid, sessionToken) else standardStream(song, uid, sessionToken)
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
                "IsFreePart" to "0", "ppage_id" to "463467626,350369493,788954147", "cdnBackup" to "1", "clientver" to "11430"), "trackercdn.kugou.com")
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
    // Adapted from MuoniumPlayer KuGouAuth / KuGouProfile (Apache-2.0).
    // Concept credentials are never used with the standard identity. No secrets are logged.
    private fun conceptRequest(path: String, args: Map<String, String>, router: String? = null): JsonObject {
        val salt = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"
        val q = linkedMapOf("dfid" to "-", "mid" to deviceMid, "uuid" to "-", "appid" to "3116",
            "clientver" to "11440", "clienttime" to (System.currentTimeMillis() / 1000).toString())
        q.putAll(args)
        q["signature"] = md5(salt + q.toSortedMap().entries.joinToString("") { "${it.key}=${it.value}" } + salt)
        val address = "https://gateway.kugou.com" + path + "?" + q.entries.joinToString("&") { encoded(it.key) + "=" + encoded(it.value) }
        val c = URL(address).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = 12000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi")
            c.setRequestProperty("mid", deviceMid); c.setRequestProperty("dfid", "-")
            c.setRequestProperty("kg-rc", "1"); c.setRequestProperty("kg-thash", "5d816a0")
            c.setRequestProperty("kg-rec", "1"); c.setRequestProperty("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F")
            c.setRequestProperty("clienttime", q.getValue("clienttime"))
            if (router != null) c.setRequestProperty("x-router", router)
            if (c.responseCode !in 200..299) throw IllegalStateException("概念版请求失败：HTTP ${c.responseCode}")
            return JsonParser.parseString(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }).asJsonObject
        } finally { c.disconnect() }
    }
    private fun conceptStream(song: Song, uid: Long, sessionToken: String): NeteaseClient.Stream {
        var authFailure = "概念版单曲授权未返回播放地址"
        try {
            return conceptAuthorizedStream(song, uid, sessionToken)
        } catch (e: Exception) {
            authFailure = e.message ?: e.javaClass.simpleName
        }

        // Fallback remains signed in the concept-app identity (appid, salt, key, page/pid and
        // router all stay paired); it never calls the anonymous standard endpoint.
        try {
            return conceptUrlStream(song, uid, sessionToken)
        } catch (e: Exception) {
            val urlFailure = e.message ?: e.javaClass.simpleName
            throw IllegalStateException("酷狗概念版授权取流失败：单曲授权链：$authFailure；概念版 /v5/url：$urlFailure", e)
        }
    }

    private fun conceptAuthorizedStream(song: Song, uid: Long, sessionToken: String): NeteaseClient.Stream {
        val authBase = mapOf("userid" to uid.toString(), "token" to sessionToken, "module_id" to "51")
        val router = "trackercdngz.kugou.com"
        val verify = conceptRequest("/v1/user_verify", authBase, router)
        val verified = obj(verify, "data") ?: throw apiFailure(verify, "酷狗概念版用户验证", "没有验证数据")
        if (long(verified, "valid") != 1L) throw apiFailure(verify, "酷狗概念版用户验证", "账号验证未通过")
        val userAuth = str(verified, "auth")
        if (userAuth.isBlank()) throw IllegalStateException("酷狗概念版用户验证未返回授权凭据")

        val hash = song.providerHash.lowercase()
        val audioId = albumAudioId(song)
        val auth = conceptRequest("/v1/authorization", authBase + mapOf("clientver" to "11561",
            "authorization" to userAuth, "album_audio_id" to audioId, "hash" to hash), router)
        val data = obj(auth, "data") ?: throw apiFailure(auth, "酷狗概念版单曲授权", "没有授权数据")
        val songAuth = str(data, "auth")
        val openTime = long(data, "open_time")
        if (songAuth.isEmpty() || openTime <= 0L) throw apiFailure(auth, "酷狗概念版单曲授权", "没有取得有效的单曲授权")

        val key = md5(hash + "185672dd44712f60bb1736df5a377e82" + "3116" + deviceMid + uid)
        val root = conceptRequest("/tracker/v5/url", authBase + mapOf("clientver" to "11561", "album_id" to song.providerAlbumId.ifEmpty { "0" },
            "area_code" to "1", "module" to "", "hash" to hash, "need_m" to "0", "ssa_flag" to "is_fromtrack",
            "version" to "11430", "open_time" to openTime.toString(), "ptype" to "0", "need_ogg" to "1",
            "page_id" to "967177915", "auth" to songAuth, "mtype" to "0", "quality" to "128",
            "album_audio_id" to audioId, "behavior" to "play", "pid" to "411", "cmd" to "26",
            "ppage_id" to "356753938,823673182,967485191", "pidversion" to "3001", "cdnBackup" to "1", "key" to key), router)
        return authorizedStream(root, hash, "酷狗概念版单曲授权取流")
    }

    private fun conceptUrlStream(song: Song, uid: Long, sessionToken: String): NeteaseClient.Stream {
        val hash = song.providerHash.lowercase()
        val key = md5(hash + "185672dd44712f60bb1736df5a377e82" + "3116" + deviceMid + uid)
        val router = "trackercdngz.kugou.com"
        val identity = mapOf("userid" to uid.toString(), "token" to sessionToken, "module_id" to "51")
        val root = conceptRequest("/v5/url", identity + mapOf(
            "clientver" to "11561", "album_id" to song.providerAlbumId.ifEmpty { "0" }, "area_code" to "1",
            "hash" to hash, "key" to key, "ssa_flag" to "is_fromtrack", "version" to "11430",
            "page_id" to "967177915", "quality" to "128", "album_audio_id" to albumAudioId(song),
            "behavior" to "play", "pid" to "411", "cmd" to "26", "pidversion" to "3001",
            "IsFreePart" to "0", "ppage_id" to "356753938,823673182,967485191", "cdnBackup" to "1"), router)
        return authorizedStream(root, hash, "酷狗概念版 /v5/url 授权取流")
    }

    fun qrKey(concept: Boolean = false): String {
        val root = request("https://login-user.kugou.com", "/v2/qrcode", mapOf("appid" to "1001", "type" to "1", "plat" to "4", "qrcode_txt" to "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=${if (concept) "3116" else "1005"}&", "srcappid" to "2919"), WEB_SALT)
        check(root, "酷狗二维码")
        return str(obj(root, "data") ?: JsonObject(), "qrcode").ifEmpty { throw IllegalStateException("酷狗未返回二维码") }
    }
    fun qrContent(key: String, concept: Boolean = false): String =
        "https://h5.kugou.com/apps/loginQRCode/html/index.html?" + (if (concept) "appid=3116&" else "") + "qrcode=" + encoded(key)
    data class QrResult(val status: Int, val token: String, val userId: Long, val nickname: String)
    fun qrCheck(key: String, concept: Boolean = false): QrResult {
        val root = request("https://login-user.kugou.com", "/v2/get_userinfo_qrcode", mapOf("plat" to "4", "srcappid" to "2919", "appid" to (if (concept) "3116" else "1005"), "qrcode" to key), WEB_SALT)
        check(root, "酷狗扫码")
        val data = obj(root, "data") ?: throw IllegalStateException("酷狗未返回扫码状态")
        return QrResult(long(data, "status").toInt(), str(data, "token"), long(data, "userid"), str(data, "nickname"))
    }
}
