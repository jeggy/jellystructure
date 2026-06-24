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
import dev.jellystructure.jobs.WsBroadcaster
import dev.jellystructure.log.Logger
import dev.jellystructure.media.ActivityLog
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.LogoDownloader
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.Scanner
import dev.jellystructure.media.ScanTracker
import dev.jellystructure.server.routes.runScan
import dev.jellystructure.server.startServer
import dev.jellystructure.tv.BrowseService
import dev.jellystructure.tv.DetailService
import dev.jellystructure.tv.HomeFeedService
import dev.jellystructure.tv.PlaybackService
import dev.jellystructure.tv.RaviloConfigService
import dev.jellystructure.tv.RaviloDeviceService
import dev.jellystructure.tmdb.TmdbClient
import dev.jellystructure.watcher.FolderWatcher
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.posix.AF_INET
import platform.posix.SIGINT
import platform.posix.SIGTERM
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.connect
import platform.posix.exit
import platform.posix.getenv
import platform.posix.htonl
import platform.posix.htons
import platform.posix.memset
import platform.posix.signal
import platform.posix.sockaddr_in
import platform.posix.socket
import kotlin.concurrent.AtomicInt

private val shutdownRequested = AtomicInt(0)

@OptIn(ExperimentalForeignApi::class)
private fun onSignal(sig: Int) {
    shutdownRequested.value = 1
}

@OptIn(ExperimentalForeignApi::class)
fun main() = runBlocking {
    val configFile = env("CONFIG_FILE", "./data/config.toml")
    val dbFile = env("DB_FILE", "./data/jellystructure.db")
    val frontendDir = env("FRONTEND_DIR", "/app/frontend")
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
    val rootScope = CoroutineScope(SupervisorJob())
    val broadcaster = WsBroadcaster()
    val activityLogFile = env("ACTIVITY_LOG_FILE", dbFile.substringBeforeLast('/') + "/activity-log.json")
    val activityLog = ActivityLog(activityLogFile, broadcaster, rootScope)
    activityLog.load()
    Logger.activityLog = activityLog
    val scanner = Scanner(configStore, tmdbClient, jellyfinClient, jsTagStore)
    val artworkDownloader = ArtworkDownloader()
    val scanTracker = ScanTracker(db)
    scanTracker.load()

    val effectiveScanThreads = configStore.current.behavior.scanThreads.coerceIn(1, 32)
    val scanDispatcher = Dispatchers.Default.limitedParallelism(effectiveScanThreads)
    scanTracker.targetWorkers.value = configStore.current.behavior.scanWorkers.coerceIn(1, 32)
    val folderWatcher = FolderWatcher(configStore) {
        if (!scanTracker.running) {
            Logger.info("FolderWatcher: starting automatic scan")
            val jobId = scanTracker.startNew()
            try {
                scanner.scan(tracker = scanTracker) { item ->
                    mediaStore.addOrUpdate(item)
                    item.jellyfinId?.let { scanTracker.recordProcessed(it) }
                }
                scanTracker.complete()
                Logger.info("FolderWatcher: auto-scan complete jobId=$jobId")
            } catch (e: Exception) {
                Logger.error("FolderWatcher auto-scan failed: ${e.message}")
                scanTracker.cancel()
            }
        } else {
            Logger.info("FolderWatcher: scan already running — skipping auto-scan")
        }
    }

    signal(SIGTERM, staticCFunction(::onSignal))
    signal(SIGINT, staticCFunction(::onSignal))

    checkPortFree(port)
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
        artworkDownloader, tmdbClient, scanTracker, folderWatcher, mediaHistory, activityLog, broadcaster,
        frontendDir, port = port, scanDispatcher = scanDispatcher, effectiveScanThreads = effectiveScanThreads, jsTagStore = jsTagStore, seedingGuard = seedingGuard, logoDownloader = logoDownloader, qbClient = qbClient, arrClient = arrClient, arrRescan = arrRescan, acquisitionService = acquisitionService, chartRegistry = chartRegistry, chartStore = chartStore, chartIngest = chartIngest, tvEventBus = tvEventBus,
    )

    // Scheduled scan — fires every scan_interval_hours hours (0 = disabled)
    rootScope.launch {
        while (shutdownRequested.value == 0) {
            val intervalHours = configStore.current.behavior.scanIntervalHours
            if (intervalHours > 0) {
                delay(intervalHours * 3_600_000L)
                if (shutdownRequested.value != 0) break
                if (!scanTracker.running) {
                    Logger.info("Scheduled scan starting (interval=${intervalHours}h)")
                    val jobId = scanTracker.startNew()
                    runScan(jobId, emptySet(), mediaStore, scanner, scanTracker, broadcaster, configStore, jellyfinClient, scanDispatcher)
                } else {
                    Logger.info("Scheduled scan skipped — a scan is already running")
                }
            } else {
                delay(60_000L)
            }
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

// Fails fast with a human-readable message if the port is already bound,
// before Ktor gets a chance to produce an unreadable coroutine cancellation trace.
// TODO: This function doesn't work. Let's just solve this issue by catching the exception instead of checking upfront.
@OptIn(ExperimentalForeignApi::class)
private suspend fun checkPortFree(port: Int) {
    val sock = socket(AF_INET, SOCK_STREAM, 0)
    if (sock < 0) return
    memScoped {
        val addr = alloc<sockaddr_in>()
        memset(addr.ptr, 0, sizeOf<sockaddr_in>().convert())
        addr.sin_family = AF_INET.convert()
        addr.sin_port = htons(port.convert())
        addr.sin_addr.s_addr = htonl(0x7f000001u) // 127.0.0.1
        val connected = connect(sock, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
        close(sock)
        if (connected == 0) {
            Logger.error("Port $port is already in use — is another jellystructure instance running?")
            Logger.error("  kill it with:  fuser -k ${port}/tcp")
            exit(1)
        }
    }
}
