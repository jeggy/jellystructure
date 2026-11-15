package dev.jellystructure.shared.tv

/**
 * DEB-3105 (DEB-3093 FR-9, FR-4 to FR-6) — what the Chromecast receiver does when the stream it is playing fails. CAF resets its
 * player on every LOAD, so the receiver cannot keep the old stream playing behind a replacement the way Android does; what it can do
 * is the part Android's `PlayerStore` does around that: ask the server for the stream again, fetch its start before loading it, and
 * put the stream the encoder cannot give behind Jellyfin's. This file is the decisions; `Receiver` carries them out.
 */
data class CastShakaRetryParameters(
    val maxAttempts: Int,
    val baseDelayMs: Int,
    val backoffFactor: Double,
    val fuzzFactor: Double,
    val timeoutMs: Int,
)

object CastRecovery {
    /** FR-6 — a failed restream call or warm-up is asked again after these delays (the same four as Android's `REPLACEMENT_RETRY_MS`). */
    val RETRY_MS: List<Long> = listOf(1_000L, 2_000L, 4_000L, 8_000L)

    /** FR-5 — a buffering moment draws nothing on the receiver's screen for this long. */
    const val DRAW_AFTER_MS = 2_000L

    /** FR-5 — Shaka resumes (and starts) once this many seconds are buffered. */
    const val RESUME_BUFFER_SEC = 4

    /** FR-3 and FR-4 — values passed to Shaka, in its units (milliseconds for timeout and base delay). */
    val shakaRetryParameters = CastShakaRetryParameters(
        maxAttempts = 5,
        baseDelayMs = 500,
        backoffFactor = 2.0,
        fuzzFactor = 0.2,
        timeoutMs = 20_000,
    )
}

/** What to do with a stream that has failed (FR-6: our encoder is asked again first, Jellyfin's stream is the second try). */
enum class CastRecoveryStep { OURS_AGAIN, JELLYFIN, GIVE_UP }

/**
 * The next try for a failed stream: [ours] = it came from our encoder. The first failure asks for it again (FR-2 restarts the job),
 * the second asks Jellyfin; any other failure, and a stream from Jellyfin that failed, ends on the receiver's error. [askedOursAgain]
 * and [askedJellyfin] are what this item has been given already.
 */
fun castRecoveryStep(ours: Boolean, askedOursAgain: Boolean, askedJellyfin: Boolean): CastRecoveryStep = when {
    !ours -> CastRecoveryStep.GIVE_UP
    !askedOursAgain -> CastRecoveryStep.OURS_AGAIN
    !askedJellyfin -> CastRecoveryStep.JELLYFIN
    else -> CastRecoveryStep.GIVE_UP
}

/**
 * Whether a finished-with-error player is a stream to replace and not a load that never played. Only a film that had reached its first
 * picture is recovered; music, a station, an end and a load that failed before any picture keep what they did (the error screen).
 */
fun castShouldRecover(music: Boolean, radio: Boolean, reachedEnd: Boolean, played: Boolean, hasItem: Boolean): Boolean =
    hasItem && !music && !radio && !reachedEnd && played

/**
 * The wait before the next ask after the [failedCalls]th failed call or warm-up (1 for the first), or null to give up. The stream is
 * dead and nothing is buffered, so once the four delays have run the error screen is the truth (Android's `replacementRetryDelayMs`
 * with an empty buffer).
 */
fun castRecoveryRetryMs(failedCalls: Int): Long? = CastRecovery.RETRY_MS.getOrNull(failedCalls - 1)

/** FR-5 — whether the receiver's buffering view should be on screen: only after the wait has lasted [CastRecovery.DRAW_AFTER_MS]. */
fun castDrawsBuffering(bufferingForMs: Long): Boolean = bufferingForMs >= CastRecovery.DRAW_AFTER_MS

/** DEB-4637 (DEB-2205 FR-5) — the receiver's frozen-picture watch, sampled once a second. */
enum class CastFrozenAction { NONE, COUNT_FROZEN, RECOVER_FROZEN, RECOVER_STUCK }

fun castFrozenAction(
    positionMs: Long, lastPositionMs: Long, lastMoveAtMs: Long, nowMs: Long, bufferedAheadMs: Long,
    wantsPlay: Boolean, seeking: Boolean, recovering: Boolean, firstFrameSeen: Boolean,
): CastFrozenAction {
    if (!wantsPlay || seeking || recovering || !firstFrameSeen || positionMs != lastPositionMs) return CastFrozenAction.NONE
    val stillMs = (nowMs - lastMoveAtMs).coerceAtLeast(0)
    if (stillMs >= 30_000L) return CastFrozenAction.RECOVER_STUCK
    if (bufferedAheadMs < 3_000L) return CastFrozenAction.NONE
    return when {
        stillMs >= 5_000L -> CastFrozenAction.RECOVER_FROZEN
        stillMs >= 1_000L -> CastFrozenAction.COUNT_FROZEN
        else -> CastFrozenAction.NONE
    }
}

/** Master BANDWIDTH includes audio and overhead; the restream cap is video bits/s. */
fun castFrozenVideoCap(rungsBps: List<Long>, frozenBps: Long?, deviceMaxVideo: Int): Int {
    val frozen = frozenBps?.takeIf { it > 0 } ?: return deviceMaxVideo
    val target = rungsBps.filter { it > 0 }.distinct().sorted().lastOrNull { it < frozen } ?: frozen
    val video = RestreamStepper.videoBpsOf(target)?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: return deviceMaxVideo
    return if (deviceMaxVideo > 0) minOf(deviceMaxVideo, video) else video
}
