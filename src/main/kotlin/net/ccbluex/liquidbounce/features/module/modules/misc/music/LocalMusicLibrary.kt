package net.ccbluex.liquidbounce.features.module.modules.misc.music

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Local-only history, favourites and playlists; never stores cookies or implies cloud sync. */
internal class LocalMusicLibrary(private val file: File) {
    private val searches = ArrayList<String>()
    private val history = ArrayList<Song>()
    private val favorites = ArrayList<Song>()
    private val playlists = LinkedHashMap<String, MutableList<Song>>()
    @Volatile var error: String? = null
        private set

    @Synchronized fun searchHistory(): List<String> = searches.toList()
    @Synchronized fun listeningHistory(): List<Song> = history.toList()
    @Synchronized fun favoriteSongs(): List<Song> = favorites.toList()
    @Synchronized fun playlistNames(): List<String> = playlists.keys.toList()
    @Synchronized fun playlistSongs(name: String): List<Song> = playlists[name]?.toList() ?: emptyList()
    @Synchronized fun isFavorite(song: Song) = favorites.any { it.sameTrack(song) }
    @Synchronized fun isInPlaylist(name: String, song: Song) = playlists[name]?.any { it.sameTrack(song) } == true

    @Synchronized fun rememberSearch(query: String) {
        val clean = query.trim()
        if (clean.isEmpty()) return
        searches.removeAll { it.equals(clean, ignoreCase = true) }
        searches.add(0, clean)
        while (searches.size > 30) searches.removeAt(searches.lastIndex)
        save()
    }
    @Synchronized fun removeSearch(query: String) { searches.remove(query); save() }
    @Synchronized fun clearSearches() { searches.clear(); save() }
    @Synchronized fun rememberSong(song: Song) { insert(history, song, 300); save() }
    @Synchronized fun toggleFavorite(song: Song) {
        if (!favorites.removeAll { it.sameTrack(song) }) insert(favorites, song, 5000)
        save()
    }
    @Synchronized fun createPlaylist(): String {
        var n = 1
        while (playlists.containsKey("本地歌单 $n")) n++
        val name = "本地歌单 $n"
        playlists[name] = ArrayList()
        save()
        return name
    }
    @Synchronized fun deletePlaylist(name: String) { playlists.remove(name); save() }
    @Synchronized fun togglePlaylistSong(name: String, song: Song) {
        val list = playlists[name] ?: return
        if (!list.removeAll { it.sameTrack(song) }) insert(list, song, 5000)
        save()
    }
    private fun insert(list: MutableList<Song>, song: Song, limit: Int) {
        list.removeAll { it.sameTrack(song) }
        list.add(0, song)
        while (list.size > limit) list.removeAt(list.lastIndex)
    }

    @Synchronized fun load() {
        if (!file.isFile) return
        try {
            val root = JsonParser.parseString(file.readText(StandardCharsets.UTF_8)).asJsonObject
            fun array(name: String): JsonArray? = root.getAsJsonArray(name)
            fun readSongs(a: JsonArray?): ArrayList<Song> {
                val result = ArrayList<Song>()
                if (a == null) return result
                for (element in a) {
                    try {
                        val o = element.asJsonObject
                        fun str(key: String) = o.get(key)?.asString ?: ""
                        val provider = runCatching { MusicProvider.valueOf(str("provider")) }.getOrDefault(MusicProvider.NETEASE)
                        val song = Song(o.get("id")!!.asLong, str("name"), str("artist"), str("album"), str("cover"), o.get("duration")?.asLong ?: 0L, provider, str("providerHash"), str("providerAlbumId"))
                        if (song.id > 0 && result.none { it.sameTrack(song) }) result.add(song)
                    } catch (_: Exception) { /* skip an invalid entry, not the entire library */ }
                }
                return result
            }
            searches.clear(); history.clear(); favorites.clear(); playlists.clear()
            array("searches")?.forEach { runCatching { it.asString }.getOrNull()?.let(searches::add) }
            history.addAll(readSongs(array("history")))
            favorites.addAll(readSongs(array("favorites")))
            array("playlists")?.forEach { element ->
                runCatching {
                    val obj = element.asJsonObject
                    val name = obj.get("name")!!.asString
                    if (name.isNotBlank()) playlists[name] = readSongs(obj.getAsJsonArray("songs"))
                }
            }
            error = null
        } catch (e: Exception) {
            // Do not replace an unreadable file with an empty one silently.
            error = "本地音乐库读取失败：${e.message ?: e.javaClass.simpleName}"
        }
    }
    @Synchronized private fun save() {
        if (error != null) return
        try {
            fun songArray(songs: List<Song>): JsonArray {
                val result = JsonArray()
                for (song in songs) {
                    val o = JsonObject()
                    o.addProperty("id", song.id); o.addProperty("name", song.name)
                    o.addProperty("artist", song.artist); o.addProperty("album", song.album)
                    o.addProperty("cover", song.coverUrl); o.addProperty("duration", song.durationMs)
                    o.addProperty("provider", song.provider.name); o.addProperty("providerHash", song.providerHash)
                    o.addProperty("providerAlbumId", song.providerAlbumId)
                    result.add(o)
                }
                return result
            }
            val root = JsonObject()
            val queries = JsonArray(); searches.forEach { queries.add(it) }
            root.add("searches", queries); root.add("history", songArray(history)); root.add("favorites", songArray(favorites))
            val lists = JsonArray()
            for ((name, songs) in playlists) {
                val entry = JsonObject(); entry.addProperty("name", name); entry.add("songs", songArray(songs)); lists.add(entry)
            }
            root.add("playlists", lists)
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            Files.write(temp.toPath(), root.toString().toByteArray(StandardCharsets.UTF_8))
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            error = "本地音乐库保存失败：${e.message ?: e.javaClass.simpleName}"
        }
    }
}
