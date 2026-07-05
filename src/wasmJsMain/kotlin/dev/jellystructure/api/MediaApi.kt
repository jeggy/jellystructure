package dev.jellystructure.api

import dev.jellystructure.encodeURIComponent
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.MediaPage
import dev.jellystructure.model.NfoFileTree
import dev.jellystructure.model.Person
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import dev.jellystructure.shared.tv.Condition
import dev.jellystructure.shared.tv.ConditionGroup
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json as KJson

@Serializable
data class StatsResponse(
    val movies: Int,
    val tvShows: Int = 0,
    val tvEpisodes: Int = 0,
    val issues: Int,
    val nfoCoverage: Int = 0,
)

@Serializable
data class ArtworkStatus(
    val posterExists: Boolean,
    val fanartExists: Boolean,
    val logoExists: Boolean = false,
)

// --- Phase 47: artwork candidate gallery ---
@Serializable
data class ArtworkCandidate(
    val filePath: String,
    val lang: String? = null,   // null = no-language / textless (UI bucket "xx")
    val voteAverage: Double = 0.0,
    val width: Int = 0,
    val height: Int = 0,
    val onDisk: Boolean = false,
)

@Serializable
data class ArtworkCandidatesResponse(
    val asset: String = "",
    val onDiskExists: Boolean = false,
    val resolvedLanguage: String? = null,
    val candidates: List<ArtworkCandidate> = emptyList(),
)

@Serializable
data class SeasonStatus(val season: Int, val posterExists: Boolean = false)

@Serializable
data class EpisodeStillStatus(val filename: String, val stillExists: Boolean = false, val stillPath: String = "", val source: String? = null)

@Serializable
data class NfoWriteResult(val path: String)

@Serializable
data class NfoWritableResult(val writable: Boolean, val path: String, val error: String? = null)

// Phase 117: one row per triage issue type (always present, even at 0 — the Dashboard breakdown
// renders every type). `instances` is episode/track-level for untagged/missingStill, title-level
// for the rest; `titles` is always how many Library rows the type will show.
// Phase 109 — media worker (ffmpeg remux) job queue, mirrors dev.jellystructure.jobs.MediaJobSnapshot.
@Serializable
data class MediaJobSnapshot(
    val id: String, val type: String, val mediaId: String, val label: String, val state: String,
    val enqueuedBy: String, val createdAt: Long, val startedAt: Long? = null, val finishedAt: Long? = null,
    val error: String? = null, val fileCount: Int = 1, val filesDone: Int = 0, val pct: Double = 0.0, val speed: String? = null,
    val etaSeconds: Long? = null,
)

@Serializable
data class JobsSummary(
    val busy: Boolean, val doneToday: Int, val running: MediaJobSnapshot? = null,
    val queued: List<MediaJobSnapshot> = emptyList(), val recent: List<MediaJobSnapshot> = emptyList(),
)

@Serializable
data class TriageTypeCount(val key: String, val label: String, val description: String, val instances: Int, val titles: Int)

@Serializable
data class TriageCount(val types: List<TriageTypeCount> = emptyList(), val total: Int = 0)

@Serializable
data class TriageTrack(val specifier: String, val streamIndex: Int, val kind: String, val codec: String, val title: String? = null)

@Serializable
data class CascadeMismatch(val resolvedLanguage: String, val expectedDefaultSpecifier: String, val actualDefaultLang: String? = null)

@Serializable
data class MultiDefaultIssue(val defaultSpecifiers: List<String>)

@Serializable
data class EpisodeTriageItem(
    val filename: String, val episodeCode: String, val title: String? = null,
    val untaggedTracks: List<TriageTrack>, val missingStill: Boolean, val multiDefault: MultiDefaultIssue? = null,
)

@Serializable
data class TriageItem(
    val mediaId: String, val title: String, val year: Int? = null, val path: String,
    val kind: String = "movie", val posterPath: String? = null, val originalLanguage: String? = null,
    val untaggedTracks: List<TriageTrack>, val cascadeMismatch: CascadeMismatch? = null,
    val episodeIssues: List<EpisodeTriageItem> = emptyList(), val resolvedLanguage: String? = null,
    val languageMix: Boolean = false, val multiDefault: MultiDefaultIssue? = null,
    val missingArtwork: Boolean = false,
    val missingFromSource: Boolean = false,   // Phase 95: gone from Jellyfin — kept (scanner never deletes)
)

@Serializable
data class HistoryEntry(
    val id: String,
    val mediaId: String,
    val timestamp: Long,
    val action: String,
    val detail: String,
    val revertable: Boolean = false,
    val beforeSnapshot: String = "",
)

@Serializable
data class TrackFacetItem(val value: String, val count: Int, val color: String? = null)

@Serializable
data class TrackFacets(
    val audioLanguages: List<TrackFacetItem> = emptyList(),
    val audioCodecs: List<TrackFacetItem> = emptyList(),
    val trackTitles: List<TrackFacetItem> = emptyList(),
)

@Serializable
data class MetaFacets(
    val studios: List<TrackFacetItem> = emptyList(),
    val networks: List<TrackFacetItem> = emptyList(),
    val genres: List<TrackFacetItem> = emptyList(),
    val tags: List<TrackFacetItem> = emptyList(),
    val ageRatings: List<TrackFacetItem> = emptyList(),
)

