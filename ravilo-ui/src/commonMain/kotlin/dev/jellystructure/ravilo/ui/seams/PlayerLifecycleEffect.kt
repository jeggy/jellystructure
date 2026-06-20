package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable

/**
 * Pauses the player when the host activity/page moves to the background and resumes it when
 * it returns to the foreground — so pressing Home on the remote doesn't leave audio playing.
 *
 * wasPlaying: lambda returning the current user-intended play state (from PlayerScreen's polled
 * isPlaying var) so a manually-paused player is NOT auto-resumed on return.
 */
@Composable
expect fun PlayerLifecycleEffect(player: RaviloPlayer, wasPlaying: () -> Boolean)
