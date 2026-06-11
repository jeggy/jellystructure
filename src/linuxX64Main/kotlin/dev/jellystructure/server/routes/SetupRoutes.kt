package dev.jellystructure.server.routes

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
private data class SetupRequest(
    val jellyfinUrl: String,
    val jellyfinToken: String,
    val tmdbKey: String,
)

@Serializable
private data class ConnectionTestResult(val jellyfin: Boolean, val tmdb: Boolean)

fun Route.setupRoutes(configStore: ConfigStore, jellyfinClient: JellyfinClient) {
    route("/setup") {
        get {
            if (configStore.current.apiKeys.jellyfinUrl.isNotBlank()) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            call.respond(mapOf("configured" to false))
        }

        post {
            if (configStore.current.apiKeys.jellyfinUrl.isNotBlank()) {
                call.respond(HttpStatusCode.NotFound)
                return@post
            }
            val req = call.receive<SetupRequest>()
            if (req.jellyfinUrl.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "jellyfinUrl is required"))
                return@post
            }
            val updated = configStore.current.copy(
                apiKeys = configStore.current.apiKeys.copy(
                    jellyfinUrl = req.jellyfinUrl.trimEnd('/'),
                    jellyfinToken = req.jellyfinToken,
                    tmdbV3Key = req.tmdbKey,
                ),
            )
            configStore.update(updated)
            call.respond(HttpStatusCode.NoContent)
        }
    }

    post("/connections/test") {
        val config = configStore.current

        val jellyfinOk = if (config.apiKeys.jellyfinUrl.isNotBlank()) {
            jellyfinClient.testConnection(config.apiKeys.jellyfinUrl, config.apiKeys.jellyfinToken)
        } else false

        val tmdbOk = config.apiKeys.tmdbV3Key.isNotBlank()

        call.respond(ConnectionTestResult(jellyfin = jellyfinOk, tmdb = tmdbOk))
    }
}
