package dev.jellystructure.server.routes

import dev.jellystructure.arr.ArrClient
import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.QBittorrentConfig
import dev.jellystructure.torrent.QBittorrentClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable

@Serializable
data class ConfigResponse(
    val config: AppConfig,
    val effectiveScanThreads: Int,
)

@Serializable
data class LibraryPathDiag(
    val name: String,
    val jellyfinPath: String,
    val localPath: String,
    val matchPrefix: String,
    val localExists: Boolean,
)

@Serializable
private data class TestQBittorrentRequest(
    val url: String,
    val username: String,
    val password: String,
)

@Serializable
data class TestQBittorrentResult(
    val ok: Boolean,
    val detail: String,
    val torrentCount: Int? = null,
)

@Serializable
private data class TestArrRequest(
    val url: String,
    val apiKey: String,
)

@Serializable
data class ArrTestResult(
    val ok: Boolean,
    val detail: String,
    val version: String? = null,
    val rootFolders: List<String>? = null,
)

fun Route.configureConfigRoutes(
    configStore: ConfigStore,
    effectiveScanThreads: Int,
    qbClient: QBittorrentClient? = null,
    arrClient: ArrClient? = null,
) {
    get("/config") {
        call.respond(ConfigResponse(configStore.current, effectiveScanThreads))
    }
    put("/config") {
        val received = call.receive<AppConfig>()
        val stored = configStore.current
        // Blank secrets arrive as the "##KEEP##" sentinel — preserve the stored value rather than wipe it.
        var config = received
        if (received.qbittorrent?.password == "##KEEP##") {
            config = config.copy(qbittorrent = received.qbittorrent.copy(password = stored.qbittorrent?.password ?: ""))
        }
        if (received.radarr?.apiKey == "##KEEP##") {
            config = config.copy(radarr = received.radarr.copy(apiKey = stored.radarr?.apiKey ?: ""))
        }
        if (received.sonarr?.apiKey == "##KEEP##") {
            config = config.copy(sonarr = received.sonarr.copy(apiKey = stored.sonarr?.apiKey ?: ""))
        }
        // The frontend AppConfig model omits acquisition and discover (config-file-only sections,
        // not exposed in the Settings UI). Preserve the stored values so a Settings save never wipes them.
        if (config.acquisition == null) config = config.copy(acquisition = stored.acquisition)
        if (config.discover    == null) config = config.copy(discover    = stored.discover)
        configStore.update(config)
        call.respond(HttpStatusCode.NoContent)
    }
    post("/config/test-qbittorrent") {
        if (qbClient == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, TestQBittorrentResult(false, "qBittorrent client not available"))
            return@post
        }
        val req = call.receive<TestQBittorrentRequest>()
        val tmpConfig = QBittorrentConfig(url = req.url, username = req.username, password = req.password, enabled = true)
        val result = runCatching {
            val sid = qbClient.login(tmpConfig)
            val torrents = qbClient.getTorrents(tmpConfig, sid)
            TestQBittorrentResult(true, "Connected successfully", torrents.size)
        }.getOrElse { e ->
            TestQBittorrentResult(false, e.message ?: "Unknown error")
        }
        call.respond(result)
    }
    // Phase 54 — Radarr/Sonarr connection test (temporary creds, never persisted). The v3 API is
    // identical, so both endpoints share one handler.
    suspend fun testArr(req: TestArrRequest): ArrTestResult {
        if (arrClient == null) return ArrTestResult(false, "*arr client not available")
        if (req.url.isBlank()) return ArrTestResult(false, "URL not configured")
        val ping = arrClient.ping(req.url, req.apiKey)
        if (!ping.ok) return ArrTestResult(false, ping.detail)
        val roots = runCatching { arrClient.rootFolders(req.url, req.apiKey) }.getOrDefault(emptyList())
        return ArrTestResult(true, "Connected" + (ping.version?.let { " · v$it" } ?: ""), ping.version, roots)
    }
    post("/config/test-radarr") { call.respond(testArr(call.receive())) }
    post("/config/test-sonarr") { call.respond(testArr(call.receive())) }
    // Import root folders → pre-fill [[libraries]] local paths (uses the stored, saved creds).
    get("/config/radarr/root-folders") {
        val r = configStore.current.radarr
        if (arrClient == null || r == null || r.url.isBlank()) { call.respond(emptyList<String>()); return@get }
        call.respond(runCatching { arrClient.rootFolders(r.url, r.apiKey) }.getOrDefault(emptyList()))
    }
    get("/config/sonarr/root-folders") {
        val s = configStore.current.sonarr
        if (arrClient == null || s == null || s.url.isBlank()) { call.respond(emptyList<String>()); return@get }
        call.respond(runCatching { arrClient.rootFolders(s.url, s.apiKey) }.getOrDefault(emptyList()))
    }
    get("/config/path-check") {
        val diags = configStore.current.libraries.filter { !it.skip }.map { lib ->
            val prefix = lib.jellyfinPath.ifBlank { lib.localPath }
            LibraryPathDiag(
                name = lib.name,
                jellyfinPath = lib.jellyfinPath,
                localPath = lib.localPath,
                matchPrefix = prefix,
                localExists = lib.localPath.isNotBlank() && SystemFileSystem.exists(Path(lib.localPath)),
            )
        }
        call.respond(diags)
    }
}
