package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.jellystructure.shared.tv.TvApiClient

/** R328 — the screen sender alone (R265), exactly as the web; R330 adds the Mac's own Chromecast sender. */
@Composable
actual fun rememberCastSender(api: TvApiClient): ActiveCastSender {
    val screen = remember(api) { ScreenSender(api) }
    return remember(screen) { ActiveCastSender(chromecast = null, screen = screen) }
}

@Composable
actual fun rememberCastRoutes(appId: String?, discovering: Boolean): List<CastRoute> = emptyList()
