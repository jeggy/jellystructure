package dev.jellystructure.api

import dev.jellystructure.encodeURIComponent
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.MediaPage
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

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

@Serializable
data class NfoWriteResult(val path: String)

@Serializable
data class NfoWritableResult(val writable: Boolean, val path: String, val error: String? = null)

@Serializable
data class TriageCount(val untagged: Int, val mismatch: Int, val total: Int)

@Serializable
data class TriageTrack(val specifier: String, val streamIndex: Int, val kind: String, val codec: String, val title: String? = null)

@Serializable
data class CascadeMismatch(val resolvedLanguage: String, val expectedDefaultSpecifier: String, val actualDefaultLang: String? = null)

@Serializable
data class MultiDefaultIssue(val defaultSpecifiers: List<String>)

@Serializable
data class EpisodeTriageItem(
    val filename: String, val episodeCode: String, val title: String? = null,
    val untaggedTracks: List<TriageTrack>, val missingOverview: Boolean, val multiDefault: MultiDefaultIssue? = null,
)

@Serializable
data class TriageItem(
    val mediaId: String, val title: String, val year: Int? = null, val path: String,
    val kind: String = "movie", val posterPath: String? = null, val originalLanguage: String? = null,
    val untaggedTracks: List<TriageTrack>, val cascadeMismatch: CascadeMismatch? = null,
    val episodeIssues: List<EpisodeTriageItem> = emptyList(), val resolvedLanguage: String? = null,
    val languageMix: Boolean = false, val multiDefault: MultiDefaultIssue? = null,
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
data class TrackFacetItem(val value: String, val count: Int)

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
)

