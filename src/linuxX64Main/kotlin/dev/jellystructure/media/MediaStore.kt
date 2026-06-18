package dev.jellystructure.media

import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.log.Logger
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.MediaPage
import dev.jellystructure.nfo.NfoWriter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class MediaStore(private val db: JellystructureDb) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load() {
        val count = db.mediaQueries.count().executeAsOne()
        Logger.infoSync("MediaStore: DB has $count media items")
    }

    suspend fun update(newItems: List<MediaItem>) {
        db.transaction {
            db.mediaQueries.deleteAll()
            newItems.forEach { upsertItem(it) }
        }
    }

    fun list(
        kind: MediaKind? = null,
        filter: String? = null,
        search: String? = null,
        sort: String? = null,
        page: Int = 1,
        pageSize: Int = 20,
    ): MediaPage {
        val filterAttention = if (filter == "attention") 1L else 0L
        val filterMissingArtwork = if (filter == "missing_artwork") 1L else 0L
        val searchArg = search?.takeIf { it.isNotBlank() }

        val jsonBlobs = db.mediaQueries.listFiltered(
            kind = kind?.name,
            filterAttention = filterAttention,
            filterMissingArtwork = filterMissingArtwork,
            search = searchArg,
        ).executeAsList()

        val decoded = jsonBlobs.mapNotNull { blob ->
            runCatching { json.decodeFromString(MediaItem.serializer(), blob) }.getOrNull()
        }

        val sorted = when (sort) {
            "title" -> decoded.sortedBy { it.title.lowercase() }
            "year" -> decoded.sortedByDescending { it.year ?: 0 }
            else -> decoded.sortedByDescending { it.scannedAt }
        }

        val total = sorted.size
        val paged = sorted.drop((page - 1) * pageSize).take(pageSize)
        return MediaPage(paged, total, page, pageSize)
    }

    fun get(id: String): MediaItem? {
        val blob = db.mediaQueries.getById(id).executeAsOneOrNull() ?: return null
        return runCatching { json.decodeFromString(MediaItem.serializer(), blob) }.getOrNull()
    }

    fun allItems(): List<MediaItem> =
        db.mediaQueries.getAll().executeAsList().mapNotNull { blob ->
            runCatching { json.decodeFromString(MediaItem.serializer(), blob) }.getOrNull()
        }

    suspend fun addOrUpdate(item: MediaItem) = upsertItem(item)

    suspend fun updateOne(item: MediaItem) = upsertItem(item)

    fun movieCount(): Int = db.mediaQueries.countByKind("MOVIE").executeAsOne().toInt()

    fun tvShowCount(): Int = db.mediaQueries.countByKind("TV_SHOW").executeAsOne().toInt()

    fun tvEpisodeCount(): Int = db.mediaQueries.sumEpisodeCount().executeAsOne().toInt()

    fun totalIssueCount(): Int = db.mediaQueries.sumIssueCount().executeAsOne().toInt()

    fun languageMixCount(): Int = db.mediaQueries.countLanguageMix().executeAsOne().toInt()

    fun nfoCoveredCount(): Int = allItems().count { NfoWriter.exists(it) }

    fun nfoCoveragePercent(): Int {
        val total = db.mediaQueries.count().executeAsOne().toInt()
        if (total == 0) return 0
        return (nfoCoveredCount() * 100) / total
    }

    private fun upsertItem(item: MediaItem) {
        db.mediaQueries.upsert(
            id = item.id,
            json = json.encodeToString(MediaItem.serializer(), item),
            kind = item.kind.name,
            title = item.title,
            year = item.year?.toLong(),
            studio = item.studio,
            network = item.network,
            issue_count = item.issueCount.toLong(),
            language_mix = if (item.languageMix) 1L else 0L,
            scanned_at = item.scannedAt,
            tmdb_id = item.tmdbId?.toLong(),
            poster_path = item.posterPath,
            episode_count = item.episodes.size.toLong(),
        )
    }
}
