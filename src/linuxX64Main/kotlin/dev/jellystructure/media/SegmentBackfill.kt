package dev.jellystructure.media

import dev.jellystructure.log.Logger
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.SegmentMarkers

/**
 * Phase 163 — one-time boot migration from the old flat `SegmentMarkers` blob field (embedded per
 * `MediaItem`/`Episode`) to the new per-kind `media_segment` table. Idempotent (gated on the new table
 * being empty — a real row already existing, from a prior backfill or the editor itself, means never
 * touch it again) and Kotlin-side, not a SQL data migration: matches the Ravilo
 * `migrateAllLegacyBehaviourFields` precedent — walking a JSON-embedded episodes array is much safer in
 * Kotlin than reconstructing it with `json_extract()` SQL. `mediaStore.allItems()` — normally off-limits
 * for a new query path — is fine here: this runs once at boot, not on a request path.
 *
 * **Critical, not a detail**: `manuallyConfirmed` maps onto `locked = 1`, never onto `checked_at`.
 * `manuallyConfirmed` **is** today's lock — every detection write path already refuses to touch a
 * `manuallyConfirmed` record — so mapping it to `checked_at` (a passive "a human looked at it" note,
 * not a write guard) would leave every existing hand-correction overwritable by the very next
 * `detect_segments` run. This was the dev-review addendum's blocking finding on this exact step.
 *
 * Only `introStartMs`/`creditsStartMs` migrate. `stinger` deliberately stays in the blob — it's a TMDB
 * keyword-only presence flag (`atMs == null`) more often than not, and a presence flag isn't a timed
 * `media_segment` row; see `MediaSegment.sq`'s own doc for why.
 */
suspend fun backfillMediaSegments(mediaStore: MediaStore, segmentStore: MediaSegmentStore) {
    if (segmentStore.totalSegmentCount() > 0) return

    var backfilled = 0
    for (item in mediaStore.allItems()) {
        backfilled += if (item.kind == MediaKind.MOVIE) {
            backfillOne(segmentStore, item.id, "", 0, item.segments)
        } else {
            item.episodes.sumOf { ep -> backfillOne(segmentStore, item.id, ep.filename, ep.episodeNumber ?: 0, ep.segments) }
        }
    }
    if (backfilled > 0) {
        Logger.info("Phase 163: backfilled $backfilled segment marker(s) from the legacy SegmentMarkers blob into media_segment", "media")
    }
}

private fun backfillOne(segmentStore: MediaSegmentStore, itemId: String, episodeKey: String, episodeNumber: Int, segments: SegmentMarkers): Int {
    var count = 0
    val locked = segments.manuallyConfirmed
    if (segments.introStartMs != null) {
        segmentStore.upsertSegment(itemId, episodeKey, episodeNumber, SegmentKind.INTRO, segments.introStartMs, segments.introEndMs, segments.source, segments.confidence, locked = locked)
        count++
    }
    if (segments.creditsStartMs != null) {
        segmentStore.upsertSegment(itemId, episodeKey, episodeNumber, SegmentKind.CREDITS, segments.creditsStartMs, null, segments.source, segments.confidence, locked = locked)
        count++
    }
    return count
}

/**
 * Phase 222 (FR-222-7) — one boot-time sweep: every media_segment / segment_evidence / segment_waveform
 * row filed under an (episode_key, episode_number) that no episode of its title carries any more (a
 * rename, a removal, a renumbering since it was written) is deleted — one History line per title, one
 * log line with the total. The same prune runs per title whenever the editor opens it (SegmentRoutes),
 * so this is only the catch-up for titles nobody opens. 392 such units existed on 2026-09-16.
 */
suspend fun pruneOrphanSegments(mediaStore: MediaStore, segmentStore: MediaSegmentStore, mediaHistory: MediaHistory): Int {
    var units = 0
    var titles = 0
    for (item in mediaStore.allItems()) {
        val removed = segmentStore.pruneOrphans(item.id, validSegmentKeys(item))
        if (removed > 0) {
            units += removed
            titles++
            mediaHistory.record(item.id, "segments_pruned", "$removed orphaned intro/credits unit(s) removed — the episode files they were filed under no longer exist")
        }
    }
    if (units > 0) Logger.info("Phase 222: pruned $units orphaned segment unit(s) across $titles title(s)", "media")
    return units
}

/** Phase 222 (FR-222-7) — the (episode_key, episode_number) keys a title's segment rows may legitimately
 *  be filed under today: its episodes for a series, the `""`/`0` sentinel for everything else. */
fun validSegmentKeys(item: dev.jellystructure.model.MediaItem): Set<Pair<String, Int>> =
    if (item.kind == dev.jellystructure.model.MediaKind.TV_SHOW) item.episodes.map { it.filename to (it.episodeNumber ?: 0) }.toSet()
    else setOf("" to 0)
