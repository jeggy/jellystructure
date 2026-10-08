package dev.jellystructure.ravilo.ui.seams

import androidx.media3.session.MediaSession

/**
 * R266 (dev review item 4) — the seam between the TV player's ONE Media3 session (R44, created and released in
 * [RaviloPlayer]) and Cast Connect's receiver in ravilo-android, which hands that session's token to
 * `CastReceiverContext`'s `MediaManager`. ravilo-ui knows nothing of the Cast TV SDK; ravilo-android sets [listener].
 */
object TvPlayerSessionHooks {
    /** True while a Cast Connect load is playing: R192's visibility toggle then leaves the session alone. Becomes true
     *  when the session of the player a cast opened appears ([castAccepted] first), false once that session goes. */
    @Volatile var castDriving: Boolean = false
        private set
    @Volatile private var castAwaiting: Boolean = false

    /** Called on the thread that changed the session (the player's, i.e. main) with the live one, or null. */
    @Volatile var listener: ((MediaSession?) -> Unit)? = null

    @Volatile var current: MediaSession? = null
        private set

    /** The receiver accepted a load: the next session to appear is the one the cast drives. */
    fun castAccepted() { castAwaiting = true }

    /** The receiver stopped (the app left the screen): nothing is driven by a cast any more. */
    fun castEnded() { castAwaiting = false; castDriving = false }

    /** [old] → [new] on one player. A release that is not of the current session (the previous player's, disposed
     *  after the next one built its own) changes nothing. */
    internal fun sessionChanged(old: MediaSession?, new: MediaSession?) {
        if (new == null && current !== old) return
        current = new
        if (new == null) castDriving = false
        else if (castAwaiting) { castAwaiting = false; castDriving = true }
        runCatching { listener?.invoke(new) }
    }
}
