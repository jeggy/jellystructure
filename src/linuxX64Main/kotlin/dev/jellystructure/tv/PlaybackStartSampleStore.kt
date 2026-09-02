package dev.jellystructure.tv

import dev.jellystructure.db.JellystructureDb

/** Phase 185 (FR-185-4) — retention: most recent N per (device, file). Bounded below at 3 by FR-185-4
 *  (below that, [medianSecondsFor] can never reach `measured`); N itself was an open question with no
 *  further constraint given, so this matches [PlaybackQoeStore.recentForDevice]'s own existing default. */
private const val START_SAMPLE_RETENTION = 10L

/** Phase 185 (FR-185-7) — a resolved verdict on how long starts have actually taken: `measured` needs
 *  at least 3 samples (the median of them, rounded to the nearest 5s); fewer than that is `expected`
 *  (no number — FR-185-6's predicate alone decided the note fires, this store had nothing to add yet). */
sealed interface StartHistoryBasis {
    data object Expected : StartHistoryBasis
    data class Measured(val seconds: Int) : StartHistoryBasis
}

/**
 * Phase 185 (FR-185-4/FR-185-7) — the append-only start-time ledger and its one read: "how sure are we,
 * and how long does it actually take, for THIS (device, file)". Written only by [record], called from
 * `PlaybackService.stopPlayback` when (and only when) the client reported a startup duration on a real
 * session completion — never mid-session, never for an abandoned-before-first-frame session (the client
 * itself can't compute a duration in that case, so it simply won't send one).
 */
class PlaybackStartSampleStore(private val db: JellystructureDb) {
    private val queries get() = db.playbackStartSampleQueries

    fun record(deviceId: String, itemId: String, fileId: String, seconds: Int, recordedAtMs: Long) {
        queries.insert(device_id = deviceId, item_id = itemId, file_id = fileId, seconds = seconds.toLong(), recorded_at = recordedAtMs)
        queries.pruneToMax(device_id = deviceId, file_id = fileId, max_rows = START_SAMPLE_RETENTION)
    }

    /** FR-185-7 — re-derive on every call (cheap: at most [START_SAMPLE_RETENTION] rows per key), never
     *  cached, so this can only ever change when [record] actually runs — "re-derived only on session
     *  completion, so two page views a minute apart cannot disagree." */
    fun basisFor(deviceId: String, fileId: String): StartHistoryBasis {
        val samples = queries.recentForDeviceAndFile(device_id = deviceId, file_id = fileId, limit = START_SAMPLE_RETENTION)
            .executeAsList().map { it.toInt() }
        if (samples.size < 3) return StartHistoryBasis.Expected
        val sorted = samples.sorted()
        val mid = sorted.size / 2
        val rawMedian = if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid].toDouble()
        return StartHistoryBasis.Measured(roundToNearest5(rawMedian))
    }
}

/** FR-185-7 — "the median of them, rounded to the nearest 5s". */
private fun roundToNearest5(v: Double): Int = (kotlin.math.round(v / 5.0) * 5.0).toInt()
