package dev.jellystructure.ravilo.ui.seams

import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.os.Build
import androidx.annotation.RequiresApi
import dev.jellystructure.ravilo.ui.RaviloAppContext
import dev.jellystructure.shared.tv.SessionRoom

/**
 * R371 (review item 2) — Android 11+: the framework's `MediaRouter2.RoutingController` for this app's Cast session (the
 * one androidx creates when media transfer is on). `getSelectableRoutes()` is the *Add a speaker…* list,
 * `selectRoute` / `deselectRoute` add and remove a room — no new LOAD, the receiver moves onto the group alive (R355's
 * effect). A member's level is its route's own volume. Below Android 11 there is none: the app goes through the server.
 *
 * Routes are matched to a Cast device id by the id's tail (the Cast route provider ends a route id with the device's
 * id) or else by name — only a device session on the speakers can confirm which (the build notes say so).
 */
@RequiresApi(Build.VERSION_CODES.R)
private class MediaRouter2GroupController : GroupController {
    private val router: MediaRouter2 get() = MediaRouter2.getInstance(RaviloAppContext.get())

    /** The Cast session's controller: the newest that is not the system's own (local speaker / Bluetooth). */
    private fun controller(): MediaRouter2.RoutingController? =
        runCatching { router.controllers.lastOrNull { !it.isReleased && it != router.systemController } }.getOrNull()

    private fun keyOf(r: MediaRoute2Info): String = r.id.substringAfterLast(':')

    private fun room(r: MediaRoute2Info): SessionRoom {
        val max = r.volumeMax.coerceAtLeast(1)
        val level = if (r.volumeHandling == MediaRoute2Info.PLAYBACK_VOLUME_FIXED) null else (r.volume * 100 / max).coerceIn(0, 100)
        return SessionRoom(castDeviceId = keyOf(r), name = r.name.toString(), volume = level, muted = level == 0)
    }

    private fun find(list: List<MediaRoute2Info>, castDeviceId: String): MediaRoute2Info? =
        list.firstOrNull { keyOf(it) == castDeviceId || it.id.endsWith(castDeviceId) } ?: list.firstOrNull { it.name.toString() == castDeviceId }

    /** A display (it plays video): never offered as a room — selecting a Nest Hub launched the Default Media Receiver on
     *  it, grouped nothing and lost the session's link (the Pixel 9 Pro, 2026-10-05). Speakers group with speakers. */
    private fun speaker(r: MediaRoute2Info): Boolean = MediaRoute2Info.FEATURE_REMOTE_VIDEO_PLAYBACK !in r.features

    /** R372 audit (bug 15) — every caller is on the main thread (LaunchedEffect); MediaRouter2 is thread-safe, the
     *  androidx MediaRouter is not: said loudly if that ever changes. */
    private fun offMain(op: String) { if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) sessionLog("R371: $op called off the main thread") }

    override fun members(): List<SessionRoom> = controller()?.selectedRoutes.orEmpty().map(::room)
    override fun selectable(): List<SessionRoom> = controller()?.selectableRoutes.orEmpty().filter(::speaker).map(::room)

    override fun add(castDeviceId: String): Boolean {
        offMain("add")
        val c = controller()
        if (c == null) { sessionLog("R371: add $castDeviceId — no routing controller for the Cast session (controllers: ${runCatching { router.controllers.size }.getOrNull()})"); return false }
        val r = find(c.selectableRoutes, castDeviceId)
        if (r == null) { sessionLog("R371: add $castDeviceId — not selectable (selectable: ${c.selectableRoutes.joinToString { "${it.name}/${keyOf(it)}" }})"); return false }
        if (!speaker(r)) { sessionLog("R371: add ${r.name} — a display, not added"); return false }
        val ok = runCatching { c.selectRoute(r) }.onFailure { sessionLog("R371: selectRoute(${r.name}) failed: ${it.message}") }.isSuccess
        sessionLog("R371: add ${r.name} → ${if (ok) "selected" else "failed"}")
        return ok
    }

    override fun remove(castDeviceId: String): Boolean {
        val c = controller() ?: return false.also { sessionLog("R371: remove $castDeviceId — no routing controller") }
        val r = find(c.deselectableRoutes, castDeviceId) ?: return false.also { sessionLog("R371: remove $castDeviceId — not deselectable") }
        val ok = runCatching { c.deselectRoute(r) }.isSuccess
        sessionLog("R371: remove ${r.name} → ${if (ok) "deselected" else "failed"}")
        return ok
    }

    override fun releaseLeftover(): List<String> {
        val left = runCatching { router.controllers.filter { !it.isReleased && it != router.systemController } }.getOrDefault(emptyList())
        return left.map { c ->
            val names = c.selectedRoutes.joinToString { it.name.toString() }
            runCatching { c.release() }.onFailure { sessionLog("R371: releasing the routing session to $names failed: ${it.message}") }
            names
        }
    }

    override fun setRoomVolume(castDeviceId: String, percent: Int): Boolean {
        val c = controller() ?: return false
        val r = find(c.selectedRoutes, castDeviceId) ?: return false
        val volume = (percent.coerceIn(0, 100) * r.volumeMax.coerceAtLeast(1) + 50) / 100
        return runCatching { router.setRouteVolume(r, volume) }.isSuccess
    }
}

actual fun platformGroupController(): GroupController? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) MediaRouter2GroupController() else null   // owner decision 3: below 11 → the relay

/** R370–R372 — the sessions log: logcat tag `RaviloSessions` (cast starts, relays, room ops, the remote's state). */
actual fun sessionLog(message: String) { runCatching { android.util.Log.i("RaviloSessions", message) } }   // a plain JVM unit test has no Log
