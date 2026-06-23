package dev.jellystructure.arr

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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
}
