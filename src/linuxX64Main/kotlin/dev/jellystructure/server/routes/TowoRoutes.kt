package dev.jellystructure.server.routes

import dev.jellystructure.towo.TowoService
import dev.jellystructure.towo.TowoSettings
import dev.jellystructure.towo.TowoStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.request.host
import io.ktor.server.request.port
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
private data class EnrollRequest(val name: String)

@Serializable
private data class EnrollResponse(val enrollmentToken: String, val expiresAt: Long, val commands: Map<String, String>)

@Serializable
private data class CreateSessionRequest(
    val folderId: String? = null,
    val path: String? = null,
    val prompt: String,
    // null = "use towo_settings' default" (spec §A/§F), resolved in TowoService.createSession --
    // not defaulted here, so the service can tell "caller didn't specify" from "caller chose normal".
    val permissionProfile: String? = null,
    val maxTurns: Long? = null,
)

@Serializable
private data class SendMessageRequest(val text: String)

@Serializable
private data class DecidePermissionRequest(val decision: String, val reason: String? = null)

@Serializable
private data class UpdateSessionRequest(
    val title: String? = null,
    val tag: String? = null,
    val maxTurns: Long? = null,
    val continueAfterReset: Boolean? = null,
)

@Serializable
private data class ResumeSessionRequest(val prompt: String = "Continue.")

/**
 * Phase 162 (Towo) — REST surface (spec §6). The two WebSocket endpoints (/api/towo/runner-link,
 * /api/towo/stream) live directly in Server.kt alongside /ws and /api/tv/events, not here — same
 * split this codebase already uses for every other WS route, and for the same reason: they need
 * connection-lifecycle code (register/unregister on the socket itself), not a single request/response.
 */
fun Route.towoRoutes(service: TowoService, store: TowoStore) {
    route("/towo") {
        get("/settings") {
            call.respond(store.getSettings())
        }

        put("/settings") {
            val settings = call.receive<TowoSettings>()
            store.updateSettings(settings)
            call.respond(HttpStatusCode.NoContent)
        }

        post("/runners/enroll") {
            val req = call.receive<EnrollRequest>()
            // Settings' "where runners connect" field (spec §A) isn't built yet (that's build-order
            // step 8's territory) -- derive it from the request itself as a reasonable v1 default.
            val scheme = if (call.request.origin.scheme == "https") "wss" else "ws"
            val publicWsBase = "$scheme://${call.request.host()}:${call.request.port()}"
            val minted = service.mintEnrollment(req.name, publicWsBase)
            call.respond(EnrollResponse(minted.token, minted.expiresAt, minted.commands))
        }

        get("/runners") {
            call.respond(store.allRunners())
        }

        get("/runners/{id}") {
            val id = call.parameters["id"]!!
            val runner = store.getRunner(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(runner)
        }

        get("/runners/{id}/folders") {
            val id = call.parameters["id"]!!
            call.respond(store.foldersForRunner(id))
        }

        get("/runners/{id}/quota") {
            val id = call.parameters["id"]!!
            call.respond(store.quotaForRunner(id))
        }

        delete("/runners/{id}") {
            val id = call.parameters["id"]!!
            store.deleteRunner(id)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/sessions") {
            val runnerId = call.request.queryParameters["runnerId"]
            val status = call.request.queryParameters["status"]
            val sessions = when {
                runnerId != null -> store.sessionsForRunner(runnerId)
                status != null -> store.sessionsByStatus(status)
                else -> store.allSessions()
            }
            call.respond(sessions)
        }

        post("/sessions") {
            val req = call.receive<CreateSessionRequest>()
            val folder = req.folderId?.let { store.getFolder(it) }
            val runnerId = folder?.runnerId
            val folderPath = folder?.absPath ?: req.path
            if (runnerId == null || folderPath == null) {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "folderId (resolved via a known folder) or path is required"))
            }
            val ok = service.createSession(runnerId, req.folderId, folderPath, req.prompt, req.maxTurns, req.permissionProfile)
            if (ok) call.respond(HttpStatusCode.Accepted) else call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "runner is offline"))
        }

        get("/sessions/{id}") {
            val id = call.parameters["id"]!!
            val session = store.getSession(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(session)
        }

        get("/sessions/{id}/messages") {
            val id = call.parameters["id"]!!
            call.respond(service.getMessages(id))
        }

        post("/sessions/{id}/messages") {
            val id = call.parameters["id"]!!
            val req = call.receive<SendMessageRequest>()
            val ok = service.sendMessage(id, req.text)
            if (ok) call.respond(HttpStatusCode.Accepted) else call.respond(HttpStatusCode.ServiceUnavailable)
        }

        post("/sessions/{id}/interrupt") {
            val id = call.parameters["id"]!!
            val ok = service.interrupt(id)
            if (ok) call.respond(HttpStatusCode.Accepted) else call.respond(HttpStatusCode.ServiceUnavailable)
        }

        patch("/sessions/{id}") {
            val id = call.parameters["id"]!!
            val req = call.receive<UpdateSessionRequest>()
            val ok = service.updateSessionMeta(id, req.title, req.tag, req.maxTurns, req.continueAfterReset)
            if (ok) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.NotFound)
        }

        // Manual "resume now" (spec §G's recovery affordances) -- the same mechanism
        // TowoAutoContinueScheduler uses, just user-triggered instead of timer-triggered.
        post("/sessions/{id}/resume") {
            val id = call.parameters["id"]!!
            val req = runCatching { call.receive<ResumeSessionRequest>() }.getOrDefault(ResumeSessionRequest())
            val ok = service.resumeSession(id, req.prompt)
            if (ok) call.respond(HttpStatusCode.Accepted) else call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "runner is offline or session has no known folder path"))
        }

        delete("/sessions/{id}") {
            val id = call.parameters["id"]!!
            store.deleteSession(id)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/permissions") {
            call.respond(store.pendingPermissionRequests())
        }

        post("/permissions/{id}") {
            val id = call.parameters["id"]!!
            val req = call.receive<DecidePermissionRequest>()
            if (req.decision != "allow" && req.decision != "deny") {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "decision must be allow or deny"))
            }
            val ok = service.decidePermission(id, req.decision, req.reason)
            if (ok) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.NotFound)
        }
    }
}
