package dev.jellystructure.media

import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.log.Logger
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.MediaPage
import dev.jellystructure.model.TrackKind
import dev.jellystructure.nfo.NfoWriter
import dev.jellystructure.resolver.LanguageResolver
import dev.jellystructure.shared.tv.Condition
import dev.jellystructure.shared.tv.MatchMode
import dev.jellystructure.tv.ConditionEvaluator
import kotlinx.serialization.json.Json

class MediaStore(private val db: JellystructureDb, private val jsTagStore: JsTagStore) {
    private val json = Json { ignoreUnknownKeys = true }

    // Phase 78: cached tmdbPersonId -> profilePath index for the /api/people/{id}/image endpoint,
    // so a cache miss is O(1) instead of deserialising the whole library per request. Invalidated
    // on any write (upsertItem). null = not built yet.
    private var peopleIndexCache: Map<Int, String>? = null

    // jellyfinId → MediaItem index so TV detail routes avoid a full table scan + JSON deserialise
    // on every page open. Built on first use, invalidated on any write.
    private var jellyfinIdIndex: Map<String, MediaItem>? = null

    // Phase 88: decoded-library cache — skip JSON deserialisation on every read path.
    // Invalidated synchronously on every write (upsertItem). Rebuilt lazily on next allItems() call.
    private var allItemsCache: List<MediaItem>? = null

    // Jellystructure-defined tags (those in the JS-tag store) always survive a re-scan, which
    // otherwise replaces an item's tags with the fresh Jellyfin set (constitution invariant #6).
    private fun preserveJsTags(fresh: MediaItem, existing: MediaItem?): MediaItem {
        val keptJs = existing?.tags?.filter { it in jsTagStore.nameSet() } ?: return fresh
        if (keptJs.isEmpty()) return fresh
        return fresh.copy(tags = (fresh.tags + keptJs).distinct())
    }

    suspend fun load() {
        val count = db.mediaQueries.count().executeAsOne()
        Logger.info("MediaStore: DB has $count media items")
    }

    // Two scanned items can produce the same id (e.g. same title+year, or a duplicate in Jellyfin).
    // Upsert is by id, so without this the second silently overwrites the first. Append a short, stable
    // jellyfinId token to every member of a colliding id-group — deterministic (depends only on the set
    // + each item's jellyfinId, never scan order) so ids stay stable across re-scans (Phase 53-B).
    private fun disambiguateIds(items: List<MediaItem>): List<MediaItem> {
        val counts = items.groupingBy { it.id }.eachCount()
        return items.map { item ->
            val jid = item.jellyfinId
            if ((counts[item.id] ?: 0) > 1 && jid != null) item.copy(id = "${item.id}-${jid.take(8)}")
            else item
        }
    }

