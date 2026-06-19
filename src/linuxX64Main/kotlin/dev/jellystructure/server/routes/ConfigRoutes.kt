package dev.jellystructure.server.routes

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

fun Route.configureConfigRoutes(configStore: ConfigStore, effectiveScanThreads: Int, qbClient: QBittorrentClient? = null) {
    get("/config") {
        call.respond(ConfigResponse(configStore.current, effectiveScanThreads))
    }
    put("/config") {
        val received = call.receive<AppConfig>()
        // If password sentinel is present, keep the stored password
        val config = if (received.qbittorrent?.password == "##KEEP##") {
            val stored = configStore.current.qbittorrent?.password ?: ""
            received.copy(qbittorrent = received.qbittorrent.copy(password = stored))
        } else received
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
