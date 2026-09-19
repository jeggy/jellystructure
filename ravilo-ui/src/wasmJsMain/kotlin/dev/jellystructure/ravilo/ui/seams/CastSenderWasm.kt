package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.jellystructure.shared.tv.TvApiClient

// R245 — there is no Chromecast SDK sender in a browser. R265 — there IS a screen sender: a browser
// phone drives a Tizen TV over plain REST/WS, no platform SDK involved, so [ActiveCastSender] here wraps
// [ScreenSender] alone (chromecast = null).
@Composable
actual fun rememberCastSender(api: TvApiClient): ActiveCastSender {
    val screen = remember(api) { ScreenSender(api) }
    return remember(screen) { ActiveCastSender(chromecast = null, screen = screen) }
}

// R245 — no Cast SDK button in a browser; no sender ⇒ no button, never a greyed one.
@Composable
actual fun PlatformCastButton(modifier: Modifier) {}
