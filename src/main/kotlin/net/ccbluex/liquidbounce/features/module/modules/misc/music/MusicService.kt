package net.ccbluex.liquidbounce.features.module.modules.misc.music

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.ccbluex.liquidbounce.config.ConfigSystem
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory cookie store.
 *
 * Only cookies the platform itself hands back during a *user initiated* login are kept, and only
 * for the two supported platforms. Nothing is sent to any other host: every request goes through
 * [MusicHttp.validate] first. Persisting is limited to the session cookies the user asked to keep
 * (see [MusicLogin]), never to unrelated browsing cookies.
 */
object MusicCookies {
    private val jar = ConcurrentHashMap<String, MutableMap<String, String>>()

    fun put(domain: String, name: String, value: String) {
        val key = normalize(domain)
        if (key.isEmpty() || name.isEmpty()) return
        jar.getOrPut(key) { ConcurrentHashMap() }[name] = value
    }

    fun get(domain: String, name: String): String? = jar[normalize(domain)]?.get(name)

    fun headerFor(host: String): String {
        if (jar.isEmpty()) return ""
        val plain = host.lowercase(Locale.ROOT)
        val parts = mutableListOf<String>()
        jar.toSortedMap(compareByDescending<String> { it.length }).forEach { (domain, values) ->
            if (plain == domain || plain.endsWith(".$domain")) {
                values.forEach { (name, value) -> if (parts.none { it.startsWith("$name=") }) parts += "$name=$value" }
            }
        }
        return parts.joinToString("; ")
    }

    fun rawFor(domain: String): String =
        jar[normalize(domain)]?.entries?.joinToString("; ") { "${it.key}=${it.value}" }.orEmpty()

    fun absorb(uri: URI, headers: Map<String, List<String>>) {
        val host = uri.host?.lowercase(Locale.ROOT) ?: return
        headers.forEach { (name, values) ->
            if (!name.equals("set-cookie", true)) return@forEach
            values.forEach { line -> absorbOne(host, line) }
        }
    }

    private fun absorbOne(host: String, line: String) {
        val segments = line.split(';')
        val pair = segments.firstOrNull()?.trim().orEmpty()
        val index = pair.indexOf('=')
        if (index <= 0) return
        val name = pair.substring(0, index).trim()
        val value = pair.substring(index + 1).trim()
        var domain = host
        for (segment in segments.drop(1)) {
            val trimmed = segment.trim()
            if (trimmed.lowercase(Locale.ROOT).startsWith("domain=")) {
                domain = trimmed.substringAfter('=').trim().removePrefix(".")
            }
        }
        val normalized = normalize(domain)
        if (host != normalized && !host.endsWith(".$normalized")) return
        if (normalized !in setOf("music.163.com", "163.com", "kugou.com") &&
            !normalized.endsWith(".kugou.com") && !normalized.endsWith(".music.163.com")) return
        put(normalized, name, value)
    }

    fun snapshotPlatform(domain: String): List<Triple<String, String, String>> = jar.entries
        .filter { it.key == domain || it.key.endsWith(".$domain") }
        .flatMap { (host, values) -> values.map { Triple(host, it.key, it.value) } }

    fun snapshot(domain: String): List<Pair<String, String>> =
        jar[normalize(domain)]?.map { it.key to it.value }?.toList().orEmpty()

    fun restore(domain: String, pairs: Map<String, String>) {
        pairs.forEach { (name, value) -> put(domain, name, value) }
    }

    fun clear(domain: String? = null) {
        if (domain == null) jar.clear() else {
            val key = normalize(domain)
            jar.keys.removeIf { it == key || it.endsWith(".$key") || key.endsWith(".$it") }
        }
    }

    private fun normalize(domain: String) = domain.trim().removePrefix(".").lowercase(Locale.ROOT)
}

/** Public HTTP only. Every URL is whitelisted before a connection is opened. */
object MusicHttp {
    val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    private const val MAX_JSON = 2 * 1024 * 1024

    private val neteaseAudio = setOf("music.163.com", "music.126.net")
    private val kugouAudio = setOf("kugou.com", "kgimg.com", "kglink.com")

