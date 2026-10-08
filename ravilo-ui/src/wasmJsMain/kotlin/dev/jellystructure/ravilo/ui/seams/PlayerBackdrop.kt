package dev.jellystructure.ravilo.ui.seams

import androidx.compose.ui.graphics.Color

// R376 (FR-R376-1) — the player screen's fill under the video surface. The surface clears its own area to transparent
// (PlayerVideoSurface), so this black is only ever seen where the surface does not reach — which on a full-window
// player is nowhere; it is what the picture's own black bars match.
actual val playerBackdropColor: Color = Color.Black
actual val playerTapTogglesChrome: Boolean = true
actual val playerArrowsSeek: Boolean = false
