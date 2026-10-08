package dev.jellystructure.server.routes

import dev.jellystructure.filefix.FileFixService
import dev.jellystructure.filefix.FixKind
import dev.jellystructure.model.FileFixSettingRequest
import dev.jellystructure.ops.GateClass
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Phase 314 — a file every device can play directly. The card (`GET /file-fix`), the dry run (`POST /file-fix/plan`,
 * in the background), a kind's list (`GET /file-fix/{kind}/rows`), its switches, Apply, and one title.
 * Nothing here writes a media file: Apply only queues; the jobs run on the media lane.
 */
fun Route.fileFixRoutes(service: FileFixService, scope: CoroutineScope) {
    route("/file-fix") {
        get { call.respond(service.overview()) }
        post("/plan") {
            if (service.planStatus().running) { call.respond(HttpStatusCode.Conflict, mapOf("error" to "The dry run is already running")); return@post }
            scope.launch(GateClass.BACKGROUND) { runCatching { service.buildPlan() } }
            call.respond(HttpStatusCode.Accepted, mapOf("ok" to true))
        }
        // Phase 314c — one title's rows for the Tracks tab.
        get("/title/{mediaId}") {
            val mediaId = call.parameters["mediaId"] ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing title"))
            call.respond(service.title(mediaId))
        }
        // Phase 314c (owner: rename, asked per film) — the ticked Dolby Vision 7 originals, renamed after their folder and
        // then given their version. Runs now (renames are instant); the version itself is a job on the media lane.
        post("/c/rename") {
            val req = call.receive<dev.jellystructure.model.FileFixRenameRequest>()
            if (req.paths.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Tick at least one film"))
            call.respond(service.renameAndQueue(req.paths.distinct()))
        }
        route("/{kind}") {
            post("/title/{mediaId}/remove") {
                val kind = FixKind.of(call.parameters["kind"]) ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown kind"))
                val mediaId = call.parameters["mediaId"] ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing title"))
                call.respond(mapOf("queued" to service.removeTitle(kind, mediaId)))
            }
            get("/rows") {
                val kind = FixKind.of(call.parameters["kind"]) ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown kind"))
                val state = call.request.queryParameters["state"]?.takeIf { it.isNotBlank() }
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 200
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                call.respond(service.rows(kind, state, limit, offset))
            }
            post("/setting") {
                val kind = FixKind.of(call.parameters["kind"]) ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown kind"))
                val req = call.receive<FileFixSettingRequest>()
                service.setSetting(kind, req.enabled, req.autoNew)
                call.respond(service.overview())
            }
            post("/apply") {
                val kind = FixKind.of(call.parameters["kind"]) ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown kind"))
                val n = service.apply(kind) ?: return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "Switch ${kind.label} on first"))
                call.respond(mapOf("queued" to n))
            }
            post("/title/{mediaId}") {
                val kind = FixKind.of(call.parameters["kind"]) ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown kind"))
                val mediaId = call.parameters["mediaId"] ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing title"))
                call.respond(mapOf("queued" to service.applyTitle(kind, mediaId)))
            }
        }
    }
}
