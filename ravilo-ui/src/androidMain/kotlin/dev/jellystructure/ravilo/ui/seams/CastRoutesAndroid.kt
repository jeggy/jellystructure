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
                .map { r -> CastRoute(id = r.id, name = r.name, selected = r.isSelected, select = { r.select() }) }
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
