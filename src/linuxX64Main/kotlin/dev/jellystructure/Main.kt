package dev.jellystructure

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.SessionService
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.arr.AcquisitionService
import dev.jellystructure.arr.AcquisitionStore
import dev.jellystructure.arr.ArrClient
import dev.jellystructure.arr.ArrRescanService
import dev.jellystructure.arr.SonarrEnrichService
import dev.jellystructure.torrent.QBittorrentClient
import dev.jellystructure.torrent.SeedingGuard
import dev.jellystructure.torrent.SeedingSnapshot
import dev.jellystructure.db.createDatabase
import dev.jellystructure.db.walCheckpoint
import dev.jellystructure.jobs.WsBroadcaster
import dev.jellystructure.log.Logger
import dev.jellystructure.log.RunContext
import dev.jellystructure.media.ActivityLog
import dev.jellystructure.media.LogoDownloader
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.Scanner
import dev.jellystructure.media.ScanTracker
import dev.jellystructure.media.pipelineStepConcurrency
import dev.jellystructure.media.runPipelineStepPool
import dev.jellystructure.config.PipelineStep
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.jobs.JobEvent
import dev.jellystructure.nfo.NfoWriter
import dev.jellystructure.server.routes.fireWebhook
import dev.jellystructure.server.routes.launchScanRun
import dev.jellystructure.server.routes.runScan
import dev.jellystructure.server.startServer
import dev.jellystructure.tv.BrowseService
import dev.jellystructure.tv.DetailService
import dev.jellystructure.tv.HomeFeedService
import dev.jellystructure.tv.PlaybackService
import dev.jellystructure.tv.RaviloConfigService
import dev.jellystructure.tv.RaviloDeviceService
import dev.jellystructure.tmdb.TmdbClient
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import platform.posix.SIGINT
import platform.posix.SIGPIPE
import platform.posix.SIGTERM
import platform.posix.SIG_IGN
import platform.posix.getenv
import platform.posix.signal
import platform.posix.time
import kotlin.concurrent.AtomicInt

private val shutdownRequested = AtomicInt(0)

@OptIn(ExperimentalForeignApi::class)
@Suppress("UNUSED_PARAMETER")
private fun onSignal(sig: Int) {
    shutdownRequested.value = 1
}

@OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
fun main() = runBlocking {
    val configFile = env("CONFIG_FILE", "./data/config.toml")
    val dbFile = env("DB_FILE", "./data/jellystructure.db")
    val frontendDir = env("FRONTEND_DIR", "/app/frontend")
    val raviloWebDir = env("RAVILO_WEB_DIR", "").takeIf { it.isNotBlank() }
    // Phase 218 (FR-218-1) — the Chromecast receiver bundle, served at /cast/ the same way ravilo-web is
    // served at /tv/. Absent directory ⇒ the route simply does not answer (pairs with FR-218-3's "off
    // means absent"). Docker sets CAST_DIR=/app/cast; a dev checkout serves the repo's cast-receiver/.
    val castDir = env("CAST_DIR", "cast-receiver").takeIf { it.isNotBlank() && kotlinx.io.files.SystemFileSystem.exists(kotlinx.io.files.Path(it)) }
    val port = env("SERVER_PORT", "9505").toIntOrNull() ?: 9505
    val tmdbBaseUrl = env("TMDB_BASE_URL", "https://api.themoviedb.org/3")

    // Phase 118 (FR B.1) — first thing: an unhandled exception anywhere in this process (not just once
    // the server is up) writes a crash marker + attempts a synchronous webhook, since the selector's
    // failure mode cancels main() outright and there's no later "safe" point to install this from.
    // (Named crashMarkerDir, not dataDir — a `dataDir` derived from DB_FILE already exists below.)
    val crashMarkerDir = configFile.substringBeforeLast('/', missingDelimiterValue = ".")
    dev.jellystructure.ops.installCrashHook(crashMarkerDir)
    // Phase 228 (FR-228-4) — an operator-settable floor for the collector's auto-tuned target heap.
    // Measured on this process: the runtime's target sat at ~200 MB while the live heap was 300-600 MB,
    // so a collection ran every ~0.7 s with 60-165 ms pauses. Unset = the runtime's own default (5 MiB
    // floor); the deployed value, if any, lives in docker-compose.yml next to its measurement.
    dev.jellystructure.ops.MemoryStats.applyGcFloorFromEnv(env("JELLYSTRUCTURE_GC_MIN_HEAP_MB", ""))

    val configStore = ConfigStore(configFile)
    configStore.load()
    dev.jellystructure.ops.setCrashWebhookUrl(configStore.current.behavior.notificationsWebhook)
    dev.jellystructure.ops.reportCrashRecoveryIfAny(crashMarkerDir, configStore)
    dev.jellystructure.ops.reportFdRestartRecoveryIfAny(crashMarkerDir, configStore)

    val db = createDatabase(dbFile)
    val sessionService = SessionService(db)
    val jellyfinClient = JellyfinClient()
    val tmdbClient = TmdbClient(configStore, tmdbBaseUrl)
    val dataDir = dbFile.substringBeforeLast('/')
    val imdbClient = dev.jellystructure.imdb.ImdbClient(dataDir)  // Phase 131/158
    val jsTagStore = dev.jellystructure.media.JsTagStore("$dataDir/js-tags.json")
    jsTagStore.load()
    val mediaStore = MediaStore(db, jsTagStore, configStore)
    mediaStore.load()
    // Phase 163 (Intro & credits editor) — one row per (item, episode, kind), replacing the old flat
    // SegmentMarkers blob field. Backfilled from mediaStore's already-loaded items just below.
    val mediaSegmentStore = dev.jellystructure.media.MediaSegmentStore(db)
    dev.jellystructure.media.backfillMediaSegments(mediaStore, mediaSegmentStore)
    // Catch exceptions escaping fire-and-forget coroutines so one failure can't abort the whole
    // Kotlin/Native process (unhandled → SIGABRT/134); log and keep running. See Server.appScope.
    val rootScope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, e ->
        println("[ERROR] Uncaught background coroutine exception (server kept alive): ${e.message}")
        println(e.stackTraceToString())
    })
    val broadcaster = WsBroadcaster()
    val activityLogFile = env("ACTIVITY_LOG_FILE", dbFile.substringBeforeLast('/') + "/activity-log.json")
    val activityLog = ActivityLog(activityLogFile, broadcaster, rootScope)
    activityLog.load()
    Logger.activityLog = activityLog
    dev.jellystructure.ops.WebhookStatus.init(dataDir)   // Phase 221 (FR-221-5) — small persisted record, never config.toml
    val scanner = Scanner(configStore, tmdbClient, jellyfinClient, jsTagStore, store = mediaStore)
    val artworkDownloader = ArtworkDownloader(tmdbClient, dev.jellystructure.media.Screengrabber())
    // Phase 150 (FR-SEG1-4) — on-disk Chromaprint fingerprint cache, keyed like RaviloArtworkService's
    // own per-episode still cache; never embedded in the MediaItem JSON blob (Phase 78 blob-bloat).
    val fingerprintService = dev.jellystructure.media.FingerprintService(dataDir)
    val scanTracker = ScanTracker(db)
    scanTracker.load()

    // Bug fix: Episode.hasStill is a persisted snapshot (Phase 121), but several code paths (the scan
    // gap-fill fetch, the fetch_artwork pipeline step, pushToJellyfin's background fetch, and the manual
    // fetch/screengrab/save endpoints) fetched or generated a still on disk without ever re-stamping it
    // afterward — so an episode whose still genuinely exists (TMDB-sourced or a locally-generated
    // screengrab, both valid; TMDB just wins when both are available) stayed flagged "missing" in
    // triage/Library/Dashboard forever. Those call sites are now fixed to restamp after every fetch; this
    // one-time, idempotent pass corrects whatever's already wrong in the current library so the numbers
    // are right immediately, not just for episodes touched by a future scan.
    rootScope.launch {
        var corrected = 0
        for (item in mediaStore.allItems()) {
            if (item.kind != dev.jellystructure.model.MediaKind.TV_SHOW) continue
            val restamped = artworkDownloader.stampHasStill(item)
            if (restamped.episodes.map { it.hasStill } != item.episodes.map { it.hasStill }) {
                mediaStore.updateOne(restamped)
                corrected++
            }
        }
        if (corrected > 0) Logger.info("Startup: corrected stale hasStill flags for $corrected series", "artwork")
    }

    // Phase 134 (FR-OPS2 §F): 32→100 — a worker/thread count doesn't cost FDs by itself (workers queue
    // behind ProcessGate/OutboundHttp, both raised alongside this), so a powerful host can genuinely
    // run 100 concurrent scan workers instead of the extra 68 just queuing uselessly behind a 32-ceiling.
    //
    // Bug fix: this used to be `Dispatchers.Default.limitedParallelism(n)` — a *view* over the same
    // shared thread pool every other Default-dispatched coroutine in the process uses (including
    // whatever the Ktor CIO engine or route handlers hop onto Dispatchers.Default for). A live
    // incident showed Ravilo TV/phone clients timing out on plain reads (`/api/tv/series/{id}`) while
    // a heavy `pull_tmdb` pipeline run was in flight — even after fixing that run's own N+1/retry-storm
    // bug, a legitimate full pipeline over hundreds of episodes is still substantial sustained work,
    // and sharing Default's pool means it can still queue up ahead of interactive request handling.
    // A dedicated fixed thread pool guarantees scan/pipeline work can never contend with request
    // threads for a CPU slot, however heavy either side gets. @DelicateCoroutinesApi: this pool is
    // intentionally never closed — it's meant to live for the whole process, same as Dispatchers.Default.
    val effectiveScanThreads = configStore.current.behavior.scanThreads.coerceIn(1, 100)
    val scanDispatcher = newFixedThreadPoolContext(effectiveScanThreads, "scan-pool")
    scanTracker.targetWorkers.value = configStore.current.behavior.scanWorkers.coerceIn(1, 100)

    // Phase 203 (FR-203-3) — background-warm the MKV structure health cache so it's normally already
    // answered by the time anyone opens the Dashboard, instead of the old lazy on-access refresh that
    // could block /triage/count for ~88s on a cold cache (Phase 201's first-Cluster descent made the
    // sweep ~10x more expensive than when that lazy refresh was written). Reuses scanDispatcher/
    // scanWorkers — this is I/O-bound file-header reading, the same class of background work the scan
    // pool already isolates from request-serving capacity.
    dev.jellystructure.media.MkvHealthCache.start(
        scope = rootScope,
        dispatcher = scanDispatcher,
        concurrency = configStore.current.behavior.scanWorkers.coerceIn(1, 100),
    ) { mediaStore.allItems() }

    signal(SIGTERM, staticCFunction(::onSignal))
    signal(SIGINT, staticCFunction(::onSignal))
    // Bug fix: writing to a socket whose peer already disconnected raises SIGPIPE, whose default
    // action is to terminate the process outright — a raw POSIX signal, not a Kotlin exception, so it
    // completely bypasses every `catch (e: Throwable)` around the WS read/write loops (see
    // reference-ktor-native-ws-crash.md). A client disconnecting mid-broadcast (e.g. a second device
    // added via TV login connecting/disconnecting in quick succession) could kill the entire server
    // instantly. Ignoring SIGPIPE makes the write instead fail normally with EPIPE, which the existing
    // runCatching/try-catch around sends already handles.
    signal(SIGPIPE, SIG_IGN)

    Logger.info("Starting jellystructure on port $port")
    Logger.info("Serving frontend from $frontendDir")

    val raviloDeviceService = RaviloDeviceService(db)
    val tvEventBus = dev.jellystructure.tv.TvEventBus(rootScope)
    // Phase 139 — constructed before RaviloConfigService: it needs arrClient to resolve the request-
    // language catalog's default intent (RaviloConfigService.resolveBehaviour's requestLanguage field).
    val arrClient = ArrClient()
    val requestLanguageService = dev.jellystructure.arr.RequestLanguageService(configStore, arrClient)
    val requestIntentStore = dev.jellystructure.seerr.RequestIntentStore(db)
    // Phase 218 (FR-218-3) — cast capability rides every RaviloConfig read, resolved from config.toml.
    val castService = dev.jellystructure.tv.CastService(db, configStore, raviloDeviceService)
    // Phase 236 (FR-236-2) — the receiver-shows-a-code pairing flow (screen/code, remote/pair, screen/claim).
    val screenPairingService = dev.jellystructure.tv.ScreenPairingService(db, raviloDeviceService)
    val raviloConfigService = RaviloConfigService(db, tvEventBus, requestLanguageService, castCapability = { castService.capability() })
    raviloConfigService.migrateAllLegacyBehaviourFields()  // R162: one-time, idempotent
    val homeFeedService = HomeFeedService(mediaStore, raviloConfigService, jellyfinClient, configStore, tvEventBus, artworkDownloader)
    // Phase 205 (FR-205-2) — background-refresh Continue Watching and the whole-catalog playstate map
    // for recently-seen devices, instead of building either on a viewer's own request. Both used to
    // fetch live on a cache miss on the interactive path; PlaystateCache also replaces DetailService's
    // and BrowseService's own live, uncached/unbounded fetches (see those files' Phase 205 notes).
    homeFeedService.start(rootScope, raviloDeviceService)
    dev.jellystructure.tv.PlaystateCache.start(rootScope, raviloDeviceService, mediaStore, jellyfinClient, configStore, tvEventBus)
    // Phase 216 (FR-216-5) — BrowseService answers `logoUrl` from LogoDownloader.hasLogo, the one
    // "was a logo really captured" predicate the admin Metadata page already uses.
    val logoDownloader = LogoDownloader(dataDir, tmdbClient)
    val browseService = BrowseService(mediaStore, jellyfinClient, configStore, raviloConfigService, artworkDownloader, logoDownloader)
    // Phase 232 (FR-232-2) — judge any logo that has no ink sidecar yet; background class, once per logo ever.
    rootScope.launch(dev.jellystructure.ops.GateClass.BACKGROUND) { runCatching { logoDownloader.computeMissingInk() } }
    // Phase 185 (FR-185-4) — needed by DetailService below, for playbackNote resolution.
    val playbackStartSampleStore = dev.jellystructure.tv.PlaybackStartSampleStore(db)
    val detailService = DetailService(mediaStore, jellyfinClient, configStore, artworkDownloader, mediaSegmentStore, raviloDeviceService, playbackStartSampleStore)
    // Phase 232 (FR-232-5) — title clearlogo ink: in memory, judged in the background, warmed once at boot.
    val clearlogoInk = dev.jellystructure.media.ClearlogoInk(rootScope, dataDir)
    detailService.clearlogoInk = clearlogoInk
    homeFeedService.clearlogoInk = clearlogoInk
    rootScope.launch(dev.jellystructure.ops.GateClass.BACKGROUND) { runCatching { clearlogoInk.warm(mediaStore.allItems()) } }
    val playbackQoeStore = dev.jellystructure.tv.PlaybackQoeStore(db)
    val playbackService = PlaybackService(mediaStore, jellyfinClient, configStore, playbackQoeStore, playbackStartSampleStore, raviloDeviceService, castService, writerScope = rootScope)
    // R248 (FR-R248-2) — once a queued stop has landed in Jellyfin, fold it into the Home feed and tell
    // the user's devices (`home_changed`); the stop route itself no longer invalidates (see TvRoutes).
    playbackService.onStopLanded = { device, stoppedId -> homeFeedService.invalidatePlaystate(device, stoppedId) }
    // Phase 236 (FR-236-8) — a reaped device's status is cleared and the "gone" snapshot fanned out to
    // whoever is watching it (a phone's remote, an API subscriber).
    playbackService.onDeviceReaped = { deviceId ->
        dev.jellystructure.tv.screenStatusTracker.clear(deviceId)?.let { finalStatus ->
            tvEventBus.notifyDeviceStatus(deviceId, kotlinx.serialization.json.Json.encodeToString(dev.jellystructure.shared.tv.ScreenStatus.serializer(), finalStatus))
        }
    }
    val mediaHistory = MediaHistory(db)
    val imageProxyService = dev.jellystructure.tv.RaviloArtworkService(dataDir, configStore, mediaStore, artworkDownloader)
    val channelLogoStore = dev.jellystructure.tv.ChannelLogoStore(dataDir)
    val qbClient = QBittorrentClient()
    val seedingSnapshot = SeedingSnapshot(configStore, qbClient)
    val seedingGuard = SeedingGuard(seedingSnapshot)
    // Phase 178 §FR-178-3 — qBittorrent alternative-speed-limits throttle while a TV plays.
    val playbackThrottleStore = dev.jellystructure.torrent.PlaybackThrottleStore("$dataDir/qbt_throttle.json")
    playbackThrottleStore.load()
    val playbackThrottleService = dev.jellystructure.torrent.PlaybackThrottleService(configStore, qbClient, playbackThrottleStore)
    rootScope.launch { playbackThrottleService.recoverOnStartup() }
    val seerrClient = dev.jellystructure.seerr.SeerrClient()
    val bazarrClient = dev.jellystructure.bazarr.BazarrClient()
    val arrRescan = ArrRescanService(configStore, arrClient, rootScope)
    val sonarrEnrich = SonarrEnrichService(mediaStore, arrClient, configStore)
    val upcomingService = dev.jellystructure.tv.UpcomingService(configStore, arrClient, mediaStore, tmdbClient, artworkDownloader)
    // Phase 109: single-worker persistent queue for heavy media edits (ffmpeg remuxes) — see the class
    // doc for why enqueue-then-drain replaces running ffmpeg inline on the request thread.
    val mediaJobQueue = dev.jellystructure.media.MediaJobQueue(db, mediaStore, broadcaster, jellyfinClient, configStore, mediaHistory, seedingGuard, arrRescan, rootScope, mediaSegmentStore, fingerprintService, artworkService = imageProxyService)
    mediaJobQueue.start()
    // Phase 220 (FR-220-4) — once, in the background, until the marker exists: every served variant for
    // the existing library, so the first viewer to scroll a season never pays for it on the request path.
    rootScope.launch(dev.jellystructure.ops.GateClass.BACKGROUND) { runCatching { mediaJobQueue.enqueuePresizeBackfill() } }
    // Phase 222 (FR-222-6/7) — once at boot, in the background: prune segment rows filed under episodes
    // that no longer exist, then queue the stored waveform envelope for every unit that lacks one.
    rootScope.launch(dev.jellystructure.ops.GateClass.BACKGROUND) {
        runCatching { dev.jellystructure.media.pruneOrphanSegments(mediaStore, mediaSegmentStore, mediaHistory) }
            .onFailure { Logger.warn("Phase 222 orphan sweep failed: ${it.message}", "media") }
        runCatching { mediaJobQueue.enqueueWaveformBackfill() }
            .onFailure { Logger.warn("Phase 222 waveform backfill could not be queued: ${it.message}", "media") }
    }
    // Phase 110 — one outbound Jellyfin WS per connected Ravilo TV (dashboard messages, remote control).
    val sessionBridge = dev.jellystructure.tv.JellyfinSessionBridge(configStore, tvEventBus, rootScope, mediaStore)
    // Phase 111 — jellystructure-issued API keys for external tools (Home Assistant etc.), fenced to /api/remote/**.
    val apiKeyStore = dev.jellystructure.auth.ApiKeyStore(db)
    // Phase 114 — realtime ingest: *arr webhooks + Jellyfin's own LibraryChanged reach Ravilo in
    // seconds instead of waiting for the next scheduled scan.
    if (configStore.current.ingest.webhookSecret.isBlank()) {
        rootScope.launch { configStore.update(configStore.current.copy(ingest = configStore.current.ingest.copy(webhookSecret = dev.jellystructure.auth.generateSecureToken()))) }
    }
    // Phase 181 (FR-181-5) — persistent "needs work" set; must exist before RealtimeIngestService, which
    // writes to it.
    val dirtyItemStore = dev.jellystructure.media.DirtyItemStore(db)
    val realtimeIngest = dev.jellystructure.media.RealtimeIngestService(scanner, mediaStore, jellyfinClient, configStore, artworkDownloader, rootScope, broadcaster, mediaHistory, arrRescan, sonarrEnrich, imdbClient, mediaJobQueue, db, mediaSegmentStore, dirtyItemStore)
    // Phase 181 — the old JellyfinLibraryListener WS fallback is gone (proven live, three separate ways,
    // to never deliver a usable event on this Jellyfin version — spec §2.3); PipelineEngine's
    // sweepJellyfinLibrary() now runs from inside every RunTarget.Library pipeline run instead.
    // Phase 118 (FR C.4) — FD telemetry: the durable defense against the unfixable Ktor Native
    // FD_SETSIZE selector crash is keeping total FDs under the 1024 ceiling; this makes pressure
    // observable (warn/alert thresholds) before the process dies.
    val fdWatchdog = dev.jellystructure.ops.FdWatchdog(configStore, crashMarkerDir, rootScope)
    fdWatchdog.start()
    val acquisitionStore = AcquisitionStore(db)
    val acquisitionService = AcquisitionService(configStore, arrClient, tmdbClient, acquisitionStore, mediaStore, tvEventBus, rootScope)
    acquisitionService.startReconciler()
    // Phase 186 — a request must be able to end: the reconciliation sweep that retires a request once
    // Seerr/*arr ground truth says it's dead (declined, media deleted, *arr entity gone by hand, or a
    // stale FAILED past the retention window), plus the `POST /acquisition/request/remove` route's
    // admin-triggerable full cascade — the 2026-09-04 manual three-system cleanup, now one call.
    val requestLifecycleService = dev.jellystructure.seerr.RequestLifecycleService(configStore, requestIntentStore, acquisitionStore, acquisitionService, seerrClient, arrClient, mediaStore, rootScope)
    requestLifecycleService.startSweeper()
    // Phase 147 — Live TV surfaced from Jellyfin; jsTagStore-style JSON store (no SQLDelight migration)
    // for the per-channel lineup overrides + guide-cadence settings.
    val liveTvStore = dev.jellystructure.tv.LiveTvStore("$dataDir/livetv.json")
    liveTvStore.load()
    val liveTvService = dev.jellystructure.tv.LiveTvService(dataDir, liveTvStore, jellyfinClient, configStore, tvEventBus)
    // Bug fix: channel names/logos/current-program only ever lived in an in-memory cache populated by
    // sync(), which was only called from admin routes — after every restart, Live TV showed placeholder
    // "Channel N" text (previously the raw Jellyfin id) until an admin happened to open Live TV settings.
    // Cheap (one Jellyfin GET, see sync()'s own doc comment) and safe to call unconditionally — it no-ops
    // if Jellyfin isn't configured/reachable yet.
    rootScope.launch { runCatching { liveTvService.sync() } }
    val shutdown = startServer(
        configStore, sessionService, raviloDeviceService, raviloConfigService, channelLogoStore, homeFeedService, browseService, detailService, playbackService, jellyfinClient, mediaStore, scanner,
        artworkDownloader, tmdbClient, scanTracker, mediaHistory, activityLog, broadcaster,
        frontendDir, raviloWebDir = raviloWebDir, port = port, scanDispatcher = scanDispatcher, effectiveScanThreads = effectiveScanThreads, jsTagStore = jsTagStore, seedingGuard = seedingGuard, seedingSnapshot = seedingSnapshot, logoDownloader = logoDownloader, qbClient = qbClient, arrClient = arrClient, arrRescan = arrRescan, sonarrEnrich = sonarrEnrich, acquisitionService = acquisitionService, seerrClient = seerrClient, bazarrClient = bazarrClient, tvEventBus = tvEventBus, imageProxyService = imageProxyService, mediaJobQueue = mediaJobQueue, sessionBridge = sessionBridge, apiKeyStore = apiKeyStore, realtimeIngest = realtimeIngest, dirtyItemStore = dirtyItemStore, fdWatchdog = fdWatchdog, imdbClient = imdbClient, upcomingService = upcomingService, requestLanguageService = requestLanguageService, requestIntentStore = requestIntentStore, requestLifecycleService = requestLifecycleService, liveTvService = liveTvService, fingerprintService = fingerprintService, mediaSegmentStore = mediaSegmentStore,
        playbackQoeStore = playbackQoeStore,
        castService = castService, castDir = castDir,
        screenPairingService = screenPairingService,
    )

    // R149: populate Sonarr next-airing data for all TV shows on startup (background, non-blocking).
    // After enriching, nudge all connected Ravilo clients to silently re-pull their home feed.
    rootScope.launch { sonarrEnrich.enrichAll(); tvEventBus.notifyGlobalConfigChanged() }

    // Phase 175 — shared collaborator bundle for every dev.jellystructure.media.runPipeline() call this
    // process makes (scheduler + SCAN_ON_START below; route handlers build their own in MediaRoutes.kt).
    val pipelineDeps = dev.jellystructure.media.PipelineDeps(
        store = mediaStore, scanner = scanner, broadcaster = broadcaster, configStore = configStore,
        jellyfinClient = jellyfinClient, scanDispatcher = scanDispatcher, artworkDownloader = artworkDownloader,
        arrRescan = arrRescan, sonarrEnrich = sonarrEnrich, imdbClient = imdbClient,
        mediaSegmentStore = mediaSegmentStore, mediaJobQueue = mediaJobQueue,
        realtimeIngest = realtimeIngest, mediaHistory = mediaHistory, dirtyItemStore = dirtyItemStore,
        artworkPresize = { imageProxyService.presize(it) },   // Phase 220 (FR-220-1)
    )

    // Scheduled scan / pipeline (Phase 91 / 93b). Fires at the LOCAL WALL-CLOCK time the admin set
    // (the cron the Settings schedule UI emits), not "interval since boot". Re-reads config every poll
    // chunk so edits apply within a minute, and publishes the next-run time for the admin indicator.
    rootScope.launch {
        var legacyNextSec = 0L  // armed lazily for the legacy scanIntervalHours fallback
        while (shutdownRequested.value == 0) {
            val cfg = configStore.current
            val schedule = cfg.scanSchedule
            val legacyHours = cfg.behavior.scanIntervalHours
            val nowSec = nowEpochSec()

            // ms until the next due run (null = nothing scheduled / schedule not understood).
            val dueInMs: Long? = when {
                schedule.isNotBlank() -> nextRunDelayMs(schedule, nowSec)
                    ?: run { Logger.warn("scan_schedule '$schedule' not understood — scheduler idle until it's fixed"); null }
                legacyHours > 0 -> {
                    if (legacyNextSec == 0L) legacyNextSec = nowSec + legacyHours * 3_600L
                    (legacyNextSec - nowSec) * 1_000L
                }
                else -> null
            }
            scanTracker.nextScheduledRunSec.value = if (dueInMs != null && dueInMs > 0L) nowSec + dueInMs / 1_000L else 0L

            // Far off (or nothing scheduled) → sleep a poll chunk so config edits are picked up, then recompute.
            if (dueInMs == null || dueInMs > 60_000L) { delay(60_000L); continue }

            delay(dueInMs.coerceAtLeast(0L))
            if (shutdownRequested.value != 0) break
            if (scanTracker.running) { Logger.info("Scheduled run skipped — a scan is already running"); delay(60_000L); continue }
            if (schedule.isBlank() && legacyHours > 0) legacyNextSec = nowEpochSec() + legacyHours * 3_600L

            // Phase 175 (follow-up) — launchScanRun() is the ONE place every trigger (this scheduler,
            // every manual button, resume, SCAN_ON_START) launches a run; no bespoke step list or
            // descriptor logic lives here anymore.
            val jobId = scanTracker.startNew()
            launchScanRun(jobId, "scheduled", scanTracker, rootScope, configStore, pipelineDeps)
        }
    }

    // Ops hook (Phase 95): a full library scan on startup when SCAN_ON_START=1 — e.g. to rebuild the
    // catalog after an incident. The scanner is non-destructive (adds/updates, flags gone items for triage).
    // Phase 175 (follow-up): runs the operator's actual configured pipeline via launchScanRun(), like
    // every other trigger — previously this ran its own bespoke reduced step list (scan_files+pull_tmdb
    // only, deliberately skipping fetch_artwork/write_nfo/etc. for speed), a special case that's gone now.
    if (getenv("SCAN_ON_START")?.toKString() == "1") {
        val jobId = scanTracker.startNew()
        launchScanRun(jobId, "startup", scanTracker, rootScope, configStore, pipelineDeps, full = true)
    }

    // Phase 110 (FR B.2) — stop watchdog: catches a playback whose client stopped heartbeating without
    // a clean disconnect (app kill, network drop, HDMI-off) — the /api/tv/events disconnect handler
    // covers the clean-close case immediately; this covers everything else within ~30s of the 90s window.
    rootScope.launch {
        while (shutdownRequested.value == 0) {
            delay(30_000L)
            runCatching { playbackService.stopWatchdogTick { deviceId -> tvEventBus.isConnected(deviceId) } }
                .onFailure { Logger.warn("Stop watchdog tick failed: ${it.message}", "tv") }
            // Phase 147 — same watchdog shape for an open live-TV stream (its own tracking, see LiveTvService).
            runCatching { liveTvService.stopWatchdogTick { deviceId -> tvEventBus.isConnected(deviceId) } }
                .onFailure { Logger.warn("Live TV stop watchdog tick failed: ${it.message}", "livetv") }
            // Phase 178 §FR-178-3 — same 30s cadence is plenty for "did playback just start/stop".
            runCatching { playbackThrottleService.tick() }
                .onFailure { Logger.warn("Playback throttle tick failed: ${it.message}", "qbittorrent") }
        }
    }

    // WAL checkpoint every 6 hours — prevents the WAL file growing unbounded between restarts.
    rootScope.launch {
        delay(6 * 3_600_000L)
        while (shutdownRequested.value == 0) {
            runCatching { db.walCheckpoint() }
                .onSuccess { Logger.info("WAL checkpoint: TRUNCATE complete") }
                .onFailure { Logger.info("WAL checkpoint failed (non-fatal): ${it.message}") }
            delay(6 * 3_600_000L)
        }
    }

    // Phase 164 (FR-164-8) — daily media_job retention sweep (14 days of terminal-state rows kept).
    // deleteOld existed since Phase 109 but was never called from anywhere; the segments lane adds one
    // row per season per full pipeline run, so this now needed wiring for real.
    rootScope.launch {
        while (shutdownRequested.value == 0) {
            runCatching { mediaJobQueue.pruneOld() }
                .onFailure { Logger.warn("media_job retention sweep failed (non-fatal): ${it.message}") }
            delay(24 * 3_600_000L)
        }
    }

    // Phase 177 §FR-177-5 — daily playback_qoe retention sweep (90 days kept; diagnostic, not a ledger).
    rootScope.launch {
        while (shutdownRequested.value == 0) {
            runCatching { playbackQoeStore.pruneOld() }
                .onFailure { Logger.warn("playback_qoe retention sweep failed (non-fatal): ${it.message}") }
            delay(24 * 3_600_000L)
        }
    }

    while (shutdownRequested.value == 0) {
        delay(1_000L)
    }
    Logger.info("Shutdown signal received — stopping gracefully")
    shutdown()
}

