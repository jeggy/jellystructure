package dev.jellystructure.media

import dev.jellystructure.ops.LockedMap

/**
 * Phase 295 (FR-295-2) — where a job that stopped for playback waits. Before this, a `prewarm_subtitles` row that
 * yielded was put straight back at the head of its queue and claimed again on the next pass: with the household's
 * *defer while watching* switch off the claim never held it, the walk yielded again, and the loop ran about eight
 * times a second for as long as anything played (76 % CPU, 500 log lines a minute, 2026-10-02).
 *
 * A parked row is skipped by the claim while something is still playing and its back-off has not run out. It is
 * claimable again the moment playback ends, or after [backoffMs] if playback goes on (the walk then decides afresh).
 * The claim releases it, and says so once.
 */
internal class PlaybackParking(
    private val nowMs: () -> Long,
    private val backoffMs: Long = DEFAULT_BACKOFF_MS,
) {
    private val until = LockedMap<String, Long>()

    /** [jobId] just stopped for playback. */
    fun park(jobId: String) {
        val now = nowMs()
        until.removeIf { _, u -> u < now - STALE_MS }   // rows cancelled while parked
        until[jobId] = now + backoffMs
    }

    /** Should the claim pass over [jobId] right now? Only while [playing] and before its back-off runs out. */
    fun holds(jobId: String, playing: Boolean): Boolean {
        val u = until[jobId] ?: return false
        return playing && nowMs() < u
    }

    /** The claim took [jobId]; true when it had been parked (so the resume is logged once). */
    fun release(jobId: String): Boolean = until.remove(jobId) != null

    companion object {
        const val DEFAULT_BACKOFF_MS = 30_000L
        private const val STALE_MS = 3_600_000L
    }
}
