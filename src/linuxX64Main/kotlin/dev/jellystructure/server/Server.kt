package dev.jellystructure.server

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.SessionService
import dev.jellystructure.io.FileIo
import dev.jellystructure.log.Logger
import dev.jellystructure.media.ActivityLog
import dev.jellystructure.auth.installAuthPlugin
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.jobs.WsBroadcaster
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.tmdb.TmdbClient
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.Scanner
import dev.jellystructure.media.ScanTracker
import dev.jellystructure.server.routes.activityRoutes
import dev.jellystructure.server.routes.authRoutes
import dev.jellystructure.server.routes.configureConfigRoutes
import dev.jellystructure.server.routes.jellyfinRoutes
import dev.jellystructure.server.routes.jobsRoutes
import dev.jellystructure.server.routes.liveTvRoutes
import dev.jellystructure.tv.LiveTvService
import dev.jellystructure.server.routes.mediaRoutes
import dev.jellystructure.server.routes.setupRoutes
import dev.jellystructure.server.routes.trackRoutes
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.LogoDownloader
import dev.jellystructure.server.routes.metadataRoutes
import dev.jellystructure.server.routes.triageRoutes
import dev.jellystructure.server.routes.tvRoutes
import dev.jellystructure.arr.AcquisitionService
import dev.jellystructure.arr.ArrClient
import dev.jellystructure.arr.ArrPing
import dev.jellystructure.arr.ArrRescanService
import dev.jellystructure.server.routes.acquisitionRoutes
import dev.jellystructure.server.routes.remoteRoutes
import dev.jellystructure.server.routes.apiKeyManagementRoutes
import dev.jellystructure.server.routes.webhookRoutes
import dev.jellystructure.torrent.QBittorrentClient
import dev.jellystructure.torrent.SeedingGuard
import dev.jellystructure.torrent.SeedingSnapshot
import dev.jellystructure.tv.BrowseService
import dev.jellystructure.tv.DetailService
import dev.jellystructure.tv.HomeFeedService
import dev.jellystructure.tv.RaviloArtworkService
import dev.jellystructure.tv.PlaybackService
import dev.jellystructure.tv.ChannelLogoStore
import dev.jellystructure.tv.RaviloConfigService
import dev.jellystructure.tv.RaviloDeviceService
import dev.jellystructure.tv.TvEventBus
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import platform.posix.exit
import platform.posix.fgets
import platform.posix.pclose
import platform.posix.popen

