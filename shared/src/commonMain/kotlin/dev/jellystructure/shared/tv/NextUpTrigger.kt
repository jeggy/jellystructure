package dev.jellystructure.shared.tv

/**
 * R351 (FR-R351-13) — when the Cast receiver puts up its next-up card: at the credits marker when it is a trusted one
 * ([trustedCreditsStartMs]) — and never later than the whole countdown plus [NEXT_UP_END_MARGIN_MS] before the end.
 *
 * R384 (FR-R384-1) — with no trusted marker the card goes up [NEXT_UP_NO_MARKER_MS] before the end, whatever the
 * countdown: the episode plays to its end and the countdown runs on over the finished picture. It was 20 s before the
 * end, which on a 1-minute episode put the card over the last third and, with autoplay, never played the last ~14 s.
 *
 * The cap is the fix. The ffmpeg credits heuristic (black + silence in the last 180 s) often finds the final fade to
 * black: on the re-test's episodes the marker sat 1.3–1.8 s before the end of the file. The receiver trusted it, put
 * the card up two seconds before the stream ended, and the end of the stream loaded the next episode after two of
 * the six ticks; the sender's *Play in N* pills were up for the same two seconds. The marker still wins whenever it
 * leaves room for the countdown.
 *
 * [durationMs] is the receiver's own media duration; null when it does not know one yet (nothing is shown then).
 */
fun nextUpStartMs(durationMs: Long, creditsStartMs: Long?, countdownSecs: Int): Long? {
    if (durationMs <= 0L) return null
    val credits = trustedCreditsStartMs(creditsStartMs, durationMs)
        ?: return (durationMs - NEXT_UP_NO_MARKER_MS).coerceAtLeast(0L)
    val latest = durationMs - countdownSecs.coerceAtLeast(0) * 1_000L - NEXT_UP_END_MARGIN_MS
    return minOf(credits, latest).coerceAtLeast(0L)
}

/** R384 (FR-R384-1) — the card's start for a title without a trusted credits marker: this long before the end. It was
 *  R111's 20 s (`NEXT_UP_FALLBACK_MS`). Every player and receiver uses this one value. */
const val NEXT_UP_NO_MARKER_MS = 1_000L

/** What the countdown keeps in hand before the end (a trusted marker only): the player's time reports are not continuous, and a converted
 *  stream's last segment can end a little before the duration its playlist states. */
const val NEXT_UP_END_MARGIN_MS = 2_000L
