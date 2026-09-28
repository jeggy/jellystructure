package dev.jellystructure.music

import dev.jellystructure.model.PacingStats
import dev.jellystructure.nowEpochSec
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

/**
 * Phase 276 (FR-276-2) — a token bucket at a **fixed** rate. MusicBrainz's ceiling is a published rule (one request a
 * second per IP, 503 beyond it), not something to discover, so this has none of TMDB's AIMD learning (183's
 * `TmdbRateLimiter` is left as it is). One per host. Requests are started no faster than [ratePerSec], never more than
 * [burst] back to back.
 */
class FixedRateLimiter(private val ratePerSec: Double, private val burst: Double = 1.0) {
    private val lock = Mutex()
    private var tokens = burst
    private var last = TimeSource.Monotonic.markNow()
    private val refused = ArrayDeque<Long>()
    private val started = ArrayDeque<Long>()

    suspend fun acquire() {
        while (true) {
            val waitMs = lock.withLock {
                val now = TimeSource.Monotonic.markNow()
                tokens = (tokens + (now - last).inWholeMilliseconds / 1000.0 * ratePerSec).coerceAtMost(burst)
                last = now
                if (tokens >= 1.0) {
                    tokens -= 1.0
                    started.addLast(nowEpochSec()); prune(started)
                    0L
                } else ((1.0 - tokens) / ratePerSec * 1000).toLong().coerceAtLeast(10L)
            }
            if (waitMs == 0L) return
            delay(waitMs)
        }
    }

    /** The host refused one (503 / 429). The caller backs off; this only counts it for the pacing card. */
    suspend fun onRefused() = lock.withLock { refused.addLast(nowEpochSec()); prune(refused) }

    suspend fun stats(): PacingStats = lock.withLock {
        prune(refused); prune(started)
        PacingStats(ratePerSec = started.size / 60.0, ceilingPerSec = ratePerSec, refusedLastMinute = refused.size)
    }

    private fun prune(q: ArrayDeque<Long>) {
        val now = nowEpochSec()
        while (q.isNotEmpty() && now - q.first() > 60L) q.removeFirst()
    }
}