    suspend fun update(rawItems: List<MediaItem>) {
        val newItems = disambiguateIds(rawItems)
        // Snapshot existing titlesByLang before deleting so a full rescan never erases
        // languages pulled in earlier scans.
        val existing: Map<String, MediaItem> = allItems().associateBy { it.id }
        db.transaction {
            db.mediaQueries.deleteAll()
            newItems.forEach { item ->
                val old = existing[item.id]
                var merged = preserveJsTags(item, old)
                val oldTitles = old?.titlesByLang
                if (!oldTitles.isNullOrEmpty()) merged = merged.copy(titlesByLang = oldTitles + item.titlesByLang)
                // Keep the original first-seen timestamp so a re-scan doesn't make every existing item
                // look "newly added" — all recency sorts (Newly Added, hero auto, Browse default,
                // related) order by scannedAt. New items keep the Scanner's fresh timestamp and
                // correctly surface as newly added. Exception: a series that gained episodes counts as
                // newly added again, so keep the fresh timestamp when the episode count grew.
                if (old != null && item.episodes.size <= old.episodes.size) {
                    merged = merged.copy(scannedAt = old.scannedAt)
                }
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
        heroIds: Set<String> = emptySet(),
        heroMode: String? = null,   // "featured" | "not_featured" — membership in a viewer's hero carousel
        conditions: List<Condition> = emptyList(),
        match: MatchMode = MatchMode.ALL,
    ): MediaPage {
        val searchLower = search?.lowercase()?.takeIf { it.isNotBlank() }

        // Phase 88: read through the decoded-library cache; apply kind + missing-artwork pre-filters
        // in memory (previously done in SQL by listFiltered, but allItems() is now free on cache hit).
        val decoded = run {
            var items = allItems()
            if (kind != null) items = items.filter { it.kind == kind }
            if (filter == "missing_artwork") items = items.filter { it.posterPath.isNullOrBlank() }
            items
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
            // R74: when a condition stack is provided, route through ConditionEvaluator so that
            // ANY ORs correctly (and is_none_of / not_contains become exact). The legacy per-facet
            // AND block is kept as the fast path when no conditions stack is passed.
            if (conditions.isNotEmpty()) {
                result = result.filter { item -> ConditionEvaluator.matches(item, match, conditions, heroIds) }
            } else {
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
                if (heroMode != null) {
                    result = result.filter { item ->
                        val featured = (item.jellyfinId != null && heroIds.contains(item.jellyfinId)) || heroIds.contains(item.id)
                        if (heroMode == "not_featured") !featured else featured
                    }
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
    fun resolve(id: String): MediaItem? = get(id) ?: resolveByJellyfinId(id)

    /** O(1) lookup by Jellyfin UUID via a lazy-built in-memory index. */
    fun resolveByJellyfinId(jellyfinId: String): MediaItem? {
        val index = jellyfinIdIndex ?: allItems()
            .associateBy { it.jellyfinId ?: "" }
            .filterKeys { it.isNotEmpty() }
            .also { jellyfinIdIndex = it }
        return index[jellyfinId]
    }

    fun allItems(): List<MediaItem> {
        val cached = allItemsCache
        if (cached != null) return cached
        return db.mediaQueries.getAll().executeAsList().mapNotNull { blob ->
            runCatching { json.decodeFromString(MediaItem.serializer(), blob) }.getOrNull()
        }.also { allItemsCache = it }
    }

    /**
     * Phase 78: O(1) profilePath lookup for a TMDB person id across all cast/crew + episode
     * guest stars/crew. Builds a cached index once; rebuilt after the next write. Returns the
     * first non-blank profilePath found, or null if the person isn't in the library.
     */
    fun personProfilePath(tmdbId: Int): String? {
        val index = peopleIndexCache ?: buildPeopleIndex().also { peopleIndexCache = it }
        return index[tmdbId]
    }

    private fun buildPeopleIndex(): Map<Int, String> {
        val map = HashMap<Int, String>()
        for (item in allItems()) {
            val all = item.cast + item.crew + item.episodes.flatMap { it.guestStars + it.crew }
            for (p in all) {
                val pp = p.profilePath
                if (!pp.isNullOrBlank() && p.tmdbId != 0 && !map.containsKey(p.tmdbId)) map[p.tmdbId] = pp
            }
        }
        return map
    }

    suspend fun addOrUpdate(item: MediaItem) {
        val existing = get(item.id)
        var merged = preserveJsTags(item, existing)
        if (existing != null && existing.titlesByLang.isNotEmpty()) {
            merged = merged.copy(titlesByLang = existing.titlesByLang + item.titlesByLang)
        }
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
        // JS-tag color by name (lowercased — list() OR-filters tags case-insensitively, so a tag
        // defined "Open Movie" must still color an item tagged "open movie"). Presence of a color
        // marks a Jellystructure tag; JS tags sort first so the filter picker can group them.
        val tagColors = jsTagStore.all().associate { it.name.lowercase() to it.color }
        return MetaFacets(
            studios  = studioCounts.entries.sortedByDescending { it.value }.map { TrackFacetItem(it.key, it.value) },
            networks = networkCounts.entries.sortedByDescending { it.value }.map { TrackFacetItem(it.key, it.value) },
            genres   = genreCounts.entries.sortedByDescending { it.value }.map { TrackFacetItem(it.key, it.value) },
            tags     = tagCounts.entries
                .map { TrackFacetItem(it.key, it.value, tagColors[it.key.lowercase()]) }
                .sortedWith(compareBy({ it.color == null }, { -it.count })),
        )
    }

    /** Evaluate N condition stacks against the library in a single pass. Avoids N×allItems() calls. */
    fun countBatch(requests: List<Pair<MatchMode, List<Condition>>>): List<Int> {
        if (requests.isEmpty()) return emptyList()
        val all = allItems()
        return requests.map { (match, conditions) ->
            if (conditions.isEmpty()) all.size
            else all.count { item -> ConditionEvaluator.matches(item, match, conditions, emptySet()) }
        }
    }

    private fun upsertItem(item: MediaItem) {
        allItemsCache    = null   // Phase 88: invalidate decoded-library cache on any write
        peopleIndexCache = null   // Phase 78: invalidate the people→profilePath index on any write
        jellyfinIdIndex  = null   // invalidate the jellyfinId→MediaItem index on any write
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

data class TrackFacetItem(val value: String, val count: Int, val color: String? = null)
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
    (audioLangs.isEmpty() || audioLangs.any { lang -> LanguageResolver.sameLanguage(t.language, lang) }) &&
    (trackTitle == null || t.title?.contains(trackTitle, ignoreCase = true) == true) &&
    (audioCodec == null || t.codec.equals(audioCodec, ignoreCase = true)) &&
    (!untaggedAudio || t.language == null)
}
