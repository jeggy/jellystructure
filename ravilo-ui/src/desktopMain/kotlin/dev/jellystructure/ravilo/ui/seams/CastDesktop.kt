package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.jellystructure.ravilo.ui.desktop.CastAvailability
import dev.jellystructure.ravilo.ui.desktop.CastDiscovery
import dev.jellystructure.ravilo.ui.desktop.CastSenderDesktop
import dev.jellystructure.ravilo.ui.desktop.DesktopPaths
import dev.jellystructure.shared.tv.TvApiClient

/**
 * R330 (FR-R330-3) — the Mac's own Chromecast sender beside the screen sender (R265), exactly as on the phone:
 * `ActiveCastSender` keeps at most one linked. Also sets what the sheet says that is the Mac's own: *Play on this
 * Mac* (open question 2) and the way to Local Network access (FR-R330-8).
 */
@Composable
actual fun rememberCastSender(api: TvApiClient): ActiveCastSender {
    remember {
        CastPlatform.playHereKey = "cast.play_here_mac"
        if (DesktopPaths.isMac) CastPlatform.openLocalNetworkSettings = {
            runCatching { ProcessBuilder("open", "x-apple.systempreferences:com.apple.preference.security?Privacy_LocalNetwork").start() }
        }
    }
    val screen = remember(api) { ScreenSender(api) }
    return remember(screen) { ActiveCastSender(chromecast = CastSenderDesktop, screen = screen) }
}

/**
 * R330 (FR-R330-2) — the devices that answer for [appId]: browsed only while [discovering] (on screen, R293), each
 * asked once whether our app can run there (D2) and listed only then. The kind comes from the record's capability
 * bits; *busy* is the app another sender left running, never our own.
 */
@Composable
actual fun rememberCastRoutes(appId: String?, discovering: Boolean): List<CastRoute> {
    val active = appId != null && discovering
    DisposableEffect(active) {
        if (active) CastDiscovery.acquire()
        onDispose { if (active) CastDiscovery.release() }
    }
    val devices by CastDiscovery.devices.collectAsState()
    val answered by CastAvailability.changed.collectAsState()
    val connectedId by CastSenderDesktop.connectedDeviceId.collectAsState()
    LaunchedEffect(devices, appId) { if (appId != null) devices.forEach { CastAvailability.ask(it, appId) } }
    if (appId == null) return emptyList()
    return remember(devices, answered, connectedId, appId) {
        devices.filter { CastAvailability.known(it, appId) == true }.map { d ->
            CastRoute(
                id = d.id, name = d.name, selected = d.id == connectedId,
                select = { CastSenderDesktop.connect(d) },
                kind = d.kind,
                busyWith = d.runningApp?.takeIf { d.id != connectedId && it != CastAvailability.ourDisplayName },
            )
        }
    }
}
