package dev.jellystructure.server.routes

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.jobs.JobEvent
import dev.jellystructure.jobs.WsBroadcaster
import dev.jellystructure.log.Logger
import dev.jellystructure.media.FfmpegRunner
import dev.jellystructure.media.FfprobeRunner
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.MkvpropeditRunner
import dev.jellystructure.resolver.LanguageResolver
import dev.jellystructure.resolver.primaryAudioLanguage
import dev.jellystructure.media.ProbeDiagnosis
import dev.jellystructure.arr.ArrRescanService
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.Track
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
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import platform.posix.fgets
import platform.posix.pclose
import platform.posix.popen

@Serializable
private data class SetDefaultRequest(val specifier: String)

@Serializable
private data class SetLanguageRequest(val specifier: String, val language: String)

/** Response for a track-language write — reports the re-probed (on-disk) language, not the request. */
@Serializable
data class LangWriteResponse(val ok: Boolean = true, val language: String? = null)

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

// Phase 128
@Serializable
data class ReacquireResponse(val managed: Boolean, val ok: Boolean, val detail: String)

// Phase 96 — bulk-reorder DTOs
@Serializable
data class BulkTrackSummary(
    val specifier: String,
    val language: String?,
    val title: String?,
    val isDefault: Boolean,
    val isStray: Boolean,
)

@Serializable
data class BulkPlanEpisode(
    val filename: String,
    val code: String,
    val title: String?,
    val status: String, // will_reorder | already_correct | partial | needs_review | nothing_to_do
    val currentOrder: List<BulkTrackSummary>,
    val proposedOrder: List<BulkTrackSummary>,
    val reason: String,
    val estSeconds: Double,
    val remux: Boolean,
)

@Serializable
data class BulkPlanResponse(
    val episodes: List<BulkPlanEpisode>,
    val scopeCount: Int,
    val willReorder: Int,
    val alreadyCorrect: Int,
    val partial: Int,
    val needsReview: Int,
    val nothingToDo: Int,
    val totalEstSeconds: Double,
    val remuxCount: Int,
)

