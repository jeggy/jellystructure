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
            call.respond(activityLog.list(page, pageSize, category, level))
        }

        delete {
            activityLog.clear()
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
