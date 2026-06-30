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
    CanvasBasedWindow(title = "Ravilo") {
        RaviloRoot()
    }
}
