package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable

/**
 * Pauses the player when the host activity/page moves to the background and resumes it when
 * it returns to the foreground — so pressing Home on the remote doesn't leave audio playing.
 *
 * wasPlaying: lambda returning the current user-intended play state (from PlayerScreen's polled
 * isPlaying var) so a manually-paused player is NOT auto-resumed on return.
 *
 * onBackground/onForeground: bug fix — pausing the player was all this used to do, so backgrounding the
 * app (Home on the remote, a swipe-kill afterwards, HDMI off, …) left the playback session open: the
 * progress heartbeat kept ticking with isPaused = true, Jellyfin kept listing the device as streaming,
 * and the resume position kept being re-reported instead of being finalised. onBackground now ends the
 * session (with the real final position) and onForeground re-arms it if the viewer actually comes back.
 * Only a true background/foreground transition invokes them — never the initial lifecycle sync.
 */
@Composable
expect fun PlayerLifecycleEffect(
    player: RaviloPlayer,
    wasPlaying: () -> Boolean,
    onBackground: () -> Unit,
    onForeground: () -> Unit,
)
