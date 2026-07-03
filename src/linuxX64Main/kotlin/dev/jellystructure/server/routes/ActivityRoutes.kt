package dev.jellystructure.server.routes

import dev.jellystructure.media.ActivityLog
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.activityRoutes(activityLog: ActivityLog) {
    route("/activity/log") {
        get {
            val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
            val category = call.request.queryParameters["category"]?.takeIf { it.isNotBlank() }
            val level = call.request.queryParameters["level"]?.takeIf { it.isNotBlank() }
            val run = call.request.queryParameters["run"]?.takeIf { it.isNotBlank() }   // 93g
            val step = call.request.queryParameters["step"]?.takeIf { it.isNotBlank() } // Phase 135 (FR-135-3 item 7)
            call.respond(activityLog.list(page, pageSize, category, level, run, step))
        }

        delete {
            activityLog.clear()
            call.respond(HttpStatusCode.NoContent)
        }
    }

    // 93g — recent scan/pipeline runs (newest first) for the Activity run picker.
    get("/activity/runs") {
        call.respond(activityLog.runSummaries())
    }
}
