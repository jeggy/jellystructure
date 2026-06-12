package dev.jellystructure.server.routes

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.jellyfinRoutes(configStore: ConfigStore, jellyfinClient: JellyfinClient) {
    route("/jellyfin") {
        get("/libraries") {
            val config = configStore.current
            if (config.apiKeys.jellyfinUrl.isBlank() || config.apiKeys.jellyfinToken.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Jellyfin URL and token are required"))
                return@get
            }
            val libraries = jellyfinClient.getLibraries(config.apiKeys.jellyfinUrl, config.apiKeys.jellyfinToken)
            call.respond(libraries)
        }
    }
}
