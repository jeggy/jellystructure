package dev.jellystructure.media

import dev.jellystructure.db.JellystructureDb

/**
 * Phase 181 (FR-181-5) — the persistent "needs work" set. Written by [RealtimeIngestService] whenever
 * an ingest attempt exhausts its retries (replacing the old "log a warning and forget" ending), read
 * back and retried by [sweepJellyfinLibrary]'s caller on the next [dev.jellystructure.media.RunTarget.Library]
 * run, and cleared the instant that jellyfinId ingests successfully — from either the retry pass or an
 * unrelated trigger (webhook, next sweep) reaching it first. `INSERT OR IGNORE` on the unique
 * `jellyfin_id` column makes marking dirty idempotent: a jellyfinId already outstanding doesn't get a
 * second row or a reset `created_at`.
 */
class DirtyItemStore(private val db: JellystructureDb) {

    fun markDirty(jellyfinId: String, reason: String, nowMs: Long) {
        db.dirtyItemQueries.insert(jellyfin_id = jellyfinId, reason = reason, created_at = nowMs)
    }

    fun clear(jellyfinId: String) {
        db.dirtyItemQueries.clear(jellyfin_id = jellyfinId)
    }

    fun all(): List<String> =
        db.dirtyItemQueries.all().executeAsList().map { it.jellyfin_id }

    fun count(): Long = db.dirtyItemQueries.count().executeAsOne()
}
