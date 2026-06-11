package dev.jellystructure

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.server.startServer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
fun main() {
    val configFile = env("CONFIG_FILE", "/config/config.toml")
    val port = env("SERVER_PORT", "9505").toIntOrNull() ?: 9505

    val configStore = ConfigStore(configFile)
    configStore.load()

    println("[INFO] Starting jellystructure on port $port")
    startServer(configStore, port)
}

@OptIn(ExperimentalForeignApi::class)
fun env(name: String, default: String): String =
    getenv(name)?.toKString() ?: default
