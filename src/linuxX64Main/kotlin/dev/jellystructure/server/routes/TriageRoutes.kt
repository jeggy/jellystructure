package dev.jellystructure.server.routes

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.torrent.SeedingCheckResult
import dev.jellystructure.torrent.SeedingGuard
import dev.jellystructure.media.DuplicateEpisodes
import dev.jellystructure.media.FfmpegRunner
import dev.jellystructure.media.FfprobeRunner
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MkvHealthCache
import dev.jellystructure.media.MediaSegmentStore
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
    val coverAsVideo: String? = null,  // Phase 144: specifier of a cover-image track muxed as video (repairable), or null
    val segmentsLowConfidence: Boolean = false,  // Phase 150: this episode's own heuristic guess is below the trust threshold
    /** Bug fix (Ravilo auto-play-next loop): this entry is a redundant copy — another file already claims
     *  the same episode number. Not repairable from here: the FILES have to be fixed on disk. */
    val duplicateEpisode: Boolean = false,
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
    val coverAsVideo: String? = null,       // Phase 144: movie — specifier of a cover-image track muxed as video, or null
    val segmentsLowConfidence: Boolean = false,  // Phase 150: movie — its own heuristic guess is below the trust threshold
    val noSegments: Boolean = false,             // Phase 150: title-level — no marker anywhere yet (movie, or whole series)
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