@OptIn(ExperimentalForeignApi::class)
fun env(name: String, default: String): String =
    getenv(name)?.toKString() ?: default

@OptIn(ExperimentalForeignApi::class)
fun nowEpochSec(): Long = time(null)

/** ms until the next LOCAL wall-clock occurrence of the schedule, or null if it isn't understood or
 *  never fires. Phase 166: thin wrapper over the shared `dev.jellystructure.cron` parser/evaluator
 *  (replacing this function's old hand-rolled three-shapes-only matcher — see the phase spec for the
 *  defects that motivated it), computed against the host's local timezone (the same clock the admin
 *  reads). Signature kept exactly as-is so both existing call sites (the scheduler loop below and
 *  `MediaRoutes.kt`'s `/scan/status`) are untouched. */
fun nextRunDelayMs(cron: String, nowEpochSec: Long): Long? {
    val parsed = dev.jellystructure.cron.parseCron(cron)
    if (parsed !is dev.jellystructure.cron.CronParse.Ok) return null
    val next = dev.jellystructure.cron.nextFireEpochSec(parsed.expr, nowEpochSec) ?: return null
    return (next - nowEpochSec) * 1_000L
}

/** 93g: run a scan/pipeline run inside a [RunContext] so every log line it emits is tagged with the run
 *  id (and therefore filterable in the Activity page), record it in the runs index for the run picker,
 *  and bracket it with start/finish log lines. Non-cancellation failures are logged and swallowed so the
 *  scheduler loop survives; cancellation propagates.
 *  Phase 135 (FR-135-4) — [scope]/[type] are the two new orthogonal run descriptors alongside
 *  [trigger] (manual/scheduled/startup — the pre-existing "ingest" trigger, [RealtimeIngestService],
 *  keeps using this same function without a live [scanTracker]); [scope] is library (plain
 *  file-discovery) or pipeline, [type] is normal/full (pipeline-only, null for library scope).
 *  [scanTracker] is null for a run that shouldn't touch the live scan-status bookkeeping (realtime
 *  ingest runs alongside a possibly-in-progress real scan on its own small dispatcher — writing to the
 *  same tracker would corrupt that scan's live activeStep/worker display). [stepPlan] seeds
 *  ScanTracker's live status for pollers — `runPipeline` (Phase 175, formerly `executePipeline`)
 *  immediately supersedes it with the real plan once `block` runs it; the default `["scan_files"]` is
 *  correct as-is for every plain-scan call site. */
suspend fun runTagged(
    jobId: String, trigger: String, scope: String, type: String?,
    startMsg: String, scanTracker: ScanTracker? = null,
    stepPlan: List<String> = listOf("scan_files"),
    block: suspend () -> Unit,
) {
    scanTracker?.setDescriptors(trigger, scope, type)
    scanTracker?.setStepPlan(stepPlan)
    Logger.startRun(jobId, trigger, scope, type)
    withContext(RunContext(jobId)) {
        Logger.info(startMsg, "scan")
        try {
            block()
            Logger.info("✓ Run finished", "scan")
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) { Logger.warn("Run cancelled", "scan"); throw e }
            Logger.error("Run failed: ${e.message}", "scan")
        } finally {
            Logger.finishRun(jobId)
        }
    }
}

