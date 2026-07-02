package dev.jellystructure.server.routes

import dev.jellystructure.media.MediaJobQueue
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class JobsSummary(
    val busy: Boolean,
    val doneToday: Int,
    val running: dev.jellystructure.jobs.MediaJobSnapshot? = null,
    val queued: List<dev.jellystructure.jobs.MediaJobSnapshot> = emptyList(),
    val recent: List<dev.jellystructure.jobs.MediaJobSnapshot> = emptyList(),
)

/** Phase 109 — Activity ▸ Jobs & workers: read the media-worker queue and cancel/retry a job. Live
 *  progress streams over the existing `/ws` connection as `media_job` JobEvents; this is just the
 *  initial-load snapshot + the two mutating actions. */
fun Route.jobsRoutes(mediaJobQueue: MediaJobQueue) {
    route("/jobs") {
        get {
            call.respond(JobsSummary(
                busy = mediaJobQueue.isBusy(),
                doneToday = mediaJobQueue.doneToday(),
                running = mediaJobQueue.running(),
                queued = mediaJobQueue.queued(),
                recent = mediaJobQueue.recent(),
            ))
        }

        post("/{id}/cancel") {
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val ok = mediaJobQueue.cancel(id)
            if (ok) call.respond(mapOf("ok" to true))
            else call.respond(HttpStatusCode.Conflict, mapOf("error" to "Job is not queued or running"))
        }

        post("/{id}/retry") {
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val snap = mediaJobQueue.retry(id)
            if (snap != null) call.respond(mapOf("jobId" to snap.id))
            else call.respond(HttpStatusCode.Conflict, mapOf("error" to "Job is not failed or cancelled"))
        }
    }
}
