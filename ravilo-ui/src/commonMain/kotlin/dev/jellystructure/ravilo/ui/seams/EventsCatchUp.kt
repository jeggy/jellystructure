package dev.jellystructure.ravilo.ui.seams

/** R293 (FR-R293-5) — a remote command (`play_item`, `navigate`, `playstate_command`, `player_command`)
 *  is applied only while a profile is active AND the app is on screen; otherwise it is dropped and
 *  logged, never queued. A backgrounded app cannot bring itself to the front on Android 10+, so applying
 *  one would start a player nobody sees. */
fun acceptsRemoteCommand(onScreen: Boolean, signedIn: Boolean): Boolean = onScreen && signedIn

/** R354 (FR-R354-5) — a playback command (pause, seek, next, volume …) is also applied off screen while playing media
 *  holds the socket open: it acts on what is already playing and never starts a player nobody sees. `play_item` and
 *  `navigate` keep [acceptsRemoteCommand]. */
fun acceptsPlayerCommand(onScreen: Boolean, mediaHoldsSocket: Boolean): Boolean = onScreen || mediaHoldsSocket

/** R354 (FR-R354-9a) — the Jellyfin dashboard's *Send message* is shown only while the app is on screen: off screen
 *  (the socket held open by music playing here) it is dropped and logged — never queued, never a notification. */
fun acceptsServerMessage(onScreen: Boolean): Boolean = onScreen

/**
 * R354 (FR-R354-5, amends R293 FR-R293-1) — playing music holds the events socket open off screen, and for [tailMs]
 * after it pauses, so the Jellyfin dashboard can pause and resume it. Fed every state change and a periodic tick.
 */
class MediaSocketHold(private val tailMs: Long = 10 * 60_000L) {
    private var pausedAtMs: Long? = null
    private var playing = false

    /** A queue loaded paused that never played (the Playing tab's last song) holds nothing; a pause starts the tail. */
    fun update(active: Boolean, playing: Boolean, nowMs: Long) {
        when {
            !active -> { pausedAtMs = null; this.playing = false }
            playing -> { pausedAtMs = null; this.playing = true }
            this.playing -> { pausedAtMs = nowMs; this.playing = false }
        }
    }

    fun holds(nowMs: Long): Boolean = playing || pausedAtMs?.let { nowMs - it < tailMs } == true
}
