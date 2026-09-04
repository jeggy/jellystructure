package dev.jellystructure.server.routes

import dev.jellystructure.arr.AcquisitionService
import dev.jellystructure.seerr.RequestLifecycleService
import dev.jellystructure.shared.tv.MediaKind
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
private data class AcqRequest(val mediaKind: String, val tmdbId: Int, val title: String = "")

@Serializable
private data class AcqCancel(val itemKey: String)

/** Phase 186 (FR-186-6) — the full cascade the 2026-09-04 manual three-system cleanup needed by hand.
 *  `deleteFiles`/`addExclusion` default false: this is "stop wanting it", never "delete my library". */
@Serializable
private data class AcqRemoveRequest(val tmdbId: Int, val mediaKind: String, val deleteFiles: Boolean = false, val addExclusion: Boolean = false)

private fun parseMediaKind(raw: String): MediaKind = when {
    raw.equals("series", true) || raw.equals("tv", true) || raw.equals("tv_show", true) || raw.equals("tvshow", true) -> MediaKind.SERIES
    else -> MediaKind.MOVIE
}

/** Phase 56 — admin acquisition API (under /api, behind the session-cookie auth plugin).
 *  [lifecycle] is Phase 186's reconciliation service — null (route reports unavailable) until it's
 *  wired, same nullable-optional pattern as every other feature service in this file's Server.kt caller. */
fun Route.acquisitionRoutes(service: AcquisitionService, lifecycle: RequestLifecycleService? = null) {
    post("/acquisition/request") {
        val req = call.receive<AcqRequest>()
        call.respond(service.request(parseMediaKind(req.mediaKind), req.tmdbId, req.title, requestedBy = "admin"))
    }
    post("/acquisition/cancel") {
        val req = call.receive<AcqCancel>()
        call.respond(mapOf("ok" to service.cancel(req.itemKey)))
    }
    post("/acquisition/request/remove") {
        val lc = lifecycle ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
        val req = call.receive<AcqRemoveRequest>()
        lc.removeRequest(parseMediaKind(req.mediaKind), req.tmdbId, req.deleteFiles, req.addExclusion)
        call.respond(mapOf("ok" to true))
    }
    get("/acquisition") {
        val keys = call.request.queryParameters["keys"]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        call.respond(service.snapshot(keys))
    }
    get("/acquisition/{itemKey}") {
        val key = call.parameters["itemKey"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val rec = service.get(key) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(rec)
    }
}
