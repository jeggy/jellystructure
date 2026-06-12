package dev.jellystructure.api

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
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
    val year: Int?,
    val kind: MediaKind,
    val path: String,
    val tmdbId: Int?,
    val originalLanguage: String?,
    val posterPath: String?,
    val overview: String?,
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
data class StatsResponse(
    val movies: Int,
    val issues: Int,
)

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

    suspend fun scan(): Int = runCatching {
        val response = httpClient.post("/api/scan")
        if (response.status == HttpStatusCode.OK) {
            response.body<Map<String, Int>>()["scanned"] ?: 0
        } else 0
    }.getOrDefault(0)

    suspend fun stats(): StatsResponse? = runCatching {
        httpClient.get("/api/stats").body<StatsResponse>()
    }.getOrNull()
}
