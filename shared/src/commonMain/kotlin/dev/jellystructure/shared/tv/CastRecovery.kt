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

/**
 * DEB-4637 (DEB-2205 FR-5) — the receiver's frozen-picture watch, sampled once a second. DEB-5928: [COUNT_DELIVERY] is a stop while the
 * video element has nothing to play past the frame on screen, whatever `buffered` reads.
 */
enum class CastFrozenAction { NONE, COUNT_FROZEN, RECOVER_FROZEN, RECOVER_STUCK, COUNT_DELIVERY }

/**
 * [readyState] is the video element's (`HAVE_FUTURE_DATA` = 3, `HAVE_ENOUGH_DATA` = 4); a negative value means it could not be read
 * and the stop is classed as before. CAF's `BUFFERING` is not used: it also covers DEB-4636's clock stopped with a full buffer.
 */
fun castFrozenAction(
    positionMs: Long, lastPositionMs: Long, lastMoveAtMs: Long, nowMs: Long, bufferedAheadMs: Long,
    wantsPlay: Boolean, seeking: Boolean, recovering: Boolean, firstFrameSeen: Boolean,
    readyState: Int = 4,
): CastFrozenAction {
    if (!wantsPlay || seeking || recovering || !firstFrameSeen || positionMs != lastPositionMs) return CastFrozenAction.NONE
    val stillMs = (nowMs - lastMoveAtMs).coerceAtLeast(0)
    if (stillMs >= 30_000L) return CastFrozenAction.RECOVER_STUCK
    if (readyState in 0..2) return if (stillMs >= 1_000L) CastFrozenAction.COUNT_DELIVERY else CastFrozenAction.NONE
    if (bufferedAheadMs < 3_000L) return CastFrozenAction.NONE
    return when {
        stillMs >= 5_000L -> CastFrozenAction.RECOVER_FROZEN
        stillMs >= 1_000L -> CastFrozenAction.COUNT_FROZEN
        else -> CastFrozenAction.NONE
    }
}

/**
 * DEB-5928 (DEB-2205 FR-5) — the steps of a `frozen` or `encoder_stuck` recovery are per item, whoever served the stream that froze:
 * our encoder first, Jellyfin second, then the error. [castRecoveryStep] keeps deciding errors, where a stream from Jellyfin ends.
 */
fun castFrozenRecoveryStep(askedOursAgain: Boolean, askedJellyfin: Boolean): CastRecoveryStep =
    castRecoveryStep(ours = true, askedOursAgain = askedOursAgain, askedJellyfin = askedJellyfin)

/** Master BANDWIDTH includes audio and overhead; the restream cap is video bits/s. */
fun castFrozenVideoCap(rungsBps: List<Long>, frozenBps: Long?, deviceMaxVideo: Int): Int {
    val frozen = frozenBps?.takeIf { it > 0 } ?: return deviceMaxVideo
    val target = rungsBps.filter { it > 0 }.distinct().sorted().lastOrNull { it < frozen } ?: frozen
    val video = RestreamStepper.videoBpsOf(target)?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: return deviceMaxVideo
    return if (deviceMaxVideo > 0) minOf(deviceMaxVideo, video) else video
}


/** DEB-4543 FR-3 — counters and stops belong to one LOAD, including a restream of the same item. */
class CastQoeCounters {
    var rebuffers = 0
    var rebufferMs = 0L
    var down = 0
    var up = 0
    var frozenCount = 0
    var frozenMs = 0L
    val stalls = mutableListOf<QoeStall>()

    fun reset() {
        rebuffers = 0; rebufferMs = 0L; down = 0; up = 0
        frozenCount = 0; frozenMs = 0L; stalls.clear()
    }
}

