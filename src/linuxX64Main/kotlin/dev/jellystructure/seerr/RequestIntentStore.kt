package dev.jellystructure.seerr

import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.shared.tv.MediaKind

/** Phase 139 — which request-language intent a viewer chose for a title. Independent of Seerr's live
 *  status (which carries no language) — this is *intent*, not progress; no reconciler needed. */
data class RequestIntentRow(
    val mediaKind: MediaKind,
    val tmdbId: Int,
    val requestedBy: String,
    val languageId: String,
    val strict: Boolean,
    val requestedAt: Long,
)

class RequestIntentStore(private val db: JellystructureDb) {
    private val q get() = db.requestIntentQueries

    fun get(mediaKind: MediaKind, tmdbId: Int): RequestIntentRow? =
        q.getOne(mediaKind.name, tmdbId.toLong()).executeAsOneOrNull()?.let(::toRow)

    fun forUser(userId: String): List<RequestIntentRow> =
        q.forUser(userId).executeAsList().map(::toRow)

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

    private fun toRow(r: dev.jellystructure.db.Request_intent) = RequestIntentRow(
        mediaKind = MediaKind.valueOf(r.media_kind),
        tmdbId = r.tmdb_id.toInt(),
        requestedBy = r.requested_by,
        languageId = r.language_id,
        strict = r.strict != 0L,
        requestedAt = r.requested_at,
    )
}
