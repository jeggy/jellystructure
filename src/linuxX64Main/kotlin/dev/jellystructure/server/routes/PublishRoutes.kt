package dev.jellystructure.server.routes

import dev.jellystructure.auth.SessionKey
import dev.jellystructure.model.PublishActionResult
import dev.jellystructure.model.PublishIdsRequest
import dev.jellystructure.publish.PublishQueue
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Phase 307 — the publish queue on the Dashboard. Admin routes (inside the authenticated block like every other).
 * Only these presses move an item past `waiting` (FR-307-7); Publish is the only door out (FR-307-4).
 */
fun Route.publishRoutes(queue: PublishQueue) {
    fun ApplicationCall.who(): String =
        runCatching { attributes[SessionKey].jellyfinUsername }.getOrNull()?.takeIf { it.isNotBlank() } ?: "admin"
    fun plural(n: Int) = if (n == 1) "" else "s"

    route("/publish/items") {
        /** FR-307-3/5/6 — waiting, publishing, failed, the last 50 published, dismissed. */
        get { call.respond(queue.list()) }

        /** FR-307-4 — *Publish all n*. */
        post("/publish") {
            val req = runCatching { call.receive<PublishIdsRequest>() }.getOrDefault(PublishIdsRequest())
            val n = queue.publish(req.ids, call.who())
            call.respond(PublishActionResult(if (n > 0) "Publishing $n item${plural(n)}, one at a time" else "Nothing to publish", n))
        }
        /** FR-307-5 — *Don't publish any*. */
        post("/dismiss") {
            val req = runCatching { call.receive<PublishIdsRequest>() }.getOrDefault(PublishIdsRequest())
            val n = queue.dismiss(req.ids, call.who())
            call.respond(PublishActionResult(if (n > 0) "$n item${plural(n)} won’t be published" else "Nothing to dismiss", n))
        }
        /** FR-307-4 — *Publish* on one item. */
        post("/{id}/publish") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
            if (queue.item(id) == null) return@post call.respond(HttpStatusCode.NotFound)
            val n = queue.publish(listOf(id), call.who())
            call.respond(PublishActionResult(if (n > 0) "Publishing" else "Already published or on its way", n))
        }
        /** FR-307-4 — *Try again* on a failed item: the same frozen payload, sent again. */
        post("/{id}/try-again") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val it = queue.item(id) ?: return@post call.respond(HttpStatusCode.NotFound)
            if (it.state != PublishQueue.FAILED) return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "Only a failed item is tried again"))
            val n = queue.publish(listOf(id), call.who())
            call.respond(PublishActionResult(if (n > 0) "Trying again" else "Already on its way", n))
        }
        /** FR-307-5 — *Don't publish* on one item. */
        post("/{id}/dismiss") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
            if (queue.item(id) == null) return@post call.respond(HttpStatusCode.NotFound)
            val n = queue.dismiss(listOf(id), call.who())
            call.respond(PublishActionResult(if (n > 0) "Won’t be published" else "Already published or on its way", n))
        }
        /** FR-307-5 — *Queue again* on a dismissed item. */
        post("/{id}/queue-again") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
            if (queue.item(id) == null) return@post call.respond(HttpStatusCode.NotFound)
            if (!queue.queueAgain(id, call.who())) return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "It is already queued or published"))
            call.respond(PublishActionResult("Waiting to publish again", 1))
        }
    }
}
