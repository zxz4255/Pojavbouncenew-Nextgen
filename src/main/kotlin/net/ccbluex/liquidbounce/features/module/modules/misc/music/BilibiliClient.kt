package net.ccbluex.liquidbounce.features.module.modules.misc.music

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** Public Bilibili video search and audio-only DASH playback; no account cookie is requested or stored. */
internal object BilibiliClient {
    private const val API = "https://api.bilibili.com"
    private const val WEB = "https://www.bilibili.com"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
    private const val MAX_VIDEO_DURATION_MS = 30L * 60L * 1000L
    private const val MAX_JSON_RESPONSE_BYTES = 8 * 1024 * 1024

    @Volatile private var guestCookie = ""
    @Volatile private var guestCookieExpiresAt = 0L

    private data class VideoAudioInfo(val cid: Long, val durationMs: Long)
    private data class AudioRepresentation(val url: String, val bandwidth: Long)

    private fun string(obj: JsonObject?, key: String): String = runCatching {
        val value = obj?.get(key) ?: return ""
        if (value.isJsonNull || !value.isJsonPrimitive) "" else value.asString
    }.getOrDefault("")

    private fun number(obj: JsonObject?, key: String): Long = runCatching {
        val value = obj?.get(key) ?: return 0L
        if (value.isJsonNull || !value.isJsonPrimitive) 0L else value.asLong
    }.getOrDefault(0L)

    private fun objectAt(obj: JsonObject?, key: String): JsonObject? = runCatching {
        obj?.get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    }.getOrNull()

    private fun arrayAt(obj: JsonObject?, key: String): JsonArray? = runCatching {
        obj?.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
    }.getOrNull()

    /** Search public video results only; the Bilibili audio-catalog endpoint is never queried. */
    fun search(keyword: String, limit: Int): List<Song> {
        val term = keyword.trim()
        if (term.isEmpty() || limit <= 0) return emptyList()

        val params = linkedMapOf(
            "search_type" to "video",
            "keyword" to term,
            "page" to "1",
            "page_size" to limit.coerceIn(1, 30).toString(),
            "order" to "totalrank",
        )
        val root = getJson("$API/x/web-interface/search/type?${query(params)}", "$WEB/")
        requireCodeZero(root, "B站公开视频搜索")
        val data = objectAt(root, "data") ?: throw IOException("B站搜索没有返回数据")
        if (data.has("v_voucher") && !data.get("v_voucher").isJsonNull) {
            throw IOException("B站搜索暂时需要额外验证，请稍后重试")
        }

        val result = arrayAt(data, "result") ?: return emptyList()
        val songs = ArrayList<Song>()
        for (element in result) {
            if (!element.isJsonObject || songs.size >= limit) continue
            val item = element.asJsonObject
            if (string(item, "type").isNotEmpty() && string(item, "type") != "video") continue

            val aid = number(item, "aid").takeIf { it > 0L } ?: number(item, "id")
            val bvid = string(item, "bvid")
            val title = cleanTitle(string(item, "title"))
            if (aid <= 0L || bvid.length !in 10..16 || !bvid.startsWith("BV") || title.isEmpty()) continue

            val duration = durationMs(item.get("duration"))
            // Avoid turning obvious multi-song compilations into enormous single downloads.
            if (duration > MAX_VIDEO_DURATION_MS) continue
            val artist = cleanTitle(string(item, "author").ifEmpty { string(item, "uname") })
            val cover = secureImage(string(item, "pic").ifEmpty { string(item, "cover") })
            songs.add(
                Song(
                    aid, title, artist, string(item, "typename"), cover, duration,
                    MusicProvider.BILIBILI, bvid, "",
                ),
            )
        }
        return songs
    }