fun startServer(
    configStore: ConfigStore,
    sessionService: SessionService,
    deviceService: RaviloDeviceService,
    raviloConfigService: RaviloConfigService,
    channelLogoStore: ChannelLogoStore,
    homeFeedService: HomeFeedService,
    browseService: BrowseService,
    detailService: DetailService,
    playbackService: PlaybackService,
    jellyfinClient: JellyfinClient,
    mediaStore: MediaStore,
    scanner: Scanner,
    artworkDownloader: ArtworkDownloader,
    tmdbClient: TmdbClient,
    scanTracker: ScanTracker,
    mediaHistory: MediaHistory,
    activityLog: ActivityLog,
    broadcaster: WsBroadcaster,
    frontendDir: String,
    raviloWebDir: String? = null,
    port: Int,
    scanDispatcher: CoroutineDispatcher,
    effectiveScanThreads: Int,
    jsTagStore: JsTagStore,
    seedingGuard: SeedingGuard,
    seedingSnapshot: SeedingSnapshot,
    logoDownloader: LogoDownloader,
    qbClient: QBittorrentClient? = null,
    arrClient: ArrClient? = null,
    arrRescan: ArrRescanService? = null,
    sonarrEnrich: dev.jellystructure.arr.SonarrEnrichService? = null,
    acquisitionService: AcquisitionService? = null,
    seerrClient: dev.jellystructure.seerr.SeerrClient? = null,
    tvEventBus: TvEventBus,
    imageProxyService: RaviloArtworkService? = null,
    mediaJobQueue: dev.jellystructure.media.MediaJobQueue,
    sessionBridge: dev.jellystructure.tv.JellyfinSessionBridge,
    apiKeyStore: dev.jellystructure.auth.ApiKeyStore,
    realtimeIngest: dev.jellystructure.media.RealtimeIngestService,
    libraryListener: dev.jellystructure.tv.JellyfinLibraryListener,
    fdWatchdog: dev.jellystructure.ops.FdWatchdog,
    imdbClient: dev.jellystructure.imdb.ImdbClient,
    upcomingService: dev.jellystructure.tv.UpcomingService? = null,
    requestLanguageService: dev.jellystructure.arr.RequestLanguageService? = null,
    requestIntentStore: dev.jellystructure.seerr.RequestIntentStore? = null,
    liveTvService: LiveTvService,
    fingerprintService: dev.jellystructure.media.FingerprintService,
): suspend () -> Unit {
    // Fire-and-forget work (scans, NFO/artwork pushes, image fetches) runs as appScope.launch{}.
    // On Kotlin/Native an exception escaping a launched coroutine reaches the global handler and
    // ABORTS the whole process (SIGABRT/134). A SupervisorJob keeps siblings alive, but only a
    // CoroutineExceptionHandler stops the abort: log it and keep the server running.
    val appScope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, e ->
        println("[ERROR] Uncaught background coroutine exception (server kept alive): ${e.message}")
        println(e.stackTraceToString())
    })
    // Ktor 3.x: the port-based embeddedServer overloads have no `configure` param — the configure
    // variant takes an environment + explicit connectors instead.
    val engine = embeddedServer(
        CIO,
        configure = {
            connectors.add(io.ktor.server.engine.EngineConnectorBuilder().apply { this.port = port })
            // Phase 118 (FR C.6), lowered by Phase 129 (FR-OPS1 §B.2) — the only inbound FD knob CIO
            // Native exposes. Trims idle keep-alive connections so they don't sit on the FD budget
            // twice as fast as before; a reverse proxy is the real concurrency cap for any internet-
            // facing deployment.
            connectionIdleTimeoutSeconds = 10
        },
    ) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(WebSockets) {
            pingPeriodMillis = 30_000L
            timeoutMillis = 15_000L
        }
        // Phase 118 (FR A.1) — CIO already isolates a handler exception from crashing the process; this
        // makes it observable and consistent instead of a bare connection drop.
        install(StatusPages) {
            // Security fix (2026-08-02 review, findings H2/M4) — these two are expected, "the request
            // isn't allowed" outcomes from PlaybackService, not bugs; give them their own status codes
            // instead of falling through to the generic 500 below (which is what the try/catch blocks
            // this replaced would have produced too, just duplicated at every call site).
            exception<dev.jellystructure.tv.JellyfinReauthRequiredException> { call, cause ->
                call.respond(HttpStatusCode.Conflict, mapOf("error" to (cause.message ?: "Re-authentication required")))
            }
            exception<dev.jellystructure.tv.PlaybackForbiddenException> { call, cause ->
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to (cause.message ?: "Forbidden")))
            }
            exception<Throwable> { call, cause ->
                // Logger.error writes both the log line and the Activity entry in one call.
                Logger.error("Unhandled route exception on ${call.request.path()}: ${cause.message}", "http")
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal server error"))
            }
            status(HttpStatusCode.NotFound) { call, status ->
                call.respond(status, mapOf("error" to "not found"))
            }
            status(HttpStatusCode.MethodNotAllowed) { call, status ->
                call.respond(status, mapOf("error" to "method not allowed"))
            }
        }
        install(CORS) {
            // Fully permissive: the Ravilo web client may be served from a different origin than
            // the backend (e.g. a dev server), so allow any origin, method, header and content type.
            anyHost()
            allowHeaders { true }              // includes Authorization (Bearer device token), Content-Type, Cookie…
            allowNonSimpleContentTypes = true  // application/json request bodies
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Head)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Delete)
            allowMethod(HttpMethod.Patch)
            allowMethod(HttpMethod.Options)
            allowCredentials = true
        }

        installAuthPlugin(sessionService, validateDeviceToken = { deviceService.validateDeviceToken(it) }, validateApiKey = { apiKeyStore.validate(it) })

        // Security fix (2026-08-02 review, finding H4) — shared between /api/auth/login and
        // /api/tv/login; see LoginRateLimiter's doc comment.
        val loginRateLimiter = dev.jellystructure.auth.LoginRateLimiter()

        installGzipCompression()

        // Phase 129 (FR-OPS1 §B.2) — global load shed, earliest pipeline phase so a shed response
        // costs the least possible work (no routing, no auth, no handler — none of which would open
        // further FDs). Replaces the old image/TV-events-only shed. `Connection: close` (not just the
        // 503) is the point: it makes the inbound FD live ~one request cycle instead of being held
        // open by keep-alive while under pressure. `/api/health` stays reachable so monitoring can see
        // why everything else is 503ing. While `draining` (the FR-OPS1 §D controlled-restart path),
        // sheds unconditionally — even the shed threshold itself doesn't matter anymore, the process
        // is on its way out.
        intercept(ApplicationCallPipeline.Setup) {
            if (call.request.path() != "/api/health" && (fdWatchdog.draining || fdWatchdog.isOverShedThreshold)) {
                call.response.headers.append(HttpHeaders.Connection, "close")
                call.response.headers.append(HttpHeaders.RetryAfter, "5")
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "server under FD pressure, retry shortly"))
                finish()
            }
        }

        routing {
            route("/api") {
                get("/health") {
                    // Phase 118 (FR C.4) — FD count on the lightweight probe too, so a monitoring
                    // scraper hitting this endpoint every few seconds doesn't need the /full checks.
                    // Phase 129 (FR-OPS1 §A.2) — the last-computed census (only recomputed at/above the
                    // 700 warn threshold, so this stays cheap on a healthy server) rides along too.
                    val census = fdWatchdog.lastCensus
                    call.respondText(
                        """{"status":"ok","fd_count":${fdWatchdog.currentCount},"fd_high_water_mark":${fdWatchdog.highWaterMark},"fd_census":${census?.toJson() ?: "null"}}""",
                        ContentType.Application.Json,
                    )
                }

                get("/health/full") {
                    @Serializable data class HealthCheck(val name: String, val ok: Boolean, val detail: String)
                    val checks = mutableListOf<HealthCheck>()
                    val cfg = configStore.current
                    // Phase 118 (FR C.4) — the FD budget itself. The 1024 ceiling is glibc's fd_set
                    // compile-time constant (FD_SETSIZE) — Ktor Native's selector crashes the whole
                    // process the moment any fd number reaches it (KTOR-8703, unfixed upstream).
                    val fdCount = fdWatchdog.currentCount
                    checks.add(HealthCheck(
                        "File descriptors",
                        fdCount < 700,
                        "$fdCount open (high water ${fdWatchdog.highWaterMark}) — warns at 700, alerts at 900, sheds load above 950, hard ceiling 1024",
                    ))
                    // Jellyfin connectivity
                    val jfOk = if (cfg.apiKeys.jellyfinUrl.isNotBlank() && cfg.apiKeys.jellyfinToken.isNotBlank()) {
                        runCatching { jellyfinClient.testConnection(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken) }.getOrDefault(false)
                    } else false
                    checks.add(HealthCheck("Jellyfin", jfOk, if (cfg.apiKeys.jellyfinUrl.isBlank()) "URL not configured" else if (jfOk) "Connected to ${cfg.apiKeys.jellyfinUrl}" else "Connection failed"))
                    // Check managed Jellyfin libraries for settings that conflict with Jellystructure
                    // taking over metadata management (e.g. NFO Metadata Saver overwrites our NFO files).
                    if (jfOk) {
                        val managedIds = cfg.libraries.filter { !it.skip }.map { it.jellyfinId }.toSet()
                        val jfLibs = runCatching { jellyfinClient.getLibraries(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken) }.getOrDefault(emptyList())
                        for (lib in jfLibs.filter { it.id in managedIds }) {
                            val savers = lib.libraryOptions?.metadataSavers ?: emptyList()
                            if ("Nfo" in savers) {
                                checks.add(HealthCheck(
                                    name = "Jellyfin library: ${lib.name}",
                                    ok = false,
                                    detail = "NFO Metadata Saver is ON — Jellyfin re-writes NFO files after every refresh, overwriting Jellystructure's metadata. Fix: Administration → Libraries → ⋯ Edit ${lib.name} → Metadata savers → uncheck Nfo"
                                ))
                            }
                        }
                    }
                    // TMDB key
                    // Security fix (2026-08-02 review, finding L11) — this used to shell out via curl
                    // with the key on the command line (visible in /proc/*/cmdline to any local user for
                    // the life of the call, and quote-stripped rather than escaped). The shared
                    // OutboundHttp client needs neither.
                    val tmdbKey = cfg.apiKeys.tmdbV3Key
                    val tmdbOk = if (tmdbKey.isNotBlank()) {
                        val result = runCatching {
                            dev.jellystructure.OutboundHttp.withPermit {
                                dev.jellystructure.OutboundHttp.client.get("https://api.themoviedb.org/3/configuration") {
                                    parameter("api_key", tmdbKey)
                                }.bodyAsText()
                            }
                        }.getOrNull()
                        result != null && !result.contains("\"status_code\":7") && !result.contains("\"status_code\":3")
                    } else false
                    checks.add(HealthCheck("TMDB API key", tmdbOk, if (tmdbKey.isBlank()) "Key not configured" else if (tmdbOk) "Valid" else "Invalid or unreachable"))
                    // Disk space
                    val dfOut = runShell("df -BM . 2>/dev/null | tail -1")
                    val freeMb = dfOut?.trim()?.split(Regex("\\s+"))?.getOrNull(3)?.trimEnd('M')?.toLongOrNull()
                    val diskOk = freeMb != null && freeMb > 1024
                    checks.add(HealthCheck("Disk space", diskOk, if (freeMb != null) "$freeMb MB free" else "Unknown"))
                    // mkvpropedit
                    val mkv = runShell("which mkvpropedit 2>/dev/null")?.trim()
                    checks.add(HealthCheck("mkvpropedit", !mkv.isNullOrBlank(), if (!mkv.isNullOrBlank()) mkv else "not found in PATH"))
                    // ffprobe
                    val ffp = runShell("which ffprobe 2>/dev/null")?.trim()
                    checks.add(HealthCheck("ffprobe", !ffp.isNullOrBlank(), if (!ffp.isNullOrBlank()) ffp else "not found in PATH"))
                    // Radarr / Sonarr (Phase 54) — probed only when enabled; an unreachable *arr is a
                    // non-fatal warning (it never fail-closes anything, unlike the qBittorrent guard).
                    cfg.radarr?.takeIf { it.enabled }?.let { r ->
                        val p = if (arrClient != null && r.url.isNotBlank()) arrClient.ping(r.url, r.apiKey) else ArrPing(false, "URL not configured")
                        checks.add(HealthCheck("Radarr", p.ok, if (p.ok) "Connected" + (p.version?.let { " · v$it" } ?: "") else p.detail))
                    }
                    cfg.sonarr?.takeIf { it.enabled }?.let { s ->
                        val p = if (arrClient != null && s.url.isNotBlank()) arrClient.ping(s.url, s.apiKey) else ArrPing(false, "URL not configured")
                        checks.add(HealthCheck("Sonarr", p.ok, if (p.ok) "Connected" + (p.version?.let { " · v$it" } ?: "") else p.detail))
                    }
                    // Phase 114 (FR B.3) — the Jellyfin LibraryChanged listener's own connection state.
                    if (cfg.ingest.realtime) {
                        val connected = libraryListener.connected
                        val lastEvent = libraryListener.lastEventAt?.let { " · last event ${dev.jellystructure.nowEpochSec() - it}s ago" } ?: ""
                        checks.add(HealthCheck("Realtime ingest listener", connected, (if (connected) "Connected" else "Disconnected — reconnecting") + lastEvent))
                    }
                    call.respond(mapOf("checks" to checks))
                }

                authRoutes(sessionService, jellyfinClient, configStore, loginRateLimiter)
                configureConfigRoutes(configStore, effectiveScanThreads, qbClient, arrClient, seerrClient, tmdbClient, requestLanguageService)
                setupRoutes(configStore, jellyfinClient)
                jellyfinRoutes(configStore, jellyfinClient)
                mediaRoutes(mediaStore, scanner, artworkDownloader, tmdbClient, appScope, scanTracker, broadcaster, jellyfinClient, configStore, mediaHistory, scanDispatcher, seedingGuard, seedingSnapshot, raviloConfigService, logoDownloader, arrRescan, sonarrEnrich, mediaJobQueue, imdbClient, fingerprintService)
                activityRoutes(activityLog)
                triageRoutes(mediaStore, jellyfinClient, configStore, mediaHistory, seedingGuard)
                metadataRoutes(mediaStore, jsTagStore, logoDownloader, seedingSnapshot, configStore)
                trackRoutes(mediaStore, configStore, jellyfinClient, mediaHistory, seedingGuard, arrRescan, appScope, broadcaster, mediaJobQueue)
                jobsRoutes(mediaJobQueue)
                remoteRoutes(deviceService, tvEventBus, mediaStore)
                apiKeyManagementRoutes(apiKeyStore)
                webhookRoutes(configStore, jellyfinClient, realtimeIngest, appScope, libraryListener)
                acquisitionService?.let { acquisitionRoutes(it) }
                // R171 — the TV Request tab's Seerr-backed discover/search/request service; null (tab
                // reports unavailable) until a SeerrClient is wired, exactly like the other optional *arr services above.
                val seerrDiscoverService = seerrClient?.let { dev.jellystructure.seerr.SeerrDiscoverService(configStore, it, raviloConfigService, mediaStore, requestLanguageService, requestIntentStore, acquisitionService) }
                tvRoutes(deviceService, raviloConfigService, homeFeedService, browseService, detailService, playbackService, sessionService, jellyfinClient, configStore, channelLogoStore, imageProxyService, tvEventBus, upcomingService, seerrDiscoverService, mediaStore, loginRateLimiter)
                liveTvRoutes(liveTvService)
            }

            webSocket("/ws") {
                // Security fix (2026-08-02 review, finding H1) — "/ws" doesn't start with "/api/", so
                // AuthPlugin's intercept never even sees it and it was reachable with zero auth. Every
                // Logger.info/warn/error call in the backend fans out to it via ActivityLog ->
                // WsBroadcaster (log/Logger.kt), including full absolute media paths, ffmpeg command
                // lines, and complete scanned MediaItems (JobEvent.ItemScanned) — a live library/path
                // leak to anyone on the internet, confirmed live: an anonymous handshake with no
                // credentials returned 101 Switching Protocols. This socket is only ever opened by the
                // admin WASM frontend (Dashboard/Activity/Library/Shell/BulkReorderWizard, all
                // same-origin), so the browser attaches the js_session cookie automatically on the
                // handshake — validate it exactly like every other admin route.
                val token = call.request.cookies["js_session"]
                val session = token?.let { sessionService.validate(it) }
                if (session == null) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Not authenticated"))
                    return@webSocket
                }
                broadcaster.register(this)
                try {
                    for (frame in incoming) {
                        if (frame is Frame.Close) break
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Client vanished (ECONNRESET / broken pipe). Iterating `incoming` rethrows the
                    // socket's close cause; left uncaught it escapes the handler and, on Kotlin/Native,
                    // terminates the whole process. Swallow it — the finally still unregisters.
                    Logger.warn("WS /ws client connection dropped: ${e.message}", "ws")
                } finally {
                    broadcaster.unregister(this)
                }
            }

            // R33 — per-user live config push. Device token comes via query param (browsers can't set
            // a handshake header); this path is exempt from the bearer-gate AuthPlugin and validates here.
            webSocket("/api/tv/events") {
                // Phase 118 (FR C.5), superseded by Phase 129's global Setup-phase shed intercept above
                // — a shed 503 is sent before the WS upgrade ever reaches this handler, so there's
                // nothing left to check here; already-connected TVs keep their socket either way.
                val token = call.request.queryParameters["token"]?.takeIf { it.isNotBlank() }
                    ?: call.request.headers["Authorization"]?.removePrefix("Bearer ")?.takeIf { it.isNotBlank() }
                val device = token?.let { deviceService.validateDeviceToken(it) }
                if (device == null) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid or missing device token"))
                    return@webSocket
                }
                // Phase 134 (FR-OPS2 §D) — defensive hard cap; a reconnect of an already-registered
                // device always succeeds, only a genuinely new device can be refused.
                if (!tvEventBus.tryRegister(device.jellyfinUserId, device.deviceId, this)) {
                    close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "TV event session limit reached"))
                    return@webSocket
                }
                // Phase 110 — while this TV is connected, bridge one outbound session to Jellyfin for
                // it (dashboard messages, remote control). Best-effort: never let a bridge problem take
                // down the TV's own event socket.
                runCatching { sessionBridge.connect(device) }
                try {
                    for (frame in incoming) {
                        if (frame is Frame.Close) break
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Device dropped the connection (ECONNRESET). See note on /ws above — must not
                    // escape the handler or it crashes the Kotlin/Native process.
                    Logger.warn("WS /api/tv/events device connection dropped: ${e.message}", "tv")
                } finally {
                    tvEventBus.unregister(device.jellyfinUserId, device.deviceId, this)
                    runCatching { sessionBridge.disconnect(device.deviceId) }
                    // Phase 110 (FR B.2) — a TV disconnecting clears its Now Playing immediately rather
                    // than waiting out the 90s heartbeat timeout.
                    runCatching { playbackService.stopWatchdogTick { deviceId -> tvEventBus.isConnected(deviceId) } }
                    // Phase 147 — same immediate-close behavior for an open live-TV stream.
                    runCatching { liveTvService.stopWatchdogTick { deviceId -> tvEventBus.isConnected(deviceId) } }
                }
            }

            // Ravilo web app — serve under /tv/** (separate bundle, different entry HTML)
            if (raviloWebDir != null) {
                get("/tv/{...}") {
                    val path = call.request.path().removePrefix("/tv")
                    call.serveFrontendFile(raviloWebDir, path)
                }
            }

            // Admin SPA: serve Wasm frontend for all non-API, non-TV paths
            get("{...}") {
                call.serveFrontendFile(frontendDir, call.request.path())
            }
        }
    }
    try {
        engine.start(wait = false)
    } catch (e: Exception) {
        val msg = e.message ?: ""
        if ("address already in use" in msg.lowercase() || "eaddrinuse" in msg.lowercase()) {
            println("[ERROR] Port $port is already in use — is another jellystructure instance running?")
            println("[ERROR]   kill it with:  fuser -k ${port}/tcp")
            exit(1)
        }
        throw e
    }
    return {
        broadcaster.closeAll()
        engine.stop(1_000L, 5_000L)
        appScope.cancel()
        Logger.info("Server stopped")
    }
}

