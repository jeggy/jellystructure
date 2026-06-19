package dev.jellystructure.server

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.SessionService
import dev.jellystructure.log.Logger
import dev.jellystructure.media.ActivityLog
import dev.jellystructure.auth.installAuthPlugin
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.jobs.WsBroadcaster
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.Scanner
import dev.jellystructure.media.ScanTracker
import dev.jellystructure.watcher.FolderWatcher
import dev.jellystructure.server.routes.activityRoutes
import dev.jellystructure.server.routes.authRoutes
import dev.jellystructure.server.routes.configureConfigRoutes
import dev.jellystructure.server.routes.jellyfinRoutes
import dev.jellystructure.server.routes.mediaRoutes
import dev.jellystructure.server.routes.setupRoutes
import dev.jellystructure.server.routes.trackRoutes
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.LogoDownloader
import dev.jellystructure.server.routes.metadataRoutes
import dev.jellystructure.server.routes.triageRoutes
import dev.jellystructure.server.routes.tvRoutes
import dev.jellystructure.torrent.QBittorrentClient
import dev.jellystructure.torrent.SeedingGuard
import dev.jellystructure.tv.BrowseService
import dev.jellystructure.tv.HomeFeedService
import dev.jellystructure.tv.RaviloConfigService
import dev.jellystructure.tv.RaviloDeviceService
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
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import platform.posix.fgets
import platform.posix.pclose
import platform.posix.popen

fun startServer(
    configStore: ConfigStore,
    sessionService: SessionService,
    deviceService: RaviloDeviceService,
    raviloConfigService: RaviloConfigService,
    homeFeedService: HomeFeedService,
    browseService: BrowseService,
    jellyfinClient: JellyfinClient,
    mediaStore: MediaStore,
    scanner: Scanner,
    artworkDownloader: ArtworkDownloader,
    scanTracker: ScanTracker,
    folderWatcher: FolderWatcher,
    mediaHistory: MediaHistory,
    activityLog: ActivityLog,
    broadcaster: WsBroadcaster,
    frontendDir: String,
    port: Int,
    scanDispatcher: CoroutineDispatcher,
    effectiveScanThreads: Int,
    jsTagStore: JsTagStore,
    seedingGuard: SeedingGuard,
    logoDownloader: LogoDownloader,
    qbClient: QBittorrentClient? = null,
): suspend () -> Unit {
    val appScope = CoroutineScope(SupervisorJob())
    val engine = embeddedServer(CIO, port = port) {
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

        installAuthPlugin(sessionService, validateDeviceToken = { deviceService.validateDeviceToken(it) })

        routing {
            route("/api") {
                get("/health") {
                    call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
                }

                get("/health/full") {
                    @Serializable data class HealthCheck(val name: String, val ok: Boolean, val detail: String)
                    val checks = mutableListOf<HealthCheck>()
                    val cfg = configStore.current
                    // Jellyfin connectivity
                    val jfOk = if (cfg.apiKeys.jellyfinUrl.isNotBlank() && cfg.apiKeys.jellyfinToken.isNotBlank()) {
                        runCatching { jellyfinClient.testConnection(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken) }.getOrDefault(false)
                    } else false
                    checks.add(HealthCheck("Jellyfin", jfOk, if (cfg.apiKeys.jellyfinUrl.isBlank()) "URL not configured" else if (jfOk) "Connected to ${cfg.apiKeys.jellyfinUrl}" else "Connection failed"))
                    // TMDB key
                    val tmdbKey = cfg.apiKeys.tmdbV3Key
                    val tmdbOk = if (tmdbKey.isNotBlank()) {
                        val result = runShell("curl -sf --max-time 5 'https://api.themoviedb.org/3/configuration?api_key=${tmdbKey.replace("'", "")}'")
                        result != null && !result.contains("\"status_code\":7") && !result.contains("\"status_code\":3")
                    } else false
                    checks.add(HealthCheck("TMDB API key", tmdbOk, if (tmdbKey.isBlank()) "Key not configured" else if (tmdbOk) "Valid" else "Invalid or unreachable"))
                    // Disk space
                    val dfOut = runShell("df -BM . 2>/dev/null | tail -1")
                    val freeMb = dfOut?.trim()?.split(Regex("\\s+"))?.getOrNull(3)?.trimEnd('M')?.toLongOrNull()
                    val diskOk = freeMb != null && freeMb > 1024
                    checks.add(HealthCheck("Disk space", diskOk, if (freeMb != null) "${freeMb} MB free" else "Unknown"))
                    // mkvpropedit
                    val mkv = runShell("which mkvpropedit 2>/dev/null")?.trim()
                    checks.add(HealthCheck("mkvpropedit", !mkv.isNullOrBlank(), if (!mkv.isNullOrBlank()) mkv else "not found in PATH"))
                    // ffprobe
                    val ffp = runShell("which ffprobe 2>/dev/null")?.trim()
                    checks.add(HealthCheck("ffprobe", !ffp.isNullOrBlank(), if (!ffp.isNullOrBlank()) ffp else "not found in PATH"))
                    call.respond(mapOf("checks" to checks))
                }

                authRoutes(sessionService, jellyfinClient, configStore)
                configureConfigRoutes(configStore, effectiveScanThreads, qbClient)
                setupRoutes(configStore, jellyfinClient)
                jellyfinRoutes(configStore, jellyfinClient)
                mediaRoutes(mediaStore, scanner, artworkDownloader, appScope, scanTracker, broadcaster, jellyfinClient, configStore, mediaHistory, scanDispatcher, seedingGuard)
                activityRoutes(activityLog)
                triageRoutes(mediaStore, jellyfinClient, configStore, mediaHistory, seedingGuard)
                metadataRoutes(mediaStore, jsTagStore, logoDownloader)
                trackRoutes(mediaStore, configStore, jellyfinClient, mediaHistory, seedingGuard)
                tvRoutes(deviceService, raviloConfigService, homeFeedService, browseService, sessionService, jellyfinClient, configStore)
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
    }
    engine.start(wait = false)
    return {
        broadcaster.closeAll()
        engine.stop(1_000L, 5_000L)
        appScope.cancel()
        Logger.info("Server stopped")
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun runShell(command: String): String? = memScoped {
    val pipe = popen(command, "r") ?: return null
    val result = StringBuilder()
    val buffer = allocArray<ByteVar>(4096)
    try {
        while (fgets(buffer, 4096, pipe) != null) result.append(buffer.toKString())
    } finally {
        pclose(pipe)
    }
    result.toString().takeIf { it.isNotBlank() }
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
