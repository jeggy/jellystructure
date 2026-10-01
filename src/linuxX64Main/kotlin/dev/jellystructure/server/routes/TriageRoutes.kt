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
import dev.jellystructure.model.originalYear
import dev.jellystructure.model.TrackKind
import dev.jellystructure.resolver.LanguageResolver
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
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
    /** Phase 278 (FR-278-12) — a music entry: `needs_you` · `no_match` · `no_cover` (albums, [kind] `album`) or
     *  `no_picture` (artists, [kind] `artist`). The dock opens the album or artist page, not `/media/`. */
    val musicIssue: String? = null,
    /** Phase 280 (FR-280-7) — an audiobook entry ([kind] `audiobook`): `missing_part` · `two_in_one` · `no_cover`.
     *  The dock opens the Audiobook page. */
    val audiobookIssue: String? = null,
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
    // Phase 285 (FR-285-9) — the same counts among films / series only; 0 for a type that is not per file.
    val movies: Int = 0,
    val series: Int = 0,
    @SerialName("movie_titles") val movieTitles: Int = 0,
    @SerialName("series_titles") val seriesTitles: Int = 0,
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

private var triageCountCache: Pair<String, TriageCount>? = null

fun Route.triageRoutes(store: MediaStore, jellyfinClient: JellyfinClient, configStore: ConfigStore, mediaHistory: MediaHistory, seedingGuard: SeedingGuard, segmentStore: MediaSegmentStore, music: dev.jellystructure.media.MusicPipeline? = null) {
    route("/triage") {
        // Phase 285 — the same computation the Dashboard reads (kind-split, cached on the library version).
        get("/count") { call.respond(triageCountFor(store, configStore, segmentStore, music)) }

        get {
            // Phase 150: same segmentsEnabled gate as /triage/count — kept in lockstep so the dock never
            // shows "no segments"/"low-confidence segments" entries the breakdown badge reports as 0.
            val segmentsEnabled = configStore.current.scan.pipeline.any { it.step == "detect_segments" && it.enabled }
            val items = store.allItems()
                .mapNotNull { it.toTriageItem(segmentsEnabled, segmentStore) }
            call.respond(items + musicTriageItems(music, configStore) + audiobookTriageItems(music, configStore))
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

/** Phase 278 (FR-278-11/12) — the music library's attention entries; absent entirely when no music library is mapped. */
/**
 * Phase 117/146/285 — the per-type counts, computed once per library version and shared by `/triage/count` and the
 * Dashboard (FR-285-1). FR-285-9: every file-level type is also counted per kind, so the Films and Series chips count
 * honestly — `movies`/`series` are that type's instances among films / series, `movieTitles`/`seriesTitles` the titles.
 */
internal suspend fun triageCountFor(store: MediaStore, configStore: ConfigStore, segmentStore: MediaSegmentStore, music: dev.jellystructure.media.MusicPipeline?): TriageCount {
    // Phase 254 — a deep check's finding changes the count without touching the library version.
    val ver = "${store.libraryVersion}:${dev.jellystructure.media.FileDamage.revision}:${dev.jellystructure.media.TrackCoverageFlags.revision}:${music?.store?.version}:${music?.audiobooks?.store?.version}"   // Phase 255 — a coverage finding changes the count too
    triageCountCache?.let { (v, c) -> if (v == ver) return c }
    val all = store.allItems()
    val types = mediaTriageTypes(all, configStore, segmentStore)
    val movies = mediaTriageTypes(all.filter { it.kind == MediaKind.MOVIE }, configStore, segmentStore).associateBy { it.key }
    val series = mediaTriageTypes(all.filter { it.kind == MediaKind.TV_SHOW }, configStore, segmentStore).associateBy { it.key }
    val split = types.map { t -> t.copy(movies = movies[t.key]?.instances ?: 0, series = series[t.key]?.instances ?: 0, movieTitles = movies[t.key]?.titles ?: 0, seriesTitles = series[t.key]?.titles ?: 0) }
    val result = (split + musicTriageCounts(music, configStore) + audiobookTriageCounts(music, configStore)).let { all3 -> TriageCount(types = all3, total = all3.sumOf { it.instances }) }
    triageCountCache = Pair(ver, result)
    return result
}

/** The media (film/series) types over [all] — called three times per computation: everything, films only, series only. */
private fun mediaTriageTypes(all: List<MediaItem>, configStore: ConfigStore, segmentStore: MediaSegmentStore): List<TriageTypeCount> {

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
    // Phase 203 — brokenPathsOrNull() never triggers a sweep and never blocks; `null` (cold
    // cache) means the count below is omitted from `types` entirely rather than reported as 0
    // (FR-203-1/FR-203-2). See MkvHealthCache's own doc for why this used to hold up the whole
    // response.
    val mkvBroken = MkvHealthCache.brokenPathsOrNull()?.keys
    val mkvLayoutInstances = mkvBroken?.let { b -> all.sumOf { TriageDetection.mkvLayoutBrokenCount(it, b) } }
    val mkvLayoutTitles = mkvBroken?.let { b -> all.count { TriageDetection.mkvLayoutBrokenCount(it, b) > 0 } }

    val types = listOfNotNull(
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
        // Phase 203 — omitted entirely (not sent as a 0) while mkvLayoutInstances/Titles are
        // null, i.e. no sweep has completed yet for this process.
        if (mkvLayoutInstances != null && mkvLayoutTitles != null) TriageTypeCount(
            "mkv_track_layout", "Unplayable in Ravilo (MKV structure)",
            "Either a flag edit moved the file's Tracks element after its first Cluster, or a prior repair left an element with a corrupted declared size. Jellyfin seeks/resyncs past both and plays the file fine, which is why nothing else here looks wrong — Ravilo reads linearly and buffers forever. Repair rewrites the header in place, no re-encode.",
            mkvLayoutInstances, mkvLayoutTitles,
        ) else null,
        // Phase 254 (FR-254-7) — omitted while unknown, like the type above.
        dev.jellystructure.media.FileDamage.damagedPathsOrNull()?.let { damaged ->
            TriageTypeCount(
                "file_damage", "Damaged video files",
                "A deep check (reading the whole file) found parts of these files that cannot be read — data was overwritten mid-file. Jellyfin resyncs past it; a viewer sees a stall, a skip or a smear part-way through. Open the title: jellystructure can replace the file from the clean copy qBittorrent is still seeding.",
                all.sumOf { TriageDetection.fileDamageCount(it, damaged) }, all.count { TriageDetection.fileDamageCount(it, damaged) > 0 },
            )
        },
        // Phase 255 (FR-255-7) — two types, because they mean different things; omitted while unknown.
        dev.jellystructure.media.TrackCoverageFlags.flaggedOrNull()?.let { flagged ->
            val early = flagged.filterValues { dev.jellystructure.media.TrackCoverageFlags.endsEarly(it) }.keys
            TriageTypeCount(
                dev.jellystructure.media.TrackCoverageFlags.TYPE_TRACK_ENDS_EARLY, "Audio or video stops before the file ends",
                "A track in these files ends early. Viewers who get that track hear silence (or see black) from that point on, and which track they get depends on the player. Open the title for the exact time and the suggested fix.",
                all.sumOf { TriageDetection.trackCoverageCount(it, early) }, all.count { TriageDetection.trackCoverageCount(it, early) > 0 },
            )
        },
        dev.jellystructure.media.TrackCoverageFlags.flaggedOrNull()?.let { flagged ->
            val wrong = flagged.filterValues { dev.jellystructure.media.TrackCoverageFlags.headerWrong(it) }.keys
            TriageTypeCount(
                dev.jellystructure.media.TrackCoverageFlags.TYPE_DURATION_HEADER_WRONG, "File claims to be longer than it is",
                "Everything in these files ends before the length the file reports. Players show the wrong length, and a player that marks watched at 90 % never gets there, so the episode never leaves Continue Watching.",
                all.sumOf { TriageDetection.trackCoverageCount(it, wrong) }, all.count { TriageDetection.trackCoverageCount(it, wrong) > 0 },
            )
        },
    )
    return types
}

internal fun musicTriageCounts(music: dev.jellystructure.media.MusicPipeline?, configStore: ConfigStore): List<TriageTypeCount> {
    if (music == null || dev.jellystructure.music.MusicScanner.musicLibraries(configStore.current).isEmpty()) return emptyList()
    // Phase 293 (FR-293-1) — one predicate per key, shared with the Library's `filter=<key>` list.
    val p = dev.jellystructure.music.MusicTriage.Music(music.store.snapshot(), dev.jellystructure.music.MusicFlags.roots(configStore.current))
    val needs = p.count("music_needs_match")
    val noCover = p.count("music_no_cover")
    val noPicture = p.count("music_no_picture")
    val reencodes = p.count("music_reencodes")
    // Phase 283 (FR-283-3) — what the folders and the songs disagree on.
    val shared = p.count("music_shared_album")
    val folder = p.count("music_folder_disagrees")
    // Phase 284 (FR-284-12) — matched songs whose files carry no MusicBrainz ids (Jellyfin reads them from the tags).
    val noIdSongs = p.count("music_files_no_ids")
    val noIdAlbums = p.songAlbums("music_files_no_ids")
    return listOf(
        TriageTypeCount("music_files_no_ids", "Songs whose files don’t say what they are",
            "Matched, but the files carry no MusicBrainz ids — every other player still sees the folder’s guess. *Write tags* on the album (or Library → Music → Write tags…) puts the match into the files.",
            noIdSongs, noIdAlbums),
        TriageTypeCount("music_shared_album", "Albums in several folders",
            "Two or more folders say they are the same album — often a band's singles whose files all name one compilation. Open one: if they are one album, put the songs in one folder; if each folder is its own release, give it its own match with Find match….",
            shared, shared),
        TriageTypeCount("music_folder_disagrees", "Albums whose folder and songs disagree",
            "The folder's name and the songs' Album or Album artist tags name different things, so a search by the tags can find the wrong album, or none. Open the album: Find match… can search by the folder's name.",
            folder, folder),
        TriageTypeCount("music_needs_match", "Albums need a match",
            "MusicBrainz found several candidates and none clearly won, or found nothing. Open the album: Find match… searches, or identifies it by sound.",
            needs, needs),
        TriageTypeCount("music_no_cover", "Albums without a cover",
            "Matched, but no cover on disk — nobody has uploaded one to the Cover Art Archive yet. Upload one on the album's Artwork tab.",
            noCover, noCover),
        TriageTypeCount("music_no_picture", "Artists without a picture",
            "Neither fanart.tv nor Wikimedia Commons had one. Choose or upload one on the artist's Artwork tab.",
            noPicture, noPicture),
        TriageTypeCount("music_reencodes", "Songs a phone plays only by re-encoding",
            "WMA files: Jellyfin re-encodes them on every play on a phone, never gapless. Convert… makes AAC copies and keeps the originals.",
            reencodes, reencodes),
    )
}

private fun musicTriageItems(music: dev.jellystructure.media.MusicPipeline?, configStore: ConfigStore): List<TriageItem> {
    if (music == null || dev.jellystructure.music.MusicScanner.musicLibraries(configStore.current).isEmpty()) return emptyList()
    val s = music.store.snapshot()
    val out = mutableListOf<TriageItem>()
    val live = s.albums.values.filter { it.missingSince == null }
    val flags = dev.jellystructure.music.MusicFlags.of(live, dev.jellystructure.music.MusicFlags.roots(configStore.current))
    for (a in live.sortedBy { (it.sortName ?: it.title).lowercase() }) {
        val kinds = flags[a.id].orEmpty().map { it.kind }
        val issue = when {
            // Phase 283 — a flag first: it says why the rest (a match, a cover) went the way it did.
            dev.jellystructure.music.MusicFlags.SHARED in kinds -> dev.jellystructure.music.MusicFlags.SHARED
            dev.jellystructure.music.MusicFlags.FOLDER in kinds -> dev.jellystructure.music.MusicFlags.FOLDER
            !a.matchLocked && a.matchState == dev.jellystructure.model.MusicMatch.NEEDS_YOU -> "needs_you"
            !a.matchLocked && a.matchState == dev.jellystructure.model.MusicMatch.UNMATCHED -> "no_match"
            a.matchState == dev.jellystructure.model.MusicMatch.MATCHED && a.coverState == dev.jellystructure.model.MusicArt.NONE -> "no_cover"
            else -> null
        } ?: continue
        out += TriageItem(mediaId = a.id, title = a.title, year = a.originalYear(), path = a.path.orEmpty(), kind = "album", untaggedTracks = emptyList(), musicIssue = issue)
    }
    for (r in s.artists.values.filter { it.missingSince == null && it.path != null && it.imageState == dev.jellystructure.model.MusicArt.NONE }.sortedBy { it.name.lowercase() })
        out += TriageItem(mediaId = r.id, title = r.name, year = null, path = r.path.orEmpty(), kind = "artist", untaggedTracks = emptyList(), musicIssue = "no_picture")
    return out
}


/** Phase 280 (FR-280-7) — the audiobook libraries' attention entries; absent entirely when none is mapped. */
internal fun audiobookTriageCounts(music: dev.jellystructure.media.MusicPipeline?, configStore: ConfigStore): List<TriageTypeCount> {
    val scanner = music?.audiobooks ?: return emptyList()
    if (dev.jellystructure.audiobooks.AudiobooksScanner.audiobookLibraries(configStore.current).isEmpty()) return emptyList()
    // Phase 293 (FR-293-1) — the same predicates the Library's `filter=<key>` list uses.
    val snap = scanner.store.snapshot()
    fun n(key: String) = dev.jellystructure.music.MusicTriage.bookCount(key, snap)
    return listOf(
        TriageTypeCount("audiobooks_missing_part", "Audiobooks with a missing part",
            "The folder's files skip a number. Open the book: if the part really is missing, get it; if it is just numbered wrong, say so and the flag goes.",
            n("audiobooks_missing_part"), n("audiobooks_missing_part")),
        TriageTypeCount("audiobooks_two_in_one", "Folder holds two books",
            "The files in one folder name two different books. Split them into two folders, or say it is one book.",
            n("audiobooks_two_in_one"), n("audiobooks_two_in_one")),
        TriageTypeCount("audiobooks_no_cover", "Audiobooks without a cover",
            "No cover.jpg in the folder and no picture inside the files. Choose or upload one on the book's Artwork tab.",
            n("audiobooks_no_cover"), n("audiobooks_no_cover")),
        TriageTypeCount("audiobooks_no_narrator", "Audiobooks with no narrator",
            "Nothing names who reads it. For information — a book plays the same without one.",
            n("audiobooks_no_narrator"), n("audiobooks_no_narrator")),
    )
}

private fun audiobookTriageItems(music: dev.jellystructure.media.MusicPipeline?, configStore: ConfigStore): List<TriageItem> {
    val scanner = music?.audiobooks ?: return emptyList()
    if (dev.jellystructure.audiobooks.AudiobooksScanner.audiobookLibraries(configStore.current).isEmpty()) return emptyList()
    return scanner.store.snapshot().books.values.filter { it.missingSince == null }.sortedBy { it.title.lowercase() }.mapNotNull { b ->
        val issue = when {
            b.gap.isNotEmpty() && !b.gapDismissed -> "missing_part"
            b.albumTags.size > 1 && !b.twoInOneDismissed -> "two_in_one"
            b.coverState == dev.jellystructure.model.MusicArt.NONE -> "no_cover"
            else -> null
        } ?: return@mapNotNull null
        TriageItem(mediaId = b.id, title = b.title, year = b.year, path = b.folderPath.orEmpty(), kind = "audiobook", untaggedTracks = emptyList(), audiobookIssue = issue)
    }
}
