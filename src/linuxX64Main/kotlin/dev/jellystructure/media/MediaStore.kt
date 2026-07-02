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
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.serialization.json.Json
import platform.posix.CLOCK_REALTIME
import platform.posix.clock_gettime
import platform.posix.timespec

class MediaStore(private val db: JellystructureDb, private val jsTagStore: JsTagStore) {
    private val json = Json { ignoreUnknownKeys = true }

    // Phase 78: cached tmdbPersonId -> profilePath index for the /api/people/{id}/image endpoint,
    // so a cache miss is O(1) instead of deserialising the whole library per request. Invalidated
    // on any write (upsertItem). null = not built yet.
    private var peopleIndexCache: Map<Int, String>? = null

    // jellyfinId → MediaItem index so TV detail routes avoid a full table scan + JSON deserialise
    // on every page open. Built on first use, invalidated on any write.
    private var jellyfinIdIndex: Map<String, MediaItem>? = null

    // R100: genre → items index so a detail page's "related" list is gathered from just the source's
    // genre buckets instead of scanning the whole library per open. Built on first use, invalidated on
    // any write — same pattern as jellyfinIdIndex.
    private var genreIndexCache: Map<String, List<MediaItem>>? = null

    // Phase 88: decoded-library cache — skip JSON deserialisation on every read path.
    // Invalidated synchronously on every write (upsertItem). Rebuilt lazily on next allItems() call.
    private var allItemsCache: List<MediaItem>? = null

    // Phase R86: monotonic counter incremented on every write. Used by HomeFeedService as a cache
    // invalidation key — if libraryVersion hasn't changed, the home feed is still valid.
    var libraryVersion: Long = 0
        private set

    // Phase 89: memoize computed results keyed on libraryVersion so repeated reads between writes are O(1).
    // The Pair<Long, T> carries the version the result was built against; a version change auto-invalidates.
    private var trackFacetsCache:  Pair<Long, TrackFacets>? = null
    private var metaFacetsCache:   Pair<Long, MetaFacets>? = null
    private var nfoCoveredCache:   Pair<Long, Int>? = null

