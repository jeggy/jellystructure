package dev.jellystructure.server.routes

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.DeviceKey
import dev.jellystructure.auth.SessionKey
import dev.jellystructure.shared.tv.SessionCommandRefusal
import dev.jellystructure.shared.tv.SessionCommandRequest
import dev.jellystructure.shared.tv.SessionQueueReport
import dev.jellystructure.tv.CommandResult
import dev.jellystructure.tv.PlaybackSessions
import dev.jellystructure.tv.SessionControl
import dev.jellystructure.tv.SessionPublisher
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable

/** 304b (dev review item 6) — the household switch, written at once (no global Save: this is not the Settings page). */
@Serializable
data class HouseholdControlRequest(val on: Boolean)

/**
 * R368 (FR-R368-5, dev review item 1) — the playback sessions, under `/api/tv/playback/…` (`/api/tv/sessions` is the
 * profile list and R191's single sign-out, and cannot change). 304 (dev review item 2) — the admin's list, cookie-gated
 * like `/tv/admin/overview`, with no visibility or network filter. R369 — the detail, commands and queue reports; 304b
 * the admin's own command route and the household switch.
 */
internal fun Route.playbackSessionRoutes(
    publisher: SessionPublisher,
    sessions: PlaybackSessions? = null,
    control: SessionControl? = null,
    setHouseholdControl: (suspend (Boolean) -> Unit)? = null,
    starter: dev.jellystructure.tv.SessionStarter? = null,
) {
    get("/tv/playback/sessions") {
        val device = call.attributes[DeviceKey]
        call.respond(publisher.listFor(device))
    }

    get("/tv/admin/playback/sessions") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        call.respond(publisher.adminList())
    }

    if (sessions == null || control == null) return

    if (starter != null) {
        // R370 (FR-R370-1/-2) — the places this viewer may start on.
        get("/tv/playback/targets") {
            val device = call.attributes[DeviceKey]
            val targets = starter.targetsFor(device) { s -> publisher.viewFor(device, s) }
            call.respond(dev.jellystructure.shared.tv.TargetList(targets, kotlin.time.Clock.System.now().toEpochMilliseconds()))
        }
        // R370 (FR-R370-3/-4/-5) — start on a place; `replace` ends a busy one first (review item 10).
        post("/tv/playback/sessions") {
            val device = call.attributes[DeviceKey]
            val req = call.receive<dev.jellystructure.shared.tv.SessionStartRequest>()
            when (val r = starter.start(device, req)) {
                is dev.jellystructure.tv.StartResult2.Started -> call.respond(dev.jellystructure.shared.tv.SessionStartResponse(
                    publisher.viewFor(device, r.session), r.loadHere, r.castDeviceId))
                dev.jellystructure.tv.StartResult2.Busy -> call.respond(HttpStatusCode.Conflict, SessionCommandRefusal("busy"))
                dev.jellystructure.tv.StartResult2.Unreachable -> call.respond(HttpStatusCode.Conflict, SessionCommandRefusal("unreachable"))
                dev.jellystructure.tv.StartResult2.Forbidden -> call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not yours to replace"))
                dev.jellystructure.tv.StartResult2.BadRequest -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to "nothing to start there"))
            }
        }
    }

    // R369 (dev review item 5) — what a remote needs beyond the row.
    get("/tv/playback/sessions/{id}") {
        val device = call.attributes[DeviceKey]
        val s = sessions.get(call.parameters["id"]!!) ?: return@get call.respond(HttpStatusCode.NotFound)
        val d = publisher.detailFor(device, s) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(d)
    }

    // R369 (dev review item 5) — a target's queue, on change only.
    post("/tv/playback/sessions/queue") {
        val device = call.attributes[DeviceKey]
        val r = call.receive<SessionQueueReport>()
        sessions.onQueueReport(device, r)
        call.respond(mapOf("status" to "ok"))
    }

    // R371 (review item 5) — a group's rooms, from the app holding the session's Cast link, on change.
    post("/tv/playback/sessions/members") {
        val device = call.attributes[DeviceKey]
        sessions.onMembersReport(device, call.receive<dev.jellystructure.shared.tv.SessionMembersReport>())
        call.respond(mapOf("status" to "ok"))
    }

    // FR-R369-1 — one command; 202 means sent (dev review item 11), 409 stale / unreachable, 403 not yours.
    post("/tv/playback/sessions/{id}/command") {
        val device = call.attributes[DeviceKey]
        val c = call.receive<SessionCommandRequest>()
        call.respondCommand(control.command(call.parameters["id"]!!, c, device, admin = false), publisher, device)
    }

    // 304b (dev review item 4) — the admin's remote: the same service, `source = admin`, never a controller row.
    post("/tv/admin/playback/sessions/{id}/command") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        val c = call.receive<SessionCommandRequest>()
        call.respondCommand(control.command(call.parameters["id"]!!, c, null, admin = true), publisher, null)
    }

    get("/tv/admin/playback/sessions/{id}") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        val s = sessions.get(call.parameters["id"]!!) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(publisher.detailFor(null, s, admin = true) ?: return@get call.respond(HttpStatusCode.NotFound))
    }

    // 304b (FR-304-4) — *Household members can control each other's playing*.
    put("/tv/admin/playback/household-control") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@put call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        val req = call.receive<HouseholdControlRequest>()
        val set = setHouseholdControl ?: return@put call.respond(HttpStatusCode.NotFound)
        set(req.on)
        call.respond(mapOf("on" to req.on))
    }
}

private suspend fun ApplicationCall.respondCommand(r: CommandResult, publisher: SessionPublisher, viewer: DeviceData?) {
    when (r) {
        CommandResult.Accepted -> respond(HttpStatusCode.Accepted, mapOf("status" to "sent"))
        is CommandResult.Stale -> {
            val view = publisher.viewFor(viewer, r.session)
            respond(HttpStatusCode.Conflict, SessionCommandRefusal("stale", view))
        }
        CommandResult.Unreachable -> respond(HttpStatusCode.Conflict, SessionCommandRefusal("unreachable"))
        CommandResult.NotOffered -> respond(HttpStatusCode.Conflict, SessionCommandRefusal("not_offered"))
        CommandResult.Forbidden -> respond(HttpStatusCode.Forbidden, mapOf("error" to "not yours to control"))
        CommandResult.NotFound -> respond(HttpStatusCode.NotFound, mapOf("error" to "no such playback"))
    }
}
