package dev.jellystructure.server.routes

import dev.jellystructure.arr.AcquisitionService
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

/** Phase 56 — admin acquisition API (under /api, behind the session-cookie auth plugin). */
fun Route.acquisitionRoutes(service: AcquisitionService) {
    post("/acquisition/request") {
        val req = call.receive<AcqRequest>()
        val kind = when {
            req.mediaKind.equals("series", true) || req.mediaKind.equals("tv", true) ||
                req.mediaKind.equals("tv_show", true) || req.mediaKind.equals("tvshow", true) -> MediaKind.SERIES
            else -> MediaKind.MOVIE
        }
        call.respond(service.request(kind, req.tmdbId, req.title, requestedBy = "admin"))
    }
    post("/acquisition/cancel") {
        val req = call.receive<AcqCancel>()
        call.respond(mapOf("ok" to service.cancel(req.itemKey)))
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