    // Phase 91: per-item last_checked timestamps (item.id → epoch ms). Loaded from DB on startup,
    // updated on upsertItem. Used by the pipeline freshness policy to skip recently-checked items.
    private val lastCheckedMap: MutableMap<String, Long> = mutableMapOf()

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
        db.mediaQueries.allLastChecked().executeAsList().forEach { row ->
            row.last_checked?.let { ts -> lastCheckedMap[row.id] = ts }
        }
        backfillSearchText()
    }

    fun lastChecked(id: String): Long? = lastCheckedMap[id]

    @OptIn(ExperimentalForeignApi::class)
    fun nowMs(): Long = memScoped {
        val ts = alloc<timespec>()
        clock_gettime(CLOCK_REALTIME, ts.ptr)
        ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
    }

    private fun backfillSearchText() {
        val emptyCount = db.mediaQueries.countEmptySearchText().executeAsOne()
        if (emptyCount == 0L) return
        val items = allItems()
        db.transaction {
            for (item in items) {
                val st = buildSearchText(item)
                if (st.isNotEmpty()) db.mediaQueries.updateSearchText(search_text = st, id = item.id)
            }
        }
        println("[INFO] MediaStore: backfilled search_text for $emptyCount rows")
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

    /**
     * Phase 95: non-destructive scan reconciliation. The scanner never deletes — instead, any catalog item
     * whose Jellyfin id is no longer in [presentJellyfinIds] is **flagged** `missingFromSource` (so it
     * surfaces in Triage for the admin to act on); items that re-appear have the flag cleared. Local-only
     * items (no jellyfinId) are left untouched. Returns the items NEWLY flagged missing (for logging).
     */
    suspend fun flagMissingFromSource(presentJellyfinIds: Set<String>, nowSec: Long): List<MediaItem> {
        val newlyMissing = mutableListOf<MediaItem>()
        for (item in allItems()) {
            val jf = item.jellyfinId ?: continue
            val missing = jf !in presentJellyfinIds
            if (missing && !item.missingFromSource) {
                addOrUpdate(item.copy(missingFromSource = true, missingSince = nowSec))
                newlyMissing += item
            } else if (!missing && item.missingFromSource) {
                addOrUpdate(item.copy(missingFromSource = false, missingSince = null))
            }
        }
        return newlyMissing
    }

    fun deleteMissing(presentIds: Set<String>) {
        val allIds = db.mediaQueries.allIds().executeAsList()
        val toDelete = allIds.filter { it !in presentIds }
        if (toDelete.isNotEmpty()) {
            db.transaction {
                toDelete.forEach { id ->
                    allItemsCache    = null
                    peopleIndexCache = null
                    jellyfinIdIndex  = null
                    genreIndexCache  = null
                    libraryVersion++
                    db.mediaQueries.deleteById(id)
                }
            }
            println("[INFO] MediaStore: deleted ${toDelete.size} items no longer in scan")
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
                val gainedEpisodes = old != null && item.episodes.size > old.episodes.size
                if (old != null && !gainedEpisodes) {
                    merged = merged.copy(scannedAt = old.scannedAt)
                }
                // addedAt = the real library date-added (Jellyfin DateCreated), with two rules on top:
                // a series that GAINS an episode counts as newly added again (bump to now), and the
                // value never moves backward across scans, so the bump persists until something newer
                // arrives. (epoch seconds; nowMs() is ms.)
                val freshAdded = if (gainedEpisodes) nowMs() / 1000 else (item.addedAt ?: old?.addedAt)
                merged = merged.copy(addedAt = listOfNotNull(freshAdded, old?.addedAt).maxOrNull())
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
        allowedIds: Set<String>? = null,  // Phase 98: tracker filter — null = no restriction
        excludeMissing: Boolean = false,  // true in viewer/Ravilo-config context: hide missingFromSource rows
    ): MediaPage {
        val searchLower = search?.lowercase()?.takeIf { it.isNotBlank() }

        // Phase 88: read through the decoded-library cache; apply kind + missing-artwork pre-filters
        // in memory (previously done in SQL by listFiltered, but allItems() is now free on cache hit).
        val decoded = run {
            var items = if (excludeMissing) liveItems() else allItems()
            if (kind != null) items = items.filter { it.kind == kind }
            // R122/R123: "missing artwork" = no real poster.jpg on disk (the Jellyfin poster). The only
            // artwork signal we track — we care about what's on disk, not the TMDB posterPath metadata.
            if (filter == "missing_artwork") items = items.filter { !posterArtworkExists(it) }
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
                    item.issueCount > 0 || item.languageMix || item.hasMultiDefaultAudio() || !posterArtworkExists(item)
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
            if (allowedIds != null) result = result.filter { it.id in allowedIds }
            result
        }

        val sorted = when (sort) {
            "title" -> decoded.sortedBy { it.title.lowercase() }
            "year" -> decoded.sortedByDescending { it.year ?: 0 }
            // "recently added" (default): the real Jellyfin date-added, falling back to scan time for
            // items not yet re-scanned. scannedAt alone sorts by scan order, not add order.
            else -> decoded.sortedByDescending { it.addedAt ?: it.scannedAt }
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

    /** Items that are present in Jellyfin — `missingFromSource` rows excluded. Use this in Ravilo
     *  catalog/browse paths so stale items (removed/re-added in Jellyfin) never surface to viewers. */
    fun liveItems(): List<MediaItem> = allItems().filter { !it.missingFromSource }

    /**
     * R100: items sharing any genre with [source], newest first, capped at [limit] — gathered from the
     * source's genre buckets only, not a full-library scan. Preserves the prior semantics exactly
     * (any shared genre, exclude self, sort by scannedAt desc). Dedup is by id (cheap) rather than by
     * the deep data-class equality of MediaItem.
     */
    fun relatedByGenre(source: MediaItem, limit: Int): List<MediaItem> {
        if (source.genres.isEmpty()) return emptyList()
        val index = genreIndexCache ?: buildGenreIndex().also { genreIndexCache = it }
        val byId = LinkedHashMap<String, MediaItem>()
        for (g in source.genres) {
            val bucket = index[g] ?: continue
            for (item in bucket) if (item.id != source.id && item.id !in byId) byId[item.id] = item
        }
        return byId.values.sortedByDescending { it.addedAt ?: it.scannedAt }.take(limit)
    }

    private fun buildGenreIndex(): Map<String, List<MediaItem>> {
        val map = HashMap<String, MutableList<MediaItem>>()
        for (item in allItems()) {
            for (g in item.genres) map.getOrPut(g) { mutableListOf() }.add(item)
        }
        return map
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
        // If another row already holds the same Jellyfin ID under a different slug (e.g. the year
        // was unknown on the first scan so the id was "girl-taken", then TMDB returned 2026 and
        // the scanner now produces "girl-missing-2026"), delete the stale row so it doesn't show up
        // as a duplicate in the library.
        val jellyfinId = item.jellyfinId
        if (jellyfinId != null) {
            val stale = resolveByJellyfinId(jellyfinId)
            if (stale != null && stale.id != item.id) {
                allItemsCache    = null
                peopleIndexCache = null
                jellyfinIdIndex  = null
                genreIndexCache  = null
                db.mediaQueries.deleteById(stale.id)
                println("[INFO] MediaStore: removed stale duplicate ${stale.id} → replaced by ${item.id}")
            }
        }
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

    fun nfoCoveredCount(): Int {
        val ver = libraryVersion
        nfoCoveredCache?.let { (v, c) -> if (v == ver) return c }
        return allItems().count { NfoWriter.exists(it) }.also { nfoCoveredCache = Pair(ver, it) }
    }

    fun nfoCoveragePercent(): Int {
        val total = db.mediaQueries.count().executeAsOne().toInt()
        if (total == 0) return 0
        return (nfoCoveredCount() * 100) / total
    }

    fun trackFacets(): TrackFacets {
        val ver = libraryVersion
        trackFacetsCache?.let { (v, f) -> if (v == ver) return f }
        return buildTrackFacets().also { trackFacetsCache = Pair(ver, it) }
    }

    private fun buildTrackFacets(): TrackFacets = buildTrackFacetsFrom(allItems())

    private fun buildTrackFacetsFrom(items: List<MediaItem>): TrackFacets {
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
        val ver = libraryVersion
        metaFacetsCache?.let { (v, f) -> if (v == ver) return f }
        return buildMetaFacets().also { metaFacetsCache = Pair(ver, it) }
    }

    private fun buildMetaFacets(): MetaFacets = buildMetaFacetsFrom(allItems())

    private fun buildMetaFacetsFrom(items: List<MediaItem>): MetaFacets {
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
        val all = liveItems()  // batch-count is always Ravilo-config context
        return requests.map { (match, conditions) ->
            if (conditions.isEmpty()) all.size
            else all.count { item -> ConditionEvaluator.matches(item, match, conditions, emptySet()) }
        }
    }

    /** R127: facet value counts narrowed to the items matching [conditions] (e.g. a channel's filter) —
     *  meta (studio/network/genre/tag) + track (audio language/codec/title), each count-sorted, only
     *  values present in the narrowed set. Uncached: computed on demand when the workbench opens in scope. */
    fun facetsNarrowed(match: MatchMode, conditions: List<Condition>): Pair<MetaFacets, TrackFacets> {
        val items = if (conditions.isEmpty()) liveItems()  // workbench narrowed-facets = Ravilo-config context
            else liveItems().filter { ConditionEvaluator.matches(it, match, conditions, emptySet()) }
        return buildMetaFacetsFrom(items) to buildTrackFacetsFrom(items)
    }

    private fun upsertItem(item: MediaItem) {
        allItemsCache    = null
        peopleIndexCache = null
        jellyfinIdIndex  = null
        genreIndexCache  = null
        libraryVersion++
        val now = nowMs()
        lastCheckedMap[item.id] = now
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
            search_text = buildSearchText(item),
            last_checked = now,
        )
    }

    private fun buildSearchText(item: MediaItem): String = buildString {
        append(item.title.lowercase())
        item.originalTitle?.lowercase()?.let { if (it != item.title.lowercase()) { append(' '); append(it) } }
        for (t in item.titlesByLang.values) { append(' '); append(t.lowercase()) }
    }.trim()
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
