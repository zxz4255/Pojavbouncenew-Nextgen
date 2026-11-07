package net.ccbluex.liquidbounce.features.module.modules.misc.music

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.ccbluex.liquidbounce.config.ConfigSystem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors

/** Device library, explicitly NOT a simulated platform collection. Separate platform/account scopes. */
object MusicUserLibrary {
    data class Playlist(val id: String, val name: String, val tracks: List<MusicTrack>)
    private data class Shelf(
        val searches: MutableList<String> = mutableListOf(),
        val history: MutableList<MusicTrack> = mutableListOf(),
        val favorites: MutableList<MusicTrack> = mutableListOf(),
        val playlists: MutableMap<String, Pair<String, MutableList<MusicTrack>>> = linkedMapOf(),
    )
    private val lock = Any()
    private val shelves = linkedMapOf<String, Shelf>()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "Music-Library").apply { isDaemon = true } }
    private val file get() = ConfigSystem.rootFolder.toPath().resolve("music/library-v2.json")
    private var loaded = false
    @Volatile var failure = ""
        private set

    private fun scope(source: MusicSource): String = source.name + ":" +
        (MusicLogin.session(source)?.userId?.takeIf { it.isNotBlank() } ?: "guest")

    private fun shelf(source: MusicSource): Shelf {
        if (!loaded) load()
        return shelves.getOrPut(scope(source)) { Shelf() }
    }

    fun searches(source: MusicSource): List<String> = synchronized(lock) { shelf(source).searches.toList() }
    fun history(source: MusicSource): List<MusicTrack> = synchronized(lock) { shelf(source).history.toList() }
    fun favorites(source: MusicSource): List<MusicTrack> = synchronized(lock) { shelf(source).favorites.toList() }
    fun favorite(track: MusicTrack): Boolean = synchronized(lock) { shelf(track.source).favorites.any { it.key == track.key } }
    fun playlists(source: MusicSource): List<Playlist> = synchronized(lock) {
        shelf(source).playlists.map { (id, item) -> Playlist(id, item.first, item.second.toList()) }
    }
    fun searched(source: MusicSource, query: String) = synchronized(lock) {
        if (query.isBlank()) return@synchronized
        val list = shelf(source).searches
        list.removeAll { it.equals(query, true) }; list.add(0, query.take(120))
        while (list.size > 24) list.removeAt(list.lastIndex)
        persist()
    }
    fun clearSearches(source: MusicSource) = synchronized(lock) { shelf(source).searches.clear(); persist() }
    fun listened(track: MusicTrack) = synchronized(lock) {
        val list = shelf(track.source).history
        list.removeAll { it.key == track.key }; list.add(0, track.copy(remoteUrl = ""))
        while (list.size > 300) list.removeAt(list.lastIndex)
        persist()
    }
    fun toggleFavorite(track: MusicTrack) = synchronized(lock) {
        val list = shelf(track.source).favorites
        if (!list.removeAll { it.key == track.key }) list.add(0, track.copy(remoteUrl = ""))
        persist()
    }
    fun createPlaylist(source: MusicSource, name: String): String = synchronized(lock) {
        val id = java.util.UUID.randomUUID().toString()
        shelf(source).playlists[id] = name.trim().take(64).ifBlank { "新歌单" } to mutableListOf()
        persist(); id
    }
    fun addToPlaylist(source: MusicSource, id: String, track: MusicTrack) = synchronized(lock) {
        val list = shelf(source).playlists[id]?.second ?: return@synchronized
        if (track.source == source && list.none { it.key == track.key }) list.add(track.copy(remoteUrl = ""))
        persist()
    }
    fun removeFromPlaylist(source: MusicSource, id: String, track: MusicTrack) = synchronized(lock) {
        shelf(source).playlists[id]?.second?.removeAll { it.key == track.key }; persist()
    }
    fun deletePlaylist(source: MusicSource, id: String) = synchronized(lock) {
        shelf(source).playlists.remove(id); persist()
    }

    private fun trackJson(track: MusicTrack): JsonObject = JsonObject().apply {
        addProperty("key", track.key); addProperty("source", track.source.name)
        addProperty("title", track.title); addProperty("artist", track.artist); addProperty("album", track.album)
        addProperty("duration", track.durationMs); addProperty("artwork", track.artworkUrl)
        addProperty("local", track.localPath?.toString()); addProperty("extra", track.extra)
    }
    private fun parseTrack(obj: JsonObject): MusicTrack = MusicTrack(
        key = obj.get("key").asString, title = obj.get("title").asString,
        artist = obj.get("artist").asString, album = obj.get("album").asString,
        durationMs = obj.get("duration").asLong, source = MusicSource.valueOf(obj.get("source").asString),
        artworkUrl = obj.get("artwork").asString, extra = obj.get("extra").asString,
        localPath = obj.get("local")?.takeUnless { it.isJsonNull }?.asString?.let { Path.of(it) },
    )
    private fun tracks(items: List<MusicTrack>) = JsonArray().apply { items.forEach { add(trackJson(it)) } }
    private fun load() {
        loaded = true
        if (!Files.isRegularFile(file)) return
        runCatching {
            if (Files.size(file) > 16 * 1024 * 1024) error("音乐资料库超过安全上限")
            val root = JsonParser.parseString(Files.readString(file)).asJsonObject
            root.entrySet().forEach { (key, value) ->
                val obj = value.asJsonObject
                val shelf = Shelf()
                obj.getAsJsonArray("searches")?.take(24)?.forEach { shelf.searches += it.asString }
                fun read(name: String, target: MutableList<MusicTrack>) {
                    obj.getAsJsonArray(name)?.take(3000)?.forEach {
                        runCatching { parseTrack(it.asJsonObject) }.getOrNull()?.let(target::add)
                    }
                }
                read("history", shelf.history); read("favorites", shelf.favorites)
                obj.getAsJsonArray("playlists")?.take(100)?.forEach { element ->
                    val p = element.asJsonObject
                    val items = p.getAsJsonArray("tracks").take(3000).mapNotNull {
                        runCatching { parseTrack(it.asJsonObject) }.getOrNull()
                    }.toMutableList()
                    shelf.playlists[p.get("id").asString] = p.get("name").asString to items
                }
                shelves[key] = shelf
            }
        }.onFailure { failure = "本地音乐资料读取失败：${it.message}" }
    }
    private fun persist() {
        val root = JsonObject()
        shelves.forEach { (key, shelf) ->
            root.add(key, JsonObject().apply {
                add("searches", JsonArray().apply { shelf.searches.forEach { add(it) } })
                add("history", tracks(shelf.history)); add("favorites", tracks(shelf.favorites))
                add("playlists", JsonArray().apply {
                    shelf.playlists.forEach { (id, p) -> add(JsonObject().apply {
                        addProperty("id", id); addProperty("name", p.first); add("tracks", tracks(p.second))
                    }) }
                })
            })
        }
        val json = root.toString()
        io.execute {
            runCatching {
                Files.createDirectories(file.parent)
                val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
                Files.writeString(temp, json)
                try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
                }
            }.onFailure { failure = "本地音乐资料保存失败：${it.message}" }
        }
    }
}
