package dev.jellystructure.server.routes

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.media.FfmpegRunner
import dev.jellystructure.media.FfprobeRunner
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.MkvpropeditRunner
import dev.jellystructure.model.TrackKind
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
private data class SetDefaultRequest(val specifier: String)

@Serializable
data class TrackPlan(
    val command: String,
    val tool: String,
    val estimatedMs: Int,
    val targetSpecifier: String,
)

fun Route.trackRoutes(store: MediaStore, configStore: ConfigStore, jellyfinClient: JellyfinClient) {
    route("/media/{id}") {
        // GET /api/media/{id}/tracks/plan?specifier=a:0 — dry-run: returns command without executing
        get("/tracks/plan") {
            val id = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest)
            val item = store.get(id)
                ?: return@get call.respond(HttpStatusCode.NotFound)

            val specifier = call.request.queryParameters["specifier"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "specifier required"))
            val targetTrack = item.tracks.firstOrNull { it.specifier == specifier }
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "track not found"))

            val ext = item.path.substringAfterLast('.').lowercase()
            val sameType = item.tracks.filter { it.kind == targetTrack.kind }

            if (ext == "mkv") {
                val escaped = item.path.replace("'", "'\\''")
                val parts = sameType.map { t ->
                    val flag = if (t.streamIndex == targetTrack.streamIndex) 1 else 0
                    "--edit track:@${t.streamIndex + 1} --set flag-default=$flag"
                }.joinToString(" \\\n  ")
                call.respond(
                    TrackPlan(
                        command = "mkvpropedit '$escaped' \\\n  $parts",
                        tool = "mkvpropedit",
                        estimatedMs = 40,
                        targetSpecifier = specifier,
                    )
                )
            } else {
                call.respond(
                    TrackPlan(
                        command = FfmpegRunner.planSetDefault(item.path, targetTrack.streamIndex, sameType.map { it.streamIndex }, targetTrack.kind),
                        tool = "ffmpeg",
                        estimatedMs = 5000,
                        targetSpecifier = specifier,
                    )
                )
            }
        }

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
            val sameType = item.tracks.filter { it.kind == targetTrack.kind }

            val ok = if (ext == "mkv") {
                MkvpropeditRunner.setDefault(item.path, targetTrack.streamIndex, sameType.map { it.streamIndex })
            } else {
                FfmpegRunner.setDefault(item.path, targetTrack.streamIndex, sameType.map { it.streamIndex }, targetTrack.kind)
            }

            if (!ok) {
                val tool = if (ext == "mkv") "mkvpropedit" else "ffmpeg"
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "$tool failed"))
                return@post
            }

            val newTracks = FfprobeRunner.probe(item.path)
            val newIssueCount = newTracks.count {
                (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null
            }
            val updated = item.copy(tracks = newTracks, issueCount = newIssueCount)
            store.updateOne(updated)

            val cfg = configStore.current
            if (!item.jellyfinId.isNullOrBlank() && cfg.apiKeys.jellyfinUrl.isNotBlank()) {
                jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, item.jellyfinId)
            }

            call.respond(mapOf("ok" to true))
        }

        // POST /api/media/{id}/jellyfin-refresh — trigger Jellyfin to reload this item's metadata
        post("/jellyfin-refresh") {
            val id = call.parameters["id"]
                ?: return@post call.respond(HttpStatusCode.BadRequest)
            val item = store.get(id)
            val config = configStore.current
            if (config.apiKeys.jellyfinUrl.isBlank() || config.apiKeys.jellyfinToken.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Jellyfin not configured"))
                return@post
            }
            val jellyfinId = item?.jellyfinId
            val ok = if (!jellyfinId.isNullOrBlank()) {
                jellyfinClient.refreshItem(config.apiKeys.jellyfinUrl, config.apiKeys.jellyfinToken, jellyfinId)
            } else {
                jellyfinClient.triggerLibraryRefresh(config.apiKeys.jellyfinUrl, config.apiKeys.jellyfinToken)
            }
            if (ok) call.respond(mapOf("ok" to true))
            else call.respond(HttpStatusCode.BadGateway, mapOf("error" to "Jellyfin refresh failed"))
        }
    }
}
