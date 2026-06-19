package dev.jellystructure.server.routes

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.server.routes.fireWebhook
import dev.jellystructure.media.FfmpegRunner
import dev.jellystructure.media.FfprobeRunner
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.MkvpropeditRunner
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.TrackKind
import dev.jellystructure.torrent.SeedingCheckResult
import dev.jellystructure.torrent.SeedingGuard
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
private data class SetDefaultRequest(val specifier: String)

@Serializable
private data class SetLanguageRequest(val specifier: String, val language: String)

@Serializable
data class TrackSnap(
    val specifier: String,
    val language: String?,
    val codec: String,
    val title: String?,
    val isDefault: Boolean,
    val kind: String,
)

@Serializable
data class TrackPlan(
    val command: String,
    val tool: String,
    val estimatedMs: Int,
    val targetSpecifier: String,
    val before: List<TrackSnap> = emptyList(),
    val after: List<TrackSnap> = emptyList(),
)

fun Route.trackRoutes(store: MediaStore, configStore: ConfigStore, jellyfinClient: JellyfinClient, mediaHistory: MediaHistory, seedingGuard: SeedingGuard) {
    route("/media/{id}") {
        // GET /api/media/{id}/tracks/plan?specifier=a:0 — dry-run: returns command without executing
        get("/tracks/plan") {
            val id = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id)
                ?: return@get call.respond(HttpStatusCode.NotFound)

            val specifier = call.request.queryParameters["specifier"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "specifier required"))
            val targetTrack = item.tracks.firstOrNull { it.specifier == specifier }
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "track not found"))

            val ext = item.path.substringAfterLast('.').lowercase()
            val sameType = item.tracks.filter { it.kind == targetTrack.kind }

            val kindStr = targetTrack.kind.name.lowercase()
            val beforeSnaps = sameType.map { t ->
                TrackSnap(t.specifier, t.language, t.codec, t.title, t.default, kindStr)
            }
            val afterSnaps = sameType.map { t ->
                TrackSnap(t.specifier, t.language, t.codec, t.title, t.streamIndex == targetTrack.streamIndex, kindStr)
            }

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
                        before = beforeSnaps,
                        after = afterSnaps,
                    )
                )
            } else {
                call.respond(
                    TrackPlan(
                        command = FfmpegRunner.planSetDefault(item.path, targetTrack.streamIndex, sameType.map { it.streamIndex }, targetTrack.kind),
                        tool = "ffmpeg",
                        estimatedMs = 5000,
                        targetSpecifier = specifier,
                        before = beforeSnaps,
                        after = afterSnaps,
                    )
                )
            }
        }

        // POST /api/media/{id}/tracks/default — set a track as default for its type
        post("/tracks/default") {
            val id = call.parameters["id"]
                ?: return@post call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id)
                ?: return@post call.respond(HttpStatusCode.NotFound)

            val req = call.receive<SetDefaultRequest>()
            val targetTrack = item.tracks.firstOrNull { it.specifier == req.specifier }
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "track not found"))

            val ext = item.path.substringAfterLast('.').lowercase()
            val sameType = item.tracks.filter { it.kind == targetTrack.kind }

            when (val guard = seedingGuard.check(item.path, configStore.current)) {
                is SeedingCheckResult.Blocked -> { call.respond(HttpStatusCode.Conflict, mapOf("error" to "File is seeded by '${guard.torrentName}'")); return@post }
                is SeedingCheckResult.Unreachable -> { call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "qBittorrent unreachable: ${guard.reason}")); return@post }
                else -> Unit
            }

            val ok = if (ext == "mkv") {
                MkvpropeditRunner.setDefault(item.path, targetTrack.streamIndex, sameType.map { it.streamIndex })
            } else {
                FfmpegRunner.setDefault(item.path, targetTrack.streamIndex, sameType.map { it.streamIndex }, targetTrack.kind)
            }

            if (!ok) {
                val tool = if (ext == "mkv") "mkvpropedit" else "ffmpeg"
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "$tool failed"))
                val cfg = configStore.current
                if (cfg.behavior.notifyOnWriteFailed) fireWebhook(cfg, """{"event":"write_failed","mediaId":"$id","tool":"$tool","action":"set_default"}""")
                return@post
            }

            val newTracks = FfprobeRunner.probe(item.path)
            val newIssueCount = newTracks.count {
                (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null
            }
            val updated = item.copy(tracks = newTracks, issueCount = newIssueCount)
            store.updateOne(updated)
            mediaHistory.record(id, "set_default", "specifier=${req.specifier}")

            val cfg = configStore.current
            if (!item.jellyfinId.isNullOrBlank() && cfg.apiKeys.jellyfinUrl.isNotBlank()) {
                jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, item.jellyfinId)
            }

            call.respond(mapOf("ok" to true))
        }

        // POST /api/media/{id}/tracks/forced — toggle forced flag on a subtitle track (MKV only)
        post("/tracks/forced") {
            val id = call.parameters["id"]
                ?: return@post call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id)
                ?: return@post call.respond(HttpStatusCode.NotFound)

            @Serializable data class SetForcedRequest(val specifier: String, val forced: Boolean)
            val req = call.receive<SetForcedRequest>()
            val targetTrack = item.tracks.firstOrNull { it.specifier == req.specifier }
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "track not found"))
            if (targetTrack.kind != TrackKind.SUBTITLE) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "forced flag only applies to subtitle tracks"))
                return@post
            }
            val ext = item.path.substringAfterLast('.').lowercase()
            if (ext != "mkv") {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "forced flag editing requires MKV container"))
                return@post
            }

            when (val guard = seedingGuard.check(item.path, configStore.current)) {
                is SeedingCheckResult.Blocked -> { call.respond(HttpStatusCode.Conflict, mapOf("error" to "File is seeded by '${guard.torrentName}'")); return@post }
                is SeedingCheckResult.Unreachable -> { call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "qBittorrent unreachable: ${guard.reason}")); return@post }
                else -> Unit
            }

            val sameType = item.tracks.filter { it.kind == TrackKind.SUBTITLE }
            val forcedIdx = if (req.forced) targetTrack.streamIndex else -1
            val ok = MkvpropeditRunner.setForced(item.path, forcedIdx, sameType.map { it.streamIndex })
            if (!ok) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "mkvpropedit failed"))
                return@post
            }
            val newTracks = FfprobeRunner.probe(item.path)
            val newIssueCount = newTracks.count { (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null }
            val updated = item.copy(tracks = newTracks, issueCount = newIssueCount)
            store.updateOne(updated)
            mediaHistory.record(id, "set_forced", "specifier=${req.specifier} forced=${req.forced}")
            call.respond(mapOf("ok" to true))
        }

        // POST /api/media/{id}/tracks/language — write a language tag to a single track
        post("/tracks/language") {
            val id = call.parameters["id"]
                ?: return@post call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id)
                ?: return@post call.respond(HttpStatusCode.NotFound)

            val req = call.receive<SetLanguageRequest>()
            if (!req.language.matches(Regex("[a-zA-Z]{2,8}(-[a-zA-Z0-9]{2,8})*"))) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid language code"))
                return@post
            }

            val targetTrack = item.tracks.firstOrNull { it.specifier == req.specifier }
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "track not found"))

            val ext = item.path.substringAfterLast('.').lowercase()

            when (val guard = seedingGuard.check(item.path, configStore.current)) {
                is SeedingCheckResult.Blocked -> { call.respond(HttpStatusCode.Conflict, mapOf("error" to "File is seeded by '${guard.torrentName}'")); return@post }
                is SeedingCheckResult.Unreachable -> { call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "qBittorrent unreachable: ${guard.reason}")); return@post }
                else -> Unit
            }

            val ok = if (ext == "mkv") {
                MkvpropeditRunner.setLanguage(item.path, targetTrack.streamIndex, req.language)
            } else {
                FfmpegRunner.setLanguage(item.path, targetTrack.streamIndex, req.language)
            }

            if (!ok) {
                val tool = if (ext == "mkv") "mkvpropedit" else "ffmpeg"
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "$tool failed"))
                val cfg = configStore.current
                if (cfg.behavior.notifyOnWriteFailed) fireWebhook(cfg, """{"event":"write_failed","mediaId":"$id","tool":"$tool","action":"set_language"}""")
                return@post
            }

            val newTracks = FfprobeRunner.probe(item.path)
            val newIssueCount = newTracks.count {
                (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null
            }
            store.updateOne(item.copy(tracks = newTracks, issueCount = newIssueCount))
            mediaHistory.record(id, "set_language", "specifier=${req.specifier} language=${req.language}")

            val cfg = configStore.current
            if (!item.jellyfinId.isNullOrBlank() && cfg.apiKeys.jellyfinUrl.isNotBlank()) {
                jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, item.jellyfinId)
            }

            call.respond(mapOf("ok" to true))
        }

        // DELETE /api/media/{id}/tracks/{specifier} — remove a track from the file (ffmpeg remux)
        delete("/tracks/{specifier}") {
            val id = call.parameters["id"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val specifier = call.parameters["specifier"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id)
                ?: return@delete call.respond(HttpStatusCode.NotFound)
            val targetTrack = item.tracks.firstOrNull { it.specifier == specifier }
                ?: return@delete call.respond(HttpStatusCode.NotFound, mapOf("error" to "track not found"))

            val ok = FfmpegRunner.removeTrack(item.path, targetTrack.streamIndex)
            if (!ok) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "ffmpeg failed"))
                return@delete
            }

            val newTracks = FfprobeRunner.probe(item.path)
            val newIssueCount = newTracks.count {
                (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null
            }
            store.updateOne(item.copy(tracks = newTracks, issueCount = newIssueCount))
            mediaHistory.record(id, "remove_track", "specifier=$specifier")

            val cfg = configStore.current
            if (!item.jellyfinId.isNullOrBlank() && cfg.apiKeys.jellyfinUrl.isNotBlank()) {
                jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, item.jellyfinId)
            }
            call.respond(mapOf("ok" to true))
        }

        // POST /api/media/{id}/tracks/reorder — reorder tracks of a given type (ffmpeg remux)
        post("/tracks/reorder") {
            val id = call.parameters["id"]
                ?: return@post call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id)
                ?: return@post call.respond(HttpStatusCode.NotFound)

            @Serializable data class ReorderRequest(val kind: String, val order: List<String>)
            val req = call.receive<ReorderRequest>()
            val kind = when (req.kind.lowercase()) {
                "audio" -> TrackKind.AUDIO
                "subtitle" -> TrackKind.SUBTITLE
                else -> return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "kind must be audio or subtitle"))
            }
            val orderedTracks = req.order.mapNotNull { spec -> item.tracks.firstOrNull { it.specifier == spec } }
            if (orderedTracks.size != req.order.size) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "one or more specifiers not found"))
                return@post
            }
            val orderedIndices = orderedTracks.map { it.streamIndex }

            val ok = FfmpegRunner.reorderTracks(item.path, kind, orderedIndices)
            if (!ok) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "ffmpeg remux failed"))
                return@post
            }

            val newTracks = FfprobeRunner.probe(item.path)
            val newIssueCount = newTracks.count {
                (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null
            }
            store.updateOne(item.copy(tracks = newTracks, issueCount = newIssueCount))
            mediaHistory.record(id, "reorder_tracks", "kind=${req.kind} order=${req.order.joinToString(",")}")

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
            val item = store.resolve(id)
            val config = configStore.current
            if (config.apiKeys.jellyfinUrl.isBlank() || config.apiKeys.jellyfinToken.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Jellyfin not configured"))
                return@post
            }
            val jellyfinId = item?.jellyfinId
            val ok = if (!jellyfinId.isNullOrBlank()) {
                jellyfinClient.refreshItem(config.apiKeys.jellyfinUrl, config.apiKeys.jellyfinToken, jellyfinId, full = true)
            } else {
                jellyfinClient.triggerLibraryRefresh(config.apiKeys.jellyfinUrl, config.apiKeys.jellyfinToken)
            }
            // For TV shows, also trigger a library scan so Jellyfin reliably re-reads tvshow.nfo.
            // Per-item FullRefresh alone does not consistently pick up tvshow.nfo changes.
            if (item?.kind == MediaKind.TV_SHOW) {
                jellyfinClient.triggerLibraryRefresh(config.apiKeys.jellyfinUrl, config.apiKeys.jellyfinToken)
            }
            if (ok) call.respond(mapOf("ok" to true))
            else call.respond(HttpStatusCode.BadGateway, mapOf("error" to "Jellyfin refresh failed"))
        }
    }
}