/** DEB-4543 FR-4 — only delivery stalls on a rung with no lower choice can ask for a ladder, once per item. */
fun castShouldAskForLadder(masterRungsBps: List<Long>, playingBps: Long?, stalls: List<QoeStall>, alreadyAsked: Boolean): Boolean {
    if (alreadyAsked || masterRungsBps.isEmpty()) return false
    val playing = playingBps ?: masterRungsBps.singleOrNull() ?: return false
    if (masterRungsBps.any { it > 0 && it < playing }) return false
    val delivery = stalls.filter { it.cause == QoeStallCause.BUFFER_EMPTY && it.durationMs >= 1_000L }
    val last = delivery.lastOrNull() ?: return false
    return last.durationMs >= 4_000L || delivery.dropLast(1).any {
        last.afterFirstFrameMs - it.afterFirstFrameMs in 0L..120_000L
    }
}

/** DEB-4543 FR-6 — a failed load names its HTTP status when Shaka has one, otherwise -1. */
fun castStartFailureStatus(firstFrameSeen: Boolean, httpStatus: Int?): Int? =
    if (firstFrameSeen) null else (httpStatus?.takeIf { it in 100..599 } ?: -1)

/** DEB-5929 (FR-6) — which of a failed start's two posts a [CastHeldPosts] step is about. */
enum class CastHeldKind { REPORT, STOP }

/** What the receiver does next for a failed start's held posts: send one now, look again in [Wait.ms], or nothing more. */
sealed interface CastHeldStep {
    data class Send(val kind: CastHeldKind) : CastHeldStep
    data class Wait(val ms: Long) : CastHeldStep
    data object Done : CastHeldStep
}

/**
 * DEB-5929 (FR-6) — the final QoE report and the stop of an item that never reached a first frame, held until the backend answers.
 * No answer or a 5xx is asked again after 5 s, then at doubling gaps up to 30 s; a 2xx or a 4xx ends that post. The report goes
 * first and the stop only once the report has been answered. The hold ends [HOLD_MS] after [failedAtMs]. A new LOAD drops the stop
 * ([dropStop], FR-289-2's hazard) and leaves the report on its schedule. Nothing is persisted; the clock is the caller's.
 */
class CastHeldPosts(private val failedAtMs: Long) {
    private var reportDone = false
    private var stopDone = false
    private var failures = 0
    private var dueAtMs = failedAtMs

    /** What to do at [nowMs]. After a [CastHeldStep.Send] the caller reports its outcome with [sent] before asking again. */
    fun step(nowMs: Long): CastHeldStep {
        if (nowMs - failedAtMs >= HOLD_MS) return CastHeldStep.Done
        val kind = when {
            !reportDone -> CastHeldKind.REPORT
            !stopDone -> CastHeldKind.STOP
            else -> return CastHeldStep.Done
        }
        return if (nowMs >= dueAtMs) CastHeldStep.Send(kind) else CastHeldStep.Wait(dueAtMs - nowMs)
    }

    /** The outcome of the post [step] asked for: [status] is the HTTP status, null when nothing answered. */
    fun sent(kind: CastHeldKind, status: Int?, nowMs: Long) {
        if (castHeldAnswered(status)) {
            if (kind == CastHeldKind.REPORT) reportDone = true else stopDone = true
            failures = 0
            dueAtMs = nowMs
        } else {
            failures++
            dueAtMs = nowMs + castHeldGapMs(failures)
        }
    }

    /** A new LOAD: the stop would end the new session, so it is never sent; the report is unaffected. */
    fun dropStop() { stopDone = true }

    companion object {
        /** The hold ends this long after the failure. */
        const val HOLD_MS = 600_000L
    }
}

/** DEB-5929 (FR-6) — a 2xx or a 4xx is an answer and ends that post's retries; no answer or a 5xx does not. */
fun castHeldAnswered(status: Int?): Boolean = status != null && status < 500

/** DEB-5929 (FR-6) — the wait after the [failures]th failed send (1 for the first): 5, 10, 20, then 30 s. */
fun castHeldGapMs(failures: Int): Long = (5_000L shl (failures - 1).coerceIn(0, 3)).coerceAtMost(30_000L)
