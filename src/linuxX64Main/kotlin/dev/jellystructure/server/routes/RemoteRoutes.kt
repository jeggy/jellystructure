package dev.jellystructure.server.routes

import dev.jellystructure.auth.ApiKeyStore
import dev.jellystructure.auth.DeviceKey
import dev.jellystructure.auth.RemoteCaller
import dev.jellystructure.auth.RemoteCallerAttr
import dev.jellystructure.log.Logger
import dev.jellystructure.media.MediaStore
import dev.jellystructure.shared.tv.ScreenBusy
import dev.jellystructure.shared.tv.RemoteCommandRequest
import dev.jellystructure.shared.tv.RemoteDevice
import dev.jellystructure.shared.tv.RemotePairRequest
import dev.jellystructure.shared.tv.RemotePairResponse
import dev.jellystructure.shared.tv.RemotePlayRequest
import dev.jellystructure.shared.tv.ScreenStatus
import dev.jellystructure.shared.tv.deviceKindOf
import dev.jellystructure.tv.RaviloDeviceService
import dev.jellystructure.tv.ScreenPairingService
import dev.jellystructure.tv.TvEventBus
import dev.jellystructure.tv.isNearby
import dev.jellystructure.tv.nowPlayingItem
import dev.jellystructure.tv.screenStatusTracker
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Phase 111 → Phase 236 — the one device-control API, for API-key callers (Home Assistant et al.) AND,
 * as of 236, Ravilo's own PWA/Android app using their ordinary device-token authentication. Both
 * credentials resolve to a [RemoteCaller] (see `AuthPlugin.kt`); every route below scopes to that
 * caller's own devices and behaves identically for either credential, with one stated exception
 * (`/pair` — an API key has no Jellyfin user token to copy onto a receiver's row).
 */
fun Route.remoteRoutes(
    deviceService: RaviloDeviceService,
    tvEventBus: TvEventBus,
    mediaStore: MediaStore,
    apiKeyStore: ApiKeyStore,
    // Phase 236 (FR-236-2) — null keeps /pair answering 404, same "not available yet" shape castService
    // uses elsewhere in this codebase.
    screenPairingService: ScreenPairingService? = null,
    // R303 (FR-R303-2) — null keeps the pre-R303 push (kind + title only); Main always passes one.
    playPushResolver: dev.jellystructure.tv.PlayPushResolver? = null,
) {
    fun callerAddressOf(headers: io.ktor.http.Headers, remoteHost: String): String =
        headers["X-Forwarded-For"]?.substringBefore(',')?.trim()?.takeIf { it.isNotBlank() } ?: remoteHost

    /**
     * R270 (FR-R270-3/-5) — the display name of the person **actually watching** [deviceId], or null.
     *
     * One resolution, used by both the device list and `/play`'s 409, so the two cannot name different
     * people. Resolved from the screen's own `sessionUserId` against the sessions paired to that
     * device; a user id with no session on this device (signed out since, or a device this caller
     * cannot see the sessions of) resolves to null rather than to a guess.
     *
     * This is the one place FR-R270-5's disclosure flag would live: returning null here makes both the
     * busy row and the refusal fall back to *"In use"* together.
     */
    suspend fun viewerNameOf(deviceId: String): String? {
        val sessionUserId = screenStatusTracker.get(deviceId)?.status?.takeIf { it.loaded }?.sessionUserId
            ?: return null
        return deviceService.listSessions(deviceId)
            .firstOrNull { it.jellyfinUserId == sessionUserId }
            ?.jellyfinUsername
            ?.takeIf { it.isNotBlank() }
    }

    suspend fun remoteDeviceOf(d: dev.jellystructure.auth.DeviceData, callerAddress: String?): RemoteDevice {
        val pairedUsers = if (d.kind == "screen" || d.kind == "cast") {
            deviceService.listSessions(d.deviceId).map { it.jellyfinUsername }.distinct()
        } else emptyList()
        return RemoteDevice(
            deviceId = d.deviceId,
            name = d.displayName,
            kind = deviceKindOf(d.kind),
            platform = d.platform,
            online = tvEventBus.isConnected(d.deviceId),
            lastSeen = d.lastSeen,
            nearby = isNearby(callerAddress, d.lastPublicAddress),
            pairedUsers = pairedUsers,
            nowPlayingTitle = nowPlayingItem(d.deviceId)?.let { mediaStore.titleForJellyfinId(it) ?: it },
            nowPlaying = screenStatusTracker.get(d.deviceId)?.status,
            // R270 (FR-R270-3) — resolved here, not on the client. `pairedUsers.firstOrNull()` was
            // the wrong person on any TV two people had paired with, and empty for kind = "tv".
            nowPlayingUser = viewerNameOf(d.deviceId),
        )
    }

    route("/remote") {
        get("/devices") {
            val caller = call.attributes[RemoteCallerAttr]
            val callerAddress = callerAddressOf(call.request.headers, call.request.local.remoteHost)
            val devices = deviceService.listByUser(caller.jellyfinUserId)
                .map { remoteDeviceOf(it, callerAddress) }
            call.respond(devices)
        }

        get("/devices/{device_id}") {
            val caller = call.attributes[RemoteCallerAttr]
            val deviceId = call.parameters["device_id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val callerAddress = callerAddressOf(call.request.headers, call.request.local.remoteHost)
            val device = deviceService.listByUser(caller.jellyfinUserId).firstOrNull { it.deviceId == deviceId }
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "device not found for this caller"))
            call.respond(remoteDeviceOf(device, callerAddress))
        }

        post("/play") {
            val caller = call.attributes[RemoteCallerAttr]
            val req = call.receive<RemotePlayRequest>()
            val device = deviceService.listByUser(caller.jellyfinUserId).firstOrNull { it.deviceId == req.deviceId }
            if (device == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "device not found for this caller"))
                return@post
            }
            if (!tvEventBus.isConnected(device.deviceId)) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to "device_offline"))
                return@post
            }
            // FR-236-3 — a shared screen already playing for a DIFFERENT user never gets silently
            // hijacked; a single-session device (today's TVs, unchanged) has no sessionUserId to
            // disagree with the caller and is always reachable, exactly as before this phase.
            val live = screenStatusTracker.get(device.deviceId)?.status
            if (live != null && live.loaded && live.sessionUserId != null && live.sessionUserId != caller.jellyfinUserId) {
                // R270 (FR-R270-3) — the refusal names the same person the device list does, from the
                // same resolution. It used to respond with the bare ScreenStatus, whose only identity
                // is a user id, so "the list and the 409 agree" could only ever be vacuously true.
                call.respond(HttpStatusCode.Conflict, ScreenBusy(live, viewerNameOf(device.deviceId)))
                return@post
            }
            // R303 (FR-R303-2, dev review item 2) — the push names what is playing (title, S·E kicker, series
            // name, the film's or series' logo + ink) so neither the TV nor the receiver-only app fetches.
            val push = playPushResolver?.resolve(req.jellyfinItemId)
            val (kind, title) = push?.let { it.kind to it.title } ?: mediaStore.resolvePlayTarget(req.jellyfinItemId) ?: ("movie" to null)
            tvEventBus.notifyPlayItem(
                caller.jellyfinUserId, device.deviceId, req.jellyfinItemId, kind, title, req.startPositionMs, sessionUserId = caller.jellyfinUserId,
                kicker = push?.kicker, seriesName = push?.seriesName, logoUrl = push?.logoUrl, logoInk = push?.logoInk,
                segments = push?.segments, next = push?.next,
            )
            Logger.info("remote play: user=${caller.jellyfinUserId} device=${device.deviceId} item=${req.jellyfinItemId}", "remote")
            call.respond(HttpStatusCode.Accepted, mapOf("ok" to true))
        }

        post("/command") {
            val caller = call.attributes[RemoteCallerAttr]
            val req = call.receive<RemoteCommandRequest>()
            val device = deviceService.listByUser(caller.jellyfinUserId).firstOrNull { it.deviceId == req.deviceId }
            if (device == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "device not found for this caller"))
                return@post
            }
            if (!tvEventBus.isConnected(device.deviceId)) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to "device_offline"))
                return@post
            }
            val live = screenStatusTracker.get(device.deviceId)?.status
            if (live != null && live.sessionUserId != null && live.sessionUserId != caller.jellyfinUserId) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "another user's play is running on this device"))
                return@post
            }
            when (req.command.lowercase()) {
                "stop", "pause", "unpause" ->
                    tvEventBus.notifyPlaystateCommand(caller.jellyfinUserId, device.deviceId, req.command.replaceFirstChar { it.uppercase() }, null)
                "home" -> tvEventBus.notifyNavigate(caller.jellyfinUserId, device.deviceId, "home")
                // FR-236-3 — every command past the original phase-111 quartet, on the new player_command
                // event: no client implements any of these yet (FR-236-11 is carved out of this phase),
                // so there is no existing wire shape to stay compatible with here the way stop/pause/
                // unpause/home must be.
                "seek", "skip", "next", "previous", "set_audio", "set_subtitle", "set_subtitle_size",
                "cancel_next_up", "skip_segment", "set_volume", "mute" -> {
                    val args = buildJsonObject {
                        req.positionMs?.let { put("position_ms", it) }
                        req.deltaMs?.let { put("delta_ms", it) }
                        req.index?.let { put("index", it) }
                        req.size?.let { put("size", it) }
                        req.volume?.let { put("volume", it) }
                    }
                    tvEventBus.notifyPlayerCommand(caller.jellyfinUserId, device.deviceId, req.command.lowercase(), args.takeIf { it.isNotEmpty() }?.toString())
                }
                else -> {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "unrecognized command: ${req.command}"))
                    return@post
                }
            }
            Logger.info("remote command: user=${caller.jellyfinUserId} device=${device.deviceId} command=${req.command}", "remote")
            call.respond(HttpStatusCode.Accepted, mapOf("ok" to true))
        }

        // Phase 236 (FR-236-2) — device-token callers only: an API key has no Jellyfin user token to
        // copy onto the receiver's row (dev review item 1).
        post("/pair") {
            val caller = call.attributes[RemoteCallerAttr]
            if (caller.viaApiKey) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "an API key cannot pair a screen"))
                return@post
            }
            val svc = screenPairingService ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "screens are not available"))
            val phone = call.attributes[DeviceKey]
            val req = runCatching { call.receive<RemotePairRequest>() }.getOrElse {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request"))
            }
            val (name, deviceId) = svc.claim(req.code, phone)
                ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "That code is not valid any more"))
            Logger.info("remote pair: user=${phone.jellyfinUserId} claimed screen $deviceId", "remote")
            call.respond(RemotePairResponse(deviceId = deviceId, deviceName = name))
        }

        // Phase 236 (FR-236-3) — the Settings revoke, exposed to the same API.
        delete("/devices/{device_id}/sessions/me") {
            val caller = call.attributes[RemoteCallerAttr]
            val deviceId = call.parameters["device_id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            deviceService.removeSession(deviceId, caller.jellyfinUserId)
            call.respond(mapOf("ok" to true))
        }

        // Phase 236 (FR-236-3) — the API-caller status stream. A browser/HA client can't set a header on
        // a WebSocket handshake, so the credential is a query param, exactly like /api/tv/events.
        webSocket("/events") {
            // R293 (FR-R293-7, dev review item 6) — a Ravilo phone sends its token as a Bearer header now;
            // the query form stays for browsers and HA, which cannot set one.
            val token = call.request.queryParameters["token"]?.takeIf { it.isNotBlank() }
                ?: call.request.headers["Authorization"]?.removePrefix("Bearer ")?.takeIf { it.isNotBlank() }
            val apiKeyParam = call.request.queryParameters["api_key"]?.takeIf { it.isNotBlank() }
            val caller = when {
                apiKeyParam != null -> apiKeyStore.validate(apiKeyParam)?.let { RemoteCaller(it.jellyfinUserId, deviceId = null, viaApiKey = true) }
                token != null -> deviceService.validateDeviceToken(token)?.let { RemoteCaller(it.jellyfinUserId, deviceId = it.deviceId, viaApiKey = false) }
                else -> null
            }
            if (caller == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid or missing credential"))
                return@webSocket
            }
            try {
                for (frame in incoming) {
                    if (frame is Frame.Close) break
                    if (frame !is Frame.Text) continue
                    handleSubscribeMessage(frame.readText(), caller, deviceService, tvEventBus, this)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.warn("WS /api/remote/events client connection dropped: ${e.message}", "remote")
            } finally {
                tvEventBus.unsubscribeAllDeviceStatus(this)
            }
        }
    }
}

