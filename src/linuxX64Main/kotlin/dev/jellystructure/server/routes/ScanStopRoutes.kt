package dev.jellystructure.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/**
 * Phase 214 (FR-214-3) — the answer to Stop scan and Stop this step: [status] as before, and how many subtitle
 * extractions Jellyfin is still finishing, which stopping cannot reach. DEB-1906 — this used to be
 * `mapOf("status" to String, "subtitlesStillRunning" to Int)`, a map whose values have two types, which kotlinx
 * serialization cannot encode: both routes answered 500 after the stop had already happened.
 */
@Serializable
data class ScanStopResponse(val status: String, val subtitlesStillRunning: Int)

/**
 * `POST /api/scan/cancel` and `POST /api/scan/stop-step`. The four lambdas are the seam: the route test
 * passes its own, [Route.mediaRoutes] wires the live tracker and queue.
 */
internal fun Route.scanStopRoutes(
    isRunning: () -> Boolean,
    cancelRun: () -> Unit,
    stopCurrentStep: () -> Unit,
    subtitlesStillRunning: () -> Int,
) {
    post("/scan/cancel") {
        if (!isRunning()) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to "no scan running"))
            return@post
        }
        // Phase 182 (FR-182-5) — cancelRun actually cancels the run's Job (not just the cooperative
        // flag) and force-frees its slots if it doesn't wind down within a bounded grace period.
        cancelRun()
        // Phase 214 (FR-214-3) — say what stopping cannot reach: how many subtitle extractions Jellyfin
        // is still finishing at the moment this was pressed (structurally at most 1 since Phase 213's
        // per-queue occupancy rule, but computed rather than assumed). 0 means the frontend shows nothing.
        call.respond(ScanStopResponse("cancel requested", subtitlesStillRunning()))
    }

    // Phase 214 (FR-214-2) — stop only the CURRENTLY EXECUTING step; the run continues to the next one
    // afterward. See ScanTracker.stopCurrentStep's doc and PipelineStepPool's two check sites.
    post("/scan/stop-step") {
        if (!isRunning()) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to "no scan running"))
            return@post
        }
        stopCurrentStep()
        call.respond(ScanStopResponse("step stop requested", subtitlesStillRunning()))
    }
}
