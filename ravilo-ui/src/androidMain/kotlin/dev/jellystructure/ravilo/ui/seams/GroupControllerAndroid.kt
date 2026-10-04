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

    override fun members(): List<SessionRoom> = controller()?.selectedRoutes.orEmpty().map(::room)
    override fun selectable(): List<SessionRoom> = controller()?.selectableRoutes.orEmpty().map(::room)

    override fun add(castDeviceId: String): Boolean {
        val c = controller() ?: return false
        val r = find(c.selectableRoutes, castDeviceId) ?: return false
        return runCatching { c.selectRoute(r) }.isSuccess
    }

    override fun remove(castDeviceId: String): Boolean {
        val c = controller() ?: return false
        val r = find(c.deselectableRoutes, castDeviceId) ?: return false
        return runCatching { c.deselectRoute(r) }.isSuccess
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
