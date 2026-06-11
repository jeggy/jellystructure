package dev.jellystructure.server.routes

import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.ConfigStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame

fun Application.configureRoutes(configStore: ConfigStore) {
    routing {
        route("/api") {
            get("/health") {
                call.respond(mapOf("status" to "ok"))
            }
            get("/config") {
                call.respond(configStore.current)
            }
            put("/config") {
                val config = call.receive<AppConfig>()
                configStore.update(config)
                call.respond(HttpStatusCode.NoContent)
            }
        }
        webSocket("/ws") {
            // Phase 2+: emit job progress and scan events over this channel
            for (frame in incoming) {
                if (frame is Frame.Close) break
            }
        }
    }
}
