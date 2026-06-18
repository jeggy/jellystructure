package dev.jellystructure

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.SessionService
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.Scanner
import dev.jellystructure.media.ScanTracker
import dev.jellystructure.server.startServer
import dev.jellystructure.tmdb.TmdbClient
import dev.jellystructure.watcher.FolderWatcher
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
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
import platform.posix.sleep
import platform.posix.sockaddr_in
import platform.posix.socket
import kotlin.concurrent.AtomicInt

private val shutdownRequested = AtomicInt(0)

@OptIn(ExperimentalForeignApi::class)
private fun onSignal(sig: Int) {
    shutdownRequested.value = 1
}

@OptIn(ExperimentalForeignApi::class)
fun main() {
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
    val mediaStore = MediaStore(db)
    mediaStore.load()
    val scanner = Scanner(configStore, tmdbClient, jellyfinClient)
    val artworkDownloader = ArtworkDownloader()
    val scanTracker = ScanTracker(db)
    scanTracker.load()

    val effectiveScanThreads = configStore.current.behavior.scanThreads.coerceIn(1, 32)
    val scanDispatcher = Dispatchers.Default.limitedParallelism(effectiveScanThreads)
    scanTracker.targetWorkers.value = configStore.current.behavior.scanWorkers.coerceIn(1, 32)
    val folderWatcher = FolderWatcher(configStore) {
        if (!scanTracker.running) {
            println("[INFO] FolderWatcher: starting automatic scan")
            val jobId = scanTracker.startNew()
            try {
                scanner.scan(tracker = scanTracker) { item ->
                    mediaStore.addOrUpdate(item)
                    item.jellyfinId?.let { scanTracker.recordProcessed(it) }
                }
                scanTracker.complete()
                println("[INFO] FolderWatcher: auto-scan complete jobId=$jobId")
            } catch (e: Exception) {
                println("[ERROR] FolderWatcher auto-scan failed: ${e.message}")
                scanTracker.cancel()
            }
        } else {
            println("[INFO] FolderWatcher: scan already running — skipping auto-scan")
        }
    }

    signal(SIGTERM, staticCFunction(::onSignal))
    signal(SIGINT, staticCFunction(::onSignal))

    checkPortFree(port)
    println("[INFO] Starting jellystructure on port $port")
    println("[INFO] Serving frontend from $frontendDir")

    val mediaHistory = MediaHistory(db)
    val shutdown = startServer(
        configStore, sessionService, jellyfinClient, mediaStore, scanner,
        artworkDownloader, scanTracker, folderWatcher, mediaHistory, frontendDir, port,
        scanDispatcher, effectiveScanThreads,
    )

    while (shutdownRequested.value == 0) {
        sleep(1u)
    }
    println("[INFO] Shutdown signal received — stopping gracefully")
    shutdown()
}

@OptIn(ExperimentalForeignApi::class)
fun env(name: String, default: String): String =
    getenv(name)?.toKString() ?: default

// Fails fast with a human-readable message if the port is already bound,
// before Ktor gets a chance to produce an unreadable coroutine cancellation trace.
// TODO: This function doesn't work. Let's just solve this issue by catching the exception instead of checking upfront.
@OptIn(ExperimentalForeignApi::class)
private fun checkPortFree(port: Int) {
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
            println("[ERROR] Port $port is already in use — is another jellystructure instance running?")
            println("[ERROR]   kill it with:  fuser -k ${port}/tcp")
            exit(1)
        }
    }
}
