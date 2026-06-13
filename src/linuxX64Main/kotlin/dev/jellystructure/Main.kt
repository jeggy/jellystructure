package dev.jellystructure

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.SessionService
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.Scanner
import dev.jellystructure.media.ScanTracker
import dev.jellystructure.server.startServer
import dev.jellystructure.tmdb.TmdbClient
import dev.jellystructure.watcher.FolderWatcher
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import platform.posix.SIGINT
import platform.posix.SIGTERM
import platform.posix.getenv
import platform.posix.signal
import platform.posix.sleep
import kotlin.concurrent.AtomicInt

private val shutdownRequested = AtomicInt(0)

@OptIn(ExperimentalForeignApi::class)
private fun onSignal(sig: Int) {
    shutdownRequested.value = 1
}

@OptIn(ExperimentalForeignApi::class)
fun main() {
    val configFile = env("CONFIG_FILE", "/config/config.toml")
    val sessionsFile = env("SESSIONS_FILE", "/config/sessions.json")
    val mediaFile = env("MEDIA_FILE", "/config/media.json")
    val frontendDir = env("FRONTEND_DIR", "/app/frontend")
    val port = env("SERVER_PORT", "9505").toIntOrNull() ?: 9505
    val tmdbBaseUrl = env("TMDB_BASE_URL", "https://api.themoviedb.org/3")

    val configStore = ConfigStore(configFile)
    configStore.load()

    val sessionService = SessionService(sessionsFile)
    val jellyfinClient = JellyfinClient()
    val tmdbClient = TmdbClient(configStore, tmdbBaseUrl)
    val mediaStore = MediaStore(mediaFile)
    mediaStore.load()
    val scanner = Scanner(configStore, tmdbClient, jellyfinClient)
    val artworkDownloader = ArtworkDownloader()
    val scanTracker = ScanTracker()
    val folderWatcher = FolderWatcher(configStore) {
        if (!scanTracker.running) {
            println("[INFO] FolderWatcher: starting automatic scan")
            scanTracker.running = true
            scanTracker.reset()
            try {
                var count = 0
                scanner.scan(tracker = scanTracker) { item ->
                    mediaStore.addOrUpdate(item)
                    count++
                    scanTracker.lastCount = count
                }
                scanTracker.lastCount = count
            } catch (e: Exception) {
                println("[ERROR] FolderWatcher auto-scan failed: ${e.message}")
            } finally {
                scanTracker.running = false
            }
        } else {
            println("[INFO] FolderWatcher: scan already running — skipping auto-scan")
        }
    }

    signal(SIGTERM, staticCFunction(::onSignal))
    signal(SIGINT, staticCFunction(::onSignal))

    println("[INFO] Starting jellystructure on port $port")
    println("[INFO] Serving frontend from $frontendDir")

    val shutdown = startServer(
        configStore, sessionService, jellyfinClient, mediaStore, scanner,
        artworkDownloader, scanTracker, folderWatcher, frontendDir, port,
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
