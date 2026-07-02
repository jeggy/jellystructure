package dev.jellystructure

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.SessionService
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.arr.AcquisitionService
import dev.jellystructure.arr.AcquisitionStore
import dev.jellystructure.arr.ArrClient
import dev.jellystructure.arr.ArrRescanService
import dev.jellystructure.arr.SonarrEnrichService
import dev.jellystructure.chart.ChartIngestService
import dev.jellystructure.chart.ChartRegistry
import dev.jellystructure.chart.ChartStore
import dev.jellystructure.chart.JustWatchProvider
import dev.jellystructure.chart.NetflixTudumProvider
import dev.jellystructure.chart.StreamingAvailabilityProvider
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
import dev.jellystructure.config.PipelineStep
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.nfo.NfoWriter
import dev.jellystructure.server.routes.fireWebhook
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
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import platform.posix.SIGINT
import platform.posix.SIGTERM
import platform.posix.getenv
import platform.posix.localtime_r
import platform.posix.mktime
import platform.posix.signal
import platform.posix.getenv
import platform.posix.time
import platform.posix.time_tVar
import kotlinx.cinterop.toKString
import platform.posix.tm
import kotlin.concurrent.AtomicInt

private val shutdownRequested = AtomicInt(0)

@OptIn(ExperimentalForeignApi::class)
@Suppress("UNUSED_PARAMETER")
private fun onSignal(sig: Int) {
    shutdownRequested.value = 1
}

