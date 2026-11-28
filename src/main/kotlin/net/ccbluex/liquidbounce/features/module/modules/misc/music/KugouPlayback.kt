/*
 * This file is part of LiquidBounce. SPDX-License-Identifier: GPL-3.0-or-later
 * Protocol reference: lxserver 6ba3d7d (Apache-2.0), see references/music078/README.md.
 * Account authorization is always decided by KuGou; no VIP claims or anonymous bypass.
 */
package net.ccbluex.liquidbounce.features.module.modules.misc.music

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest

/** Separable wire protocol so requests, routing, signatures and denials can be tested offline. */
internal class KugouPlayback(private val transport: Transport = HttpTransport, private val clock: () -> Long = System::currentTimeMillis) {
    class Session(val userId: Long, val token: String, val mid: String, val dfid: String = "-") {
        init {
            require(userId > 0 && token.isNotBlank() && token.length <= 4096 && token.none { it == '\r' || it == '\n' || it == ';' }) { "酷狗概念版登录态不完整，请重新扫码" }
            require(mid.matches(Regex("[a-fA-F0-9]{16,64}")) && dfid.matches(Regex("[a-zA-Z0-9_-]{1,128}"))) { "酷狗设备标识无效，请重新扫码" }
        }
        override fun toString() = "KuGouSession(redacted)"
    }
    class Request(val base: String, val path: String, val params: Map<String, String>, val headers: Map<String, String>, val body: String? = null) {
        override fun toString() = "KuGouRequest($path)"
    }
    fun interface Transport { fun exchange(request: Request): JsonObject }
    enum class FailureKind { LOGIN, API, PROTOCOL, NETWORK, FORMAT }
    class Failure(val kind: FailureKind, val stage: String, val code: Long = 0, detail: String) : IOException("$stage：$detail")
    data class Asset(val hash: String, val quality: String, val audioId: String)
    data class Resolved(val url: String, val durationMs: Long, val quality: String)

    private fun str(root: JsonObject, key: String) = runCatching { root.get(key)?.asString.orEmpty() }.getOrDefault("")
    private fun number(root: JsonObject, key: String) = str(root, key).toLongOrNull() ?: 0L
    private fun obj(root: JsonObject, key: String) = root.get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun validHash(value: String) = value.matches(Regex("[a-fA-F0-9]{32}"))

    private fun call(session: Session, path: String, args: Map<String, String> = emptyMap(), router: String? = null, body: JsonObject? = null): JsonObject {
        val time = (clock() / 1000).toString()
        val params = linkedMapOf("dfid" to session.dfid, "mid" to session.mid, "uuid" to "-", "appid" to "3116", "clientver" to "11440", "clienttime" to time)
        params.putAll(args)
        params["userid"] = session.userId.toString(); params["token"] = session.token
        val content = body?.toString()
        params["signature"] = signature(params, content.orEmpty(), LITE_SALT)
        val headers = linkedMapOf("User-Agent" to LITE_UA, "dfid" to session.dfid, "mid" to session.mid, "clienttime" to time,
            "kg-rc" to "1", "kg-thash" to "5d816a0", "kg-rec" to "1", "kg-rf" to "B9EDA08A64250DEFFBCADDEE00F8F25F")
        if (router != null) headers["x-router"] = router
        // Values here are the SAME snapshot used for query signing, never mutable global credentials.
        headers["Cookie"] = "token=${session.token}; userid=${session.userId}; mid=${session.mid}; dfid=${session.dfid}"
        return try { transport.exchange(Request(GATEWAY, path, params, headers, content)) }
        catch (e: Failure) { throw e }
        catch (_: IOException) { throw Failure(FailureKind.NETWORK, path, detail = "酷狗网络请求失败，请检查网络后重试") }
        catch (_: Exception) { throw Failure(FailureKind.PROTOCOL, path, detail = "酷狗响应格式无效") }
    }

