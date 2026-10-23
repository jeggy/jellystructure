package dev.jellystructure.shared.tv

/** DEB-2312 — how far the media time must have moved under BUFFERING before the receiver presses play itself. */
const val CAST_HEAL_MOVED_MS = 2_000L

/**
 * DEB-2312 (DEB-2252, FR-R330-7) — whether a cast receiver should call `playerManager.play()` now. Found live (DEB-2288,
 * DEB-2310): after the receiver's own load of a direct song, CAF stayed in BUFFERING while the speaker played, so no
 * `MEDIA_STATUS` followed and the Mac's bar sat at 0:00; one press of play settled it. This is that press, automated.
 *
 * True when a music song is not paused, CAF still says BUFFERING, `TIME_UPDATE` has moved the media time ([timeMs]) forward
 * by [CAST_HEAL_MOVED_MS] or more from where BUFFERING began ([bufferingFromMs], null when not noted), and this song has not
 * been pressed for yet ([healedKey] is the last key pressed for, [songKey] this load's: the song's id plus the load).
 * Never for a film, never while paused, never twice for one song.
 */
fun castShouldPressPlay(
    music: Boolean,
    paused: Boolean,
    playerState: String?,
    bufferingFromMs: Long?,
    timeMs: Long,
    healedKey: String?,
    songKey: String,
): Boolean = music && !paused && playerState == "BUFFERING" && bufferingFromMs != null &&
    timeMs - bufferingFromMs >= CAST_HEAL_MOVED_MS && healedKey != songKey
