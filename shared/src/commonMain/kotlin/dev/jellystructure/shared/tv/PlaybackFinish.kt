package dev.jellystructure.shared.tv

/**
 * R347 (FR-R347-1) — one rule for "this item is finished", used by the player (the tick on advance and on
 * *Skip credits*), by the player's stop, and by the server's stop (`PlaybackService.stopPlayback`).
 *
 * Finished = at or past 90 % of the duration, or at or past the credits marker when it is a trusted one.
 * Trusted is the player's own rule from the auto-advance fix ([trustedCreditsStartMs]): positive, inside the
 * duration, and in the back half — a marker anywhere else belongs to another stream and is ignored, so an
 * item without a trusted marker keeps the plain 90 % rule (FR-R347-4).
 */
fun playbackFinished(positionMs: Long, durationMs: Long, creditsStartMs: Long?): Boolean {
    if (durationMs <= 0L || positionMs <= 0L) return false
    if (positionMs >= durationMs * FINISHED_PERCENT / 100) return true
    val credits = trustedCreditsStartMs(creditsStartMs, durationMs) ?: return false
    return positionMs >= credits
}

/** The credits marker when it plausibly belongs to this stream (see [playbackFinished]); null otherwise. */
fun trustedCreditsStartMs(creditsStartMs: Long?, durationMs: Long): Long? =
    creditsStartMs?.takeIf { durationMs > 0 && it > 0 && it < durationMs && it >= (durationMs * CREDITS_MARKER_MIN_FRACTION).toLong() }

/** R142's threshold: Jellyfin's own default *MaxResumePct*. */
const val FINISHED_PERCENT = 90L

/** A trusted credits marker never sits in the first half of a title (the auto-advance retry-loop fix). */
const val CREDITS_MARKER_MIN_FRACTION = 0.5
