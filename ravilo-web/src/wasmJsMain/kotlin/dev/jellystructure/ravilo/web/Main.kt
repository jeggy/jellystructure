package dev.jellystructure.ravilo.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.CanvasBasedWindow
import dev.jellystructure.ravilo.ui.RaviloRoot

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    CanvasBasedWindow(title = "Ravilo") {
        RaviloRoot()
    }
}
