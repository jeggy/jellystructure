package dev.jellystructure.auth

import dev.jellystructure.log.Logger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

private const val DEVICE_ID = "jellystructure-server-v01"
private const val AUTH_HEADER =
    """MediaBrowser Client="Jellystructure", Device="Server", DeviceId="$DEVICE_ID", Version="0.1.0""""

class JellyfinClient {
    private val http = HttpClient(Curl) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    suspend fun authenticateByName(
        baseUrl: String,
        username: String,
        password: String,
    ): JellyfinAuthResponse {
        val url = baseUrl.trimEnd('/') + "/Users/AuthenticateByName"
        val response = http.post(url) {
            header("Authorization", AUTH_HEADER)
            contentType(ContentType.Application.Json)
            setBody("""{"Username":${username.jsonEscape()},"Pw":${password.jsonEscape()}}""")
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            throw IllegalArgumentException("Invalid Jellyfin credentials")
        }
        if (!response.status.value.toString().startsWith("2")) {
            throw IllegalStateException("Jellyfin returned ${response.status.value}")
        }
        return response.body()
    }

    suspend fun testConnection(baseUrl: String, token: String): Boolean = runCatching {
        val url = baseUrl.trimEnd('/') + "/System/Info/Public"
        val response = http.get(url) { jellyfinAuth(token) }
        response.status.value in 200..299
    }.getOrDefault(false)

    /**
     * True if [token] is a Jellyfin access token that can still act as [userId]. Used to detect a
     * stale **paired** TV user token (Jellyfin 401s it) so the TV can fall back to the long-lived
     * server token. `/Users/{userId}` is an authenticated endpoint — public `/System/Info/Public`
     * would 200 even for an invalid token, so it can't be used here.
     */
    suspend fun isTokenValid(baseUrl: String, token: String, userId: String): Boolean = runCatching {
        if (token.isBlank()) return false
        http.get(baseUrl.trimEnd('/') + "/Users/$userId") { jellyfinAuth(token) }.status.isSuccess()
    }.getOrDefault(false)

    suspend fun getUsers(baseUrl: String, token: String): List<JellyfinUser> = runCatching {
        http.get(baseUrl.trimEnd('/') + "/Users") { jellyfinAuth(token) }
            .bodyOrNull<List<JellyfinUser>>("getUsers").orEmpty()
    }.getOrDefault(emptyList())

    suspend fun getLibraries(baseUrl: String, token: String): List<JellyfinLibrary> = runCatching {
        val url = baseUrl.trimEnd('/') + "/Library/VirtualFolders"
        http.get(url) { jellyfinAuth(token) }
            .bodyOrNull<List<JellyfinLibrary>>("getLibraries").orEmpty()
    }.getOrDefault(emptyList())

    suspend fun getItems(baseUrl: String, token: String): List<JellyfinItem> = runCatching {
        val url = baseUrl.trimEnd('/') +
            "/Items?IncludeItemTypes=Movie,Series&Recursive=true&Fields=Path,ProviderIds,ProductionYear,LockData,LockedFields,Tags"
        http.get(url) { jellyfinAuth(token) }
            .bodyOrNull<JellyfinItemsResponse>("getItems")?.items.orEmpty()
            .filter { it.type == "Movie" || it.type == "Series" }
    }.let { result ->
        if (result.isFailure) Logger.warn("Jellyfin getItems failed: ${result.exceptionOrNull()?.message}")
        result.getOrDefault(emptyList())
    }

    suspend fun getItemsByParent(baseUrl: String, token: String, parentId: String): List<JellyfinItem> = runCatching {
        val url = baseUrl.trimEnd('/') +
            "/Items?ParentId=$parentId&IncludeItemTypes=Movie,Series&Recursive=true&Fields=Path,ProviderIds,ProductionYear,LockData,LockedFields,Tags"
        http.get(url) { jellyfinAuth(token) }
            .bodyOrNull<JellyfinItemsResponse>("getItemsByParent")?.items.orEmpty()
            .filter { it.type == "Movie" || it.type == "Series" }
    }.let { result ->
        if (result.isFailure) Logger.warn("Jellyfin getItemsByParent failed: ${result.exceptionOrNull()?.message}")
        result.getOrDefault(emptyList())
    }

