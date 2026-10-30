package dev.jellystructure.tv

import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.log.Logger

/**
 * DEB-3100 (trouble-never-reaches-the-viewer FR-2) — the plan behind a stream id, kept so that a restart of the backend does not end a
 * play: a request for an id the process does not know re-registers it from here. One row per stream; [kind] says which registry owns it.
 * A row goes with its play session ([deleteForPlay]) or after [UNREAD_SWEEP_MS] unread ([sweep]).
 *
 * A store that cannot be written never fails a play: the stream is simply memory-only again, which is what it was before.
 */
class StreamPlanStore(private val db: JellystructureDb) {
    class Stored(val id: String, val kind: String, val playSessionId: String, val payload: String, val expiresAt: Long, val readAt: Long)

    private val queries get() = db.streamPlanQueries

    suspend fun put(id: String, kind: String, playSessionId: String, payload: String, expiresAt: Long, now: Long) {
        runCatching { queries.upsert(id, kind, playSessionId, payload, expiresAt, now) }
            .onFailure { Logger.warn("stream plan $id not stored: ${it.message}", "tv") }
    }

    fun get(id: String, kind: String): Stored? =
        runCatching { queries.get(id).executeAsOneOrNull() }.getOrNull()?.takeIf { it.kind == kind }
            ?.let { Stored(it.id, it.kind, it.play_session_id, it.payload, it.expires_at, it.read_at) }

    fun forPlay(playSessionId: String, kind: String): List<Stored> =
        runCatching { queries.forPlay(playSessionId, kind).executeAsList() }.getOrDefault(emptyList())
            .map { Stored(it.id, it.kind, it.play_session_id, it.payload, it.expires_at, it.read_at) }

    /** A read renewed the ticket: the new expiry and the read time. */
    fun touch(id: String, expiresAt: Long, now: Long) { runCatching { queries.touch(expiresAt, now, id) } }

    fun delete(id: String) { runCatching { queries.deleteOne(id) } }

    /** The play session ended: every stream of [kind] it owned goes (each registry deletes its own, so the other can still read them to stop its jobs). */
    fun deleteForPlay(playSessionId: String, kind: String) { if (playSessionId.isNotEmpty()) runCatching { queries.deleteForPlay(playSessionId, kind) } }

    /** Rows nobody read for [UNREAD_SWEEP_MS]. */
    fun sweep(now: Long) { runCatching { queries.sweep(now - UNREAD_SWEEP_MS) } }

    companion object {
        const val UNREAD_SWEEP_MS = 24L * 3_600_000L
        /** A read renews the ticket to this long from now (FR-2: a stream ends by expiry only after this long with no reads). */
        const val RENEW_MS = 4L * 3_600_000L
        /** The store learns of a read at most this often per stream: a segment every 2 s must not be a write every 2 s. */
        const val TOUCH_EVERY_MS = 60_000L
    }
}
