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
import dev.jellystructure.tmdb.TmdbPacingStats
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.Scanner
import dev.jellystructure.media.ScanTracker
import dev.jellystructure.server.routes.activityRoutes
import dev.jellystructure.server.routes.authRoutes
import dev.jellystructure.server.routes.bazarrRoutes
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
import dev.jellystructure.server.routes.segmentRoutes
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
import io.ktor.websocket.readText
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
    bazarrClient: dev.jellystructure.bazarr.BazarrClient? = null,
    tvEventBus: TvEventBus,
    imageProxyService: RaviloArtworkService? = null,
    mediaJobQueue: dev.jellystructure.media.MediaJobQueue,
    sessionBridge: dev.jellystructure.tv.JellyfinSessionBridge,
    apiKeyStore: dev.jellystructure.auth.ApiKeyStore,
    realtimeIngest: dev.jellystructure.media.RealtimeIngestService,
    dirtyItemStore: dev.jellystructure.media.DirtyItemStore,
    fdWatchdog: dev.jellystructure.ops.FdWatchdog,
    imdbClient: dev.jellystructure.imdb.ImdbClient,
    upcomingService: dev.jellystructure.tv.UpcomingService? = null,
    requestLanguageService: dev.jellystructure.arr.RequestLanguageService? = null,
    requestIntentStore: dev.jellystructure.seerr.RequestIntentStore? = null,
    requestLifecycleService: dev.jellystructure.seerr.RequestLifecycleService? = null,
    liveTvService: LiveTvService,
    fingerprintService: dev.jellystructure.media.FingerprintService,
    mediaSegmentStore: dev.jellystructure.media.MediaSegmentStore,
    playbackQoeStore: dev.jellystructure.tv.PlaybackQoeStore,
    // Phase 218 — the cast service (hand-off, ceiling, reachability, status) and the receiver bundle dir.
    castService: dev.jellystructure.tv.CastService? = null,
    castDir: String? = null,
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
            // Phase 218 (FR-218-8) — a cast past `max_sessions`: phase 182's own shape, 503 + Retry-After,
            // which the receiver renders as its busy state and the phone's remote mirrors.
            exception<dev.jellystructure.tv.CastCeilingException> { call, cause ->
                call.response.headers.append(HttpHeaders.RetryAfter, cause.retryAfterSeconds.toString())
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to (cause.message ?: "all cast sessions in use")))
            }
            // Phase 182 (FR-182-8) — a request-path caller that hit OutboundHttp/ProcessGate's bounded
            // interactive-acquire timeout and had no withTimeoutOrNull of its own to degrade through
            // (most do — HomeFeedService/DetailService/BrowseService's existing hydration timeouts).
            // 503 + Retry-After, not the generic 500 below: this is "the server is busy, try again
            // shortly," not an application bug.
            exception<dev.jellystructure.OutboundHttp.GateTimeoutException> { call, cause ->
                call.response.headers.append(HttpHeaders.RetryAfter, "2")
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to (cause.message ?: "server busy")))
            }
            exception<dev.jellystructure.ops.ProcessGate.GateTimeoutException> { call, cause ->
                call.response.headers.append(HttpHeaders.RetryAfter, "2")
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to (cause.message ?: "server busy")))
            }
            exception<Throwable> { call, cause ->
                // Phase 219 (FR-219-5) — a TV hanging up its event socket (ECONNRESET during the upgrade
                // or close handshake, thrown OUTSIDE the handler's own frame loop) is lifecycle, not a
                // server error: one INFO line with the device, no Activity entry, no 500. 43 a day
                // were landing in the Activity log as errors before this.
                if (call.request.path().startsWith("/api/tv/events")) {
                    val device = call.request.queryParameters["token"]?.takeIf { it.isNotBlank() }?.let { deviceService.validateDeviceToken(it) }
                    Logger.info("TV event socket closed by peer device=${device?.deviceId ?: "unknown"}: ${cause.message}", "tv")
                    runCatching { call.respond(HttpStatusCode.OK) }
                    return@exception
                }
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
            // Security fix (2026-08-02 review, finding M1) — this was `anyHost()` + `allowCredentials
            // = true`. Verified against the Ktor 3.5.0 CORS plugin source
            // (`val headerOrigin = if (allowsAnyHost && !allowCredentials) "*" else origin`): with
            // credentials on, Ktor does NOT send `*` back — it REFLECTS the caller's Origin and adds
            // `Access-Control-Allow-Credentials: true`. Confirmed live during the audit (an
            // Origin: https://evil.example.com preflight got that Origin echoed back). The only thing
            // stopping full cross-origin credentialed access today is `SameSite=Lax` on the js_session
            // cookie — one attribute away from account takeover, with no CSRF token as a second layer.
            //
            // Same-origin requests (the normal deployment: this server serves its own admin/Ravilo-web
            // frontends) need no CORS headers at all. The only legitimate cross-origin case is a
            // separately-hosted dev server (webpack/vite) during local development — allow that
            // explicitly via CORS_ALLOWED_ORIGINS (comma-separated "host:port", e.g.
            // "localhost:8080,localhost:5173"), never via a blanket wildcard.
            val extraOrigins = dev.jellystructure.env("CORS_ALLOWED_ORIGINS", "")
                .split(",").map { it.trim() }.filter { it.isNotBlank() }
            extraOrigins.forEach { origin -> allowHost(origin, schemes = listOf("http", "https")) }
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

        installAuthPlugin(sessionService, validateDeviceToken = { token, appVersion, platform -> deviceService.validateDeviceToken(token, appVersion, platform) }, validateApiKey = { apiKeyStore.validate(it) })

        // Security fix (2026-08-02 review, finding H4) — shared between /api/auth/login and
        // /api/tv/login; see LoginRateLimiter's doc comment.
        //
        // Phase 197 (FR-197-5) — the cap is overridable ONLY so the e2e stack can raise it; the
        // default is unchanged at LoginRateLimiter's own 5-per-60s and no deployment sets this. The
        // whole Playwright suite shares one backend, so its ~5 logins per run already sit on the
        // limit; a single flaky test retrying re-runs a `beforeAll` and pushes it over, which then
        // fails an unrelated later spec at its login step and reads as that spec being broken. That
        // is exactly how it presented: shell-layout's screenshot assertions never ran (0 ms) because
        // `login()` timed out waiting for the dashboard. The specs already log in once per file to
        // stay under the cap (see shell-layout.spec.ts's header); retries defeat that by design.
        val loginRateLimiter = dev.jellystructure.auth.LoginRateLimiter(
            maxAttempts = dev.jellystructure.env("LOGIN_RATE_LIMIT_MAX", "5").toIntOrNull() ?: 5,
        )

        // Security fix (2026-08-02 review, finding M8) — no security response headers were sent at
        // all. HSTS is safe to send unconditionally: browsers only ever act on it when it arrives over
        // an actually-secure connection (RFC 6797), and this server is designed to sit behind a
        // TLS-terminating reverse proxy anyway (see D1 in the deployment guide) — it's a no-op until
        // then, not a footgun. The CSP here is deliberately permissive enough for a Kotlin/WASM SPA
        // (needs 'wasm-unsafe-eval'/'unsafe-eval' to instantiate its own compiled module, and this
        // codebase's admin UI leans on inline styles).
        //
        // Security fix (FR-167-3/4, 2026-08-17) — this CSP was never checked in a real browser and was
        // wrong in two ways, both confirmed live: (1) wf.css used to @import Space Grotesk/Sora/
        // JetBrains Mono from fonts.googleapis.com/fonts.gstatic.com, which style-src/font-src 'self'
        // silently blocked — the admin UI's entire type system was falling back to system fonts with no
        // visible error. Fixed by self-hosting the fonts (design/app/fonts/, wf.css) instead of
        // allowlisting the external host — one fewer third-party dependency for an internet-facing
        // service, and style-src/font-src stay 'self'. (2) No frame-src meant it inherited default-src
        // 'self', which blocks Ravilo's YouTube/Vimeo trailer <iframe> (TrailerEmbed.kt, R163) — added
        // below, scoped to exactly the two origins trailerEmbedUrl() ever constructs.
        intercept(ApplicationCallPipeline.Plugins) {
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            call.response.headers.append("Referrer-Policy", "strict-origin-when-cross-origin")
            call.response.headers.append("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
            // Phase 218 amendment (2026-09-18) — the Chromecast receiver is the one page here that MUST
            // load third-party script: Google's CAF framework (and the player libraries it pulls in)
            // comes from www.gstatic.com and cannot be self-hosted. Under the site-wide `script-src
            // 'self'` the framework was blocked, the receiver never started, and a TV that had accepted
            // the launch showed nothing while the phone sat on "Connecting…" — found on the first real
            // cast. The receiver is also embedded by some cast shells, so it carries no frame ban; it is
            // non-interactive (FR-R245-15), so there is nothing to clickjack.
            if (call.request.path().startsWith("/cast")) {
                call.response.headers.append("Content-Security-Policy", CAST_RECEIVER_CSP)
                proceed()
                return@intercept
            }
            call.response.headers.append("X-Frame-Options", "DENY")
            call.response.headers.append(
                "Content-Security-Policy",
                "default-src 'self'; " +
                    "script-src 'self' 'wasm-unsafe-eval' 'unsafe-eval'; " +
                    "style-src 'self' 'unsafe-inline'; " +
                    "img-src 'self' data: blob: https:; " +
                    "font-src 'self' data:; " +
                    "connect-src 'self' ws: wss: https:; " +
                    "media-src 'self' blob: https:; " +
                    "frame-src https://www.youtube-nocookie.com https://player.vimeo.com; " +
                    "object-src 'none'; " +
                    "frame-ancestors 'none'; " +
                    "base-uri 'self'",
            )
            proceed()
        }

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

        // Security fix (2026-08-02 review, finding M6) — no request body size limit existed anywhere;
        // unauthenticated handlers (`/api/auth/login`, `/api/tv/login`, `/api/setup`) call
        // `call.receive<T>()` on an unbounded body, so a multi-GB POST to any of them exhausts memory
        // pre-auth. Same early Setup phase as the FD-shed check above, so an oversized request is
        // rejected before routing/auth/handler do any work. This is a Content-Length pre-check (cheap,
        // catches the realistic "huge declared body" case); it can't catch a request that lies about
        // Content-Length via chunked transfer — Ktor Native's CIO engine exposes no lower-level
        // streaming cap to enforce that case here.
        intercept(ApplicationCallPipeline.Setup) {
            val path = call.request.path()
            val limit = if (path == "/api/auth/login" || path == "/api/tv/login" || path == "/api/setup") {
                16 * 1024L   // credentials/URLs — never legitimately more than a few hundred bytes
            } else {
                64 * 1024 * 1024L   // generous global ceiling (largest legitimate body is artwork upload)
            }
            val declaredLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declaredLength != null && declaredLength > limit) {
                call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "request body too large"))
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
                    // Phase 182 (FR-182-9) — gate saturation, so "Ravilo requests are queuing behind
                    // background work" is an observable fact, not something only inferrable from a TV
                    // that does nothing. Cheap (plain atomic reads, no lock) — safe on every /health hit.
                    val outboundHttp = dev.jellystructure.OutboundHttp.stats()
                    val processGate = dev.jellystructure.ops.ProcessGate.stats()
                    // Phase 183 (FR-183-6) — the Activity page's "Outbound pacing" card; same cheap-probe
                    // reasoning as the gate stats above (plain spin-locked reads, safe on every hit).
                    val tmdbPacing = tmdbClient.pacingStats()
                    // Phase 203 (FR-203-6) — sweptAt() was computed since Phase 201 and surfaced
                    // nowhere; a slow or failing sweep was only diagnosable by reading container logs,
                    // which is how the 88s block was found in the first place. `null` means no sweep has
                    // completed yet for this process (cold cache) — distinct from a real past timestamp.
                    val mkvHealthSweptAt = dev.jellystructure.media.MkvHealthCache.sweptAt()
                    // Phase 213 (FR-213-7) — the state that mattered during the 2026-09-15 incident:
                    // jellystructure's own gates (outboundHttp/processGate above) all read idle while ten
                    // Jellyfin-side subtitle extractions saturated the disk, because that cost lands in
                    // Jellyfin's process where no gate here can see it. This can't measure that cost either
                    // — it only makes the count of outstanding requests jellystructure itself issued legible.
                    val jobQueues = mediaJobQueue.healthSnapshot()
                    // Phase 219 (FR-219-4) — the playback writer's queue and each refresher's last
                    // successful cycle per user, so a stale household is visible without reading logs.
                    val writerJson = playbackService.writerStats()?.toJson() ?: "null"
                    fun ages(m: Map<String, Long>) = m.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
                    val refreshersJson = """{"playstate_age_ms":${ages(dev.jellystructure.tv.PlaystateCache.refresherAges())},"continue_age_ms":${ages(homeFeedService.continueRefreshAges())}}"""
                    call.respondText(
                        """{"status":"ok","version":"${dev.jellystructure.ServerVersion.current.replace("\\", "\\\\").replace("\"", "\\\"")}","fd_count":${fdWatchdog.currentCount},"fd_high_water_mark":${fdWatchdog.highWaterMark},"fd_census":${census?.toJson() ?: "null"},""" +
                            """"outbound_http_gate":${outboundHttp.toJson()},"process_gate":${processGate.toJson()},""" +
                            """"tmdb_pacing":${Json.encodeToString(TmdbPacingStats.serializer(), tmdbPacing)},""" +
                            """"mkv_health_swept_at":${mkvHealthSweptAt ?: "null"},"job_queues":${jobQueues.toJson()},""" +
                            """"playback_writer":$writerJson,"refreshers":$refreshersJson,""" +
                            """"tv_image":${imageProxyService?.stats()?.toJson() ?: "null"},""" +
                            """"memory":${dev.jellystructure.ops.MemoryStats.snapshot().toJson()}}""",
                        ContentType.Application.Json,
                    )
                }

                // Phase 178 §FR-178-1 — "is any TV playing right now" for the admin UI's ambient-dock
                // deferral state (FR-178-4) and its "Run anyway" override. Reads the same tracker the
                // stop watchdog does; no new state, no polling of anything else.
                get("/playback/active") {
                    runCatching { call.attributes[dev.jellystructure.auth.SessionKey] }.getOrNull()
                        ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
                    call.respond(mapOf(
                        "active" to dev.jellystructure.tv.isPlaybackActive(),
                        "devices" to dev.jellystructure.tv.activePlaybackDeviceNames(),
                    ))
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
                    cfg.bazarr?.takeIf { it.enabled }?.let { b ->
                        val p = if (bazarrClient != null && b.url.isNotBlank()) bazarrClient.ping(b.url, b.apiKey) else ArrPing(false, "URL not configured")
                        checks.add(HealthCheck("Bazarr", p.ok, if (p.ok) "Connected" + (p.version?.let { " · v$it" } ?: "") else p.detail))
                    }
                    // Phase 181 (FR-181-4.2) — replaces the deleted JellyfinLibraryListener's connection
                    // check (that socket never delivered a usable event on this Jellyfin version — a
                    // false "connected" concealed a 23-day silence). The real signal is whether ANY
                    // realtime-ingest path has actually completed successfully recently, and whether the
                    // persistent dirty-set is backing up.
                    if (cfg.ingest.realtime) {
                        val last = realtimeIngest.lastSuccessfulIngestAt
                        val ageSec = last?.let { dev.jellystructure.nowEpochSec() - it }
                        val outstanding = dirtyItemStore.count()
                        // 6h — generous enough to never false-alarm a quiet household, tight enough to
                        // catch a silence like the 23-day one this phase was written to surface.
                        val healthy = last != null && (ageSec ?: Long.MAX_VALUE) < 6 * 3600
                        val detail = buildString {
                            append(if (last != null) "Last successful ingest ${ageSec}s ago" else "No successful ingest yet")
                            if (outstanding > 0) append(" · $outstanding item(s) awaiting retry")
                        }
                        checks.add(HealthCheck("Realtime ingest", healthy, detail))
                    }
                    call.respond(mapOf("checks" to checks))
                }

                authRoutes(sessionService, jellyfinClient, configStore, loginRateLimiter)
                configureConfigRoutes(configStore, effectiveScanThreads, qbClient, arrClient, seerrClient, bazarrClient, tmdbClient, requestLanguageService, castService = castService, tvEventBus = tvEventBus, realtimeIngest = realtimeIngest)
                setupRoutes(configStore, jellyfinClient)
                jellyfinRoutes(configStore, jellyfinClient, deviceService)
                mediaRoutes(mediaStore, scanner, artworkDownloader, tmdbClient, appScope, scanTracker, broadcaster, jellyfinClient, configStore, mediaHistory, scanDispatcher, seedingGuard, seedingSnapshot, raviloConfigService, logoDownloader, arrRescan, sonarrEnrich, mediaJobQueue, imdbClient, fingerprintService, mediaSegmentStore, realtimeIngest, dirtyItemStore, imageProxyService = imageProxyService)
                activityRoutes(activityLog)
                triageRoutes(mediaStore, jellyfinClient, configStore, mediaHistory, seedingGuard, mediaSegmentStore)
                segmentRoutes(mediaStore, mediaSegmentStore, configStore, fingerprintService, appScope, jellyfinClient, mediaJobQueue, mediaHistory)
                metadataRoutes(mediaStore, jsTagStore, logoDownloader, seedingSnapshot, configStore)
                trackRoutes(mediaStore, configStore, jellyfinClient, mediaHistory, seedingGuard, arrRescan, appScope, broadcaster, mediaJobQueue)
                jobsRoutes(mediaJobQueue)
                remoteRoutes(deviceService, tvEventBus, mediaStore)
                apiKeyManagementRoutes(apiKeyStore)
                webhookRoutes(configStore, jellyfinClient, realtimeIngest, appScope, dirtyItemStore)
                acquisitionService?.let { acquisitionRoutes(it, requestLifecycleService) }
                bazarrClient?.let { bc ->
                    val bazarrService = dev.jellystructure.bazarr.BazarrService(configStore, bc)
                    bazarrRoutes(mediaStore, bazarrService, bc)
                }
                // R171 — the TV Request tab's Seerr-backed discover/search/request service; null (tab
                // reports unavailable) until a SeerrClient is wired, exactly like the other optional *arr services above.
                val seerrDiscoverService = seerrClient?.let { dev.jellystructure.seerr.SeerrDiscoverService(configStore, it, raviloConfigService, mediaStore, requestLanguageService, requestIntentStore, acquisitionService) }
                tvRoutes(deviceService, raviloConfigService, homeFeedService, browseService, detailService, playbackService, sessionService, jellyfinClient, configStore, channelLogoStore, imageProxyService, logoDownloader, castService, tvEventBus, upcomingService, seerrDiscoverService, mediaStore, loginRateLimiter, playbackQoeStore)
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

            // Phase 218 (FR-218-1) — the Chromecast receiver, served under /cast/** exactly like the
            // Ravilo web app below. The X-Ravilo-Cast header is what FR-218-5's reachability check looks
            // for, so a captive portal or a stranger's 200 at the same address does not pass as ours.
            if (castDir != null) {
                get("/cast/{...}") {
                    val path = call.request.path().removePrefix("/cast")
                    call.response.headers.append("X-Ravilo-Cast", "receiver")
                    call.serveFrontendFile(castDir, path)
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

/** Phase 218 amendment — the receiver page's own policy: everything the site-wide one allows, plus
 *  Google's Cast origin for script/style/font, and blob workers for the player's demuxer. */
internal const val CAST_RECEIVER_CSP: String =
    "default-src 'self'; " +
        // www.gstatic.com = the CAF framework; ajax.googleapis.com = the Shaka player CAF fetches for HLS
        // (seen blocked in a headless run of the receiver page, 2026-09-18).
        // Host-sources without a scheme: CAF requests Shaka protocol-relative ("//ajax…"), and a bare host
        // matches the page's own scheme — https in production, http in a local run of the e2e stack.
        "script-src 'self' 'unsafe-eval' 'wasm-unsafe-eval' www.gstatic.com ajax.googleapis.com; " +
        "style-src 'self' 'unsafe-inline' www.gstatic.com fonts.googleapis.com; " +
        "img-src 'self' data: blob: https:; " +
        "font-src 'self' data: www.gstatic.com fonts.gstatic.com; " +
        "connect-src 'self' ws: wss: https:; " +
        "media-src 'self' blob: https:; " +
        "worker-src 'self' blob:; " +
        "object-src 'none'; " +
        "base-uri 'self'"
