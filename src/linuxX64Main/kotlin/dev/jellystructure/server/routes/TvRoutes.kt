package dev.jellystructure.server.routes

import dev.jellystructure.server.respondCachedBytes
import dev.jellystructure.auth.DeviceKey
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.SessionKey
import dev.jellystructure.auth.SessionService
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.shared.tv.DiscoverResponse
import dev.jellystructure.shared.tv.ChannelLogoUpload
import dev.jellystructure.shared.tv.CardPlayState
import dev.jellystructure.shared.tv.MarkRequest
import dev.jellystructure.shared.tv.PlayedRequest
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.PairResult
import dev.jellystructure.shared.tv.TvLoginRequest
import dev.jellystructure.shared.tv.PlaybackProgressRequest
import dev.jellystructure.shared.tv.PlaybackRestreamRequest
import dev.jellystructure.shared.tv.PlaybackStartRequest
import dev.jellystructure.shared.tv.PlaybackStopRequest
import dev.jellystructure.shared.tv.SeededBrowseRequest
import dev.jellystructure.shared.tv.TvSession
import dev.jellystructure.shared.tv.ViewerSettingsRequest
import dev.jellystructure.tv.BrowseService
import dev.jellystructure.tv.ChannelLogoStore
import dev.jellystructure.tv.DetailService
import dev.jellystructure.tv.HomeFeedService
import dev.jellystructure.tv.RaviloArtworkService
import dev.jellystructure.tv.RaviloImageUrl
import dev.jellystructure.tv.PlaybackService
import dev.jellystructure.tv.RaviloConfigService
import dev.jellystructure.tv.RaviloDeviceService
import dev.jellystructure.tv.TvEventBus
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Phase 143 — Users & Devices admin overview DTOs.
@Serializable
private data class OverviewDevice(
    @SerialName("device_id") val deviceId: String,
    val name: String,
    val connected: Boolean,
    @SerialName("is_admin") val isAdmin: Boolean,
    @SerialName("is_kids") val isKids: Boolean,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("last_seen") val lastSeen: Long,
    @SerialName("now_playing") val nowPlaying: String? = null,
)

@Serializable
private data class OverviewSession(
    // Phase 143: NEVER the raw session token (that IS the js_session cookie value — returning it would
    // let any admin viewing this page hijack another admin's live session). A 12-char prefix of a
    // cryptographically random 64-char token leaks ~48 of 256 bits — not enough to reconstruct it —
    // and is resolved back to the full token server-side by the revoke route below.
    val id: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("last_used_at") val lastUsedAt: Long,
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("is_current") val isCurrent: Boolean,
)

@Serializable
private data class OverviewPolicy(
    @SerialName("is_admin") val isAdmin: Boolean,
    @SerialName("all_folders") val allFolders: Boolean,
    @SerialName("library_count") val libraryCount: Int? = null,  // null when allFolders (unrestricted)
    @SerialName("total_libraries") val totalLibraries: Int = 0,
    @SerialName("allowed_tags") val allowedTags: List<String> = emptyList(),
    @SerialName("blocked_tags") val blockedTags: List<String> = emptyList(),
    @SerialName("max_rating") val maxRating: Int? = null,
)

@Serializable
private data class OverviewUser(
    @SerialName("user_id") val userId: String,
    val username: String,
    val policy: OverviewPolicy,
    val devices: List<OverviewDevice>,
    val sessions: List<OverviewSession>,
)

// Phase 143 (design addendum) — "Recently watched" lazy history DTOs. Timestamps are epoch **seconds**
// (straight off Jellyfin's ISO DatePlayed via isoToEpochSeconds) — unlike every other Phase 143
// timestamp above, which is epoch millis (nowMs()-based); the admin frontend must not conflate the two.
@Serializable
private data class WatchHistoryEntry(
    val title: String,
    @SerialName("episode_label") val episodeLabel: String? = null,  // e.g. "S01 · E01–E08"; null for a movie
    @SerialName("episode_count") val episodeCount: Int = 1,
    @SerialName("first_played_at") val firstPlayedAt: Long,  // epoch seconds; oldest play in the group
    @SerialName("last_played_at") val lastPlayedAt: Long,    // epoch seconds; newest play in the group
    // Phase 143 follow-up — false only for the in-progress ("stopped at N%") items folded in on the
    // first page (see the /history route); true (and progressPct null) for every grouped finished row.
    val finished: Boolean = true,
    @SerialName("progress_pct") val progressPct: Int? = null,
)

@Serializable
private data class WatchHistoryPage(
    val entries: List<WatchHistoryEntry>,
    @SerialName("has_more") val hasMore: Boolean,
)

private const val HISTORY_PAGE_SIZE = 20

/** Phase 143 — collapses consecutive episodes of the same series **and season** (adjacent in the
 *  DatePlayed-sorted list) into one row, matching the design addendum's
 *  "Havets Hjarta · S01 · E01–E08 · ✓ 8 episodes" example. A movie, or an episode whose neighbours
 *  belong to a different series/season, is its own one-item row. */
