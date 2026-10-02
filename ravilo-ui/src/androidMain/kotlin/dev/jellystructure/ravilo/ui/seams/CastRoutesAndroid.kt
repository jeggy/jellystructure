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
                    // speaker; a group route is a group. R353 — the description is the route provider's status line: the
                    // running receiver app's name ("Spotify"), or the model name when nothing runs, which is not busy.
                    val dev = runCatching { CastDevice.getFromBundle(r.extras) }.getOrNull()
                    val kind = when {
                        r.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_GROUP -> "group"
                        dev != null && !dev.hasCapability(CastDevice.CAPABILITY_VIDEO_OUT) -> "speaker"
                        else -> "display"
                    }
                    val busy = castRouteBusyWith(r.description?.toString(), dev?.modelName, dev?.friendlyName)
                    CastRoute(id = r.id, name = r.name, selected = r.isSelected, select = { selectRoute(router, r.id) }, kind = kind, busyWith = busy)
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

/**
 * R353 (FR-R353-2) — select a Cast route, and select it once more if the Cast SDK never started a session from it.
 *
 * On Android 14+ the selection is a MediaRouter2 transfer: Play services creates the routing session, and androidx
 * adopts it by reading the session's selected routes back against the app's route list. Play services republishes
 * its whole route list while it connects to the device (a speaker that is a member of a speaker group goes through a
 * "dynamic group" controller first), and when the session reaches the app just after that removal, the selected
 * routes resolve to nothing: androidx logs *Selected routes are empty*, drops the transfer, no route is ever selected,
 * and the Cast SDK — which starts its session from `onRouteSelected` — never hears of it. Nothing launches on the
 * device and the sheet just closes (Pixel 9 → the Stue speaker, 2026-10-02). The second try finds Play services'
 * controller already connected and goes through. A try the SDK did start ([CastStartWatch]) is never repeated, and a
 * route that has gone or that something else selected in the meantime is left alone.
 */
internal fun selectRoute(router: MediaRouter, routeId: String, attempt: Int = 0) {
    val route = router.routes.firstOrNull { it.id == routeId } ?: return
    val at = android.os.SystemClock.elapsedRealtime()
    route.select()
    if (attempt >= CAST_SELECT_RETRIES) return
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        if (CastStartWatch.startedAt >= at) return@postDelayed
        val now = router.routes.firstOrNull { it.id == routeId } ?: return@postDelayed
        if (now.isSelected || !router.selectedRoute.isDefaultOrBluetooth) return@postDelayed
        android.util.Log.w("RaviloCast", "R353: no Cast session started from ${now.name}; selecting it again (try ${attempt + 2})")
        selectRoute(router, routeId, attempt + 1)
    }, CAST_SELECT_WAIT_MS)
}

private const val CAST_SELECT_RETRIES = 2
private const val CAST_SELECT_WAIT_MS = 3_000L

/** R353 — when the Cast SDK last began a session (start or resume), on the elapsed-realtime clock. */
internal object CastStartWatch {
    @Volatile var startedAt: Long = 0L
    fun mark() { startedAt = android.os.SystemClock.elapsedRealtime() }
}
