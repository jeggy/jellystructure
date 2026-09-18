package dev.jellystructure.shared.tv

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.websocket.*
import dev.jellystructure.shared.RaviloHeaders
import dev.jellystructure.shared.raviloVersion
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
 *
 * R210 — [wsClient] is a separate [HttpClient] used only by [connectEvents]'s WebSocket upgrade;
 * every other method still goes through [client]. Defaults to [client] (a single-engine setup,
 * e.g. wasmJs/Tizen's `ktor-client-js`, which supports both plain REST and WebSockets fine) —
 * Android is the only platform that passes a different one, routing REST calls off CIO to work
 * around a live client-side CIO connect bug ([[bug-ravilo-tv-cio-connect-timeout]]) while keeping
 * CIO for the WebSocket (the Android engine has no WS support at all).
 */
class TvApiClient(
    private val client: HttpClient,
    val baseUrl: String,
    private val deviceToken: () -> String?,
    private val wsClient: HttpClient = client,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    // R252 (FR-R252-2) — which kind of client this is, stated on every request: tv · phone · web ·
    // tizen · cast. Each entry point passes its own; the version is never passed (see identify()).
    private val platform: String = "unknown",
) {

    // ─── Login (no auth) ─────────────────────────────────────────────────────

    /** Phase 141/R175 — proxied username/password sign-in: jellystructure authenticates against
     *  Jellyfin server-side and mints a device token bound to the returned user. Throws
     *  [TvApiError.Http] on failure (401 = invalid credentials; 503 = Jellyfin not configured). */
    suspend fun login(username: String, password: String, deviceId: String, deviceName: String? = null): PairResult {
        val r = client.post("$baseUrl/api/tv/login") {
            identify()
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
    // Phase 216 (FR-216-7) / R243 (FR-R243-5) — [studios] and [networks] are the two repeated query
    // parameters the server has accepted since R187 but this client never sent, so a studio or network
    // seed had no way through the shared client at all. Genres already passed.
    suspend fun browse(
        kind: String? = null,
        genre: String? = null,
        page: Int = 1,
        pageSize: Int? = null,
        studios: List<String> = emptyList(),
        networks: List<String> = emptyList(),
    ): SearchResults {
        val r = client.get("$baseUrl/api/tv/browse") {
            auth()
            if (kind != null) parameter("kind", kind)
            if (genre != null) parameter("genre", genre)
            studios.forEach { parameter("studio", it) }
            networks.forEach { parameter("network", it) }
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
     *  not a catalog filter), so its own endpoint, plain [MediaCard]s (no facet bar on that page).
     *  R219 (FR-R219-6) — [channelId], when set, mirrors the originating row's own configured scope
     *  (server-decided; the client just forwards the id it's already standing in — see
     *  HomeFeedService.continueWatchingAll's doc comment for the exact rule). */
    suspend fun continueAll(channelId: String? = null): List<MediaCard> {
        val url = if (channelId != null) "$baseUrl/api/tv/continue/all?channel=$channelId" else "$baseUrl/api/tv/continue/all"
        val r = client.get(url) { auth() }
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

    /** startupMs — Phase 185 (FR-185-4) client-measured negotiation-to-first-frame for this session,
     *  null when the client never rendered a first frame or never measured (see PlaybackStopRequest's
     *  own doc). */
    suspend fun stopPlayback(itemId: String, positionMs: Long, startupMs: Long? = null) {
        client.post("$baseUrl/api/tv/playback/stop") {
            auth()
            jsonBody(json.encodeToString(PlaybackStopRequest(itemId, positionMs, startupMs)))
        }.assertSuccess()
    }

    /** R216/Phase 177 (FR-R216-4) — fire-and-forget playback-quality report. Never throws: a failed post
     *  must not affect playback (the phase's own invariant) — the caller doesn't need to runCatching this
     *  itself. */
    suspend fun postPlaybackQoe(report: PlaybackQoeReport) {
        runCatching {
            client.post("$baseUrl/api/tv/playback/qoe") {
                auth()
                jsonBody(json.encodeToString(report))
            }
        }
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

    // ─── Phase 218 / R245 — Chromecast hand-off ─────────────────────────────

    /** The phone mints a short-lived, single-use code under its own session (FR-218-9). 404 when the
     *  server has no cast capability — the client never reaches this without one. */
    suspend fun castHandoff(): CastHandoffResponse {
        val r = client.post("$baseUrl/api/tv/cast/handoff") { auth() }
        r.assertSuccess()
        return json.decodeFromString<CastHandoffResponse>(r.bodyAsText())
    }

    /** The receiver redeems the code for its own device token — pre-auth, so no [auth] here. */
    suspend fun castRedeem(code: String, deviceName: String?, receiverId: String?): PairResult {
        val r = client.post("$baseUrl/api/tv/cast/redeem") {
            identify()
            jsonBody(json.encodeToString(CastRedeemRequest(code, deviceName, receiverId)))
        }
        r.assertSuccess()
        return json.decodeFromString<PairResult>(r.bodyAsText())
    }

    // ─── Phase 236 — the receiver-shows-a-code pairing flow ─────────────────

    /** POST /api/tv/screen/code — open path, rate-limited. Mints a fresh code for an unpaired screen;
     *  the receiver keeps [ScreenCodeResponse.claimSecret] in memory only. */
    suspend fun screenCode(deviceId: String, deviceName: String?, platform: String?): ScreenCodeResponse {
        val r = client.post("$baseUrl/api/tv/screen/code") {
            identify()
            jsonBody(json.encodeToString(ScreenCodeRequest(deviceId, deviceName, platform)))
        }
        r.assertSuccess()
        return json.decodeFromString<ScreenCodeResponse>(r.bodyAsText())
    }

    /** POST /api/tv/screen/claim — open path, polled while unclaimed. `null` while still waiting (202)
     *  or once the code stops being valid (401 — unknown, expired, or already collected once); the
     *  receiver can't tell those two apart, matching every other single-use code in this codebase. */
    suspend fun screenClaim(code: String, claimSecret: String): PairResult? {
        val r = client.post("$baseUrl/api/tv/screen/claim") {
            identify()
            jsonBody(json.encodeToString(ScreenClaimRequest(code, claimSecret)))
        }
        if (!r.status.isSuccess() || r.status == HttpStatusCode.Accepted) return null
        return json.decodeFromString<PairResult>(r.bodyAsText())
    }

    /** POST /api/tv/playback/status (236 FR-236-5) — sent on every change and at least every 5s while
     *  loaded; fanned out to whoever is subscribed to this device (a phone's remote, an API caller). */
    suspend fun postScreenStatus(status: ScreenStatus) {
        val r = client.post("$baseUrl/api/tv/playback/status") {
            auth()
            jsonBody(json.encodeToString(ScreenStatus.serializer(), status))
        }
        r.assertSuccess()
    }

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
     * Distinct from per-session "Sign out" ([signOutSession]), which only ends one session.
     *
     * Bug fix (R191): this posted to `/api/tv/unpair`, but the server only ever registered
     * `/api/tv/pair/unpair` (`TvRoutes.kt`) — every call 404'd, silently, since the response was
     * never surfaced anywhere a user would notice (the local store was cleared regardless).
     */
    suspend fun unpair(tokenOverride: String) {
        client.post("$baseUrl/api/tv/pair/unpair") {
            identify()
            headers { append(HttpHeaders.Authorization, "Bearer $tokenOverride") }
        }.assertSuccess()
    }

    /**
     * R191 — signs out exactly ONE profile's session (`DELETE /api/tv/sessions/{userId}`), leaving
     * every other profile signed into this device untouched. Authenticated with the caller's own
     * (active) token via [auth] — the route resolves `deviceId` from that token, so this can only
     * ever remove a session on the device making the call, never an arbitrary one.
     */
    suspend fun signOutSession(userId: String) {
        client.delete("$baseUrl/api/tv/sessions/$userId") { auth() }.assertSuccess()
    }

    // ─── Phase 187/R234 — a viewer's own photo + password ────────────────────────

    /** R234 (FR-R234-3) — [dataBase64] is the raw image bytes, base64-encoded (data: URL prefix
     *  tolerated server-side); the server validates/re-encodes before anything reaches Jellyfin
     *  (187 FR-187-6) — this is what the client actually captured, not what gets forwarded. */
    suspend fun setAccountPhoto(dataBase64: String, contentType: String): AccountPhotoResult {
        val r = client.post("$baseUrl/api/tv/account/photo") {
            auth(); jsonBody("""{"data_base64":${dataBase64.jsonStr()},"content_type":${contentType.jsonStr()}}""")
        }
        r.assertSuccess()
        return json.decodeFromString(r.bodyAsText())
    }

    suspend fun deleteAccountPhoto(): AccountPhotoResult {
        val r = client.delete("$baseUrl/api/tv/account/photo") { auth() }
        r.assertSuccess()
        return json.decodeFromString(r.bodyAsText())
    }

    /**
     * R234 (FR-R234-5/7) — the server relays Jellyfin's own verdict rather than the client guessing;
     * a wrong current password comes back as `200 OK` with [AccountPasswordResult.wrongCurrentPassword]
     * true, not an HTTP error, so it's decoded here rather than thrown. A `429` (187 FR-187-4's rate
     * limit) or any other non-2xx is a real failure and throws, same as everywhere else in this client
     * — the caller's generic "couldn't reach the server" path already exists for that shape of error.
     */
    suspend fun changeAccountPassword(currentPassword: String, newPassword: String): AccountPasswordResult {
        val r = client.post("$baseUrl/api/tv/account/password") {
            auth(); jsonBody("""{"current_password":${currentPassword.jsonStr()},"new_password":${newPassword.jsonStr()}}""")
        }
        r.assertSuccess()
        return json.decodeFromString(r.bodyAsText())
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
        // Phase 236 (FR-236-3) — the command set past stop/pause/unpause/home.
        onPlayerCommand: suspend (PlayerCommandEnvelope) -> Unit = {},
        // Pushed once a live Jellyfin playstate fetch completes for this user (see PlaystateChangedEnvelope) —
        // patch already-rendered tiles in place, the same way onAcquisition does for acquisition status.
        onPlaystateChanged: suspend (Map<String, CardPlayState>) -> Unit = {},
        // R248 (FR-R248-2) — the server folded a stop (or a played/mark write) into this user's Home feed;
        // re-pull Home / the open channel page. A signal only, like config_changed.
        onHomeChanged: suspend (Long) -> Unit = {},
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
        // R210 — wsClient (not client): on Android this is the CIO-backed client, kept solely for
        // this WebSocket upgrade after REST calls moved to a different engine.
        wsClient.webSocket(wsUrl, request = {
            identify()
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
                    "player_command" -> {
                        runCatching { json.decodeFromString<PlayerCommandEnvelope>(text) }.getOrNull()?.let { onPlayerCommand(it) }
                    }
                    "playstate_changed" -> {
                        runCatching { json.decodeFromString<PlaystateChangedEnvelope>(text).patch }.getOrNull()?.let { onPlaystateChanged(it) }
                    }
                    "home_changed" -> onHomeChanged(ev.rev)
                    else -> onEvent(ev)
                }
            }
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun HttpRequestBuilder.auth() {
        identify()
        val token = deviceToken() ?: return
        headers { append(HttpHeaders.Authorization, "Bearer $token") }
    }

    // R252 (FR-R252-2) — every request says which build and which platform it comes from, so the backend
    // can store it and forward the truth to Jellyfin (224). Two headers, never a login-body field: a
    // device token outlives the build that minted it, so the only truthful moment is each request. The
    // version comes from raviloVersion() alone — no caller can pass a different one.
    private fun HttpRequestBuilder.identify() {
        headers {
            append(RaviloHeaders.VERSION, raviloVersion())
            append(RaviloHeaders.PLATFORM, platform)
        }
    }

    private fun HttpRequestBuilder.jsonBody(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpResponse.assertSuccess() {
        if (!status.isSuccess()) {
            throw TvApiError.Http(
                status.value,
                bodyAsText(),
                // R237 — only the delta-seconds form; the HTTP-date form is legal but nothing in this
                // server emits it, and a misparse would be worse than falling back to our own backoff.
                retryAfterSeconds = headers[HttpHeaders.RetryAfter]?.toIntOrNull(),
            )
        }
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
