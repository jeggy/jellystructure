package dev.jellystructure.ravilo.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.CanvasBasedWindow
import dev.jellystructure.ravilo.ui.RaviloRoot

// CMP 1.9 escalated CanvasBasedWindow's deprecation to error level in favour of ComposeViewport.
// We keep it for now: this module relies on the #ComposeTarget canvas (video-behind-canvas player
// layering + manual key dispatch in index.html), which ComposeViewport's self-created canvas would
// disrupt. TODO: migrate to ComposeViewport and rework the HTML player layering.
@OptIn(ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION_ERROR")
fun main() {
    // R157 — this CanvasBasedWindow API has no canvas-alpha/opaque parameter in this Compose
    // Multiplatform version (verified directly against its signature), so the canvas can't be made
    // transparent. Video visibility while the player's chrome is hidden is instead achieved by
    // RaviloPlayer.setChromeVisible's z-index swap — see RaviloPlayerWasm.kt / RaviloPlayer.kt.
    CanvasBasedWindow(title = "Ravilo") {
        RaviloRoot()
    }
}
