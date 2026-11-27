package dev.jellystructure.shared.tv

/**
 * DEB-5843 (FR-1) — "Are you still watching?": whether a series auto-advance may start the next episode or must ask first.
 * The three players (`ravilo-ui`, the Samsung app, the Chromecast receiver) all call this one rule.
 *
 * It counts unattended auto-advances: next episodes started by a countdown running out since the viewer's last input.
 * The ask is due when this advance would be the [STILL_WATCHING_EPISODES]th unattended one (episodes 1, 2 and 3 played,
 * the 4th asks). A TV-class player also needs [STILL_WATCHING_TV_MS] since the last input; elsewhere the count alone decides.
 */
const val STILL_WATCHING_EPISODES = 3

/** On a TV-class player the ask also needs this long since the last input: three short episodes do not ask on a TV. */
const val STILL_WATCHING_TV_MS = 90L * 60_000L

/** An ask nobody answers for this long closes the player as Back would (FR-4). */
const val STILL_WATCHING_CLOSE_MS = 30L * 60_000L

/** The decision as a pure function: [unattended] advances already made since the last input, [sinceInputMs] since it. */
fun stillWatchingDue(unattended: Int, sinceInputMs: Long, tvClass: Boolean): Boolean {
    if (unattended + 1 < STILL_WATCHING_EPISODES) return false
    return !tvClass || sinceInputMs >= STILL_WATCHING_TV_MS
}

/**
 * The app-run state behind [stillWatchingDue]: one plain `Int` and one plain `Long`, no Compose state, so the input hook
 * (FR-2) costs one write and nothing reads it while composing. [onInput] is any viewer input, including a hand-started
 * play, a rail pick, a cast or a hand-over; [decide] counts the advance when it lets the next
 * episode start by itself.
 */
class StillWatching(private val tvClass: Boolean, startMs: Long = 0L) {
    var unattended: Int = 0
        private set
    var lastInputMs: Long = startMs
        private set

    fun onInput(nowMs: Long) {
        unattended = 0
        lastInputMs = nowMs
    }

    /** True when the countdown running out must ask instead of starting the next episode. Does not change the count. */
    fun askIsDue(nowMs: Long): Boolean = stillWatchingDue(unattended, nowMs - lastInputMs, tvClass)

    /** The next episode starts by itself, unattended. */
    fun onAutoAdvance() {
        unattended++
    }

    /** The one call a player makes when its countdown ends: true = ask (do not advance), false = advance, counted. */
    fun decide(nowMs: Long): Boolean {
        if (askIsDue(nowMs)) return true
        onAutoAdvance()
        return false
    }
}