@OptIn(ExperimentalForeignApi::class)
fun Route.trackRoutes(
    store: MediaStore,
    configStore: ConfigStore,
    jellyfinClient: JellyfinClient,
    mediaHistory: MediaHistory,
    seedingGuard: SeedingGuard,
    arrRescan: ArrRescanService? = null,
    appScope: CoroutineScope,
    broadcaster: WsBroadcaster,
    mediaJobQueue: dev.jellystructure.media.MediaJobQueue,
) {
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
            val sameType = item.tracks.filter { it.kind == targetTrack.kind && !it.external }

            val kindStr = targetTrack.kind.name.lowercase()
            val beforeSnaps = sameType.map { t ->
                TrackSnap(t.specifier, t.language, t.codec, t.title, t.default, kindStr)
            }
            val afterSnaps = sameType.map { t ->
                TrackSnap(t.specifier, t.language, t.codec, t.title, t.streamIndex == targetTrack.streamIndex, kindStr)
            }

            if (ext == "mkv") {
                val escaped = item.path.replace("'", "'\\''")
                val parts = sameType.joinToString(" \\\n  ") { t ->
                    val flag = if (t.streamIndex == targetTrack.streamIndex) 1 else 0
                    "--edit track:@${t.streamIndex + 1} --set flag-default=$flag"
                }
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

        // Phase 128 — GET /api/media/{id}/tracks/diagnose: why did this file's track probe come back
        // the way it did (corrupt/unreadable/no-audio/etc), plus whether a configured Sonarr/Radarr
        // manages it (drives the empty-state card's Re-acquire vs. manual-repair guidance).
        get("/tracks/diagnose") {
            val id = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val diagnosis = FfprobeRunner.diagnose(item.path).copy(managed = arrRescan?.isManaged(item) ?: false)
            call.respond(diagnosis)
        }

        // Phase 128 — POST /api/media/{id}/tracks/reprobe: re-run ffprobe and store the fresh tracks +
        // issueCount + resolvedLanguage (honest — null if still no audio), so a manually-replaced file
        // picks up its real tracks without a full library scan.
        post("/tracks/reprobe") {
            val id = call.parameters["id"]
                ?: return@post call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id)
                ?: return@post call.respond(HttpStatusCode.NotFound)
            val newTracks = FfprobeRunner.probe(item.path)
            val newIssueCount = newTracks.count {
                (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null
            }
            val hasAudio = newTracks.any { it.kind == TrackKind.AUDIO }
            val updated = item.copy(
                tracks = newTracks,
                issueCount = newIssueCount,
                resolvedLanguage = primaryAudioLanguage(configStore.current, item.path, newTracks).takeIf { hasAudio },
            )
            store.updateOne(updated)
            mediaHistory.record(id, "tracks_reprobe", "streams=${newTracks.size} hasAudio=$hasAudio")
            call.respond(newTracks)
        }

        // Phase 128 — POST /api/media/{id}/reacquire: managed-only best-effort Sonarr/Radarr rescan
        // trigger for the empty-state card's repair action — reuses ArrRescanService, but synchronous
        // and reporting back (unlike the fire-and-forget nudge() used after Jellystructure's own writes)
        // so the UI can tell the difference between "queued" and "not managed, do it manually".
        post("/reacquire") {
            val id = call.parameters["id"]
                ?: return@post call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id)
                ?: return@post call.respond(HttpStatusCode.NotFound)
            val (managed, ok, detail) = arrRescan?.reacquire(item) ?: Triple(false, false, "No *arr configured")
            call.respond(ReacquireResponse(managed, ok, detail))
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
            // Phase 200 — a sidecar subtitle isn't part of the container; there is no flag to set.
            if (targetTrack.external) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "cannot edit an external subtitle track"))

            val ext = item.path.substringAfterLast('.').lowercase()
            val sameType = item.tracks.filter { it.kind == targetTrack.kind && !it.external }

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
                // Bug fix (live report, 2026-08-16) — full=true; see MediaJobQueue.postWriteSync's doc
                // comment for why a stream-metadata edit can't rely on ValidationOnly's recency skip.
                jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, item.jellyfinId, full = true)
            }
            arrRescan?.nudge(item)  // Phase 54 — refresh the *arr's MediaInfo after a track edit (best-effort)

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
            if (targetTrack.external) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "cannot edit an external subtitle track"))
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
            // Bug fix (live report, 2026-08-16) — this route edited the file but, unlike every other
            // track-editing route on this page, never told Jellyfin to re-read it at all.
            val cfg = configStore.current
            if (!item.jellyfinId.isNullOrBlank() && cfg.apiKeys.jellyfinUrl.isNotBlank()) {
                jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, item.jellyfinId, full = true)
            }
            arrRescan?.nudge(item)  // Phase 54 — refresh the *arr's MediaInfo after a track edit (best-effort)
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
            if (LanguageResolver.toIso6392(req.language) == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no ISO-639-2 mapping for '${req.language}'"))
                return@post
            }

            val targetTrack = item.tracks.firstOrNull { it.specifier == req.specifier }
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "track not found"))
            if (targetTrack.external) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "cannot edit an external subtitle track"))

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

            // Re-probe and store disk truth, then verify the tag actually persisted (ffmpeg can exit 0
            // having written `und`). Compare via normalize() so the check is ISO 639-2 B/T-agnostic.
            val newTracks = FfprobeRunner.probe(item.path)
            val newIssueCount = newTracks.count {
                (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null
            }
            store.updateOne(item.copy(tracks = newTracks, issueCount = newIssueCount))

            val probed = newTracks.firstOrNull { it.specifier == req.specifier }?.language
            val persisted = probed != null && LanguageResolver.normalize(probed) == LanguageResolver.normalize(req.language)
            if (!persisted) {
                mediaHistory.record(id, "set_language", "specifier=${req.specifier} FAILED to persist (on disk: ${probed ?: "none"})")
                val cfgF = configStore.current
                if (cfgF.behavior.notifyOnWriteFailed) fireWebhook(cfgF, """{"event":"write_failed","mediaId":"$id","tool":"${if (ext == "mkv") "mkvpropedit" else "ffmpeg"}","action":"set_language"}""")
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "the language tag did not persist (file shows '${probed ?: "none"}') — the container may not support per-stream language, or the code has no ISO-639-2 mapping"))
                return@post
            }
            mediaHistory.record(id, "set_language", "specifier=${req.specifier} language=$probed")

            val cfg = configStore.current
            // Bug fix (live report, 2026-08-16) — full=true; see MediaJobQueue.postWriteSync's doc
            // comment for why a stream-metadata edit can't rely on ValidationOnly's recency skip.
            if (!item.jellyfinId.isNullOrBlank() && cfg.apiKeys.jellyfinUrl.isNotBlank()) {
                jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, item.jellyfinId, full = true)
            }
            arrRescan?.nudge(item)  // Phase 54 — refresh the *arr's MediaInfo after a track edit (best-effort)

            call.respond(LangWriteResponse(ok = true, language = probed))
        }

        // DELETE /api/media/{id}/tracks/{specifier} — Phase 109: enqueues an ffmpeg remux job (removes
        // the track) and returns immediately instead of blocking the request for the whole remux.
        delete("/tracks/{specifier}") {
            val id = call.parameters["id"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val specifier = call.parameters["specifier"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id)
                ?: return@delete call.respond(HttpStatusCode.NotFound)
            val deleteTarget = item.tracks.firstOrNull { it.specifier == specifier }
                ?: return@delete call.respond(HttpStatusCode.NotFound, mapOf("error" to "track not found"))
            if (deleteTarget.external) return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "cannot edit an external subtitle track"))

            when (val guard = seedingGuard.check(item.path, configStore.current)) {
                is SeedingCheckResult.Blocked -> { call.respond(HttpStatusCode.Conflict, mapOf("error" to "File is seeded by '${guard.torrentName}'")); return@delete }
                is SeedingCheckResult.Unreachable -> { call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "qBittorrent unreachable: ${guard.reason}")); return@delete }
                else -> Unit
            }

            val job = mediaJobQueue.enqueue("remove", id, item.title, dev.jellystructure.jobs.MediaJobParams(specifier = specifier))
            call.respond(HttpStatusCode.Accepted, mapOf("jobId" to job.id))
        }

        // POST /api/media/{id}/tracks/reorder — Phase 109: enqueues an ffmpeg remux job and returns
        // immediately (202 + job id) instead of blocking the request for the whole remux.
        post("/tracks/reorder") {
            val id = call.parameters["id"]
                ?: return@post call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id)
                ?: return@post call.respond(HttpStatusCode.NotFound)

            @Serializable data class ReorderRequest(val kind: String, val order: List<String>)
            val req = call.receive<ReorderRequest>()
            when (req.kind.lowercase()) {
                "audio" -> TrackKind.AUDIO
                "subtitle" -> TrackKind.SUBTITLE
                else -> return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "kind must be audio or subtitle"))
            }
            val orderedTracks = req.order.mapNotNull { spec -> item.tracks.firstOrNull { it.specifier == spec } }
            if (orderedTracks.size != req.order.size) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "one or more specifiers not found"))
                return@post
            }
            if (orderedTracks.any { it.external }) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "cannot reorder an external subtitle track"))
                return@post
            }

            // Fail-fast guard at enqueue time (Phase 109 FR A.3) — the job re-checks at start too,
            // since the seeding state can change while it waits in the queue.
            when (val guard = seedingGuard.check(item.path, configStore.current)) {
                is SeedingCheckResult.Blocked -> { call.respond(HttpStatusCode.Conflict, mapOf("error" to "File is seeded by '${guard.torrentName}'")); return@post }
                is SeedingCheckResult.Unreachable -> { call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "qBittorrent unreachable: ${guard.reason}")); return@post }
                else -> Unit
            }

            val job = mediaJobQueue.enqueue(
                "reorder", id, item.title,
                dev.jellystructure.jobs.MediaJobParams(kind = req.kind.lowercase(), order = req.order),
            )
            call.respond(HttpStatusCode.Accepted, mapOf("jobId" to job.id))
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
            // Phase 193 (FR-193-1) — this is exactly the button the "Jellyfin hasn't re-read the NFO
            // yet" banner offers; without this the fix action didn't fix the banner's own state.
            if (ok && item != null) {
                store.updateOne((store.get(id) ?: item).copy(jfSyncedAt = dev.jellystructure.nowEpochSec()))
            } else if (!ok) {
                dev.jellystructure.log.Logger.warn("jellyfin-refresh: failed for '$id'")
                if (item != null) mediaHistory.record(id, "jellyfin_refresh_failed", "triggered by Sync Jellyfin banner action")
            }
            if (ok) call.respond(mapOf("ok" to true))
            else call.respond(HttpStatusCode.BadGateway, mapOf("error" to "Jellyfin refresh failed"))
        }

        // Phase 96: POST /api/media/{id}/tracks/bulk-reorder/plan — classify episodes (dry-run)
        post("/tracks/bulk-reorder/plan") {
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id) ?: return@post call.respond(HttpStatusCode.NotFound)
            if (item.kind != MediaKind.TV_SHOW)
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bulk reorder is only for TV shows"))

            @Serializable data class BulkPlanReq(val kind: String, val scope: String, val order: List<String>, val setDefault: Boolean = false)
            val req = call.receive<BulkPlanReq>()
            val kind = parseBulkKind(req.kind)
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "kind must be audio or subtitle"))
            val episodes = episodesInScope(item.episodes, req.scope)
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "scope must be 'series' or 'season-N'"))

            call.respond(classifyEpisodes(episodes, kind, req.order, req.setDefault))
        }

        // Phase 96: POST /api/media/{id}/tracks/bulk-reorder — apply as background job
        post("/tracks/bulk-reorder") {
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id) ?: return@post call.respond(HttpStatusCode.NotFound)
            if (item.kind != MediaKind.TV_SHOW)
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bulk reorder is only for TV shows"))

            @Serializable data class BulkApplyReq(val kind: String, val scope: String, val order: List<String>, val setDefault: Boolean = false, val optIn: List<String> = emptyList())
            val req = call.receive<BulkApplyReq>()
            val kind = parseBulkKind(req.kind)
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "kind must be audio or subtitle"))
            val episodes = episodesInScope(item.episodes, req.scope)
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "scope must be 'series' or 'season-N'"))

            val plan = classifyEpisodes(episodes, kind, req.order, req.setDefault)
            val toReorder = plan.episodes.filter { ep ->
                ep.status == "will_reorder" || (ep.status == "partial" && ep.filename in req.optIn)
            }
            val toFlagFix = if (req.setDefault) plan.episodes.filter { ep ->
                ep.status == "already_correct" &&
                    item.episodes.firstOrNull { it.filename == ep.filename }
                        ?.tracks?.filter { it.kind == kind }
                        ?.let { ts -> ts.isNotEmpty() && ts.minByOrNull { it.streamIndex }?.default == false } == true
            } else emptyList()

            val total = toReorder.size + toFlagFix.size
            // Phase 109: registers a media_job row (Jobs page visibility) and takes the same
            // single-worker slot a queued reorder/remove job would — a bulk run and a concurrent single
            // reorder can never remux in parallel. The per-episode work below is unchanged (Phase 96);
            // only the outer scheduling is new.
            val jobRow = mediaJobQueue.registerBulkJob(
                id, "${item.title} — bulk ${req.kind} reorder (${total} episode${if (total != 1) "s" else ""})",
                dev.jellystructure.jobs.MediaJobParams(kind = req.kind, order = req.order, bulkScope = req.scope, bulkOptIn = req.optIn, bulkSetDefault = req.setDefault),
                total,
            )
            val jobId = jobRow.id
            call.respond(mapOf("jobId" to jobId))

            appScope.launch {
                mediaJobQueue.acquireBulkSlot(jobId)
                var succeeded = 0
                var failed = 0
                try {
                broadcaster.broadcast(JobEvent.Started(jobId, total))

                for (epPlan in toReorder) {
                    val ep = store.resolve(id)?.episodes?.firstOrNull { it.filename == epPlan.filename } ?: continue
                    val targetLangs = req.order.map { it.lowercase() }
                    val tracksOfKind = ep.tracks.filter { it.kind == kind && !it.external }
                    val ordered = computeProposedTracks(tracksOfKind, targetLangs, partial = epPlan.status == "partial")
                    val orderedIndices = ordered.map { it.streamIndex }

                    when (val guard = seedingGuard.check(ep.path, configStore.current)) {
                        is SeedingCheckResult.Blocked -> {
                            broadcaster.broadcast(JobEvent.FileDone(jobId, epPlan.code, false, "Seeded by '${guard.torrentName}'"))
                            failed++; continue
                        }
                        is SeedingCheckResult.Unreachable -> {
                            broadcaster.broadcast(JobEvent.FileDone(jobId, epPlan.code, false, "qBittorrent unreachable"))
                            failed++; continue
                        }
                        else -> Unit
                    }

                    // Phase 201 (FR-201-8): run setDefault BEFORE the reorder remux, not after — an MKV
                    // mkvpropedit edit can grow the Tracks element past its slot and evict it to EOF,
                    // reachable only via SeekHead; ExoPlayer reads the HTTP body linearly and never
                    // builds a renderer. Putting the ffmpeg remux (which already carries -cues_to_front
                    // and therefore lays Tracks out correctly) LAST means the common path never reaches
                    // FR-201-3's repair at all. The track that will become first-of-kind after the
                    // reorder is known up front — it's simply `orderedIndices.first()` — so the default
                    // flag can be set on the pre-reorder file by its current (pre-reorder) stream index,
                    // and ffmpeg's `-c copy` remux carries dispositions forward per-stream regardless of
                    // where the track ends up in the new order.
                    if (req.setDefault) {
                        val desiredDefaultIndex = orderedIndices.firstOrNull()
                        val currentlyDefault = tracksOfKind.firstOrNull { it.streamIndex == desiredDefaultIndex }?.default == true
                        if (desiredDefaultIndex != null && !currentlyDefault) {
                            val sameKindIndices = tracksOfKind.map { it.streamIndex }
                            val ext = ep.path.substringAfterLast('.').lowercase()
                            if (ext == "mkv") MkvpropeditRunner.setDefault(ep.path, desiredDefaultIndex, sameKindIndices)
                            else FfmpegRunner.setDefault(ep.path, desiredDefaultIndex, sameKindIndices, kind)
                        }
                    }

                    val ok = FfmpegRunner.reorderTracks(ep.path, kind, orderedIndices)
                    if (!ok) {
                        broadcaster.broadcast(JobEvent.FileDone(jobId, epPlan.code, false, "ffmpeg remux failed"))
                        failed++; continue
                    }

                    val newTracks = FfprobeRunner.probe(ep.path)
                    val latestItem = store.resolve(id)
                    if (latestItem != null) {
                        // Bug fix (dev-review addendum §6, Phase 149): the edit (mkvpropedit/ffmpeg) just
                        // ran on the shared PHYSICAL FILE, so it affects every episode contained in it —
                        // this used to refresh only the first Episode matched by filename, leaving
                        // sibling episodes in a multi-episode group with stale cached tracks until a full
                        // rescan. Refresh every episode sharing this filename, not just one.
                        val newIssue = newTracks.count { (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null }
                        val epResolved = if (kind == TrackKind.AUDIO) primaryAudioLanguage(configStore.current, ep.path, newTracks) else ep.resolvedLanguage
                        val updatedEps = latestItem.episodes.map { e ->
                            if (e.filename == ep.filename) e.copy(tracks = newTracks, issueCount = newIssue, resolvedLanguage = epResolved) else e
                        }
                        store.updateOne(latestItem.copy(episodes = updatedEps))
                    }

                    store.resolve(id)?.let { broadcaster.broadcast(JobEvent.ItemScanned(jobId, it)) }
                    broadcaster.broadcast(JobEvent.FileDone(jobId, epPlan.code, true, null))
                    succeeded++
                }

                for (epPlan in toFlagFix) {
                    val ep = store.resolve(id)?.episodes?.firstOrNull { it.filename == epPlan.filename } ?: continue
                    val tracksOfKind = ep.tracks.filter { it.kind == kind && !it.external }
                    val firstOfKind = tracksOfKind.minByOrNull { it.streamIndex } ?: continue
                    val sameKind = tracksOfKind.map { it.streamIndex }

                    when (val guard = seedingGuard.check(ep.path, configStore.current)) {
                        is SeedingCheckResult.Blocked -> { broadcaster.broadcast(JobEvent.FileDone(jobId, epPlan.code, false, "Seeded")); failed++; continue }
                        is SeedingCheckResult.Unreachable -> { broadcaster.broadcast(JobEvent.FileDone(jobId, epPlan.code, false, "qBittorrent unreachable")); failed++; continue }
                        else -> Unit
                    }

                    val ext = ep.path.substringAfterLast('.').lowercase()
                    val ok = if (ext == "mkv") MkvpropeditRunner.setDefault(ep.path, firstOfKind.streamIndex, sameKind)
                             else FfmpegRunner.setDefault(ep.path, firstOfKind.streamIndex, sameKind, kind)
                    broadcaster.broadcast(JobEvent.FileDone(jobId, epPlan.code, ok, if (!ok) "set-default failed" else null))
                    if (ok) succeeded++ else failed++
                }

                val finalItem = store.resolve(id)
                if (finalItem != null && kind == TrackKind.AUDIO) {
                    val votes = mutableMapOf<String, Int>()
                    for (e in finalItem.episodes) {
                        e.tracks.firstOrNull { it.kind == TrackKind.AUDIO }?.language
                            ?.let { LanguageResolver.normalize(it) }
                            ?.let { lang -> votes[lang] = (votes[lang] ?: 0) + 1 }
                    }
                    val newResolved = votes.maxByOrNull { it.value }?.key
                    if (newResolved != null && newResolved != finalItem.resolvedLanguage)
                        store.updateOne(finalItem.copy(resolvedLanguage = newResolved))
                }
                mediaHistory.record(id, "bulk_reorder_tracks", "kind=${req.kind} scope=${req.scope} succeeded=$succeeded failed=$failed")
                val cfg = configStore.current
                // Bug fix (live report, 2026-08-16) — full=true; this reported "succeeded" for every
                // remuxed episode, but the auto-refresh below used ValidationOnly (default), which
                // "skips the re-read if the item was recently refreshed" (JellyfinClient.refreshItem's
                // own doc) — the reordered stream layout could stay uncached until an unrelated later
                // full sync caught up. See MediaJobQueue.postWriteSync's doc comment for the full story.
                if (!item.jellyfinId.isNullOrBlank() && cfg.apiKeys.jellyfinUrl.isNotBlank())
                    jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, item.jellyfinId, full = true)

                broadcaster.broadcast(JobEvent.Finished(jobId, succeeded, failed))
                mediaJobQueue.markBulkFinished(jobId, ok = failed == 0, error = if (failed > 0) "$failed of $total episode(s) failed" else null, filesDone = succeeded)
                } catch (e: Exception) {
                    // Phase 109: a worker-loop-style safety net — an exception anywhere in the bulk run
                    // must still release the single-worker slot, or every later reorder/remove job (and
                    // any other bulk run) would wait on it forever.
                    Logger.error("bulk reorder job $jobId threw: ${e.message}", "jobs")
                    mediaJobQueue.markBulkFinished(jobId, ok = false, error = e.message ?: "unexpected error", filesDone = succeeded)
                }
            }
        }
    }

    // Phase 200 (FR-200-6) — the guard on the sidecar-discovery decision: sweep the library comparing
    // our own per-title subtitle language set against Jellyfin's own MediaStreams (one Jellyfin round
    // trip per item with a jellyfinId — operator-triggered, not part of the automatic /health/full
    // poll, the same reasoning as the MKV layout sweep below). Report only; a divergence is a parser
    // bug to fix, never a value to silently correct.
    get("/media/health/subtitle-reconciliation") {
        val cfg = configStore.current
        if (cfg.apiKeys.jellyfinUrl.isBlank() || cfg.apiKeys.jellyfinToken.isBlank()) {
            return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Jellyfin not configured"))
        }
        // Movies and music videos only — item.tracks IS the file's own track list for those kinds. A
        // series' tracks live per-episode; extending this to every episode would multiply the Jellyfin
        // round trips by episode count for comparatively little of the 260-movie finding this exists to
        // guard. Scoped deliberately, not an oversight.
        val items = store.allItems().filter { !it.jellyfinId.isNullOrBlank() && it.kind != MediaKind.TV_SHOW }
        val divergences = items.mapNotNull { item ->
            val jfId = item.jellyfinId ?: return@mapNotNull null
            val streams = jellyfinClient.getItemMediaStreams(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, jfId)?.mediaStreams
                ?: return@mapNotNull null
            val ourSubLangs = item.tracks.filter { it.kind == TrackKind.SUBTITLE }.mapNotNull { it.language }
            dev.jellystructure.media.SubtitleReconciliation.compare(jfId, ourSubLangs, streams).takeUnless { it.agrees }
        }
        call.respond(mapOf("scanned" to items.size, "diverged" to divergences.size, "divergences" to divergences))
    }

    // Phase 201 (FR-201-6) — a read-only Tracks/Cluster layout sweep the operator can run and read,
    // surfaced on the Activity page's health reporting. Never auto-repairs: a repair is a write to a
    // media file, and this route is a report. Cheap — it stops at the first Cluster of each file and
    // never reads a payload; the full production library swept well under a minute.
    get("/media/health/mkv-layout") {
        call.respond(dev.jellystructure.media.MkvLayoutAudit.sweep(store.allItems()))
    }

    // Phase 201 amendment (2026-09-13, FR-201-11) — the media detail page's own Fix banner: a live
    // check of just THIS item's file(s), never the whole-library sweep above. Cheap (1 file for a
    // movie, N for a series) and always fresh — no cache, unlike the Dashboard/Library integration's
    // MkvHealthCache, which exists specifically to avoid this cost at library scale.
    get("/media/{mediaId}/health/mkv-layout") {
        val mediaId = call.parameters["mediaId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val item = store.resolve(mediaId) ?: return@get call.respond(HttpStatusCode.NotFound)
        val broken = dev.jellystructure.media.MkvLayoutAudit.sweep(listOf(item)).tracksAfterClusters
        call.respond(mapOf("brokenPaths" to broken))
    }

    // Phase 201 (FR-201-5) — repair a specific, operator-chosen set of files: normally exactly the
    // `tracksAfterClusters` list a prior sweep reported. An explicit action on the media library, not
    // something a scan may trigger on its own initiative (Phase 188 is the standing reminder why).
    //
    // Phase 201 amendment (2026-09-13) — this used to run every file inline on the request thread and
    // block until all were done. The first real production click (85 episodes) took minutes with no
    // feedback, read as broken, and got re-clicked/reloaded — each attempt ran a fresh, fully-overlapping
    // repair of the same files (see MkvLayoutAudit.repair's doc for the race that caused). It now enqueues
    // through the existing Phase 109 media job queue instead: 202 + jobId, visible with real progress on
    // Activity ▸ Jobs, and the queue's single-worker FIFO is what actually rules out the concurrent-remux
    // race (a second "Fix now" click just enqueues a second job behind the first, never runs alongside it).
    post("/media/health/mkv-layout/repair") {
        @Serializable data class MkvRepairReq(val mediaId: String, val paths: List<String>)
        val req = call.receive<MkvRepairReq>()
        if (req.paths.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "paths required"))
        val item = store.resolve(req.mediaId) ?: return@post call.respond(HttpStatusCode.NotFound)
        val job = mediaJobQueue.enqueue(
            "mkv_layout_repair", item.id, "Fix now: ${req.paths.size} file${if (req.paths.size != 1) "s" else ""}",
            dev.jellystructure.jobs.MediaJobParams(repairPaths = req.paths), fileCount = req.paths.size,
        )
        call.respond(HttpStatusCode.Accepted, mapOf("jobId" to job.id))
    }
}

