package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.QoeStall
import kotlin.math.abs

/**
 * DEB-2205 (FR-1, FR-5) — a play stops at most once, at its start; a stop after the first frame is measured, and a
 * frozen HLS play recovers downward in seconds. The pure rules, so the decision table is a unit test and not a device
 * session: the store reads the player each tick ([PlayerWatch]), [FrozenMeter] measures the stop, [FreezeRecovery] decides.
 */

/** What the store reads from the player every tick. [bufferedAheadMs] is -1 where the platform cannot say. */
data class PlayerWatch(
    val positionMs: Long,
    /** The viewer wants it playing (not paused). */
    val wantsToPlay: Boolean,
    /** The player is buffering or seeking: its own wait, counted elsewhere (the buffer-empty kind, or the viewer's seek). */
    val waiting: Boolean,
    val firstFrame: Boolean,
    val bufferedAheadMs: Long,
    /** R220's video-output recovery ladder is running (its rung 2 seeks by 1 ms and waits for the player): its own wait is
     *  not the viewer's and does not end a freeze, or the ladder would restart the 5 s every time it ran. */
    val recovering: Boolean = false,
)

/** FR-1 — a stop is a position that does not move for this long while the player intends to play. */
internal const val FROZEN_STOP_MS = 1_000L
/** FR-5.2 (replacing 313 F2's 20 s for a picture with buffer) — a freeze this old is recovered. */
internal const val FROZEN_RECOVER_MS = 5_000L
/** FR-5.2 — "buffer in hand": this much ahead of the playhead. Under it a stuck position is the buffer-empty kind. */
internal const val FROZEN_BUFFER_IN_HAND_MS = 3_000L
/** FR-5.3 — nothing climbs again for this long after a recovery. */
internal const val CLIMB_BLOCK_AFTER_RECOVERY_MS = 120_000L
/** FR-5.1 — buffer-empty for this long caps the adaptive selection one rung down, in place. DEB-3102 (FR-5): at once (was 3 s). */
internal const val IN_PLACE_CAP_AFTER_MS = 0L

/** FR-5.4 — how long a recovery's own stop is watched for the new stream's first movement before it is written off. */
internal const val RECOVERY_WATCH_MS = 60_000L
/** The frozen stops and recoveries kept beside the player's own stalls, per item. */
internal const val MAX_EXTRA_STOPS = 10

/** One finished frozen stop: when it began (the player's clock, ms), how long, where, and the buffer ahead when it began. */
internal data class FrozenStop(val startedAtMs: Long, val durationMs: Long, val positionMs: Long, val bufferAheadMs: Long)

/** [heldMs] is how long the picture has been frozen with buffer in hand now (0: it is not, or not yet [FROZEN_STOP_MS]). */
internal data class FrozenReading(val heldMs: Long, val bufferAheadMs: Long, val ended: FrozenStop?)

/**
 * FR-1 — the stops where the position does not move with buffer in hand. Not a stop: a pause, a buffering or seeking
 * player (its own wait), a play before its first frame, or a stuck position with under [FROZEN_BUFFER_IN_HAND_MS] ahead
 * (the buffer ran out: the player says so itself). R220's recovery ladder running is not a wait that ends a freeze.
 */
internal class FrozenMeter(private val toleranceMs: Long = 250) {
    private var lastPos = -1L
    private var sinceMs = -1L
    private var active = false
    private var startedAtMs = 0L
    private var aheadAtStart = 0L

    fun observe(nowMs: Long, w: PlayerWatch): FrozenReading {
        val eligible = w.firstFrame && w.wantsToPlay && (!w.waiting || w.recovering)
        val moved = lastPos < 0 || abs(w.positionMs - lastPos) > toleranceMs
        if (!eligible || moved) {
            val ended = finish(nowMs)
            lastPos = if (eligible) w.positionMs else -1L
            sinceMs = if (eligible) nowMs else -1L
            return FrozenReading(0, w.bufferedAheadMs, ended)
        }
        if (w.bufferedAheadMs < FROZEN_BUFFER_IN_HAND_MS) {
            // Stuck with the buffer gone (or unknown): not this kind. Whatever was frozen ends here; a new look starts now.
            val ended = finish(nowMs)
            sinceMs = nowMs
            return FrozenReading(0, w.bufferedAheadMs, ended)
        }
        val held = nowMs - sinceMs
        if (!active && held >= FROZEN_STOP_MS) {
            active = true
            startedAtMs = sinceMs
            aheadAtStart = w.bufferedAheadMs
        }
        return FrozenReading(if (active) held else 0L, w.bufferedAheadMs, null)
    }

    /** Closes a stop in progress now (the store is about to restream), returning it. */
    fun flush(nowMs: Long): FrozenStop? {
        val ended = finish(nowMs)
        lastPos = -1L
        sinceMs = -1L
        return ended
    }

    private fun finish(nowMs: Long): FrozenStop? {
        if (!active) return null
        active = false
        return FrozenStop(startedAtMs, (nowMs - startedAtMs).coerceAtLeast(0), lastPos, aheadAtStart)
    }
}

internal enum class FreezeKind { BUFFER_EMPTY, FROZEN_WITH_BUFFER }

/** What FR-5 does about a stop. */
internal sealed interface FreezeAction {
    /** FR-5.1 — cap the adaptive selection at the rung below the current one, in place. No restream. */
    data object CapInPlace : FreezeAction