    fun validate(uri: URI, cover: Boolean = false) {
        val host = uri.host?.lowercase(Locale.ROOT) ?: throw IOException("无效的音源地址")
        if (uri.scheme !in setOf("http", "https") || uri.userInfo != null || uri.port !in setOf(-1, 80, 443)) {
            throw IOException("已拒绝非常规地址：$host")
        }
        // API hosts are always the platform's own domain; media may come from their CDN suffixes.
        val allowed = neteaseAudio.any { host == it || host.endsWith(".$it") } ||
            kugouAudio.any { host == it || host.endsWith(".$it") } ||
            host == "music.163.com" || host.endsWith(".music.163.com")
        if (!allowed) throw IOException("已拒绝非音源白名单地址：$host")
        if (cover && !allowed) throw IOException("已拒绝非白名单封面地址：$host")
    }

    fun request(url: String): HttpRequest.Builder {
        val uri = URI(url)
        validate(uri)
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) LiquidBounce-MonetMusic/2.0")
        when {
            host.endsWith("163.com") || host.endsWith("126.net") -> builder.header("Referer", "https://music.163.com/")
            host.endsWith("kugou.com") || host.endsWith("kgimg.com") -> builder.header("Referer", "https://www.kugou.com/")
        }
        val cookies = MusicCookies.headerFor(host)
        if (cookies.isNotEmpty()) builder.header("Cookie", cookies)
        return builder
    }

    data class RemoteStream(val input: java.io.InputStream, val length: Long)

    /** All redirects are individually revalidated; cookies are re-scoped at each hop. */
    fun stream(url: String, cancelled: () -> Boolean = { false }): RemoteStream {
        var uri = URI(url)
        repeat(6) {
            if (cancelled()) throw IOException("请求已取消")
            validate(uri)
            val response = client.send(request(uri.toString()).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
            MusicCookies.absorb(uri, response.headers().map())
            if (response.statusCode() in setOf(301, 302, 303, 307, 308)) {
                response.body().close()
                uri = uri.resolve(response.headers().firstValue("location").orElseThrow { IOException("重定向无地址") })
            } else {
                if (response.statusCode() != 200) {
                    response.body().close()
                    throw IOException("HTTP ${response.statusCode()}：平台拒绝请求或地址过期")
                }
                val length = response.headers().firstValueAsLong("content-length").orElse(-1)
                if (length > AudioDecoder.MAX_FILE_BYTES) {
                    response.body().close(); throw IOException("响应超过 100 MiB")
                }
                val body = object : java.io.FilterInputStream(response.body()) {
                    private var count = 0L
                    private fun account(n: Int): Int {
                        if (cancelled()) throw IOException("请求已取消")
                        if (n > 0) count += n
                        if (count > AudioDecoder.MAX_FILE_BYTES) throw IOException("响应超过 100 MiB")
                        return n
                    }
                    override fun read(): Int { val n = super.read(); account(if (n < 0) 0 else 1); return n }
                    override fun read(b: ByteArray, off: Int, len: Int): Int = account(`in`.read(b, off, len))
                }
                return RemoteStream(body, length)
            }
        }
        throw IOException("重定向次数过多")
    }

    fun bytes(url: String, cover: Boolean = false, limit: Int = MAX_JSON): ByteArray {
        val uri = URI(url)
        if (cover) validate(uri, cover = true)
        stream(url).input.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > limit) throw IOException("响应超过 ${limit / 1024 / 1024} MiB 安全上限")
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }

    fun json(url: String): JsonObject =
        JsonParser.parseString(bytes(url).toString(Charsets.UTF_8)).asJsonObject

    fun text(url: String): String = bytes(url).toString(Charsets.UTF_8)

    /** Form encoded POST, used by the login endpoints. */
    fun post(url: String, form: Map<String, String> = emptyMap()): JsonObject {
        val uri = URI(url)
        validate(uri)
        val body = form.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val request = request(url)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        MusicCookies.absorb(uri, response.headers().map())
        if (response.statusCode() != 200) throw IOException("HTTP ${response.statusCode()}")
        return JsonParser.parseString(response.body()).asJsonObject
    }

    fun download(url: String, target: Path, cancelled: () -> Boolean, opened: (java.io.InputStream?) -> Unit = {}, progress: (Long) -> Unit): Path {
        if (Files.isRegularFile(target) && Files.size(target) > 0) return target
        Files.createDirectories(target.parent)
        val partial = target.resolveSibling(target.fileName.toString() + ".part")
        var current = URI(url)
        try {
            repeat(6) {
                validate(current)
                val response = client.send(request(current.toString()).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
                MusicCookies.absorb(current, response.headers().map())
                if (response.statusCode() in setOf(301, 302, 303, 307, 308)) {
                    response.body().close()
                    val location = response.headers().firstValue("location").orElseThrow { IOException("重定向缺少地址") }
                    current = current.resolve(location)
                    return@repeat
                }
                if (response.statusCode() != 200) {
                    response.body().close()
                    throw IOException("音频下载 HTTP ${response.statusCode()}；地址可能已过期，请重新选歌")
                }
                val declared = response.headers().firstValueAsLong("content-length").orElse(-1)
                if (declared > AudioDecoder.MAX_FILE_BYTES) {
                    response.body().close()
                    throw IOException("文件大于 100 MiB，已拒绝加载")
                }
                opened(response.body())
                response.body().use { input ->
                    Files.newOutputStream(partial).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            if (cancelled()) throw IOException("下载已取消")
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > AudioDecoder.MAX_FILE_BYTES) throw IOException("文件超过 100 MiB 安全上限")
                            output.write(buffer, 0, n)
                            progress(total)
                        }
                        if (total == 0L) throw IOException("服务器返回了空音频")
                    }
                }
                // Sniff before exposing a cached, apparently playable file.
                AudioDecoder.open(partial).use { }
                try {
                    Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
                }
                return target
            }
            throw IOException("音源重定向次数过多")
        } finally {
            opened(null)
            Files.deleteIfExists(partial)
        }
    }

    fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
        .take(24)

    fun md5(value: String): String = MessageDigest.getInstance("MD5").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

