package dev.jellystructure.server.routes

import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.Scanner
import dev.jellystructure.model.MediaKind
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.mediaRoutes(store: MediaStore, scanner: Scanner) {
    route("/media") {
        get {
            val kindStr = call.request.queryParameters["kind"]
            val kind = kindStr?.let { runCatching { MediaKind.valueOf(it) }.getOrNull() }
            val filter = call.request.queryParameters["filter"]
            val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull()
                ?.coerceIn(1, 100) ?: 20
            call.respond(store.list(kind, filter, page, pageSize))
        }

        get("/{id}") {
            val id = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest)
            val item = store.get(id)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(item)
        }
    }

    post("/scan") {
        println("[INFO] Library scan triggered via API")
        val items = scanner.scan()
        store.update(items)
        call.respond(mapOf("scanned" to items.size))
    }

    get("/stats") {
        call.respond(
            mapOf(
                "movies" to store.movieCount(),
                "issues" to store.totalIssueCount(),
            )
        )
    }
}
