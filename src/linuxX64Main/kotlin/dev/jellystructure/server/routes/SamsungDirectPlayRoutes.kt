package dev.jellystructure.server.routes

import dev.jellystructure.auth.SessionKey
import dev.jellystructure.config.ConfigStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable

@Serializable
data class SamsungDirectPlayRequest(val on: Boolean)

/**
 * DEB-5916 (DEB-5503, FR-46) — `[ravilo] samsung_direct_play`'s own route, built like 304b's household switch: under an admin session,
 * written to `config.toml` and the live config at once, read by the next start with no restart. `PUT /api/config` never touches
 * `[ravilo]` (DEB-731), so this is the only way to flip it without editing the file. No admin UI. Additive: no existing field changes.
 */
internal fun Route.samsungDirectPlayRoutes(configStore: ConfigStore) {
    get("/tv/admin/playback/samsung-direct-play") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        call.respond(mapOf("on" to configStore.current.ravilo.samsungDirectPlay))
    }

    put("/tv/admin/playback/samsung-direct-play") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@put call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        val req = call.receive<SamsungDirectPlayRequest>()
        val ok = configStore.update(configStore.current.copy(ravilo = configStore.current.ravilo.copy(samsungDirectPlay = req.on)))
        // The live config holds the new value either way; a failed write to disk says so rather than answering as if it were kept.
        if (!ok) return@put call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "The switch is set for now, but config.toml could not be written"))
        call.respond(mapOf("on" to req.on))
    }
}
