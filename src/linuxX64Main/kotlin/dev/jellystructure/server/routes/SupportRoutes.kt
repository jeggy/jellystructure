package dev.jellystructure.server.routes

import dev.jellystructure.auth.DeviceKey
import dev.jellystructure.auth.SessionKey
import dev.jellystructure.model.SupportClassRequest
import dev.jellystructure.model.SupportFlagRequest
import dev.jellystructure.model.SupportForwardRequest
import dev.jellystructure.model.SupportStateRequest
import dev.jellystructure.shared.RaviloHeaders
import dev.jellystructure.shared.tv.SupportPostRequest
import dev.jellystructure.shared.tv.SupportReadRequest
import dev.jellystructure.support.SupportService
import dev.jellystructure.support.SupportService.Outcome
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * Phase 320 — the support lane's routes: the viewer's under `/api/tv/support/` (a device token, FR-320-3), the admin's
 * under `/api/support/` (the admin cookie, FR-320-12). Thin on purpose; every rule lives in [SupportService].
 *
 * **Availability (FR-320-1).** While the lane is unavailable every viewer route answers 404 — the same answer as a ticket
 * that is not yours, so nothing says "it exists but is switched off" — and every admin *write* answers 409
 * `support_unavailable`. Admin reads still answer.
 */
internal fun Route.supportRoutes(service: SupportService) {
    // ── The viewer ───────────────────────────────────────────────────────────────────────────────────────────────────

    get("/tv/support/tickets") {
        if (!call.viewerLaneOpen(service)) return@get
        call.respond(service.list(call.attributes[DeviceKey].jellyfinUserId))
    }

    get("/tv/support/unread") {
        if (!call.viewerLaneOpen(service)) return@get
        call.respond(service.unread(call.attributes[DeviceKey].jellyfinUserId))
    }

    post("/tv/support/tickets") {
        if (!call.viewerLaneOpen(service)) return@post
        val device = call.attributes[DeviceKey]
        val req = call.receiveOrBadRequest<SupportPostRequest>() ?: return@post
        call.respondOutcome(service.file(device.jellyfinUserId, call.request.headers[RaviloHeaders.PLATFORM], device.deviceId, req.body))
    }

    get("/tv/support/tickets/{n}") {
        if (!call.viewerLaneOpen(service)) return@get
        val n = call.ticketNumber() ?: return@get call.respondNotFound()
        val thread = service.thread(call.attributes[DeviceKey].jellyfinUserId, n) ?: return@get call.respondNotFound()
        call.respond(thread)
    }

    post("/tv/support/tickets/{n}/messages") {
        if (!call.viewerLaneOpen(service)) return@post
        val device = call.attributes[DeviceKey]
        val platform = call.request.headers[RaviloHeaders.PLATFORM]
        // A platform that may not post is refused before the number is looked at, so a TV learns nothing about it.
        service.platformRefusal(platform)?.let { return@post call.respondRefused(it) }
        val n = call.ticketNumber() ?: return@post call.respondNotFound()
        val req = call.receiveOrBadRequest<SupportPostRequest>() ?: return@post
        call.respondOutcome(service.post(device.jellyfinUserId, n, platform, device.deviceId, req.body))
    }

    post("/tv/support/tickets/{n}/same-here") {
        if (!call.viewerLaneOpen(service)) return@post
        val device = call.attributes[DeviceKey]
        val n = call.ticketNumber() ?: return@post call.respondNotFound()
        call.respondOutcome(service.sameHere(device.jellyfinUserId, n, call.request.headers[RaviloHeaders.PLATFORM], device.deviceId))
    }

    post("/tv/support/tickets/{n}/read") {
        if (!call.viewerLaneOpen(service)) return@post
        val n = call.ticketNumber() ?: return@post call.respondNotFound()
        val req = call.receiveOrBadRequest<SupportReadRequest>() ?: return@post
        if (service.markRead(call.attributes[DeviceKey].jellyfinUserId, n, req.upTo)) call.respond(HttpStatusCode.NoContent)
        else call.respondNotFound()
    }

    delete("/tv/support/tickets/{n}") {
        if (!call.viewerLaneOpen(service)) return@delete
        val n = call.ticketNumber() ?: return@delete call.respondNotFound()
        if (service.deleteOwn(call.attributes[DeviceKey].jellyfinUserId, n)) call.respond(HttpStatusCode.NoContent)
        else call.respondNotFound()
    }

    // ── The admin ────────────────────────────────────────────────────────────────────────────────────────────────────

    get("/support/tickets") {
        val q = call.request.queryParameters
        call.respond(service.adminList(SupportService.AdminFilter(
            state = q["state"]?.takeIf { it.isNotBlank() }, holder = q["holder"]?.takeIf { it.isNotBlank() },
            owes = q["owes"]?.takeIf { it.isNotBlank() }, flagged = q["flagged"] == "true",
            overdue = q["overdue"]?.let { it == "true" },
        )))
    }

    get("/support/summary") { call.respond(service.summary()) }

    get("/support/tickets/{n}") {
        val n = call.ticketNumber() ?: return@get call.respondNotFound()
        call.respond(service.adminThread(n) ?: return@get call.respondNotFound())
    }

    // 321 (FR-321-10) — every request `AI Agent` made for this ticket, for the ticket's life.
    get("/support/tickets/{n}/transcript") {
        val n = call.ticketNumber() ?: return@get call.respondNotFound()
        call.respond(service.agentTranscript(n) ?: return@get call.respondNotFound())
    }

    post("/support/tickets/{n}/messages") {
        if (!call.adminWriteOpen(service)) return@post
        val n = call.ticketNumber() ?: return@post call.respondNotFound()
        val req = call.receiveOrBadRequest<SupportPostRequest>() ?: return@post
        call.respondOutcome(service.reply(n, call.adminName(), req.body))
    }

    post("/support/tickets/{n}/forward") {
        if (!call.adminWriteOpen(service)) return@post
        val n = call.ticketNumber() ?: return@post call.respondNotFound()
        val req = call.receiveOrBadRequest<SupportForwardRequest>() ?: return@post
        call.respondOutcome(service.forward(n, call.adminName(), req.holder, req.seat, req.owes, req.note))
    }

    post("/support/tickets/{n}/state") {
        if (!call.adminWriteOpen(service)) return@post
        val n = call.ticketNumber() ?: return@post call.respondNotFound()
        val req = call.receiveOrBadRequest<SupportStateRequest>() ?: return@post
        call.respondOutcome(service.setState(n, req.state))
    }

    post("/support/tickets/{n}/class") {
        if (!call.adminWriteOpen(service)) return@post
        val n = call.ticketNumber() ?: return@post call.respondNotFound()
        val req = call.receiveOrBadRequest<SupportClassRequest>() ?: return@post
        call.respondOutcome(service.setClass(n, req.ticketClass))
    }

    post("/support/tickets/{n}/flag") {
        if (!call.adminWriteOpen(service)) return@post
        val n = call.ticketNumber() ?: return@post call.respondNotFound()
        val req = call.receiveOrBadRequest<SupportFlagRequest>() ?: return@post
        call.respondOutcome(service.setFlag(n, req.flagged))
    }

    post("/support/tickets/{n}/read") {
        if (!call.adminWriteOpen(service)) return@post
        val n = call.ticketNumber() ?: return@post call.respondNotFound()
        val req = call.receiveOrBadRequest<SupportReadRequest>() ?: return@post
        if (service.adminMarkRead(n, req.upTo)) call.respond(HttpStatusCode.NoContent) else call.respondNotFound()
    }

    delete("/support/tickets/{n}") {
        if (!call.adminWriteOpen(service)) return@delete
        val n = call.ticketNumber() ?: return@delete call.respondNotFound()
        if (service.adminDelete(n)) call.respond(HttpStatusCode.NoContent) else call.respondNotFound()
    }
}

