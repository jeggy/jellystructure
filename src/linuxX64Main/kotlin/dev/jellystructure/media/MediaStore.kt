package dev.jellystructure.media

import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.log.Logger
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.MediaPage
import dev.jellystructure.model.TrackKind
import dev.jellystructure.nfo.NfoWriter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class MediaStore(private val db: JellystructureDb) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load() {
        val count = db.mediaQueries.count().executeAsOne()
        Logger.info("MediaStore: DB has $count media items")
    }

    suspend fun update(newItems: List<MediaItem>) {
        // Snapshot existing titlesByLang before deleting so a full rescan never erases
        // languages pulled in earlier scans.
        val existingTitles: Map<String, Map<String, String>> = allItems().associate { it.id to it.titlesByLang }
        db.transaction {
            db.mediaQueries.deleteAll()
            newItems.forEach { item ->
                val old = existingTitles[item.id]
                val merged = if (!old.isNullOrEmpty()) item.copy(titlesByLang = old + item.titlesByLang) else item
                upsertItem(merged)
            }
        }
    }

    fun list(
        kind: MediaKind? = null,
        filter: String? = null,
        search: String? = null,
        sort: String? = null,
        page: Int = 1,
        pageSize: Int = 20,
        studios: List<String> = emptyList(),
        networks: List<String> = emptyList(),
        genres: List<String> = emptyList(),
        audioLangs: List<String> = emptyList(),
        trackTitle: String? = null,
        audioCodec: String? = null,
        untaggedAudio: Boolean = false,
        tags: List<String> = emptyList(),
    ): MediaPage {
        // Attention filter is applied in-memory so multi-default items (issueCount=0) are included.
        val filterMissingArtwork = if (filter == "missing_artwork") 1L else 0L
        // When a search term is given, skip SQL LIKE (which only covers `title`) and do in-memory
        // search across title + originalTitle + all titlesByLang values for multi-language coverage.
        val searchArg = if (search.isNullOrBlank()) null else null // always null: in-memory below

        val jsonBlobs = db.mediaQueries.listFiltered(
            kind = kind?.name,
            filterAttention = 0L,
            filterMissingArtwork = filterMissingArtwork,
            search = searchArg,
        ).executeAsList()

        val searchLower = search?.lowercase()?.takeIf { it.isNotBlank() }

        val decoded = jsonBlobs.mapNotNull { blob ->
            runCatching { json.decodeFromString(MediaItem.serializer(), blob) }.getOrNull()
        }.let { items ->
            var result = items
            if (searchLower != null) {
                result = result.filter { item ->
                    item.title.lowercase().contains(searchLower) ||
                    item.originalTitle?.lowercase()?.contains(searchLower) == true ||
                    item.titlesByLang.values.any { it.lowercase().contains(searchLower) }
                }
            }
            if (filter == "attention") {
                result = result.filter { item ->
                    item.issueCount > 0 || item.languageMix || item.hasMultiDefaultAudio()
                }
            }
            if (studios.isNotEmpty()) result = result.filter { item -> studios.any { s -> item.studio.equals(s, ignoreCase = true) } }
            if (networks.isNotEmpty()) result = result.filter { item -> networks.any { n -> item.network.equals(n, ignoreCase = true) } }
            if (genres.isNotEmpty()) result = result.filter { item -> genres.any { g -> item.genres.any { it.equals(g, ignoreCase = true) } } }
            if (audioLangs.isNotEmpty() || trackTitle != null || audioCodec != null || untaggedAudio) {
                result = result.filter { item -> item.matchesAudioFilter(audioLangs, trackTitle, audioCodec, untaggedAudio) }
            }
            if (tags.isNotEmpty()) {
                result = result.filter { item ->
                    tags.any { tag -> item.tags.any { it.equals(tag, ignoreCase = true) } }
                }
            }
            result
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

    // Resolves either a slug id or a Jellyfin UUID — Jellyfin ID is the canonical URL form.
    fun resolve(id: String): MediaItem? = get(id) ?: allItems().firstOrNull { it.jellyfinId == id }

    fun allItems(): List<MediaItem> =
        db.mediaQueries.getAll().executeAsList().mapNotNull { blob ->
            runCatching { json.decodeFromString(MediaItem.serializer(), blob) }.getOrNull()
        }

    suspend fun addOrUpdate(item: MediaItem) {
        val existing = get(item.id)
        val merged = if (existing != null && existing.titlesByLang.isNotEmpty()) {
            item.copy(titlesByLang = existing.titlesByLang + item.titlesByLang)
        } else item
        upsertItem(merged)
    }

    suspend fun updateOne(item: MediaItem) {
        val existing = get(item.id)
        val merged = if (existing != null && existing.titlesByLang.isNotEmpty()) {
            item.copy(titlesByLang = existing.titlesByLang + item.titlesByLang)
        } else item
        upsertItem(merged)
    }

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

    fun trackFacets(): TrackFacets {
        val items = allItems()
        val langCounts = mutableMapOf<String, Int>()
        val codecCounts = mutableMapOf<String, Int>()
        val titleCounts = mutableMapOf<String, Int>()
        for (item in items) {
            val audioTracks = item.allAudioTracks()
            val allTracks = if (item.kind == MediaKind.TV_SHOW) item.episodes.flatMap { it.tracks } else item.tracks
            val langs = audioTracks.mapNotNull { it.language?.lowercase() }.toSet()
            val codecs = audioTracks.map { it.codec.lowercase() }.toSet()
            // titles from audio + subtitle tracks
            val titles = allTracks.filter { it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE }
                .mapNotNull { it.title?.takeIf { t -> t.isNotBlank() } }.toSet()
            for (lang in langs) langCounts[lang] = (langCounts[lang] ?: 0) + 1
            for (codec in codecs) codecCounts[codec] = (codecCounts[codec] ?: 0) + 1
            for (title in titles) titleCounts[title] = (titleCounts[title] ?: 0) + 1
        }
        return TrackFacets(
            audioLanguages = langCounts.entries.sortedByDescending { it.value }
                .map { TrackFacetItem(it.key, it.value) },
            audioCodecs = codecCounts.entries.sortedByDescending { it.value }
                .map { TrackFacetItem(it.key, it.value) },
            trackTitles = titleCounts.entries.sortedByDescending { it.value }
                .map { TrackFacetItem(it.key, it.value) },
        )
    }

    fun metaFacets(): MetaFacets {
        val items = allItems()
        val studioCounts  = mutableMapOf<String, Int>()
        val networkCounts = mutableMapOf<String, Int>()
        val genreCounts   = mutableMapOf<String, Int>()
        val tagCounts     = mutableMapOf<String, Int>()
        for (item in items) {
            item.studio?.let  { s -> studioCounts[s]  = (studioCounts[s]  ?: 0) + 1 }
            item.network?.let { n -> networkCounts[n] = (networkCounts[n] ?: 0) + 1 }
            item.genres.forEach { g -> genreCounts[g] = (genreCounts[g] ?: 0) + 1 }
            item.tags.forEach   { t -> tagCounts[t]   = (tagCounts[t]   ?: 0) + 1 }
        }
        return MetaFacets(
            studios  = studioCounts.entries.sortedByDescending { it.value }.map { TrackFacetItem(it.key, it.value) },
            networks = networkCounts.entries.sortedByDescending { it.value }.map { TrackFacetItem(it.key, it.value) },
            genres   = genreCounts.entries.sortedByDescending { it.value }.map { TrackFacetItem(it.key, it.value) },
            tags     = tagCounts.entries.sortedByDescending { it.value }.map { TrackFacetItem(it.key, it.value) },
        )
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

data class TrackFacetItem(val value: String, val count: Int)
data class TrackFacets(
    val audioLanguages: List<TrackFacetItem>,
    val audioCodecs: List<TrackFacetItem>,
    val trackTitles: List<TrackFacetItem>,
)

data class MetaFacets(
    val studios: List<TrackFacetItem>,
    val networks: List<TrackFacetItem>,
    val genres: List<TrackFacetItem>,
    val tags: List<TrackFacetItem>,
)

private fun MediaItem.hasMultiDefaultAudio(): Boolean {
    val tracks = if (kind == MediaKind.TV_SHOW) episodes.flatMap { it.tracks } else tracks
    return tracks.filter { it.kind == TrackKind.AUDIO && it.default }.size >= 2 ||
        (kind == MediaKind.TV_SHOW && episodes.any { ep -> ep.tracks.count { it.kind == TrackKind.AUDIO && it.default } >= 2 })
}

private fun MediaItem.allAudioTracks() =
    (if (kind == MediaKind.TV_SHOW) episodes.flatMap { it.tracks } else tracks)
        .filter { it.kind == TrackKind.AUDIO }

private fun MediaItem.matchesAudioFilter(
    audioLangs: List<String>,
    trackTitle: String?,
    audioCodec: String?,
    untaggedAudio: Boolean,
): Boolean = allAudioTracks().any { t ->
    (audioLangs.isEmpty() || audioLangs.any { lang -> t.language?.equals(lang, ignoreCase = true) == true }) &&
    (trackTitle == null || t.title?.contains(trackTitle, ignoreCase = true) == true) &&
    (audioCodec == null || t.codec.equals(audioCodec, ignoreCase = true)) &&
    (!untaggedAudio || t.language == null)
}
