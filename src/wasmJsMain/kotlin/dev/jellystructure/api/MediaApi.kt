package dev.jellystructure.api

import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.MediaPage
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
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
    val issues: Int,
    val nfoCoverage: Int = 0,
)

@Serializable
data class ArtworkStatus(val posterExists: Boolean, val fanartExists: Boolean)

@Serializable
data class TrackPlan(
    val command: String,
    val tool: String,
    val estimatedMs: Int,
    val targetSpecifier: String,
)

@Serializable
data class NfoWriteResult(val path: String)

@Serializable
data class ScanStatus(val running: Boolean, val lastCount: Int? = null)

object MediaApi {
    suspend fun list(
        kind: MediaKind? = null,
        filter: String? = null,
        page: Int = 1,
        pageSize: Int = 20,
    ): MediaPage? = runCatching {
        httpClient.get("/api/media") {
            if (kind != null) parameter("kind", kind.name)
            if (filter != null) parameter("filter", filter)
            parameter("page", page)
            parameter("pageSize", pageSize)
        }.body<MediaPage>()
    }.getOrNull()

    suspend fun get(id: String): MediaItem? = runCatching {
        httpClient.get("/api/media/$id").body<MediaItem>()
    }.getOrNull()

    // Returns true if the scan was successfully started, false if already running or failed.
    suspend fun startScan(): Boolean = runCatching {
        val response = httpClient.post("/api/scan")
        response.status == HttpStatusCode.Accepted
    }.getOrDefault(false)

    suspend fun scanStatus(): ScanStatus? = runCatching {
        httpClient.get("/api/scan/status").body<ScanStatus>()
    }.getOrNull()

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

    suspend fun writeNfo(id: String): NfoWriteResult? = runCatching {
        val response = httpClient.post("/api/media/$id/nfo")
        if (response.status == HttpStatusCode.OK) response.body<NfoWriteResult>() else null
    }.getOrNull()

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

    suspend fun setDefaultTrack(id: String, specifier: String): Boolean = runCatching {
        val response = httpClient.post("/api/media/$id/tracks/default") {
            setBody("""{"specifier":"$specifier"}""")
            contentType(io.ktor.http.ContentType.Application.Json)
        }
        response.status.value in 200..299
    }.getOrDefault(false)

    suspend fun repull(id: String): MediaItem? = runCatching {
        val response = httpClient.post("/api/media/$id/repull")
        if (response.status == HttpStatusCode.OK) response.body<MediaItem>() else null
    }.getOrNull()
}