    /** Resolves only a DASH audio representation. No video URL or progressive durl is used. */
    fun stream(song: Song): NeteaseClient.Stream {
        require(song.provider == MusicProvider.BILIBILI) { "歌曲不是 B 站来源" }
        val bvid = song.providerHash
        if (!bvid.startsWith("BV") || bvid.length !in 10..16) throw IOException("B站视频标识无效")

        val detail = videoAudioInfo(song, bvid)
        if (detail.cid <= 0L) throw IOException("B站视频没有可播放分 P")

        val params = linkedMapOf(
            "bvid" to bvid,
            "cid" to detail.cid.toString(),
            "qn" to "16",
            "fnval" to "16", // DASH: audio and video are separate; this player reads only dash.audio[].
            "fnver" to "0",
            "fourk" to "0",
            "otype" to "json",
        )
        val root = getJson("$API/x/player/playurl?${query(params)}", "$WEB/video/$bvid")
        requireCodeZero(root, "B站 DASH 音频解析")
        val data = objectAt(root, "data") ?: throw IOException("B站未返回 DASH 音频信息")
        val dash = objectAt(data, "dash") ?: throw IOException("B站没有提供 DASH 音频流")
        val audioTracks = arrayAt(dash, "audio") ?: throw IOException("B站没有提供 DASH 音频流")

        val representations = ArrayList<AudioRepresentation>()
        for (element in audioTracks) {
            if (!element.isJsonObject) continue
            val item = element.asJsonObject
            val mime = string(item, "mimeType").ifEmpty { string(item, "mime_type") }.lowercase()
            val codec = string(item, "codecs").lowercase()
            if (mime != "audio/mp4" && !mime.startsWith("audio/")) continue
            if (codec.isNotEmpty() && !codec.startsWith("mp4a.")) continue

            // The primary URL and backups all come from dash.audio[]. Only .m4s audio is accepted.
            for (candidate in audioUrlCandidates(item)) {
                val safeUrl = runCatching { validateDashAudioUrl(candidate) }.getOrNull() ?: continue
                representations.add(AudioRepresentation(safeUrl, number(item, "bandwidth")))
                break
            }
        }
        val selected = representations.maxByOrNull { it.bandwidth }
            ?: throw IOException("B站没有可用的 AAC 音频 .m4s 流")

        val duration = number(data, "timelength").takeIf { it > 0L }
            ?: detail.durationMs.takeIf { it > 0L }
            ?: song.durationMs
        return NeteaseClient.Stream(selected.url, duration)
    }

    private fun audioUrlCandidates(item: JsonObject): List<String> {
        val result = ArrayList<String>()
        for (key in arrayOf("baseUrl", "base_url", "url")) {
            string(item, key).takeIf { it.isNotBlank() }?.let(result::add)
        }
        for (key in arrayOf("backupUrl", "backup_url")) {
            val backup = arrayAt(item, key) ?: continue
            for (entry in backup) {
                if (entry.isJsonPrimitive && !entry.isJsonNull) {
                    entry.asString.takeIf { it.isNotBlank() }?.let(result::add)
                }
            }
        }
        return result
    }

    private fun videoAudioInfo(song: Song, bvid: String): VideoAudioInfo {
        val requestedCid = song.providerAlbumId.toLongOrNull()?.takeIf { it > 0L }
        if (requestedCid != null && song.durationMs > 0L) return VideoAudioInfo(requestedCid, song.durationMs)

        val root = getJson("$API/x/web-interface/view?bvid=${encode(bvid)}", "$WEB/video/$bvid")
        requireCodeZero(root, "B站视频详情")
        val data = objectAt(root, "data") ?: throw IOException("B站未返回视频详情")
        val pages = arrayAt(data, "pages")
        val firstPage = pages?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        val cid = number(firstPage, "cid").takeIf { it > 0L } ?: number(data, "cid")
        val duration = number(firstPage, "duration").takeIf { it > 0L }?.times(1000L)
            ?: number(data, "duration").takeIf { it > 0L }?.times(1000L)
            ?: song.durationMs
        return VideoAudioInfo(cid, duration)
    }

    private fun durationMs(value: JsonElement?): Long {
        if (value == null || value.isJsonNull || !value.isJsonPrimitive) return 0L
        val raw = runCatching { value.asString.trim() }.getOrDefault("")
        if (raw.isEmpty()) return 0L
        if (raw.contains(':')) {
            val parts = raw.split(':')
            if (parts.size !in 2..3) return 0L
            val numbers = parts.map { it.toLongOrNull() ?: return 0L }
            var seconds = 0L
            for (part in numbers) seconds = seconds * 60L + part
            return (seconds * 1000L).coerceAtLeast(0L)
        }
        val seconds = raw.toLongOrNull() ?: return 0L
        return if (seconds > 0L) seconds * 1000L else 0L
    }

    private fun cleanTitle(raw: String): String = raw
        .replace(Regex("<[^>]*>"), "")
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace("&#x27;", "'", ignoreCase = true)
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun secureImage(raw: String): String = when {
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("http://") -> raw.replaceFirst("http://", "https://")
        raw.startsWith("https://") -> raw
        else -> ""
    }

