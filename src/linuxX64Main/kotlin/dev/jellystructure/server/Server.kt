package dev.jellystructure.server

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.server.routes.configureRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.websocket.WebSockets

fun startServer(configStore: ConfigStore, port: Int) {
    embeddedServer(CIO, port = port) {
        install(ContentNegotiation) { json() }
        install(WebSockets)
        install(CORS) { anyHost() }
        configureRoutes(configStore)
    }.start(wait = true)
}