/** Shared by both the /api/remote/events socket above and /api/tv/events in Server.kt (a Ravilo client
 *  may subscribe on its own already-open socket instead of opening a second one — FR-236-3, open
 *  question 1). Authorizes [deviceId] against [caller]'s own device list before touching the bus, so a
 *  subscription can never reach a device the caller isn't already allowed to see. */
suspend fun handleSubscribeMessage(
    text: String,
    caller: RemoteCaller,
    deviceService: RaviloDeviceService,
    tvEventBus: TvEventBus,
    session: DefaultWebSocketServerSession,
) {
    val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
    val type = (obj["type"] as? JsonPrimitive)?.content ?: return
    val deviceId = (obj["device_id"] as? JsonPrimitive)?.content ?: return
    when (type) {
        "subscribe_device" -> {
            if (deviceService.listByUser(caller.jellyfinUserId).any { it.deviceId == deviceId }) {
                tvEventBus.subscribeDeviceStatus(deviceId, session)
            }
        }
        "unsubscribe_device" -> tvEventBus.unsubscribeDeviceStatus(deviceId, session)
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

// Phase 111 (FR A.2) — Settings ▸ Connections ▸ "API keys" CRUD. Cookie-gated (falls through
// AuthPlugin's default /api/** branch — not under /api/remote/**, so an API key can't manage keys).
// (Line comment on purpose: Kotlin block comments nest, so the `/*` in those path globs would break the file.)
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
