package dev.jellystructure.media

import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.nowEpochSec
import kotlinx.serialization.Serializable

/** The five first-class kinds (spec §3) — matching Jellyfin's own `MediaSegmentType` vocabulary
 *  (Recap/Intro/Preview/Outro/Outro-protected) so reading Jellyfin's segments in (§E) is a straight
 *  map. `Commercial` is deliberately unmapped — nothing in this library has ad breaks. */
object SegmentKind {
    const val RECAP = "recap"
    const val INTRO = "intro"
    const val PREVIEW = "preview"
    const val CREDITS = "credits"
    const val STINGER = "stinger"
    val ALL = listOf(RECAP, INTRO, PREVIEW, CREDITS, STINGER)
}

/** `source` values a row can carry — matches the old `SegmentMarkers.source` vocabulary plus `jellyfin`
 *  for a candidate read in from Jellyfin's own MediaSegments API (spec §E, never auto-applied). */
object SegmentSource {
    const val FINGERPRINT = "fingerprint"
    const val HEURISTIC = "heuristic"
    const val CHAPTER = "chapter"
    const val JELLYFIN = "jellyfin"
    const val TMDB = "tmdb"
    const val MANUAL = "manual"

    /**
     * Phase 170 (detect_segments follow-ups, §2) — a `force=true` re-detect only checked [locked], never
     * [source], so an unlocked exact chapter-title match could be silently clobbered by a later, merely-
     * good-guess fingerprint or heuristic pass, or a heuristic pass could clobber a fingerprint season
     * consensus. Higher number wins: an incoming detection may only overwrite an existing row whose
     * precedence is ≤ its own (`precedence(incoming) >= precedence(existing.source)`) — equal-tier
     * overwrites (a fresh chapter hit replacing an older one, a fresh fingerprint pass replacing an older
     * one) stay allowed, matching the pre-fix behavior for same-source re-detection. `null`/unrecognized
     * sources (a legacy pre-source row, or no row at all) rank lowest so they never block anything.
     */
    fun precedence(source: String?): Int = when (source) {
        MANUAL -> 4
        CHAPTER -> 3
        FINGERPRINT -> 2
        HEURISTIC -> 1
        else -> 0  // JELLYFIN/TMDB (candidate-only, never written by a detection tier) and null/unknown
    }
}

@Serializable
data class MediaSegmentRow(
    val itemId: String,
    val episodeKey: String = "",
    val episodeNumber: Int = 0,
    val kind: String,
    val startMs: Long,
    val endMs: Long? = null,
    val source: String? = null,
    val confidence: Double? = null,
    val locked: Boolean = false,
    val checkedAt: Long? = null,
    val updatedAt: Long = 0,
)

/** `evidenceType` values (spec addendum §2 / the "full raw evidence" decision) — black-frame/silence
 *  intervals the credits heuristic scanned, a fingerprint match span against a specific paired episode,
 *  or a chapter title that matched a keyword but lost to the winning chapter. */
object EvidenceType {
    const val BLACK_FRAME = "black_frame"
    const val SILENCE = "silence"
    const val FINGERPRINT_MATCH = "fingerprint_match"
    const val CHAPTER_CANDIDATE = "chapter_candidate"
}

@Serializable
data class SegmentEvidenceRow(
    val id: Long = 0,
    val itemId: String,
    val episodeKey: String = "",
    val episodeNumber: Int = 0,
    val kind: String,
    val evidenceType: String,
    val startMs: Long,
    val endMs: Long? = null,
    val detail: String? = null,
    val accepted: Boolean = false,
    val recordedAt: Long = 0,
)

/** Phase 163 — the per-(item, episode, kind) segment-marker store, replacing the old flat
 *  `SegmentMarkers` blob field embedded in `MediaItem`/`Episode`. Mirrors `TowoStore`'s thin-wrapper
 *  style: plain query pass-throughs plus the handful of derived operations (lock-checking, evidence
 *  bookkeeping, the `media.has_segments` point-update) every caller would otherwise duplicate. */
class MediaSegmentStore(private val db: JellystructureDb) {

    fun segmentsForItem(itemId: String): List<MediaSegmentRow> =
        db.mediaSegmentQueries.segmentsForItem(itemId).executeAsList().map { it.toModel() }

    fun segmentsForEpisode(itemId: String, episodeKey: String, episodeNumber: Int): List<MediaSegmentRow> =
        db.mediaSegmentQueries.segmentsForEpisode(itemId, episodeKey, episodeNumber.toLong()).executeAsList().map { it.toModel() }

    fun getSegment(itemId: String, episodeKey: String, episodeNumber: Int, kind: String): MediaSegmentRow? =
        db.mediaSegmentQueries.getSegment(itemId, episodeKey, episodeNumber.toLong(), kind).executeAsOneOrNull()?.toModel()

    fun isLocked(itemId: String, episodeKey: String, episodeNumber: Int, kind: String): Boolean =
        db.mediaSegmentQueries.isLocked(itemId, episodeKey, episodeNumber.toLong(), kind).executeAsOneOrNull() == 1L