    private fun requireSuccess(root: JsonObject, stage: String, session: Session) {
        val code = number(root, "error_code").takeIf { it != 0L } ?: number(root, "errcode")
        if (code == 35005L) throw Failure(FailureKind.LOGIN, stage, code, "概念版登录态失效或客户端身份不匹配，请退出酷狗登录后重新选择概念版扫码")
        if (code != 0L || number(root, "status") != 1L) {
            val raw = listOf("error_msg", "errmsg", "error", "msg", "message").map { str(root, it) }.firstOrNull { it.isNotBlank() }.orEmpty()
            val safe = raw.replace(session.token, "[凭据]").replace(session.mid, "[设备]")
                .replace(Regex("https?://\\S+"), "[地址]").replace(Regex("[\\r\\n\\t]"), " ").replace(Regex("[a-zA-Z0-9+/=_-]{20,}"), "[已隐藏]").take(160)
            val detail = if (code == 35002L) "接口参数或客户端身份被拒绝，不代表账号没有 VIP"
                else safe.ifBlank { "平台拒绝请求（status=${str(root, "status").ifBlank { "缺失" }}，code=$code）" }
            throw Failure(FailureKind.API, stage, code, "$detail（code=$code）")
        }
    }

    private fun userAuth(session: Session): String {
        val root = call(session, "/v1/user_verify", mapOf("module_id" to "51"), TRACKER_ROUTER)
        requireSuccess(root, "酷狗概念版用户验证", session)
        val data = obj(root, "data") ?: throw Failure(FailureKind.PROTOCOL, "用户验证", detail = "缺少授权数据")
        // Some successful responses have auth but NO `valid` field. Absence must not become false.
        if (data.has("valid") && str(data, "valid") !in listOf("1", "true")) throw Failure(FailureKind.LOGIN, "用户验证", detail = "平台未通过账号验证，请重新扫码")
        return str(data, "auth").takeIf { it.isNotBlank() } ?: throw Failure(FailureKind.PROTOCOL, "用户验证", detail = "未返回用户授权串")
    }

    private fun assets(session: Session, song: Song): List<Asset> {
        val body = JsonObject().apply {
            addProperty("appid", 3116); addProperty("area_code", 1); addProperty("behavior", "play"); addProperty("clientver", 11440)
            addProperty("need_hash_offset", 1); addProperty("relate", 1); addProperty("support_verify", 1)
            add("resource", com.google.gson.JsonArray().apply { add(JsonObject().apply {
                addProperty("type", "audio"); addProperty("page_id", 0); addProperty("hash", song.providerHash.lowercase())
                addProperty("album_id", song.providerAlbumId.toLongOrNull()?.takeIf { it > 0 } ?: 0L)
            }) })
            add("qualities", com.google.gson.JsonArray().apply { listOf("128", "320", "flac").forEach { add(it) } })
        }
        val root = call(session, "/v2/get_res_privilege/lite", router = "media.store.kugou.com", body = body)
        requireSuccess(root, "酷狗概念版音质信息", session)
        val row = root.get("data")?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw Failure(FailureKind.PROTOCOL, "音质信息", detail = "缺少歌曲资源信息")
        val audioId = str(row, "album_audio_id").toLongOrNull()?.takeIf { it > 0 }?.toString()
            ?: song.providerAlbumAudioId.toLongOrNull()?.takeIf { it > 0 }?.toString() ?: "0"
        // privilege/pay_type describe catalogue pricing, NOT whether this logged-in account may play.
        return row.get("relate_goods")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { item ->
            if (!item.isJsonObject) return@mapNotNull null
            val value = item.asJsonObject; val hash = str(value, "hash"); val quality = str(value, "quality")
            if (quality in listOf("128", "320", "flac") && validHash(hash)) Asset(hash.lowercase(), quality, audioId) else null
        }?.distinctBy { it.quality }?.takeIf { it.isNotEmpty() }
            ?: throw Failure(FailureKind.PROTOCOL, "音质信息", detail = "平台没有提供可用音质")
    }

