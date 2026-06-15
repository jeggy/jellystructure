package dev.jellystructure.media

import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.MediaPage
import dev.jellystructure.nfo.NfoWriter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class MediaStore(private val cacheFile: String) {
    private val mutex = Mutex()
    private var items: List<MediaItem> = emptyList()
    private val json = Json { ignoreUnknownKeys = true }

    fun load() {
        val path = Path(cacheFile)
        if (!SystemFileSystem.exists(path)) return
        runCatching {
            val content = SystemFileSystem.source(path).buffered().readString()
            items = json.decodeFromString(ListSerializer(MediaItem.serializer()), content)
            println("[INFO] Loaded ${items.size} media items from cache")
        }.onFailure {
            println("[WARN] Failed to load media cache: ${it.message}")
        }
    }

    suspend fun update(newItems: List<MediaItem>) = mutex.withLock {
        items = newItems
        persist()
    }

    fun list(
        kind: MediaKind? = null,
        filter: String? = null,
        page: Int = 1,
        pageSize: Int = 20,
    ): MediaPage {
        var filtered = items
        if (kind != null) filtered = filtered.filter { it.kind == kind }
        when (filter) {
            "attention" -> filtered = filtered.filter { it.issueCount > 0 || it.languageMix }
            "missing_artwork" -> filtered = filtered.filter { it.posterPath == null }
        }
        val total = filtered.size
        val paged = filtered.drop((page - 1) * pageSize).take(pageSize)
        return MediaPage(paged, total, page, pageSize)
    }

    fun get(id: String): MediaItem? = items.firstOrNull { it.id == id }

    fun allItems(): List<MediaItem> = items

    suspend fun addOrUpdate(item: MediaItem) = mutex.withLock {
        items = if (items.any { it.id == item.id }) {
            items.map { if (it.id == item.id) item else it }
        } else {
            items + item
        }
        persist()
    }

    suspend fun updateOne(item: MediaItem) = mutex.withLock {
        items = items.map { if (it.id == item.id) item else it }
        persist()
    }

    fun movieCount(): Int = items.count { it.kind == MediaKind.MOVIE }

    fun tvShowCount(): Int = items.count { it.kind == MediaKind.TV_SHOW }

    fun totalIssueCount(): Int = items.sumOf { it.issueCount }

    fun nfoCoveredCount(): Int = items.count { NfoWriter.exists(it) }

    fun languageMixCount(): Int = items.count { it.languageMix }

    private fun persist() {
        val tmp = "$cacheFile.tmp"
        runCatching {
            val content = json.encodeToString(ListSerializer(MediaItem.serializer()), items)
            val sink = SystemFileSystem.sink(Path(tmp)).buffered()
            sink.writeString(content)
            sink.flush()
            sink.close()
            platform.posix.rename(tmp, cacheFile)
        }.onFailure {
            println("[ERROR] Failed to persist media cache: ${it.message}")
        }
    }
}
