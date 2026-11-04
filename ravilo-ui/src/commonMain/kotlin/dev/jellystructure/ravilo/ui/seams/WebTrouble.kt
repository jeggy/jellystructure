package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.ravilo.ui.screens.CLIMB_BLOCK_AFTER_RECOVERY_MS
import dev.jellystructure.shared.tv.LadderRules

// DEB-3213 (DEB-3093 FR-9, FR-3 to FR-6) — how the web player (hls.js) meets trouble. The decisions are plain functions so they are
// tested on every platform (the build has no browser test runner); `RaviloPlayerWasm` feeds them what hls.js and the element reported.

/**
 * FR-3 and FR-4 — hls.js's timeouts and retries, as the three load policies it reads (1.7: a level's playlist is `playlistLoadPolicy`,
 * there is no `levelLoadPolicy`). [configJson] is parsed by `attachSource` and merged into hls.js's config; the retry *count* for a
 * segment that is not the last picture in hand is the one thing JSON cannot say, so `attachSource` adds a `shouldRetry` to the policies
 * that applies [LadderRules.retryLimit]'s rule (see [retryAllowed]).
 */
internal object WebHlsPolicy {
    /** FR-3 — the first byte of a segment, playlist or master may take this long; the player's read timeout is 20 s. */
    const val FIRST_BYTE_TIMEOUT_MS = 20_000

    /** hls.js's own ceiling for a whole segment load (its default), and for a playlist 10 s above the first-byte wait so a body can follow. */
    const val FRAG_MAX_LOAD_MS = 120_000
    const val PLAYLIST_MAX_LOAD_MS = 30_000

    /** FR-4 — the scheduled retries: 0.5, 1, 2 and 4 s, then 5 s ([LadderRules.RETRY_DELAYS_MS]). */
    val retryDelaysMs: List<Long> get() = LadderRules.RETRY_DELAYS_MS

    /** hls.js's delay is `min(2^n × retryDelayMs, maxRetryDelayMs)` for `backoff: 'exponential'`; this is its n-th delay (n from 0). */
    fun hlsDelayMs(n: Int): Long = minOf((1L shl n) * retryDelaysMs.first(), retryDelaysMs.last())

    /**
     * FR-4 — whether the failure number [failures] (0 = the first) of a load is retried: the scheduled retries always, and beyond them for
     * as long as the buffer holds picture. [LadderRules.retryLimit] states it; once the buffer is empty and every retry has run the failure
     * is the error (which the restream, FR-6, may still answer). Any status counts, a 4xx as well as a 5xx.
     */
    fun retryAllowed(failures: Int, bufferedMs: Long): Boolean = failures < LadderRules.retryLimit(bufferedMs)

    private fun retry(): String =
        """{"maxNumRetry":${retryDelaysMs.size},"retryDelayMs":${retryDelaysMs.first()},"maxRetryDelayMs":${retryDelaysMs.last()},"backoff":"exponential"}"""

    private fun policy(maxLoadMs: Int): String =
        """{"default":{"maxTimeToFirstByteMs":$FIRST_BYTE_TIMEOUT_MS,"maxLoadTimeMs":$maxLoadMs,"timeoutRetry":${retry()},"errorRetry":${retry()}}}"""

    /**
     * FR-4 — how long hls.js's own ABR keeps a rung out after a load of it failed. hls.js's default is 0, which means never: a level with a
     * load error is skipped by its ABR until one of its segments is buffered, and a skipped rung never gets one. [WebRungHolds] lifts its
     * own cap after [LadderRules.EXCLUDE_FAILED_RUNG_MS], so hls.js's penalty ends with it (DEB-3544).
     */
    const val ERROR_PENALTY_EXPIRE_MS = LadderRules.EXCLUDE_FAILED_RUNG_MS

    /** The three policies, and the rung penalty, as a JSON object keyed by hls.js's config names. */
    fun configJson(): String =
        """{"fragLoadPolicy":${policy(FRAG_MAX_LOAD_MS)},"manifestLoadPolicy":${policy(PLAYLIST_MAX_LOAD_MS)},"playlistLoadPolicy":${policy(PLAYLIST_MAX_LOAD_MS)},"errorPenaltyExpireMs":$ERROR_PENALTY_EXPIRE_MS}"""
}

