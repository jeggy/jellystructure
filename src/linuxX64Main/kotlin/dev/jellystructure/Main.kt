package dev.jellystructure

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.SessionService
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.arr.AcquisitionService
import dev.jellystructure.arr.AcquisitionStore
import dev.jellystructure.arr.ArrClient
import dev.jellystructure.arr.ArrRescanService
import dev.jellystructure.chart.ChartIngestService
import dev.jellystructure.chart.ChartRegistry
import dev.jellystructure.chart.ChartStore
import dev.jellystructure.chart.NetflixTudumProvider
import dev.jellystructure.torrent.QBittorrentClient
import dev.jellystructure.torrent.SeedingGuard
import dev.jellystructure.db.createDatabase
import dev.jellystructure.db.walCheckpoint
import dev.jellystructure.jobs.WsBroadcaster
import dev.jellystructure.log.Logger
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
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.posix.SIGINT
import platform.posix.SIGTERM
import platform.posix.getenv
import platform.posix.signal
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

    val configStore = ConfigStore(configFile)
    configStore.load()

    val db = createDatabase(dbFile)
    val sessionService = SessionService(db)
    val jellyfinClient = JellyfinClient()
    val tmdbClient = TmdbClient(configStore, tmdbBaseUrl)
    val dataDir = dbFile.substringBeforeLast('/')
    val jsTagStore = dev.jellystructure.media.JsTagStore("$dataDir/js-tags.json")
    jsTagStore.load()
    val mediaStore = MediaStore(db, jsTagStore)
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
    val artworkDownloader = ArtworkDownloader(tmdbClient)
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
    val imageProxyService = dev.jellystructure.tv.ImageProxyService(dataDir, configStore)
    val channelLogoStore = dev.jellystructure.tv.ChannelLogoStore(dataDir)
    val qbClient = QBittorrentClient()
    val seedingGuard = SeedingGuard(qbClient)
    val arrClient = ArrClient()
    val arrRescan = ArrRescanService(configStore, arrClient, rootScope)
    val acquisitionStore = AcquisitionStore(db)
    val acquisitionService = AcquisitionService(configStore, arrClient, tmdbClient, acquisitionStore, mediaStore, tvEventBus, rootScope)
    acquisitionService.startReconciler()
    val chartStore = ChartStore(db)
    val chartRegistry = ChartRegistry(listOf(NetflixTudumProvider()))
    val chartIngest = ChartIngestService(configStore, chartRegistry, tmdbClient, chartStore, mediaStore)
    val shutdown = startServer(
        configStore, sessionService, raviloDeviceService, raviloConfigService, channelLogoStore, homeFeedService, browseService, detailService, playbackService, jellyfinClient, mediaStore, scanner,
        artworkDownloader, tmdbClient, scanTracker, mediaHistory, activityLog, broadcaster,
        frontendDir, raviloWebDir = raviloWebDir, port = port, scanDispatcher = scanDispatcher, effectiveScanThreads = effectiveScanThreads, jsTagStore = jsTagStore, seedingGuard = seedingGuard, logoDownloader = logoDownloader, qbClient = qbClient, arrClient = arrClient, arrRescan = arrRescan, acquisitionService = acquisitionService, chartRegistry = chartRegistry, chartStore = chartStore, chartIngest = chartIngest, tvEventBus = tvEventBus, imageProxyService = imageProxyService,
    )

    // Scheduled scan / pipeline (Phase 91)
    // When scan.pipeline is non-empty, runs the full pipeline; otherwise falls back to the legacy
    // scanIntervalHours simple loop. Both share the same ScanTracker gate.
    rootScope.launch {
        while (shutdownRequested.value == 0) {
            val cfg = configStore.current
            val pipeline = cfg.scan.pipeline.filter { it.enabled }
            val schedule = cfg.scanSchedule
            val legacyHours = cfg.behavior.scanIntervalHours

            // Determine delay before next run
            val delayMs = when {
                schedule.isNotBlank() -> scheduleDelayMs(schedule)
                legacyHours > 0 -> legacyHours * 3_600_000L
                else -> 60_000L  // poll for config change
            }
            delay(delayMs)
            if (shutdownRequested.value != 0) break
            if (scanTracker.running) {
                Logger.info("Scheduled scan skipped — a scan is already running")
                continue
            }

            val active = if (pipeline.isNotEmpty()) pipeline else null
            if (active != null || (schedule.isBlank() && legacyHours > 0)) {
                val jobId = scanTracker.startNew()
                if (active != null) {
                    executePipeline(active, jobId, mediaStore, scanner, scanTracker, broadcaster, configStore, jellyfinClient, scanDispatcher, artworkDownloader, arrRescan)
                } else {
                    Logger.info("Scheduled scan starting (interval=${legacyHours}h)")
                    runScan(jobId, emptySet(), mediaStore, scanner, scanTracker, broadcaster, configStore, jellyfinClient, scanDispatcher)
                }
            }
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

/** Parse the operator schedule string → ms until next wakeup. Supports: "daily", "weekly",
 *  "6h", "every Xh" (X hours), or an integer string interpreted as hours. Falls back to 24h. */
fun scheduleDelayMs(schedule: String): Long {
    val s = schedule.trim().lowercase()
    return when {
        s == "daily"   || s == "24h" -> 24 * 3_600_000L
        s == "weekly"  || s == "7d"  -> 7  * 24 * 3_600_000L
        s == "6h"                    -> 6  * 3_600_000L
        s == "12h"                   -> 12 * 3_600_000L
        s.startsWith("every ") -> {
            val part = s.removePrefix("every ").trim()
            val h = part.removeSuffix("h").trim().toLongOrNull()
            (h ?: 24) * 3_600_000L
        }
        else -> (s.toLongOrNull() ?: 24) * 3_600_000L
    }
}

/** ms duration for a freshness cadence string: "weekly", "monthly", "6months", "yearly", "never" */
fun cadenceMs(cadence: String): Long? = when (cadence.trim().lowercase()) {
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

    val workingSet = runScan(
        jobId, emptySet(), store, scanner, scanTracker, broadcaster,
        configStore, jellyfinClient, scanDispatcher,
        freshnessFilter = freshnessFilter
    )

    if (workingSet.isEmpty()) {
        Logger.info("Pipeline scan_files: no items in working set, skipping action steps")
        return
    }

    Logger.info("Pipeline scan_files complete: ${workingSet.size} items in working set")

    val cfg = configStore.current
    for (step in pipeline) {
        if (step.step == "scan_files") continue
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
                Logger.info("write_nfo: ${workingSet.size} items (overwrite=${step.overwrite})")
                val serverUrl = cfg.apiKeys.jellyfinUrl
                for (item in workingSet) {
                    val current = store.get(item.id) ?: item
                    if (step.overwrite || cfg.behavior.overwriteNfo) {
                        runCatching { NfoWriter.write(current, serverUrl) }
                            .onFailure { Logger.warn("write_nfo failed for '${item.id}': ${it.message}") }
                    }
                }
            }
            "sync_jellyfin" -> {
                Logger.info("sync_jellyfin: ${workingSet.size} items")
                if (cfg.apiKeys.jellyfinUrl.isNotBlank() && cfg.apiKeys.jellyfinToken.isNotBlank()) {
                    for (item in workingSet) {
                        item.jellyfinId?.let { jid ->
                            runCatching {
                                jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, jid)
                            }.onFailure { Logger.warn("sync_jellyfin failed for '${item.id}': ${it.message}") }
                        }
                    }
                }
            }
            "rescan_arr" -> {
                Logger.info("rescan_arr: ${workingSet.size} items")
                for (item in workingSet) arrRescan.nudge(item)
            }
            "detect_drift" -> {
                Logger.warn("Pipeline: detect_drift not yet wired in pipeline executor")
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
    Logger.info("Pipeline complete")
}