    fun resolve(song: Song, level: String, session: Session): Resolved {
        require(validHash(song.providerHash)) { "酷狗歌曲缺少有效 hash" }
        val userAuth = userAuth(session) // invalid credentials terminate; never silently try anonymous.
        val available = assets(session, song)
        val qualities = when (level) { "lossless" -> listOf("flac", "320", "128"); "standard" -> listOf("128"); else -> listOf("320", "128") }
        var failure: Failure? = null
        for (quality in qualities) {
            val asset = available.firstOrNull { it.quality == quality } ?: continue
            try { return resolveAsset(song, asset, session, userAuth) }
            catch (e: Failure) {
                if (e.kind == FailureKind.LOGIN || e.kind == FailureKind.NETWORK) throw e
                failure = e
            }
        }
        throw failure ?: Failure(FailureKind.PROTOCOL, "酷狗概念版取流", detail = "歌曲未提供所选音质或可用的较低音质")
    }

    private fun resolveAsset(song: Song, asset: Asset, session: Session, userAuth: String): Resolved {
        val root = call(session, "/v1/authorization", mapOf("authorization" to userAuth, "module_id" to "51", "clientver" to "11561", "album_audio_id" to asset.audioId, "hash" to asset.hash), TRACKER_ROUTER)
        requireSuccess(root, "酷狗概念版单曲授权", session)
        val data = obj(root, "data") ?: throw Failure(FailureKind.PROTOCOL, "单曲授权", detail = "缺少授权数据")
        val auth = str(data, "auth"); val openTime = number(data, "open_time")
        if (auth.isBlank() || openTime <= 0) throw Failure(FailureKind.PROTOCOL, "单曲授权", detail = "缺少歌曲授权串或 open_time")
        val params = mapOf("album_id" to (song.providerAlbumId.toLongOrNull()?.takeIf { it > 0 } ?: 0L).toString(),
            "area_code" to "1", "module" to "", "hash" to asset.hash, "need_m" to "0", "ssa_flag" to "is_fromtrack", "version" to "11430",
            // Keep file identity stable: do not request an alternate OGG rendition.
            "open_time" to openTime.toString(), "ptype" to "0", "need_ogg" to "0", "page_id" to "967177915", "auth" to auth, "mtype" to "0", "quality" to asset.quality,
            "album_audio_id" to asset.audioId, "behavior" to "play", "pid" to "411", "module_id" to "51", "cmd" to "26", "ppage_id" to "356753938,823673182,967485191",
            "clientver" to "11561", "pidversion" to "3001", "cdnBackup" to "1", "key" to md5(asset.hash + KEY_SALT + "3116" + session.mid + session.userId))
        // Deliberately NO x-router here: the gateway owns /tracker/v5/url, not the auth router.
        val stream = call(session, "/tracker/v5/url", params)
        requireSuccess(stream, "酷狗概念版播放地址", session)
        return parseStream(stream, asset.hash, asset.quality)
    }

