package dev.jellystructure.server.routes

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.torrent.SeedingCheckResult
import dev.jellystructure.torrent.SeedingGuard
import dev.jellystructure.media.FfmpegRunner
import dev.jellystructure.media.FfprobeRunner
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.MkvpropeditRunner
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
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
data class TriageTrack(
    val specifier: String,
    val streamIndex: Int,
    val kind: String,
    val codec: String,
    val title: String? = null,
)

@Serializable
data class CascadeMismatch(
    val resolvedLanguage: String,
    val expectedDefaultSpecifier: String,
    val actualDefaultLang: String? = null,
)

@Serializable
data class MultiDefaultIssue(val defaultSpecifiers: List<String>)

@Serializable
data class EpisodeTriageItem(
    val filename: String,
    val episodeCode: String,
    val title: String? = null,
    val untaggedTracks: List<TriageTrack>,
    val missingOverview: Boolean,
    val multiDefault: MultiDefaultIssue? = null,
)

@Serializable
data class TriageItem(
    val mediaId: String,
    val title: String,
    val year: Int?,
    val path: String,
    val kind: String = "movie",
    val posterPath: String? = null,
    val originalLanguage: String? = null,
    val untaggedTracks: List<TriageTrack>,
    val cascadeMismatch: CascadeMismatch? = null,
    val episodeIssues: List<EpisodeTriageItem> = emptyList(),
    val resolvedLanguage: String? = null,
    val languageMix: Boolean = false,
    val multiDefault: MultiDefaultIssue? = null,
)

@Serializable
data class TriageCount(val untagged: Int, val mismatch: Int, val multiDefault: Int = 0, val total: Int)

@Serializable
private data class AssignLanguageRequest(val language: String)

@Serializable
private data class AssignLanguageResponse(val ok: Boolean, val language: String)

fun Route.triageRoutes(store: MediaStore, jellyfinClient: JellyfinClient, configStore: ConfigStore, mediaHistory: MediaHistory, seedingGuard: SeedingGuard) {
    route("/triage") {
        get("/count") {
            val all = store.allItems()
            val untagged = all.sumOf { item ->
                if (item.kind == MediaKind.TV_SHOW) {
                    item.episodes.sumOf { ep ->
                        ep.tracks.count { (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null }
                    }
                } else {
                    item.tracks.count { (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null }
                }
            }
            val mismatch = all.count { it.detectCascadeMismatch() != null }
            val multiDefault = all.count { item ->
                if (item.kind == MediaKind.TV_SHOW) item.episodes.any { it.detectMultiDefaultAudio() != null }
                else item.detectMultiDefaultAudio() != null
            }
            call.respond(TriageCount(untagged = untagged, mismatch = mismatch, multiDefault = multiDefault, total = untagged + mismatch + multiDefault))
        }

        get {
            val items = store.allItems()
                .mapNotNull { it.toTriageItem() }
            call.respond(items)
        }

        get("/{mediaId}/suggest") {
            val mediaId = call.parameters["mediaId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(mediaId)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(mapOf("language" to item.originalLanguage))
        }

        post("/{mediaId}/episodes/{epFilename}/tracks/{specifier}/language") {
            val mediaId = call.parameters["mediaId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest)
            val epFilename = call.parameters["epFilename"]
                ?: return@post call.respond(HttpStatusCode.BadRequest)
            val specifier = call.parameters["specifier"]
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            val item = store.resolve(mediaId)
                ?: return@post call.respond(HttpStatusCode.NotFound)
            val ep = item.episodes.firstOrNull { it.filename == epFilename }
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "episode not found"))
            val track = ep.tracks.firstOrNull { it.specifier == specifier }
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "track not found"))

            val req = call.receive<AssignLanguageRequest>()
            val lang = req.language.trim()
            if (lang.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "language is required"))
                return@post
            }

            val ext = ep.path.substringAfterLast('.').lowercase()

            when (val guard = seedingGuard.check(ep.path, configStore.current)) {
                is SeedingCheckResult.Blocked -> { call.respond(HttpStatusCode.Conflict, mapOf("error" to "File is seeded by '${guard.torrentName}'")); return@post }
                is SeedingCheckResult.Unreachable -> { call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "qBittorrent unreachable: ${guard.reason}")); return@post }
                else -> Unit
            }

            val ok = if (ext == "mkv") MkvpropeditRunner.setLanguage(ep.path, track.streamIndex, lang)
                     else FfmpegRunner.setLanguage(ep.path, track.streamIndex, lang)

            if (!ok) {
                val tool = if (ext == "mkv") "mkvpropedit" else "ffmpeg"
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "$tool failed"))
                return@post
            }

            val newTracks = FfprobeRunner.probe(ep.path)
            val updatedEp = ep.copy(tracks = newTracks)
            val updatedEpisodes = item.episodes.map { if (it.filename == epFilename) updatedEp else it }
            val totalIssues = updatedEpisodes.sumOf { e ->
                e.tracks.count { (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null }
            }
            // Keep item.tracks in sync with the first episode's tracks so repull language resolution is correct
            val updatedItemTracks = if (updatedEpisodes.firstOrNull()?.filename == epFilename) newTracks else item.tracks
            val updatedItem = item.copy(episodes = updatedEpisodes, issueCount = totalIssues, tracks = updatedItemTracks)
            store.updateOne(updatedItem)
            mediaHistory.record(mediaId, "ep_assign_language", "ep=$epFilename specifier=$specifier lang=$lang")

            val cfg = configStore.current
            if (!item.jellyfinId.isNullOrBlank() && cfg.apiKeys.jellyfinUrl.isNotBlank() && cfg.apiKeys.jellyfinToken.isNotBlank()) {
                jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, item.jellyfinId)
            }

            call.respond(AssignLanguageResponse(ok = true, language = lang))
        }

        post("/{mediaId}/tracks/{specifier}/language") {
            val mediaId = call.parameters["mediaId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest)
            val specifier = call.parameters["specifier"]
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            val item = store.resolve(mediaId)
                ?: return@post call.respond(HttpStatusCode.NotFound)

            val track = item.tracks.firstOrNull { it.specifier == specifier }
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "track not found"))

            val req = call.receive<AssignLanguageRequest>()
            val lang = req.language.trim()
            if (lang.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "language is required"))
                return@post
            }

            val ext = item.path.substringAfterLast('.').lowercase()

            when (val guard = seedingGuard.check(item.path, configStore.current)) {
                is SeedingCheckResult.Blocked -> { call.respond(HttpStatusCode.Conflict, mapOf("error" to "File is seeded by '${guard.torrentName}'")); return@post }
                is SeedingCheckResult.Unreachable -> { call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "qBittorrent unreachable: ${guard.reason}")); return@post }
                else -> Unit
            }

            val ok = if (ext == "mkv") {
                MkvpropeditRunner.setLanguage(item.path, track.streamIndex, lang)
            } else {
                FfmpegRunner.setLanguage(item.path, track.streamIndex, lang)
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
            mediaHistory.record(mediaId, "assign_language", "specifier=$specifier lang=$lang")

            val cfg = configStore.current
            if (!item.jellyfinId.isNullOrBlank() && cfg.apiKeys.jellyfinUrl.isNotBlank()) {
                jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, item.jellyfinId)
            }

            call.respond(AssignLanguageResponse(ok = true, language = lang))
        }
    }
}

