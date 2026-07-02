package dev.jellystructure.server.routes

import dev.jellystructure.auth.ApiKeyAttr
import dev.jellystructure.auth.ApiKeyStore
import dev.jellystructure.log.Logger
import dev.jellystructure.media.MediaStore
import dev.jellystructure.tv.RaviloDeviceService
import dev.jellystructure.tv.TvEventBus
import dev.jellystructure.tv.nowPlayingItem
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RemoteDevice(
    @SerialName("device_id") val deviceId: String,
    val name: String,
    val connected: Boolean,
    @SerialName("last_seen") val lastSeen: Long,
    @SerialName("now_playing") val nowPlaying: String? = null,
)

@Serializable
private data class PlayRequest(
    @SerialName("device_id") val deviceId: String,
    @SerialName("jellyfin_item_id") val jellyfinItemId: String,
    @SerialName("start_position_ms") val startPositionMs: Long = 0,
)

@Serializable
private data class CommandRequest(
    @SerialName("device_id") val deviceId: String,
    val command: String, // stop | pause | unpause | home
)

/**
 * Phase 111 — the external remote-control surface (Home Assistant et al.): list an API key's bound
 * user's paired Ravilo devices and push play/playstate/navigate commands to one of them over the
 * existing per-device `/api/tv/events` socket (Phase 110). Fenced to API-key auth by AuthPlugin —
 * see [ApiKeyAttr].
 */
fun Route.remoteRoutes(deviceService: RaviloDeviceService, tvEventBus: TvEventBus, mediaStore: MediaStore) {
    route("/remote") {
        get("/devices") {
            val key = call.attributes[ApiKeyAttr]
            val devices = deviceService.listByUser(key.jellyfinUserId).map { d ->
                RemoteDevice(
                    deviceId = d.deviceId,
                    name = d.displayName,
                    connected = tvEventBus.isConnected(d.deviceId),
                    lastSeen = d.lastSeen,
                    nowPlaying = nowPlayingItem(d.deviceId),
                )
            }
            call.respond(devices)
        }

        post("/play") {
            val key = call.attributes[ApiKeyAttr]
            val req = call.receive<PlayRequest>()
            val device = deviceService.listByUser(key.jellyfinUserId).firstOrNull { it.deviceId == req.deviceId }
            if (device == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "device not found for this key's user"))
                return@post
            }
            if (!tvEventBus.isConnected(device.deviceId)) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to "device_offline"))
                return@post
            }
            val (kind, title) = mediaStore.resolvePlayTarget(req.jellyfinItemId) ?: ("movie" to null)
            tvEventBus.notifyPlayItem(key.jellyfinUserId, device.deviceId, req.jellyfinItemId, kind, title, req.startPositionMs)
            Logger.info("remote play: key='${key.name}' device=${device.deviceId} item=${req.jellyfinItemId}", "remote")
            call.respond(HttpStatusCode.Accepted, mapOf("ok" to true))
        }

        post("/command") {
            val key = call.attributes[ApiKeyAttr]
            val req = call.receive<CommandRequest>()
            val device = deviceService.listByUser(key.jellyfinUserId).firstOrNull { it.deviceId == req.deviceId }
            if (device == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "device not found for this key's user"))
                return@post
            }
            if (!tvEventBus.isConnected(device.deviceId)) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to "device_offline"))
                return@post
            }
            when (req.command.lowercase()) {
                "stop", "pause", "unpause" -> tvEventBus.notifyPlaystateCommand(key.jellyfinUserId, device.deviceId, req.command.replaceFirstChar { it.uppercase() }, null)
                "home" -> tvEventBus.notifyNavigate(key.jellyfinUserId, device.deviceId, "home")
                else -> {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "command must be stop|pause|unpause|home"))
                    return@post
                }
            }
            Logger.info("remote command: key='${key.name}' device=${device.deviceId} command=${req.command}", "remote")
            call.respond(HttpStatusCode.Accepted, mapOf("ok" to true))
        }
    }
}

@Serializable
data class ApiKeySummary(
    val id: String,
    val name: String,
    @SerialName("jellyfin_user_id") val jellyfinUserId: String,
    @SerialName("jellyfin_username") val jellyfinUsername: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("last_used_at") val lastUsedAt: Long? = null,
)

@Serializable
private data class CreateApiKeyRequest(
    val name: String,
    @SerialName("jellyfin_user_id") val jellyfinUserId: String,
    @SerialName("jellyfin_username") val jellyfinUsername: String,
)

/**
 * Phase 111 (FR A.2) — Settings ▸ Connections ▸ "API keys" CRUD. Cookie-gated (falls through
 * AuthPlugin's default /api/** branch — not under /api/remote/**, so an API key can't manage keys).
 */
fun Route.apiKeyManagementRoutes(apiKeyStore: ApiKeyStore) {
    route("/settings/api-keys") {
        get {
            call.respond(apiKeyStore.list().filterNot { it.revoked }.map {
                ApiKeySummary(it.id, it.name, it.jellyfinUserId, it.jellyfinUsername, it.createdAt, it.lastUsedAt)
            })
        }
        post {
            val req = call.receive<CreateApiKeyRequest>()
            if (req.name.isBlank() || req.jellyfinUserId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "name and jellyfin_user_id are required"))
                return@post
            }
            val plaintext = apiKeyStore.create(req.name.trim(), req.jellyfinUserId, req.jellyfinUsername)
            Logger.info("API key created: name='${req.name}' user=${req.jellyfinUsername}", "remote")
            // Shown once — never retrievable again (FR A.1).
            call.respond(mapOf("token" to plaintext))
        }
        delete("/{id}") {
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            apiKeyStore.revoke(id)
            Logger.info("API key revoked: id=$id", "remote")
            call.respond(mapOf("ok" to true))
        }
    }
}
