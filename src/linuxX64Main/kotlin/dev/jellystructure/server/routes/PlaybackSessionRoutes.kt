package dev.jellystructure.server.routes

import dev.jellystructure.auth.DeviceKey
import dev.jellystructure.auth.SessionKey
import dev.jellystructure.tv.SessionPublisher
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * R368 (FR-R368-5, dev review item 1) — the playback sessions, under `/api/tv/playback/…` (`/api/tv/sessions` is the
 * profile list and R191's single sign-out, and cannot change). 304 (dev review item 2) — the admin's list, cookie-gated
 * like `/tv/admin/overview`, with no visibility or network filter.
 */
fun Route.playbackSessionRoutes(publisher: SessionPublisher) {
    get("/tv/playback/sessions") {
        val device = call.attributes[DeviceKey]
        call.respond(publisher.listFor(device))
    }

    get("/tv/admin/playback/sessions") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        call.respond(publisher.adminList())
    }
}
