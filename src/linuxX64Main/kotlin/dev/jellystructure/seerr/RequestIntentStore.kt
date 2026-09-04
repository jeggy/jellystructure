package dev.jellystructure.seerr

import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.shared.tv.MediaKind

/** Phase 139 — which request-language intent a viewer chose for a title. Independent of Seerr's live
 *  status (which carries no language) — this is *intent*, not progress; no reconciler needed.
 *  Phase 186 — [deadStreak]/[retiredAt]/[retiredReason] are the reconciliation sweep's own state; a row
 *  is only ever retired after [deadStreak] consecutive dead observations (FR-186-2). */
data class RequestIntentRow(
    val mediaKind: MediaKind,
    val tmdbId: Int,
    val requestedBy: String,
    val languageId: String,
    val strict: Boolean,
    val requestedAt: Long,
    val deadStreak: Int = 0,
    val retiredAt: Long? = null,
    val retiredReason: String? = null,
)

class RequestIntentStore(private val db: JellystructureDb) {
    private val q get() = db.requestIntentQueries

    fun get(mediaKind: MediaKind, tmdbId: Int): RequestIntentRow? =
        q.getOne(mediaKind.name, tmdbId.toLong()).executeAsOneOrNull()?.let(::toRow)

    fun forUser(userId: String): List<RequestIntentRow> =
        q.forUser(userId).executeAsList().map(::toRow)

    /** Phase 186 (FR-186-1) — every live (not-yet-retired) row, for the reconciliation sweep. */
    fun all(): List<RequestIntentRow> = q.all().executeAsList().map(::toRow)

    /** A (re-)request is definitionally live: this always clears any prior dead-streak/retirement
     *  state, same as the SQL's own `upsert` literal — see RequestIntent.sq (open question 5). */
    fun save(mediaKind: MediaKind, tmdbId: Int, requestedBy: String, languageId: String, strict: Boolean, now: Long) {
        val requestedAt = get(mediaKind, tmdbId)?.requestedAt ?: now
        q.upsert(
            media_kind = mediaKind.name,
            tmdb_id = tmdbId.toLong(),
            requested_by = requestedBy,
            language_id = languageId,
            strict = if (strict) 1L else 0L,
            requested_at = requestedAt,
            updated_at = now,
        )
    }

    /** Phase 186 (FR-186-2) — one more consecutive dead observation for this row. */
    fun bumpDeadStreak(mediaKind: MediaKind, tmdbId: Int, now: Long) =
        q.bumpDeadStreak(now, mediaKind.name, tmdbId.toLong())

    /** Phase 186 — a live/indeterminate observation un-does any run of bad ones. */
    fun resetDeadStreak(mediaKind: MediaKind, tmdbId: Int, now: Long) =
        q.resetDeadStreak(now, mediaKind.name, tmdbId.toLong())

    /** Phase 186 (FR-186-4) — soft-retire; caller is responsible for the History/Activity line. */
    fun retire(mediaKind: MediaKind, tmdbId: Int, reason: String, now: Long) =
        q.retire(now, reason, mediaKind.name, tmdbId.toLong())

    /** Phase 186 (FR-186-6) — the explicit remove action hard-deletes; nothing left to explain later. */
    fun deleteOne(mediaKind: MediaKind, tmdbId: Int) =
        q.deleteOne(mediaKind.name, tmdbId.toLong())

    private fun toRow(r: dev.jellystructure.db.Request_intent) = RequestIntentRow(
        mediaKind = MediaKind.valueOf(r.media_kind),
        tmdbId = r.tmdb_id.toInt(),
        requestedBy = r.requested_by,
        languageId = r.language_id,
        strict = r.strict != 0L,
        requestedAt = r.requested_at,
        deadStreak = r.dead_streak.toInt(),
        retiredAt = r.retired_at,
        retiredReason = r.retired_reason,
    )
}
