package net.ccbluex.liquidbounce.features.module.modules.misc.music

import com.google.gson.JsonObject
import java.io.IOException
import java.nio.file.Path
import java.util.Base64

/**
 * 酷狗音乐 provider.
 *
 * Uses the public web endpoints only:
 *  - `songsearch.kugou.com/song_search_v2` for search (no account, no key)
 *  - `wwwapi.kugou.com/yy/index.php?r=play/getdata` for the stream address. A device id (`kg_mid`)
 *    is generated locally; that is a public handshake value, not an account credential.
 *  - `krcs.kugou.com` / `lyrics.kugou.com` for the timed lyric text.
 *
 * Nothing bypasses paywalls: when the platform answers without a playable url the module reports it
 * instead of trying to reconstruct one.
 */
object KugouApi {

    private const val APPID = "1014"
    private const val CLIENT_VERSION = "2000"

    fun deviceId(): String {
        val existing = MusicCookies.get("kugou.com", "kg_mid")
        if (!existing.isNullOrBlank()) return existing
        val generated = MusicHttp.md5(System.nanoTime().toString() + "-monet-music")
        MusicCookies.put("kugou.com", "kg_mid", generated)
        return generated
    }

    fun search(query: String, page: Int): SearchResult {
        val url = "https://songsearch.kugou.com/song_search_v2" +
            "?keyword=${MusicHttp.encode(query)}" +
            "&page=${page + 1}&pagesize=30&platform=WebFilter&userid=0" +
            "&clientver=$CLIENT_VERSION&iscorrection=1&privilege_filter=0&filter=10&token=&appid=$APPID"
        val root = MusicHttp.json(url)
        val data = root.getAsJsonObject("data") ?: return SearchResult(emptyList(), "酷狗搜索未返回结果")
        val lists = data.getAsJsonArray("lists") ?: return SearchResult(emptyList(), "没有匹配歌曲")
        val tracks = lists.mapNotNull { element ->
            val song = element.asJsonObject
            val hash = song.string("FileHash").ifBlank { song.string("EMixSongID") }
            if (hash.isBlank()) return@mapNotNull null
            val title = song.string("SongName").ifBlank { song.string("FileName") }
            val artist = song.string("SingerName")
            val albumId = song.string("AlbumID")
            val privileged = song.int("Privilege") != 0 || song.int("PayType") != 0
            MusicTrack(
                key = "kugou:$hash",
                title = title.ifBlank { "未知歌曲" },
                artist = artist.ifBlank { "未知歌手" },
                album = song.string("AlbumName"),
                durationMs = song.int("Duration").toLong() * 1000L,
                source = MusicSource.KUGOU,
                artworkUrl = song.string("Image").replace("{size}", "240").replace("http://", "https://"),
                license = if (privileged) "VIP / 付费歌曲" else "公开接口结果",
                extra = albumId,
            )
        }
        return SearchResult(tracks, "酷狗免登录可搜索；VIP / 版权限制歌曲可能无法取得播放地址")
    }

    fun resolveTrack(track: MusicTrack): MusicTrack {
        val hash = track.key.substringAfter(':')
        if (!hash.matches(Regex("[A-Za-z0-9]+"))) throw IOException("非法酷狗资源标识")
        val mid = deviceId()
        val timestamp = System.currentTimeMillis()
        val url = "https://wwwapi.kugou.com/yy/index.php?r=play/getdata" +
            "&hash=$hash&album_id=${MusicHttp.encode(track.extra)}&mid=$mid&platid=4&_=$timestamp"
        val root = MusicHttp.json(url)
        val data = root.getAsJsonObject("data")
            ?: throw IOException("酷狗没有返回播放数据（${root.int("err_code")}）")
        var playUrl = data.string("play_url")
        if (playUrl.isBlank()) {
            playUrl = data.getAsJsonArray("backup_url")?.firstOrNull()?.asString.orEmpty()
        }
        if (playUrl.isBlank()) {
            throw IOException("酷狗未提供此歌曲的播放地址：VIP / 版权限制不会被绕过，可登录后重试")
        }
        return track.copy(remoteUrl = playUrl,
            artworkUrl = data.string("img").ifBlank { track.artworkUrl }.replace("{size}", "240"))
    }

    fun artwork(track: MusicTrack): String {
        val hash = track.key.substringAfter(':')
        if (!hash.matches(Regex("[A-Za-z0-9]+"))) return ""
        return MusicHttp.json("https://wwwapi.kugou.com/yy/index.php?r=play/getdata&hash=$hash" +
            "&album_id=${MusicHttp.encode(track.extra)}&mid=${deviceId()}&platid=4")
            .getAsJsonObject("data")?.string("img").orEmpty().replace("{size}", "240")
    }

