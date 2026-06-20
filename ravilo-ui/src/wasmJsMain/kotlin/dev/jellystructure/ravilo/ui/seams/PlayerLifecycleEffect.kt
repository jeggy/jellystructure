package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable

@Composable
actual fun PlayerLifecycleEffect(player: RaviloPlayer, wasPlaying: () -> Boolean) {
    // Browser tab visibility is handled by the <video> element's native behaviour.
}