    private fun validateDashAudioUrl(raw: String): String {
        val safe = validateMediaUrl(raw)
        val mediaPath = runCatching { URL(safe).path.lowercase() }.getOrDefault("")
        if (!mediaPath.endsWith(".m4s")) throw IOException("B站 DASH 音频不是 .m4s 文件")
        return safe
    }

    private fun validateMediaUrl(raw: String): String {
        val secure = when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") -> raw.replaceFirst("http://", "https://")
            else -> raw
        }
        val parsed = runCatching { URL(secure) }.getOrElse { throw IOException("B站返回的媒体地址无效", it) }
        val host = parsed.host.lowercase()
        val allowed = host == "bilivideo.com" || host.endsWith(".bilivideo.com") ||
            host == "bilivideo.net" || host.endsWith(".bilivideo.net") ||
            host == "bilivideo.cn" || host.endsWith(".bilivideo.cn") ||
            host == "hdslb.com" || host.endsWith(".hdslb.com") ||
            host.endsWith(".akamaized.net")
        if (parsed.protocol != "https" || !allowed) throw IOException("B站返回了非安全或非预期媒体地址")
        return secure
    }

    private fun requireCodeZero(root: JsonObject, action: String) {
        val code = number(root, "code")
        if (code != 0L) {
            val reason = string(root, "message").ifEmpty { string(root, "msg") }
            throw IOException("$action：B站返回 $code${if (reason.isEmpty()) "" else "（$reason）"}")
        }
    }

    private fun getJson(url: String, referer: String): JsonObject {
        val text = requestText(url, referer, MAX_JSON_RESPONSE_BYTES)
        return runCatching { JsonParser.parseString(text).asJsonObject }
            .getOrElse { throw IOException("B站 API 返回了无效 JSON", it) }
    }

    private fun requestText(url: String, referer: String, maxBytes: Int): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 15000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Referer", referer)
            connection.setRequestProperty("Origin", WEB)
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
            connection.setRequestProperty("Accept-Encoding", "identity")
            val cookie = ensureGuestCookie()
            if (cookie.isNotEmpty()) connection.setRequestProperty("Cookie", cookie)
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("B站 HTTP $status")
            var input: java.io.InputStream = connection.inputStream
            if (connection.contentEncoding?.contains("gzip", ignoreCase = true) == true) {
                input = GZIPInputStream(input)
            }
            val bytes = input.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = stream.read(chunk)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    if (total > maxBytes) throw IOException("B站响应超过大小限制")
                    output.write(chunk, 0, count)
                }
                output.toByteArray()
            }
            return String(bytes, StandardCharsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    /** Bootstrap only anonymous anti-bot device cookies; never stores SESSDATA. */
    private fun ensureGuestCookie(): String {
        val now = System.currentTimeMillis()
        if (guestCookie.isNotEmpty() && guestCookieExpiresAt > now) return guestCookie
        synchronized(this) {
            val checkedAt = System.currentTimeMillis()
            if (guestCookie.isNotEmpty() && guestCookieExpiresAt > checkedAt) return guestCookie
            val connection = runCatching { URL("$WEB/").openConnection() as HttpURLConnection }.getOrNull()
                ?: return guestCookie
            try {
                connection.connectTimeout = 7000
                connection.readTimeout = 9000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
                val status = connection.responseCode
                if (status !in 200..399) return guestCookie
                val cookies = LinkedHashMap<String, String>()
                connection.headerFields.entries
                    .filter { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
                    .flatMap { it.value ?: emptyList() }
                    .forEach { header ->
                        val pair = header.substringBefore(';').split('=', limit = 2)
                        if (pair.size == 2 && (pair[0] == "buvid3" || pair[0] == "b_nut")) {
                            cookies[pair[0]] = pair[1]
                        }
                    }
                val buvid = cookies["buvid3"]
                if (!buvid.isNullOrBlank()) {
                    guestCookie = buildString {
                        append("buvid3=").append(buvid)
                        cookies["b_nut"]?.let { append("; b_nut=").append(it) }
                    }
                    guestCookieExpiresAt = checkedAt + TimeUnit.HOURS.toMillis(12)
                }
                return guestCookie
            } catch (_: Exception) {
                return guestCookie
            } finally {
                runCatching { connection.inputStream.close() }
                connection.disconnect()
            }
        }
    }

    private fun query(params: Map<String, String>): String = params.entries.joinToString("&") {
        "${encode(it.key)}=${encode(it.value)}"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
