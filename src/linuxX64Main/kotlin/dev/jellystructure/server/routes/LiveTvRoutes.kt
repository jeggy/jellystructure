package dev.jellystructure.server.routes

import dev.jellystructure.auth.DeviceKey
import dev.jellystructure.auth.SessionKey
import dev.jellystructure.server.respondCachedBytes
import dev.jellystructure.shared.tv.LiveTvChannelUpdate
import dev.jellystructure.shared.tv.LiveTvHeartbeatRequest
import dev.jellystructure.shared.tv.LiveTvReorderRequest
import dev.jellystructure.shared.tv.LiveTvSettingsUpdate
import dev.jellystructure.shared.tv.LiveTvStopRequest
import dev.jellystructure.shared.tv.LiveTvTuneRequest
import dev.jellystructure.tv.CHANNEL_KINDS
import dev.jellystructure.tv.LiveTvService
import dev.jellystructure.tv.RelayOpen
import dev.jellystructure.tv.pumpRadioRelay
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.Json

/** DEB-2627 FR-5 — a heartbeat body from a newer client may carry fields this build does not know. */
private val heartbeatJson = Json { ignoreUnknownKeys = true }

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
        if (req.kind != null && req.kind !in CHANNEL_KINDS) return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Unknown kind"))
        val ok = liveTvService.updateChannel(id, req.shown, req.number, req.category, req.logoOverrideUrl, req.displayName, req.kind)
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
        // DEB-2520 — the channels Jellyfin lists for this viewer's account, not the whole lineup.
        // DEB-2655 (FR-2) — stations only for a client that sends `radio=1`; `kind=radio|tv` narrows the answer.
        val q = call.request.queryParameters
        call.respond(liveTvService.homeChannels(call.attributes[DeviceKey], radio = q["radio"] == "1", kind = q["kind"]?.takeIf { it == "radio" || it == "tv" }))
    }

    get("/tv/livetv/guide") {
        val device = call.attributes[DeviceKey]
        val days = call.request.queryParameters["days"]?.toIntOrNull()?.coerceIn(1, 14) ?: 7
        // User request: guide should scroll 4h into the past as well as forward — was always "now" floor.
        val hoursBack = call.request.queryParameters["hoursBack"]?.toIntOrNull()?.coerceIn(0, 48) ?: 0
        call.respond(liveTvService.guide(device, days, hoursBack, radio = call.request.queryParameters["radio"] == "1"))
    }

    post("/tv/livetv/channels/{id}/tune") {
        val device = call.attributes[DeviceKey]
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val req = runCatching { call.receive<LiveTvTuneRequest>() }.getOrElse {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request")); return@post
        }
        // DEB-2520 — a channel Jellyfin does not list for this viewer does not exist for them.
        if (id !in liveTvService.visibleChannelIds(device)) return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown channel"))
        val ticket = liveTvService.tune(device, id, req.capabilities, req.via)
            ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Could not tune this channel"))
        call.respond(ticket)
    }

    post("/tv/livetv/heartbeat") {
        val device = call.attributes[DeviceKey]
        // DEB-2627 FR-5 — the body is optional: an installed client sends none, and a body that does not parse is no body.
        val report = runCatching { call.receiveText() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?.let { runCatching { heartbeatJson.decodeFromString(LiveTvHeartbeatRequest.serializer(), it) }.getOrNull() }
        liveTvService.heartbeat(device, report)
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

    // ── DEB-2628 FR-4 — the radio relay. Listed in AuthPlugin's OPEN_API_PATHS beside the logo proxy: neither <audio> nor a Cast
    // receiver's media element can send a device header, so the route's credential is its `relay_id` — 128 secure-random bits minted
    // by a device-authenticated tune, compared in constant time, and never written to a log (Server.kt redacts the path). ──

    get("/tv/livetv/radio/{relay_id}") {
        val id = call.parameters["relay_id"] ?: return@get call.respond(HttpStatusCode.NotFound)
        when (val open = liveTvService.relayOpen(id)) {
            RelayOpen.NotFound -> call.respond(HttpStatusCode.NotFound, mapOf("error" to "not found"))
            RelayOpen.Busy -> {
                call.response.headers.append("Retry-After", "5")
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "too many listeners on this station"))
            }
            is RelayOpen.Granted -> try {
                pumpRadioRelay(call, open.lease) { liveTvService.relayStillLive(open.lease) }
            } finally {
                liveTvService.relayRelease(open.lease)
            }
        }
    }

    // ── Public — logo proxy (AuthPlugin OPEN_API_PATHS; not sensitive, addendum E) ────────────────

    get("/tv/livetv/logo/{id}") {
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val result = liveTvService.serveLogo(id) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondCachedBytes(result.first, ContentType.parse(result.second))
    }
}