// ── Phase 96 helpers ─────────────────────────────────────────────────────────

private fun parseBulkKind(s: String): TrackKind? = when (s.lowercase()) {
    "audio" -> TrackKind.AUDIO
    "subtitle" -> TrackKind.SUBTITLE
    else -> null
}

private fun episodesInScope(episodes: List<Episode>, scope: String): List<Episode>? = when {
    scope == "series" -> episodes
    scope.startsWith("season-") -> {
        val n = scope.removePrefix("season-").toIntOrNull() ?: return null
        episodes.filter { it.seasonNumber == n }
    }
    else -> null
}

private fun classifyEpisodes(episodes: List<Episode>, kind: TrackKind, order: List<String>, setDefault: Boolean): BulkPlanResponse {
    val targetLangs = order.map { it.lowercase() }
    val targetSet = targetLangs.toSet()
    val classified = episodes.map { ep -> classifyEpisode(ep, kind, targetLangs, targetSet, setDefault) }
    return BulkPlanResponse(
        episodes = classified,
        scopeCount = episodes.size,
        willReorder = classified.count { it.status == "will_reorder" },
        alreadyCorrect = classified.count { it.status == "already_correct" },
        partial = classified.count { it.status == "partial" },
        needsReview = classified.count { it.status == "needs_review" },
        nothingToDo = classified.count { it.status == "nothing_to_do" },
        totalEstSeconds = classified.sumOf { it.estSeconds },
        remuxCount = classified.count { it.remux },
    )
}

