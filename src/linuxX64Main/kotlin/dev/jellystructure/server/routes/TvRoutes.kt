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

    get("/tv/search") {
        val device = call.attributes[DeviceKey]
        val query = call.request.queryParameters["q"] ?: ""
        call.respond(browseService.search(device, query))
    }

    get("/tv/facets") {
        val kind = call.request.queryParameters["kind"]
        call.respond(browseService.facets(kind))
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
    }

    // R142 — played/unplayed write-through (movie / episode / season / series). Returns the authoritative
    // per-id play-state for the item + affected episodes so the client renders from the server result.
    put("/tv/played") {
        val device = call.attributes[DeviceKey]
        val req = call.receive<PlayedRequest>()
        call.respond(playbackService.setPlayed(device, req.itemId, req.played, req.episodeIds))
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
                nowPlaying = dev.jellystructure.tv.nowPlayingItem(d.deviceId),
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
    // R133: episode still — addressed by series id + episode filename.
    get("/tv/image/{itemId}/still/{epFilename}") {
        val itemId = call.parameters["itemId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val epFilename = call.parameters["epFilename"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val svc = imageProxyService ?: return@get call.respond(HttpStatusCode.ServiceUnavailable)
        val width = call.request.queryParameters["w"]?.toIntOrNull()
        val result = svc.serveStill(itemId, epFilename, width) ?: return@get call.respond(HttpStatusCode.NotFound)
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
