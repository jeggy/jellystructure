package dev.jellystructure.torrent

import dev.jellystructure.config.QBittorrentConfig
import dev.jellystructure.OutboundHttp
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class QBTorrent(
    val hash: String,
    val name: String,
    val state: String,
    @SerialName("save_path") val savePath: String,
    @SerialName("content_path") val contentPath: String,
    // active tracker announce URL (single URL; may be empty string when no peers)
    val tracker: String = "",
    val ratio: Double = 0.0,
    @SerialName("num_seeds") val numSeeds: Int = 0,
    @SerialName("num_leechs") val numLeechs: Int = 0,
    val uploaded: Long = 0L,
    @SerialName("added_on") val addedOn: Long = 0L,
    @SerialName("seeding_time") val seedingTime: Int = 0,
)

class QBittorrentClient {
    private suspend fun httpGet(url: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): io.ktor.client.statement.HttpResponse =
        OutboundHttp.withPermit { http.get(url, block) }
    private suspend fun httpPost(url: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): io.ktor.client.statement.HttpResponse =
        OutboundHttp.withPermit { http.post(url, block) }

    private val http = HttpClient(Curl) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            socketTimeoutMillis  = 15_000
            requestTimeoutMillis = 15_000
        }
    }

    // Returns SID cookie value, or "" when auth is disabled (qBittorrent returns 204 No Content).
    suspend fun login(config: QBittorrentConfig): String {
        if (config.noAuth) return ""
        val url = config.url.trimEnd('/') + "/api/v2/auth/login"
        val response = httpPost(url) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("username=${config.username}&password=${config.password}")
        }
        // 204 means auth is disabled or bypassed on this qBittorrent instance
        if (response.status == HttpStatusCode.NoContent) return ""
        if (response.status != HttpStatusCode.OK) {
            throw IllegalStateException("qBittorrent login failed: HTTP ${response.status.value}")
        }
        val body = response.body<String>()
        if (body.trim() == "Fails.") {
            throw IllegalArgumentException("qBittorrent login rejected — wrong credentials")
        }
        val sid = response.headers["Set-Cookie"]
            ?.split(";")
            ?.firstOrNull { it.trim().startsWith("SID=") }
            ?.removePrefix("SID=")
            ?.trim()
            ?: throw IllegalStateException("qBittorrent login succeeded but no SID cookie in response")
        return sid
    }

    suspend fun getTorrents(config: QBittorrentConfig, sid: String): List<QBTorrent> {
        val url = config.url.trimEnd('/') + "/api/v2/torrents/info?filter=all"
        val response = httpGet(url) {
            if (sid.isNotBlank()) header("Cookie", "SID=$sid")
        }
        if (response.status != HttpStatusCode.OK) {
            throw IllegalStateException("qBittorrent getTorrents failed: HTTP ${response.status.value}")
        }
        return response.body()
    }
}
