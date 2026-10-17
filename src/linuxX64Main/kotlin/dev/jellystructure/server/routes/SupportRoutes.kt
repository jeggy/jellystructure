package dev.jellystructure.server.routes

import dev.jellystructure.auth.DeviceKey
import dev.jellystructure.auth.SessionKey
import dev.jellystructure.auth.SupportSeatKey
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.model.SupportEnabledRequest
import dev.jellystructure.model.SupportClassRequest
import dev.jellystructure.model.SupportFlagRequest
import dev.jellystructure.model.SupportForwardRequest
import dev.jellystructure.model.SupportHervorRequest
import dev.jellystructure.model.SupportStateRequest
import dev.jellystructure.log.Logger
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
internal fun Route.supportRoutes(service: SupportService, configStore: ConfigStore? = null) {
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

    // DEB-1508 (FR-4) — the five routes Hervør's seat token reaches are these existing ones: `AuthPlugin` lets a `jss_` bearer
    // through to exactly them, and each answers a seat call (`SupportSeatKey` set) with her view — only her tickets (FR-6),
    // the viewer anonymous (FR-7) — and writes as HERVOR (FR-11, FR-12). Every other route here never sees the seat.
    get("/support/tickets") {
        val q = call.request.queryParameters
        val filter = SupportService.AdminFilter(
            state = q["state"]?.takeIf { it.isNotBlank() }, holder = q["holder"]?.takeIf { it.isNotBlank() },
            owes = q["owes"]?.takeIf { it.isNotBlank() }, flagged = q["flagged"] == "true",
            overdue = q["overdue"]?.let { it == "true" },
        )
        call.respond(if (call.isSeatCall()) service.seatList(filter) else service.adminList(filter))
    }

    get("/support/summary") { call.respond(service.summary()) }

    // DEB-1500 (FR-3) — the lane's switch. It writes `[support] enabled` through ConfigStore.update, so the `config_changed`
    // Ravilo re-fetches on is emitted by the hook wired in `wireSupport`, and it answers with the new summary (FR-4): the
    // admin renders that and nothing it assumed. `PUT /api/config` still restores `[support]` from the stored config, so a
    // Settings save can never flip the lane.
    post("/support/enabled") {
        val store = configStore ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "config_unavailable"))
        val req = call.receiveOrBadRequest<SupportEnabledRequest>() ?: return@post
        val current = store.current
        if (current.support.enabled != req.enabled &&
            !store.update(current.copy(support = current.support.copy(enabled = req.enabled)))) {
            return@post call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "config_not_saved"))
        }
        call.respond(service.summary())
    }

    // DEB-1508 (FR-8) — Hervør's switch. It writes `[support] hervor_enabled` through ConfigStore.update and answers the summary,
    // like `/enabled`. On needs a live seat token (409 `no_seat_token`); off hands every ticket she holds or owes back to admins
    // (FR-9). `PUT /api/config` still restores `[support]`, so a Settings save never flips it.
    post("/support/hervor") {
        val store = configStore ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "config_unavailable"))
        val req = call.receiveOrBadRequest<SupportHervorRequest>() ?: return@post
        if (req.enabled && !service.seatTokens.hasLive) {
            return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "no_seat_token", "reason" to "Issue Hervør a token first"))
        }
        if (!store.setHervorEnabled(req.enabled)) {
            return@post call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "config_not_saved"))
        }
        val by = call.adminName()
        val moved = if (req.enabled) emptyList() else service.handBackFromHervor(by)
        Logger.info("Support: $by switched Hervør ${if (req.enabled) "on" else "off"}" + if (moved.isNotEmpty()) " (${moved.size} ticket(s) handed back)" else "", "support")
        call.respond(service.summary())
    }

    // DEB-1508 (FR-2) — Hervør's seat token. Cookie only: the plugin refuses these routes to the seat itself (FR-4).
    get("/support/seat-token") { call.respond(service.seatTokens.state()) }

    post("/support/seat-token") {
        val by = call.adminName()
        val replaced = service.seatTokens.hasLive
        val issued = service.seatTokens.issue(by)
        Logger.info("Support: $by ${if (replaced) "replaced" else "issued"} Hervør's seat token", "support")
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respond(HttpStatusCode.Created, issued)
    }

    // Revoking also switches her off and hands her tickets back (FR-2, FR-9): one click stops her completely, and a later
    // token never switches her back on by itself. With no live token it changes nothing.
    delete("/support/seat-token") {
        val by = call.adminName()
        if (!service.seatTokens.revoke(by)) return@delete call.respond(HttpStatusCode.NoContent)
        configStore?.setHervorEnabled(false)
        val moved = service.handBackFromHervor(by)
        Logger.info("Support: $by revoked Hervør's seat token and switched her off (${moved.size} ticket(s) handed back)", "support")
        call.respond(service.seatTokens.state())
    }

    // DEB-1500 (FR-12) — Activity › Support log: history only, newest first, no bodies.
    get("/support/log") {
        val q = call.request.queryParameters
        call.respond(service.log(q["before"]?.toLongOrNull(), q["limit"]?.toIntOrNull() ?: SupportService.LOG_PAGE))
    }

    get("/support/tickets/{n}") {
        val n = call.ticketNumber() ?: return@get call.respondNotFound()
        val thread = (if (call.isSeatCall()) service.seatThread(n) else service.adminThread(n)) ?: return@get call.respondNotFound()
        call.respond(thread)
    }

    // 321 (FR-321-10) — every request `AI Agent` made for this ticket, for the ticket's life.
    get("/support/tickets/{n}/transcript") {
        val n = call.ticketNumber() ?: return@get call.respondNotFound()
        call.respond(service.agentTranscript(n) ?: return@get call.respondNotFound())
    }

    post("/support/tickets/{n}/messages") {
        val seatToken = call.attributes.getOrNull(SupportSeatKey)
        // A seat call's rules run in FR-11's order inside the service (the 404 comes before the 409), so it is not gated here.
        if (seatToken == null && !call.adminWriteOpen(service)) return@post
        val n = call.ticketNumber() ?: return@post call.respondNotFound()
        val req = call.receiveOrBadRequest<SupportPostRequest>() ?: return@post
        call.respondOutcome(
            if (seatToken != null) service.seatReply(n, seatToken, req.body) else service.reply(n, call.adminName(), req.body),
        )
    }

    post("/support/tickets/{n}/forward") {
        val seatToken = call.attributes.getOrNull(SupportSeatKey)
        if (seatToken == null && !call.adminWriteOpen(service)) return@post
        val n = call.ticketNumber() ?: return@post call.respondNotFound()
        val req = call.receiveOrBadRequest<SupportForwardRequest>() ?: return@post
        call.respondOutcome(
            if (seatToken != null) service.seatForward(n, seatToken, req.holder, req.seat, req.owes, req.note)
            else service.forward(n, call.adminName(), req.holder, req.seat, req.owes, req.note),
        )
    }

    post("/support/tickets/{n}/state") {
        if (!call.adminWriteOpen(service)) return@post
        val n = call.ticketNumber() ?: return@post call.respondNotFound()
        val req = call.receiveOrBadRequest<SupportStateRequest>() ?: return@post
        call.respondOutcome(service.setState(n, req.state, call.adminName()))
    }

    post("/support/tickets/{n}/class") {
        if (!call.adminWriteOpen(service)) return@post
        val n = call.ticketNumber() ?: return@post call.respondNotFound()
        val req = call.receiveOrBadRequest<SupportClassRequest>() ?: return@post
        call.respondOutcome(service.setClass(n, req.ticketClass, call.adminName()))
    }

    post("/support/tickets/{n}/flag") {
        if (!call.adminWriteOpen(service)) return@post
        val n = call.ticketNumber() ?: return@post call.respondNotFound()
        val req = call.receiveOrBadRequest<SupportFlagRequest>() ?: return@post
        call.respondOutcome(service.setFlag(n, req.flagged, call.adminName()))
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

/** DEB-1508 (FR-3) — the call carries Hervør's seat token, not an admin's session. */
private fun ApplicationCall.isSeatCall(): Boolean = attributes.contains(SupportSeatKey)

/** Writes `[support] hervor_enabled`; true when it is now [enabled], whether or not a write was needed. */
private suspend fun ConfigStore.setHervorEnabled(enabled: Boolean): Boolean {
    val current = this.current
    return current.support.hervorEnabled == enabled || update(current.copy(support = current.support.copy(hervorEnabled = enabled)))
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