private fun classifyEpisode(ep: Episode, kind: TrackKind, targetLangs: List<String>, targetSet: Set<String>, setDefault: Boolean): BulkPlanEpisode {
    val code = buildBulkEpCode(ep)
    val tracks = ep.tracks.filter { it.kind == kind && !it.external }

    if (tracks.size <= 1) {
        val summary = tracks.toSummary(targetSet)
        return BulkPlanEpisode(ep.filename, code, ep.title, "nothing_to_do", summary, summary, "≤1 track of this kind", 0.0, false)
    }

    val hasUntagged = tracks.any { it.language == null }
    val present = tracks.mapNotNull { it.language?.lowercase() }.toSet()
    val strays = present - targetSet
    val missing = targetSet - present

    return when {
        hasUntagged || strays.isNotEmpty() -> {
            val reason = buildString {
                if (hasUntagged) append("has untagged track(s)")
                if (hasUntagged && strays.isNotEmpty()) append("; ")
                if (strays.isNotEmpty()) append("stray language(s): ${strays.sorted().joinToString()}")
            }
            val summary = tracks.sortedBy { it.streamIndex }.toSummary(targetSet)
            BulkPlanEpisode(ep.filename, code, ep.title, "needs_review", summary, summary, reason, 0.0, false)
        }
        missing.isNotEmpty() -> {
            // Episode has a subset of the target languages. Reorder the present tracks by their
            // priority in the target list — the missing languages just won't appear.
            val proposed = computeProposedTracks(tracks, targetLangs, partial = true)
            val currentLangs = tracks.sortedBy { it.streamIndex }.mapNotNull { it.language?.lowercase() }
            val proposedLangs = proposed.mapNotNull { it.language?.lowercase() }
            val currentSummary = tracks.sortedBy { it.streamIndex }.toSummary(targetSet)
            val proposedSummary = proposed.toSummary(targetSet)
            val firstStreamIndex = tracks.minByOrNull { it.streamIndex }?.streamIndex
            val currentDefault = tracks.firstOrNull { it.default }?.streamIndex
            val needsDefaultFix = setDefault && currentDefault != firstStreamIndex
            val missingNote = "missing: ${missing.sorted().joinToString()}"
            if (currentLangs == proposedLangs) {
                val estSec = if (needsDefaultFix) 0.05 else 0.0
                BulkPlanEpisode(ep.filename, code, ep.title, "already_correct",
                    currentSummary, proposedSummary, "already in target order ($missingNote)", estSec, false)
            } else {
                val estSec = estimateRemuxSeconds(ep.path)
                BulkPlanEpisode(ep.filename, code, ep.title, "will_reorder",
                    currentSummary, proposedSummary, "order differs from target ($missingNote)", estSec, true)
            }
        }
        else -> {
            val currentLangs = tracks.sortedBy { it.streamIndex }.mapNotNull { it.language?.lowercase() }
            val proposed = computeProposedTracks(tracks, targetLangs, partial = false)
            val currentSummary = tracks.sortedBy { it.streamIndex }.toSummary(targetSet)
            val proposedSummary = proposed.toSummary(targetSet)
            val firstStreamIndex = tracks.minByOrNull { it.streamIndex }?.streamIndex
            val currentDefault = tracks.firstOrNull { it.default }?.streamIndex
            val needsDefaultFix = setDefault && currentDefault != firstStreamIndex

            if (currentLangs == targetLangs) {
                val estSec = if (needsDefaultFix) 0.05 else 0.0
                BulkPlanEpisode(ep.filename, code, ep.title, "already_correct",
                    currentSummary, proposedSummary, "already in target order", estSec, false)
            } else {
                val estSec = estimateRemuxSeconds(ep.path)
                BulkPlanEpisode(ep.filename, code, ep.title, "will_reorder",
                    currentSummary, proposedSummary, "order differs from target", estSec, true)
            }
        }
    }
}