    /**
     * FR-5.2, first freeze — restream once to our encoder at the rung below the frozen one ([belowBandwidthBps] is the
     * frozen variant's `BANDWIDTH`: only rungs strictly under it are served), in the other codec family where the device
     * has one ([leaveHevc]: HEVC froze, so ask for the H.264 SDR rungs).
     */
    data class RestreamOurs(val belowBandwidthBps: Long?, val leaveHevc: Boolean) : FreezeAction

    /** FR-5.2, second freeze — Jellyfin's stream (`not_our_encoder`), its top capped under the lowest frozen rung ([maxVideoBps], video bits/s). */
    data class RestreamJellyfin(val maxVideoBps: Long?, val leaveHevc: Boolean) : FreezeAction

    /** FR-5.2, third freeze — R237's error (Retry / Back). */
    data object Fail : FreezeAction
}

/**
 * FR-5 — the decision table, one per item:
 *
 * | stop                        | 1st                       | 2nd                          | 3rd   |
 * |-----------------------------|---------------------------|------------------------------|-------|
 * | buffer empty (at once)      | cap one rung down, in place | cap one rung down, in place | same  |
 * | position stuck, buffer ≥ 3 s | ours, rung below, other codec | Jellyfin, under the 1st    | error |
 *
 * Nothing goes above the rung that froze: every bound only ever lowers ([lowestFrozenBps]).
 */
internal class FreezeRecovery {
    var freezes = 0; private set
    var emptyBuffers = 0; private set
    /** The lowest `BANDWIDTH` that has frozen this item; every later bound sits under it. */
    var lowestFrozenBps: Long? = null; private set
    private var leftHevc = false

    fun decide(kind: FreezeKind, frozenBandwidthBps: Long? = null, frozenCodec: String? = null): FreezeAction = when (kind) {
        FreezeKind.BUFFER_EMPTY -> { emptyBuffers++; FreezeAction.CapInPlace }
        FreezeKind.FROZEN_WITH_BUFFER -> {
            freezes++
            frozenBandwidthBps?.takeIf { it > 0 }?.let { b -> lowestFrozenBps = lowestFrozenBps?.let { minOf(it, b) } ?: b }
            if (frozenCodec.equals("hevc", ignoreCase = true)) leftHevc = true
            when (freezes) {
                1 -> FreezeAction.RestreamOurs(lowestFrozenBps, leaveHevc = leftHevc)
                2 -> FreezeAction.RestreamJellyfin(lowestFrozenBps?.let { maxVideoBpsUnder(it) }, leaveHevc = leftHevc)
                else -> FreezeAction.Fail
            }
        }
    }

    companion object {
        /** The most audio a rung's `BANDWIDTH` carries beside its video (FR-313-7: 640 kb/s for 5.1 AC-3). */
        private const val MOST_AUDIO_BPS = 640_000L

        /**
         * The `max_video_bitrate` that keeps Jellyfin's top video under the rung whose `BANDWIDTH` is [bandwidthBps]: a
         * variant's `BANDWIDTH` is video × 1.5 plus audio, so this is under that rung's video whatever audio it carried,
         * and Jellyfin takes 0.9 of it as its top. Null when the rung is too small to bound.
         */
        fun maxVideoBpsUnder(bandwidthBps: Long): Long? = ((bandwidthBps - MOST_AUDIO_BPS) / 1.5).toLong().minus(1).takeIf { it > 0 }
    }
}

/**
 * FR-5.1 — the rung one below [currentBps] among the master's [rungs] (their `bitrate`s): the largest strictly under it,
 * or null when [currentBps] is already the lowest (nothing to cap to).
 */
internal fun rungBelow(rungs: List<Long>, currentBps: Long): Long? = rungs.filter { it in 1 until currentBps }.maxOrNull()

/** FR-5.4 — the one log line a recovery writes. */
internal fun recoveryLine(frozenHeight: Int?, frozenBandwidthBps: Long?, frozenCodec: String?, bufferedMs: Long, target: String): String =
    "recovery: froze on ${rungWords(frozenHeight, frozenBandwidthBps)}${frozenCodec?.let { " ($it)" } ?: ""} with ${bufferedMs / 1000} s buffered → $target (DEB-2205 FR-5)"

internal fun rungWords(height: Int?, bandwidthBps: Long?): String = when {
    height != null && bandwidthBps != null -> "${height}p@${bandwidthBps / 1000}k"
    bandwidthBps != null -> "${bandwidthBps / 1000}k"
    height != null -> "${height}p"
    else -> "an unknown rung"
}

/** FR-5.2 — the video family a MIME type names (`video/hevc` → `hevc`, `video/avc` → `h264`, `video/av01` → `av1`, VP9 → `vp9`); null for anything else. */
internal fun videoFamilyOfMime(mime: String?): String? = when (mime?.lowercase()) {
    "video/hevc", "video/dolby-vision" -> "hevc"
    "video/avc" -> "h264"
    "video/av01" -> "av1"
    "video/x-vnd.on2.vp9" -> "vp9"
    else -> null
}

/**
 * FR-1 — the player's own buffer-empty stalls and the store's frozen stops and recoveries, in time order. The delivery
 * stalls keep their own order (the server's stream record reads them by position in the list, FR-309-1).
 */
internal fun mergeStops(playerStalls: List<QoeStall>, storeStops: List<QoeStall>): List<QoeStall> =
    if (storeStops.isEmpty()) playerStalls else (playerStalls + storeStops).sortedBy { it.afterFirstFrameMs }
