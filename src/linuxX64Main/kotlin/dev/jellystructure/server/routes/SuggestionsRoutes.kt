package dev.jellystructure.server.routes

import dev.jellystructure.model.SuggestionDismissRequest
import dev.jellystructure.suggestions.SuggestionService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Phase 274 — the admin's Suggestions page. Every route answers 404 while Seerr is not connected (FR-274-1): the page,
 * its sidebar entry and the Dashboard card ask, and draw nothing on a 404.
 */
fun Route.suggestionsRoutes(service: SuggestionService) {
    route("/suggestions") {
        get {
            if (!service.available()) return@get call.respond(HttpStatusCode.NotFound)
            call.respond(service.page())
        }
        get("/summary") {
            if (!service.available()) return@get call.respond(HttpStatusCode.NotFound)
            call.respond(service.summary() ?: return@get call.respond(HttpStatusCode.NoContent))
        }
        get("/dismissed") {
            if (!service.available()) return@get call.respond(HttpStatusCode.NotFound)
            call.respond(service.dismissed())
        }
        /** FR-274-7 — queues a build in the background; never runs it inline. */
        post("/rebuild") {
            if (!service.available()) return@post call.respond(HttpStatusCode.NotFound)
            val who = runCatching { call.attributes[dev.jellystructure.auth.SessionKey].jellyfinUsername }.getOrNull() ?: "admin"
            call.respond(mapOf("queued" to service.queueBuild(SuggestionService.REASON_BY_HAND, who).toString()))
        }
        post("/{tmdbId}/download") {
            if (!service.available()) return@post call.respond(HttpStatusCode.NotFound)
            val id = call.parameters["tmdbId"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val session = runCatching { call.attributes[dev.jellystructure.auth.SessionKey] }.getOrNull() ?: return@post call.respond(HttpStatusCode.Unauthorized)
            call.respond(service.download(id, session.jellyfinUserId, session.jellyfinUsername))
        }
        post("/{tmdbId}/dismiss") {
            if (!service.available()) return@post call.respond(HttpStatusCode.NotFound)
            val id = call.parameters["tmdbId"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val req = runCatching { call.receive<SuggestionDismissRequest>() }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val who = runCatching { call.attributes[dev.jellystructure.auth.SessionKey].jellyfinUsername }.getOrNull() ?: "admin"
            call.respond(service.dismiss(id, req.reason, req.note, who))
        }
        /** *Undo* and *Bring back* (FR-274-11/12). */
        delete("/{tmdbId}/dismiss") {
            if (!service.available()) return@delete call.respond(HttpStatusCode.NotFound)
            val id = call.parameters["tmdbId"]?.toIntOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest)
            call.respond(service.bringBack(id))
        }
    }
}
