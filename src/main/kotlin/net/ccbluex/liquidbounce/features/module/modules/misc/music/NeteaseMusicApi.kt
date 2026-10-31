package net.ccbluex.liquidbounce.features.module.modules.misc.music

import com.google.gson.JsonParser
import net.ccbluex.liquidbounce.utils.client.logger
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * 无登录网易云接入：走公开 Meting 兼容网关（无需账号/Cookie）。
 * 若网关不可用，搜索会返回空列表，仍可使用本地曲库。
 */
object NeteaseMusicApi {

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** 可按需改成自建 meting 实例 */
    private val gateways = listOf(
        "https://api.injahow.cn/meting/",
        "https://meting.qjqq.cn/",
    )

    fun search(keyword: String, limit: Int = 20): List<MusicTrack> {
        if (keyword.isBlank()) return emptyList()
        val q = URLEncoder.encode(keyword, StandardCharsets.UTF_8)
        for (base in gateways) {
            val url = "${base}?server=netease&type=search&id=$q"
            val body = get(url) ?: continue
            val tracks = parseSearch(body, limit)
            if (tracks.isNotEmpty()) return tracks
        }
        // 官方匿名搜索兜底（仅元数据，播放仍依赖网关取 URL）
        return searchOfficial(keyword, limit)
    }

    fun songUrl(id: String): String? {
        for (base in gateways) {
            val url = "${base}?server=netease&type=url&id=$id"
            val body = get(url) ?: continue
            parseUrl(body)?.let { return it }
        }
        return null
    }

    fun lyric(id: String): LyricSheet {
        for (base in gateways) {
            val url = "${base}?server=netease&type=lrc&id=$id"
            val body = get(url) ?: continue
            val sheet = LyricParser.parse(body)
            if (sheet.lines.isNotEmpty()) return sheet
            // 有的网关返回 JSON
            runCatching {
                val el = JsonParser.parseString(body)
                if (el.isJsonObject) {
                    val lrc = el.asJsonObject.get("lrc")?.asString
                        ?: el.asJsonObject.get("lyric")?.asString
                    val parsed = LyricParser.parse(lrc)
                    if (parsed.lines.isNotEmpty()) return parsed
                }
            }
        }
        return LyricSheet(emptyList())
    }

    private fun searchOfficial(keyword: String, limit: Int): List<MusicTrack> {
        val q = URLEncoder.encode(keyword, StandardCharsets.UTF_8)
        val url = "https://music.163.com/api/search/get/web?csrf_token=&s=$q&type=1&offset=0&total=true&limit=$limit"
        val body = get(url, mapOf(
            "Referer" to "https://music.163.com/",
            "User-Agent" to "Mozilla/5.0",
        )) ?: return emptyList()
        return runCatching {
            val root = JsonParser.parseString(body).asJsonObject
            val songs = root.getAsJsonObject("result")?.getAsJsonArray("songs") ?: return emptyList()
            songs.take(limit).mapNotNull { el ->
                val o = el.asJsonObject
                val id = o.get("id")?.asString ?: return@mapNotNull null
                val name = o.get("name")?.asString ?: "未知"
                val artist = o.getAsJsonArray("artists")
                    ?.joinToString(" / ") { it.asJsonObject.get("name")?.asString ?: "" }
                    ?.ifBlank { "未知" } ?: "未知"
                val album = o.getAsJsonObject("album")?.get("name")?.asString ?: ""
                val duration = o.get("duration")?.asLong ?: 0L
                MusicTrack(id, name, artist, album, duration)
            }
        }.getOrElse {
            logger.warn("Netease official search failed", it)
            emptyList()
        }
    }

    private fun parseSearch(body: String, limit: Int): List<MusicTrack> {
        return runCatching {
            val el = JsonParser.parseString(body)
            val arr = when {
                el.isJsonArray -> el.asJsonArray
                el.isJsonObject && el.asJsonObject.has("data") -> el.asJsonObject.getAsJsonArray("data")
                else -> return emptyList()
            }
            arr.take(limit).mapNotNull { item ->
                val o = item.asJsonObject
                val id = o.get("id")?.asString
                    ?: o.get("url")?.asString?.substringAfterLast("/")
                    ?: return@mapNotNull null
                val title = o.get("title")?.asString ?: o.get("name")?.asString ?: "未知"
                val artist = o.get("author")?.asString ?: o.get("artist")?.asString ?: "未知"
                val pic = o.get("pic")?.asString ?: o.get("cover")?.asString
                MusicTrack(id = id, title = title, artist = artist, coverUrl = pic)
            }
        }.getOrDefault(emptyList())
    }

    private fun parseUrl(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.startsWith("http")) return trimmed.lineSequence().firstOrNull()?.trim()
        return runCatching {
            val el = JsonParser.parseString(body)
            when {
                el.isJsonObject -> el.asJsonObject.get("url")?.asString
                el.isJsonArray && el.asJsonArray.size() > 0 ->
                    el.asJsonArray[0].asJsonObject.get("url")?.asString
                else -> null
            }
        }.getOrNull()
    }

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String? {
        return runCatching {
            val builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .GET()
            headers.forEach { (k, v) -> builder.header(k, v) }
            if (!headers.containsKey("User-Agent")) {
                builder.header("User-Agent", "Mozilla/5.0 LiquidBounce-Music")
            }
            val resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            if (resp.statusCode() in 200..299) resp.body() else null
        }.onFailure {
            logger.debug("Music API GET failed: $url", it)
        }.getOrNull()
    }
}
