package dev.jellystructure.ravilo.ui.screens

/**
 * R248 (FR-R248-1/2) — decides whether a screen that is returned to (Home, a channel page) still needs
 * its own silent re-pull, or whether the server's `home_changed` push already refreshed the retained
 * store while the screen was away. One refresh per return, not two: the event wins when it arrived
 * first; the return re-pull is the fallback for a device whose events socket is down.
 *
 * Pure state so the rule is unit-testable without a store or a network client.
 */
internal class ReturnRefreshGate {
    private var away = false
    private var refreshedWhileAway = false

    /** The screen left the composition (navigated forward — to the player, a detail page…). */
    fun onLeave() { away = true; refreshedWhileAway = false }

    /** A server-driven refresh ran. Remembered only while the screen is away: an event that lands
     *  while the screen is visible says nothing about what the *next* return will need. */
    fun onEventRefresh() { if (away) refreshedWhileAway = true }

    /** Called on re-entry: true when the screen must refresh itself, false when the event already did. */
    fun consumeReturn(): Boolean {
        val skip = away && refreshedWhileAway
        away = false
        refreshedWhileAway = false
        return !skip
    }
}
