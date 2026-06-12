package dev.jellystructure.server

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.SessionService
import dev.jellystructure.auth.installAuthPlugin
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.jobs.WsBroadcaster
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.Scanner
import dev.jellystructure.media.ScanTracker
import dev.jellystructure.server.routes.authRoutes
import dev.jellystructure.server.routes.configureConfigRoutes
import dev.jellystructure.server.routes.jellyfinRoutes
import dev.jellystructure.server.routes.languageRoutes
import dev.jellystructure.server.routes.mediaRoutes
import dev.jellystructure.server.routes.setupRoutes
import dev.jellystructure.server.routes.trackRoutes
import dev.jellystructure.server.routes.triageRoutes
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

fun startServer(
    configStore: ConfigStore,
    sessionService: SessionService,
    jellyfinClient: JellyfinClient,
    mediaStore: MediaStore,
    scanner: Scanner,
    artworkDownloader: ArtworkDownloader,
    scanTracker: ScanTracker,
    frontendDir: String,
    port: Int,
) {
    val appScope = CoroutineScope(SupervisorJob())
    val broadcaster = WsBroadcaster()
    embeddedServer(CIO, port = port) {
        install(ContentNegotiation) { json() }
        install(WebSockets)
        install(CORS) {
            anyHost()
            allowHeader("Content-Type")
            allowHeader("Cookie")
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Delete)
            allowMethod(HttpMethod.Post)
            allowCredentials = true
        }

        installAuthPlugin(sessionService)

        routing {
            route("/api") {
                get("/health") {
                    call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
                }

                authRoutes(sessionService, jellyfinClient, configStore)
                configureConfigRoutes(configStore)
                setupRoutes(configStore, jellyfinClient)
                jellyfinRoutes(configStore, jellyfinClient)
                mediaRoutes(mediaStore, scanner, artworkDownloader, appScope, scanTracker, broadcaster)
                languageRoutes(configStore)
                triageRoutes(mediaStore)
                trackRoutes(mediaStore, configStore, jellyfinClient)
            }

            webSocket("/ws") {
                broadcaster.register(this)
                try {
                    for (frame in incoming) {
                        if (frame is Frame.Close) break
                    }
                } finally {
                    broadcaster.unregister(this)
                }
            }

            // SPA: serve Wasm frontend for all non-API paths
            get("{...}") {
                call.serveFrontendFile(frontendDir, call.request.path())
            }
        }
    }.start(wait = true)
}

private suspend fun io.ktor.server.application.ApplicationCall.serveFrontendFile(
    dir: String,
    requestPath: String,
) {
    val rel = requestPath.trimStart('/').ifEmpty { "index.html" }

    if (".." in rel) {
        respond(HttpStatusCode.BadRequest)
        return
    }

    val target = Path("$dir/$rel")
    if (SystemFileSystem.exists(target)) {
        val bytes = SystemFileSystem.source(target).buffered().readByteArray()
        respondBytes(bytes, contentTypeFor(rel))
        return
    }

    // SPA fallback
    val index = Path("$dir/index.html")
    if (SystemFileSystem.exists(index)) {
        val bytes = SystemFileSystem.source(index).buffered().readByteArray()
        respondBytes(bytes, ContentType.Text.Html)
    } else {
        respond(HttpStatusCode.NotFound)
    }
}

private fun contentTypeFor(path: String): ContentType = when (path.substringAfterLast('.').lowercase()) {
    "html"       -> ContentType.Text.Html
    "css"        -> ContentType.Text.CSS
    "js", "mjs"  -> ContentType.Application.JavaScript
    "wasm"       -> ContentType.parse("application/wasm")
    "json"       -> ContentType.Application.Json
    "png"        -> ContentType.Image.PNG
    "jpg", "jpeg"-> ContentType.Image.JPEG
    "svg"        -> ContentType.Image.SVG
    "ico"        -> ContentType.parse("image/x-icon")
    else         -> ContentType.Application.OctetStream
}