private fun ApplicationCall.ticketNumber(): Long? = parameters["n"]?.toLongOrNull()?.takeIf { it > 0 }

private suspend fun ApplicationCall.respondNotFound() =
    respond(HttpStatusCode.NotFound, mapOf("error" to "not_found"))

/** FR-320-1 — 404 on every viewer route while unavailable. Returns false after answering. */
private suspend fun ApplicationCall.viewerLaneOpen(service: SupportService): Boolean {
    if (service.available) return true
    respondNotFound()
    return false
}

/** FR-320-1 — 409 `support_unavailable` on every admin write while unavailable. Returns false after answering. */
private suspend fun ApplicationCall.adminWriteOpen(service: SupportService): Boolean {
    if (service.available) return true
    respond(HttpStatusCode.Conflict, mapOf("error" to "support_unavailable"))
    return false
}

private fun ApplicationCall.adminName(): String =
    runCatching { attributes[SessionKey].jellyfinUsername }.getOrNull().orEmpty().ifBlank { "Admin" }

private suspend inline fun <reified T : Any> ApplicationCall.receiveOrBadRequest(): T? =
    runCatching { receive<T>() }.getOrNull() ?: run {
        respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_body", "reason" to "The request body could not be read"))
        null
    }

private suspend fun ApplicationCall.respondRefused(r: Outcome.Refused) {
    r.retryAfterSeconds?.let { response.headers.append(HttpHeaders.RetryAfter, it.toString()) }
    respond(HttpStatusCode.fromValue(r.status), mapOf("error" to r.code, "reason" to r.reason))
}

/** A filing answers 201 (FR-320-4); every other success is 200 with the whole thread. */
private suspend inline fun <reified T : Any> ApplicationCall.respondOutcome(outcome: Outcome<T>) {
    when (outcome) {
        is Outcome.Ok -> respond(if (outcome.created) HttpStatusCode.Created else HttpStatusCode.OK, outcome.value)
        is Outcome.Refused -> respondRefused(outcome)
    }
}