    suspend fun getItem(baseUrl: String, token: String, jellyfinId: String): JellyfinItem? = runCatching {
        // Fetch via the same list-endpoint shape getItems uses, filtered to one id. The
        // non-user-scoped single-item route `/Items/{id}` 400s with a server token across Jellyfin
        // versions (it expects `/Users/{userId}/Items/{id}`); the `Ids=` filter on the list endpoint
        // is accepted with the same token + Fields (incl. LockData/LockedFields for Phase 22).
        val url = baseUrl.trimEnd('/') +
            "/Items?Ids=$jellyfinId&Recursive=true&Fields=Path,ProviderIds,ProductionYear,LockData,LockedFields,Tags"
        http.get(url) { jellyfinAuth(token) }
            .bodyOrNull<JellyfinItemsResponse>("getItem")?.items?.firstOrNull()
    }.let { result ->
        if (result.isFailure) Logger.warn("Jellyfin getItem failed: ${result.exceptionOrNull()?.message}")
        result.getOrNull()
    }

    suspend fun refreshItem(baseUrl: String, token: String, jellyfinId: String, full: Boolean = false): Boolean = runCatching {
        val mode = if (full) "FullRefresh" else "ValidationOnly"
        val extra = if (full) "&Recursive=true&ReplaceAllMetadata=true" else ""
        val url = baseUrl.trimEnd('/') +
            "/Items/$jellyfinId/Refresh?MetadataRefreshMode=$mode&ImageRefreshMode=$mode$extra"
        val response = http.post(url) { jellyfinAuth(token) }
        Logger.info("Jellyfin item refresh $jellyfinId (${if (full) "full/recursive" else "validation"}): ${response.status.value}")
        response.status.value in 200..299
    }.getOrDefault(false)

    suspend fun triggerLibraryRefresh(baseUrl: String, token: String): Boolean = runCatching {
        val url = baseUrl.trimEnd('/') + "/Library/Refresh"
        val response = http.post(url) { jellyfinAuth(token) }
        response.status.value in 200..299
    }.getOrDefault(false)

    suspend fun getResumeItems(
        baseUrl: String,
        userToken: String,
        userId: String,
        limit: Int = 20,
    ): List<JellyfinPlayItem> = runCatching {
        val url = baseUrl.trimEnd('/') +
            "/Users/$userId/Items?Filters=IsResumable&Recursive=true" +
            "&IncludeItemTypes=Movie,Episode&Limit=$limit" +
            "&SortBy=DatePlayed&SortOrder=Descending" +
            "&Fields=UserData,SeriesId,SeriesName,SeasonId,IndexNumber,ParentIndexNumber"
        http.get(url) { jellyfinAuth(userToken) }
            .bodyOrNull<JellyfinPlayItemsResponse>("getResumeItems")?.items.orEmpty()
    }.let { result ->
        if (result.isFailure) Logger.warn("Jellyfin getResumeItems failed: ${result.exceptionOrNull()?.message}")
        result.getOrDefault(emptyList())
    }

    suspend fun startPlaybackSession(
        baseUrl: String,
        userToken: String,
        jellyfinId: String,
        positionTicks: Long,
        mediaSourceId: String,
    ) = runCatching {
        http.post(baseUrl.trimEnd('/') + "/Sessions/Playing") {
            jellyfinAuth(userToken)
            contentType(ContentType.Application.Json)
            setBody("""{"ItemId":"$jellyfinId","StartPositionTicks":$positionTicks,"MediaSourceId":"$mediaSourceId","CanSeek":true}""")
        }
    }.let { if (it.isFailure) Logger.warn("Jellyfin startPlaybackSession failed: ${it.exceptionOrNull()?.message}") }

    suspend fun reportPlaybackProgress(
        baseUrl: String,
        userToken: String,
        jellyfinId: String,
        positionTicks: Long,
        isPaused: Boolean,
        mediaSourceId: String,
    ) = runCatching {
        http.post(baseUrl.trimEnd('/') + "/Sessions/Playing/Progress") {
            jellyfinAuth(userToken)
            contentType(ContentType.Application.Json)
            setBody("""{"ItemId":"$jellyfinId","PositionTicks":$positionTicks,"IsPaused":$isPaused,"MediaSourceId":"$mediaSourceId","EventName":"timeupdate"}""")
        }
    }.let { if (it.isFailure) Logger.warn("Jellyfin reportPlaybackProgress failed: ${it.exceptionOrNull()?.message}") }

    suspend fun stopPlaybackSession(
        baseUrl: String,
        userToken: String,
        jellyfinId: String,
        positionTicks: Long,
        mediaSourceId: String,
    ) = runCatching {
        http.post(baseUrl.trimEnd('/') + "/Sessions/Playing/Stopped") {
            jellyfinAuth(userToken)
            contentType(ContentType.Application.Json)
            setBody("""{"ItemId":"$jellyfinId","PositionTicks":$positionTicks,"MediaSourceId":"$mediaSourceId"}""")
        }
    }.let { if (it.isFailure) Logger.warn("Jellyfin stopPlaybackSession failed: ${it.exceptionOrNull()?.message}") }

