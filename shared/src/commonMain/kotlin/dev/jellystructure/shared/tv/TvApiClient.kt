package dev.jellystructure.shared.tv

import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// R146: ids per /api/tv/playstate request. 100 × ~33 chars ≈ 3.3 KB — well under the Ktor CIO
// 8192-char request-line limit, so even a 261-episode series never 400s the play-state lookup.
private const val PLAYSTATE_ID_BATCH = 100

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

    /** R18: poll with optional device_id so a second pairing reuses the existing device slot. */
    suspend fun pollPairingWithDevice(pollToken: String, deviceId: String?): PairResult? {
        val body = if (deviceId != null)
            """{"poll_token":${pollToken.jsonStr()},"device_id":${deviceId.jsonStr()}}"""
        else
            """{"poll_token":${pollToken.jsonStr()}}"""
        val r = client.post("$baseUrl/api/tv/pair/poll") { jsonBody(body) }
        if (r.status == io.ktor.http.HttpStatusCode.Accepted) return null
        r.assertSuccess()
        return json.decodeFromString<PairResult>(r.bodyAsText())
    }

    /** R18: list all signed-in users on this device. */
    suspend fun getSessions(): List<TvSession> {
        val r = client.get("$baseUrl/api/tv/sessions") { auth() }
        r.assertSuccess()
        return json.decodeFromString<List<TvSession>>(r.bodyAsText())
    }

    /** R18: remove one user's session from this device. */
    suspend fun removeSession(userId: String) {
        val r = client.delete("$baseUrl/api/tv/sessions/$userId") { auth() }
        r.assertSuccess()
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

    // R118: pageSize null ⇒ server returns the full filtered set (no 40-item cap).
    suspend fun browse(kind: String? = null, genre: String? = null, page: Int = 1, pageSize: Int? = null): SearchResults {
        val r = client.get("$baseUrl/api/tv/browse") {
            auth()
            if (kind != null) parameter("kind", kind)
            if (genre != null) parameter("genre", genre)
            parameter("page", page)
            if (pageSize != null) parameter("pageSize", pageSize)
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

    /**
     * R83: fetch per-user play-state for a batch of Jellyfin ids.
     * R146: chunk the ids so a long series (e.g. 261 episodes ≈ 8.6 KB of ids) never blows past the
     * Ktor CIO 8192-char request-line limit, which silently 400'd the whole request and left every
     * episode looking unwatched. Each chunk is a separate request; results are merged. A failed chunk is
     * skipped (its items render unwatched) rather than failing the whole lookup.
     */
    suspend fun getPlaystate(ids: List<String>): Map<String, CardPlayState> {
        if (ids.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, CardPlayState>()
        for (chunk in ids.chunked(PLAYSTATE_ID_BATCH)) {
            val r = runCatching {
                client.get("$baseUrl/api/tv/playstate") { auth(); parameter("ids", chunk.joinToString(",")) }
            }.getOrNull() ?: continue
            if (!r.status.isSuccess()) continue
            runCatching { json.decodeFromString<Map<String, CardPlayState>>(r.bodyAsText()) }
                .getOrNull()?.let { out.putAll(it) }
        }
        return out
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

    suspend fun reportProgress(itemId: String, positionMs: Long, isPaused: Boolean = false) {
        client.post("$baseUrl/api/tv/playback/progress") {
            auth()
            jsonBody(json.encodeToString(PlaybackProgressRequest(itemId, positionMs, isPaused)))
        }.assertSuccess()
    }

    suspend fun stopPlayback(itemId: String, positionMs: Long) {
        client.post("$baseUrl/api/tv/playback/stop") {
            auth()
            jsonBody(json.encodeToString(PlaybackStopRequest(itemId, positionMs)))
        }.assertSuccess()
    }

    /** R56 — re-stream with a subtitle burned in via Jellyfin HLS transcode (encode/PGS path). */
    suspend fun restream(itemId: String, subtitleStreamIndex: Int, positionMs: Long): StreamTicket {
        val r = client.post("$baseUrl/api/tv/playback/restream") {
            auth()
            jsonBody(json.encodeToString(PlaybackRestreamRequest(itemId, subtitleStreamIndex, positionMs)))
        }
        r.assertSuccess()
        return json.decodeFromString<StreamTicket>(r.bodyAsText())
    }

    suspend fun markPlayed(itemId: String, watched: Boolean) {
        client.post("$baseUrl/api/tv/mark") {
            auth()
            jsonBody(json.encodeToString(MarkRequest(itemId, watched)))
        }.assertSuccess()
    }

    /**
     * R142 — set played/unplayed for a movie / episode / series (server fans a series out to its episodes;
     * pass [episodeIds] to target a season). Returns the authoritative per-id play-state for the item + all
     * affected episodes, so the caller patches its overlay from the server result instead of guessing.
     */
    suspend fun setPlayed(itemId: String, played: Boolean, episodeIds: List<String> = emptyList()): Map<String, CardPlayState> {
        val r = client.put("$baseUrl/api/tv/played") {
            auth()
            jsonBody(json.encodeToString(PlayedRequest(itemId, played, episodeIds)))
        }
        r.assertSuccess()
        return json.decodeFromString(r.bodyAsText())
    }

    // ─── Config ──────────────────────────────────────────────────────────────

    suspend fun getConfig(): RaviloConfig {
        val r = client.get("$baseUrl/api/tv/config") { auth() }
        r.assertSuccess()
        return json.decodeFromString<RaviloConfig>(r.bodyAsText())
    }

    /**
     * Persist the on-device viewer-tweakable settings. Posts a [ViewerSettingsRequest] (a small
     * subset of [RaviloConfig]); any null field is left unchanged server-side. The full-config
     * admin write lives behind the cookie-authed `/tv/admin/config` route, not here.
     */
    suspend fun putViewerSettings(
        skin: Skin? = null,
        showContinueProgress: Boolean? = null,
        autoplayNext: Boolean? = null,
        tileShape: TileShape? = null,
    ) {
        client.put("$baseUrl/api/tv/settings") {
            auth()
            jsonBody(json.encodeToString(ViewerSettingsRequest(skin, showContinueProgress, autoplayNext, tileShape)))
        }.assertSuccess()
    }

    // ─── Discover / Top 10 (R48) ─────────────────────────────────────────────

    suspend fun getDiscover(): DiscoverResponse {
        val r = client.get("$baseUrl/api/tv/discover") { auth() }
        r.assertSuccess()
        return json.decodeFromString(r.bodyAsText())
    }

    suspend fun getDiscoverItem(listId: String, rank: Int): DiscoverDetail {
        val r = client.get("$baseUrl/api/tv/discover/item/$listId/$rank") { auth() }
        r.assertSuccess()
        return json.decodeFromString(r.bodyAsText())
    }

    suspend fun requestDiscover(listId: String, rank: Int): AcquisitionRecord {
        val r = client.post("$baseUrl/api/tv/discover/request") {
            auth(); jsonBody("""{"listId":${listId.jsonStr()},"rank":$rank}""")
        }
        r.assertSuccess()
        return json.decodeFromString(r.bodyAsText())
    }

    // ─── Live events (R33/R141) ──────────────────────────────────────────────

    /** R141: degrade-to-poll fallback. Returns the server's monotonic config-change rev so the client
     *  can detect a missed WS event and trigger a silent refresh without a full reconnect. */
    suspend fun getConfigRev(): Long? {
        val r = runCatching { client.get("$baseUrl/api/tv/config/rev") { auth() } }.getOrNull() ?: return null
        if (!r.status.isSuccess()) return null
        val body = runCatching { r.bodyAsText() }.getOrNull() ?: return null
        return runCatching { json.decodeFromString<Map<String, Long>>(body)["rev"] }.getOrNull()
    }

    // ─── Live events (R33) ───────────────────────────────────────────────────

    /**
     * Opens the `/api/tv/events` WebSocket and streams [TvEvent]s until the socket closes (then
     * returns; the caller is responsible for reconnect/backoff). [onOpen] fires once the socket is
     * established — use it to trigger a full refresh so changes missed while disconnected are caught.
     * The device token is passed as a query param because browsers can't set a WS handshake header.
     * Requires the `WebSockets` client plugin to be installed on [client].
     */
    suspend fun connectEvents(
        onOpen: suspend () -> Unit = {},
        onEvent: suspend (TvEvent) -> Unit,
        onAcquisition: suspend (AcquisitionRecord) -> Unit = {},
        // R152 — a Jellyfin dashboard message relayed device-addressed via the Phase 110 session bridge.
        onServerMessage: suspend (ServerMessageEnvelope) -> Unit = {},
    ) {
        val token = deviceToken() ?: return
        val wsUrl = baseUrl.replaceFirst("http", "ws").trimEnd('/') +
            "/api/tv/events?token=" + token.encodeURLParameter()
        client.webSocket(wsUrl) {
            onOpen()
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val text = frame.readText()
                val ev = runCatching { json.decodeFromString<TvEvent>(text) }.getOrNull() ?: continue
                when (ev.type) {
                    "acquisition_changed" -> {
                        // Payload-bearing (Phase 56): patch a tile in place, no re-pull.
                        runCatching { json.decodeFromString<AcquisitionChangedEnvelope>(text).record }.getOrNull()?.let { onAcquisition(it) }
                    }
                    "server_message" -> {
                        runCatching { json.decodeFromString<ServerMessageEnvelope>(text) }.getOrNull()?.let { onServerMessage(it) }
                    }
                    else -> onEvent(ev)
                }
            }
        }
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
