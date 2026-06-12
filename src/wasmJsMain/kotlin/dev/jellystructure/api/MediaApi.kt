package dev.jellystructure.api

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MediaKind { MOVIE, TV_SHOW }

@Serializable
enum class TrackKind { VIDEO, AUDIO, SUBTITLE, DATA }

@Serializable
data class Track(
    val streamIndex: Int,
    val specifier: String,
    val kind: TrackKind,
    val codec: String,
    val language: String?,
    val title: String?,
    val default: Boolean,
    val forced: Boolean,
)

@Serializable
data class MediaItem(
    val id: String,
    val title: String,
    val originalTitle: String? = null,
    val year: Int?,
    val kind: MediaKind,
    val path: String,
    val tmdbId: Int?,
    val originalLanguage: String?,
    val resolvedLanguage: String? = null,
    val posterPath: String?,
    val backdropPath: String? = null,
    val overview: String?,
    val genres: List<String> = emptyList(),
    val tracks: List<Track>,
    val issueCount: Int,
    val scannedAt: Long,
)

@Serializable
data class MediaPage(
    val items: List<MediaItem>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
)

@Serializable
data class StatsResponse(val movies: Int, val issues: Int)

@Serializable
data class ArtworkStatus(val posterExists: Boolean, val fanartExists: Boolean)

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
}
