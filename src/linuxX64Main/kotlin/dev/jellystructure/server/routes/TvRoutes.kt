package dev.jellystructure.server.routes

import dev.jellystructure.auth.DeviceKey
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.SessionKey
import dev.jellystructure.auth.SessionService
import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.arr.AcquisitionService
import dev.jellystructure.shared.tv.AcquisitionRecord
import dev.jellystructure.shared.tv.AcquisitionStatus
import dev.jellystructure.shared.tv.ChartEntry
import dev.jellystructure.shared.tv.DiscoverDetail
import dev.jellystructure.shared.tv.DiscoverEntry
import dev.jellystructure.shared.tv.DiscoverResponse
import dev.jellystructure.shared.tv.DiscoverRow
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.Person
import dev.jellystructure.shared.tv.ChannelLogoUpload
import dev.jellystructure.shared.tv.MarkRequest
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.PairingChallenge
import dev.jellystructure.shared.tv.PairResult
import dev.jellystructure.shared.tv.PlaybackProgressRequest
import dev.jellystructure.shared.tv.PlaybackStartRequest
import dev.jellystructure.shared.tv.PlaybackStopRequest
import dev.jellystructure.shared.tv.TvSession
import dev.jellystructure.shared.tv.ViewerSettingsRequest
import dev.jellystructure.tv.BrowseService
import dev.jellystructure.tv.ChannelLogoStore
import dev.jellystructure.tv.DetailService
import dev.jellystructure.tv.HomeFeedService
import dev.jellystructure.tv.PlaybackService
import dev.jellystructure.tv.RaviloConfigService
import dev.jellystructure.tv.RaviloDeviceService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
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
private data class PollRequest(
    @SerialName("poll_token") val pollToken: String,
    @SerialName("device_id") val deviceId: String? = null,
)


@Serializable
private data class ApproveRequest(
    val code: String,
    val username: String? = null,
    val password: String? = null,
)

@Serializable
private data class TvDiscoverRequest(
    val listId: String? = null,
    val rank: Int? = null,
    val mediaKind: String? = null,
    val tmdbId: Int? = null,
)

// R48 — which *arr must be enabled to serve a given list (tv-* → Sonarr, else Radarr).
private fun servable(listId: String, cfg: AppConfig): Boolean =
    if (listId.startsWith("tv-")) cfg.sonarr?.enabled == true else cfg.radarr?.enabled == true