    /** Write-through upsert (spec §6 — no staged/apply model). Recomputes `media.has_segments`
     *  afterward; callers never need to do that themselves. Does NOT check [isLocked] — callers that
     *  must respect a lock (every detection path) check it themselves first, since an explicit human
     *  edit is allowed to overwrite a lock it's simultaneously changing. */
    fun upsertSegment(
        itemId: String, episodeKey: String, episodeNumber: Int, kind: String,
        startMs: Long, endMs: Long?, source: String?, confidence: Double?,
        locked: Boolean = false, checkedAt: Long? = null,
    ) {
        db.mediaSegmentQueries.upsertSegment(
            item_id = itemId, episode_key = episodeKey, episode_number = episodeNumber.toLong(), kind = kind,
            start_ms = startMs, end_ms = endMs, source = source, confidence = confidence,
            locked = if (locked) 1L else 0L, checked_at = checkedAt, updated_at = nowEpochSec(),
        )
        recomputeHasSegments(itemId)
    }

    fun setLocked(itemId: String, episodeKey: String, episodeNumber: Int, kind: String, locked: Boolean) {
        db.mediaSegmentQueries.setLocked(if (locked) 1L else 0L, nowEpochSec(), itemId, episodeKey, episodeNumber.toLong(), kind)
    }

    /** "Checked" (spec §5) is episode-scoped, not per-kind — a human looked at this episode's markers —
     *  so this stamps every kind row currently present for the episode; a kind with no row yet (nothing
     *  detected, nothing manual) has nothing to mark. */
    fun setChecked(itemId: String, episodeKey: String, episodeNumber: Int, checkedAt: Long = nowEpochSec()) {
        db.mediaSegmentQueries.setChecked(checkedAt, nowEpochSec(), itemId, episodeKey, episodeNumber.toLong())
    }

    fun deleteSegment(itemId: String, episodeKey: String, episodeNumber: Int, kind: String) {
        db.mediaSegmentQueries.deleteSegment(itemId, episodeKey, episodeNumber.toLong(), kind)
        recomputeHasSegments(itemId)
    }

    fun deleteSegmentsForItem(itemId: String) {
        db.mediaSegmentQueries.deleteSegmentsForItem(itemId)
        db.mediaSegmentQueries.clearEvidenceForItem(itemId)
        recomputeHasSegments(itemId)
    }

    /** Backs the boot backfill's "has this already run" idempotency check. */
    fun totalSegmentCount(): Long = db.mediaSegmentQueries.countAll().executeAsOne()

    /** Backs `TriageDetection`/the dashboard's "low-confidence segments" row. */
    fun lowConfidenceSegments(threshold: Double): List<MediaSegmentRow> =
        db.mediaSegmentQueries.lowConfidenceSegments(threshold).executeAsList().map { it.toModel() }

    /** Backs `media.has_segments` (spec addendum §5 — must be recomputed on every segment write, not
     *  just on a whole-item write, or triage/§G's deep links go stale while the editor is in use). A
     *  point-update, no blob decode. "Has segments" = a real intro or credits marker exists — matches
     *  the old blob-derived definition exactly (`introStartMs != null || creditsStartMs != null`). */
    private fun recomputeHasSegments(itemId: String) {
        val has = db.mediaSegmentQueries.anyUsableSegment(itemId).executeAsOne()
        db.mediaQueries.updateHasSegments(if (has) 1L else 0L, itemId)
    }

    // ===== Evidence (spec addendum §2 — full raw evidence capture) =====

    fun recordEvidence(
        itemId: String, episodeKey: String, episodeNumber: Int, kind: String, evidenceType: String,
        startMs: Long, endMs: Long?, detail: String?, accepted: Boolean,
    ) {
        db.mediaSegmentQueries.insertEvidence(
            item_id = itemId, episode_key = episodeKey, episode_number = episodeNumber.toLong(), kind = kind,
            evidence_type = evidenceType, start_ms = startMs, end_ms = endMs, detail = detail,
            accepted = if (accepted) 1L else 0L, recorded_at = nowEpochSec(),
        )
    }

    fun evidenceForEpisode(itemId: String, episodeKey: String, episodeNumber: Int): List<SegmentEvidenceRow> =
        db.mediaSegmentQueries.evidenceForEpisode(itemId, episodeKey, episodeNumber.toLong()).executeAsList().map { it.toModel() }

    /** Called before a fresh detect run writes new evidence for this (item, episode, kind), so
     *  re-detecting doesn't accumulate stale rows from a previous run's rejected candidates. */
    fun clearEvidenceForKind(itemId: String, episodeKey: String, episodeNumber: Int, kind: String) {
        db.mediaSegmentQueries.clearEvidenceForKind(itemId, episodeKey, episodeNumber.toLong(), kind)
    }
}

private fun dev.jellystructure.db.Media_segment.toModel() = MediaSegmentRow(
    itemId = item_id, episodeKey = episode_key, episodeNumber = episode_number.toInt(), kind = kind,
    startMs = start_ms, endMs = end_ms, source = source, confidence = confidence,
    locked = locked != 0L, checkedAt = checked_at, updatedAt = updated_at,
)

private fun dev.jellystructure.db.LowConfidenceSegments.toModel() = MediaSegmentRow(
    itemId = item_id, episodeKey = episode_key, episodeNumber = episode_number.toInt(), kind = kind,
    startMs = start_ms, endMs = end_ms, source = source, confidence = confidence,
    locked = locked != 0L, checkedAt = checked_at, updatedAt = updated_at,
)

private fun dev.jellystructure.db.Segment_evidence.toModel() = SegmentEvidenceRow(
    id = id, itemId = item_id, episodeKey = episode_key, episodeNumber = episode_number.toInt(), kind = kind,
    evidenceType = evidence_type, startMs = start_ms, endMs = end_ms, detail = detail,
    accepted = accepted != 0L, recordedAt = recorded_at,
)
