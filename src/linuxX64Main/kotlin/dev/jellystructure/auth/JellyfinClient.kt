package dev.jellystructure.auth

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
        val response = http.get(url) {
            header("Authorization", """$AUTH_HEADER, Token="$token"""")
        }
        response.status.value in 200..299
    }.getOrDefault(false)

    suspend fun getLibraries(baseUrl: String, token: String): List<JellyfinLibrary> = runCatching {
        val url = baseUrl.trimEnd('/') + "/Library/VirtualFolders"
        http.get(url) {
            header("Authorization", """$AUTH_HEADER, Token="$token"""")
        }.body<List<JellyfinLibrary>>()
    }.getOrDefault(emptyList())

    suspend fun getItems(baseUrl: String, token: String): List<JellyfinItem> = runCatching {
        val url = baseUrl.trimEnd('/') +
            "/Items?IncludeItemTypes=Movie,Series&Recursive=true&Fields=Path,ProviderIds,ProductionYear"
        http.get(url) {
            header("Authorization", """$AUTH_HEADER, Token="$token"""")
        }.body<JellyfinItemsResponse>().items
            .filter { it.type == "Movie" || it.type == "Series" }
    }.onFailure { println("[WARN] Jellyfin getItems failed: ${it.message}") }
     .getOrDefault(emptyList())

    suspend fun refreshItem(baseUrl: String, token: String, jellyfinId: String): Boolean = runCatching {
        val url = baseUrl.trimEnd('/') +
            "/Items/$jellyfinId/Refresh?MetadataRefreshMode=ValidationOnly&ImageRefreshMode=ValidationOnly"
        val response = http.post(url) {
            header("Authorization", """$AUTH_HEADER, Token="$token"""")
        }
        println("[INFO] Jellyfin item refresh $jellyfinId: ${response.status.value}")
        response.status.value in 200..299
    }.getOrDefault(false)

    suspend fun triggerLibraryRefresh(baseUrl: String, token: String): Boolean = runCatching {
        val url = baseUrl.trimEnd('/') + "/Library/Refresh"
        val response = http.post(url) {
            header("Authorization", """$AUTH_HEADER, Token="$token"""")
        }
        response.status.value in 200..299
    }.getOrDefault(false)
}

private fun String.jsonEscape(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
