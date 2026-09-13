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
import dev.jellystructure.ops.SpinLock
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
import kotlin.concurrent.AtomicLong
import kotlin.concurrent.AtomicReference
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

    // Phase 163 — same `db`, so a thin wrapper here rather than threading a second constructor param
    // through MediaStore's one call site.
    private val segmentStore = MediaSegmentStore(db)

    // Phase 78: cached tmdbPersonId -> profilePath index for the /api/people/{id}/image endpoint,
    // so a cache miss is O(1) instead of deserialising the whole library per request. Invalidated
    // on any write (upsertItem). null = not built yet.
    // Phase 182 (FR-182-2): AtomicReference, not a bare `var` — these fields are read/invalidated from
    // every concurrent scan-pool thread (upsertItem/addOrUpdate/deleteItem all run there), and a plain
    // reference field has no cross-thread visibility guarantee on Kotlin/Native. The check-then-build
    // read below can still race two threads into computing the same index redundantly (benign — both
    // compute the same correct answer from the same underlying data); what this fixes is a torn/stale
    // read of the reference itself, not that redundant-build race.
    private val peopleIndexCache = AtomicReference<Map<Int, String>?>(null)

    // jellyfinId → MediaItem index so TV detail routes avoid a full table scan + JSON deserialise
    // on every page open. Built on first use, invalidated on any write.
    private val jellyfinIdIndex = AtomicReference<Map<String, MediaItem>?>(null)

    // R100: genre → items index so a detail page's "related" list is gathered from just the source's
    // genre buckets instead of scanning the whole library per open. Built on first use, invalidated on
    // any write — same pattern as jellyfinIdIndex.
    private val genreIndexCache = AtomicReference<Map<String, List<MediaItem>>?>(null)

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
    // Phase 182 (FR-182-2): AtomicLong, not a bare `var` + `libraryVersion++` — the increment used to be
    // a non-atomic read-modify-write executed from every scan thread via upsertItemDbOnly, so concurrent
    // writers could race and lose an increment. A lost increment here is a real correctness bug, not just
    // a benign redundant rebuild: HomeFeedService trusts this number as a cache-invalidation key, so a
    // lost bump can serve a stale home feed after a real write.
    private val libraryVersionAtomic = AtomicLong(0L)
    val libraryVersion: Long get() = libraryVersionAtomic.value

    // Phase 204 (FR-204-2) — a coarser sibling of [libraryVersion] for HomeFeedService's feed/Continue
    // caches specifically. [libraryVersion] bumps on every write regardless of content (correctly, for
    // the facet/Triage consumers below that key on it); those caches were keying on it too, so a poster
    // path written for one title discarded the assembled Home feed for every user. This bumps only when
    // [stampTimestamps]' own already-computed `changed` (content-signature) comparison says a write
    // actually changed something — the "free" option from that phase's three: the comparison already
    // runs on every write to decide `updatedAt`, so gating this on its result costs nothing new. It is
    // coarser than a MediaCard/FocusDetailFacts field list (an IMDb-rating-only write still bumps it,
    // same as libraryVersion would), which is deliberate: a field-list signature drifts out of sync with
    // whatever Phase 202 (or the next phase) adds to the payload, silently, with no test to catch it —
    // this can't, because it reuses the one piece of "did the content change at all" logic MediaStore
    // already maintains for its own reasons.
    private val feedVersionAtomic = AtomicLong(0L)
    val feedVersion: Long get() = feedVersionAtomic.value

    // Phase 89: memoize computed results keyed on libraryVersion so repeated reads between writes are O(1).
    // The Pair<Long, T> carries the version the result was built against; a version change auto-invalidates.
    // Phase 182 (FR-182-2): AtomicReference — same cross-thread-visibility reasoning as the index caches above.
    private val trackFacetsCache = AtomicReference<Pair<Long, TrackFacets>?>(null)
    private val metaFacetsCache  = AtomicReference<Pair<Long, MetaFacets>?>(null)
    private val nfoCoveredCache  = AtomicReference<Pair<Long, Int>?>(null)

    // Phase 91: per-item last-examined timestamps (item.id → epoch ms). Loaded from DB on startup.
    // Used by the pipeline freshness policy to skip recently-examined items.
    //
    // Phase 196 — **set only by a completed examination of the title's files on disk**, never by a
    // metadata write. It was `last_checked` and was stamped unconditionally inside upsertItemDbOnly,
    // so every unrelated writer (write_nfo, sync_jellyfin, artwork fetch, segment writes, Sonarr
    // enrichment, a manual edit) silently deferred the next real scan of a title nobody had looked at.
    // Measured on production 2026-09-06: one write_nfo pass reset 310 rows within two seconds, 89 of
    // 516 rows sat over an hour ahead of their own scannedAt, and Klovn went 31 hours unexamined while
    // reporting a freshness clock minutes old — with a new episode on disk the whole time. Worse, the
    // Sonarr enrichment that writes `sonarrNextAiringDate` — the field that makes Phase 181's
    // isActivelyAiring true — was itself resetting the clock that the resulting `daily` tier is
    // compared against, so the fix aimed at airing shows could never fire for them.
    // Phase 182 (FR-182-2): this is the one cache here that is genuinely MUTATED in place (not
    // replaced wholesale like the Map caches above), from every scan thread via the non-suspend
    // upsertItemDbOnly (called synchronously inside SQLDelight's db.transaction{} — a kotlinx.coroutines
    // Mutex cannot guard it, its withLock is suspend-only). Concurrent unsynchronized `put`s into a plain
    // MutableMap is a real data race that can corrupt the map's internal structure; this was the leading
    // hypothesis for the reported "4 items stuck on 4 scan threads, forever, uncancellably" incident —
    // see phase-182's §2.4. Guarded by [lastExaminedLock], a SpinLock so it works from upsertItemDbOnly's
    // non-suspend context too.
    private val lastExaminedMap: MutableMap<String, Long> = mutableMapOf()
    private val lastExaminedLock = SpinLock()

    // Jellystructure-defined tags (those in the JS-tag store) always survive a re-scan, which
    // otherwise replaces an item's tags with the fresh Jellyfin set (constitution invariant #6). The
    // guard itself is [preserveJsTags] in JsTagLock.kt (Phase 199 — extracted so it can be unit-tested,
    // same reason as the artwork/TMDB-match/metadata-language guards below).

    // Phase 133: a manually-picked/uploaded poster or backdrop must survive every automatic metadata
    // pull (scan/sync/re-pull), which otherwise unconditionally resets posterPath/backdropPath to TMDB's
    // current default. The guard itself lives in ArtworkLock.kt (Phase 151).
    //
    // Phase 199 — the real per-choke-point coverage (corrected; the previous version of this comment
    // claimed the artwork/JS-tag guards mirrored each other's coverage and they never did):
    //
    //             | update() | addOrUpdate() | updateOne() |
    //   ----------|----------|---------------|-------------|
    //   jsTags     |    ✓    |       ✓       |   ✓ (opt)   |
    //   artwork    |    —    |       ✓       |   ✓ (opt)   |
    //   tmdbMatch  |    —    |       ✓       |   ✓ (opt)   |
    //   metaLang   |    —    |       ✓       |   ✓ (opt)   |
    //
    // `update()` keeps jsTags (it has a real per-id predecessor: `existing[item.id]`, since it's a
    // wholesale replace-by-id, not the twin/slug-matching `addOrUpdate` does) but has no locked-artwork/
    // TMDB-match/metadata-language guard — its only caller is a full library wipe
    // (`MediaRoutes.kt`, `store.update(emptyList())`). Every guard below is keyed off the item's
    // **predecessor**, not the row under its own id: across a slug rename (id change) the predecessor is
    // `old` (the stale duplicate under the previous id when one exists), not `existing` — `existing` is
    // `null` on exactly that rename, and a guard keyed on it preserves nothing (Phase 199 / FR-199-1).

    // Phase 108: JS-owned created/updated timestamps. createdAt is stamped once (first insert) and
    // never moves; updatedAt only bumps when the item's actual content changed — a scan that re-finds
    // an unchanged title must not make it look freshly edited. Compared via a "content signature" that
    // zeroes the fields which are expected to differ on every write regardless of real content change.
    private fun contentSignature(item: MediaItem): MediaItem =
        item.copy(scannedAt = 0, createdAt = null, updatedAt = null, jellyfinUpdatedAt = null)

    // Phase 204 — returns the already-computed `changed` alongside the stamped item, instead of
    // discarding it once `updatedAt` is decided, so callers can bump [feedVersionAtomic] on the exact
    // same signal rather than recomputing (or skipping) their own notion of "did this write matter".
    private fun stampTimestamps(fresh: MediaItem, old: MediaItem?): Pair<MediaItem, Boolean> {
        val now = nowMs() / 1000
        val createdAt = old?.createdAt ?: now
        val changed = old == null || contentSignature(old) != contentSignature(fresh)
        val updatedAt = if (changed) now else (old.updatedAt ?: now)
        return fresh.copy(createdAt = createdAt, updatedAt = updatedAt) to changed
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
        db.mediaQueries.allLastExamined().executeAsList().forEach { row ->
            lastExaminedLock.withLock { lastExaminedMap[row.id] = row.last_examined_at }
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
                upsertItemDbOnly(item.copy(libraryId = libId), examined = false)  // Phase 196: a backfill reads no files
                backfilled++
            }
        }
        // One-time startup batch (not the live-scan hot path) — a single invalidate for the whole
        // batch is simpler than patching per row, and just as correct since nothing else can be
        // running concurrently against a fresh cache this early.
        // Phase 204 — deliberately does NOT bump feedVersionAtomic: libraryId isn't a field any
        // MediaCard/FocusDetailFacts carries, so a feed rebuild here would cost every reader for a
        // write that provably cannot change what they see. Same reasoning covers backfillTimestamps
        // below (createdAt/updatedAt aren't card-visible either).
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
                upsertItemDbOnly(backfilled, examined = false)  // Phase 196: a backfill reads no files
            }
        }
        allItemsMutex.withLock { allItemsCache = null }
        Logger.info("MediaStore: backfilled createdAt/updatedAt for ${toBackfill.size} rows")
    }

    /** Phase 196 — epoch ms of the last completed examination of this item's files; see [lastExaminedMap]. */
    fun lastExaminedAt(id: String): Long? = lastExaminedLock.withLock { lastExaminedMap[id] }

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
                var merged = preserveJsTags(item, old, jsTagStore.nameSet())
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
                // Phase 196: scan output, and deleteAll above means there is no stored value to carry
                // forward anyway — this is a genuine examination.
                upsertItemDbOnly(merged, examined = true)
            }
        }
        // Phase 204 — this is a wholesale delete-all + reinsert (see the comment above), not the
        // incremental per-item hot path stampTimestamps' `changed` signal exists for. One conservative
        // bump per call is correct and cheap: this path already forces a full allItemsCache invalidate
        // for the same reason, and it isn't the "scan re-finds 500 unchanged titles" case this phase
        // targets — it's a rarer bulk-import/full-rescan path.
        feedVersionAtomic.incrementAndGet()
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
                "segments_lowconf" -> items = items.filter { TriageDetection.lowConfidenceSegmentsCount(it, segmentStore) > 0 }  // Phase 150/163
                "no_segments" -> items = items.filter { TriageDetection.hasNoSegments(it, segmentStore) }  // Phase 150/163
                "unresolved_jellyfin_id" -> items = items.filter { TriageDetection.unresolvedJellyfinIdCount(it) > 0 }  // Phase 152/153
                "mkv_track_layout" -> {  // Phase 201 amendment (2026-09-13)
                    // Phase 203 — resolved open question 2 in favour of consistency with FR-203-4,
                    // which already named this call site as one that "must not start its own sweep" and
                    // must "take the current value and move on": a cold cache filters to no matches
                    // rather than blocking ~88s on the operator's own filter click. The background warm
                    // (FR-203-3) plus the post-scan hook keep this cold window rare in practice.
                    val broken = MkvHealthCache.brokenPathsOrNull()?.keys.orEmpty()
                    items = items.filter { TriageDetection.mkvLayoutBrokenCount(it, broken) > 0 }
                }
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
        val index = jellyfinIdIndex.value ?: allItems()
            .associateBy { it.jellyfinId ?: "" }
            .filterKeys { it.isNotEmpty() }
            .also { jellyfinIdIndex.value = it }
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
        val index = genreIndexCache.value ?: buildGenreIndex().also { genreIndexCache.value = it }
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
        val index = peopleIndexCache.value ?: buildPeopleIndex().also { peopleIndexCache.value = it }
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
            peopleIndexCache.value = null
            jellyfinIdIndex.value  = null
            genreIndexCache.value  = null
            for (t in twins) {
                db.mediaQueries.deleteById(t.id)
                println("[INFO] MediaStore: removed stale duplicate ${t.id} → replaced by ${item.id}")
            }
        }
        // Phase 108: the item's real predecessor is `stale` across an id-rename (existing is null in
        // that case, keyed under the old id), otherwise `existing` — so createdAt/episode createdAt
        // survive a slug change instead of resetting.
        val old = stale ?: existing
        // Phase 199 (FR-199-1): keyed off `old`, not `existing`, matching the three guards beside it —
        // a slug rename now keeps the operator's JS tags instead of silently losing them.
        var merged = preserveJsTags(item, old, jsTagStore.nameSet())
        // Phase 151: key the artwork lock off `old`, not `existing`, so a slug rename (id change) keeps
        // the operator's locked poster instead of silently falling back to the fresh TMDB default.
        merged = preserveLockedArtwork(merged, old)
        // Phase 174: same idea one field over — a scan-fresh item carries `tmdbMatchLocked = false` and
        // has already re-run the search the operator rejected, so the flag AND the match it suppresses
        // both have to come from `old`.
        merged = preserveTmdbMatchLock(merged, old)
        // Phase 184: same idea again — an operator's chosen fetch language must survive a fresh scan
        // exactly like the artwork lock and the TMDB match lock do.
        merged = preserveMetadataLanguage(merged, old)
        if (existing != null && existing.titlesByLang.isNotEmpty()) {
            merged = merged.copy(titlesByLang = existing.titlesByLang + item.titlesByLang)
        }
        merged = merged.copy(episodes = stampEpisodeCreatedAt(merged.episodes, old?.episodes))
        val (stamped, changed) = stampTimestamps(merged, old)
        merged = stamped
        // Phase 153: Scanner never sets these — a fresh scan left them null every cycle, which broke
        // write_nfo's content-hash compare (a real hash can never equal null) and sync_jellyfin's
        // staleness gate (nfoWrittenAt > jfSyncedAt, comparing against a just-reset baseline). Carry
        // them forward from `old` exactly like the other drift/lock state above.
        merged = merged.copy(nfoWrittenAt = old?.nfoWrittenAt, nfoHash = old?.nfoHash, jfSyncedAt = old?.jfSyncedAt)
        // Phase 196 (FR-196-2) — this is THE scan write path (runScan's per-item store, and the targeted
        // per-item sync): the Scanner has just probed this title's files on disk. One of only two places
        // allowed to advance the freshness clock.
        upsertItem(merged, examined = true)
        // Phase 204 (FR-204-1) — a scan re-finding a title with no real content change must not discard
        // every user's Home feed; `changed` is the same content-signature comparison stampTimestamps
        // already made above.
        if (changed) feedVersionAtomic.incrementAndGet()
    }

    /**
     * Phase 151: [respectArtworkLock] applies the same manual-artwork guard `addOrUpdate` uses. It
     * defaults to true because most `updateOne` callers pass an item derived from current store state
     * (where the guard is a no-op), while the ones that matter — `POST /{id}/sync`,
     * `POST /{id}/repull-jellyfin` and `pushToJellyfin` — persist a *freshly scanned* item whose
     * posterPath/backdropPath were just reset to TMDB's default. Only the explicit artwork routes (an
     * operator picking/uploading a new image, which deliberately changes the locked value) pass false.
     *
     * Phase 174: [respectTmdbMatchLock] is the same contract for [MediaItem.tmdbMatchLocked] — it must
     * be false on exactly the routes that deliberately *change* the lock (`PATCH /{id}/tmdb-id` setting
     * a real id, and a `tmdb_match_clear` revert), or the guard would strip the very match they just
     * restored. Every other caller leaves it true, including the clear route itself (whose stored
     * predecessor isn't locked yet, so the guard no-ops there).
     *
     * Phase 199 (FR-199-3): [respectJsTags] is the same contract for JS tags. Every path that reaches
     * `updateOne` today happens to be safe without it — the point of adding it isn't extra protection,
     * it's that "a manual tag removal through `PATCH /{id}/metadata` sticks" stops being a property of
     * the guard's *absence* and becomes a property somebody wrote down, defended by a test. `false` on
     * exactly that route and its History revert (when reverting a `metadata_edit`, which is the one
     * action that can change tags) — same wording and same default as the two guards above it.
     */
    suspend fun updateOne(
        item: MediaItem,
        respectArtworkLock: Boolean = true,
        respectTmdbMatchLock: Boolean = true,
        // Phase 184: false on exactly the routes that deliberately CHANGE metadataLanguage (the set/
        // reset route, and its history revert) — same contract as [respectTmdbMatchLock].
        respectMetadataLanguageLock: Boolean = true,
        respectJsTags: Boolean = true,
        // Phase 196 (FR-196-2) — true ONLY when the caller has just re-read this title's files from disk
        // (today: Scanner.syncSeriesEpisodes and the season re-sync). Every other caller here is a
        // metadata write — an NFO stamp, a Jellyfin sync stamp, an artwork fetch, a Sonarr enrichment, a
        // manual edit — and must leave the freshness clock alone, or it defers a scan of files nobody
        // looked at. Defaults to false so a new call site is safe by omission: the failure mode of a
        // wrong `false` is one redundant scan, of a wrong `true` a title that silently stops being
        // scanned at all.
        examined: Boolean = false,
    ) {
        val existing = get(item.id)
        var merged = if (existing != null && existing.titlesByLang.isNotEmpty()) {
            item.copy(titlesByLang = existing.titlesByLang + item.titlesByLang)
        } else item
        if (respectArtworkLock) merged = preserveLockedArtwork(merged, existing)
        if (respectTmdbMatchLock) merged = preserveTmdbMatchLock(merged, existing)
        if (respectMetadataLanguageLock) merged = preserveMetadataLanguage(merged, existing)
        if (respectJsTags) merged = preserveJsTags(merged, existing, jsTagStore.nameSet())
        merged = merged.copy(episodes = stampEpisodeCreatedAt(merged.episodes, existing?.episodes))
        val (stamped, changed) = stampTimestamps(merged, existing)
        merged = stamped
        upsertItem(merged, examined)
        // Phase 204 (FR-204-1) — same reasoning as addOrUpdate: an NFO stamp, a Jellyfin sync stamp, or
        // an artwork fetch that ends up writing back identical content must not cost every reader a
        // rebuilt Home feed.
        if (changed) feedVersionAtomic.incrementAndGet()
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
        peopleIndexCache.value = null
        jellyfinIdIndex.value  = null
        genreIndexCache.value  = null
        libraryVersionAtomic.incrementAndGet()
        // Phase 204 — a title disappearing is a real content change for anyone's Home/Continue feed
        // (the card it backed can no longer be shown), unlike the metadata-only writes stampTimestamps
        // filters out elsewhere.
        feedVersionAtomic.incrementAndGet()
        lastExaminedLock.withLock { lastExaminedMap.remove(item.id) }
        db.mediaQueries.deleteById(item.id)
        return true
    }

    fun movieCount(): Int = db.mediaQueries.countByKind("MOVIE").executeAsOne().toInt()

    fun tvShowCount(): Int = db.mediaQueries.countByKind("TV_SHOW").executeAsOne().toInt()

    fun tvEpisodeCount(): Int = db.mediaQueries.sumEpisodeCount().executeAsOne().toInt()

    fun totalIssueCount(): Int = db.mediaQueries.sumIssueCount().executeAsOne().toInt()

    suspend fun nfoCoveredCount(): Int {
        val ver = libraryVersion
        nfoCoveredCache.value?.let { (v, c) -> if (v == ver) return c }
        return allItems().count { NfoWriter.exists(it) }.also { nfoCoveredCache.value = Pair(ver, it) }
    }

    suspend fun nfoCoveragePercent(): Int {
        val total = db.mediaQueries.count().executeAsOne().toInt()
        if (total == 0) return 0
        return (nfoCoveredCount() * 100) / total
    }

    suspend fun trackFacets(): TrackFacets {
        val ver = libraryVersion
        trackFacetsCache.value?.let { (v, f) -> if (v == ver) return f }
        return buildTrackFacets().also { trackFacetsCache.value = Pair(ver, it) }
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
        metaFacetsCache.value?.let { (v, f) -> if (v == ver) return f }
        return buildMetaFacets().also { metaFacetsCache.value = Pair(ver, it) }
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
        // Phase 184 (FR-184-8) — "automatic" only shows up here (and so only appears in the picker) when
        // at least one title in the library was actually hand-set; otherwise every item would silently
        // add to it and the facet would be permanently 100% of the library, which is noise, not a filter.
        val metadataLanguageCounts = mutableMapOf<String, Int>()
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
            item.metadataLanguage?.let { lang -> metadataLanguageCounts[lang] = (metadataLanguageCounts[lang] ?: 0) + 1 }
        }
        if (metadataLanguageCounts.isNotEmpty()) {
            metadataLanguageCounts["automatic"] = items.size - metadataLanguageCounts.values.sum()
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
            // "automatic" sorts first regardless of count — it's the library's default state, not a
            // rare value that happens to be popular; the hand-set languages behind it are what an admin
            // is actually looking for when they open this facet.
            metadataLanguages = metadataLanguageCounts.entries
                .sortedWith(compareBy({ it.key != "automatic" }, { -it.value }))
                .map { TrackFacetItem(it.key, it.value) },
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
    /**
     * Phase 196 (FR-196-1/FR-196-2) — [examined] is true **only** when the caller has just completed a
     * real examination of this title's files on disk (a scan or a targeted per-item sync that actually
     * probed them). It used to be unconditional (`last_checked = now()` on every single write), which
     * meant an NFO write, a Jellyfin sync stamp, an artwork fetch or a Sonarr enrichment all deferred
     * the next real scan of a title nobody had read — see [lastExaminedMap]'s doc for the measured
     * production damage and the circular case that made it worst for exactly the airing shows Phase 181
     * set out to protect.
     *
     * When false the stored value is read back and handed straight to `upsert` — `INSERT OR REPLACE`
     * rewrites every column on every write, so carrying it forward is the only way to leave it alone.
     * Exactly the shape Phase 163 already uses for `has_segments` two lines down.
     */
    private fun upsertItemDbOnly(item: MediaItem, examined: Boolean) {
        libraryVersionAtomic.incrementAndGet()
        val lastExamined = if (examined) {
            nowMs().also { now -> lastExaminedLock.withLock { lastExaminedMap[item.id] = now } }
        } else {
            db.mediaQueries.getLastExamined(item.id).executeAsOneOrNull()?.last_examined_at
        }
        // Phase 163: has_segments' source of truth is now MediaSegmentStore's own point-update, not this
        // item's (now-stale) SegmentMarkers blob — INSERT OR REPLACE still touches every column on every
        // write, so this carries the currently-stored value forward instead of recomputing it wrong.
        val hasSegments = db.mediaQueries.getHasSegments(item.id).executeAsOneOrNull() ?: 0L
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
            has_segments = hasSegments,
            scanned_at = item.scannedAt,
            tmdb_id = item.tmdbId?.toLong(),
            poster_path = item.posterPath,
            episode_count = item.episodes.size.toLong(),
            search_text = buildSearchText(item),
            last_examined_at = lastExamined,
        )
    }

    private suspend fun upsertItem(item: MediaItem, examined: Boolean) {
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
        peopleIndexCache.value = null
        jellyfinIdIndex.value  = null
        genreIndexCache.value  = null
        upsertItemDbOnly(item, examined)
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
    // Phase 184 (FR-184-8) — "automatic" (a synthetic value, never a real ISO-639-1 code) stands in for
    // metadataLanguage == null; see ConditionEvaluator's matching doc for why one facet covers both the
    // language-code list and the chosen/automatic distinction the spec asks for.
    val metadataLanguages: List<TrackFacetItem> = emptyList(),
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
