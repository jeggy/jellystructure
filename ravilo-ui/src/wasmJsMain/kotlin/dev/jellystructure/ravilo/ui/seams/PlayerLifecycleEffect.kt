package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable

@Composable
actual fun PlayerLifecycleEffect(
    player: RaviloPlayer,
    wasPlaying: () -> Boolean,
    onBackground: (wasPlaying: Boolean) -> Unit,
    onForeground: () -> Unit,
) {
    // Browser tab visibility is handled by the <video> element's native behaviour. A backgrounded tab
    // keeps the element alive and the session legitimately open, so onBackground/onForeground have no
    // web equivalent here; a closed tab is reaped by the server-side stale-playback watchdog.
}