// R127: meta + track facet counts narrowed to a condition set (POST /api/media/facets).
@Serializable
data class NarrowedFacets(
    val studios: List<TrackFacetItem> = emptyList(),
    val networks: List<TrackFacetItem> = emptyList(),
    val genres: List<TrackFacetItem> = emptyList(),
    val tags: List<TrackFacetItem> = emptyList(),
    val ageRatings: List<TrackFacetItem> = emptyList(),
    val audioLanguages: List<TrackFacetItem> = emptyList(),
    val audioCodecs: List<TrackFacetItem> = emptyList(),
    val trackTitles: List<TrackFacetItem> = emptyList(),
)

@Serializable
private data class FacetReq(val match: String = "ALL", val conditions: List<Condition> = emptyList(), val query: ConditionGroup? = null)

@Serializable
data class BatchCountRequest(
    val index: Int,
    val match: String = "ALL",
    val conditions: List<Condition> = emptyList(),
    // Phase 140 — the blocks tree; takes priority over match/conditions when present.
    val query: ConditionGroup? = null,
)

@Serializable
data class BatchCountResult(val index: Int, val total: Int)

@Serializable
data class ScanStatus(
    val running: Boolean,
    val status: String = "IDLE",
    val jobId: String? = null,
    val startedAt: Long? = null,
    val processedCount: Int = 0,
    val activeWorkers: Int = 0,
    val configuredWorkers: Int = 1,
    val nextScheduledRun: Long? = null,   // 93e: epoch seconds of the next automation run
    // Phase 135 — lets a late-joining/polling client reconstruct where the run is without having seen
    // the WS event stream: the active step + the whole ordered plan, plus the three run descriptors.
    val activeStep: String? = null,
    val stepPlan: List<String> = emptyList(),
    val trigger: String? = null,
    val scope: String? = null,
    val type: String? = null,
)

enum class PipelineRunResult { STARTED, ALREADY_RUNNING, FAILED }

private fun String.encodeURL() = encodeURIComponent(this)

object MediaApi {
    suspend fun list(
        kind: MediaKind? = null,
        filter: String? = null,
        search: String? = null,
        sort: String? = null,
        page: Int = 1,
        pageSize: Int = 20,
        studios: List<String> = emptyList(),
        networks: List<String> = emptyList(),
        genres: List<String> = emptyList(),
        audioLangs: List<String> = emptyList(),
        trackTitle: String? = null,
        audioCodec: String? = null,
        untaggedAudio: Boolean = false,
        tags: List<String> = emptyList(),
        heroItem: String? = null,
        viewer: String? = null,
        match: String = "ALL",
        conditions: List<Condition> = emptyList(),
        // Phase 140 — the blocks tree; takes priority over match/conditions when present.
        query: ConditionGroup? = null,
        tracker: String? = null,
    ): MediaPage? = runCatching {
        httpClient.get("/api/media") {
            if (kind != null) parameter("kind", kind.name)
            if (filter != null) parameter("filter", filter)
            if (!search.isNullOrBlank()) parameter("search", search)
            if (!sort.isNullOrBlank()) parameter("sort", sort)
            parameter("page", page)
            parameter("pageSize", pageSize)
            if (query != null) {
                parameter("query", KJson.encodeToString(ConditionGroup.serializer(), query))
            } else if (conditions.isNotEmpty()) {
                // R74: when a condition stack is provided, send it instead of per-facet params.
                parameter("conditions", KJson.encodeToString(ListSerializer(Condition.serializer()), conditions))
                parameter("match", match)
            } else {
                if (studios.isNotEmpty()) parameter("studios", studios.joinToString(","))
                if (networks.isNotEmpty()) parameter("networks", networks.joinToString(","))
                if (genres.isNotEmpty()) parameter("genres", genres.joinToString(","))
                if (audioLangs.isNotEmpty()) parameter("audioLang", audioLangs.joinToString(","))
                if (!trackTitle.isNullOrBlank()) parameter("trackTitle", trackTitle)
                if (!audioCodec.isNullOrBlank()) parameter("audioCodec", audioCodec)
                if (untaggedAudio) parameter("untaggedAudio", "true")
                if (tags.isNotEmpty()) parameter("tags", tags.joinToString(","))
                if (!heroItem.isNullOrBlank()) parameter("heroItem", heroItem)
            }
            if (!viewer.isNullOrBlank()) parameter("viewer", viewer)
            if (!tracker.isNullOrBlank()) parameter("tracker", tracker)
        }.body<MediaPage>()
    }.getOrNull()

    suspend fun batchCount(items: List<BatchCountRequest>): List<BatchCountResult>? = runCatching {
        httpClient.post("/api/media/batch-count") {
            contentType(ContentType.Application.Json)
            setBody(items)
        }.body<List<BatchCountResult>>()
    }.getOrNull()

    // R127: facet counts narrowed to a condition set (e.g. a channel's filter).
    suspend fun narrowedFacets(match: String, conditions: List<Condition>): NarrowedFacets? = runCatching {
        httpClient.post("/api/media/facets") {
            contentType(ContentType.Application.Json)
            setBody(FacetReq(match, conditions))
        }.body<NarrowedFacets>()
    }.getOrNull()

    /** Phase 140 — tree-native narrowed facets (the blocks editor's channel-scoped value-picker counts). */
    suspend fun narrowedFacets(query: ConditionGroup): NarrowedFacets? = runCatching {
        httpClient.post("/api/media/facets") {
            contentType(ContentType.Application.Json)
            setBody(FacetReq(query = query))
        }.body<NarrowedFacets>()
    }.getOrNull()