// Phase 118 (FR C.3) — shared ProcessGate; callers are all inside the /health/full suspend handler.
@OptIn(ExperimentalForeignApi::class)
private suspend fun runShell(command: String): String? = dev.jellystructure.ops.ProcessGate.withPermit {
    memScoped {
        val pipe = popen(command, "r")
        if (pipe == null) {
            null
        } else {
            val result = StringBuilder()
            val buffer = allocArray<ByteVar>(4096)
            try {
                while (fgets(buffer, 4096, pipe) != null) result.append(buffer.toKString())
            } finally {
                pclose(pipe)
            }
            result.toString().takeIf { it.isNotBlank() }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.serveFrontendFile(
    dir: String,
    requestPath: String,
) {
    val rel = requestPath.trimStart('/').ifEmpty { "index.html" }

    if (".." in rel) {
        respond(HttpStatusCode.BadRequest)
        return
    }

    val target = Path("$dir/$rel")
    if (SystemFileSystem.exists(target)) {
        serveStaticBytes(FileIo.readBytes(target), rel)
        return
    }

    // SPA fallback — never cache index.html (it bootstraps the WASM app)
    val index = Path("$dir/index.html")
    if (SystemFileSystem.exists(index)) {
        val bytes = FileIo.readBytes(index)
        response.cacheControl(CacheControl.NoCache(null))
        respondBytes(bytes, ContentType.Text.Html)
    } else {
        respond(HttpStatusCode.NotFound)
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.serveStaticBytes(
    bytes: ByteArray,
    rel: String,
) {
    val etag = "\"${bytes.crc32Hex()}\""
    val ifNoneMatch = request.headers[HttpHeaders.IfNoneMatch]
    if (ifNoneMatch == etag) {
        respond(HttpStatusCode.NotModified)
        return
    }
    response.headers.append(HttpHeaders.ETag, etag)
    if (rel == "index.html") {
        response.cacheControl(CacheControl.NoCache(null))
    } else {
        response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 3600, mustRevalidate = true))
    }
    respondBytes(bytes, contentTypeFor(rel))
}

private fun ByteArray.crc32Hex(): String {
    var crc = 0xFFFFFFFFL
    for (b in this) {
        var v = ((crc xor b.toLong().and(0xFF)) and 0xFF).toInt()
        repeat(8) { v = if (v and 1 != 0) (v ushr 1) xor 0xEDB88320.toInt() else v ushr 1 }
        crc = (crc ushr 8) xor v.toLong().and(0xFFFFFFFFL)
    }
    return (crc xor 0xFFFFFFFFL).toString(16).padStart(8, '0')
}

private fun contentTypeFor(path: String): ContentType = when (path.substringAfterLast('.').lowercase()) {
    "html"       -> ContentType.Text.Html
    "css"        -> ContentType.Text.CSS
    "js", "mjs"  -> ContentType.Application.JavaScript
    "wasm"       -> ContentType.parse("application/wasm")
    "json"       -> ContentType.Application.Json
    "png"        -> ContentType.Image.PNG
    "jpg", "jpeg"-> ContentType.Image.JPEG
    "svg"        -> ContentType.Image.SVG
    "ico"        -> ContentType.parse("image/x-icon")
    else         -> ContentType.Application.OctetStream
}
