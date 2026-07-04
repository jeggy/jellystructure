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
import platform.posix.time
import platform.posix.time_tVar
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
    val raviloWebDir = env("RAVILO_WEB_DIR", "").takeIf { it.isNotBlank() }
    val port = env("SERVER_PORT", "9505").toIntOrNull() ?: 9505
    val tmdbBaseUrl = env("TMDB_BASE_URL", "https://api.themoviedb.org/3")

    // Phase 118 (FR B.1) — first thing: an unhandled exception anywhere in this process (not just once
    // the server is up) writes a crash marker + attempts a synchronous webhook, since the selector's
    // failure mode cancels main() outright and there's no later "safe" point to install this from.
    // (Named crashMarkerDir, not dataDir — a `dataDir` derived from DB_FILE already exists below.)
    val crashMarkerDir = configFile.substringBeforeLast('/', missingDelimiterValue = ".")
    dev.jellystructure.ops.installCrashHook(crashMarkerDir)

    val configStore = ConfigStore(configFile)
    configStore.load()
    dev.jellystructure.ops.setCrashWebhookUrl(configStore.current.behavior.notificationsWebhook)
    dev.jellystructure.ops.reportCrashRecoveryIfAny(crashMarkerDir, configStore)
    dev.jellystructure.ops.reportFdRestartRecoveryIfAny(crashMarkerDir, configStore)

    val db = createDatabase(dbFile)
    val sessionService = SessionService(db)
    val jellyfinClient = JellyfinClient()
    val tmdbClient = TmdbClient(configStore, tmdbBaseUrl)
    val imdbClient = dev.jellystructure.imdb.ImdbClient()  // Phase 131
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

    // Phase 134 (FR-OPS2 §F): 32→100 — a worker/thread count doesn't cost FDs by itself (workers queue
    // behind ProcessGate/OutboundHttp, both raised alongside this), so a powerful host can genuinely
    // run 100 concurrent scan workers instead of the extra 68 just queuing uselessly behind a 32-ceiling.
    val effectiveScanThreads = configStore.current.behavior.scanThreads.coerceIn(1, 100)
    val scanDispatcher = Dispatchers.Default.limitedParallelism(effectiveScanThreads)
    scanTracker.targetWorkers.value = configStore.current.behavior.scanWorkers.coerceIn(1, 100)

    signal(SIGTERM, staticCFunction(::onSignal))
    signal(SIGINT, staticCFunction(::onSignal))

    Logger.info("Starting jellystructure on port $port")
    Logger.info("Serving frontend from $frontendDir")

    val raviloDeviceService = RaviloDeviceService(db)
    val tvEventBus = dev.jellystructure.tv.TvEventBus(rootScope)
    val raviloConfigService = RaviloConfigService(db, tvEventBus)
    raviloConfigService.migrateAllLegacyBehaviourFields()  // R162: one-time, idempotent
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
    val seerrClient = dev.jellystructure.seerr.SeerrClient()
    val arrRescan = ArrRescanService(configStore, arrClient, rootScope)
    val sonarrEnrich = SonarrEnrichService(mediaStore, arrClient, configStore)
    val upcomingService = dev.jellystructure.tv.UpcomingService(configStore, arrClient, mediaStore, tmdbClient)
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
    val fdWatchdog = dev.jellystructure.ops.FdWatchdog(configStore, crashMarkerDir, rootScope)
    fdWatchdog.start()
    val acquisitionStore = AcquisitionStore(db)
    val acquisitionService = AcquisitionService(configStore, arrClient, tmdbClient, acquisitionStore, mediaStore, tvEventBus, rootScope)
    acquisitionService.startReconciler()
    val shutdown = startServer(
        configStore, sessionService, raviloDeviceService, raviloConfigService, channelLogoStore, homeFeedService, browseService, detailService, playbackService, jellyfinClient, mediaStore, scanner,
        artworkDownloader, tmdbClient, scanTracker, mediaHistory, activityLog, broadcaster,
        frontendDir, raviloWebDir = raviloWebDir, port = port, scanDispatcher = scanDispatcher, effectiveScanThreads = effectiveScanThreads, jsTagStore = jsTagStore, seedingGuard = seedingGuard, seedingSnapshot = seedingSnapshot, logoDownloader = logoDownloader, qbClient = qbClient, arrClient = arrClient, arrRescan = arrRescan, sonarrEnrich = sonarrEnrich, acquisitionService = acquisitionService, seerrClient = seerrClient, tvEventBus = tvEventBus, imageProxyService = imageProxyService, mediaJobQueue = mediaJobQueue, sessionBridge = sessionBridge, apiKeyStore = apiKeyStore, realtimeIngest = realtimeIngest, libraryListener = libraryListener, fdWatchdog = fdWatchdog, imdbClient = imdbClient, upcomingService = upcomingService,
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

            val active = pipeline.ifEmpty { null }
            val jobId = scanTracker.startNew()
            runTagged(
                jobId, "scheduled", if (active != null) "pipeline" else "library",
                if (active != null) "normal" else null,
                "▶ Scheduled ${if (active != null) "pipeline" else "scan"} run started", scanTracker,
            ) {
                if (active != null) {
                    executePipeline(active, jobId, mediaStore, scanner, scanTracker, broadcaster, configStore, jellyfinClient, scanDispatcher, artworkDownloader, arrRescan, sonarrEnrich, imdbClient)
                } else {
                    runScan(jobId, emptySet(), mediaStore, scanner, scanTracker, broadcaster, configStore, jellyfinClient, scanDispatcher, artworkDownloader = if (cfg.behavior.fetchImages) artworkDownloader else null)
                }
            }
        }
    }

    // Ops hook (Phase 95): a full library scan on startup when SCAN_ON_START=1 — e.g. to rebuild the
    // catalog after an incident. The scanner is non-destructive (adds/updates, flags gone items for triage).
    if (getenv("SCAN_ON_START")?.toKString() == "1") {
        rootScope.launch {
            val jobId = scanTracker.startNew()
            runTagged(jobId, "startup", "library", null, "▶ Startup scan (SCAN_ON_START=1)", scanTracker) {
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
    scanner: Scanner,
    scanTracker: ScanTracker,
    broadcaster: WsBroadcaster,
    configStore: ConfigStore,
    jellyfinClient: JellyfinClient,
    scanDispatcher: kotlinx.coroutines.CoroutineDispatcher,
    artworkDownloader: ArtworkDownloader,
    arrRescan: ArrRescanService,
    sonarrEnrich: SonarrEnrichService? = null,
    imdbClient: dev.jellystructure.imdb.ImdbClient? = null,
    // "Run pipeline now (full)" — every step downstream of scan_files (pull_tmdb, fetch_artwork,
    // sync_imdb_ratings, write_nfo, sync_jellyfin, …) only ever sees `workingSet`, i.e. whatever
    // scan_files' freshness filter let through. That's correct for "keep already-scanned metadata
    // fresh", but wrong for a step whose own "does this need doing" condition is independent of scan
    // freshness — sync_imdb_ratings backfilling a brand-new field across the whole library, or
    // write_nfo catching a stored-vs-disk hash mismatch from an edit or a TMDB re-pull. Those items
    // otherwise wait for their unrelated metadata-recheck cadence to come due, which can take weeks.
    // fullRun=true skips the freshness filter outright so worklist == the whole library for this run.
    fullRun: Boolean = false,
) {
    val scanStep = pipeline.firstOrNull { it.step == "scan_files" }
        ?: PipelineStep(step = "scan_files")

    Logger.info("Pipeline starting: ${pipeline.joinToString(" → ") { it.step }}${if (fullRun) " (full — no freshness filter)" else ""}")

    // Build freshness filter: compute the set of JellyfinItem IDs that are NOT due (→ skip them).
    // Items with no lastChecked or no release year are always included.
    val freshnessFilter: ((dev.jellystructure.auth.JellyfinItem) -> Boolean)? =
        if (fullRun || !scanStep.recheckUnchanged) null
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

    // Bug fix (2026-07-03): runScan's own completion (scanTracker.complete(), which resets
    // activeWorkers to 0 and flips running=false) must NOT fire after just this scan_files sub-step —
    // it previously did, so "Run pipeline now" could race to a false "already running" 409 on a second
    // click and the Activity page's worker count froze at 0/N for the rest of the run while pull_tmdb/
    // fetch_artwork/etc. were still actually going. This pipeline signals completion itself, once, after
    // every step has truly finished (or on the early "nothing to do" return right below).
    suspend fun signalPipelineComplete(items: List<dev.jellystructure.model.MediaItem>) {
        if (scanTracker.cancelRequested) {
            broadcaster.broadcast(JobEvent.Finished(jobId, items.size, 0))
            return
        }
        scanTracker.complete()
        broadcaster.broadcast(JobEvent.Finished(jobId, items.size, 0))
        val cfg = configStore.current
        if (cfg.apiKeys.jellyfinUrl.isNotBlank() && cfg.apiKeys.jellyfinToken.isNotBlank()) {
            jellyfinClient.triggerLibraryRefresh(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken)
        }
        if (cfg.behavior.notifyOnScanDone)
            fireWebhook(cfg, """{"event":"scan_complete","jobId":"$jobId","items":${items.size}}""")
        if (cfg.behavior.notifyOnNoMatch) {
            val unmatched = items.count { it.tmdbId == null }
            if (unmatched > 0)
                fireWebhook(cfg, """{"event":"no_tmdb_match","jobId":"$jobId","unmatched":$unmatched}""")
        }
    }

    // Phase 135 (FR-135-2 item 4) — emit the whole ordered step plan up front, before scan_files itself
    // runs, so the client can draw every step chip immediately instead of discovering steps one at a
    // time. scan_files is listed first but keeps its own existing Started/ItemScanned/FileProgress
    // events untouched below — the client infers "scan_files is active" from ScanStatusResponse.activeStep.
    val orderedSteps = listOf("scan_files") + pipeline.filter { it.step != "scan_files" }.map { it.step }
    scanTracker.setStepPlan(orderedSteps)
    broadcaster.broadcast(JobEvent.PipelinePlan(jobId, orderedSteps))
    scanTracker.setActiveStep("scan_files")

    val workingSet = withContext(RunContext(jobId, "scan_files")) {
        runScan(
            jobId, emptySet(), store, scanner, scanTracker, broadcaster,
            configStore, jellyfinClient, scanDispatcher,
            freshnessFilter = freshnessFilter,
            signalCompletion = false,
        )
    }

    sonarrEnrich?.enrichAll()

    if (workingSet.isEmpty()) {
        Logger.info("Pipeline scan_files: no items in working set, skipping action steps")
        signalPipelineComplete(workingSet)
        return
    }

    Logger.info("Pipeline scan_files complete: ${workingSet.size} items in working set")

    val cfg = configStore.current
    try {
    for (step in pipeline) {
        if (step.step == "scan_files") continue
        withContext(RunContext(jobId, step.step)) {
        Logger.info("Pipeline step: ${step.step}")
        // Phase 135 (FR-135-1) — every step below now runs its per-item work through
        // runPipelineStepPool: a bounded worker pool (reusing scan_files' pattern) sized off
        // behavior.scanWorkers (clamped per-step by pipelineStepConcurrency), so ScanTracker's
        // activeWorkers/targetWorkers reflect this step's live pool and the work is concurrent instead
        // of one item at a time. Real per-item concurrency stays bounded by each item's own existing
        // gate (OutboundHttp/ProcessGate/the artwork downloader's semaphore/the imdb throttle) — the
        // pool only controls dispatch, not a new ceiling. The pool wraps every item in runCatching
        // uniformly (matching most steps' pre-existing per-item error handling; rescan_arr/detect_drift
        // previously had none at the top level — an item failure there now degrades gracefully instead
        // of aborting the rest of the run, which is strictly safer under concurrent dispatch).
        val scanWorkers = configStore.current.behavior.scanWorkers
        when (step.step) {
            "pull_tmdb" -> {
                val toProcess = if (step.scope == "all") workingSet
                    else workingSet.filter { it.tmdbId == null }
                Logger.info("pull_tmdb: ${toProcess.size} items (scope=${step.scope})")
                runPipelineStepPool(
                    jobId, step.step, toProcess, pipelineStepConcurrency(step.step, scanWorkers),
                    scanTracker, broadcaster, labelOf = { it.title },
                ) { item ->
                    val updated = scanner.rescanMetadata(item)
                    updated?.let { store.addOrUpdate(it) }
                }
            }
            "fetch_artwork" -> {
                // R125/R126: "missing" scope = anything fetch() can fill is absent — poster/fanart, plus
                // episode stills + season posters for series.
                val toProcess = if (step.scope == "all") workingSet
                    else workingSet.filter { artworkDownloader.isArtworkIncomplete(it) }
                Logger.info("fetch_artwork: ${toProcess.size} items (scope=${step.scope})")
                runPipelineStepPool(
                    jobId, step.step, toProcess, pipelineStepConcurrency(step.step, scanWorkers),
                    scanTracker, broadcaster, labelOf = { it.title },
                ) { item ->
                    val current = store.get(item.id) ?: item
                    artworkDownloader.fetch(current)
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
                val written = AtomicInt(0)
                val unchanged = AtomicInt(0)
                val foreignSkipped = AtomicInt(0)
                runPipelineStepPool(
                    jobId, step.step, workingSet, pipelineStepConcurrency(step.step, scanWorkers),
                    scanTracker, broadcaster, labelOf = { it.title },
                ) { item ->
                    val current = store.get(item.id) ?: item
                    val wouldBeHash = NfoWriter.contentHash(current, serverUrl, cfg.metadata.ageRatingCascade)
                    if (wouldBeHash == current.nfoHash) { unchanged.incrementAndGet(); return@runPipelineStepPool }
                    val onDiskHash = NfoWriter.onDiskHash(current)
                    val isForeign = onDiskHash != null && onDiskHash != current.nfoHash
                    if (isForeign && !(step.overwrite || cfg.behavior.overwriteNfo)) { foreignSkipped.incrementAndGet(); return@runPipelineStepPool }
                    val r = NfoWriter.writeTracked(current, serverUrl, cfg.metadata.ageRatingCascade).getOrThrow()
                    store.updateOne(current.copy(nfoWrittenAt = r.writtenAt, nfoHash = r.hash))
                    written.incrementAndGet()
                }
                Logger.info("write_nfo: ${written.value} written, ${unchanged.value} unchanged" +
                    if (foreignSkipped.value > 0) ", ${foreignSkipped.value} foreign NFO(s) skipped (set overwrite to replace)" else "")
            }
            "sync_jellyfin" -> {
                // Phase 115 (FR C) — full import (not the old ValidationOnly, which never re-reads NFOs),
                // scoped to items that actually have something new to import (nfoWrittenAt > jfSyncedAt)
                // so an unchanged library doesn't hammer Jellyfin with hundreds of full refreshes a night.
                val toSync = workingSet.filter { (it.nfoWrittenAt ?: 0L) > (it.jfSyncedAt ?: 0L) && !it.jellyfinId.isNullOrBlank() }
                val jellyfinReady = cfg.apiKeys.jellyfinUrl.isNotBlank() && cfg.apiKeys.jellyfinToken.isNotBlank()
                Logger.info("sync_jellyfin: ${toSync.size} of ${workingSet.size} items have unsynced NFO changes")
                runPipelineStepPool(
                    jobId, step.step, if (jellyfinReady) toSync else emptyList(),
                    pipelineStepConcurrency(step.step, scanWorkers), scanTracker, broadcaster, labelOf = { it.title },
                ) { item ->
                    val jid = item.jellyfinId ?: return@runPipelineStepPool
                    val ok = jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, jid, full = true)
                    if (ok) store.updateOne(item.copy(jfSyncedAt = nowEpochSec()))
                }
            }
            "rescan_arr" -> {
                Logger.info("rescan_arr: ${workingSet.size} items")
                runPipelineStepPool(
                    jobId, step.step, workingSet, pipelineStepConcurrency(step.step, scanWorkers),
                    scanTracker, broadcaster, labelOf = { it.title },
                ) { item -> arrRescan.nudge(item) }
            }
            "detect_drift" -> {
                // Phase 115 (FR F) — real state evaluation across the working set, replacing the no-op.
                val converged = AtomicInt(0); val nfoStale = AtomicInt(0)
                val jfBehind = AtomicInt(0); val external = AtomicInt(0)
                runPipelineStepPool(
                    jobId, step.step, workingSet, pipelineStepConcurrency(step.step, scanWorkers),
                    scanTracker, broadcaster, labelOf = { it.title },
                ) { item ->
                    val current = store.get(item.id) ?: item
                    val result = dev.jellystructure.nfo.DriftEvaluator.evaluate(current, jellyfinClient, cfg)
                    when (result.state) {
                        dev.jellystructure.nfo.DriftState.NFO_STALE.name.lowercase() -> nfoStale.incrementAndGet()
                        dev.jellystructure.nfo.DriftState.JELLYFIN_BEHIND.name.lowercase() -> {
                            jfBehind.incrementAndGet()
                            if (step.autoReassert) {
                                runCatching { NfoWriter.writeTracked(current, cfg.apiKeys.jellyfinUrl, cfg.metadata.ageRatingCascade).getOrThrow() }
                                    .onSuccess { r -> store.updateOne(current.copy(nfoWrittenAt = r.writtenAt, nfoHash = r.hash)) }
                                current.jellyfinId?.let { jid ->
                                    val ok = runCatching { jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, jid, full = true) }.getOrDefault(false)
                                    if (ok) store.updateOne(current.copy(jfSyncedAt = nowEpochSec()))
                                }
                            }
                        }
                        dev.jellystructure.nfo.DriftState.EXTERNAL_DRIFT.name.lowercase() -> external.incrementAndGet()
                        else -> converged.incrementAndGet()
                    }
                }
                Logger.info("detect_drift: ${converged.value} converged, ${nfoStale.value} NFO stale, ${jfBehind.value} Jellyfin behind" +
                    (if (step.autoReassert) " (auto-reassert attempted)" else "") + ", ${external.value} external drift")
                if (external.value > 0 && cfg.behavior.notifyOnDrift) {
                    runCatching { fireWebhook(cfg, """{"event":"drift_detected","pipeline":true,"items":${external.value}}""") }
                        .onFailure { Logger.warn("detect_drift notify webhook failed: ${it.message}") }
                }
            }
            "sync_imdb_ratings" -> {
                // Phase 131: keyed by imdbId; a title without one has no rating to sync. A *small* pool
                // (pipelineStepConcurrency caps this step at 2) preserves the intended per-call throttle
                // (imdbapi.dev has no verified batch endpoint) instead of multiplying it by scanWorkers.
                val toSync = workingSet.filter { !it.imdbId.isNullOrBlank() }
                Logger.info("sync_imdb_ratings: ${toSync.size} of ${workingSet.size} items have an IMDb id")
                val updated = AtomicInt(0)
                runPipelineStepPool(
                    jobId, step.step, toSync, pipelineStepConcurrency(step.step, scanWorkers),
                    scanTracker, broadcaster, labelOf = { it.title },
                ) { item ->
                    val imdbId = item.imdbId ?: return@runPipelineStepPool
                    val fetched = imdbClient?.getRating(imdbId)
                    if (fetched != null) {
                        store.updateOne(item.copy(imdbRating = dev.jellystructure.model.ImdbRating(fetched.aggregateRating, fetched.voteCount, nowEpochSec())))
                        updated.incrementAndGet()
                    }
                    delay(250)
                }
                Logger.info("sync_imdb_ratings: ${updated.value} of ${toSync.size} ratings updated")
            }
            "wait" -> {
                // No per-item fan-out — still bracket with step events (FR-135-1 item 3) so the chip
                // shows active + a result summary instead of a silent gap.
                Logger.info("wait: ${step.minutes} min")
                scanTracker.setActiveStep(step.step)
                broadcaster.broadcast(JobEvent.StepStarted(jobId, step.step, 1))
                delay(step.minutes * 60_000L)
                broadcaster.broadcast(JobEvent.StepFinished(jobId, step.step, "${step.minutes} min elapsed"))
            }
            "notify" -> {
                scanTracker.setActiveStep(step.step)
                broadcaster.broadcast(JobEvent.StepStarted(jobId, step.step, 1))
                val payload = """{"event":"pipeline_complete","items":${workingSet.size},"on":"${step.on}"}"""
                runCatching { fireWebhook(cfg, payload) }
                    .onFailure { Logger.warn("notify webhook failed: ${it.message}") }
                broadcaster.broadcast(JobEvent.StepFinished(jobId, step.step, "notified"))
            }
        }
        }
    }
    Logger.info("Pipeline complete — ${workingSet.size} items processed")
    } finally {
        // Guarantees scanTracker is always released — even if a step above threw (runTagged's own
        // catch just logs "Run failed" and never touches scanTracker, which is exactly how a stuck
        // "0/N workers" / phantom-running pipeline could happen from any step-level exception).
        signalPipelineComplete(workingSet)
    }
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
 *  ScanTracker's live status for pollers — `executePipeline` immediately supersedes it with the real
 *  plan once `block` runs it; the default `["scan_files"]` is correct as-is for every runScan-only
 *  call site. */
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

