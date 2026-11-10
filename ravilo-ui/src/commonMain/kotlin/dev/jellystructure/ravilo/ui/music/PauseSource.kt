package dev.jellystructure.ravilo.ui.music

/**
 * DEB-3034 (a-song-that-waits-says-so.md FR-7) — what sent a pause. The music engine reports it as `pause_by` on the
 * report that first says the song is paused, and the server writes it as the detail of the session timeline's `paused`
 * event. Anything that is not one of [known] omits the field.
 */
object PauseSource {
    /** An on-screen control. */
    const val SCREEN = "screen"
    /**
     * The desktop's Space (DEB-4133, R337 FR-R337-10 amended 2026-11-09). The key sends this value itself; until then it
     * was worked out from a press that followed the key within a window, because Space pressed the focused control.
     */
    const val KEYBOARD = "keyboard"
    /** The Dock menu (macOS). */
    const val DOCK = "dock"
    /** macOS's three Now Playing commands: media keys, Control Center and headphones reach the app through them. */
    const val NOW_PLAYING_PAUSE = "now_playing_pause"
    const val NOW_PLAYING_TOGGLE = "now_playing_toggle"
    const val NOW_PLAYING_STOP = "now_playing_stop"
    /** A session remote, or the Jellyfin dashboard. */
    const val REMOTE = "remote"
    /** The engine's own stop, such as the end of the queue. */
    const val ENGINE = "engine"
    /** Android's Media3 session (a notification button, a headset) and its play-when-ready change for audio focus. */
    const val MEDIA_SESSION = "media_session"
    const val AUDIO_FOCUS = "audio_focus"

    val known: Set<String> = setOf(SCREEN, KEYBOARD, DOCK, NOW_PLAYING_PAUSE, NOW_PLAYING_TOGGLE, NOW_PLAYING_STOP, REMOTE, ENGINE, MEDIA_SESSION, AUDIO_FOCUS)

    /** The wire value for [by]: itself when it is one of [known], else null (the field is left out). */
    fun wire(by: String?): String? = by?.takeIf { it in known }
}
