package net.ccbluex.liquidbounce.features.module.modules.misc.music

import com.google.gson.JsonObject
import java.io.IOException

/** Real server responses only. Device shelves in MusicUserLibrary are never returned as cloud data.
 * Kugou's r5 adapter contains no cloud-library routes; fail explicitly rather than inventing them.
 */
object MusicCloudLibrary {
    data class Playlist(val id: String, val name: String, val count: Int, val special: Int = 0)
    private fun requireNetease(source: MusicSource): MusicSession {
        if (source != MusicSource.NETEASE) throw IOException("r5 不含酷狗云端曲库协议；本机曲库不冒充云端数据")
        return MusicLogin.session(source) ?: throw IOException("请先完成网易云认证")
    }
    private fun checked(root: JsonObject): JsonObject {
        val code = root.get("code")?.asInt ?: 0
        if (code != 200) throw IOException("平台曲库请求失败 ($code)：${root.get("message")?.asString.orEmpty()}")
        return root
    }
    fun playlists(source: MusicSource): List<Playlist> {
        val session = requireNetease(source)
        val root = checked(MusicHttp.json("https://music.163.com/api/user/playlist?uid=${MusicHttp.encode(session.userId)}&limit=100&offset=0"))
        return root.getAsJsonArray("playlist")?.map {
            val p = it.asJsonObject
            Playlist(p.get("id").asString, p.get("name").asString, p.get("trackCount")?.asInt ?: 0, p.get("specialType")?.asInt ?: 0)
        }.orEmpty()
    }
    fun tracks(source: MusicSource, id: String): List<MusicTrack> {
        requireNetease(source)
        if (!id.matches(Regex("[0-9]+"))) throw IOException("歌单标识无效")
        val root = checked(MusicHttp.json("https://music.163.com/api/playlist/detail?id=$id&n=1000&s=0"))
        val playlist = root.getAsJsonObject("playlist") ?: root.getAsJsonObject("result")
        return playlist?.getAsJsonArray("tracks")?.map { track(it.asJsonObject) }.orEmpty()
    }
    fun favorites(source: MusicSource): List<MusicTrack> {
        val favoriteList = playlists(source).firstOrNull { it.special == 5 }
            ?: throw IOException("平台未返回带收藏标识的歌单，不用普通歌单冒充收藏")
        return tracks(source, favoriteList.id)
    }
    fun history(source: MusicSource): List<MusicTrack> {
        val session = requireNetease(source)
        val root = checked(MusicHttp.post("https://music.163.com/api/v1/play/record",
            mapOf("uid" to session.userId, "type" to "1", "limit" to "1000")))
        val records = root.getAsJsonArray("weekData") ?: root.getAsJsonArray("allData")
        return records?.mapNotNull { it.asJsonObject.getAsJsonObject("song")?.let(::track) }.orEmpty()
    }
    fun favorite(track: MusicTrack, like: Boolean) {
        requireNetease(track.source)
        val id = track.key.substringAfter(':')
        if (!id.matches(Regex("[0-9]+"))) throw IOException("资源标识无效")
        checked(MusicHttp.post("https://music.163.com/api/radio/like", mapOf(
            "trackId" to id, "like" to like.toString(), "alg" to "itembased", "time" to "3",
            "csrf_token" to MusicCookies.get("music.163.com", "__csrf").orEmpty())))
    }
    private fun track(song: JsonObject): MusicTrack {
        val album = song.getAsJsonObject("al") ?: song.getAsJsonObject("album")
        val artists = song.getAsJsonArray("ar") ?: song.getAsJsonArray("artists")
        val duration = song.get("dt") ?: song.get("duration")
        return MusicTrack("netease:${song.get("id").asString}", song.get("name").asString,
            artists?.joinToString(" / ") { it.asJsonObject.get("name").asString }.orEmpty(),
            album?.get("name")?.asString.orEmpty(), duration?.asLong ?: 0, MusicSource.NETEASE,
            artworkUrl = album?.get("picUrl")?.asString.orEmpty())
    }
}