@OptIn(ExperimentalForeignApi::class)
fun main() = runBlocking {
    val configFile = env("CONFIG_FILE", "./data/config.toml")
    val dbFile = env("DB_FILE", "./data/jellystructure.db")
    val frontendDir = env("FRONTEND_DIR", "/app/frontend")
    val raviloWebDir = env("RAVILO_WEB_DIR", "")?.takeIf { it.isNotBlank() }
    val port = env("SERVER_PORT", "9505").toIntOrNull() ?: 9505
    val tmdbBaseUrl = env("TMDB_BASE_URL", "https://api.themoviedb.org/3")

    // Phase 118 (FR B.1) — first thing: an unhandled exception anywhere in this process (not just once
    // the server is up) writes a crash marker + attempts a synchronous webhook, since the selector's
    // failure mode cancels main() outright and there's no later "safe" point to install this from.
    val dataDir = configFile.substringBeforeLast('/', missingDelimiterValue = ".")
    dev.jellystructure.ops.installCrashHook(dataDir)

    val configStore = ConfigStore(configFile)
    configStore.load()
    dev.jellystructure.ops.setCrashWebhookUrl(configStore.current.behavior.notificationsWebhook)
    dev.jellystructure.ops.reportCrashRecoveryIfAny(dataDir, configStore)

    val db = createDatabase(dbFile)
    val sessionService = SessionService(db)
    val jellyfinClient = JellyfinClient()
    val tmdbClient = TmdbClient(configStore, tmdbBaseUrl)
    val dataDir = dbFile.substringBeforeLast('/')
    val jsTagStore = dev.jellystructure.media.JsTagStore("$dataDir/js-tags.json")
    jsTagStore.load()
    val mediaStore = MediaStore(db, jsTagStore, configStore)
    mediaStore.load()
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
    val scanner = Scanner(configStore, tmdbClient, jellyfinClient, jsTagStore)
    val artworkDownloader = ArtworkDownloader(tmdbClient, dev.jellystructure.media.Screengrabber())
    val scanTracker = ScanTracker(db)
    scanTracker.load()

    val effectiveScanThreads = configStore.current.behavior.scanThreads.coerceIn(1, 32)
    val scanDispatcher = Dispatchers.Default.limitedParallelism(effectiveScanThreads)
    scanTracker.targetWorkers.value = configStore.current.behavior.scanWorkers.coerceIn(1, 32)

    signal(SIGTERM, staticCFunction(::onSignal))
    signal(SIGINT, staticCFunction(::onSignal))

    Logger.info("Starting jellystructure on port $port")
    Logger.info("Serving frontend from $frontendDir")

    val raviloDeviceService = RaviloDeviceService(db)
    val tvEventBus = dev.jellystructure.tv.TvEventBus(rootScope)
    val raviloConfigService = RaviloConfigService(db, tvEventBus)
    val homeFeedService = HomeFeedService(mediaStore, raviloConfigService, jellyfinClient, configStore)
    val browseService = BrowseService(mediaStore, jellyfinClient, configStore)
    val detailService = DetailService(mediaStore, jellyfinClient, configStore)
    val playbackService = PlaybackService(mediaStore, jellyfinClient, configStore)
    val mediaHistory = MediaHistory(db)
    val logoDownloader = LogoDownloader(dataDir, tmdbClient)
    val imageProxyService = dev.jellystructure.tv.RaviloArtworkService(dataDir, configStore, mediaStore, artworkDownloader)
    val channelLogoStore = dev.jellystructure.tv.ChannelLogoStore(dataDir)
    val qbClient = QBittorrentClient()
    val seedingSnapshot = SeedingSnapshot(configStore, qbClient)
    val seedingGuard = SeedingGuard(seedingSnapshot)
    val arrClient = ArrClient()
    val arrRescan = ArrRescanService(configStore, arrClient, rootScope)
    val sonarrEnrich = SonarrEnrichService(mediaStore, arrClient, configStore)
    // Phase 109: single-worker persistent queue for heavy media edits (ffmpeg remuxes) — see the class
    // doc for why enqueue-then-drain replaces running ffmpeg inline on the request thread.
    val mediaJobQueue = dev.jellystructure.media.MediaJobQueue(db, mediaStore, broadcaster, jellyfinClient, configStore, mediaHistory, seedingGuard, arrRescan, rootScope)
    mediaJobQueue.start()
    // Phase 110 — one outbound Jellyfin WS per connected Ravilo TV (dashboard messages, remote control).
    val sessionBridge = dev.jellystructure.tv.JellyfinSessionBridge(configStore, tvEventBus, rootScope, mediaStore)
    // Phase 111 — jellystructure-issued API keys for external tools (Home Assistant etc.), fenced to /api/remote/**.
    val apiKeyStore = dev.jellystructure.auth.ApiKeyStore(db)
    // Phase 114 — realtime ingest: *arr webhooks + Jellyfin's own LibraryChanged reach Ravilo in
    // seconds instead of waiting for the next scheduled scan.
    if (configStore.current.ingest.webhookSecret.isBlank()) {
        rootScope.launch { configStore.update(configStore.current.copy(ingest = configStore.current.ingest.copy(webhookSecret = dev.jellystructure.auth.generateSecureToken()))) }
    }
    val realtimeIngest = dev.jellystructure.media.RealtimeIngestService(scanner, mediaStore, jellyfinClient, configStore, artworkDownloader, rootScope, broadcaster, mediaHistory, arrRescan, sonarrEnrich)
    val libraryListener = dev.jellystructure.tv.JellyfinLibraryListener(configStore, jellyfinClient, mediaStore, realtimeIngest, rootScope)
    libraryListener.start()
    // Phase 118 (FR C.4) — FD telemetry: the durable defense against the unfixable Ktor Native
    // FD_SETSIZE selector crash is keeping total FDs under the 1024 ceiling; this makes pressure
    // observable (warn/alert thresholds) before the process dies.
    val fdWatchdog = dev.jellystructure.ops.FdWatchdog(configStore, rootScope)
    fdWatchdog.start()
    val acquisitionStore = AcquisitionStore(db)
    val acquisitionService = AcquisitionService(configStore, arrClient, tmdbClient, acquisitionStore, mediaStore, tvEventBus, rootScope)
    acquisitionService.startReconciler()
    val chartStore = ChartStore(db)
    val chartRegistry = ChartRegistry(listOf(
        NetflixTudumProvider(),
        // Phase 59 — Streaming Availability API (movieofthenight.com): official platform top lists.
        // Free tier = 500 req/month; week-gated polling uses ~40 req/month.
        // Requires [api_keys] streaming_availability_key in config (free RapidAPI sign-up).
        StreamingAvailabilityProvider(configStore, "max",     "Max"),
        StreamingAvailabilityProvider(configStore, "disney",  "Disney+"),
        StreamingAvailabilityProvider(configStore, "prime",   "Amazon Prime"),
        StreamingAvailabilityProvider(configStore, "apple",   "Apple TV+"),
        // Phase 59 — JustWatch unofficial GraphQL: covers Nordic and other regional services.
        // No API key needed. Provider shortName is auto-discovered from urlSlug via GetProviders.
        JustWatchProvider("viaplay",     "Viaplay",     "viaplay"),
        JustWatchProvider("paramount",   "Paramount+",  "paramount-plus-premium"),
        JustWatchProvider("skyshowtime", "SkyShowtime", "sky-showtime"),
    ))
    val chartIngest = ChartIngestService(configStore, chartRegistry, tmdbClient, chartStore, mediaStore)
    val shutdown = startServer(
        configStore, sessionService, raviloDeviceService, raviloConfigService, channelLogoStore, homeFeedService, browseService, detailService, playbackService, jellyfinClient, mediaStore, scanner,
        artworkDownloader, tmdbClient, scanTracker, mediaHistory, activityLog, broadcaster,
        frontendDir, raviloWebDir = raviloWebDir, port = port, scanDispatcher = scanDispatcher, effectiveScanThreads = effectiveScanThreads, jsTagStore = jsTagStore, seedingGuard = seedingGuard, seedingSnapshot = seedingSnapshot, logoDownloader = logoDownloader, qbClient = qbClient, arrClient = arrClient, arrRescan = arrRescan, sonarrEnrich = sonarrEnrich, acquisitionService = acquisitionService, chartRegistry = chartRegistry, chartStore = chartStore, chartIngest = chartIngest, tvEventBus = tvEventBus, imageProxyService = imageProxyService, mediaJobQueue = mediaJobQueue, sessionBridge = sessionBridge, apiKeyStore = apiKeyStore, realtimeIngest = realtimeIngest, libraryListener = libraryListener, fdWatchdog = fdWatchdog,
    )

    // R149: populate Sonarr next-airing data for all TV shows on startup (background, non-blocking).
    // After enriching, nudge all connected Ravilo clients to silently re-pull their home feed.
    rootScope.launch { sonarrEnrich.enrichAll(); tvEventBus.notifyGlobalConfigChanged() }

    // Scheduled scan / pipeline (Phase 91 / 93b). Fires at the LOCAL WALL-CLOCK time the admin set
    // (the cron the Settings schedule UI emits), not "interval since boot". Re-reads config every poll
    // chunk so edits apply within a minute, and publishes the next-run time for the admin indicator.
    rootScope.launch {
        var legacyNextSec = 0L  // armed lazily for the legacy scanIntervalHours fallback
        while (shutdownRequested.value == 0) {
            val cfg = configStore.current
            val pipeline = cfg.scan.pipeline.filter { it.enabled }
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

            val active = if (pipeline.isNotEmpty()) pipeline else null
            val jobId = scanTracker.startNew()
            runTagged(jobId, "scheduled", "▶ Scheduled ${if (active != null) "pipeline" else "scan"} run started") {
                if (active != null) {
                    executePipeline(active, jobId, mediaStore, scanner, scanTracker, broadcaster, configStore, jellyfinClient, scanDispatcher, artworkDownloader, arrRescan, sonarrEnrich)
                } else {
                    runScan(jobId, emptySet(), mediaStore, scanner, scanTracker, broadcaster, configStore, jellyfinClient, scanDispatcher, artworkDownloader = if (cfg.behavior.fetchImages) artworkDownloader else null)
                }
            }
        }
    }

    // Ops hook (Phase 95): a full library scan on startup when SCAN_ON_START=1 — e.g. to rebuild the
    // catalog after an incident. The scanner is non-destructive (adds/updates, flags gone items for triage).
    if (platform.posix.getenv("SCAN_ON_START")?.toKString() == "1") {
        rootScope.launch {
            val jobId = scanTracker.startNew()
            runTagged(jobId, "scan", "▶ Startup scan (SCAN_ON_START=1)") {
                // Items-only (no artwork fetch) — fast + FD-safe; on-disk posters are still served by R133.
                runScan(jobId, emptySet(), mediaStore, scanner, scanTracker, broadcaster, configStore, jellyfinClient, scanDispatcher, artworkDownloader = null)
            }
        }
    }

    // Phase 110 (FR B.2) — stop watchdog: catches a playback whose client stopped heartbeating without
    // a clean disconnect (app kill, network drop, HDMI-off) — the /api/tv/events disconnect handler
    // covers the clean-close case immediately; this covers everything else within ~30s of the 90s window.
    rootScope.launch {
        while (shutdownRequested.value == 0) {
            delay(30_000L)
            runCatching { playbackService.stopWatchdogTick { deviceId -> tvEventBus.isConnected(deviceId) } }
                .onFailure { Logger.warn("Stop watchdog tick failed: ${it.message}", "tv") }
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

    // Scheduled chart ingest (Phase 57) — refresh every refresh_hours (default 24), week-gated.
    // Ingest runs by default even without an explicit [discover] block; set enabled=false to opt out.
    rootScope.launch {
        while (shutdownRequested.value == 0) {
            val d = configStore.current.discover
            if (d?.enabled != false) {
                val regions = d?.regions ?: listOf("DK")
                for (region in regions) runCatching { chartIngest.refresh(region) }
            }
            delay((configStore.current.discover?.refreshHours ?: 24).coerceAtLeast(1) * 3_600_000L)
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

/** ms until the next LOCAL wall-clock occurrence of the schedule, or null if it isn't understood.
 *  Honors the three cron patterns the Settings schedule UI emits — daily at H:00 (`0 H * * *`),
 *  weekly on Sunday at H:00 (`0 H * * 0`), and the every-N-hours form (hour field `[star]/N`) —
 *  computed against the host's local timezone (the same clock the admin reads). Returns null on
 *  anything else so the caller can warn instead of silently running every 24h. */
@OptIn(ExperimentalForeignApi::class)
fun nextRunDelayMs(cron: String, nowEpochSec: Long): Long? = memScoped {
    val f = cron.trim().split(Regex("\\s+"))
    if (f.size < 5) return@memScoped null
    val minF = f[0]; val hourF = f[1]; val dowF = f[4]

    val nowVar = alloc<time_tVar>().apply { value = nowEpochSec.convert() }
    val tm = alloc<tm>()
    if (localtime_r(nowVar.ptr, tm.ptr) == null) return@memScoped null
    tm.tm_isdst = -1  // let mktime resolve DST for the (possibly future) target

    // every-N-hours at minute 0: "0 */N * * *"
    if (hourF.startsWith("*/")) {
        val step = hourF.removePrefix("*/").toIntOrNull()?.takeIf { it in 1..23 } ?: return@memScoped null
        tm.tm_min = 0; tm.tm_sec = 0
        tm.tm_hour = ((tm.tm_hour / step) + 1) * step  // strictly-next boundary; mktime normalizes >23 into the next day
        return@memScoped (mktime(tm.ptr).convert<Long>() - nowEpochSec) * 1_000L
    }

    val hour = hourF.toIntOrNull()?.takeIf { it in 0..23 } ?: return@memScoped null
    val minute = minF.toIntOrNull()?.takeIf { it in 0..59 } ?: 0
    val targetDow = if (dowF == "*") null else dowF.toIntOrNull()?.takeIf { it in 0..6 }  // 0 = Sunday

    tm.tm_hour = hour; tm.tm_min = minute; tm.tm_sec = 0
    var target = mktime(tm.ptr).convert<Long>()  // today at H:MM local; mktime refreshes tm_wday
    var guard = 0
    while (target <= nowEpochSec || (targetDow != null && tm.tm_wday != targetDow)) {
        tm.tm_mday += 1
        tm.tm_isdst = -1
        target = mktime(tm.ptr).convert<Long>()
        if (++guard > 8) return@memScoped null
    }
    (target - nowEpochSec) * 1_000L
}

/** ms duration for a freshness cadence string: "daily", "weekly", "monthly", "6months", "yearly", "never" */
fun cadenceMs(cadence: String): Long? = when (cadence.trim().lowercase()) {
    "daily"   -> 24 * 3_600_000L
    "weekly"  -> 7  * 24 * 3_600_000L
    "monthly" -> 30 * 24 * 3_600_000L
    "6months" -> 180 * 24 * 3_600_000L
    "yearly"  -> 365 * 24 * 3_600_000L
    "never"   -> null  // never recheck
    else      -> 30 * 24 * 3_600_000L  // default monthly
}

/** Execute a scan pipeline: scan_files (trigger, always first) then action blocks sequentially. */
@OptIn(ExperimentalForeignApi::class)
suspend fun executePipeline(
    pipeline: List<PipelineStep>,
    jobId: String,
    store: MediaStore,
    scanner: dev.jellystructure.media.Scanner,
    scanTracker: dev.jellystructure.media.ScanTracker,
    broadcaster: dev.jellystructure.jobs.WsBroadcaster,
    configStore: ConfigStore,
    jellyfinClient: JellyfinClient,
    scanDispatcher: kotlinx.coroutines.CoroutineDispatcher,
    artworkDownloader: ArtworkDownloader,
    arrRescan: ArrRescanService,
    sonarrEnrich: SonarrEnrichService? = null,
) {
    val scanStep = pipeline.firstOrNull { it.step == "scan_files" }
        ?: PipelineStep(step = "scan_files")

    Logger.info("Pipeline starting: ${pipeline.joinToString(" → ") { it.step }}")

    // Build freshness filter: compute the set of JellyfinItem IDs that are NOT due (→ skip them).
    // Items with no lastChecked or no release year are always included.
    val freshnessFilter: ((dev.jellystructure.auth.JellyfinItem) -> Boolean)? =
        if (!scanStep.recheckUnchanged) null
        else {
            val now = store.nowMs()
            // Approximate current calendar year from epoch ms (leap-year-agnostic, ±1 day error OK)
            val currentYear = ((now / 1000L) / 31_557_600L + 1970).toInt()
            val skipJellyfinIds = store.allItems().mapNotNull { item ->
                val jid = item.jellyfinId ?: return@mapNotNull null
                val lc = store.lastChecked(item.id) ?: return@mapNotNull null
                val ry = item.year ?: return@mapNotNull null
                val cadenceStr = when {
                    ry >= currentYear            -> scanStep.refreshThisYear
                    (currentYear - ry) <= 5     -> scanStep.refresh1To5y
                    else                         -> scanStep.refreshOlder
                }
                val thresh = cadenceMs(cadenceStr) ?: return@mapNotNull jid  // "never" → always skip
                if ((now - lc) < thresh) jid else null  // not due → skip
            }.toSet()
            val filter: (dev.jellystructure.auth.JellyfinItem) -> Boolean = { jItem ->
                jItem.id !in skipJellyfinIds
            }
            filter
        }

    val workingSet = withContext(RunContext(jobId, "scan_files")) {
        runScan(
            jobId, emptySet(), store, scanner, scanTracker, broadcaster,
            configStore, jellyfinClient, scanDispatcher,
            freshnessFilter = freshnessFilter
        )
    }

    sonarrEnrich?.enrichAll()

    if (workingSet.isEmpty()) {
        Logger.info("Pipeline scan_files: no items in working set, skipping action steps")
        return
    }

    Logger.info("Pipeline scan_files complete: ${workingSet.size} items in working set")

    val cfg = configStore.current
    for (step in pipeline) {
        if (step.step == "scan_files") continue
        withContext(RunContext(jobId, step.step)) {
        Logger.info("Pipeline step: ${step.step}")
        when (step.step) {
            "pull_tmdb" -> {
                val toProcess = if (step.scope == "all") workingSet
                    else workingSet.filter { it.tmdbId == null }
                Logger.info("pull_tmdb: ${toProcess.size} items (scope=${step.scope})")
                for (item in toProcess) {
                    runCatching { scanner.rescanMetadata(item) }
                        .onSuccess { updated -> updated?.let { store.addOrUpdate(it) } }
                        .onFailure { Logger.warn("pull_tmdb failed for '${item.id}': ${it.message}") }
                }
            }
            "download_artwork" -> {
                // R125/R126: "missing" scope = anything fetch() can fill is absent — poster/fanart, plus
                // episode stills + season posters for series.
                val toProcess = if (step.scope == "all") workingSet
                    else workingSet.filter { artworkDownloader.isArtworkIncomplete(it) }
                Logger.info("download_artwork: ${toProcess.size} items (scope=${step.scope})")
                for (item in toProcess) {
                    val current = store.get(item.id) ?: item
                    runCatching { artworkDownloader.fetch(current) }
                        .onFailure { Logger.warn("download_artwork failed for '${item.id}': ${it.message}") }
                }
            }
            "write_nfo" -> {
                // Phase 115 (FR B) — content-aware: the old `overwrite`-gated write meant an NFO
                // written once was never updated again by the pipeline (default overwrite=false), so
                // every later DB change (a TMDB freshness re-pull, an operator edit) diverged from the
                // NFO forever. Now: always regenerate + compare by hash. If our own last-written hash
                // changed, rewrite regardless of the flag — that's just keeping our own file current,
                // not "overwriting". If the on-disk file isn't ours (foreign/hand-edited — its hash
                // doesn't match nfoHash), only `overwrite`/`overwriteNfo` may replace it; otherwise skip
                // and count it for a once-per-run summary line (Phase 53 skip-summary pattern).
                val serverUrl = cfg.apiKeys.jellyfinUrl
                var written = 0
                var unchanged = 0
                var foreignSkipped = 0
                for (item in workingSet) {
                    val current = store.get(item.id) ?: item
                    val wouldBeHash = NfoWriter.contentHash(current, serverUrl, cfg.metadata.ageRatingCascade)
                    if (wouldBeHash == current.nfoHash) { unchanged++; continue }
                    val onDiskHash = NfoWriter.onDiskHash(current)
                    val isForeign = onDiskHash != null && onDiskHash != current.nfoHash
                    if (isForeign && !(step.overwrite || cfg.behavior.overwriteNfo)) { foreignSkipped++; continue }
                    runCatching { NfoWriter.writeTracked(current, serverUrl, cfg.metadata.ageRatingCascade).getOrThrow() }
                        .onSuccess { r ->
                            store.updateOne(current.copy(nfoWrittenAt = r.writtenAt, nfoHash = r.hash))
                            written++
                        }
                        .onFailure { Logger.warn("write_nfo failed for '${item.id}': ${it.message}") }
                }
                Logger.info("write_nfo: $written written, $unchanged unchanged" +
                    if (foreignSkipped > 0) ", $foreignSkipped foreign NFO(s) skipped (set overwrite to replace)" else "")
            }
            "sync_jellyfin" -> {
                // Phase 115 (FR C) — full import (not the old ValidationOnly, which never re-reads NFOs),
                // scoped to items that actually have something new to import (nfoWrittenAt > jfSyncedAt)
                // so an unchanged library doesn't hammer Jellyfin with hundreds of full refreshes a night.
                val toSync = workingSet.filter { (it.nfoWrittenAt ?: 0L) > (it.jfSyncedAt ?: 0L) && !it.jellyfinId.isNullOrBlank() }
                Logger.info("sync_jellyfin: ${toSync.size} of ${workingSet.size} items have unsynced NFO changes")
                if (cfg.apiKeys.jellyfinUrl.isNotBlank() && cfg.apiKeys.jellyfinToken.isNotBlank()) {
                    for (item in toSync) {
                        val jid = item.jellyfinId ?: continue
                        runCatching {
                            jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, jid, full = true)
                        }.onSuccess { ok ->
                            if (ok) store.updateOne(item.copy(jfSyncedAt = nowEpochSec()))
                        }.onFailure { Logger.warn("sync_jellyfin failed for '${item.id}': ${it.message}") }
                    }
                }
            }
            "rescan_arr" -> {
                Logger.info("rescan_arr: ${workingSet.size} items")
                for (item in workingSet) arrRescan.nudge(item)
            }
            "detect_drift" -> {
                // Phase 115 (FR F) — real state evaluation across the working set, replacing the no-op.
                var converged = 0; var nfoStale = 0; var jfBehind = 0; var external = 0
                for (item in workingSet) {
                    val current = store.get(item.id) ?: item
                    val result = dev.jellystructure.nfo.DriftEvaluator.evaluate(current, jellyfinClient, cfg)
                    when (result.state) {
                        dev.jellystructure.nfo.DriftState.NFO_STALE.name.lowercase() -> nfoStale++
                        dev.jellystructure.nfo.DriftState.JELLYFIN_BEHIND.name.lowercase() -> {
                            jfBehind++
                            if (step.autoReassert) {
                                runCatching { NfoWriter.writeTracked(current, cfg.apiKeys.jellyfinUrl, cfg.metadata.ageRatingCascade).getOrThrow() }
                                    .onSuccess { r -> store.updateOne(current.copy(nfoWrittenAt = r.writtenAt, nfoHash = r.hash)) }
                                current.jellyfinId?.let { jid ->
                                    val ok = runCatching { jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, jid, full = true) }.getOrDefault(false)
                                    if (ok) store.updateOne(current.copy(jfSyncedAt = nowEpochSec()))
                                }
                            }
                        }
                        dev.jellystructure.nfo.DriftState.EXTERNAL_DRIFT.name.lowercase() -> external++
                        else -> converged++
                    }
                }
                Logger.info("detect_drift: $converged converged, $nfoStale NFO stale, $jfBehind Jellyfin behind" +
                    (if (step.autoReassert) " (auto-reassert attempted)" else "") + ", $external external drift")
                if (external > 0 && cfg.behavior.notifyOnDrift) {
                    runCatching { fireWebhook(cfg, """{"event":"drift_detected","pipeline":true,"items":$external}""") }
                        .onFailure { Logger.warn("detect_drift notify webhook failed: ${it.message}") }
                }
            }
            "wait" -> {
                Logger.info("wait: ${step.minutes} min")
                kotlinx.coroutines.delay(step.minutes * 60_000L)
            }
            "notify" -> {
                val payload = """{"event":"pipeline_complete","items":${workingSet.size},"on":"${step.on}"}"""
                runCatching { fireWebhook(cfg, payload) }
                    .onFailure { Logger.warn("notify webhook failed: ${it.message}") }
            }
        }
        }
    }
    Logger.info("Pipeline complete — ${workingSet.size} items processed")
}

/** 93g: run a scan/pipeline run inside a [RunContext] so every log line it emits is tagged with the run
 *  id (and therefore filterable in the Activity page), record it in the runs index for the run picker,
 *  and bracket it with start/finish log lines. Non-cancellation failures are logged and swallowed so the
 *  scheduler loop survives; cancellation propagates. */
suspend fun runTagged(jobId: String, trigger: String, startMsg: String, block: suspend () -> Unit) {
    Logger.startRun(jobId, trigger)
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

