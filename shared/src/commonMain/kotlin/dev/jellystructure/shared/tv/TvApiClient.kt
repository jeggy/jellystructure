package dev.jellystructure.shared.tv

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.websocket.*
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

    // ─── Login (no auth) ─────────────────────────────────────────────────────

    /** Phase 141/R175 — proxied username/password sign-in: jellystructure authenticates against
     *  Jellyfin server-side and mints a device token bound to the returned user. Throws
     *  [TvApiError.Http] on failure (401 = invalid credentials; 503 = Jellyfin not configured). */
    suspend fun login(username: String, password: String, deviceId: String, deviceName: String? = null): PairResult {
        val r = client.post("$baseUrl/api/tv/login") {
            jsonBody(json.encodeToString(TvLoginRequest(username, password, deviceId, deviceName)))
        }
        r.assertSuccess()
        return json.decodeFromString<PairResult>(r.bodyAsText())
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

    /** R187 fix — just the channel id->name list, for the seeded-browse page's Channel facet when it's
     *  opened from an entry point (e.g. the Movies/Series tab) that never loaded a full [HomeFeed]. */
    suspend fun getChannels(): List<Channel> {
        val r = client.get("$baseUrl/api/tv/channels") { auth() }
        r.assertSuccess()
        return json.decodeFromString<List<Channel>>(r.bodyAsText())
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

    /** R187 — resolves a "→ See all" seed ([Row.seedQuery]/[Row.seedMediaKind]) to the FULL matching
     *  set as [BrowseCard]s; the caller computes every facet's counts/filtering/sort reactively from
     *  this one response — see [SeededBrowseResponse]'s doc comment for why no further round trip. */
    suspend fun browseSeeded(query: ConditionGroup?, mediaKind: String? = null): SeededBrowseResponse {
        val r = client.post("$baseUrl/api/tv/browse/seeded") {
            auth()
            jsonBody(json.encodeToString(SeededBrowseRequest(query, mediaKind)))
        }
        r.assertSuccess()
        return json.decodeFromString<SeededBrowseResponse>(r.bodyAsText())
    }

    /** R190 §C — the person-browse page's Seerr overflow row: requestable titles featuring this person
     *  not already in the library, capped at 12 server-side. Empty (never an error) with Seerr off. */
    suspend fun getPersonOverflow(personTmdbId: Int): List<DiscoverEntry> {
        val r = client.get("$baseUrl/api/tv/browse/person/$personTmdbId/seerr-overflow") { auth() }
        r.assertSuccess()
        return json.decodeFromString(r.bodyAsText())
    }

    /** R187 (§G-4) — Continue Watching's own "→ See all": not seed-representable (a live Jellyfin join,
     *  not a catalog filter), so its own endpoint, plain [MediaCard]s (no facet bar on that page). */
    suspend fun continueAll(): List<MediaCard> {
        val r = client.get("$baseUrl/api/tv/continue/all") { auth() }
        r.assertSuccess()
        return json.decodeFromString<List<MediaCard>>(r.bodyAsText())
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

    // Bug fix — "My List" write-through (see FavoriteRequest's doc comment). Null on a 404 (item not
    // visible to this device) — the caller should treat that like any other failed write.
    suspend fun setFavorite(itemId: String, favorite: Boolean): CardPlayState? {
        val r = client.put("$baseUrl/api/tv/favorite") {
            auth()
            jsonBody(json.encodeToString(FavoriteRequest(itemId, favorite)))
        }
        if (r.status.value == 404) return null
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
        // R161/R162: a per-user viewer override, resolved server-side against the per-user admin
        // override then the global default (RaviloConfigService.resolveBehaviour) — never written
        // back to the Jellystructure profile itself.
        uiLanguage: String? = null,
    ) {
        client.put("$baseUrl/api/tv/settings") {
            auth()
            jsonBody(json.encodeToString(ViewerSettingsRequest(skin, showContinueProgress, autoplayNext, tileShape, uiLanguage)))
        }.assertSuccess()
    }

    /**
     * R161 — "Unpair this TV" revokes every session this device holds, not just the active one:
     * the caller iterates `MultiTokenStore.getAll()` and calls this once per stored token (an
     * explicit [tokenOverride], since each session's token is a different Bearer identity — not
     * necessarily the one [deviceToken] currently resolves to), then clears the local store.
     * Distinct from per-session "Sign out", which only ends the active session client-side.
     */
    suspend fun unpair(tokenOverride: String) {
        client.post("$baseUrl/api/tv/unpair") {
            headers { append(HttpHeaders.Authorization, "Bearer $tokenOverride") }
        }.assertSuccess()
    }

    // ─── Request / Seerr discover (R171, replaces the retired R48 chart Top 10) ──────────────

    suspend fun getDiscover(): DiscoverResponse {
        val r = client.get("$baseUrl/api/tv/discover") { auth() }
        r.assertSuccess()
        return json.decodeFromString(r.bodyAsText())
    }

    /** [mediaType] is `"movie"` or `"tv"`. */
    suspend fun getDiscoverItem(mediaType: String, tmdbId: Int): DiscoverDetail {
        val r = client.get("$baseUrl/api/tv/discover/item/$mediaType/$tmdbId") { auth() }
        r.assertSuccess()
        return json.decodeFromString(r.bodyAsText())
    }

    /** Search scoped to the Seerr catalogue only (never the local library — see [search]). */
    suspend fun searchSeerr(query: String): SeerrSearchResults {
        val r = client.get("$baseUrl/api/tv/search/seerr") { auth(); parameter("q", query) }
        r.assertSuccess()
        return json.decodeFromString(r.bodyAsText())
    }

    // ─── Upcoming calendar (R160) ────────────────────────────────────────────

    suspend fun getUpcoming(): UpcomingFeed {
        val r = client.get("$baseUrl/api/tv/upcoming") { auth() }
        r.assertSuccess()
        return json.decodeFromString(r.bodyAsText())
    }

    /** R167 — the enriched not-held detail (genres/runtime/cast); throws on a lookup miss (404) so
     *  the caller can fall back to the plain feed item it already has. */
    suspend fun getUpcomingItem(id: String): UpcomingDetail {
        val r = client.get("$baseUrl/api/tv/upcoming/item/$id") { auth() }
        r.assertSuccess()
        return json.decodeFromString(r.bodyAsText())
    }

    /** [mediaKind] is `"movie"` or `"tv"` — matches Seerr's own vocabulary, not [MediaKind]'s enum names.
     *  Phase 139 — [language] is the viewer's explicit pick from the Original/Nordic popup; null lets
     *  the server resolve it (per-viewer default → kids default → catalog default). */
    suspend fun requestDiscover(mediaKind: String, tmdbId: Int, title: String, language: String? = null): AcquisitionRecord {
        val r = client.post("$baseUrl/api/tv/discover/request") {
            auth(); jsonBody("""{"mediaKind":${mediaKind.jsonStr()},"tmdbId":$tmdbId,"title":${title.jsonStr()}${language?.let { ""","language":${it.jsonStr()}""" }.orEmpty()}}""")
        }
        r.assertSuccess()
        return json.decodeFromString(r.bodyAsText())
    }

    /** Phase 139 §D.2 — the viewer's own not-yet-available requests (Request tab's "In progress" rail). */
    suspend fun getMyRequests(): List<DiscoverEntry> {
        val r = client.get("$baseUrl/api/tv/discover/requests/mine") { auth() }
        r.assertSuccess()
        return json.decodeFromString(r.bodyAsText())
    }

    /** Phase 139 §E — switch a still-waiting request to a different language; re-profiles + re-searches
     *  server-side. Returns false (not throws) on failure — the caller shows a "couldn't change" toast
     *  rather than treating it like a network error. */
    suspend fun changeRequestLanguage(mediaType: String, tmdbId: Int, language: String): Boolean {
        val r = client.post("$baseUrl/api/tv/discover/request/$mediaType/$tmdbId/language") {
            auth(); jsonBody("""{"language":${language.jsonStr()}}""")
        }
        return r.status.value in 200..299
    }

    // ─── Live TV (Phase 147/R177) ────────────────────────────────────────────

    /** Shown+available channels for the Home "On now" row / EPG guide's channel list — embedded
     *  current/next program, no separate guide fetch (jellystructure Phase 147 addendum C). */
    suspend fun getLiveTvChannels(): List<LiveTvChannel> {
        val r = client.get("$baseUrl/api/tv/livetv/channels") { auth() }
        r.assertSuccess()
        return json.decodeFromString(r.bodyAsText())
    }

    /** Full-schedule guide grid, cached server-side at the admin-configured cadence. [hoursBack] pulls
     *  in recently-elapsed programs too (user request — the guide used to only ever look forward). */
    suspend fun getLiveTvGuide(days: Int = 7, hoursBack: Int = 0): List<LiveTvGuideProgram> {
        val r = client.get("$baseUrl/api/tv/livetv/guide") {
            auth(); parameter("days", days); parameter("hoursBack", hoursBack)
        }
        r.assertSuccess()
        return json.decodeFromString(r.bodyAsText())
    }

    /** Tunes a channel — Jellyfin's explicit open handshake (Phase 147 addendum D); the returned
     *  ticket's [LiveTvStreamTicket.liveStreamId] must be passed to [stopLiveTv] on exit. */
    suspend fun tuneLiveTv(channelId: String, capabilities: ClientCapabilities): LiveTvStreamTicket {
        val r = client.post("$baseUrl/api/tv/livetv/channels/$channelId/tune") {
            auth()
            jsonBody(json.encodeToString(LiveTvTuneRequest(channelId, capabilities)))
        }
        r.assertSuccess()
        return json.decodeFromString(r.bodyAsText())
    }

    suspend fun liveTvHeartbeat() {
        runCatching { client.post("$baseUrl/api/tv/livetv/heartbeat") { auth() } }
    }

    suspend fun stopLiveTv(liveStreamId: String) {
        client.post("$baseUrl/api/tv/livetv/stop") {
            auth()
            jsonBody(json.encodeToString(LiveTvStopRequest(liveStreamId)))
        }.assertSuccess()
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
        // R155 — remote-control commands (Phase 111 / Home Assistant + the Jellyfin dashboard cast menu).
        onPlayItem: suspend (PlayItemEnvelope) -> Unit = {},
        onPlaystateCommand: suspend (PlaystateCommandEnvelope) -> Unit = {},
        onNavigate: suspend (NavigateEnvelope) -> Unit = {},
        // Pushed once a live Jellyfin playstate fetch completes for this user (see PlaystateChangedEnvelope) —
        // patch already-rendered tiles in place, the same way onAcquisition does for acquisition status.
        onPlaystateChanged: suspend (Map<String, CardPlayState>) -> Unit = {},
    ) {
        val token = deviceToken() ?: return
        val wsUrl = baseUrl.replaceFirst("http", "ws").trimEnd('/') +
            "/api/tv/events?token=" + token.encodeURLParameter()
        // Bug fix: the shared HttpClient's HttpTimeout plugin (requestTimeoutMillis/socketTimeoutMillis
        // = 10s, installed to bound ordinary REST calls) applies to this WebSocket session too, since
        // Ktor treats a WS as one continuous request — it silently force-closed this otherwise-idle
        // long-lived connection every ~10s, which the reconnect loop above (RaviloApp.kt) then papered
        // over as a healthy reconnect (held open well past its own 2s "was it real" threshold),
        // producing a live connect/disconnect/force-stop-playback cycle every ~10s indefinitely.
        // Exempt only this call from the client-wide REST bound; regular requests are unaffected.
        client.webSocket(wsUrl, request = {
            timeout {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            }
        }) {
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
                    "play_item" -> {
                        runCatching { json.decodeFromString<PlayItemEnvelope>(text) }.getOrNull()?.let { onPlayItem(it) }
                    }
                    "playstate_command" -> {
                        runCatching { json.decodeFromString<PlaystateCommandEnvelope>(text) }.getOrNull()?.let { onPlaystateCommand(it) }
                    }
                    "navigate" -> {
                        runCatching { json.decodeFromString<NavigateEnvelope>(text) }.getOrNull()?.let { onNavigate(it) }
                    }
                    "playstate_changed" -> {
                        runCatching { json.decodeFromString<PlaystateChangedEnvelope>(text).patch }.getOrNull()?.let { onPlaystateChanged(it) }
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
