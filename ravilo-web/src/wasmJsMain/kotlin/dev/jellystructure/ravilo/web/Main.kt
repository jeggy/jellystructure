package dev.jellystructure.ravilo.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import dev.jellystructure.ravilo.ui.RaviloRoot

// R376 (FR-R376-1) — ComposeViewport, not the deprecated CanvasBasedWindow. The viewport draws into a canvas it
// creates inside #ComposeTarget's shadow root, together with a real DOM text input for whichever Compose text field
// has focus (so a phone raises its own keyboard — R281's hidden bridge input is gone), and the `<video>` lives
// behind that container (RaviloPlayerWasm.kt). Skiko clears the canvas to opaque white every frame (skiko
// 0.9.22.2's CanvasRenderer), so there is no "transparent window" switch to turn on: the player screen punches its
// own hole with BlendMode.Clear (PlayerVideoSurface on wasmJs), and the WebGL context's alpha lets the video show
// through exactly there. Every other screen paints itself as before.
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // R302 — probe what this browser can decode once, at boot, so the answer is ready before the first
    // play and visible to the e2e suite; the list itself is only ever read by a playback start.
    dev.jellystructure.ravilo.ui.seams.supportedAudioCodecs()
    // R265 (FR-R265-8) — the same, for the video codecs and for "Safari: HLS only, subtitles in the manifest".
    dev.jellystructure.ravilo.ui.seams.supportedVideoCodecs()
    dev.jellystructure.ravilo.ui.seams.playsHlsForAirPlay()
    // R376 (FR-R376-5) — and the containers this browser opens.
    dev.jellystructure.ravilo.ui.seams.supportedContainers()
    // R376 (FR-R376-S1) — the page's one <video> element, listening for the first click, tap or key, so the play that
    // follows a click has Safari's sound.
    dev.jellystructure.ravilo.ui.seams.prepareWebVideo()
    val container = document.getElementById("ComposeTarget") ?: error("index.html has no #ComposeTarget")
    ComposeViewport(
        viewportContainer = container,
        // The a11y mirror is a second DOM tree updated on every semantics change; CanvasBasedWindow never had
        // one, and turning it on is its own decision, not part of moving the player.
        configure = { isA11YEnabled = false },
    ) {
        // R315 — the fallback font is registered before any text is laid out.
        dev.jellystructure.ravilo.ui.WithWebFallbackFont { RaviloRoot() }
    }
}