    fun lyrics(track: MusicTrack): LyricDocument {
        val hash = track.key.substringAfter(':')
        val keyword = listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" - ")
        val searchUrl = "https://krcs.kugou.com/search?ver=1&man=yes&client=mobi" +
            "&keyword=${MusicHttp.encode(keyword)}&duration=${track.durationMs}&hash=$hash"
        val candidate = runCatching {
            MusicHttp.json(searchUrl).getAsJsonArray("candidates")?.map { it.asJsonObject }
                ?.minByOrNull { item ->
                    val duration = item.string("duration").toLongOrNull() ?: 0L
                    val delta = if (duration > 0 && track.durationMs > 0) kotlin.math.abs(duration - track.durationMs) else 10000L
                    delta - if (item.string("hash").equals(hash, true)) 1000000L else 0L
                }
        }.getOrNull() ?: return LyricDocument.empty(track.key, "酷狗没有提供歌词")
        val candidateDuration = candidate.string("duration").toLongOrNull() ?: 0L
        if (candidateDuration > 0 && track.durationMs > 0 &&
            kotlin.math.abs(candidateDuration - track.durationMs) > 5000L) {
            return LyricDocument.empty(track.key, "歌词候选时长与当前版本不匹配，未使用错误时间轴")
        }
        val id = candidate.string("id")
        val accessKey = candidate.string("accesskey")
        if (id.isBlank() || accessKey.isBlank()) return LyricDocument.empty(track.key, "酷狗歌词索引不完整")
        val downloadUrl = "https://lyrics.kugou.com/download?ver=1&client=pc&id=$id" +
            "&accesskey=${MusicHttp.encode(accessKey)}&fmt=lrc&charset=utf8"
        val payload = runCatching { MusicHttp.json(downloadUrl) }.getOrNull()
            ?: return LyricDocument.empty(track.key, "酷狗歌词下载失败")
        val encoded = payload.string("content")
        if (encoded.isBlank()) return LyricDocument.empty(track.key, "酷狗歌词内容为空")
        val text = runCatching { String(Base64.getDecoder().decode(encoded), Charsets.UTF_8) }.getOrNull()
            ?: return LyricDocument.empty(track.key, "酷狗歌词解码失败")
        // KRC tags are stripped, only the timestamped text matters here.
        val cleaned = text.replace(Regex("<[^>]+>"), "")
        val lines = LrcParser.parse(cleaned)
        return if (lines.isEmpty()) LyricDocument.empty(track.key, "酷狗歌词没有时间轴")
        else LyricDocument(track.key, lines)
    }

    // --- login ------------------------------------------------------------------------------

    fun qrChallenge(): QrChallenge {
        val root = MusicHttp.json(
            "https://login-user.kugou.com/v2/qrcode?appid=1005&type=1&plat=4&qrcode_tm=${System.currentTimeMillis()}",
        )
        val data = root.getAsJsonObject("data") ?: throw IOException("酷狗二维码接口无数据")
        val key = data.string("qrcode")
        if (key.isBlank()) throw IOException("酷狗二维码为空")
        return QrChallenge(key, "https://login-user.kugou.com/qrcode/index.html?qrcode=$key")
    }

    fun pollQr(key: String): QrPoll {
        val root = MusicHttp.json(
            "https://login-user.kugou.com/v2/get_userinfo_qrcode?appid=1005&plat=4&qrcode=${MusicHttp.encode(key)}",
        )
        val data = root.getAsJsonObject("data") ?: return QrPoll.Waiting
        val status = data.int("status")
        if (status == 4) {
            val userid = data.string("userid")
            val token = data.string("token").ifBlank { data.string("vip_token") }
            if (userid.isBlank() || token.isBlank()) return QrPoll.Failed("酷狗扫码确认未返回有效凭证")
            if (userid.isNotBlank()) MusicCookies.put("kugou.com", "userid", userid)
            if (token.isNotBlank()) MusicCookies.put("kugou.com", "token", token)
            deviceId()
            return QrPoll.Success(
                MusicSession(
                    MusicSource.KUGOU, userid, data.string("nickname").ifBlank { "酷狗用户" },
                    vip = data.int("vip_type") > 0, detail = "扫码登录",
                ),
            )
        }
        return when (status) {
            0, 1 -> QrPoll.Waiting
            2, 3 -> QrPoll.Scanned
            5, 6 -> QrPoll.Expired
            else -> QrPoll.Failed("酷狗二维码状态异常：$status / ${root.int("errcode")}")
        }
    }

    fun sendCode(phone: String): String {
        val root = MusicHttp.json(
            "https://login-user.kugou.com/v1/send_mobile_code?appid=1005&plat=4&area_code=86" +
                "&phone=${MusicHttp.encode(phone)}&verifycode=&_=${System.currentTimeMillis()}",
        )
        return if (root.int("status") == 1 || root.int("errcode") == 0) "" else "验证码发送失败（${root.int("errcode")}）"
    }

    fun loginByPhone(phone: String, code: String): MusicSession {
        val root = MusicHttp.json(
            "https://login-user.kugou.com/v1/login_by_phone?appid=1005&plat=4&area_code=86" +
                "&phone=${MusicHttp.encode(phone)}&code=${MusicHttp.encode(code)}&_=${System.currentTimeMillis()}",
        )
        val data = root.getAsJsonObject("data") ?: throw IOException("酷狗登录失败（${root.int("errcode")}）")
        val userid = data.string("userid")
        val token = data.string("token")
        if (userid.isBlank() || token.isBlank()) throw IOException("酷狗登录未返回有效凭证")
        MusicCookies.put("kugou.com", "userid", userid)
        MusicCookies.put("kugou.com", "token", token)
        deviceId()
        return MusicSession(
            MusicSource.KUGOU, userid, data.string("nickname").ifBlank { "酷狗用户" },
            detail = "手机号登录",
        )
    }

    /** Manual fallback: `userid=..; token=..` pasted from the web player. */
    fun loginByToken(raw: String): MusicSession {
        val pairs = raw.split(';', '&', '\n')
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else part.substring(0, index).trim() to part.substring(index + 1).trim()
            }
            .toMap()
        val userid = pairs["userid"] ?: pairs["user_id"] ?: throw IOException("缺少 userid")
        val token = pairs["token"] ?: throw IOException("缺少 token")
        throw IOException("r5 未提供可验证酷狗 Cookie 的接口；为避免假登录，请使用平台扫码或短信认证")
    }
}

/** Small helpers so the JSON parsing above stays readable. */
private fun JsonObject.string(name: String): String =
    get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString.orEmpty()

private fun JsonObject.int(name: String): Int =
    get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrDefault(0) } ?: 0
