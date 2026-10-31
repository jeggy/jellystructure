package dev.jellystructure.shared.tv

/**
 * DEB-3105 (DEB-3093 FR-9, FR-4 to FR-6) — what the Chromecast receiver does when the stream it is playing fails. CAF resets its
 * player on every LOAD, so the receiver cannot keep the old stream playing behind a replacement the way Android does; what it can do
 * is the part Android's `PlayerStore` does around that: ask the server for the stream again, fetch its start before loading it, and
 * put the stream the encoder cannot give behind Jellyfin's. This file is the decisions; `Receiver` carries them out.
 */
object CastRecovery {
    /** FR-6 — a failed restream call or warm-up is asked again after these delays (the same four as Android's `REPLACEMENT_RETRY_MS`). */
    val RETRY_MS: List<Long> = listOf(1_000L, 2_000L, 4_000L, 8_000L)

    /** FR-5 — a buffering moment draws nothing on the receiver's screen for this long. */
    const val DRAW_AFTER_MS = 2_000L

    /** FR-5 — Shaka resumes (and starts) once this many seconds are buffered. */
    const val RESUME_BUFFER_SEC = 4

    /** FR-3 — Shaka's request timeout in seconds, the same 20 s Android's read timeout is. */
    const val REQUEST_TIMEOUT_SEC = 20

    /** FR-4 — Shaka's retry delays are `baseDelay × backoffFactor^n`: 0.5, 1, 2, 4 s between five attempts. */
    const val RETRY_ATTEMPTS = 5
    const val RETRY_BASE_DELAY_MS = 500
    const val RETRY_BACKOFF = 2.0
    const val RETRY_FUZZ = 0.2
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
