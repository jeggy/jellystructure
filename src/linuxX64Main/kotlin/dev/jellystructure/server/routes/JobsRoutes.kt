package dev.jellystructure.server.routes

import dev.jellystructure.auth.SessionKey
import dev.jellystructure.media.MediaJobQueue
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

// Phase 164 widened this for the two-lane queue; Phase 213 widened it again to three, sharing one
// worker pool (behavior.job_workers): `running` is every currently-running job across all THREE queues
// (each has at most one, per FR-213-1's occupancy rule), and `lanes` carries each queue's own
// busy/queued/done-today counts for the Jobs page's three worker lines.
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

/** Phase 260 (FR-260-1) — `POST /api/jobs/empty` body. */
@Serializable
data class EmptyQueuesRequest(val queues: List<String> = emptyList())

/** Phase 260 (FR-260-1/7) — the answer carries the new per-queue counts too (dev review item 2: the view
 *  polls, there is no job WS event), so one response is the one number source for every chip at once. */
@Serializable
data class EmptyQueuesResponse(
    val removed: Map<String, Int>,
    @kotlinx.serialization.SerialName("kept_running") val keptRunning: List<dev.jellystructure.jobs.MediaJobSnapshot>,
    val lanes: List<dev.jellystructure.jobs.LaneSummary>,
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

        // Phase 260 (FR-260-1) — empty the waiting part of one or more queues; what runs, finishes.
        post("/empty") {
            val session = runCatching { call.attributes[SessionKey] }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
            val req = runCatching { call.receive<EmptyQueuesRequest>() }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Body must be {\"queues\": [...]}"))
            val unknown = req.queues.filter { it !in MediaJobQueue.QUEUE_NAMES }
            if (req.queues.isEmpty() || unknown.isNotEmpty()) {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "queues must be a non-empty subset of ${MediaJobQueue.QUEUE_NAMES}", "unknown" to unknown))
            }
            val result = mediaJobQueue.emptyQueues(req.queues, by = session.jellyfinUsername)
            call.respond(EmptyQueuesResponse(removed = result.removed, keptRunning = result.keptRunning, lanes = mediaJobQueue.laneSummaries()))
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
