package dev.jellystructure.media

import dev.jellystructure.db.JellystructureDb

/**
 * Phase 181 (FR-181-5) — the persistent "needs work" set. Written by [RealtimeIngestService] whenever
 * an ingest attempt exhausts its retries (replacing the old "log a warning and forget" ending), read
 * back and retried by [sweepJellyfinLibrary]'s caller on the next [dev.jellystructure.media.RunTarget.Library]
 * run, and cleared the instant that jellyfinId ingests successfully — from either the retry pass or an
 * unrelated trigger (webhook, next sweep) reaching it first.
 *
 * Phase 195 — the set could not drain. Every outstanding id was replayed at once on every cycle, each
 * ingest fanned out one ffprobe waiter per episode file, and the resulting `ProcessGate saturated`
 * timeouts were recorded as failures, re-marking every id dirty for the next cycle to replay. Twelve
 * hours of continuous retrying moved a 120-item backlog to 117 while logging 1 994 gate saturations in
 * a day. Two changes land here: `markDirty` now widens a per-item backoff on each **genuine** failure
 * (a busy gate is a deferral and never reaches this class — see `RealtimeIngestService.IngestOutcome`),
 * and [due] gives the caller a bounded, oldest-first worklist instead of the whole set.
 */
class DirtyItemStore(private val db: JellystructureDb) {

    /** First failure waits this long before a retry; each further consecutive failure doubles it. */
    private val baseBackoffMs = 30 * 60_000L

    /** Ceiling on the backoff — a permanently-broken item is still re-tried daily, just not hourly. */
    private val maxBackoffMs = 24 * 3_600_000L

    /**
     * Records a genuine ingest failure and widens this id's backoff. Update-then-insert in one
     * transaction (see DirtyItem.sq — the dialect has no UPSERT): exactly one of the two takes effect,
     * so a repeat failure increments the counter instead of being silently ignored the way the old
     * bare `INSERT OR IGNORE` did — which is why `attempt_count` could never grow and every outstanding
     * id was replayed on every cycle forever.
     */
    fun markDirty(jellyfinId: String, reason: String, nowMs: Long) {
        db.transaction {
            db.dirtyItemQueries.bumpAttempt(
                reason = reason,
                now = nowMs,
                base_ms = baseBackoffMs,
                cap_ms = maxBackoffMs,
                jellyfin_id = jellyfinId,
            )
            db.dirtyItemQueries.insert(
                jellyfin_id = jellyfinId,
                reason = reason,
                created_at = nowMs,
                base_ms = baseBackoffMs,
            )
        }
    }

    fun clear(jellyfinId: String) {
        db.dirtyItemQueries.clear(jellyfin_id = jellyfinId)
    }

    fun all(): List<String> =
        db.dirtyItemQueries.all().executeAsList().map { it.jellyfin_id }

    /**
     * Phase 195 (FR-195-1/FR-195-4) — ids whose backoff has elapsed, longest-outstanding first, capped
     * at [limit]. The cap is the whole point: without it one cycle re-creates the burst that produced
     * the backlog. Callers must report what they left behind rather than implying full coverage.
     */
    fun due(nowMs: Long, limit: Int): List<String> =
        db.dirtyItemQueries.due(now = nowMs, limit = limit.toLong()).executeAsList().map { it.jellyfin_id }

    fun countDue(nowMs: Long): Long = db.dirtyItemQueries.countDue(now = nowMs).executeAsOne()

    fun count(): Long = db.dirtyItemQueries.count().executeAsOne()

    /** Epoch ms of the longest-outstanding entry, or null when the set is empty. Phase 195 (FR-195-6). */
    fun oldestCreatedAt(): Long? = db.dirtyItemQueries.oldestCreatedAt().executeAsOne().MIN
}