// R48 — derive the per-entry acquisition status: in-library → available; else the tracked record, else not_requested.
private fun acquisitionFor(e: ChartEntry, acq: AcquisitionService?): AcquisitionRecord {
    val itemId = e.itemId
    if (itemId != null)
        return AcquisitionRecord(itemId, e.kind, AcquisitionStatus.AVAILABLE, e.tmdbId, e.title, progress = 100, itemId = itemId)
    val key = e.tmdbId?.let { "tmdb:$it" }
        ?: return AcquisitionRecord("chart:${e.listId}:${e.rank}", e.kind, AcquisitionStatus.NOT_REQUESTED, e.tmdbId, e.title)
    return acq?.get(key) ?: AcquisitionRecord(key, e.kind, AcquisitionStatus.NOT_REQUESTED, e.tmdbId, e.title)
}

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
    acquisitionService: dev.jellystructure.arr.AcquisitionService? = null,
    chartStore: dev.jellystructure.chart.ChartStore? = null,
    chartRegistry: dev.jellystructure.chart.ChartRegistry? = null,
    tmdbClient: dev.jellystructure.tmdb.TmdbClient? = null,
) {
    route("/tv/pair") {
        post("/start") {
            val result = deviceService.startPairing()
            call.respond(PairingChallenge(
                code = result.code,
                pollToken = result.pollToken,
                expiresAt = result.expiresAt,
            ))
        }

        post("/poll") {
            val req = call.receive<PollRequest>()
            val result = deviceService.pollPairing(req.pollToken, req.deviceId)
            if (result == null) {
                call.respond(HttpStatusCode.Accepted, mapOf("status" to "pending"))
                return@post
            }
            val (device, deviceToken) = result
            val baseUrl = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
            call.respond(PairResult(
                session = TvSession(
                    deviceId = device.deviceId,
                    userId = device.jellyfinUserId,
                    displayName = device.jellyfinUsername,
                    isAdmin = device.isAdmin,
                    avatarUrl = if (baseUrl.isNotBlank()) "$baseUrl/Users/${device.jellyfinUserId}/Images/Primary?api_key=${device.jellyfinUserToken}" else null,
                ),
                deviceToken = deviceToken,
            ))
        }

        // Approver is either an already-signed-in jellystructure admin (cookie)
        // or any Jellyfin user supplying credentials directly (phone form).
        post("/approve") {
            val req = call.receive<ApproveRequest>()

            val cookieToken = call.request.cookies["js_session"]
            val cookieSession = cookieToken?.let { sessionService.validate(it) }

            val jellyfinUserId: String
            val jellyfinUsername: String
            val jellyfinUserToken: String
            val isAdmin: Boolean

            if (cookieSession != null) {
                jellyfinUserId = cookieSession.jellyfinUserId
                jellyfinUsername = cookieSession.jellyfinUsername
                jellyfinUserToken = cookieSession.jellyfinUserToken
                isAdmin = true
            } else if (req.username != null && req.password != null) {
                val config = configStore.current
                if (config.apiKeys.jellyfinUrl.isBlank()) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Jellyfin not configured"))
                    return@post
                }
                val authResult = runCatching {
                    jellyfinClient.authenticateByName(config.apiKeys.jellyfinUrl, req.username, req.password)
                }.getOrElse {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid Jellyfin credentials"))
                    return@post
                }
                jellyfinUserId = authResult.user.id
                jellyfinUsername = authResult.user.name
                jellyfinUserToken = authResult.accessToken
                isAdmin = authResult.user.policy.isAdministrator
            } else {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                return@post
            }

            val approved = deviceService.approvePairing(
                code = req.code,
                jellyfinUserId = jellyfinUserId,
                jellyfinUsername = jellyfinUsername,
                jellyfinUserToken = jellyfinUserToken,
                isAdmin = isAdmin,
            )
            if (!approved) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Invalid or expired pairing code"))
                return@post
            }
            call.respond(mapOf("status" to "approved"))
        }

        post("/unpair") {
            val device = runCatching { call.attributes[DeviceKey] }.getOrNull()
                ?: run {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "No device session"))
                    return@post
                }
            deviceService.unpair(device.deviceToken)
            call.respond(mapOf("status" to "unpaired"))
        }
    }

    // ── Multi-user sessions ──────────────────────────────────────────────────
    get("/tv/sessions") {
        val device = call.attributes[DeviceKey]
        val baseUrl = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        val sessions = deviceService.listSessions(device.deviceId).map { d ->
            TvSession(
                deviceId = d.deviceId,
                userId = d.jellyfinUserId,
                displayName = d.jellyfinUsername,
                isAdmin = d.isAdmin,
                avatarUrl = if (baseUrl.isNotBlank()) "$baseUrl/Users/${d.jellyfinUserId}/Images/Primary?api_key=${d.jellyfinUserToken}" else null,
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

    // ── Browse, search, facets ───────────────────────────────────────────────
    get("/tv/browse") {
        val device = call.attributes[DeviceKey]
        val kind     = call.request.queryParameters["kind"]
        val sort     = call.request.queryParameters["sort"]
        val page     = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
        val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull() ?: 40
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
        val ticket = playbackService.startPlayback(device, req.itemId, req.capabilities)
        if (ticket == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "Item not found"))
        else call.respond(ticket)
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

    post("/tv/mark") {
        val device = call.attributes[DeviceKey]
        val req = call.receive<MarkRequest>()
        playbackService.mark(device, req.itemId, req.watched)
        call.respond(mapOf("status" to "ok"))
    }

    // ── Per-user config ──────────────────────────────────────────────────────
    get("/tv/config") {
        val device = call.attributes[DeviceKey]
        call.respond(raviloConfigService.getConfig(device.jellyfinUserId))
    }

    // R48 — Discover / Top 10. Composes the per-user list selection (R04) + Phase 57 charts +
    // Phase 56 live acquisition status. Server decides availability/gating; the TV never re-derives it.
    get("/tv/discover") {
        val device = call.attributes[DeviceKey]
        val d = raviloConfigService.getConfig(device.jellyfinUserId).discover
        val cfg = configStore.current
        val specs = chartRegistry?.get(d.source)?.availableLists(d.region).orEmpty()
        val acqEnabled = cfg.acquisition?.enabled == true
        // Tab shows when *arr is connected; acquisition engine only gates the request button, not browsing.
        val available = d.enabled && d.lists.isNotEmpty() && d.lists.any { servable(it, cfg) }
        if (!available) {
            call.respond(DiscoverResponse(false, d.source, d.region, false)); return@get
        }
        val rows = d.lists.mapNotNull { listId ->
            val spec = specs.firstOrNull { it.id == listId } ?: return@mapNotNull null
            val entries = (chartStore?.entries(listId).orEmpty()).map { DiscoverEntry(it, acquisitionFor(it, acquisitionService)) }
            DiscoverRow(spec, entries)
        }
        call.respond(DiscoverResponse(true, d.source, d.region, acqEnabled && (device.isAdmin || d.canRequest), rows))
    }

    get("/tv/discover/item/{listId}/{rank}") {
        val device = call.attributes[DeviceKey]
        val listId = call.parameters["listId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val rank = call.parameters["rank"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
        val entry = (chartStore?.entries(listId).orEmpty()).firstOrNull { it.rank == rank }
            ?: return@get call.respond(HttpStatusCode.NotFound)
        val d = raviloConfigService.getConfig(device.jellyfinUserId).discover
        val provider = chartRegistry?.get(d.source)
        // R63 — fetch TMDB genres, runtime, cast when tmdbId is known
        val isSeries = entry.kind == MediaKind.SERIES
        val tmdbId = entry.tmdbId
        val genres: List<String>
        val runtime: Int?
        val cast: List<Person>
        if (tmdbId != null && tmdbClient != null) {
            if (isSeries) {
                val det = tmdbClient.getTvDetails(tmdbId)
                val cred = tmdbClient.getTvCredits(tmdbId)
                genres = det?.genres?.map { it.name } ?: emptyList()
                runtime = det?.episodeRunTime?.firstOrNull()
                cast = cred.map { m -> Person(m.id.toString(), m.name, m.character.takeIf { it.isNotBlank() }, m.profilePath?.let { "https://image.tmdb.org/t/p/w185$it" }) }
            } else {
                val det = tmdbClient.getMovieDetails(tmdbId)
                val cred = tmdbClient.getMovieCredits(tmdbId)
                genres = det?.genres?.map { it.name } ?: emptyList()
                runtime = det?.runtime
                cast = cred.map { m -> Person(m.id.toString(), m.name, m.character.takeIf { it.isNotBlank() }, m.profilePath?.let { "https://image.tmdb.org/t/p/w185$it" }) }
            }
        } else {
            genres = emptyList(); runtime = null; cast = emptyList()
        }
        call.respond(
            DiscoverDetail(
                entry = entry,
                acquisition = acquisitionFor(entry, acquisitionService),
                sourceLabel = provider?.displayName ?: d.source,
                attribution = provider?.attribution ?: "",
                genres = genres,
                runtime = runtime,
                isSeries = isSeries,
                cast = cast,
            )
        )
    }

    post("/tv/discover/request") {
        val device = call.attributes[DeviceKey]
        val d = raviloConfigService.getConfig(device.jellyfinUserId).discover
        if (!(device.isAdmin || d.canRequest)) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not allowed to request — ask the owner")); return@post
        }
        if (acquisitionService == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "acquisition not available")); return@post
        }
        val req = call.receive<TvDiscoverRequest>()
        // Resolve the target either from a chart entry (listId+rank) or directly (mediaKind+tmdbId).
        val entry = if (req.listId != null && req.rank != null)
            (chartStore?.entries(req.listId).orEmpty()).firstOrNull { it.rank == req.rank } else null
        val tmdbId = entry?.tmdbId ?: req.tmdbId
        val kind = entry?.kind ?: when {
            req.mediaKind.equals("series", true) || req.mediaKind.equals("tv", true) -> MediaKind.SERIES
            else -> MediaKind.MOVIE
        }
        val title = entry?.title ?: ""
        if (tmdbId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no tmdbId for this title")); return@post
        }
        call.respond(acquisitionService.request(kind, tmdbId, title, requestedBy = device.jellyfinUserId))
    }

    // Admin config endpoints — authenticated by session cookie (jellystructure admin login)
    get("/tv/admin/config") {
        val session = runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: run { call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in")); return@get }
        val userId = call.request.queryParameters["userId"] ?: session.jellyfinUserId
        call.respond(raviloConfigService.getConfig(userId))
    }

    put("/tv/admin/config") {
        val session = runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: run { call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in")); return@put }
        val userId = call.request.queryParameters["userId"] ?: session.jellyfinUserId
        val config = runCatching { call.receive<RaviloConfig>() }.getOrElse {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid config: ${it.message}")); return@put
        }
        raviloConfigService.validate(config)?.let { err ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to err)); return@put
        }
        raviloConfigService.save(userId, config)
        call.respond(mapOf("status" to "ok"))
    }

    // ── Channel-logo asset library (R36 §F) ──────────────────────────────────
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
        call.respondBytes(data, ContentType.parse(channelLogoStore.contentType(name)))
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
        )
        call.respond(mapOf("status" to "ok"))
    }
}