class MusicLibrary {
    val directory: Path = ConfigSystem.rootFolder.toPath().resolve("music")
    val cache: Path = directory.resolve("cache")

    init {
        Files.createDirectories(directory)
        Files.createDirectories(cache)
    }

    fun scan(query: String): List<MusicTrack> {
        val root = directory.toRealPath()
        return Files.walk(root, 5).use { paths ->
            paths.filter {
                Files.isRegularFile(it) && it.fileName.toString().substringAfterLast(".", "").lowercase() in setOf("mp3", "flac")
            }
                .filter { !it.toAbsolutePath().normalize().startsWith(cache.toAbsolutePath().normalize()) }
                .limit(2000)
                .toList()
                .map { path ->
                    MusicTrack(
                        "local:${path.toRealPath()}", path.fileName.toString().substringBeforeLast('.'),
                        "本地音乐", durationMs = 0, source = MusicSource.LOCAL, localPath = path,
                        license = "用户提供的本地文件",
                    )
                }
                .filter { query.isBlank() || "${it.title} ${it.artist}".contains(query, true) }
                .sortedBy { it.title.lowercase() }
        }
    }

    fun local(path: Path): MusicTrack {
        val real = path.toAbsolutePath().normalize().toRealPath()
        if (!Files.isRegularFile(real) ||
            real.fileName.toString().substringAfterLast(".", "").lowercase() !in setOf("mp3", "flac")
        ) {
            throw IOException("请选择存在的 .mp3 或 .flac 文件")
        }
        if (Files.size(real) > AudioDecoder.MAX_FILE_BYTES) throw IOException("本地文件大于 100 MiB")
        AudioDecoder.open(real).use { }
        return MusicTrack(
            "local:$real", real.fileName.toString().substringBeforeLast('.'), "本地音乐",
            source = MusicSource.LOCAL, localPath = real, license = "用户提供的本地文件",
        )
    }

    fun localLyrics(track: MusicTrack): LyricDocument {
        val file = track.localPath ?: return LyricDocument.empty(track.key, "可放入同名 .lrc 文件")
        val path = file.resolveSibling(file.fileName.toString().substringBeforeLast('.') + ".lrc")
        return if (Files.isRegularFile(path) && Files.size(path) < 1024 * 1024) {
            val lines = LrcParser.parse(Files.readString(path))
            if (lines.isEmpty()) LyricDocument.empty(track.key, "LRC 中没有有效时间轴") else LyricDocument(track.key, lines)
        } else {
            LyricDocument.empty(track.key, "可放入同名 .lrc 文件")
        }
    }

    fun cleanupCache() {
        if (!Files.isDirectory(cache)) return
        val files = Files.list(cache).use { it.filter { file -> Files.isRegularFile(file) }.toList() }
        var bytes = files.sumOf { Files.size(it) }
        files.sortedBy { Files.getLastModifiedTime(it) }.forEach {
            if (bytes <= 256L * 1024 * 1024) return
            val size = Files.size(it)
            if (Files.deleteIfExists(it)) bytes -= size
        }
    }
}

data class SearchResult(val tracks: List<MusicTrack>, val notice: String)

object MusicProviders {

