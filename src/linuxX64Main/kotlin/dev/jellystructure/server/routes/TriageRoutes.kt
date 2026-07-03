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
import dev.jellystructure.media.TriageDetection
import dev.jellystructure.media.posterArtworkExists
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.TrackKind
import dev.jellystructure.resolver.LanguageResolver
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
    val missingStill: Boolean,  // Phase 121: no image on disk at all (was missingOverview — TMDB plot text isn't an issue)
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
    val missingArtwork: Boolean = false,   // R123: no poster.jpg on disk — needs artwork
    val missingFromSource: Boolean = false, // Phase 95: gone from Jellyfin — kept (scanner never deletes), needs review
)

// Phase 117: one row per triage issue type, always present (even at 0), carrying its own display copy
// and BOTH counting bases — `instances` (what the dashboard headline sums; episode/track-level for
// untagged/missingStill) and `titles` (how many distinct items/series the Library will actually show
// for this type — a series with 200 untagged episode tracks is 200 instances but 1 title).
@Serializable
data class TriageTypeCount(
    val key: String,
    val label: String,
    val description: String,
    val instances: Int,
    val titles: Int,
)

@Serializable
data class TriageCount(
    val types: List<TriageTypeCount>,
    val total: Int,
)

@Serializable
private data class AssignLanguageRequest(val language: String)

@Serializable
private data class AssignLanguageResponse(val ok: Boolean, val language: String)

private var triageCountCache: Pair<Long, TriageCount>? = null

fun Route.triageRoutes(store: MediaStore, jellyfinClient: JellyfinClient, configStore: ConfigStore, mediaHistory: MediaHistory, seedingGuard: SeedingGuard) {
    route("/triage") {
        get("/count") {
            val ver = store.libraryVersion
            triageCountCache?.let { (v, c) -> if (v == ver) { call.respond(c); return@get } }
            val all = store.allItems()

            val untaggedInstances = all.sumOf { TriageDetection.untaggedCount(it) }
            val untaggedTitles = all.count { TriageDetection.untaggedCount(it) > 0 }
            val mismatchTitles = all.count { TriageDetection.hasCascadeMismatch(it) }
            val multiDefaultTitles = all.count { TriageDetection.hasMultiDefault(it) }
            val languageMixTitles = all.count { it.languageMix }
            val missingArtworkTitles = all.count { !posterArtworkExists(it) }
            val missingFromSourceTitles = all.count { it.missingFromSource }   // Phase 95
            val missingStillInstances = all.sumOf { TriageDetection.missingStillCount(it) }
            val missingStillTitles = all.count { TriageDetection.missingStillCount(it) > 0 }
            val dupGroups = all.filter { !it.jellyfinId.isNullOrBlank() }.groupBy { it.jellyfinId }.filterValues { it.size > 1 }

            val types = listOf(
                TriageTypeCount("untagged", "Untagged audio/subtitle tracks",
                    "Tracks with no language tag — Ravilo and the workbench can't filter by language until these are assigned.",
                    untaggedInstances, untaggedTitles),
                TriageTypeCount("cascade_mismatch", "Wrong default audio track",
                    "The default audio track doesn't match the title's resolved metadata language.",
                    mismatchTitles, mismatchTitles),
                TriageTypeCount("multi_default", "Multiple default audio tracks",
                    "More than one audio track is flagged default — a file should have exactly one.",
                    multiDefaultTitles, multiDefaultTitles),
                TriageTypeCount("language_mix", "Mixed-language series",
                    "Episodes disagree on audio language — the majority language is used for metadata.",
                    languageMixTitles, languageMixTitles),
                TriageTypeCount("missing_artwork", "Missing poster artwork",
                    "No poster.jpg on disk for this title.",
                    missingArtworkTitles, missingArtworkTitles),
                TriageTypeCount("missing_from_source", "No longer in Jellyfin",
                    "The scanner no longer finds this title in Jellyfin — kept for review, never auto-deleted.",
                    missingFromSourceTitles, missingFromSourceTitles),
                TriageTypeCount("missing_still", "Missing episode image",
                    "Episode has no still image — no TMDB still and no screen-grab — so Ravilo shows a blank episode card.",
                    missingStillInstances, missingStillTitles),
                TriageTypeCount("duplicate", "Duplicate library entries",
                    "The same Jellyfin item appears more than once — both open the same detail page; re-scan or remove the extra entry.",
                    dupGroups.values.sumOf { it.size }, dupGroups.size),
            )
            val result = TriageCount(types = types, total = types.sumOf { it.instances })
            triageCountCache = Pair(ver, result)
            call.respond(result)
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
            val missingStill = !ep.hasStill
            val multiDefault = ep.detectMultiDefaultAudio()
            if (untagged.isEmpty() && !missingStill && multiDefault == null) return@mapNotNull null
            val code = if (ep.seasonNumber != null && ep.episodeNumber != null) {
                "S${ep.seasonNumber.toString().padStart(2, '0')}E${ep.episodeNumber.toString().padStart(2, '0')}"
            } else ep.filename.substringBeforeLast('.')
            EpisodeTriageItem(
                filename = ep.filename,
                episodeCode = code,
                title = ep.title,
                untaggedTracks = untagged,
                missingStill = missingStill,
                multiDefault = multiDefault,
            )
        }
        val missingArtwork = !posterArtworkExists(this)
        if (epIssues.isEmpty() && !missingArtwork && !missingFromSource) return null
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
            missingArtwork = missingArtwork,
            missingFromSource = missingFromSource,
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
    val missingArtwork = !posterArtworkExists(this)
    if (untagged.isEmpty() && mismatch == null && multiDefault == null && !missingArtwork && !missingFromSource) return null
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
        missingArtwork = missingArtwork,
        missingFromSource = missingFromSource,
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
    val expectedTrack = audioTracks.firstOrNull { LanguageResolver.sameLanguage(it.language, resolvedLanguage) } ?: return null
    val currentDefault = audioTracks.firstOrNull { it.default }
    if (currentDefault != null && currentDefault.specifier == expectedTrack.specifier) return null
    return CascadeMismatch(
        resolvedLanguage = resolvedLanguage,
        expectedDefaultSpecifier = expectedTrack.specifier,
        actualDefaultLang = currentDefault?.language,
    )
}
