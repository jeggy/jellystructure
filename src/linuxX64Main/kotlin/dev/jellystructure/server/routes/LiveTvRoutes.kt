package dev.jellystructure.server.routes

import dev.jellystructure.auth.DeviceKey
import dev.jellystructure.auth.SessionKey
import dev.jellystructure.server.respondCachedBytes
import dev.jellystructure.shared.tv.LiveTvChannelUpdate
import dev.jellystructure.shared.tv.LiveTvReorderRequest
import dev.jellystructure.shared.tv.LiveTvSettingsUpdate
import dev.jellystructure.shared.tv.LiveTvStopRequest
import dev.jellystructure.shared.tv.LiveTvTuneRequest
import dev.jellystructure.tv.LiveTvService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/**
 * Phase 147 — Live TV configuration: admin routes under `/tv/admin/livetv/`, cookie-gated by AuthPlugin
 * like every other `/tv/admin/` route; the TV-facing surface R177 consumes, under `/tv/livetv/`,
 * device-token gated; and the public logo proxy (`/tv/livetv/logo/{id}`), listed in AuthPlugin's
 * OPEN_API_PATHS.
 */
fun Route.liveTvRoutes(liveTvService: LiveTvService) {
    // ── Admin ──────────────────────────────────────────────────────────────────

    get("/tv/admin/livetv/overview") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        call.respond(liveTvService.sync())
    }

    post("/tv/admin/livetv/sync") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        call.respond(liveTvService.sync())
    }

    get("/tv/admin/livetv/channels") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        call.respond(liveTvService.lineup())
    }

    put("/tv/admin/livetv/channels/{id}") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@put call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
        val req = runCatching { call.receive<LiveTvChannelUpdate>() }.getOrElse {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request")); return@put
        }
        val ok = liveTvService.updateChannel(id, req.shown, req.number, req.category, req.logoOverrideUrl)
        if (!ok) return@put call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown channel"))
        call.respond(liveTvService.lineup())
    }

    post("/tv/admin/livetv/channels/reorder") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        val req = runCatching { call.receive<LiveTvReorderRequest>() }.getOrElse {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request")); return@post
        }
        liveTvService.reorder(req.channelIds)
        call.respond(liveTvService.lineup())
    }

    post("/tv/admin/livetv/bulk/show-new") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        liveTvService.bulkShowNew()
        call.respond(liveTvService.lineup())
    }

    post("/tv/admin/livetv/bulk/remove-missing") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        liveTvService.bulkRemoveMissing()
        call.respond(liveTvService.lineup())
    }

    put("/tv/admin/livetv/settings") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@put call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        val req = runCatching { call.receive<LiveTvSettingsUpdate>() }.getOrElse {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request")); return@put
        }
        liveTvService.updateSettings(req.enabled, req.epgCadenceMinutes)
        call.respond(liveTvService.overview())
    }

    // ── TV-facing (device-token gated — Phase 147 addendum E/D, consumed by R177) ──────────────────

    get("/tv/livetv/channels") {
        call.attributes[DeviceKey] // gate; unused (no per-user restriction yet — spec §E defers this to Ravilo config)
        call.respond(liveTvService.homeChannels())
    }

    get("/tv/livetv/guide") {
        call.attributes[DeviceKey]
        val days = call.request.queryParameters["days"]?.toIntOrNull()?.coerceIn(1, 14) ?: 7
        call.respond(liveTvService.guide(days))
    }

    post("/tv/livetv/channels/{id}/tune") {
        val device = call.attributes[DeviceKey]
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val req = runCatching { call.receive<LiveTvTuneRequest>() }.getOrElse {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request")); return@post
        }
        val ticket = liveTvService.tune(device, id, req.capabilities)
            ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Could not tune this channel"))
        call.respond(ticket)
    }

    post("/tv/livetv/heartbeat") {
        val device = call.attributes[DeviceKey]
        liveTvService.heartbeat(device)
        call.respond(mapOf("status" to "ok"))
    }

    post("/tv/livetv/stop") {
        val device = call.attributes[DeviceKey]
        val req = runCatching { call.receive<LiveTvStopRequest>() }.getOrElse {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request")); return@post
        }
        liveTvService.stopTune(device, req.liveStreamId)
        call.respond(mapOf("status" to "ok"))
    }

    // ── Public — logo proxy (AuthPlugin OPEN_API_PATHS; not sensitive, addendum E) ────────────────

    get("/tv/livetv/logo/{id}") {
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val result = liveTvService.serveLogo(id) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondCachedBytes(result.first, ContentType.parse(result.second))
    }
}