    fun search(source: MusicSource, query: String, library: MusicLibrary, page: Int = 0): SearchResult = when (source) {
        MusicSource.LOCAL -> SearchResult(library.scan(query), "仅扫描 LiquidBounce/music；不上传本地文件")
        MusicSource.NETEASE -> neteaseSearch(query, page)
        MusicSource.KUGOU -> KugouApi.search(query, page)
    }

    private fun neteaseSearch(query: String, page: Int): SearchResult {
        val url = "https://music.163.com/api/cloudsearch/pc?s=${MusicHttp.encode(query)}&type=1&limit=30&offset=${page * 30}"
        val result = MusicHttp.json(url).getAsJsonObject("result") ?: throw IOException("网易云搜索未返回结果对象")
        val songs = result.getAsJsonArray("songs") ?: return SearchResult(emptyList(), "没有匹配歌曲")
        val tracks = songs.map { element ->
            val song = element.asJsonObject
            val id = song.get("id").asLong
            val album = song.getAsJsonObject("al")
            val artists = song.getAsJsonArray("ar")?.joinToString(" / ") { it.asJsonObject.get("name")?.asString.orEmpty() }
            val fee = song.get("fee")?.asInt ?: 0
            val cover = album?.get("picUrl")?.asString.orEmpty()
                .replace("http://", "https://")
                .let { if (it.isBlank() || it.contains('?')) it else "$it?param=240y240" }
            MusicTrack(
                "netease:$id", song.get("name")?.asString ?: "未知歌曲", artists ?: "未知歌手",
                album?.get("name")?.asString.orEmpty(), song.get("dt")?.asLong ?: 0,
                MusicSource.NETEASE,
                artworkUrl = cover,
                license = if (fee == 0) "公开接口结果" else "VIP / 付费歌曲",
                extra = id.toString(),
            )
        }
        return SearchResult(tracks, "免登录可搜索；VIP、地区或版权限制歌曲可能无法取得播放地址")
    }

    /** Resolve metadata only: playback policy decides whether to cache or incrementally decode. */
    fun resolveTrack(track: MusicTrack): MusicTrack = when (track.source) {
        MusicSource.LOCAL -> track
        MusicSource.NETEASE -> {
            val id = track.key.substringAfter(':')
            if (!id.matches(Regex("[0-9]+"))) throw IOException("非法网易云资源标识")
            val response = MusicHttp.json("https://music.163.com/api/song/enhance/player/url?ids=%5B$id%5D&br=320000")
            val data = response.getAsJsonArray("data")?.firstOrNull()?.asJsonObject
                ?: throw IOException("网易云没有返回音源")
            val url = data.get("url")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            if (url.isBlank() || data.get("code")?.asInt != 200) throw IOException("网易云未授权播放，版权 / VIP 限制不会绕过")
            if (data.has("freeTrialInfo") && !data.get("freeTrialInfo").isJsonNull) throw IOException("平台只返回试听片段")
            var resolved = track.copy(remoteUrl = url)
            if (resolved.artworkUrl.isBlank()) {
                runCatching {
                    val song = MusicHttp.json("https://music.163.com/api/song/detail/?id=$id&ids=%5B$id%5D")
                        .getAsJsonArray("songs")?.firstOrNull()?.asJsonObject
                    val album = song?.getAsJsonObject("album") ?: song?.getAsJsonObject("al")
                    resolved = resolved.copy(artworkUrl = album?.get("picUrl")?.takeUnless { it.isJsonNull }?.asString.orEmpty())
                }
            }
            resolved
        }
        MusicSource.KUGOU -> KugouApi.resolveTrack(track)
    }

    fun lyrics(track: MusicTrack, library: MusicLibrary): LyricDocument = when (track.source) {
        MusicSource.LOCAL -> library.localLyrics(track)
        MusicSource.NETEASE -> neteaseLyrics(track)
        MusicSource.KUGOU -> KugouApi.lyrics(track)
    }

    private fun neteaseLyrics(track: MusicTrack): LyricDocument {
        val id = track.key.substringAfter(':')
        val obj = MusicHttp.json("https://music.163.com/api/song/lyric?id=$id&lv=-1&tv=-1&kv=-1")
        val raw = obj.getAsJsonObject("lrc")?.get("lyric")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        val translation = obj.getAsJsonObject("tlyric")?.get("lyric")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        val lines = LrcParser.parse(raw, translation)
        return if (lines.isEmpty()) LyricDocument.empty(track.key, "网易云没有提供时间轴歌词")
        else LyricDocument(track.key, lines)
    }
}