    suspend fun markPlayed(baseUrl: String, userToken: String, userId: String, jellyfinId: String) = runCatching {
        http.post(baseUrl.trimEnd('/') + "/Users/$userId/PlayedItems/$jellyfinId") {
            jellyfinAuth(userToken)
        }
    }.let { if (it.isFailure) Logger.warn("Jellyfin markPlayed failed: ${it.exceptionOrNull()?.message}") }

    suspend fun markUnplayed(baseUrl: String, userToken: String, userId: String, jellyfinId: String) = runCatching {
        http.delete(baseUrl.trimEnd('/') + "/Users/$userId/PlayedItems/$jellyfinId") {
            jellyfinAuth(userToken)
        }
    }.let { if (it.isFailure) Logger.warn("Jellyfin markUnplayed failed: ${it.exceptionOrNull()?.message}") }

    suspend fun getItemDetail(
        baseUrl: String,
        userToken: String,
        userId: String,
        jellyfinId: String,
    ): JellyfinItemDetail? = runCatching {
        val url = baseUrl.trimEnd('/') +
            "/Users/$userId/Items/$jellyfinId?Fields=UserData,RunTimeTicks,MediaStreams"
        http.get(url) { jellyfinAuth(userToken) }
            .bodyOrNull<JellyfinItemDetail>("getItemDetail")
    }.let { result ->
        if (result.isFailure) Logger.warn("Jellyfin getItemDetail failed: ${result.exceptionOrNull()?.message}")
        result.getOrNull()
    }

    suspend fun getSeriesEpisodes(
        baseUrl: String,
        userToken: String,
        userId: String,
        seriesId: String,
    ): List<JellyfinEpisodeItem> = runCatching {
        val url = baseUrl.trimEnd('/') +
            "/Shows/$seriesId/Episodes?UserId=$userId" +
            "&Fields=UserData,RunTimeTicks,SeasonName"
        http.get(url) { jellyfinAuth(userToken) }
            .bodyOrNull<JellyfinEpisodesResponse>("getSeriesEpisodes")?.items.orEmpty()
    }.let { result ->
        if (result.isFailure) Logger.warn("Jellyfin getSeriesEpisodes failed: ${result.exceptionOrNull()?.message}")
        result.getOrDefault(emptyList())
    }

    suspend fun getFavoriteItemIds(
        baseUrl: String,
        userToken: String,
        userId: String,
    ): Set<String> = runCatching {
        val url = baseUrl.trimEnd('/') +
            "/Users/$userId/Items?Filters=IsFavorite&Recursive=true" +
            "&IncludeItemTypes=Movie,Series&Fields=Id&Limit=500"
        http.get(url) { jellyfinAuth(userToken) }
            .bodyOrNull<JellyfinItemsResponse>("getFavoriteItemIds")?.items?.map { it.id }?.toSet().orEmpty()
    }.let { result ->
        if (result.isFailure) Logger.warn("Jellyfin getFavoriteItemIds failed: ${result.exceptionOrNull()?.message}")
        result.getOrDefault(emptySet())
    }

    suspend fun getNextUp(
        baseUrl: String,
        userToken: String,
        userId: String,
        limit: Int = 20,
    ): List<JellyfinPlayItem> = runCatching {
        val url = baseUrl.trimEnd('/') +
            "/Shows/NextUp?UserId=$userId&Limit=$limit" +
            "&Fields=UserData,SeriesId,SeriesName,SeasonId,IndexNumber,ParentIndexNumber"
        http.get(url) { jellyfinAuth(userToken) }
            .bodyOrNull<JellyfinPlayItemsResponse>("getNextUp")?.items.orEmpty()
    }.let { result ->
        if (result.isFailure) Logger.warn("Jellyfin getNextUp failed: ${result.exceptionOrNull()?.message}")
        result.getOrDefault(emptyList())
    }
}

/** Canonical authenticated Jellyfin header — one place so no call site can drift (Phase 50). */
private fun HttpRequestBuilder.jellyfinAuth(token: String) {
    header("Authorization", """$AUTH_HEADER, Token="$token"""")
}

/**
 * Deserialize the body only on a 2xx response. A non-2xx Jellyfin reply is often `text/plain`, which
 * `.body<T>()` can't parse as JSON — it throws `NoTransformationFoundException` (Phase 50). Log the
 * status + a snippet of the error body and return null so callers degrade gracefully instead.
 */
private suspend inline fun <reified T> HttpResponse.bodyOrNull(context: String): T? {
    if (!status.isSuccess()) {
        val detail = runCatching { bodyAsText() }.getOrDefault("")
        Logger.warn("Jellyfin $context failed: ${status.value} ${detail.take(180)}")
        return null
    }
    return body()
}

private fun String.jsonEscape(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
