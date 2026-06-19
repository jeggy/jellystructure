package dev.jellystructure.shared.tv

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Ktor-based client for the jellystructure `/api/tv/` control plane.
 *
 * The caller supplies a pre-configured [HttpClient] (engine choice is platform-specific;
 * no ContentNegotiation plugin is required — this client handles serialization internally
 * via the [json] instance). [deviceToken] is called per request and may return null before
 * the device has been paired.
 */
class TvApiClient(
    private val client: HttpClient,
    val baseUrl: String,
    private val deviceToken: () -> String?,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    // ─── Pairing (no auth) ───────────────────────────────────────────────────

    suspend fun startPairing(): PairingChallenge {
        val r = client.post("$baseUrl/api/tv/pair/start")
        r.assertSuccess()
        return json.decodeFromString<PairingChallenge>(r.bodyAsText())
    }

    /**
     * TV polls until the web/phone client approves the code.
     * Returns null while approval is pending (server responds 202).
     */
    suspend fun pollPairing(pollToken: String): PairResult? {
        val r = client.post("$baseUrl/api/tv/pair/poll") {
            jsonBody("""{"poll_token":${pollToken.jsonStr()}}""")
        }
        if (r.status == HttpStatusCode.Accepted) return null
        r.assertSuccess()
        return json.decodeFromString<PairResult>(r.bodyAsText())
    }

    suspend fun approvePairing(code: String) {
        client.post("$baseUrl/api/tv/pair/approve") {
            jsonBody("""{"code":${code.jsonStr()}}""")
        }.assertSuccess()
    }

    // ─── Feed ────────────────────────────────────────────────────────────────

    suspend fun getHome(): HomeFeed {
        val r = client.get("$baseUrl/api/tv/home") { auth() }
        r.assertSuccess()
        return json.decodeFromString<HomeFeed>(r.bodyAsText())
    }

    suspend fun getChannel(id: String): HomeFeed {
        val r = client.get("$baseUrl/api/tv/channel/$id") { auth() }
        r.assertSuccess()
        return json.decodeFromString<HomeFeed>(r.bodyAsText())
    }

    suspend fun getContinue(): List<MediaCard> {
        val r = client.get("$baseUrl/api/tv/continue") { auth() }
        r.assertSuccess()
        return json.decodeFromString<List<MediaCard>>(r.bodyAsText())
    }

    // ─── Browse + search ─────────────────────────────────────────────────────

    suspend fun browse(kind: String? = null, page: Int = 0, pageSize: Int = 40): SearchResults {
        val r = client.get("$baseUrl/api/tv/browse") {
            auth()
            if (kind != null) parameter("kind", kind)
            parameter("page", page)
            parameter("pageSize", pageSize)
        }
        r.assertSuccess()
        return json.decodeFromString<SearchResults>(r.bodyAsText())
    }

    suspend fun search(query: String): SearchResults {
        val r = client.get("$baseUrl/api/tv/search") {
            auth()
            parameter("q", query)
        }
        r.assertSuccess()
        return json.decodeFromString<SearchResults>(r.bodyAsText())
    }

    suspend fun getFacets(kind: String? = null): BrowseFacets {
        val r = client.get("$baseUrl/api/tv/facets") {
            auth()
            if (kind != null) parameter("kind", kind)
        }
        r.assertSuccess()
        return json.decodeFromString<BrowseFacets>(r.bodyAsText())
    }

    // ─── Detail ──────────────────────────────────────────────────────────────

    suspend fun getMovie(id: String): MovieDetail {
        val r = client.get("$baseUrl/api/tv/movie/$id") { auth() }
        r.assertSuccess()
        return json.decodeFromString<MovieDetail>(r.bodyAsText())
    }

    suspend fun getSeries(id: String): SeriesDetail {
        val r = client.get("$baseUrl/api/tv/series/$id") { auth() }
        r.assertSuccess()
        return json.decodeFromString<SeriesDetail>(r.bodyAsText())
    }

    // ─── Playback ────────────────────────────────────────────────────────────

    suspend fun startPlayback(itemId: String, capabilities: ClientCapabilities): StreamTicket {
        val r = client.post("$baseUrl/api/tv/playback/start") {
            auth()
            jsonBody(json.encodeToString(PlaybackStartRequest(itemId, capabilities)))
        }
        r.assertSuccess()
        return json.decodeFromString<StreamTicket>(r.bodyAsText())
    }

    suspend fun reportProgress(sessionId: String, itemId: String, positionMs: Long) {
        client.post("$baseUrl/api/tv/playback/progress") {
            auth()
            jsonBody(json.encodeToString(PlaybackProgressRequest(sessionId, itemId, positionMs)))
        }.assertSuccess()
    }

    suspend fun stopPlayback(sessionId: String, itemId: String, positionMs: Long) {
        client.post("$baseUrl/api/tv/playback/stop") {
            auth()
            jsonBody(json.encodeToString(PlaybackProgressRequest(sessionId, itemId, positionMs)))
        }.assertSuccess()
    }

    suspend fun markPlayed(itemId: String, watched: Boolean) {
        client.post("$baseUrl/api/tv/mark") {
            auth()
            jsonBody(json.encodeToString(MarkRequest(itemId, watched)))
        }.assertSuccess()
    }

    // ─── Config ──────────────────────────────────────────────────────────────

    suspend fun getConfig(): RaviloConfig {
        val r = client.get("$baseUrl/api/tv/config") { auth() }
        r.assertSuccess()
        return json.decodeFromString<RaviloConfig>(r.bodyAsText())
    }

    suspend fun putSettings(config: RaviloConfig) {
        client.put("$baseUrl/api/tv/settings") {
            auth()
            jsonBody(json.encodeToString(config))
        }.assertSuccess()
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun HttpRequestBuilder.auth() {
        val token = deviceToken() ?: return
        headers { append(HttpHeaders.Authorization, "Bearer $token") }
    }

    private fun HttpRequestBuilder.jsonBody(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpResponse.assertSuccess() {
        if (!status.isSuccess()) throw TvApiError.Http(status.value, bodyAsText())
    }

    // Returns the JSON-quoted form of this string (surrounds with `"`, escapes `\` and `"`).
    private fun String.jsonStr(): String = buildString {
        append('"')
        for (ch in this@jsonStr) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> append(ch)
            }
        }
        append('"')
    }
}
