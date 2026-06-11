package dev.jellystructure.server.routes

import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.ConfigStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put

fun Route.configureConfigRoutes(configStore: ConfigStore) {
    get("/config") {
        call.respond(configStore.current)
    }
    put("/config") {
        val config = call.receive<AppConfig>()
        configStore.update(config)
        call.respond(HttpStatusCode.NoContent)
    }
}
