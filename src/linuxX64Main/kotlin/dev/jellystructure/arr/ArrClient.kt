package dev.jellystructure.arr

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

@Serializable
data class ArrSystemStatus(val version: String = "", val appName: String = "")

@Serializable
private data class ArrRootFolder(val path: String = "")

@Serializable
private data class ArrMovieRef(val id: Int = 0, val tmdbId: Int = 0, val path: String = "")

@Serializable
private data class ArrSeriesRef(val id: Int = 0, val path: String = "")

/** Outcome of a connection probe. */
data class ArrPing(val ok: Boolean, val detail: String, val version: String? = null)

/**
 * Phase 54 — a tiny shared client for Radarr & Sonarr. Their v3 API is identical for the calls we
 * make. **Read + rescan only**: we never call any add/grab/delete endpoint here (that scope fence
 * belongs to Phase 56's acquisition engine). All methods take `url`/`apiKey` explicitly so the same
 * client serves both the test flow (temporary creds) and the post-write hook (stored config).
 */
class ArrClient {
    private val http = HttpClient(Curl) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            socketTimeoutMillis  = 30_000
            requestTimeoutMillis = 30_000
        }
    }

    private fun base(url: String) = url.trimEnd('/') + "/api/v3"

    suspend fun ping(url: String, apiKey: String): ArrPing = runCatching {
        val status: ArrSystemStatus = http.get(base(url) + "/system/status") {
            header("X-Api-Key", apiKey)
        }.body()
        ArrPing(true, "Connected", status.version.ifBlank { null })
    }.getOrElse { e -> ArrPing(false, e.message ?: "Unknown error") }

    suspend fun rootFolders(url: String, apiKey: String): List<String> = runCatching {
        val folders: List<ArrRootFolder> = http.get(base(url) + "/rootfolder") {
            header("X-Api-Key", apiKey)
        }.body()
        folders.map { it.path }.filter { it.isNotBlank() }
    }.getOrElse { emptyList() }

    /** Radarr: resolve a movie's `movieId` by its TMDB id. */
    suspend fun findMovieId(url: String, apiKey: String, tmdbId: Int): Int? = runCatching {
        val movies: List<ArrMovieRef> = http.get(base(url) + "/movie") {
            header("X-Api-Key", apiKey)
            parameter("tmdbId", tmdbId)
        }.body()
        // Radarr honours ?tmdbId= (verified live: 1 hit for a present movie, 0 for absent), but we still
        // match exactly — never fall back to "first movie", so a server that ignored the filter and
        // returned the whole library could never make us rescan the wrong title.
        movies.firstOrNull { it.tmdbId == tmdbId }?.id
    }.getOrNull()

    /**
     * Sonarr: resolve a series' `seriesId` by matching its folder path. Our [path] is an episode file
     * inside the series folder, so we take the longest series `path` that is a prefix of it.
     */
    suspend fun findSeriesIdByPath(url: String, apiKey: String, path: String): Int? = runCatching {
        val series: List<ArrSeriesRef> = http.get(base(url) + "/series") {
            header("X-Api-Key", apiKey)
        }.body()
        series.filter { it.path.isNotBlank() && (path == it.path || path.startsWith(it.path.trimEnd('/') + "/")) }
            .maxByOrNull { it.path.length }?.id
    }.getOrNull()

    suspend fun rescanMovie(url: String, apiKey: String, movieId: Int): Boolean =
        command(url, apiKey, """{"name":"RescanMovie","movieId":$movieId}""")

    suspend fun rescanSeries(url: String, apiKey: String, seriesId: Int): Boolean =
        command(url, apiKey, """{"name":"RescanSeries","seriesId":$seriesId}""")

    private suspend fun command(url: String, apiKey: String, body: String): Boolean = runCatching {
        val resp = http.post(base(url) + "/command") {
            header("X-Api-Key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        resp.status == HttpStatusCode.Created || resp.status == HttpStatusCode.OK || resp.status == HttpStatusCode.Accepted
    }.getOrElse { false }

    // ---- Phase 56: acquisition (add + search, queue, cancel) ----

    suspend fun getQualityProfiles(url: String, apiKey: String): List<ArrQualityProfile> = runCatching {
        http.get(base(url) + "/qualityprofile") { header("X-Api-Key", apiKey) }.body<List<ArrQualityProfile>>()
    }.getOrElse { emptyList() }

    /** Resolve a profile name to its id; blank name → the *arr's first/default profile. */
    suspend fun resolveQualityProfileId(url: String, apiKey: String, name: String): Int? {
        val profiles = getQualityProfiles(url, apiKey)
        if (profiles.isEmpty()) return null
        return if (name.isBlank()) profiles.first().id
        else profiles.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id ?: profiles.first().id
    }

    /** Resolve the root folder: configured value, else the sole root; null = ambiguous (>1 root, none set). */
    suspend fun resolveRootFolder(url: String, apiKey: String, configured: String): String? {
        if (configured.isNotBlank()) return configured
        val roots = rootFolders(url, apiKey)
        return if (roots.size == 1) roots.first() else null
    }

    /** Radarr: add by TMDB id + start a search. Returns the new movieId, or null. */
    suspend fun addMovie(url: String, apiKey: String, tmdbId: Int, rootFolder: String, qualityProfileId: Int): Int? = runCatching {
        val lookups = http.get(base(url) + "/movie/lookup") {
            header("X-Api-Key", apiKey); parameter("term", "tmdb:$tmdbId")
        }.body<JsonArray>()
        val movie = lookups.map { it.jsonObject }.firstOrNull { it["tmdbId"]?.jsonPrimitive?.intOrNull == tmdbId }
            ?: lookups.firstOrNull()?.jsonObject ?: return null
        val payload = buildJsonObject {
            movie.forEach { (k, v) -> put(k, v) }
            put("qualityProfileId", qualityProfileId)
            put("rootFolderPath", rootFolder)
            put("monitored", true)
            put("addOptions", buildJsonObject { put("searchForMovie", true) })
        }
        val resp = http.post(base(url) + "/movie") {
            header("X-Api-Key", apiKey); contentType(ContentType.Application.Json); setBody(payload.toString())
        }
        if (resp.status == HttpStatusCode.Created || resp.status == HttpStatusCode.OK)
            resp.body<JsonObject>()["id"]?.jsonPrimitive?.intOrNull else null
    }.getOrNull()

    /** Sonarr: add by TheTVDB id (bridge tmdb→tvdb first) + monitor scope + search. Returns seriesId. */
    suspend fun addSeries(url: String, apiKey: String, tvdbId: Int, rootFolder: String, qualityProfileId: Int, monitor: String, seasonFolder: Boolean): Int? = runCatching {
        val lookups = http.get(base(url) + "/series/lookup") {
            header("X-Api-Key", apiKey); parameter("term", "tvdb:$tvdbId")
        }.body<JsonArray>()
        val series = lookups.map { it.jsonObject }.firstOrNull { it["tvdbId"]?.jsonPrimitive?.intOrNull == tvdbId }
            ?: lookups.firstOrNull()?.jsonObject ?: return null
        val payload = buildJsonObject {
            series.forEach { (k, v) -> put(k, v) }
            put("qualityProfileId", qualityProfileId)
            put("rootFolderPath", rootFolder)
            put("monitored", true)
            put("seasonFolder", seasonFolder)
            put("addOptions", buildJsonObject {
                put("monitor", monitor)
                put("searchForMissingEpisodes", true)
                put("searchForCutoffUnmetEpisodes", false)
            })
        }
        val resp = http.post(base(url) + "/series") {
            header("X-Api-Key", apiKey); contentType(ContentType.Application.Json); setBody(payload.toString())
        }
        if (resp.status == HttpStatusCode.Created || resp.status == HttpStatusCode.OK)
            resp.body<JsonObject>()["id"]?.jsonPrimitive?.intOrNull else null
    }.getOrNull()

    /** The *arr download queue (Radarr movies + Sonarr episodes), normalized. */
    suspend fun getQueue(url: String, apiKey: String): List<ArrQueueItem> = runCatching {
        val obj = http.get(base(url) + "/queue") {
            header("X-Api-Key", apiKey); parameter("pageSize", 200); parameter("includeEpisode", true)
        }.body<JsonObject>()
        val records = obj["records"]?.jsonArray ?: return emptyList()
        records.map { it.jsonObject }.mapNotNull { r ->
            val refId = (r["movieId"] ?: r["seriesId"])?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val ep = r["episode"]?.jsonObject
            ArrQueueItem(
                id = r["id"]?.jsonPrimitive?.longOrNull ?: 0L,
                refId = refId,
                downloadId = r["downloadId"]?.jsonPrimitive?.contentOrNull,
                status = r["status"]?.jsonPrimitive?.contentOrNull ?: "",
                trackedState = r["trackedDownloadState"]?.jsonPrimitive?.contentOrNull ?: "",
                trackedStatus = r["trackedDownloadStatus"]?.jsonPrimitive?.contentOrNull ?: "",
                sizeLeft = r["sizeleft"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                size = r["size"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                timeLeft = r["timeleft"]?.jsonPrimitive?.contentOrNull,
                season = ep?.get("seasonNumber")?.jsonPrimitive?.intOrNull,
                episode = ep?.get("episodeNumber")?.jsonPrimitive?.intOrNull,
                errorMessage = r["errorMessage"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }.getOrElse { emptyList() }

    /** Sonarr: monitored/aired/hasFile state per episode, for the series roll-up. */
    suspend fun getSeriesEpisodes(url: String, apiKey: String, seriesId: Int): List<ArrEpisode> = runCatching {
        http.get(base(url) + "/episode") {
            header("X-Api-Key", apiKey); parameter("seriesId", seriesId)
        }.body<List<ArrEpisode>>()
    }.getOrElse { emptyList() }

    suspend fun deleteMovie(url: String, apiKey: String, movieId: Int): Boolean =
        del(url, apiKey, "/movie/$movieId?deleteFiles=false&addImportExclusion=false")

    suspend fun deleteSeries(url: String, apiKey: String, seriesId: Int): Boolean =
        del(url, apiKey, "/series/$seriesId?deleteFiles=false&addImportExclusion=false")

    suspend fun deleteQueueItem(url: String, apiKey: String, queueId: Long): Boolean =
        del(url, apiKey, "/queue/$queueId?removeFromClient=true&blocklist=false")

    private suspend fun del(url: String, apiKey: String, path: String): Boolean = runCatching {
        val resp = http.delete(base(url) + path) { header("X-Api-Key", apiKey) }
        resp.status == HttpStatusCode.OK || resp.status == HttpStatusCode.NoContent || resp.status == HttpStatusCode.Accepted
    }.getOrElse { false }
}

@Serializable
data class ArrQualityProfile(val id: Int = 0, val name: String = "")

@Serializable
data class ArrEpisode(
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val monitored: Boolean = false,
    val hasFile: Boolean = false,
    val airDateUtc: String? = null,
)

/** Unified download-queue item across Radarr (movieId) and Sonarr (seriesId + episode). */
data class ArrQueueItem(
    val id: Long,
    val refId: Int,
    val downloadId: String?,
    val status: String,
    val trackedState: String,
    val trackedStatus: String,
    val sizeLeft: Double,
    val size: Double,
    val timeLeft: String?,
    val season: Int?,
    val episode: Int?,
    val errorMessage: String?,
)
