package dev.jellystructure

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.SessionService
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.Scanner
import dev.jellystructure.server.startServer
import dev.jellystructure.tmdb.TmdbClient
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
fun main() {
    val configFile = env("CONFIG_FILE", "/config/config.toml")
    val sessionsFile = env("SESSIONS_FILE", "/config/sessions.json")
    val mediaFile = env("MEDIA_FILE", "/config/media.json")
    val frontendDir = env("FRONTEND_DIR", "/app/frontend")
    val port = env("SERVER_PORT", "9505").toIntOrNull() ?: 9505

    val configStore = ConfigStore(configFile)
    configStore.load()

    val sessionService = SessionService(sessionsFile)
    val jellyfinClient = JellyfinClient()
    val tmdbClient = TmdbClient(configStore)
    val mediaStore = MediaStore(mediaFile)
    mediaStore.load()
    val scanner = Scanner(configStore, tmdbClient)

    println("[INFO] Starting jellystructure on port $port")
    println("[INFO] Serving frontend from $frontendDir")

    startServer(configStore, sessionService, jellyfinClient, mediaStore, scanner, frontendDir, port)
}

@OptIn(ExperimentalForeignApi::class)
fun env(name: String, default: String): String =
    getenv(name)?.toKString() ?: default