fun Route.triageRoutes(store: MediaStore, jellyfinClient: JellyfinClient, configStore: ConfigStore, mediaHistory: MediaHistory, seedingGuard: SeedingGuard, segmentStore: MediaSegmentStore) {
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
            val zeroAudioInstances = all.sumOf { TriageDetection.zeroAudioCount(it) }
            val zeroAudioTitles = all.count { TriageDetection.zeroAudioCount(it) > 0 }
            val coverAsVideoInstances = all.sumOf { TriageDetection.coverAsVideoCount(it) }  // Phase 144
            val coverAsVideoTitles = all.count { TriageDetection.coverAsVideoCount(it) > 0 }
            val dupEpisodeInstances = all.sumOf { TriageDetection.duplicateEpisodeCount(it) }
            val dupEpisodeTitles = all.count { TriageDetection.duplicateEpisodeCount(it) > 0 }
            val unresolvedIdInstances = all.sumOf { TriageDetection.unresolvedJellyfinIdCount(it) }
            val unresolvedIdTitles = all.count { TriageDetection.unresolvedJellyfinIdCount(it) > 0 }
            // Phase 150: only meaningful once detect_segments is actually enabled — otherwise EVERY title
            // has "no segments" (the feature has simply never run) and the row would flood with a
            // misleading "everything is broken" count for an admin who hasn't opted in at all.
            val segmentsEnabled = configStore.current.scan.pipeline.any { it.step == "detect_segments" && it.enabled }
            val segmentsLowConfInstances = if (segmentsEnabled) all.sumOf { TriageDetection.lowConfidenceSegmentsCount(it, segmentStore) } else 0
            val segmentsLowConfTitles = if (segmentsEnabled) all.count { TriageDetection.lowConfidenceSegmentsCount(it, segmentStore) > 0 } else 0
            val noSegmentsTitles = if (segmentsEnabled) all.count { TriageDetection.hasNoSegments(it, segmentStore) } else 0
            // Phase 201 amendment (2026-09-13): Tracks-after-Cluster — unplayable in Ravilo, fine in Jellyfin.
            val mkvBroken = MkvHealthCache.brokenPaths(all)
            val mkvLayoutInstances = all.sumOf { TriageDetection.mkvLayoutBrokenCount(it, mkvBroken) }
            val mkvLayoutTitles = all.count { TriageDetection.mkvLayoutBrokenCount(it, mkvBroken) > 0 }

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
                TriageTypeCount("duplicate_episode", "Duplicate episode files",
                    "Two files claim the same episode number — Ravilo can only play one of them, and auto-play-next stalls on the copy. Delete the extra file, or fix its episode number, then re-scan.",
                    dupEpisodeInstances, dupEpisodeTitles),
                TriageTypeCount("unresolved_jellyfin_id", "Episode never matched in Jellyfin",
                    "jellystructure could read a season/episode from the filename, but Jellyfin never numbered this file (no IndexNumber) — it silently drops out of playstate, next-episode targeting, and Jellyfin's own Continue Watching/Next Up. Fix the episode's identification in Jellyfin (rename to match its naming rules, or manually identify it), then re-scan.",
                    unresolvedIdInstances, unresolvedIdTitles),
                TriageTypeCount("zero_audio", "No audio tracks",
                    "Zero audio tracks detected — usually a corrupt/truncated file. Open Tracks & order for the diagnosis and repair options.",
                    zeroAudioInstances, zeroAudioTitles),
                TriageTypeCount("cover_as_video", "Cover art muxed as a video track",
                    "A still image (cover.png etc.) is muxed as a second video stream — players may open the file but never start the video. Repairable: drop the cover stream.",
                    coverAsVideoInstances, coverAsVideoTitles),
                TriageTypeCount("segments_lowconf", "Low-confidence segments",
                    "Intro/credits found by heuristic below 0.60 — worth an eyeball.",
                    segmentsLowConfInstances, segmentsLowConfTitles),
                TriageTypeCount("no_segments", "No intro/credits detected",
                    "Skip Intro/Credits falls back to the fixed end-of-file heuristic — no chapter, heuristic, or manual marker exists yet.",
                    noSegmentsTitles, noSegmentsTitles),
                TriageTypeCount("mkv_track_layout", "Unplayable in Ravilo (MKV structure)",
                    "Either a flag edit moved the file's Tracks element after its first Cluster, or a prior repair left an element with a corrupted declared size. Jellyfin seeks/resyncs past both and plays the file fine, which is why nothing else here looks wrong — Ravilo reads linearly and buffers forever. Repair rewrites the header in place, no re-encode.",
                    mkvLayoutInstances, mkvLayoutTitles),
            )
            val result = TriageCount(types = types, total = types.sumOf { it.instances })
            triageCountCache = Pair(ver, result)
            call.respond(result)
        }

        get {
            // Phase 150: same segmentsEnabled gate as /triage/count — kept in lockstep so the dock never
            // shows "no segments"/"low-confidence segments" entries the breakdown badge reports as 0.
            val segmentsEnabled = configStore.current.scan.pipeline.any { it.step == "detect_segments" && it.enabled }
            val items = store.allItems()
                .mapNotNull { it.toTriageItem(segmentsEnabled, segmentStore) }
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
            // Security fix (2026-08-02 review, finding L2) — only checked for blank; this value reaches
            // an UNQUOTED `--set language=$lang` in TrackCommandBuilder (MkvpropeditRunner/FfmpegRunner),
            // so shell metacharacters here execute. TrackRoutes/MediaRoutes' own language-write routes
            // already validate the format before it reaches that sink — apply the same check here.
            if (lang.isBlank() || !lang.matches(Regex("[a-zA-Z]{2,8}(-[a-zA-Z0-9]{2,8})*"))) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid language code"))
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
            // Security fix (2026-08-02 review, finding L2) — only checked for blank; this value reaches
            // an UNQUOTED `--set language=$lang` in TrackCommandBuilder (MkvpropeditRunner/FfmpegRunner),
            // so shell metacharacters here execute. TrackRoutes/MediaRoutes' own language-write routes
            // already validate the format before it reaches that sink — apply the same check here.
            if (lang.isBlank() || !lang.matches(Regex("[a-zA-Z]{2,8}(-[a-zA-Z0-9]{2,8})*"))) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid language code"))
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

private fun MediaItem.toTriageItem(segmentsEnabled: Boolean = false, segmentStore: MediaSegmentStore): TriageItem? {
    if (kind == MediaKind.TV_SHOW) {
        val dupEpisodeIndices = DuplicateEpisodes.extraIndices(episodes)
        val epIssues = episodes.mapIndexedNotNull { epIdx, ep ->
            val untagged = ep.tracks
                .filter { (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null }
                .map { t -> TriageTrack(specifier = t.specifier, streamIndex = t.streamIndex, kind = t.kind.name.lowercase(), codec = t.codec, title = t.title) }
            val missingStill = !ep.hasStill
            val multiDefault = ep.detectMultiDefaultAudio()
            val coverAsVideo = TriageDetection.coverVideoSpecifier(ep.tracks)  // Phase 144
            val segmentsLowConfidence = segmentsEnabled &&  // Phase 150/163
                TriageDetection.isLowConfidenceSegments(segmentStore.segmentsForEpisode(id, ep.filename, ep.episodeNumber ?: 0))
            val duplicateEpisode = epIdx in dupEpisodeIndices
            if (untagged.isEmpty() && !missingStill && multiDefault == null && coverAsVideo == null &&
                !segmentsLowConfidence && !duplicateEpisode) return@mapIndexedNotNull null
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
                coverAsVideo = coverAsVideo,
                segmentsLowConfidence = segmentsLowConfidence,
                duplicateEpisode = duplicateEpisode,
            )
        }
        val missingArtwork = !posterArtworkExists(this)
        val noSegments = segmentsEnabled && TriageDetection.hasNoSegments(this, segmentStore)  // Phase 150/163
        if (epIssues.isEmpty() && !missingArtwork && !missingFromSource && !noSegments) return null
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
            noSegments = noSegments,
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
    val coverAsVideo = TriageDetection.coverVideoSpecifier(tracks)  // Phase 144
    val segmentsLowConfidence = segmentsEnabled && TriageDetection.isLowConfidenceSegments(segmentStore.segmentsForItem(id))  // Phase 150/163
    val noSegments = segmentsEnabled && TriageDetection.hasNoSegments(this, segmentStore)  // Phase 150/163
    if (untagged.isEmpty() && mismatch == null && multiDefault == null && !missingArtwork && !missingFromSource &&
        coverAsVideo == null && !segmentsLowConfidence && !noSegments) return null
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
        coverAsVideo = coverAsVideo,
        segmentsLowConfidence = segmentsLowConfidence,
        noSegments = noSegments,
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
