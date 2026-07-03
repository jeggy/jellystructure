package dev.jellystructure.torrent

import dev.jellystructure.config.QBittorrentConfig
import dev.jellystructure.OutboundHttp
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    // Phase 134 (FR-OPS2 §F): dropped this class's own private HttpClient(Curl) (a second, ungoverned
    // idle keep-alive pool outside the documented Phase-129 budget) for the shared OutboundHttp.client —
    // safe because the SID cookie is managed manually via a header, not cookie-jar plugin state that
    // would need to live on a dedicated client. OutboundHttp's timeouts (10s connect / 120s socket) are
    // more generous than this class's old ones (5s / 15s), which per OutboundHttp's own doc comment only
    // waits longer on a truly-hung peer, never causes a premature failure.
    private val http = OutboundHttp.client
    private suspend fun httpGet(url: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): io.ktor.client.statement.HttpResponse =
        OutboundHttp.withPermit { http.get(url, block) }
    private suspend fun httpPost(url: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): io.ktor.client.statement.HttpResponse =
        OutboundHttp.withPermit { http.post(url, block) }

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