    suspend fun trackFacets(): TrackFacets? = runCatching {
        httpClient.get("/api/media/track-facets").body<TrackFacets>()
    }.getOrNull()

    suspend fun metaFacets(): MetaFacets? = runCatching {
        httpClient.get("/api/media/meta-facets").body<MetaFacets>()
    }.getOrNull()

    suspend fun get(id: String): MediaItem? = runCatching {
        httpClient.get("/api/media/$id").body<MediaItem>()
    }.getOrNull()

    @Serializable
    data class JellyfinLocksResponse(val lockData: Boolean, val lockedFields: List<String>)

    suspend fun jellyfinLocks(id: String): JellyfinLocksResponse? = runCatching {
        httpClient.get("/api/media/$id/jellyfin-locks").body<JellyfinLocksResponse>()
    }.getOrNull()

    suspend fun setTmdbId(id: String, tmdbId: Int?): MediaItem? = runCatching {
        httpClient.patch("/api/media/$id/tmdb-id") {
            contentType(ContentType.Application.Json)
            setBody("""{"tmdbId":${tmdbId ?: "null"}}""")
        }.body<MediaItem>()
    }.getOrNull()

    /** Re-fetches the item from Jellyfin and re-runs a full scan (ffprobe + TMDB). */
    suspend fun repullFromJellyfin(id: String): MediaItem? = runCatching {
        httpClient.post("/api/media/$id/repull-jellyfin").body<MediaItem>()
    }.getOrNull()

    // Returns true if the scan was successfully started, false if already running or failed.
    suspend fun startLibraryScan(jellyfinLibraryId: String): Boolean = runCatching {
        val response = httpClient.post("/api/scan?library=${encodeURIComponent(jellyfinLibraryId)}")
        response.status.value == 202
    }.getOrDefault(false)

    suspend fun startScan(): Boolean = runCatching {
        val response = httpClient.post("/api/scan")
        response.status == HttpStatusCode.Accepted
    }.getOrDefault(false)

    // 93c: run the composed automation (the saved scan pipeline) on demand — all enabled steps,
    // not just file discovery like startScan(). Conflict is distinguished from a genuine failure so the
    // caller can show "already running" instead of a misleading "failed to start".
    // full=true bypasses scan_files' freshness filter for this one run, so every downstream step
    // (sync_imdb_ratings, write_nfo, …) sees the whole library instead of just whatever's due for an
    // unrelated metadata recheck — "Run pipeline now (full)" in Settings.
    suspend fun runPipeline(full: Boolean = false): PipelineRunResult = runCatching {
        val url = if (full) "/api/pipeline/run?full=true" else "/api/pipeline/run"
        when (httpClient.post(url).status) {
            HttpStatusCode.Accepted -> PipelineRunResult.STARTED
            HttpStatusCode.Conflict -> PipelineRunResult.ALREADY_RUNNING
            else -> PipelineRunResult.FAILED
        }
    }.getOrDefault(PipelineRunResult.FAILED)

    suspend fun scanStatus(): ScanStatus? = runCatching {
        httpClient.get("/api/scan/status").body<ScanStatus>()
    }.getOrNull()

    suspend fun resumeScan(): Boolean = runCatching {
        val response = httpClient.post("/api/scan/resume")
        response.status.value in 200..299
    }.getOrDefault(false)

    suspend fun cancelScan(): Boolean = runCatching {
        val response = httpClient.post("/api/scan/cancel")
        response.status.value in 200..299
    }.getOrDefault(false)

    suspend fun stats(): StatsResponse? = runCatching {
        httpClient.get("/api/stats").body<StatsResponse>()
    }.getOrNull()

    /** Phase 44 — the tree of NFO files for an item (movie.nfo / tvshow.nfo + per-episode). */
    suspend fun getNfoFiles(id: String): NfoFileTree? = runCatching {
        httpClient.get("/api/media/$id/nfo/files").body<NfoFileTree>()
    }.getOrNull()

    /** Fetch the exact on-disk bytes of one NFO file by its server-built read URL. 404 ⇒ null. */
    suspend fun getNfoRaw(readUrl: String): String? = runCatching {
        val response = httpClient.get(readUrl)
        if (response.status == HttpStatusCode.OK) response.body<String>() else null
    }.getOrNull()

    suspend fun checkNfoWritable(id: String): NfoWritableResult? = runCatching {
        httpClient.get("/api/media/$id/nfo/writable").body<NfoWritableResult>()
    }.getOrNull()

    suspend fun writeNfo(id: String): Pair<NfoWriteResult?, String?> = runCatching {
        val response = httpClient.post("/api/media/$id/nfo")
        if (response.status == HttpStatusCode.OK) {
            Pair(response.body<NfoWriteResult>(), null)
        } else {
            @Serializable data class ErrBody(val error: String = "")
            val msg = runCatching { response.body<ErrBody>().error }.getOrElse { "" }
            Pair(null, msg.ifBlank { "HTTP ${response.status.value}" })
        }
    }.getOrElse { e -> Pair(null, e.message ?: "network error") }

    suspend fun getArtworkStatus(id: String): ArtworkStatus? = runCatching {
        httpClient.get("/api/media/$id/artwork").body<ArtworkStatus>()
    }.getOrNull()

    suspend fun fetchArtwork(id: String): ArtworkStatus? = runCatching {
        val response = httpClient.post("/api/media/$id/artwork")
        if (response.status == HttpStatusCode.OK) response.body<ArtworkStatus>() else null
    }.getOrNull()