/**
 * FR-4 and FR-5 — the ceilings a trouble puts on hls.js's rung (its level index, ascending): a failed load keeps its rung out for 15 s, an
 * abandoned segment keeps the climb below its rung for 30 s, a stall caps one rung down for the 2 minutes Android's cap lasts. Several
 * may hold at once; the lowest ceiling wins, and each is forgotten when its time is up. Nothing is held for the lowest rung (there is no
 * rung below to ask for). One per play; [reset] on a new load.
 */
internal class WebRungHolds {
    private class Hold(val ceiling: Int, val untilMs: Double)
    private val holds = mutableListOf<Hold>()

    /** FR-4 — a segment or playlist of [level] failed: the rung is excluded for [LadderRules.EXCLUDE_FAILED_RUNG_MS]. */
    fun failedLoad(level: Int, nowMs: Double) = add(level - 1, nowMs + LadderRules.EXCLUDE_FAILED_RUNG_MS)

    /** FR-4 — a slow segment of [level] was abandoned: the rung below is the ceiling for [LadderRules.CLIMB_WAIT_AFTER_ABANDON_MS]. */
    fun abandoned(level: Int, nowMs: Double) = add(level - 1, nowMs + LadderRules.CLIMB_WAIT_AFTER_ABANDON_MS)

    /** FR-5 — a stall on [level] caps one rung down at once, for the same [CLIMB_BLOCK_AFTER_RECOVERY_MS] Android's in-place cap holds. */
    fun stalled(level: Int, nowMs: Double) = add(level - 1, nowMs + CLIMB_BLOCK_AFTER_RECOVERY_MS)

    private fun add(ceiling: Int, untilMs: Double) { if (ceiling >= 0) holds += Hold(ceiling, untilMs) }

    /** The highest level hls.js may use now, or null when nothing is held. */
    fun ceiling(nowMs: Double): Int? {
        holds.removeAll { it.untilMs <= nowMs }
        return holds.minOfOrNull { it.ceiling }
    }

    fun reset() = holds.clear()
}

/**
 * FR-4 — the one decision about the segment loading now, where hls.js's own abandon rule (the load's projected finish against the whole
 * buffer) is not FR-4's (against the buffer minus 5 s, and DEB-3172's carve-out). [LadderRules.shouldAbandonSegment] is the arithmetic; this
 * keeps what Android's `ClimbingTrackSelection` keeps around it: nothing is judged with nothing buffered, nor until the buffer has once
 * held the 5 s margin plus the segment's own length since the start or the last seek, and one load is abandoned once.
 */
internal class WebSegmentAbandon {
    /** The segment in flight, in the numbers hls.js reports: [key] names the load (segment and level), times are ms, [bitrate] the level's. */
    data class Load(
        val key: String, val level: Int, val ageMs: Long, val bytesLoaded: Long, val segmentMs: Long, val bitrate: Long,
        val sinceFirstByteMs: Long, val estimateBps: Long,
    ) {
        /** The load's size when it is whole: the segment's duration at the level's bitrate (0 = unknown). */
        val expectedBytes: Long get() = segmentMs * bitrate / 8_000
    }

    private var judgeable = false
    private var abandonedKey: String? = null

    /** A start, a seek or a replacement: the carve-out begins again. */
    fun reset() { judgeable = false; abandonedKey = null }

    fun shouldAbandon(load: Load, bufferedMs: Long): Boolean {
        if (bufferedMs <= 0) return false
        if (!judgeable && bufferedMs >= LadderRules.ABANDON_MARGIN_MS + load.segmentMs) judgeable = true
        if (!judgeable || load.key == abandonedKey) return false
        val cancel = LadderRules.shouldAbandonSegment(
            elapsedMs = load.ageMs, bytesLoaded = load.bytesLoaded, expectedBytes = load.expectedBytes, estimateBps = load.estimateBps,
            bufferedMs = bufferedMs, lowerRungExists = load.level > 0, sinceFirstByteMs = load.sinceFirstByteMs,
        )
        if (cancel) abandonedKey = load.key
        return cancel
    }
}

/** What the ladder rule and the holds say together: the rung hls.js is capped at, and whether the next segment moves there now. */
internal fun boundedByHolds(decision: WebLadderCap.Decision?, ceiling: Int?, currentLevel: Int): WebLadderCap.Decision? {
    if (ceiling == null) return decision
    val cap = minOf(decision?.capLevel ?: ceiling, ceiling)
    val step = (decision?.stepDown ?: false) || (currentLevel > cap)
    return WebLadderCap.Decision(cap, step)
}
