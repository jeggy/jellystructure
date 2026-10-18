package dev.jellystructure.server.routes

import dev.jellystructure.auth.SessionKey
import dev.jellystructure.tv.activePlaybackDeviceNames
import dev.jellystructure.tv.isPlaybackActive
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

/**
 * Phase 178 §FR-178-1 — `GET /api/playback/active`: whether anything is playing right now (or within the 120 s grace)
 * and the display names it counts. DEB-1824 — this used to answer `mapOf("active" to Boolean, "devices" to List)`, a
 * map whose values have two element types, which kotlinx serialization cannot encode: every signed-in call was a 500.
 */
@Serializable
data class PlaybackActiveResponse(val active: Boolean, val devices: List<String>)

/** [isActive] and [deviceNames] default to the live tracker; the route test passes its own. */
internal fun Route.playbackActiveRoutes(
    isActive: () -> Boolean = ::isPlaybackActive,
    deviceNames: () -> List<String> = ::activePlaybackDeviceNames,
) {
    get("/playback/active") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        call.respond(PlaybackActiveResponse(isActive(), deviceNames()))
    }
}