@Serializable
data class ScanStatus(
    val running: Boolean,
    val status: String = "IDLE",
    val jobId: String? = null,
    val startedAt: Long? = null,
    val processedCount: Int = 0,
    val activeWorkers: Int = 0,
    val configuredWorkers: Int = 1,
)

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
    ): MediaPage? = runCatching {
        httpClient.get("/api/media") {
            if (kind != null) parameter("kind", kind.name)
            if (filter != null) parameter("filter", filter)
            if (!search.isNullOrBlank()) parameter("search", search)
            if (!sort.isNullOrBlank()) parameter("sort", sort)
            parameter("page", page)
            parameter("pageSize", pageSize)
            if (studios.isNotEmpty()) parameter("studios", studios.joinToString(","))
            if (networks.isNotEmpty()) parameter("networks", networks.joinToString(","))
            if (genres.isNotEmpty()) parameter("genres", genres.joinToString(","))
            if (audioLangs.isNotEmpty()) parameter("audioLang", audioLangs.joinToString(","))
            if (!trackTitle.isNullOrBlank()) parameter("trackTitle", trackTitle)
            if (!audioCodec.isNullOrBlank()) parameter("audioCodec", audioCodec)
            if (untaggedAudio) parameter("untaggedAudio", "true")
            if (tags.isNotEmpty()) parameter("tags", tags.joinToString(","))
        }.body<MediaPage>()
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
    suspend fun startScan(): Boolean = runCatching {
        val response = httpClient.post("/api/scan")
        response.status == HttpStatusCode.Accepted
    }.getOrDefault(false)

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

    suspend fun getNfo(id: String): String? = runCatching {
        val response = httpClient.get("/api/media/$id/nfo")
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

    suspend fun getTrackPlan(id: String, specifier: String): TrackPlan? = runCatching {
        httpClient.get("/api/media/$id/tracks/plan") {
            parameter("specifier", specifier)
        }.body<TrackPlan>()
    }.getOrNull()

    @Serializable
    data class TrackOpError(val error: String = "")

    /** Returns null on success, error message string on failure (including seeding guard 409/503). */
    suspend fun setDefaultTrack(id: String, specifier: String): String? = runCatching {
        val response = httpClient.post("/api/media/$id/tracks/default") {
            setBody("""{"specifier":"$specifier"}""")
            contentType(io.ktor.http.ContentType.Application.Json)
        }
        if (response.status.value in 200..299) null
        else runCatching { response.body<TrackOpError>().error }.getOrDefault("HTTP ${response.status.value}")
    }.getOrDefault("request failed")

    /** Returns null on success, error message string on failure (including seeding guard 409/503). */
    suspend fun setTrackLanguage(id: String, specifier: String, language: String): String? = runCatching {
        val response = httpClient.post("/api/media/$id/tracks/language") {
            setBody("""{"specifier":"${specifier.replace("\"", "")}","language":"${language.replace("\"", "")}"}""")
            contentType(ContentType.Application.Json)
        }
        if (response.status.value in 200..299) null
        else runCatching { response.body<TrackOpError>().error }.getOrDefault("HTTP ${response.status.value}")
    }.getOrDefault("request failed")

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

    suspend fun getTriageCount(): TriageCount? = runCatching {
        httpClient.get("/api/triage/count").body<TriageCount>()
    }.getOrNull()

    suspend fun getTriageItems(): List<TriageItem> = runCatching {
        httpClient.get("/api/triage").body<List<TriageItem>>()
    }.getOrDefault(emptyList())

    suspend fun getHistory(id: String): List<HistoryEntry> = runCatching {
        httpClient.get("/api/media/$id/history").body<List<HistoryEntry>>()
    }.getOrDefault(emptyList())

    suspend fun writeEpisodeNfos(id: String): Map<String, Int>? = runCatching {
        val response = httpClient.post("/api/media/$id/episodes/nfo")
        if (response.status == HttpStatusCode.OK) response.body<Map<String, Int>>() else null
    }.getOrNull()

    suspend fun fetchEpisodeStills(id: String): Map<String, Int>? = runCatching {
        val response = httpClient.post("/api/media/$id/episodes/stills")
        if (response.status == HttpStatusCode.OK) response.body<Map<String, Int>>() else null
    }.getOrNull()

    suspend fun getEpisodeTrackPlan(mediaId: String, epFilename: String, specifier: String): TrackPlan? = runCatching {
        val encoded = encodeURIComponent(epFilename)
        httpClient.get("/api/media/$mediaId/episodes/$encoded/tracks/plan") {
            parameter("specifier", specifier)
        }.body<TrackPlan>()
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

    suspend fun setEpisodeTrackLanguage(mediaId: String, epFilename: String, specifier: String, language: String): String? = runCatching {
        val encoded = encodeURIComponent(epFilename)
        val response = httpClient.post("/api/media/$mediaId/episodes/$encoded/tracks/language") {
            setBody("""{"specifier":"${specifier.replace("\"","")}","language":"${language.replace("\"","")}"}""")
            contentType(ContentType.Application.Json)
        }
        if (response.status.value in 200..299) null
        else runCatching { response.body<TrackOpError>().error }.getOrDefault("HTTP ${response.status.value}")
    }.getOrDefault("request failed")

    suspend fun removeTrack(id: String, specifier: String): Boolean = runCatching {
        val encoded = encodeURIComponent(specifier)
        val response = httpClient.delete("/api/media/$id/tracks/$encoded")
        response.status.value in 200..299
    }.getOrDefault(false)

    suspend fun reorderTracks(id: String, kind: String, order: List<String>): Boolean = runCatching {
        val orderJson = order.joinToString(",") { "\"${it.replace("\"", "")}\"" }
        val response = httpClient.post("/api/media/$id/tracks/reorder") {
            setBody("""{"kind":"$kind","order":[$orderJson]}""")
            contentType(ContentType.Application.Json)
        }
        response.status.value in 200..299
    }.getOrDefault(false)

    suspend fun getRecentActivity(): List<HistoryEntry> = runCatching {
        httpClient.get("/api/activity/recent").body<List<HistoryEntry>>()
    }.getOrDefault(emptyList())

    suspend fun getTriageSuggestion(mediaId: String): String? = runCatching {
        @Serializable data class SuggestResp(val language: String?)
        httpClient.get("/api/triage/$mediaId/suggest").body<SuggestResp>().language
    }.getOrNull()

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

    suspend fun revertHistoryEntry(id: String, entryId: String): MediaItem? = runCatching {
        httpClient.post("/api/media/$id/history/$entryId/revert").body<MediaItem>()
    }.getOrNull()

    suspend fun getDrift(id: String): List<DriftField> = runCatching {
        httpClient.get("/api/media/$id/drift").body<List<DriftField>>()
    }.getOrDefault(emptyList())

    suspend fun tmdbSearch(id: String, query: String, year: Int? = null): List<TmdbMatchResult> = runCatching {
        httpClient.get("/api/media/$id/tmdb-search") {
            parameter("q", query)
            if (year != null) parameter("year", year)
        }.body<List<TmdbMatchResult>>()
    }.getOrDefault(emptyList())
}

@Serializable
data class DriftField(val field: String, val inJellyfin: String, val inDb: String)

@Serializable
data class TmdbMatchResult(
    val id: Int,
    val title: String,
    val year: String,
    val posterPath: String? = null,
    val overview: String = "",
)