private fun List<Track>.toSummary(targetSet: Set<String>): List<BulkTrackSummary> =
    map { t -> BulkTrackSummary(t.specifier, t.language, t.title, t.default, t.language?.lowercase()?.let { it !in targetSet } ?: false) }

private fun computeProposedTracks(tracks: List<Track>, targetLangs: List<String>, partial: Boolean): List<Track> {
    val byLang = LinkedHashMap<String, MutableList<Track>>()
    val unmatched = mutableListOf<Track>()
    for (t in tracks.sortedBy { it.streamIndex }) {
        val key = t.language?.lowercase()
        if (key != null && key in targetLangs.toSet()) byLang.getOrPut(key) { mutableListOf() }.add(t)
        else if (!partial) unmatched.add(t)
    }
    return targetLangs.flatMap { byLang[it.lowercase()] ?: emptyList() } + unmatched
}

private fun buildBulkEpCode(ep: Episode): String {
    val s = ep.seasonNumber?.toString()?.padStart(2, '0')
    val e = ep.episodeNumber?.toString()?.padStart(2, '0')
    return if (s != null && e != null) "S${s}E${e}" else ep.filename.substringBeforeLast('.')
}

@OptIn(ExperimentalForeignApi::class)
private fun estimateRemuxSeconds(path: String): Double {
    val escaped = path.replace("'", "'\\''")
    val sizeBytes = memScoped {
        val buf = allocArray<ByteVar>(32)
        val fp = popen("stat -c '%s' '$escaped' 2>/dev/null", "r") ?: return 3.0
        val n = fgets(buf, 31, fp)?.toKString()?.trim()?.toLongOrNull() ?: 0L
        pclose(fp)
        n
    }
    return if (sizeBytes > 0) maxOf(0.5, sizeBytes.toDouble() / 400_000_000.0) else 3.0
}
