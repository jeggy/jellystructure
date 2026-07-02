package dev.jellystructure.ravilo.ui.seams

import androidx.compose.ui.graphics.Color

// R157: this Compose Multiplatform version's canvas has no accessible alpha/opaque toggle (verified —
// CanvasBasedWindow exposes no such parameter), so Color.Transparent here wouldn't actually let
// anything show through — the canvas paints solid regardless. Black matches what's actually rendered.
// Video visibility while chrome is hidden is achieved instead by RaviloPlayer.setChromeVisible's
// z-index swap (the video promotes above the canvas, not by punching a hole in the canvas itself).
actual val playerBackdropColor: Color = Color.Black
actual val playerTapTogglesChrome: Boolean = true
