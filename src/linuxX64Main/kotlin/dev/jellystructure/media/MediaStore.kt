package dev.jellystructure.media

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.log.Logger
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.MediaPage
import dev.jellystructure.model.TrackKind
import dev.jellystructure.model.recencyKey
import dev.jellystructure.nfo.NfoWriter
import dev.jellystructure.resolver.CertificationResolver
import dev.jellystructure.resolver.LanguageResolver
import dev.jellystructure.shared.tv.ConditionGroup
import dev.jellystructure.shared.tv.isLive
import dev.jellystructure.tv.ConditionEvaluator
import dev.jellystructure.tv.normalizeGuid
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import platform.posix.CLOCK_REALTIME
import platform.posix.clock_gettime
import platform.posix.timespec

/**
 * Phase 142 — true if a restricted user's [allowed] library set (already GUID-normalized; null =
 * unrestricted, e.g. admin/EnableAllFolders) permits this item. **Fail-closed**: an item with no
 * [MediaItem.libraryId] yet (not backfilled, or its library isn't mapped) is hidden from restricted
 * users — visible only once the backfill/scan actually resolves its library.
 */
fun MediaItem.visibleTo(allowed: Set<String>?): Boolean {
    if (allowed == null) return true
    return libraryId != null && normalizeGuid(libraryId) in allowed
}

/**
 * Phase 142 follow-up — Jellyfin Policy AllowedTags/BlockedTags, alongside library-level restriction.
 * [allowedTags]/[blockedTags] are already lowercased (see [DeviceData]); this item's own [MediaItem.tags]
 * are lowercased at comparison time so casing differences never let a blocked title through or hide an
 * allowed one. Mirrors Jellyfin's own semantics: BlockedTags always excludes; a non-empty AllowedTags is
 * an allow-list — only items carrying at least one of those tags pass.
 */
fun MediaItem.passesTagPolicy(allowedTags: Set<String>, blockedTags: Set<String>): Boolean {
    if (allowedTags.isEmpty() && blockedTags.isEmpty()) return true
    val ownTags = tags.map { it.lowercase() }
    if (blockedTags.isNotEmpty() && ownTags.any { it in blockedTags }) return false
    if (allowedTags.isNotEmpty() && ownTags.none { it in allowedTags }) return false
    return true
}

/** Phase 142 (+ follow-up) — the full device-facing visibility check: library access AND tag policy. */
fun MediaItem.visibleTo(device: DeviceData): Boolean =
    visibleTo(device.allowedLibraries) && passesTagPolicy(device.allowedTags, device.blockedTags)

