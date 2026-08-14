package dev.jellystructure.server.routes

import dev.jellystructure.media.MediaJobQueue
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

// Phase 164 — widened for the two-lane queue: `running` is now every currently-running job across BOTH
// lanes (the media lane has at most one; the segments lane can have up to behavior.segment_workers), and
// `lanes` carries each lane's own busy/queued/done-today counts for the Jobs page's two worker lines.
// `busy`/`doneToday` are KEPT (not removed) for one release so an old cached frontend build doesn't
// crash on a missing field; the new UI reads `lanes` instead.
@Serializable
data class JobsSummary(
    val busy: Boolean,
    val doneToday: Int,
    val lanes: List<dev.jellystructure.jobs.LaneSummary> = emptyList(),
    val running: List<dev.jellystructure.jobs.MediaJobSnapshot> = emptyList(),
    val queued: List<dev.jellystructure.jobs.MediaJobSnapshot> = emptyList(),
    val recent: List<dev.jellystructure.jobs.MediaJobSnapshot> = emptyList(),
)

/** Phase 109 (widened Phase 164) — Activity ▸ Jobs & workers: read the media-worker + segments-lane
 *  queues and cancel/retry a job. Live progress streams over the existing `/ws` connection as
 *  `media_job` JobEvents; this is just the initial-load snapshot + the two mutating actions. */
fun Route.jobsRoutes(mediaJobQueue: MediaJobQueue) {
    route("/jobs") {
        get {
            val lanes = mediaJobQueue.laneSummaries()
            call.respond(JobsSummary(
                busy = mediaJobQueue.isBusy(),
                doneToday = lanes.sumOf { it.doneToday },
                lanes = lanes,
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