private fun groupHistoryEntries(items: List<dev.jellystructure.auth.JellyfinPlayItem>): List<WatchHistoryEntry> {
    fun playedAt(item: dev.jellystructure.auth.JellyfinPlayItem): Long =
        item.userData?.lastPlayedDate?.let { dev.jellystructure.util.isoToEpochSeconds(it) } ?: 0L

    val result = mutableListOf<WatchHistoryEntry>()
    var i = 0
    while (i < items.size) {
        val head = items[i]
        if (head.seriesId == null) {
            result.add(WatchHistoryEntry(title = head.name, firstPlayedAt = playedAt(head), lastPlayedAt = playedAt(head)))
            i++
            continue
        }
        var j = i + 1
        while (j < items.size && items[j].seriesId == head.seriesId && items[j].seasonNumber == head.seasonNumber) j++
        val run = items.subList(i, j)
        val playedTimes = run.map { playedAt(it) }.filter { it > 0 }
        val episodeNums = run.mapNotNull { it.episodeNumber }
        val epLabel = buildString {
            append("S${(head.seasonNumber ?: 0).toString().padStart(2, '0')}")
            episodeNums.minOrNull()?.let { min ->
                append(" · E${min.toString().padStart(2, '0')}")
                val max = episodeNums.maxOrNull()!!
                if (max != min) append("–E${max.toString().padStart(2, '0')}")
            }
        }
        result.add(WatchHistoryEntry(
            title = head.seriesName ?: head.name,
            episodeLabel = epLabel,
            episodeCount = run.size,
            firstPlayedAt = playedTimes.minOrNull() ?: 0L,
            lastPlayedAt = playedTimes.maxOrNull() ?: 0L,
        ))
        i = j
    }
    return result
}

private const val SESSION_ID_PREFIX_LEN = 12

@Serializable
private data class AdminConfigEnvelope(
    val config: RaviloConfig,
    val hasOverride: Boolean,
    val isGlobal: Boolean,
)

@Serializable
private data class TvDiscoverRequest(
    val mediaKind: String? = null,   // "movie" | "tv"
    val tmdbId: Int? = null,
    val title: String? = null,
    val language: String? = null,    // Phase 139 — explicit request-language intent id; null = resolve server-side
)

@Serializable
private data class ChangeRequestLanguageBody(val language: String)

