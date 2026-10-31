package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.QoeStall
import dev.jellystructure.shared.tv.QoeStallCause

/**
 * DEB-3103 (DEB-3093 FR-6, FR-8) — a stream the viewer did not ask to have replaced is replaced behind the picture: the old one
 * plays on while the new one is asked for, fetched and warmed, and the player switches only when it is in hand.
 */

/** FR-6 — a failed call is retried at these delays, then at the last one, for as long as the buffer holds picture. */
internal val REPLACEMENT_RETRY_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L)

/** FR-6 — the new stream is asked for to start this far ahead of the playhead at most, so it is ready when the playhead gets there. */
internal const val REPLACEMENT_LEAD_MS = 6_000L

/** FR-6 — the held last frame (no shutter, no start screen, no cold-start moment) is kept this long; after it R218's own treatment applies. */
internal const val REPLACEMENT_HOLD_MS = 2_000L

/**
 * FR-6 — what to do after the [failedAttempts]th failed call (1 for the first): the delay before the next, or null to give up.
 * With picture in the buffer it never gives up (the viewer sees nothing). With the buffer empty it gives up once every retry has run,
 * which is when the error screen is the truth.
 */
internal fun replacementRetryDelayMs(failedAttempts: Int, bufferedAheadMs: Long, schedule: List<Long> = REPLACEMENT_RETRY_MS): Long? {
    val holds = bufferedAheadMs > 0
    if (!holds && failedAttempts > schedule.size) return null
    return schedule[(failedAttempts - 1).coerceIn(0, schedule.lastIndex)]
}

/**
 * FR-6 — where the new stream is asked to start: ahead of the playhead by what the old buffer can cover (leaving 2 s), at most
 * [REPLACEMENT_LEAD_MS]; at the playhead when the old stream is dead or has nothing buffered.
 */
internal fun replacementAtMs(positionMs: Long, bufferedAheadMs: Long, oldStreamDead: Boolean): Long =
    if (oldStreamDead || bufferedAheadMs <= 2_000L) positionMs else positionMs + (bufferedAheadMs - 2_000L).coerceAtMost(REPLACEMENT_LEAD_MS)

/** FR-8 — the `reason` of a `recovery` entry: why the stream was replaced. */
internal object ReplacementReason {
    const val ENCODER_FAILED = "encoder_failed"
    const val ENCODER_STUCK = "encoder_stuck"
    const val FROZEN = "frozen"
    const val DECODE_FAILED = "decode_failed"
    const val HANDOVER_NOW = "handover_now"
    const val HANDOVER_AHEAD = "handover_ahead"
    const val STALL_TO_LADDER = "stall_to_ladder"
    const val STEP_CAP = "step_cap"
}

/**
 * The stop a replacement makes, waiting for the new stream's first movement: [frozenHeight]/[frozenBps]/[frozenCodec] are the rung it
 * left. [startedAtMs] and [positionMs] are the switch's, not the decision's ([switched]): the old stream plays on until then, so a
 * clock started at the decision would end at once, and a stop of 0 s would be written for a 3 s wait.
 */
internal class PendingRecovery(
    var startedAtMs: Long, var positionMs: Long, val bufferAheadMs: Long,
    val frozenHeight: Int?, val frozenBps: Long?, val frozenCodec: String?, val target: String,
    /** FR-8 — why: one of [ReplacementReason]. */
    val reason: String,
    /** DEB-2205 (FR-4) — set for a hand-over (not a freeze): the words that replace "froze on …" in the log line. */
    val handover: String? = null,
    /** FR-5.3 — the climb waits 2 min after this one (a freeze or a hand-over); false for a step that is itself the climb. */
    val blocksClimb: Boolean = true,
    /** True from the decision to the switch: [finishRecovery] reads nothing while the old stream is the one playing. */
    var awaitingSwitch: Boolean = false,
) {
    fun switched(nowMs: Long, positionMs: Long) { startedAtMs = nowMs; this.positionMs = positionMs; awaitingSwitch = false }
}

/** What a finished recovery says: the one log line (every replacement writes it) and the `stalls_json` entry (only for a stop that lasted). */
internal class RecoveryEnd(val line: String, val stall: QoeStall?)

/**
 * FR-8 — one tick of a recovery's own watch. Null while it is still waiting: before the switch, or before the new stream has moved
 * (more than half a second past the switch) and [RECOVERY_WATCH_MS] has not run out. An entry is written only for a stop of
 * [FROZEN_STOP_MS] or more (FR-1: a replacement under 1 s is not a stop), cause `recovery`, with the rung it left and its reason.
 */
internal fun finishRecovery(
    r: PendingRecovery, w: PlayerWatch, nowMs: Long, landedHeight: Int?, landedBps: Long?, landedCodec: String?,
    afterFirstFrame: (Long) -> Long,
): RecoveryEnd? {
    if (r.awaitingSwitch) return null
    val moved = w.firstFrame && !w.waiting && w.wantsToPlay && w.positionMs > r.positionMs + 500
    if (!moved && nowMs - r.startedAtMs <= RECOVERY_WATCH_MS) return null
    val duration = nowMs - r.startedAtMs
    // FR-5.4 — one line per recovery, naming the rung that froze and the one it landed on.
    val landed = if (moved) "${r.target} ${rungWords(landedHeight, landedBps)}${landedCodec?.let { " $it" } ?: ""}"
        else "${r.target} (no picture within ${RECOVERY_WATCH_MS / 1000} s)"
    val line = r.handover?.let { handoverLine(it, r.bufferAheadMs, landed) } ?: recoveryLine(r.frozenHeight, r.frozenBps, r.frozenCodec, r.bufferAheadMs, landed)
    val stall = if (duration >= FROZEN_STOP_MS) {   // a stream that never moved within the window is a stop too
        val after = afterFirstFrame(r.startedAtMs)
        QoeStall(
            afterFirstFrameMs = after, positionMs = r.positionMs, bufferedMs = r.bufferAheadMs, durationMs = duration,
            phase = if (after < 10_000L) "start" else "mid", variantBps = landedBps, cause = QoeStallCause.RECOVERY,
            bufferAheadMs = r.bufferAheadMs, rungHeight = r.frozenHeight, rungBps = r.frozenBps, reason = r.reason,
        )
    } else null
    return RecoveryEnd("$line [${r.reason}]", stall)
}

/** FR-6 — how often a fetched replacement looks at the playhead while it waits for the place it was asked for. */
internal const val REPLACEMENT_SWITCH_POLL_MS = 250L

/** [block]'s result, cancellation excepted: `runCatching` would swallow it, and a cancelled replacement must stop, not retry. */
internal suspend fun <T> attempt(block: suspend () -> T): Result<T> =
    try { Result.success(block()) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { Result.failure(e) }
