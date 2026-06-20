package dev.jellystructure.ravilo.ui.seams

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun PlayerVideoSurface(player: RaviloPlayer, modifier: Modifier) {
    Box(modifier) // <video> element is fixed-positioned behind the skiko canvas via CSS
}