fun Route.tvRoutes(
    deviceService: RaviloDeviceService,
    raviloConfigService: RaviloConfigService,
    homeFeedService: HomeFeedService,
    browseService: BrowseService,
    detailService: DetailService,
    playbackService: PlaybackService,
    sessionService: SessionService,
    jellyfinClient: JellyfinClient,
    configStore: ConfigStore,
    channelLogoStore: ChannelLogoStore,
    imageProxyService: RaviloArtworkService? = null,
    tvEventBus: TvEventBus? = null,
    upcomingService: dev.jellystructure.tv.UpcomingService? = null,
    seerrDiscoverService: dev.jellystructure.seerr.SeerrDiscoverService? = null,
    mediaStore: dev.jellystructure.media.MediaStore? = null,
) {
    // Phase 141 — proxied username/password login, replacing the code+poll+admin-approve pairing flow.
    // No device token exists yet (OPEN_API_PATHS); jellystructure authenticates the credentials against
    // Jellyfin itself and mints a device token bound to the returned user (never the admin, silently).
    post("/tv/login") {
        val req = runCatching { call.receive<TvLoginRequest>() }.getOrElse {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request"))
            return@post
        }
        if (req.username.isBlank() || req.password.isBlank() || req.deviceId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "username, password and deviceId are required"))
            return@post
        }
        val config = configStore.current
        if (config.apiKeys.jellyfinUrl.isBlank()) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Jellyfin not configured"))
            return@post
        }
        // The identity presented for THIS auth call, before the Jellyfin userId is known — built from
        // deviceId + username (not forDevice(), which needs a DeviceData row that doesn't exist yet).
        // Folding the username in here is what keeps two different profiles on one shared TV from
        // colliding on a single Jellyfin DeviceId (see JellyfinDeviceIdentity.forDevice's KDoc) —
        // post-login calls use forDevice(device), which folds in the now-known jellyfinUserId instead.
        val loginIdentity = dev.jellystructure.auth.JellyfinDeviceIdentity(
            "ravilo-${req.deviceId}-${req.username}",
            req.deviceName?.ifBlank { null } ?: "Ravilo TV",
        )
        val authAttempt = runCatching {
            jellyfinClient.authenticateByName(config.apiKeys.jellyfinUrl, req.username, req.password, loginIdentity)
        }
        val authResult = authAttempt.getOrElse { e ->
            val invalidCredentials = e is IllegalArgumentException
            call.respond(
                if (invalidCredentials) HttpStatusCode.Unauthorized else HttpStatusCode.ServiceUnavailable,
                mapOf("error" to if (invalidCredentials) "Invalid username or password" else "Could not reach Jellyfin"),
            )
            return@post
        }
        val (device, deviceToken) = deviceService.loginDevice(
            deviceId = req.deviceId,
            deviceName = req.deviceName,
            jellyfinUserId = authResult.user.id,
            jellyfinUsername = authResult.user.name,
            jellyfinUserToken = authResult.accessToken,
            isAdmin = authResult.user.policy.isAdministrator,
            isKids = authResult.user.policy.maxParentalRating != null,   // R18: parental cap ⇒ Kids profile
            // Phase 142 — AuthenticateByName already returns the full Policy inline, so this needs no
            // extra Jellyfin call. EnableAllFolders ⇒ unrestricted (null); else the enabled set (RAW —
            // loginDevice normalizes it).
            allowedLibraries = if (authResult.user.policy.enableAllFolders) null else authResult.user.policy.enabledFolders.toSet(),
            // Phase 142 follow-up — same policy response, its AllowedTags/BlockedTags (RAW; loginDevice
            // lowercases them).
            allowedTags = authResult.user.policy.allowedTags.toSet(),
            blockedTags = authResult.user.policy.blockedTags.toSet(),
        )
        call.respond(PairResult(
            session = TvSession(
                deviceId = device.deviceId,
                userId = device.jellyfinUserId,
                displayName = device.jellyfinUsername,
                isAdmin = device.isAdmin,
                isKids = device.isKids,
                avatarUrl = RaviloImageUrl.avatar(device.jellyfinUserId),
            ),
            deviceToken = deviceToken,
        ))
    }

    post("/tv/pair/unpair") {
        val device = runCatching { call.attributes[DeviceKey] }.getOrNull()
            ?: run {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "No device session"))
                return@post
            }
        deviceService.unpair(device.deviceToken)
        call.respond(mapOf("status" to "unpaired"))
    }

    // ── Multi-user sessions ──────────────────────────────────────────────────
    get("/tv/sessions") {
        val device = call.attributes[DeviceKey]
        val sessions = deviceService.listSessions(device.deviceId).map { d ->
            TvSession(
                deviceId = d.deviceId,
                userId = d.jellyfinUserId,
                displayName = d.jellyfinUsername,
                isAdmin = d.isAdmin,
                isKids = d.isKids,
                avatarUrl = RaviloImageUrl.avatar(d.jellyfinUserId),
            )
        }
        call.respond(sessions)
    }

    delete("/tv/sessions/{userId}") {
        val device = call.attributes[DeviceKey]
        val userId = call.parameters["userId"] ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "userId required")); return@delete
        }
        deviceService.removeSession(device.deviceId, userId)
        call.respond(mapOf("status" to "removed"))
    }

    // ── Home feed ────────────────────────────────────────────────────────────
    get("/tv/home") {
        val device = call.attributes[DeviceKey]
        call.respond(homeFeedService.getHomeFeed(device))
    }

    get("/tv/channel/{id}") {
        val device = call.attributes[DeviceKey]
        val channelId = call.parameters["id"] ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing channel id"))
            return@get
        }
        call.respond(homeFeedService.getChannelFeed(device, channelId))
    }

    // ── Detail ───────────────────────────────────────────────────────────────
    get("/tv/movie/{id}") {
        val device = call.attributes[DeviceKey]
        val id = call.parameters["id"] ?: run { call.respond(HttpStatusCode.BadRequest); return@get }
        val detail = detailService.getMovieDetail(device, id)
        if (detail == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "Movie not found"))
        else call.respond(detail)
    }

    get("/tv/series/{id}") {
        val device = call.attributes[DeviceKey]
        val id = call.parameters["id"] ?: run { call.respond(HttpStatusCode.BadRequest); return@get }
        val detail = detailService.getSeriesDetail(device, id)
        if (detail == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "Series not found"))
        else call.respond(detail)
    }

    // ── R83: bulk play-state ─────────────────────────────────────────────────
    get("/tv/playstate") {
        val device = call.attributes[DeviceKey]
        val raw = call.request.queryParameters["ids"] ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ids required")); return@get
        }
        val ids = raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val result: Map<String, CardPlayState> = detailService.getPlaystate(device, ids)
        call.respond(result)
    }

    // ── Browse, search, facets ───────────────────────────────────────────────
    get("/tv/browse") {
        val device = call.attributes[DeviceKey]
        val kind     = call.request.queryParameters["kind"]
        val sort     = call.request.queryParameters["sort"]
        val page     = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
        // R118: absent pageSize ⇒ null ⇒ full filtered set (no 40-item default cap).
        val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull()
        val genres   = call.request.queryParameters.getAll("genre")   ?: emptyList()
        val studios  = call.request.queryParameters.getAll("studio")  ?: emptyList()
        val networks = call.request.queryParameters.getAll("network") ?: emptyList()
        val tags     = call.request.queryParameters.getAll("tag")     ?: emptyList()
        call.respond(browseService.browse(device, kind, genres, studios, networks, tags, sort, page, pageSize))
    }

    // R187 (§G-4) — Continue Watching's own "→ See all": not a ConditionGroup seed (see
    // HomeFeedService.continueWatchingAll's doc comment), so it's a dedicated endpoint, not a
    // /tv/browse/seeded call. Plain MediaCards, not BrowseCard — Continue Watching's own See-all page
    // doesn't offer the catalog facet bar (genre/quality/etc.), just the viewer's full in-progress list.
    get("/tv/continue/all") {
        val device = call.attributes[DeviceKey]
        call.respond(homeFeedService.continueWatchingAll(device))
    }

    // R187 — the "→ See all" browse page's seed resolver: POST (not GET) because the seed is a
    // ConditionGroup tree, not flat query params. Returns the FULL matching set — see
    // BrowseService.browseByQuery's doc comment for why no pagination/narrowed-facets round trip.
    post("/tv/browse/seeded") {
        val device = call.attributes[DeviceKey]
        val req = runCatching { call.receive<SeededBrowseRequest>() }.getOrDefault(SeededBrowseRequest())
        call.respond(browseService.browseByQuery(device, req.query, req.mediaKind))
    }

    get("/tv/search") {
        val device = call.attributes[DeviceKey]
        val query = call.request.queryParameters["q"] ?: ""
        call.respond(browseService.search(device, query))
    }

    get("/tv/facets") {
        val device = call.attributes[DeviceKey]
        val kind = call.request.queryParameters["kind"]
        call.respond(browseService.facets(device, kind))
    }

    // ── Playback ─────────────────────────────────────────────────────────────
    post("/tv/playback/start") {
        val device = call.attributes[DeviceKey]
        val req = call.receive<PlaybackStartRequest>()
        call.respond(playbackService.startPlayback(device, req.itemId, req.capabilities))
    }

    post("/tv/playback/progress") {
        val device = call.attributes[DeviceKey]
        val req = call.receive<PlaybackProgressRequest>()
        playbackService.reportProgress(device, req.itemId, req.positionMs, req.isPaused)
        call.respond(mapOf("status" to "ok"))
    }

    post("/tv/playback/stop") {
        val device = call.attributes[DeviceKey]
        val req = call.receive<PlaybackStopRequest>()
        playbackService.stopPlayback(device, req.itemId, req.positionMs)
        call.respond(mapOf("status" to "ok"))
        // Bug fix: the stop used to be forwarded to Jellyfin and nothing else — the cached home feed
        // (5 min) kept serving the pre-stop Continue row, so a correct stop could stay invisible on
        // Home for minutes. Runs after responding so the client's stop ack isn't delayed by it.
        homeFeedService.invalidatePlaystate(device)
    }

    // R56: restream with a subtitle burned in (PGS encode path)
    post("/tv/playback/restream") {
        val device = call.attributes[DeviceKey]
        val req = call.receive<PlaybackRestreamRequest>()
        call.respond(playbackService.restream(device, req.itemId, req.subtitleStreamIndex, req.positionMs))
    }

    post("/tv/mark") {
        val device = call.attributes[DeviceKey]
        val req = call.receive<MarkRequest>()
        playbackService.mark(device, req.itemId, req.watched)
        call.respond(mapOf("status" to "ok"))
        homeFeedService.invalidatePlaystate(device)  // see /tv/playback/stop
    }

    // R142 — played/unplayed write-through (movie / episode / season / series). Returns the authoritative
    // per-id play-state for the item + affected episodes so the client renders from the server result.
    put("/tv/played") {
        val device = call.attributes[DeviceKey]
        val req = call.receive<PlayedRequest>()
        call.respond(playbackService.setPlayed(device, req.itemId, req.played, req.episodeIds))
        // A finished episode must leave (and its successor enter) the Continue row now, not in 5 minutes.
        homeFeedService.invalidatePlaystate(device)  // see /tv/playback/stop
    }

    // ── Per-user config ──────────────────────────────────────────────────────
    get("/tv/config") {
        val device = call.attributes[DeviceKey]
        call.respond(raviloConfigService.getConfig(device.jellyfinUserId))
    }

    // R141: degrade-to-poll fallback — client polls this when the WS is down (or as a safety net).
    // Returns the monotonic config-change rev so the client can detect a missed event and self-heal.
    get("/tv/config/rev") {
        val rev = tvEventBus?.currentRev() ?: 0L
        call.respond(mapOf("rev" to rev))
    }

    // R171 — the TV's Request tab: Seerr-backed discover feeds (Phase 137 row config), replacing the
    // chart engine Phase 136 retired. `seerrDiscoverService` is null only if Main.kt didn't wire it
    // (shouldn't happen outside tests) — falls back to unavailable rather than 500ing.
    get("/tv/discover") {
        val device = call.attributes[DeviceKey]
        val resp = seerrDiscoverService?.getRequestFeeds(device.jellyfinUserId, device.isAdmin, device.isKids)
            ?: DiscoverResponse(available = false, canRequest = false)
        call.respond(resp)
    }

    // Phase 139 §D.2 — the viewer's own not-yet-available requests (the Request tab's "In progress"
    // rail), so a strict-waiting choice made days ago is easy to find again and change.
    get("/tv/discover/requests/mine") {
        val device = call.attributes[DeviceKey]
        call.respond(seerrDiscoverService?.getMyRequests(device.jellyfinUserId) ?: emptyList())
    }

    // R160 — the calendar is the same for every viewer (no per-user scoping), server-cached with a
    // short TTL (UpcomingService) so opening the tab never fans out a live Sonarr/Radarr round-trip.
    get("/tv/upcoming") {
        call.attributes[DeviceKey]  // auth only; no per-device personalization
        call.respond(upcomingService?.getUpcoming() ?: dev.jellystructure.shared.tv.UpcomingFeed(enabled = false))
    }

    // R167 — not-held-item detail (Discover-detail parity: live TMDB genres/runtime/cast). Best-effort:
    // a lookup miss still returns 404 so the client falls back to the plain feed item it already has,
    // never a blank screen.
    get("/tv/upcoming/item/{id}") {
        call.attributes[DeviceKey]  // auth only
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val detail = upcomingService?.getDetail(id) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(detail)
    }

    // R171 — request detail: {mediaType}=movie|tv, {tmdbId}=TMDB id (addressing changed from the
    // retired chart flow's listId+rank, since Request rows have no rank concept).
    get("/tv/discover/item/{mediaType}/{tmdbId}") {
        val device = call.attributes[DeviceKey]
        val mediaType = call.parameters["mediaType"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val tmdbId = call.parameters["tmdbId"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
        val detail = seerrDiscoverService?.getEntry(mediaType, tmdbId, device.jellyfinUserId, device.isKids) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(detail)
    }

    post("/tv/discover/request") {
        val device = call.attributes[DeviceKey]
        val req = call.receive<TvDiscoverRequest>()
        val tmdbId = req.tmdbId ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no tmdbId"))
        val service = seerrDiscoverService
            ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "discover request unavailable"))
        call.respond(service.request(device.jellyfinUserId, device.isAdmin, req.mediaKind ?: "movie", tmdbId, req.title.orEmpty(), req.language, device.isKids))
    }

    // Phase 139 §E — switch a still-waiting request to a different language: re-profiles + re-searches
    // the *arr item and updates the persisted intent. 404 covers "nothing requested at that id" and
    // "request-language feature not configured" alike — the client shows the same generic failure either way.
    post("/tv/discover/request/{mediaType}/{tmdbId}/language") {
        val device = call.attributes[DeviceKey]
        val mediaType = call.parameters["mediaType"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val tmdbId = call.parameters["tmdbId"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
        val body = runCatching { call.receive<ChangeRequestLanguageBody>() }.getOrElse {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "language is required"))
        }
        val ok = seerrDiscoverService?.changeLanguage(device.jellyfinUserId, mediaType, tmdbId, body.language) ?: false
        if (ok) call.respond(HttpStatusCode.OK) else call.respond(HttpStatusCode.NotFound, mapOf("error" to "couldn't change language"))
    }

    // R171 — search scoped to the Seerr catalogue only (never the local library — that stays on the
    // AppBar search icon / GET /tv/search). Results are request tiles, same live-status derivation as
    // the discover feed rows.
    get("/tv/search/seerr") {
        call.attributes[DeviceKey]
        val q = call.request.queryParameters["q"].orEmpty()
        val items = seerrDiscoverService?.search(q) ?: emptyList()
        call.respond(dev.jellystructure.shared.tv.SeerrSearchResults(query = q, items = items))
    }

    // Admin config endpoints — authenticated by session cookie (jellystructure admin login)
    // R51: ?scope=global → global layout; ?userId=<id> → per-user override; default = session user.
    get("/tv/admin/config") {
        val session = runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: run { call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in")); return@get }
        val qScope = call.request.queryParameters["scope"]
        val userId = when {
            qScope == "global" -> dev.jellystructure.tv.GLOBAL_USER_ID
            else -> call.request.queryParameters["userId"] ?: session.jellyfinUserId
        }
        val config = if (userId == dev.jellystructure.tv.GLOBAL_USER_ID) raviloConfigService.getGlobalConfig()
                     else raviloConfigService.getConfig(userId)
        val hasOverride = raviloConfigService.hasCustomConfig(userId)
        call.respond(AdminConfigEnvelope(config, hasOverride, userId == dev.jellystructure.tv.GLOBAL_USER_ID))
    }

    // Phase 111 (FR D.1) — paired-devices list for the Ravilo config editor's Pair-a-TV area (name,
    // last seen, connected, and eventually Phase 110's "re-pair" chip). Same shape as /api/remote/devices
    // minus the API-key fence — cookie-gated like the rest of Settings.
    get("/tv/admin/devices") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: run { call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in")); return@get }
        val userId = call.request.queryParameters["userId"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "userId is required"))
        val devices = deviceService.listByUser(userId).map { d ->
            RemoteDevice(
                deviceId = d.deviceId,
                name = d.displayName,
                connected = tvEventBus?.isConnected(d.deviceId) ?: false,
                lastSeen = d.lastSeen,
                // Bug fix: this used to be the raw Jellyfin item id (a hex UUID) — resolve to a title.
                nowPlaying = dev.jellystructure.tv.nowPlayingItem(d.deviceId)?.let { id -> mediaStore?.titleForJellyfinId(id) ?: id },
            )
        }
        call.respond(devices)
    }

    delete("/tv/admin/devices/{deviceId}") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: run { call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in")); return@delete }
        val deviceId = call.parameters["deviceId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
        val userId = call.request.queryParameters["userId"]
            ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "userId is required"))
        deviceService.removeSession(deviceId, userId)
        call.respond(mapOf("ok" to true))
    }

    // Phase 143 — every Jellyfin user → their Ravilo devices + admin web sessions + policy summary, one
    // fetch. Supersedes the per-user get("/tv/admin/devices") above for the new "Users & devices" tab
    // (that route stays for any other caller). Now-playing is sourced from the same local
    // PlaybackService.activePlayback map /tv/admin/devices already reads — no extra Jellyfin call.
    get("/tv/admin/overview") {
        val session = runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        val config = configStore.current
        val allDevices = deviceService.allDevices()
        val allSessions = sessionService.list()
        // Every admin's full Policy, in one call — already fetched for admin login elsewhere; reused
        // here rather than a per-user round-trip. Skipped gracefully if Jellyfin isn't configured yet.
        val jfPolicies = if (config.apiKeys.jellyfinUrl.isNotBlank() && config.apiKeys.jellyfinToken.isNotBlank()) {
            jellyfinClient.getUsers(config.apiKeys.jellyfinUrl, config.apiKeys.jellyfinToken)
                .associate { it.id to it.policy }
        } else emptyMap()
        val totalLibraries = config.libraries.size

        val userIds = (allDevices.map { it.jellyfinUserId } + allSessions.map { it.jellyfinUserId }).distinct()
        val users = userIds.map { uid ->
            val userDevices = allDevices.filter { it.jellyfinUserId == uid }
            val userSessions = allSessions.filter { it.jellyfinUserId == uid }
            val username = userDevices.firstOrNull()?.jellyfinUsername ?: userSessions.firstOrNull()?.jellyfinUsername ?: uid
            val policy = jfPolicies[uid]
            OverviewUser(
                userId = uid,
                username = username,
                policy = OverviewPolicy(
                    // Sessions exist only for admins (AuthRoutes 403s a non-admin login); fall back to
                    // that signal if the live Policy lookup missed (Jellyfin unreachable/unconfigured).
                    isAdmin = policy?.isAdministrator ?: (userDevices.any { it.isAdmin } || userSessions.isNotEmpty()),
                    allFolders = policy?.enableAllFolders ?: true,
                    libraryCount = policy?.takeUnless { it.enableAllFolders }?.enabledFolders?.size,
                    totalLibraries = totalLibraries,
                    allowedTags = policy?.allowedTags ?: emptyList(),
                    blockedTags = policy?.blockedTags ?: emptyList(),
                    maxRating = policy?.maxParentalRating,
                ),
                devices = userDevices.map { d ->
                    OverviewDevice(
                        deviceId = d.deviceId,
                        name = d.displayName,
                        connected = tvEventBus?.isConnected(d.deviceId) ?: false,
                        isAdmin = d.isAdmin,
                        isKids = d.isKids,
                        createdAt = d.createdAt,
                        lastSeen = d.lastSeen,
                        // Bug fix: this used to be the raw Jellyfin item id (a hex UUID) — resolve to a title.
                        nowPlaying = dev.jellystructure.tv.nowPlayingItem(d.deviceId)?.let { id -> mediaStore?.titleForJellyfinId(id) ?: id },
                    )
                },
                sessions = userSessions.map { s ->
                    OverviewSession(
                        id = s.token.take(SESSION_ID_PREFIX_LEN),
                        createdAt = s.createdAt,
                        lastUsedAt = s.lastUsedAt,
                        expiresAt = s.expiresAt,
                        isCurrent = s.token == session.token,
                    )
                },
            )
        }
        call.respond(users)
    }

    // Phase 143 — revoke one admin web session by its wire-safe id prefix (never the raw token; see
    // OverviewSession). Resolving the prefix back to a full token is an in-memory scan over `list()` —
    // the session count is always small (tens at most), so this is cheap.
    delete("/tv/admin/sessions/{id}") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@delete call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
        val match = sessionService.list().firstOrNull { it.token.startsWith(id) }
            ?: return@delete call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
        sessionService.revoke(match.token)
        call.respond(mapOf("ok" to true))
    }

    // Phase 143 — "sign out everywhere": revokes every device token AND every admin web session this
    // Jellyfin user holds, in one action.
    post("/tv/admin/users/{userId}/signout-all") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        val userId = call.parameters["userId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        deviceService.deleteAllForUser(userId)
        sessionService.revokeAllForUser(userId)
        call.respond(mapOf("ok" to true))
    }

    // Phase 143 (design addendum) — "Recently watched": the one section of the overview that must read
    // Jellyfin live (no local play-history store). Strictly lazy, one user at a time, behind the FE's
    // "Show more" expander — never fanned out across all users on the base overview.
    get("/tv/admin/users/{userId}/history") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        val userId = call.parameters["userId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val config = configStore.current
        if (config.apiKeys.jellyfinUrl.isBlank() || config.apiKeys.jellyfinToken.isBlank()) {
            call.respond(WatchHistoryPage(emptyList(), false)); return@get
        }
        val raw = jellyfinClient.getRecentlyPlayed(
            config.apiKeys.jellyfinUrl, config.apiKeys.jellyfinToken, userId,
            limit = HISTORY_PAGE_SIZE, startIndex = offset,
        )
        // Caveat (documented, not a bug): a binge run can straddle a page boundary, splitting one
        // logical group across two "Show more" pages — acceptable for a history view, not a spec violation.
        val finishedEntries = groupHistoryEntries(raw)
        // Phase 143 follow-up — fold in "stopped at N%" (in-progress) items, first page only: there are
        // only ever a handful at once (Continue Watching, not a growing history), so no pagination is
        // needed for these; re-fetching them on every "Show more" click would be wasted work. Reuses
        // getResumeItems (built for the device's own paired token) with the ADMIN token instead — the
        // same "admin token, arbitrary userId" pattern getRecentlyPlayed/getUsers already rely on.
        val entries = if (offset == 0) {
            val resumable = jellyfinClient.getResumeItems(config.apiKeys.jellyfinUrl, config.apiKeys.jellyfinToken, userId, limit = 10)
            val inProgress = resumable.mapNotNull { item ->
                val userData = item.userData ?: return@mapNotNull null
                val playedAt = userData.lastPlayedDate?.let { dev.jellystructure.util.isoToEpochSeconds(it) } ?: return@mapNotNull null
                WatchHistoryEntry(
                    title = item.seriesName ?: item.name,
                    episodeLabel = if (item.seriesId != null) {
                        "S${(item.seasonNumber ?: 0).toString().padStart(2, '0')} · E${(item.episodeNumber ?: 0).toString().padStart(2, '0')}"
                    } else null,
                    firstPlayedAt = playedAt,
                    lastPlayedAt = playedAt,
                    finished = false,
                    progressPct = userData.playedPercentage?.toInt(),
                )
            }
            (finishedEntries + inProgress).sortedByDescending { it.lastPlayedAt }
        } else finishedEntries
        call.respond(WatchHistoryPage(entries = entries, hasMore = raw.size == HISTORY_PAGE_SIZE))
    }

    put("/tv/admin/config") {
        val session = runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: run { call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in")); return@put }
        val qScope = call.request.queryParameters["scope"]
        val userId = when {
            qScope == "global" -> dev.jellystructure.tv.GLOBAL_USER_ID
            else -> call.request.queryParameters["userId"] ?: session.jellyfinUserId
        }
        val config = runCatching { call.receive<RaviloConfig>() }.getOrElse {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid config: ${it.message}")); return@put
        }
        raviloConfigService.validate(config)?.let { err ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to err)); return@put
        }
        raviloConfigService.save(userId, config)
        call.respond(mapOf("status" to "ok"))
    }

    delete("/tv/admin/config") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: run { call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in")); return@delete }
        val userId = call.request.queryParameters["userId"]
            ?: run { call.respond(HttpStatusCode.BadRequest, mapOf("error" to "userId required")); return@delete }
        if (userId == dev.jellystructure.tv.GLOBAL_USER_ID) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Cannot delete the global config")); return@delete
        }
        raviloConfigService.removeCustomConfig(userId)
        call.respond(mapOf("status" to "ok"))
    }

    // R162: the field-level behaviour & preferences overlay — independent of /tv/admin/config above
    // (the R51 layout override). Never gated by "has a custom layout"; editable in both scopes.
    get("/tv/admin/behaviour") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: run { call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in")); return@get }
        val userId = call.request.queryParameters["userId"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "userId is required"))
        call.respond(raviloConfigService.resolveBehaviour(userId))
    }

    // PUT body: same shape as ViewerSettingsRequest — only the one field being changed is non-null.
    // Global scope (?scope=global) edits the global defaults directly (via the layout config save path,
    // unchanged); this route only ever writes a per-user overlay entry, so scope=global is rejected.
    put("/tv/admin/behaviour") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: run { call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in")); return@put }
        val userId = call.request.queryParameters["userId"]
            ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "userId is required"))
        if (userId == dev.jellystructure.tv.GLOBAL_USER_ID) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Set the global default via /tv/admin/config instead")); return@put
        }
        val req = runCatching { call.receive<ViewerSettingsRequest>() }.getOrElse {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request: ${it.message}")); return@put
        }
        req.skin?.let { raviloConfigService.setAdminSkin(userId, it) }
        req.tileShape?.let { raviloConfigService.setAdminTileShape(userId, it) }
        req.showContinueProgress?.let { raviloConfigService.setAdminShowContinueProgress(userId, it) }
        req.autoplayNext?.let { raviloConfigService.setAdminAutoplayNext(userId, it) }
        req.uiLanguage?.let { raviloConfigService.setAdminUiLanguage(userId, it) }
        req.requestLanguage?.let { raviloConfigService.setAdminRequestLanguage(userId, it) }
        req.skipIntro?.let { raviloConfigService.setAdminSkipIntro(userId, it) }
        req.skipCredits?.let { raviloConfigService.setAdminSkipCredits(userId, it) }
        req.skipSecs?.let { raviloConfigService.setAdminSkipSecs(userId, it) }
        call.respond(raviloConfigService.resolveBehaviour(userId))
    }

    delete("/tv/admin/behaviour") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: run { call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in")); return@delete }
        val userId = call.request.queryParameters["userId"]
            ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "userId is required"))
        val field = call.request.queryParameters["field"]
            ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "field is required"))
        raviloConfigService.resetBehaviourField(userId, field)
        call.respond(raviloConfigService.resolveBehaviour(userId))
    }

    // ── Channel-logo asset library (R36 §F) ──────────────────────────────────
    // R133: public artwork — serves jellystructure's OWN on-disk poster/backdrop/logo (resized + cached),
    // no Jellyfin call (AuthPlugin OPEN_API_PATHS; Coil can't attach a token, images aren't sensitive).
    get("/tv/image/{itemId}/{type}") {
        val itemId = call.parameters["itemId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val type   = call.parameters["type"]   ?: return@get call.respond(HttpStatusCode.BadRequest)
        val svc    = imageProxyService ?: return@get call.respond(HttpStatusCode.ServiceUnavailable)
        val width  = call.request.queryParameters["w"]?.toIntOrNull()  // R93: optional width
        val result = svc.serve(itemId, type, width) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondCachedBytes(result.first, ContentType.parse(result.second))
    }
    // R133: episode still — addressed by series id + episode filename. Phase 149: ?ep= disambiguates
    // when several episodes share that filename (a multi-episode file).
    get("/tv/image/{itemId}/still/{epFilename}") {
        val itemId = call.parameters["itemId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val epFilename = call.parameters["epFilename"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val svc = imageProxyService ?: return@get call.respond(HttpStatusCode.ServiceUnavailable)
        val width = call.request.queryParameters["w"]?.toIntOrNull()
        val epNum = call.request.queryParameters["ep"]?.toIntOrNull()
        val result = svc.serveStill(itemId, epFilename, epNum, width) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondCachedBytes(result.first, ContentType.parse(result.second))
    }
    // R133: user avatar — the one remaining (cached) Jellyfin fetch; reused by the admin pairing UI.
    get("/tv/image/user/{userId}/avatar") {
        val userId = call.parameters["userId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val svc = imageProxyService ?: return@get call.respond(HttpStatusCode.ServiceUnavailable)
        val result = svc.serveAvatar(userId) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondCachedBytes(result.first, ContentType.parse(result.second))
    }

    // Admin (cookie) uploads + lists; the serve route is public (AuthPlugin OPEN_API_PATHS) so the TV
    // can load a channel's logoUrl without a device token — brand logos are not sensitive.
    get("/tv/admin/channel-logos") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        call.respond(channelLogoStore.list())
    }
    post("/tv/admin/channel-logos") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        val req = runCatching { call.receive<ChannelLogoUpload>() }.getOrElse {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid upload")); return@post
        }
        val data = runCatching {
            @OptIn(ExperimentalEncodingApi::class)
            Base64.decode(req.dataBase64.substringAfterLast(",")) // tolerate a data: URL prefix
        }.getOrNull()
        if (data == null || data.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid base64 data")); return@post
        }
        val logo = channelLogoStore.save(req.filename, data)
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "unsupported file — use PNG/SVG/JPG/WebP up to 2 MB"))
        call.respond(logo)
    }
    // Public serve — see AuthPlugin OPEN_API_PATHS.
    get("/tv/channel-logos/{name}") {
        val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val data = channelLogoStore.read(name) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondCachedBytes(data, ContentType.parse(channelLogoStore.contentType(name)))
    }

    put("/tv/settings") {
        val device = call.attributes[DeviceKey]
        val req = call.receive<ViewerSettingsRequest>()
        raviloConfigService.applyViewerSettings(
            userId = device.jellyfinUserId,
            skin = req.skin,
            showContinueProgress = req.showContinueProgress,
            autoplayNext = req.autoplayNext,
            tileShape = req.tileShape,
            uiLanguage = req.uiLanguage,
        )
        call.respond(mapOf("status" to "ok"))
    }
}