    private fun jsonStr(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    suspend fun getArtworkCandidates(id: String, asset: String): ArtworkCandidatesResponse? = runCatching {
        httpClient.get("/api/media/$id/artwork/candidates") { parameter("asset", asset) }.body<ArtworkCandidatesResponse>()
    }.getOrNull()

    suspend fun saveArtworkCandidate(id: String, asset: String, source: String): ArtworkStatus? = runCatching {
        val response = httpClient.post("/api/media/$id/artwork/candidates/save") {
            contentType(ContentType.Application.Json)
            setBody("""{"asset":${jsonStr(asset)},"source":${jsonStr(source)}}""")
        }
        if (response.status == HttpStatusCode.OK) response.body<ArtworkStatus>() else null
    }.getOrNull()

    suspend fun getSeasons(id: String): List<SeasonStatus>? = runCatching {
        httpClient.get("/api/media/$id/seasons").body<List<SeasonStatus>>()
    }.getOrNull()

    suspend fun getSeasonPosterCandidates(id: String, season: Int): ArtworkCandidatesResponse? = runCatching {
        httpClient.get("/api/media/$id/seasons/$season/poster/candidates").body<ArtworkCandidatesResponse>()
    }.getOrNull()

    suspend fun saveSeasonPoster(id: String, season: Int, source: String): Boolean = runCatching {
        httpClient.post("/api/media/$id/seasons/$season/poster/save") {
            contentType(ContentType.Application.Json)
            setBody("""{"source":${jsonStr(source)}}""")
        }.status == HttpStatusCode.OK
    }.getOrDefault(false)

    suspend fun getEpisodeStillCandidates(id: String, epFilename: String): ArtworkCandidatesResponse? = runCatching {
        httpClient.get("/api/media/$id/episodes/${encodeURIComponent(epFilename)}/still/candidates").body<ArtworkCandidatesResponse>()
    }.getOrNull()

    suspend fun saveEpisodeStill(id: String, epFilename: String, source: String): Boolean = runCatching {
        httpClient.post("/api/media/$id/episodes/${encodeURIComponent(epFilename)}/still/save") {
            contentType(ContentType.Application.Json)
            setBody("""{"source":${jsonStr(source)}}""")
        }.status == HttpStatusCode.OK
    }.getOrDefault(false)

    // R131: generate / regenerate a screen-grab still from the episode's video frame.
    suspend fun screengrabStill(id: String, epFilename: String): EpisodeStillStatus? = runCatching {
        httpClient.post("/api/media/$id/episodes/${encodeURIComponent(epFilename)}/still/screengrab").body<EpisodeStillStatus>()
    }.getOrNull()

    suspend fun getEpisodeStillStatuses(id: String): List<EpisodeStillStatus>? = runCatching {
        httpClient.get("/api/media/$id/episodes/stills").body<List<EpisodeStillStatus>>()
    }.getOrNull()

    @Serializable
    data class TrackOpError(val error: String = "")

    @Serializable
    private data class LangWriteBody(val ok: Boolean = false, val language: String? = null)

    /** Result of a track-language write: [error] non-null on failure; [language] is the re-probed
     *  (on-disk) code on success — the client adopts this instead of its optimistic 2-letter guess. */
    data class TrackLangResult(val error: String?, val language: String?)

    /** Returns null on success, error message string on failure (including seeding guard 409/503). */
    suspend fun setDefaultTrack(id: String, specifier: String): String? = runCatching {
        val response = httpClient.post("/api/media/$id/tracks/default") {
            setBody("""{"specifier":"$specifier"}""")
            contentType(ContentType.Application.Json)
        }
        if (response.status.value in 200..299) null
        else runCatching { response.body<TrackOpError>().error }.getOrDefault("HTTP ${response.status.value}")
    }.getOrDefault("request failed")

    /** Returns null on success, error message string on failure (including seeding guard 409/503). */
    suspend fun setTrackLanguage(id: String, specifier: String, language: String): TrackLangResult = runCatching {
        val response = httpClient.post("/api/media/$id/tracks/language") {
            setBody("""{"specifier":"${specifier.replace("\"", "")}","language":"${language.replace("\"", "")}"}""")
            contentType(ContentType.Application.Json)
        }
        if (response.status.value in 200..299) {
            TrackLangResult(null, runCatching { response.body<LangWriteBody>().language }.getOrNull())
        } else {
            TrackLangResult(runCatching { response.body<TrackOpError>().error }.getOrDefault("HTTP ${response.status.value}"), null)
        }
    }.getOrDefault(TrackLangResult("request failed", null))

    suspend fun overrideLanguage(id: String, language: String): MediaItem? = runCatching {
        val response = httpClient.patch("/api/media/$id/language") {
            setBody("""{"language":"${language.replace("\"", "")}"}""")
            contentType(ContentType.Application.Json)
        }
        if (response.status == HttpStatusCode.OK) response.body<MediaItem>() else null
    }.getOrNull()

    suspend fun editMetadata(
        id: String,
        title: String? = null,
        overview: String? = null,
        year: Int? = null,
        originalTitle: String? = null,
        tags: List<String>? = null,
        genres: List<String>? = null,
        director: String? = null,
        studio: String? = null,
        network: String? = null,
    ): MediaItem? = runCatching {
        val parts = buildList {
            if (title != null) add(""""title":"${title.replace("\"", "\\\"").replace("\n", "")}"""")
            if (overview != null) add(""""overview":"${overview.replace("\"", "\\\"")}"""")
            if (year != null) add(""""year":$year""")
            if (originalTitle != null) add(""""originalTitle":"${originalTitle.replace("\"", "\\\"")}"""")
            if (tags != null) add(""""tags":[${tags.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }}]""")
            if (genres != null) add(""""genres":[${genres.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }}]""")
            if (director != null) add(""""director":"${director.replace("\"", "\\\"")}"""")
            if (studio != null) add(""""studio":"${studio.replace("\"", "\\\"")}"""")
            if (network != null) add(""""network":"${network.replace("\"", "\\\"")}"""")
        }
        val response = httpClient.patch("/api/media/$id/metadata") {
            setBody("{${parts.joinToString(",")}}")
            contentType(ContentType.Application.Json)
        }
        if (response.status == HttpStatusCode.OK) response.body<MediaItem>() else null
    }.getOrNull()

    suspend fun getTmdbLanguages(id: String): Set<String> = runCatching {
        httpClient.get("/api/media/$id/tmdb-languages").body<List<String>>().toSet()
    }.getOrElse { emptySet() }

    suspend fun syncMedia(id: String, scope: String? = null): MediaItem? = runCatching {
        val body = if (scope != null) """{"scope":"$scope"}""" else "{}"
        val response = httpClient.post("/api/media/$id/sync") {
            setBody(body)
            contentType(ContentType.Application.Json)
        }
        if (response.status == HttpStatusCode.OK) response.body<MediaItem>() else null
    }.getOrNull()

    suspend fun syncSeason(id: String, seasonNumber: Int, scope: String = "episodes"): Int? = runCatching {
        @Serializable data class R(val synced: Int)
        val response = httpClient.post("/api/media/$id/seasons/$seasonNumber/sync") {
            setBody("""{"scope":"$scope"}""")
            contentType(ContentType.Application.Json)
        }
        if (response.status == HttpStatusCode.OK) response.body<R>().synced else null
    }.getOrNull()

    suspend fun repull(id: String): MediaItem? = runCatching {
        val response = httpClient.post("/api/media/$id/repull")
        if (response.status == HttpStatusCode.OK) response.body<MediaItem>() else null
    }.getOrNull()

    // Phase 130: trailer ingest — re-run TMDB /videos + selection, or clear a manually/auto-ingested one.
    suspend fun refetchTrailer(id: String): MediaItem? = runCatching {
        val response = httpClient.post("/api/media/$id/trailer/refetch")
        if (response.status == HttpStatusCode.OK) response.body<MediaItem>() else null
    }.getOrNull()

    suspend fun clearTrailer(id: String): MediaItem? = runCatching {
        val response = httpClient.delete("/api/media/$id/trailer")
        if (response.status == HttpStatusCode.OK) response.body<MediaItem>() else null
    }.getOrNull()

    // Phase 131: manual per-title IMDb rating re-sync against imdbapi.dev.
    suspend fun syncImdbRating(id: String): MediaItem? = runCatching {
        val response = httpClient.post("/api/media/$id/imdb-rating/sync")
        if (response.status == HttpStatusCode.OK) response.body<MediaItem>() else null
    }.getOrNull()

    suspend fun getTriageCount(): TriageCount? = runCatching {
        httpClient.get("/api/triage/count").body<TriageCount>()
    }.getOrNull()

    suspend fun getJobsSummary(): JobsSummary? = runCatching {
        httpClient.get("/api/jobs").body<JobsSummary>()
    }.getOrNull()

    suspend fun cancelJob(id: String): Boolean = runCatching {
        httpClient.post("/api/jobs/$id/cancel").status.value in 200..299
    }.getOrDefault(false)

    suspend fun retryJob(id: String): Boolean = runCatching {
        httpClient.post("/api/jobs/$id/retry").status.value in 200..299
    }.getOrDefault(false)

    suspend fun getTriageItems(): List<TriageItem> = runCatching {
        httpClient.get("/api/triage").body<List<TriageItem>>()
    }.getOrDefault(emptyList())

    suspend fun getHistory(id: String): List<HistoryEntry> = runCatching {
        httpClient.get("/api/media/$id/history").body<List<HistoryEntry>>()
    }.getOrDefault(emptyList())

    suspend fun fetchEpisodeStills(id: String): Map<String, Int>? = runCatching {
        val response = httpClient.post("/api/media/$id/episodes/stills")
        if (response.status == HttpStatusCode.OK) response.body<Map<String, Int>>() else null
    }.getOrNull()

    suspend fun setEpisodeDefaultTrack(mediaId: String, epFilename: String, specifier: String): String? = runCatching {
        val encoded = encodeURIComponent(epFilename)
        val response = httpClient.post("/api/media/$mediaId/episodes/$encoded/tracks/default") {
            setBody("""{"specifier":"$specifier"}""")
            contentType(ContentType.Application.Json)
        }
        if (response.status.value in 200..299) null
        else runCatching { response.body<TrackOpError>().error }.getOrDefault("HTTP ${response.status.value}")
    }.getOrDefault("request failed")

    suspend fun setEpisodeTrackLanguage(mediaId: String, epFilename: String, specifier: String, language: String): TrackLangResult = runCatching {
        val encoded = encodeURIComponent(epFilename)
        val response = httpClient.post("/api/media/$mediaId/episodes/$encoded/tracks/language") {
            setBody("""{"specifier":"${specifier.replace("\"","")}","language":"${language.replace("\"","")}"}""")
            contentType(ContentType.Application.Json)
        }
        if (response.status.value in 200..299) {
            TrackLangResult(null, runCatching { response.body<LangWriteBody>().language }.getOrNull())
        } else {
            TrackLangResult(runCatching { response.body<TrackOpError>().error }.getOrDefault("HTTP ${response.status.value}"), null)
        }
    }.getOrDefault(TrackLangResult("request failed", null))

    // Phase 128 — mirrors dev.jellystructure.media.ProbeDiagnosis/ProbeStatus (linuxX64-only, so a
    // hand-mirrored DTO here, matching this file's existing convention for backend response shapes).
    @Serializable
    enum class ProbeStatus { OK, NO_AUDIO, CORRUPT, UNREADABLE, PROBE_MISSING, UNKNOWN }

    @Serializable
    data class ProbeDiagnosis(
        val status: ProbeStatus,
        val detail: String,
        val streamCounts: Map<TrackKind, Int> = emptyMap(),
        val managed: Boolean = false,
    )

    @Serializable
    data class ReacquireResponse(val managed: Boolean, val ok: Boolean, val detail: String)

    suspend fun diagnoseTracks(id: String): ProbeDiagnosis? = runCatching {
        httpClient.get("/api/media/$id/tracks/diagnose").body<ProbeDiagnosis>()
    }.getOrNull()

    suspend fun diagnoseEpisodeTracks(mediaId: String, epFilename: String): ProbeDiagnosis? = runCatching {
        val encoded = encodeURIComponent(epFilename)
        httpClient.get("/api/media/$mediaId/episodes/$encoded/tracks/diagnose").body<ProbeDiagnosis>()
    }.getOrNull()

    suspend fun reprobeTracks(id: String): List<Track>? = runCatching {
        val response = httpClient.post("/api/media/$id/tracks/reprobe")
        if (response.status.value in 200..299) response.body<List<Track>>() else null
    }.getOrNull()

    suspend fun reprobeEpisodeTracks(mediaId: String, epFilename: String): List<Track>? = runCatching {
        val encoded = encodeURIComponent(epFilename)
        val response = httpClient.post("/api/media/$mediaId/episodes/$encoded/tracks/reprobe")
        if (response.status.value in 200..299) response.body<List<Track>>() else null
    }.getOrNull()

    suspend fun reacquire(id: String): ReacquireResponse? = runCatching {
        httpClient.post("/api/media/$id/reacquire").body<ReacquireResponse>()
    }.getOrNull()

    suspend fun reacquireEpisode(mediaId: String, epFilename: String): ReacquireResponse? = runCatching {
        val encoded = encodeURIComponent(epFilename)
        httpClient.post("/api/media/$mediaId/episodes/$encoded/reacquire").body<ReacquireResponse>()
    }.getOrNull()

    /** Phase 109: reordering is now a queued ffmpeg-remux job — returns the job id, or null on failure. */
    suspend fun reorderTracks(id: String, kind: String, order: List<String>): String? = runCatching {
        val orderJson = order.joinToString(",") { "\"${it.replace("\"", "")}\"" }
        val response = httpClient.post("/api/media/$id/tracks/reorder") {
            setBody("""{"kind":"$kind","order":[$orderJson]}""")
            contentType(ContentType.Application.Json)
        }
        if (response.status.value !in 200..299) return@runCatching null
        @Serializable data class JobIdResp(val jobId: String)
        response.body<JobIdResp>().jobId
    }.getOrNull()

    suspend fun getRecentActivity(): List<HistoryEntry> = runCatching {
        httpClient.get("/api/activity/recent").body<List<HistoryEntry>>()
    }.getOrDefault(emptyList())

    suspend fun jellyfinRefresh(id: String): Boolean = runCatching {
        val response = httpClient.post("/api/media/$id/jellyfin-refresh")
        response.status.value in 200..299
    }.getOrDefault(false)

    suspend fun setEpisodeMetadata(mediaId: String, filename: String, title: String?, overview: String?): Boolean = runCatching {
        val encoded = encodeURIComponent(filename)
        val parts = buildList {
            if (title != null) add(""""title":"${title.replace("\"", "\\\"").replace("\n", "")}"""")
            if (overview != null) add(""""overview":"${overview.replace("\"", "\\\"")}"""")
        }
        val response = httpClient.patch("/api/media/$mediaId/episodes/$encoded/metadata") {
            setBody("{${parts.joinToString(",")}}")
            contentType(ContentType.Application.Json)
        }
        response.status.value in 200..299
    }.getOrDefault(false)

    suspend fun jellyfinRefreshAll(): Boolean = runCatching {
        val response = httpClient.post("/api/jellyfin/refresh")
        response.status.value in 200..299
    }.getOrDefault(false)

    suspend fun batchFetchArtwork(): Boolean = runCatching {
        val response = httpClient.post("/api/media/batch/artwork")
        response.status.value in 200..299
    }.getOrDefault(false)

    suspend fun batchJellyfinPush(): Boolean = runCatching {
        val response = httpClient.post("/api/media/batch/jellyfin-push")
        response.status.value in 200..299
    }.getOrDefault(false)

    suspend fun setEpisodeForcedFlag(mediaId: String, epFilename: String, specifier: String, forced: Boolean): Boolean = runCatching {
        val encoded = encodeURIComponent(epFilename)
        val response = httpClient.post("/api/media/$mediaId/episodes/$encoded/tracks/forced") {
            contentType(ContentType.Application.Json)
            setBody("""{"specifier":"${specifier.replace("\"", "")}","forced":$forced}""")
        }
        response.status.value in 200..299
    }.getOrDefault(false)

    /** Phase 109: per-episode reordering is now a queued ffmpeg-remux job — returns the job id, or null
     *  on failure. */
    suspend fun reorderEpisodeTracks(mediaId: String, epFilename: String, kind: String, order: List<String>): String? = runCatching {
        val encoded = encodeURIComponent(epFilename)
        val orderJson = order.joinToString(",") { "\"${it.replace("\"", "")}\"" }
        val response = httpClient.post("/api/media/$mediaId/episodes/$encoded/tracks/reorder") {
            setBody("""{"kind":"$kind","order":[$orderJson]}""")
            contentType(ContentType.Application.Json)
        }
        if (response.status.value !in 200..299) return@runCatching null
        @Serializable data class JobIdResp(val jobId: String)
        response.body<JobIdResp>().jobId
    }.getOrNull()

    suspend fun setForcedFlag(id: String, specifier: String, forced: Boolean): Boolean = runCatching {
        val response = httpClient.post("/api/media/$id/tracks/forced") {
            contentType(ContentType.Application.Json)
            setBody("""{"specifier":"$specifier","forced":$forced}""")
        }
        response.status.value in 200..299
    }.getOrDefault(false)

    /** @deprecated use getSeedingReport */
    suspend fun getSeedingStatus(id: String): SeedingStatus? = runCatching {
        // Legacy compatibility shim — returns unconfigured if new report is empty
        val report = httpClient.get("/api/media/$id/seeding").body<SeedingReport>()
        if (!report.guard.configured) SeedingStatus("unconfigured")
        else if (!report.reachable) SeedingStatus("unreachable")
        else if (report.torrents.any { it.state == "seeding" }) SeedingStatus("blocked", report.torrents.first { it.state == "seeding" }.name)
        else SeedingStatus("allowed")
    }.getOrNull()

    suspend fun getSeedingReport(id: String): SeedingReport? = runCatching {
        httpClient.get("/api/media/$id/seeding").body<SeedingReport>()
    }.getOrNull()

    suspend fun getTrackers(): List<TrackerEntry> = runCatching {
        httpClient.get("/api/metadata/trackers").body<List<TrackerEntry>>()
    }.getOrDefault(emptyList())

    suspend fun createTracker(name: String, private: Boolean, hosts: List<String>): Boolean = runCatching {
        httpClient.post("/api/metadata/trackers") {
            contentType(ContentType.Application.Json)
            val hostsJson = "[${hosts.joinToString(",") { jsonStr(it) }}]"
            setBody("""{"name":${jsonStr(name)},"private":$private,"hosts":$hostsJson}""")
        }.status.value in 200..299
    }.getOrDefault(false)

    suspend fun updateTracker(name: String, newName: String?, private: Boolean?, hosts: List<String>?): Boolean = runCatching {
        httpClient.put("/api/metadata/trackers/${name.encodeURL()}") {
            contentType(ContentType.Application.Json)
            val parts = buildList {
                if (newName != null) add(""""name":${jsonStr(newName)}""")
                if (private != null) add(""""private":$private""")
                if (hosts != null) add(""""hosts":[${hosts.joinToString(",") { jsonStr(it) }}]""")
            }
            setBody("{${parts.joinToString(",")}}")
        }.status.value in 200..299
    }.getOrDefault(false)

    suspend fun deleteTracker(name: String): Boolean = runCatching {
        httpClient.delete("/api/metadata/trackers/${name.encodeURL()}").status.value in 200..299
    }.getOrDefault(false)

    suspend fun getUnmappedTrackers(): List<DetectedTrackerGroup> = runCatching {
        httpClient.get("/api/metadata/trackers/unmapped").body<List<DetectedTrackerGroup>>()
    }.getOrDefault(emptyList())

    suspend fun revertHistoryEntry(id: String, entryId: String): MediaItem? = runCatching {
        httpClient.post("/api/media/$id/history/$entryId/revert").body<MediaItem>()
    }.getOrNull()

    suspend fun getDrift(id: String): DriftResult? = runCatching {
        httpClient.get("/api/media/$id/drift").body<DriftResult>()
    }.getOrNull()

    suspend fun tmdbSearch(id: String, query: String, year: Int? = null): List<TmdbMatchResult> = runCatching {
        httpClient.get("/api/media/$id/tmdb-search") {
            parameter("q", query)
            if (year != null) parameter("year", year)
        }.body<List<TmdbMatchResult>>()
    }.getOrDefault(emptyList())

    suspend fun patchCast(id: String, cast: List<Person>): MediaItem? = runCatching {
        httpClient.patch("/api/media/$id/cast") {
            contentType(ContentType.Application.Json)
            setBody(cast)
        }.body<MediaItem>()
    }.getOrNull()

    suspend fun patchCrew(id: String, crew: List<Person>): MediaItem? = runCatching {
        httpClient.patch("/api/media/$id/crew") {
            contentType(ContentType.Application.Json)
            setBody(crew)
        }.body<MediaItem>()
    }.getOrNull()

    suspend fun fetchCastFromTmdb(id: String): MediaItem? = runCatching {
        httpClient.post("/api/media/$id/cast/fetch").body<MediaItem>()
    }.getOrNull()

    suspend fun patchEpisodeCast(id: String, filename: String, guests: List<Person>): MediaItem? = runCatching {
        val encoded = encodeURIComponent(filename)
        httpClient.patch("/api/media/$id/episodes/$encoded/cast") {
            contentType(ContentType.Application.Json)
            setBody(guests)
        }.body<MediaItem>()
    }.getOrNull()

    suspend fun patchEpisodeCrew(id: String, filename: String, crew: List<Person>): MediaItem? = runCatching {
        val encoded = encodeURIComponent(filename)
        httpClient.patch("/api/media/$id/episodes/$encoded/crew") {
            contentType(ContentType.Application.Json)
            setBody(crew)
        }.body<MediaItem>()
    }.getOrNull()

    suspend fun fetchEpisodeCastFromTmdb(id: String, filename: String): MediaItem? = runCatching {
        val encoded = encodeURIComponent(filename)
        httpClient.post("/api/media/$id/episodes/$encoded/cast/fetch").body<MediaItem>()
    }.getOrNull()

    suspend fun searchPeople(query: String): List<PersonSearchResult> = runCatching {
        httpClient.get("/api/people/search") {
            parameter("q", query)
        }.body<List<PersonSearchResult>>()
    }.getOrDefault(emptyList())

    // Phase 96 — bulk track reorder
    suspend fun bulkReorderPlan(mediaId: String, kind: String, scope: String, order: List<String>, setDefault: Boolean): BulkPlanResponse? = runCatching {
        val orderJson = order.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }
        httpClient.post("/api/media/$mediaId/tracks/bulk-reorder/plan") {
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"$kind","scope":"$scope","order":[$orderJson],"setDefault":$setDefault}""")
        }.body<BulkPlanResponse>()
    }.getOrNull()

    suspend fun startBulkReorder(mediaId: String, kind: String, scope: String, order: List<String>, setDefault: Boolean, optIn: List<String>): String? = runCatching {
        val orderJson = order.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }
        val optInJson = optIn.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }
        val body = httpClient.post("/api/media/$mediaId/tracks/bulk-reorder") {
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"$kind","scope":"$scope","order":[$orderJson],"setDefault":$setDefault,"optIn":[$optInJson]}""")
        }.body<Map<String, String>>()
        body["jobId"]
    }.getOrNull()
}

@Serializable
data class PersonSearchResult(
    val tmdbId: Int,
    val name: String,
    val profilePath: String? = null,
    val knownForDepartment: String = "",
)

@Serializable
data class SeedingStatus(val status: String, val torrentName: String? = null, val detail: String? = null)

@Serializable
data class TorrentCoversDto(val all: Boolean = false, val s: Int? = null, val e: Int? = null)

@Serializable
data class TorrentRef(
    val hash: String,
    val name: String,
    val announce: List<String>,
    val state: String,
    val ratio: Double,
    val seeders: Int,
    val leechers: Int,
    val uploaded: String,
    val added: String,
    val seedTime: String,
    val scope: String,
    val covers: TorrentCoversDto? = null,
    val xseed: String? = null,
    val xseedNote: String? = null,
    val error: String? = null,
)

@Serializable
data class GuardStatusDto(val configured: Boolean, val reachable: Boolean)

@Serializable
data class SeasonInfoDto(val n: Int, val episodes: Int)

@Serializable
data class SeedingReport(
    val guard: GuardStatusDto,
    val torrents: List<TorrentRef>,
    val takenAt: Long = 0L,
    val ttl: Long = 600L,
    val reachable: Boolean = false,
    val seasons: List<SeasonInfoDto> = emptyList(),
)

@Serializable
data class TrackerEntry(
    val name: String,
    @SerialName("private") val isPrivate: Boolean = false,
    val hosts: List<String> = emptyList(),
    val torrentCount: Int = 0,
)

@Serializable
data class DetectedTrackerGroup(val hosts: List<String>, val torrentCount: Int)

@Serializable
data class DriftField(val field: String, val inJellyfin: String, val inDb: String)

// Phase 115 — three-state sync evaluation: "nfo_stale" | "jellyfin_behind" | "external_drift" | "converged".
@Serializable
data class DriftResult(val state: String, val message: String, val fields: List<DriftField> = emptyList())

@Serializable
data class TmdbMatchResult(
    val id: Int,
    val title: String,
    val year: String,
    val posterPath: String? = null,
    val overview: String = "",
)

// Phase 96 — bulk track reorder DTOs
@Serializable
data class BulkTrackSummary(
    val specifier: String,
    val language: String? = null,
    val title: String? = null,
    val isDefault: Boolean = false,
    val isStray: Boolean = false,
)

@Serializable
data class BulkPlanEpisode(
    val filename: String,
    val code: String,
    val title: String? = null,
    val status: String, // will_reorder | already_correct | partial | needs_review | nothing_to_do
    val currentOrder: List<BulkTrackSummary> = emptyList(),
    val proposedOrder: List<BulkTrackSummary> = emptyList(),
    val reason: String = "",
    val estSeconds: Double = 0.0,
    val remux: Boolean = false,
)

@Serializable
data class BulkPlanResponse(
    val episodes: List<BulkPlanEpisode> = emptyList(),
    val scopeCount: Int = 0,
    val willReorder: Int = 0,
    val alreadyCorrect: Int = 0,
    val partial: Int = 0,
    val needsReview: Int = 0,
    val nothingToDo: Int = 0,
    val totalEstSeconds: Double = 0.0,
    val remuxCount: Int = 0,
)
