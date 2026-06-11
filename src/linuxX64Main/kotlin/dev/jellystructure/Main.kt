package dev.jellystructure

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.SessionService
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.server.startServer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
fun main() {
    val configFile = env("CONFIG_FILE", "/config/config.toml")
    val sessionsFile = env("SESSIONS_FILE", "/config/sessions.json")
    val frontendDir = env("FRONTEND_DIR", "/app/frontend")
    val port = env("SERVER_PORT", "9505").toIntOrNull() ?: 9505

    val configStore = ConfigStore(configFile)
    configStore.load()

    val sessionService = SessionService(sessionsFile)
    val jellyfinClient = JellyfinClient()

    println("[INFO] Starting jellystructure on port $port")
    println("[INFO] Serving frontend from $frontendDir")

    startServer(configStore, sessionService, jellyfinClient, frontendDir, port)
}

@OptIn(ExperimentalForeignApi::class)
fun env(name: String, default: String): String =
    getenv(name)?.toKString() ?: default
