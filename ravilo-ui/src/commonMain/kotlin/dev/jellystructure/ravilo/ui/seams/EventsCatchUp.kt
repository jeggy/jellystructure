package dev.jellystructure.ravilo.ui.seams

/**
 * R293 (FR-R293-3/4, dev review items 2 and 9) — what one events-socket open owes the screen.
 *
 * Before this phase every open emitted an unconditional refresh: a config pull *and* a Home rebuild,
 * ~40 an hour on a TV that reconnected once a minute in standby. Now an open is a config-rev check:
 * the config refreshes only when the rev moved (the same rule R141's poll always had), and Home
 * refreshes only when the app was disconnected longer than the server's push stream can be assumed to
 * have covered ([homeGapMs], open question 2's 30 s) — a `home_changed` push carries no rev, so the
 * gap is the only signal that one may have been missed. The very first open in a process refreshes
 * the config (it is the app's own start-up pull) and never Home (the Home store loads itself).
 */
class EventsCatchUp(private val homeGapMs: Long = 30_000L) {
    data class Decision(val refreshConfig: Boolean, val refreshHome: Boolean)

    var seenRev: Long = -1L
        private set
    private var closedAtMs: Long? = null
    private var everOpened = false

    /** [rev] is the server's current config rev (null when the check failed — then nothing is assumed
     *  to have moved, the poll will catch it). */
    fun onOpen(rev: Long?, nowMs: Long): Decision {
        val first = !everOpened
        everOpened = true
        val moved = rev != null && rev != seenRev
        if (rev != null) seenRev = rev
        val gap = closedAtMs?.let { nowMs - it }
        val home = !first && (gap == null || gap > homeGapMs)
        return Decision(refreshConfig = moved || first, refreshHome = home)
    }

    fun onClosed(nowMs: Long) { closedAtMs = nowMs }

    /** R141's poll: true when [rev] advanced past what the socket or an earlier poll already saw. */
    fun onPollRev(rev: Long): Boolean {
        if (rev == seenRev) return false
        seenRev = rev
        return true
    }
}

/** R293 (FR-R293-5) — a remote command (`play_item`, `navigate`, `playstate_command`, `player_command`)
 *  is applied only while a profile is active AND the app is on screen; otherwise it is dropped and
 *  logged, never queued. A backgrounded app cannot bring itself to the front on Android 10+, so applying
 *  one would start a player nobody sees. */
fun acceptsRemoteCommand(onScreen: Boolean, signedIn: Boolean): Boolean = onScreen && signedIn
