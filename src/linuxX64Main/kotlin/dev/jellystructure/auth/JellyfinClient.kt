package dev.jellystructure.auth

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
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
    private val http = HttpClient(CIO) {
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
}

private fun String.jsonEscape(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