    companion object {
        const val LITE_SALT = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"
        const val LITE_UA = "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi"
        private const val KEY_SALT = "185672dd44712f60bb1736df5a377e82"
        private const val TRACKER_ROUTER = "trackercdngz.kugou.com"
        private const val GATEWAY = "https://gateway.kugou.com"
        fun md5(value: String): String = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        fun signature(params: Map<String, String>, body: String, salt: String): String = md5(salt + params.filterKeys { it != "signature" }.toSortedMap().entries.joinToString("") { "${it.key}=${it.value}" } + body + salt)
        /** QR creation intentionally uses1001, result polling uses3116; both use lite11440. */
        fun loginRequest(path: String, concept: Boolean, mid: String, guid: String, key: String = "", now: Long = System.currentTimeMillis()): Request {
            require(path in listOf("/v2/qrcode", "/v2/get_userinfo_qrcode"))
            require(mid.matches(Regex("[a-fA-F0-9]{16,64}")) && guid.matches(Regex("[a-fA-F0-9-]{16,64}")))
            val time = now.toString()
            val params = linkedMapOf("dfid" to "-", "mid" to mid, "uuid" to "-", "appid" to (if (path == "/v2/qrcode") "1001" else if (concept) "3116" else "1005"),
                "clientver" to (if (concept) "11440" else "20489"), "clienttime" to time, "plat" to "4", "srcappid" to "2919")
            if (path == "/v2/qrcode") {
                params["type"] = "1"
                params["qrcode_txt"] = "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=${if (concept) "3116" else "1005"}&"
            } else { require(key.isNotBlank()); params["qrcode"] = key }
            params["signature"] = signature(params, "", "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt")
            val headers = linkedMapOf("User-Agent" to (if (concept) LITE_UA else "Android-AndroidPhone-11430-0-0-NetMusic-wifi"), "dfid" to "-", "mid" to mid, "clienttime" to time,
                "kg-rc" to "1", "kg-thash" to "5d816a0", "kg-rec" to "1", "kg-rf" to "B9EDA08A64250DEFFBCADDEE00F8F25F",
                "Cookie" to "KUGOU_API_GUID=$guid; KUGOU_API_MID=$mid; dfid=-")
            return Request("https://login-user.kugou.com", path, params, headers)
        }

        fun parseStream(root: JsonObject, expectedHash: String, quality: String): Resolved {
            fun str(o: JsonObject, key: String) = runCatching { o.get(key)?.asString.orEmpty() }.getOrDefault("")
            val parts = listOfNotNull(root, root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject)
            val hashes = parts.flatMap { o -> listOf("req_hash", "hash", "filehash", "file_hash").map { str(o, it).trim() } }.filter { it.isNotBlank() }
            if (hashes.any { !it.equals(expectedHash, true) }) throw Failure(FailureKind.PROTOCOL, "播放地址", detail = "返回 hash 与请求音质不匹配")
            if (parts.any { o -> listOf("is_free_part", "IsFreePart", "is_trial", "preview").any { str(o, it) in listOf("1", "true") } })
                throw Failure(FailureKind.API, "播放地址", detail = "平台仅提供试听，不作为完整曲目播放")
            val urls = parts.flatMap { o -> listOf("url", "backupdownurl", "backup_url").flatMap { key ->
                val value = o.get(key)
                when { value == null || value.isJsonNull -> emptyList(); value.isJsonArray -> value.asJsonArray.mapNotNull { runCatching { it.asString }.getOrNull() }; else -> listOfNotNull(runCatching { value.asString }.getOrNull()) }
            } }
            // Some CDN mirrors are advertised as http; upgrade the SAME host/path/query to TLS.
            // Never send account cookies to the audio CDN, and never downgrade after a TLS failure.
            val secureUrls = urls.map { it.trim().replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://") }
            val url = secureUrls.firstOrNull { value -> runCatching {
                val uri = URI(value.trim())
                uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
            }.getOrDefault(false) } ?: throw Failure(FailureKind.FORMAT, "播放地址", detail = "平台未提供安全的音频地址")
            val millis = parts.firstNotNullOfOrNull { str(it, "timelength").toLongOrNull()?.takeIf { n -> n > 0 } }
            val seconds = parts.firstNotNullOfOrNull { str(it, "timeLength").toLongOrNull()?.takeIf { n -> n > 0 } }
            return Resolved(url.trim(), millis ?: seconds?.takeIf { it < Long.MAX_VALUE / 1000 }?.times(1000) ?: 0L, quality)
        }
    }

    internal object HttpTransport : Transport {
        override fun exchange(request: Request): JsonObject {
            val query = request.params.entries.joinToString("&") { URLEncoder.encode(it.key, Charsets.UTF_8) + "=" + URLEncoder.encode(it.value, Charsets.UTF_8) }
            val connection = URI(request.base + request.path + "?" + query).toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8000; connection.readTimeout = 12000; connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                request.headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
                request.body?.let { body ->
                    connection.requestMethod = "POST"; connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                if (connection.responseCode !in 200..299) throw Failure(FailureKind.NETWORK, request.path, detail = "酷狗 HTTP ${connection.responseCode}")
                val bytes = connection.inputStream.use { it.readNBytes(2 * 1024 * 1024 + 1) }
                if (bytes.size > 2 * 1024 * 1024) throw Failure(FailureKind.PROTOCOL, request.path, detail = "响应超过安全上限")
                return JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
            } finally { connection.disconnect() }
        }
    }
}
