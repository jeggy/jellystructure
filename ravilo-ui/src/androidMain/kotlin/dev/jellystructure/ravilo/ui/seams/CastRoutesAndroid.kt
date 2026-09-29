package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.CastMediaControlIntent

/**
 * R265 (FR-R265-3) — the Chromecasts for [appId], read from the same [MediaRouter] the SDK's own dialog
 * reads. `MediaRouteChooserDialog` only ever calls `route.select()`; `CastContext`'s session manager is
 * what turns a selected Cast route into a session, so selecting one here starts the session through the
 * very path the dialog used — [CastSenderAndroid]'s listener sees `onSessionStarting` either way.
 *
 * The callback is registered for as long as there is an [appId], and scans ACTIVELY
 * (`CALLBACK_FLAG_REQUEST_DISCOVERY`) only while [discovering] — the app is on screen. Off screen it
 * scans nothing (R293). On screen it must: a resume cannot finish without a scan (seen on the Pixel 9:
 * after the app process died with the TV still playing, the SDK logged "resuming session" and then
 * waited forever for the route until something scanned). The list is rebuilt on every route change,
 * never cached, because a Chromecast that went to sleep must drop off the sheet.
 */
@Composable
actual fun rememberCastRoutes(appId: String?, discovering: Boolean): List<CastRoute> {
    val ctx = LocalContext.current.applicationContext
    var routes by remember { mutableStateOf<List<CastRoute>>(emptyList()) }
    DisposableEffect(appId, discovering) {
        if (appId == null) {
            routes = emptyList()
            return@DisposableEffect onDispose { }
        }
        val router = runCatching { MediaRouter.getInstance(ctx) }.getOrNull()
            ?: return@DisposableEffect onDispose { }
        val selector = MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(appId))
            .build()
        fun refresh() {
            routes = router.routes
                .filter { !it.isDefaultOrBluetooth && it.isEnabled && it.matchesSelector(selector) }
                .map { r ->
                    // R324 (dev review 1) — the kind from the Cast device's own capabilities: no video output ⇒ a
                    // speaker; a group route is a group. The description is the route provider's status line, which
                    // names the receiver app running on the device ("Spotify") — verified on a real speaker, never
                    // guessed: empty ⇒ nothing said.
                    val dev = runCatching { CastDevice.getFromBundle(r.extras) }.getOrNull()
                    val kind = when {
                        r.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_GROUP -> "group"
                        dev != null && !dev.hasCapability(CastDevice.CAPABILITY_VIDEO_OUT) -> "speaker"
                        else -> "display"
                    }
                    val busy = r.description?.toString()?.trim()?.takeIf { it.isNotBlank() }
                    CastRoute(id = r.id, name = r.name, selected = r.isSelected, select = { r.select() }, kind = kind, busyWith = busy)
                }
        }
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
            override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
        }
        router.addCallback(selector, callback, if (discovering) MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY else 0)
        refresh()
        onDispose { router.removeCallback(callback) }
    }
    return routes
}
