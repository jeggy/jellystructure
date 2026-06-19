package dev.jellystructure.server.routes

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class JellyfinUserDto(
    val id: String,
    @SerialName("display_name") val displayName: String,
)

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

        get("/users") {
            val config = configStore.current
            if (config.apiKeys.jellyfinUrl.isBlank() || config.apiKeys.jellyfinToken.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Jellyfin not configured"))
                return@get
            }
            val users = jellyfinClient.getUsers(config.apiKeys.jellyfinUrl, config.apiKeys.jellyfinToken)
                .map { JellyfinUserDto(id = it.id, displayName = it.name) }
            call.respond(users)
        }
    }
}
