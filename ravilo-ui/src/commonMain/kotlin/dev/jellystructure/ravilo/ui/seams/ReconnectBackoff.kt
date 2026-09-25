package dev.jellystructure.ravilo.ui.seams

import kotlin.random.Random

/**
 * R293 (FR-R293-4, dev review item 3) — the one reconnect backoff for every socket a Ravilo client
 * keeps open (`/api/tv/events` in `RaviloApp`, `/api/remote/events` in [ScreenSender]).
 *
 * Before this phase any socket held open ≥ 2 s counted as healthy and reset the delay to 1 s, so a
 * socket that died every minute was reopened every minute, forever (1,326 times in 29 hours on one
 * TV). A socket is healthy only after [healthyAfterMs] (5 minutes); one that ends sooner doubles the
 * delay up to [maxMs] (60 s), with ±[jitter] so a household's TVs do not reconnect in lockstep.
 * Only a healthy socket resets it.
 */
class ReconnectBackoff(
    private val baseMs: Long = 1_000L,
    private val maxMs: Long = 60_000L,
    private val healthyAfterMs: Long = 5 * 60_000L,
    private val jitter: Double = 0.2,
    private val random: Random = Random.Default,
) {
    /** The un-jittered delay the next failure will produce; exposed for tests and the socket log. */
    var currentMs: Long = baseMs
        private set

    /** Records that a socket ended after [heldOpenMs] (0 when it never opened) and returns the delay
     *  before the next attempt, jittered. */
    fun next(heldOpenMs: Long): Long {
        val delay = if (heldOpenMs >= healthyAfterMs) {
            currentMs = baseMs
            baseMs
        } else {
            val d = currentMs
            currentMs = (currentMs * 2).coerceAtMost(maxMs)
            d
        }
        val spread = (delay * jitter).toLong()
        return if (spread <= 0) delay else delay + random.nextLong(-spread, spread + 1)
    }
}
