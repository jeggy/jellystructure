package dev.jellystructure.media

import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.log.Logger
import dev.jellystructure.nowEpochSec

/**
 * DEB-5537 (FR-1, FR-4) — a failure that will repeat, remembered against what was tried, so the scheduled pass
 * stops asking every hour. The input is a file (path, with the size and mtime it had) or a remote fetch (the URL
 * or release-group id, size and mtime 0). A record holds until the input changes or [RETRY_AFTER_SEC] passes; an
 * operator action never consults it (the caller does not ask), and clears it when it succeeds ([forget]).
 *
 * [kind] names the loop — a job type, `cover_art`, `write_tags` — so two loops on one path never share a record.
 */
class KnownFailures(private val db: JellystructureDb, private val now: () -> Long = ::nowEpochSec) {
    private val q get() = db.knownFailureQueries

    /** What one pass of one loop skipped, for the single count-only line of FR-4. */
    class Pass(private val kind: String, private val what: String) {
        private var skipped = 0
        private var nextRetry: Long? = null
        internal fun skip(retryAt: Long) { skipped++; nextRetry = minOf(nextRetry ?: retryAt, retryAt) }
        /** FR-4 — one INFO line per pass, counts only; nothing when nothing was skipped. */
        suspend fun log() {
            val at = nextRetry ?: return
            Logger.info("$kind: skipped $skipped known failure${if (skipped == 1) "" else "s"} ($what); next retry ${isoFromEpoch(at)}", "retry")
        }
        val count get() = skipped
    }

    fun pass(kind: String, what: String) = Pass(kind, what)

    /** FR-1 — true when [input] failed before with this same stamp and the record is under 7 days old; counts it on [pass]. */
    fun skip(kind: String, input: String, size: Long, mtime: Long, pass: Pass? = null): Boolean {
        val row = q.byInput(kind, input).executeAsOneOrNull() ?: return false
        if (row.size != size || row.mtime != mtime) return false
        val retryAt = row.failed_at + RETRY_AFTER_SEC
        if (now() >= retryAt) return false
        pass?.skip(retryAt)
        return true
    }

    /** FR-1/FR-4 — remember a failure. True when this is a new record (or the input changed or the record had aged out),
     *  which is the one time the caller prints its WARN. A repeat failure inside a live record refreshes nothing. */
    fun record(kind: String, input: String, size: Long, mtime: Long, reason: String): Boolean {
        val row = q.byInput(kind, input).executeAsOneOrNull()
        val live = row != null && row.size == size && row.mtime == mtime && now() < row.failed_at + RETRY_AFTER_SEC
        if (live) return false
        q.put(kind, input, size, mtime, reason, now())
        return true
    }

    /** FR-1 — a success, or an operator's retry that worked, clears the record. */
    fun forget(kind: String, input: String) = q.remove(kind, input)

    /** FR-3 — a file that is gone has no failure to remember. */
    fun forgetAllFor(input: String) = q.removeAllForInput(input)

    companion object {
        const val RETRY_AFTER_SEC = 7 * 86_400L
        const val COVER_ART = "cover_art"
        const val WRITE_TAGS = "write_tags"
    }
}

private fun isoFromEpoch(sec: Long): String = dev.jellystructure.filefix.isoDateUtc(sec)
