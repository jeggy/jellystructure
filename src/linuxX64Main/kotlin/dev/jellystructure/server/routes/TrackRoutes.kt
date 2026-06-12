package dev.jellystructure.server.routes

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.media.FfprobeRunner
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.MkvpropeditRunner
import dev.jellystructure.model.TrackKind
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
private data class SetDefaultRequest(val specifier: String)

fun Route.trackRoutes(store: MediaStore, configStore: ConfigStore, jellyfinClient: JellyfinClient) {
    route("/media/{id}") {
        // POST /api/media/{id}/tracks/default — set a track as default for its type
        post("/tracks/default") {
            val id = call.parameters["id"]
                ?: return@post call.respond(HttpStatusCode.BadRequest)
            val item = store.get(id)
                ?: return@post call.respond(HttpStatusCode.NotFound)

            val req = call.receive<SetDefaultRequest>()
            val targetTrack = item.tracks.firstOrNull { it.specifier == req.specifier }
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "track not found"))

            val ext = item.path.substringAfterLast('.').lowercase()
            if (ext != "mkv") {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "only MKV supported for header-only edits"))
                return@post
            }

            // All tracks of the same type — set default on target, clear all others
            val sameType = item.tracks.filter { it.kind == targetTrack.kind }
            val ok = MkvpropeditRunner.setDefault(
                item.path,
                targetTrack.streamIndex,
                sameType.map { it.streamIndex },
            )
            if (!ok) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "mkvpropedit failed"))
                return@post
            }

            val newTracks = FfprobeRunner.probe(item.path)
            val newIssueCount = newTracks.count {
                (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null
            }
            store.updateOne(item.copy(tracks = newTracks, issueCount = newIssueCount))
            call.respond(mapOf("ok" to true))
        }

        // POST /api/media/{id}/jellyfin-refresh — trigger Jellyfin to reload this item's metadata
        post("/jellyfin-refresh") {
            val config = configStore.current
            if (config.apiKeys.jellyfinUrl.isBlank() || config.apiKeys.jellyfinToken.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Jellyfin not configured"))
                return@post
            }
            val ok = jellyfinClient.triggerLibraryRefresh(config.apiKeys.jellyfinUrl, config.apiKeys.jellyfinToken)
            if (ok) call.respond(mapOf("ok" to true))
            else call.respond(HttpStatusCode.BadGateway, mapOf("error" to "Jellyfin refresh failed"))
        }
    }
}