private fun MediaItem.toTriageItem(): TriageItem? {
    if (kind == MediaKind.TV_SHOW) {
        val epIssues = episodes.mapNotNull { ep ->
            val untagged = ep.tracks
                .filter { (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null }
                .map { t -> TriageTrack(specifier = t.specifier, streamIndex = t.streamIndex, kind = t.kind.name.lowercase(), codec = t.codec, title = t.title) }
            val missingOverview = ep.overview.isNullOrBlank()
            val multiDefault = ep.detectMultiDefaultAudio()
            if (untagged.isEmpty() && !missingOverview && multiDefault == null) return@mapNotNull null
            val code = if (ep.seasonNumber != null && ep.episodeNumber != null) {
                "S${ep.seasonNumber.toString().padStart(2, '0')}E${ep.episodeNumber.toString().padStart(2, '0')}"
            } else ep.filename.substringBeforeLast('.')
            EpisodeTriageItem(
                filename = ep.filename,
                episodeCode = code,
                title = ep.title,
                untaggedTracks = untagged,
                missingOverview = missingOverview,
                multiDefault = multiDefault,
            )
        }
        if (epIssues.isEmpty()) return null
        return TriageItem(
            mediaId = id,
            title = title,
            year = year,
            path = path,
            kind = "tv",
            posterPath = posterPath,
            originalLanguage = originalLanguage,
            untaggedTracks = emptyList(),
            episodeIssues = epIssues,
            resolvedLanguage = resolvedLanguage,
            languageMix = languageMix,
        )
    }
    val untagged = tracks
        .filter { (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null }
        .map { t ->
            TriageTrack(
                specifier = t.specifier,
                streamIndex = t.streamIndex,
                kind = t.kind.name.lowercase(),
                codec = t.codec,
                title = t.title,
            )
        }
    val mismatch = detectCascadeMismatch()
    val multiDefault = detectMultiDefaultAudio()
    if (untagged.isEmpty() && mismatch == null && multiDefault == null) return null
    return TriageItem(
        mediaId = id,
        title = title,
        year = year,
        path = path,
        kind = "movie",
        posterPath = posterPath,
        originalLanguage = originalLanguage,
        untaggedTracks = untagged,
        cascadeMismatch = mismatch,
        multiDefault = multiDefault,
    )
}

private fun MediaItem.detectMultiDefaultAudio(): MultiDefaultIssue? {
    val defaults = tracks.filter { it.kind == TrackKind.AUDIO && it.default }
    if (defaults.size < 2) return null
    return MultiDefaultIssue(defaultSpecifiers = defaults.map { it.specifier })
}

private fun Episode.detectMultiDefaultAudio(): MultiDefaultIssue? {
    val defaults = tracks.filter { it.kind == TrackKind.AUDIO && it.default }
    if (defaults.size < 2) return null
    return MultiDefaultIssue(defaultSpecifiers = defaults.map { it.specifier })
}

private fun MediaItem.detectCascadeMismatch(): CascadeMismatch? {
    if (languageMix || resolvedLanguage.isNullOrBlank()) return null
    val audioTracks = tracks.filter { it.kind == TrackKind.AUDIO }
    val expectedTrack = audioTracks.firstOrNull { it.language == resolvedLanguage } ?: return null
    val currentDefault = audioTracks.firstOrNull { it.default }
    if (currentDefault != null && currentDefault.specifier == expectedTrack.specifier) return null
    return CascadeMismatch(
        resolvedLanguage = resolvedLanguage,
        expectedDefaultSpecifier = expectedTrack.specifier,
        actualDefaultLang = currentDefault?.language,
    )
}
