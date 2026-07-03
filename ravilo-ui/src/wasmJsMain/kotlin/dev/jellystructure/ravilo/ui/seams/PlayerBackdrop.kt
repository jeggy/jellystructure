package dev.jellystructure.ravilo.ui.seams

import androidx.compose.ui.graphics.Color

// R157: this Compose Multiplatform version's canvas has no accessible alpha/opaque toggle (verified —
// CanvasBasedWindow exposes no such parameter), so Color.Transparent here wouldn't actually let
// anything show through — the canvas paints solid regardless. Black matches what's actually rendered
// on the rare occasions the canvas IS the top layer (the track picker / next-up card / episode rail —
// R169 promotes the video above the canvas for the everyday "just chrome" case instead, see
// PlayerChromeBridge.kt). Video visibility is achieved by RaviloPlayer.setChromeVisible's z-index swap
// (the video promotes above the canvas, not by punching a hole in the canvas itself).
actual val playerBackdropColor: Color = Color.Black
actual val playerTapTogglesChrome: Boolean = true
