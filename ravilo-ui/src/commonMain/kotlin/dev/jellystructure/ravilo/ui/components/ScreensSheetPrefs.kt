package dev.jellystructure.ravilo.ui.components

/**
 * R265 — the two things the "Play on a TV" sheet remembers on THIS phone, and nothing else:
 * whether *All your TVs* was left open (FR-R265-3, "remembering its open state per device") and which
 * TV was used last, which is pinned to the top of its tier (open question 3, answered *yes*).
 *
 * Per device, not per viewer, and not on the server: which list a thumb last opened is a property of
 * the phone in the hand. Its own storage, apart from the session store, so a sign-out does not reset it.
 */
expect object ScreensSheetPrefs {
    fun tier2Open(): Boolean
    fun setTier2Open(open: Boolean)
    /** A screen's `deviceId` or a Chromecast route's id — whichever was tapped last. */
    fun lastDevice(): String?
    fun setLastDevice(id: String)
}