class MediaStore(
    private val db: JellystructureDb,
    private val jsTagStore: JsTagStore,
    private val configStore: ConfigStore,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun ageRatingCascade(): List<String> = configStore.current.metadata.ageRatingCascade

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

    // Bug fix: allItemsCache had no synchronization at all — concurrent callers hitting a cold cache
    // (e.g. right after a backend restart, or the moment a write invalidates it) each independently
    // ran the full ~25MB DB scan + JSON decode, multiplying CPU load by however many requests arrived
    // in that window instead of sharing one result. Guards the cache-fill only; a warm-cache read never
    // touches this lock.
    private val allItemsMutex = Mutex()

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

    // Phase 133: a manually-picked/uploaded poster or backdrop must survive every automatic metadata
    // pull (scan/sync/re-pull), which otherwise unconditionally resets posterPath/backdropPath to TMDB's
    // current default. The guard itself lives in ArtworkLock.kt (Phase 151) and is applied at BOTH write
    // choke points below, mirroring preserveJsTags/mergeUserGenres — the Scanner never needs to know
    // about the lock itself.

    // Phase 108: JS-owned created/updated timestamps. createdAt is stamped once (first insert) and
    // never moves; updatedAt only bumps when the item's actual content changed — a scan that re-finds
    // an unchanged title must not make it look freshly edited. Compared via a "content signature" that
    // zeroes the fields which are expected to differ on every write regardless of real content change.
    private fun contentSignature(item: MediaItem): MediaItem =
        item.copy(scannedAt = 0, createdAt = null, updatedAt = null, jellyfinUpdatedAt = null)

    private fun stampTimestamps(fresh: MediaItem, old: MediaItem?): MediaItem {
        val now = nowMs() / 1000
        val createdAt = old?.createdAt ?: now
        val changed = old == null || contentSignature(old) != contentSignature(fresh)
        val updatedAt = if (changed) now else (old.updatedAt ?: now)
        return fresh.copy(createdAt = createdAt, updatedAt = updatedAt)
    }

    // Phase 108: an episode's createdAt is the JS "first-seen" timestamp — stamped once when it's not
    // present in the item's previous episode list (matched by season/episode, else filename), preserved
    // on every later re-scan. This is the sort key a series' "Newly Added" placement uses.
    private fun episodeKey(ep: dev.jellystructure.model.Episode): String =
        if (ep.seasonNumber != null && ep.episodeNumber != null) "${ep.seasonNumber}:${ep.episodeNumber}" else ep.filename

    private fun stampEpisodeCreatedAt(fresh: List<dev.jellystructure.model.Episode>, old: List<dev.jellystructure.model.Episode>?): List<dev.jellystructure.model.Episode> {
        val now = nowMs() / 1000
        val oldByKey = old?.associateBy { episodeKey(it) } ?: emptyMap()
        return fresh.map { ep -> ep.copy(createdAt = oldByKey[episodeKey(ep)]?.createdAt ?: ep.createdAt ?: now) }
    }

    suspend fun load() {
        val count = db.mediaQueries.count().executeAsOne()
        Logger.info("MediaStore: DB has $count media items")
        db.mediaQueries.allLastChecked().executeAsList().forEach { row ->
            lastCheckedMap[row.id] = row.last_checked
        }
        backfillSearchText()
        backfillTimestamps()
        backfillLibraryIds()
    }

    // Phase 142: one-time startup backfill for rows scanned before libraryId existed. Matches the
    // stored (local) item.path against config.libraries the same direction syncMovie/rescanMetadata
    // use — local-path first, falling back to jellyfinPath — NOT scanItem's jellyfinPath-first match
    // (scanItem matches the Jellyfin API's path; item.path here is always the local filesystem path).
    // Must run before Phase 142 filtering activates, or already-scanned items would be wrongly hidden.
    private suspend fun backfillLibraryIds() {
        val libraries = configStore.current.libraries
        val toBackfill = allItems().filter { it.libraryId == null }
        if (toBackfill.isEmpty()) return
        var backfilled = 0
        db.transaction {
            for (item in toBackfill) {
                val lib = libraries.firstOrNull { lib ->
                    val prefix = lib.localPath.ifBlank { lib.jellyfinPath }
                    prefix.isNotBlank() && item.path.startsWith(prefix)
                } ?: continue
                val libId = lib.jellyfinId.ifBlank { null } ?: continue
                upsertItemDbOnly(item.copy(libraryId = libId))
                backfilled++
            }
        }
        // One-time startup batch (not the live-scan hot path) — a single invalidate for the whole
        // batch is simpler than patching per row, and just as correct since nothing else can be
        // running concurrently against a fresh cache this early.
        if (backfilled > 0) {
            allItemsMutex.withLock { allItemsCache = null }
            Logger.info("MediaStore: backfilled libraryId for $backfilled rows", "media")
        }
    }

    // Phase 108: one-time startup backfill for rows written before createdAt/updatedAt existed.
    // Best-available history: item createdAt <- addedAt (Jellyfin DateCreated) ?: scannedAt; episode
    // createdAt <- the same item-level fallback (no per-episode history exists to do better).
    private suspend fun backfillTimestamps() {
        val toBackfill = allItems().filter { it.createdAt == null || it.episodes.any { ep -> ep.createdAt == null } }
        if (toBackfill.isEmpty()) return
        db.transaction {
            for (item in toBackfill) {
                val fallback = item.addedAt ?: item.scannedAt
                val backfilled = item.copy(
                    createdAt = item.createdAt ?: fallback,
                    updatedAt = item.updatedAt ?: item.scannedAt,
                    episodes = item.episodes.map { ep -> if (ep.createdAt == null) ep.copy(createdAt = fallback) else ep },
                )
                upsertItemDbOnly(backfilled)
            }
        }
        allItemsMutex.withLock { allItemsCache = null }
        Logger.info("MediaStore: backfilled createdAt/updatedAt for ${toBackfill.size} rows")
    }

    fun lastChecked(id: String): Long? = lastCheckedMap[id]

    @OptIn(ExperimentalForeignApi::class)
    fun nowMs(): Long = memScoped {
        val ts = alloc<timespec>()
        clock_gettime(CLOCK_REALTIME, ts.ptr)
        ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
    }

    private suspend fun backfillSearchText() {
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

    suspend fun update(rawItems: List<MediaItem>) {
        val newItems = disambiguateIds(rawItems)
        // Snapshot existing titlesByLang before deleting so a full rescan never erases
        // languages pulled in earlier scans.
        val existing: Map<String, MediaItem> = allItems().associateBy { it.id }
        // This is a wholesale replace (deleteAll + selective reinsert) — an item present in the old
        // cache but absent from newItems must NOT survive. upsertItem's incremental patch below only
        // adds/updates, never removes, so it can't express that; force a full invalidate here instead
        // and let the next allItems() rebuild fully and correctly from the post-replace DB state.
        allItemsMutex.withLock { allItemsCache = null }
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
                upsertItemDbOnly(merged)
            }
        }
    }

    suspend fun list(
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
        // Phase 140 — the blocks tree (query= param, or conditions=/match= migrated by the caller —
        // MediaRoutes.kt resolves whichever the request used into one ConditionGroup before calling in,
        // so this API is tree-native and single-shaped).
        query: ConditionGroup? = null,
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
            // Phase 117: one filter value per dashboard triage-breakdown row — same TriageDetection
            // predicates the breakdown counts against, so the numbers and the Library never disagree.
            when (filter) {
                "untagged" -> items = items.filter { TriageDetection.untaggedCount(it) > 0 }
                "cascade_mismatch" -> items = items.filter { TriageDetection.hasCascadeMismatch(it) }
                "multi_default" -> items = items.filter { TriageDetection.hasMultiDefault(it) }
                "language_mix" -> items = items.filter { it.languageMix }
                "missing_from_source" -> items = items.filter { it.missingFromSource }
                "missing_still" -> items = items.filter { TriageDetection.missingStillCount(it) > 0 }
                "duplicate" -> {
                    // Phase 122: hoisted out of the per-item lambda — it's a whole-library relationship,
                    // computed once per request, not per item.
                    val dupIds = TriageDetection.duplicateIds(items)
                    items = items.filter { it.id in dupIds }
                }
                // Bug fix (Ravilo auto-play-next loop): two files claiming the same S__E__ — see
                // TriageDetection.duplicateEpisodeCount / DuplicateEpisodes.
                "duplicate_episode" -> items = items.filter { TriageDetection.duplicateEpisodeCount(it) > 0 }
                "zero_audio" -> items = items.filter { TriageDetection.zeroAudioCount(it) > 0 }
                "cover_as_video" -> items = items.filter { TriageDetection.coverAsVideoCount(it) > 0 }  // Phase 144
                "segments_lowconf" -> items = items.filter { TriageDetection.lowConfidenceSegmentsCount(it) > 0 }  // Phase 150
                "no_segments" -> items = items.filter { TriageDetection.hasNoSegments(it) }  // Phase 150
                "unresolved_jellyfin_id" -> items = items.filter { TriageDetection.unresolvedJellyfinIdCount(it) > 0 }  // Phase 152/153
            }
            items
        }.let { items ->
            var result = items
            if (searchLower != null) {
                // Bug fix: search_text (title + originalTitle + every titlesByLang value, lowercased,
                // written at scan/edit time — see buildSearchText()) was already indexed
                // (media_search_text) but never actually queried; this used to re-lowercase and
                // .contains() all three fields per item, in Kotlin, on every search request. Push the
                // match down to the indexed SQL column instead.
                val matchedIds = db.mediaQueries.searchIds(searchLower).executeAsList().toHashSet()
                result = result.filter { it.id in matchedIds }
            }
            if (filter == "attention") {
                result = result.filter { item ->
                    item.issueCount > 0 || item.languageMix || item.hasMultiDefaultAudio() || !posterArtworkExists(item)
                }
            }
            // R74/Phase 140: when a query tree is provided, route through ConditionEvaluator so joins/
            // NOT/nesting evaluate correctly. The legacy per-facet AND block is kept as the fast path
            // when no query is passed.
            if (query != null && query.isLive()) {
                val cascade = ageRatingCascade()
                result = result.filter { item -> ConditionEvaluator.matches(item, query, heroIds, cascade) }
            } else {
                if (studios.isNotEmpty()) result = result.filter { item -> studios.any { s -> item.studio.equals(s, ignoreCase = true) || item.secondaryStudios.any { it.equals(s, ignoreCase = true) } } }
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
            else -> decoded.sortedByDescending { it.recencyKey() }
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
    suspend fun resolve(id: String): MediaItem? = get(id) ?: resolveByJellyfinId(id)

    /** O(1) lookup by Jellyfin UUID via a lazy-built in-memory index. */
    suspend fun resolveByJellyfinId(jellyfinId: String): MediaItem? {
        val index = jellyfinIdIndex ?: allItems()
            .associateBy { it.jellyfinId ?: "" }
            .filterKeys { it.isNotEmpty() }
            .also { jellyfinIdIndex = it }
        return index[jellyfinId]
    }

    /** Phase 111/R155 — resolves a Jellyfin id to what the remote-control `play_item` event needs:
     *  "movie" | "series" (both O(1) via [resolveByJellyfinId]) or "episode" (O(n) scan over series —
     *  no index exists for nested episode ids). Null if the id isn't in this library at all. */
    suspend fun resolvePlayTarget(jellyfinId: String): Pair<String, String?>? {
        resolveByJellyfinId(jellyfinId)?.let { item ->
            return (if (item.kind == MediaKind.TV_SHOW) "series" else "movie") to item.title
        }
        allItems().asSequence()
            .filter { it.kind == MediaKind.TV_SHOW }
            .forEach { series -> series.episodes.firstOrNull { it.jellyfinId == jellyfinId }?.let { return "episode" to null } }
        return null
    }

    /**
     * Bug fix: the Users & Devices "now playing" line was showing the raw Jellyfin item id (a hex
     * UUID) because nothing resolved it to a title. Human-readable label for [jellyfinId] — a movie/
     * series' own title, or "Series · S01E02 · Episode title" for an episode (same O(n) episode scan
     * as [resolvePlayTarget] — no index exists for nested episode ids). Null if the id isn't in this
     * library at all (e.g. a stale/deleted item), so callers can fall back gracefully.
     */
    suspend fun titleForJellyfinId(jellyfinId: String): String? {
        resolveByJellyfinId(jellyfinId)?.let { return it.title }
        for (series in allItems()) {
            if (series.kind != MediaKind.TV_SHOW) continue
            val ep = series.episodes.firstOrNull { it.jellyfinId == jellyfinId } ?: continue
            val code = if (ep.seasonNumber != null && ep.episodeNumber != null)
                "S${ep.seasonNumber.toString().padStart(2, '0')}E${ep.episodeNumber.toString().padStart(2, '0')}"
            else null
            return listOfNotNull(series.title, code, ep.title?.takeIf { it.isNotBlank() }).joinToString(" · ")
        }
        return null
    }

    suspend fun allItems(): List<MediaItem> {
        // Fast, unlocked path: once warm, every read is a plain field access — the lock below is only
        // ever taken while the cache is cold.
        allItemsCache?.let { return it }
        return allItemsMutex.withLock {
            // Double-checked: another caller may have filled it while this one waited for the lock.
            allItemsCache?.let { return@withLock it }
            db.mediaQueries.getAll().executeAsList().mapNotNull { blob ->
                runCatching { json.decodeFromString(MediaItem.serializer(), blob) }.getOrNull()
            }.also { allItemsCache = it }
        }
    }

    /** Items that are present in Jellyfin — `missingFromSource` rows excluded. Use this in Ravilo
     *  catalog/browse paths so stale items (removed/re-added in Jellyfin) never surface to viewers. */
    suspend fun liveItems(): List<MediaItem> = allItems().filter { !it.missingFromSource }

    /** Items visible to a device with [allowed] library access ([DeviceData.allowedLibraries] —
     *  already GUID-normalized; null = unrestricted). Compose with [liveItems] at every Ravilo
     *  device-facing read path (Phase 142) — restricted users only, never the admin surfaces. */
    suspend fun liveItems(allowed: Set<String>?): List<MediaItem> = liveItems().filter { it.visibleTo(allowed) }

    /** Phase 142 + follow-up — the full per-device filter (library access AND tag policy). Prefer this
     *  overload at every Ravilo device-facing read path; the [allowed]-only overload above is kept for
     *  the narrower library-only check DetailService needs per-item. */
    suspend fun liveItems(device: DeviceData): List<MediaItem> = liveItems().filter { it.visibleTo(device) }

    /**
     * R100: items sharing any genre with [source], newest first, capped at [limit] — gathered from the
     * source's genre buckets only, not a full-library scan. Preserves the prior semantics exactly
     * (any shared genre, exclude self, sort by scannedAt desc). Dedup is by id (cheap) rather than by
     * the deep data-class equality of MediaItem.
     */
    suspend fun relatedByGenre(source: MediaItem, limit: Int): List<MediaItem> {
        if (source.genres.isEmpty()) return emptyList()
        val index = genreIndexCache ?: buildGenreIndex().also { genreIndexCache = it }
        val byId = LinkedHashMap<String, MediaItem>()
        for (g in source.genres) {
            val bucket = index[g] ?: continue
            for (item in bucket) if (item.id != source.id && item.id !in byId) byId[item.id] = item
        }
        return byId.values.sortedByDescending { it.recencyKey() }.take(limit)
    }

    private suspend fun buildGenreIndex(): Map<String, List<MediaItem>> {
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
    suspend fun personProfilePath(tmdbId: Int): String? {
        val index = peopleIndexCache ?: buildPeopleIndex().also { peopleIndexCache = it }
        return index[tmdbId]
    }

    private suspend fun buildPeopleIndex(): Map<Int, String> {
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
        // If other rows already hold the same Jellyfin ID under different slugs (e.g. the year was
        // unknown on the first scan so the id was "girl-taken", then TMDB returned 2026 and the
        // scanner now produces "girl-taken-2026" — or two unlocked scan workers raced on the same
        // jellyfinId), delete ALL of them, not just one, so a duplicate can't survive a rescan
        // (Phase 122; the previous single-victim deletion was last-wins and could never see more
        // than one twin at a time).
        val jellyfinId = item.jellyfinId
        val twins = if (jellyfinId != null) allItems().filter { it.jellyfinId == jellyfinId && it.id != item.id } else emptyList()
        val stale = twins.firstOrNull()
        if (twins.isNotEmpty()) {
            // A real deletion (not the common upsertItem patch path below) — full invalidate.
            allItemsMutex.withLock { allItemsCache = null }
            peopleIndexCache = null
            jellyfinIdIndex  = null
            genreIndexCache  = null
            for (t in twins) {
                db.mediaQueries.deleteById(t.id)
                println("[INFO] MediaStore: removed stale duplicate ${t.id} → replaced by ${item.id}")
            }
        }
        // Phase 108: the item's real predecessor is `stale` across an id-rename (existing is null in
        // that case, keyed under the old id), otherwise `existing` — so createdAt/episode createdAt
        // survive a slug change instead of resetting.
        val old = stale ?: existing
        var merged = preserveJsTags(item, existing)
        // Phase 151: key the artwork lock off `old`, not `existing`, so a slug rename (id change) keeps
        // the operator's locked poster instead of silently falling back to the fresh TMDB default.
        merged = preserveLockedArtwork(merged, old)
        if (existing != null && existing.titlesByLang.isNotEmpty()) {
            merged = merged.copy(titlesByLang = existing.titlesByLang + item.titlesByLang)
        }
        merged = merged.copy(episodes = stampEpisodeCreatedAt(merged.episodes, old?.episodes))
        merged = stampTimestamps(merged, old)
        // Phase 153: Scanner never sets these — a fresh scan left them null every cycle, which broke
        // write_nfo's content-hash compare (a real hash can never equal null) and sync_jellyfin's
        // staleness gate (nfoWrittenAt > jfSyncedAt, comparing against a just-reset baseline). Carry
        // them forward from `old` exactly like the other drift/lock state above.
        merged = merged.copy(nfoWrittenAt = old?.nfoWrittenAt, nfoHash = old?.nfoHash, jfSyncedAt = old?.jfSyncedAt)
        upsertItem(merged)
    }

    /**
     * Phase 151: [respectArtworkLock] applies the same manual-artwork guard `addOrUpdate` uses. It
     * defaults to true because most `updateOne` callers pass an item derived from current store state
     * (where the guard is a no-op), while the ones that matter — `POST /{id}/sync`,
     * `POST /{id}/repull-jellyfin` and `pushToJellyfin` — persist a *freshly scanned* item whose
     * posterPath/backdropPath were just reset to TMDB's default. Only the explicit artwork routes (an
     * operator picking/uploading a new image, which deliberately changes the locked value) pass false.
     */
    suspend fun updateOne(item: MediaItem, respectArtworkLock: Boolean = true) {
        val existing = get(item.id)
        var merged = if (existing != null && existing.titlesByLang.isNotEmpty()) {
            item.copy(titlesByLang = existing.titlesByLang + item.titlesByLang)
        } else item
        if (respectArtworkLock) merged = preserveLockedArtwork(merged, existing)
        merged = merged.copy(episodes = stampEpisodeCreatedAt(merged.episodes, existing?.episodes))
        merged = stampTimestamps(merged, existing)
        upsertItem(merged)
    }

    /**
     * Permanently removes a catalog item that's no longer in Jellyfin — the Phase 95 triage's
     * "review & remove if intended" action, finally implemented. Deliberately refuses to delete an
     * item still present in Jellyfin: this is a cleanup valve for stale/orphaned rows, not a general
     * delete-anything operation. The item will only reappear on a future scan if Jellyfin has it again.
     * Returns null if the id doesn't resolve at all, false if it resolves but isn't missing, true on
     * success.
     */
    suspend fun deleteItem(id: String): Boolean? {
        val item = resolve(id) ?: return null
        if (!item.missingFromSource) return false
        // A real deletion (not the common upsertItem patch path) — full invalidate.
        allItemsMutex.withLock { allItemsCache = null }
        peopleIndexCache = null
        jellyfinIdIndex  = null
        genreIndexCache  = null
        libraryVersion++
        lastCheckedMap.remove(item.id)
        db.mediaQueries.deleteById(item.id)
        return true
    }

    fun movieCount(): Int = db.mediaQueries.countByKind("MOVIE").executeAsOne().toInt()

    fun tvShowCount(): Int = db.mediaQueries.countByKind("TV_SHOW").executeAsOne().toInt()

    fun tvEpisodeCount(): Int = db.mediaQueries.sumEpisodeCount().executeAsOne().toInt()

    fun totalIssueCount(): Int = db.mediaQueries.sumIssueCount().executeAsOne().toInt()

    suspend fun nfoCoveredCount(): Int {
        val ver = libraryVersion
        nfoCoveredCache?.let { (v, c) -> if (v == ver) return c }
        return allItems().count { NfoWriter.exists(it) }.also { nfoCoveredCache = Pair(ver, it) }
    }

    suspend fun nfoCoveragePercent(): Int {
        val total = db.mediaQueries.count().executeAsOne().toInt()
        if (total == 0) return 0
        return (nfoCoveredCount() * 100) / total
    }

    suspend fun trackFacets(): TrackFacets {
        val ver = libraryVersion
        trackFacetsCache?.let { (v, f) -> if (v == ver) return f }
        return buildTrackFacets().also { trackFacetsCache = Pair(ver, it) }
    }

    private suspend fun buildTrackFacets(): TrackFacets = buildTrackFacetsFrom(allItems())

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

    suspend fun metaFacets(): MetaFacets {
        val ver = libraryVersion
        metaFacetsCache?.let { (v, f) -> if (v == ver) return f }
        return buildMetaFacets().also { metaFacetsCache = Pair(ver, it) }
    }

    private suspend fun buildMetaFacets(): MetaFacets = buildMetaFacetsFrom(allItems())

    private fun buildMetaFacetsFrom(items: List<MediaItem>): MetaFacets {
        val studioCounts  = mutableMapOf<String, Int>()
        val networkCounts = mutableMapOf<String, Int>()
        val genreCounts   = mutableMapOf<String, Int>()
        val tagCounts     = mutableMapOf<String, Int>()
        val ageRatingCounts = mutableMapOf<String, Int>()
        val cascade = ageRatingCascade()
        // R190: keyed by tmdbId (a person can appear under slightly different name spellings across
        // sources; the id is the one thing guaranteed stable) — name is whichever credit we saw first.
        val castCrewCounts = mutableMapOf<Int, Int>()
        val castCrewNames = mutableMapOf<Int, String>()
        for (item in items) {
            (listOfNotNull(item.studio) + item.secondaryStudios).distinct().forEach { s -> studioCounts[s] = (studioCounts[s] ?: 0) + 1 }
            item.network?.let { n -> networkCounts[n] = (networkCounts[n] ?: 0) + 1 }
            item.genres.forEach { g -> genreCounts[g] = (genreCounts[g] ?: 0) + 1 }
            item.tags.forEach   { t -> tagCounts[t]   = (tagCounts[t]   ?: 0) + 1 }
            CertificationResolver.resolve(cascade, item.certifications)?.let { cert ->
                ageRatingCounts[cert.code] = (ageRatingCounts[cert.code] ?: 0) + 1
            }
            (item.cast + item.crew).distinctBy { it.tmdbId }.forEach { p ->
                castCrewCounts[p.tmdbId] = (castCrewCounts[p.tmdbId] ?: 0) + 1
                if (p.tmdbId !in castCrewNames) castCrewNames[p.tmdbId] = p.name
            }
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
            // Phase 106: ordered by maturity tier then count, so the picker reads like a rating scale.
            ageRatings = ageRatingCounts.entries
                .sortedWith(compareBy({ CertificationResolver.tierFor(it.key) }, { -it.value }))
                .map { TrackFacetItem(it.key, it.value) },
            // R190: capped — an admin workbench picker, not a full people index; a library's most-
            // credited 500 people covers every realistic "build a channel/row around this actor" use.
            castCrew = castCrewCounts.entries.sortedByDescending { it.value }.take(500)
                .map { TrackFacetItem(it.key.toString(), it.value, label = castCrewNames[it.key]) },
        )
    }

    /** Evaluate N condition stacks against the library in a single pass. Avoids N×allItems() calls. */
    /** Phase 140 — one entry per requested query tree (channel/row/block badges); a caller with a
     *  legacy flat match/conditions request resolves it to a tree first (`migrateFlatQuery`). */
    suspend fun countBatch(requests: List<ConditionGroup>): List<Int> {
        if (requests.isEmpty()) return emptyList()
        val all = liveItems()  // batch-count is always Ravilo-config context
        val cascade = ageRatingCascade()
        return requests.map { query ->
            if (!query.isLive()) all.size
            else all.count { item -> ConditionEvaluator.matches(item, query, emptySet(), cascade) }
        }
    }

    /** R127: facet value counts narrowed to the items matching [query] (e.g. a channel's filter) —
     *  meta (studio/network/genre/tag) + track (audio language/codec/title), each count-sorted, only
     *  values present in the narrowed set. Uncached: computed on demand when the workbench opens in
     *  scope. Phase 140: [query] is the blocks tree; a caller with the legacy flat shape resolves it
     *  first (`migrateFlatQuery`). */
    suspend fun facetsNarrowed(query: ConditionGroup): Pair<MetaFacets, TrackFacets> {
        val items = if (!query.isLive()) liveItems()  // workbench narrowed-facets = Ravilo-config context
            else liveItems().filter { ConditionEvaluator.matches(it, query, emptySet(), ageRatingCascade()) }
        return buildMetaFacetsFrom(items) to buildTrackFacetsFrom(items)
    }

    // DB write + non-cache bookkeeping only — split out so the one-time startup backfills
    // (backfillLibraryIds/backfillTimestamps) and the bulk-replace update() can call this from inside a
    // (non-suspend) db.transaction {} block. Those three callers invalidate allItemsCache themselves,
    // once for the whole batch, rather than patching per item — they're either one-time startup work or
    // an already-wholesale replace, not the hot per-item path upsertItem's cache patch exists for.
    private fun upsertItemDbOnly(item: MediaItem) {
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
            has_segments = if (TriageDetection.hasAnySegments(item)) 1L else 0L,
            scanned_at = item.scannedAt,
            tmdb_id = item.tmdbId?.toLong(),
            poster_path = item.posterPath,
            episode_count = item.episodes.size.toLong(),
            search_text = buildSearchText(item),
            last_checked = now,
        )
    }

    private suspend fun upsertItem(item: MediaItem) {
        // Bug fix: this used to null the whole cache on every single write. During an active scan,
        // addOrUpdate fires once per scanned item from potentially dozens of concurrent workers — the
        // cache was being invalidated multiple times a second for the run's entire duration, so any
        // Library-page load or filter-workbench query in that window always paid the full library
        // decode, never getting a warm hit (a real symptom: the Library page and its filter workbench
        // both went from instant to multi-second the moment a scan started). Patch the one changed item
        // into the already-decoded list in place instead — the cache now stays warm through an entire
        // scan. Only meaningful when the cache is already warm (a cold/null cache has nothing to patch,
        // and correctly stays null until the next allItems() rebuilds it in full).
        allItemsMutex.withLock {
            allItemsCache = allItemsCache?.let { cache ->
                val idx = cache.indexOfFirst { it.id == item.id }
                if (idx >= 0) cache.toMutableList().also { it[idx] = item } else cache + item
            }
        }
        peopleIndexCache = null
        jellyfinIdIndex  = null
        genreIndexCache  = null
        upsertItemDbOnly(item)
    }

    private fun buildSearchText(item: MediaItem): String = buildString {
        append(item.title.lowercase())
        item.originalTitle?.lowercase()?.let { if (it != item.title.lowercase()) { append(' '); append(it) } }
        for (t in item.titlesByLang.values) { append(' '); append(t.lowercase()) }
    }.trim()
}

// R190: [label] is set only for facets whose stored value isn't its own display text — cast_crew's
// value is a tmdbId string, label is the person's name.
data class TrackFacetItem(val value: String, val count: Int, val color: String? = null, val label: String? = null)
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
    val ageRatings: List<TrackFacetItem> = emptyList(),
    val castCrew: List<TrackFacetItem> = emptyList(),
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
