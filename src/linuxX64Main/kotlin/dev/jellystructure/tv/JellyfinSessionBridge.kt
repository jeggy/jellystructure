package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.JellyfinDeviceIdentity
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

// Phase 110 (FR C.5) — one long-lived outbound socket per connected TV, hard-capped and kept OUTSIDE
// OutboundHttp's 24-permit pool: a permanently-held permit there would starve every transient request
// (scan, artwork, metadata) behind it. Documented as part of the Phase 118 FD budget.
private const val MAX_BRIDGE_CONNECTIONS = 16
private const val KEEPALIVE_INTERVAL_MS = 30_000L
private const val RECONNECT_BASE_MS = 2_000L
private const val RECONNECT_MAX_MS = 60_000L

/**
 * Phase 110 — while a Ravilo TV is connected (its `/api/tv/events` socket is open), jellystructure
 * holds one outbound WebSocket to Jellyfin's own `/socket` for it: registers remote-control
 * capabilities, answers keepalives, and turns Jellyfin dashboard commands (DisplayMessage/Play/
 * Playstate) into device-addressed [TvEventBus] events for that TV's own `/api/tv/events` socket.
 * Connect/disconnect is driven by the TV's own connection lifecycle (see Server.kt `/api/tv/events`) —
 * this class owns no polling of its own.
 */
class JellyfinSessionBridge(
    private val configStore: ConfigStore,
    private val tvEventBus: TvEventBus,
    private val scope: CoroutineScope,
) {
    private val http = HttpClient(Curl) { install(WebSockets) }
    private val jellyfinClient = JellyfinClient()
    private val bridgeSemaphore = Semaphore(MAX_BRIDGE_CONNECTIONS)
    private val active = HashMap<String, Job>() // deviceId -> connection loop job

    /** Starts (or no-ops if already running) the bridge for [device]. Safe to call repeatedly. */
    fun connect(device: DeviceData) {
        if (active.containsKey(device.deviceId)) return
        active[device.deviceId] = scope.launch { runLoop(device) }
    }

    /** Stops the bridge for [deviceId] — closes the Jellyfin session promptly on the dashboard. */
    fun disconnect(deviceId: String) {
        active.remove(deviceId)?.cancel()
    }

    private suspend fun runLoop(device: DeviceData) {
        // FR C.5: the semaphore caps how many bridges run AT ONCE — a 17th connected TV simply waits
        // here until one frees up, rather than an unbounded FD count.
        bridgeSemaphore.withPermit {
            var backoff = RECONNECT_BASE_MS
            val identity = JellyfinDeviceIdentity.forDevice(device)
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val cfg = configStore.current
                val base = cfg.apiKeys.jellyfinUrl.trimEnd('/')
                if (base.isBlank() || device.jellyfinUserToken.isBlank()) {
                    delay(RECONNECT_MAX_MS)
                    continue
                }
                val wsUrl = base.replaceFirst(Regex("^http"), "ws") +
                    "/socket?api_key=${device.jellyfinUserToken}&deviceId=${identity.deviceId}"
                try {
                    http.webSocket(wsUrl) {
                        Logger.info("Jellyfin session bridge connected: device=${device.deviceId} user=${device.jellyfinUserId}", "tv")
                        backoff = RECONNECT_BASE_MS
                        runCatching { jellyfinClient.postCapabilities(base, device.jellyfinUserToken, identity) }

                        val keepaliveJob = launch {
                            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                                delay(KEEPALIVE_INTERVAL_MS)
                                runCatching { send(Frame.Text("""{"MessageType":"KeepAlive"}""")) }
                            }
                        }
                        try {
                            // FR C.4: an escaping exception in a WS read loop kills the whole
                            // Kotlin/Native process — catch everything, rethrow only cancellation.
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    runCatching { handleIncoming(device, frame.readText()) }
                                        .onFailure { Logger.warn("Jellyfin session bridge: bad frame from device=${device.deviceId}: ${it.message}", "tv") }
                                }
                            }
                        } finally {
                            keepaliveJob.cancel()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Logger.warn("Jellyfin session bridge dropped for device=${device.deviceId}: ${e.message}", "tv")
                }
                if (!active.containsKey(device.deviceId)) return // disconnect() removed us — stop reconnecting
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(RECONNECT_MAX_MS)
            }
        }
    }

    // FR C.3 — inbound command routing: Jellyfin dashboard/Home Assistant → this device's own
    // /api/tv/events socket, via TvEventBus's device-addressed events.
    private suspend fun handleIncoming(device: DeviceData, raw: String) {
        val json = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        val messageType = json["MessageType"]?.jsonPrimitive?.contentOrNull ?: return
        val data = json["Data"] as? JsonObject
        when (messageType) {
            "GeneralCommand" -> {
                val name = data?.get("Name")?.jsonPrimitive?.contentOrNull ?: return
                if (name != "DisplayMessage") return
                val args = data["Arguments"] as? JsonObject ?: return
                val text = args["Text"]?.jsonPrimitive?.contentOrNull ?: return
                val header = args["Header"]?.jsonPrimitive?.contentOrNull
                val timeoutMs = args["TimeoutMs"]?.jsonPrimitive?.longOrNull
                tvEventBus.notifyServerMessage(device.jellyfinUserId, device.deviceId, text, header, timeoutMs)
            }
            "Play" -> {
                val itemId = data?.get("ItemIds")?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull ?: return
                val startTicks = data["StartPositionTicks"]?.jsonPrimitive?.longOrNull ?: 0L
                tvEventBus.notifyPlayItem(device.jellyfinUserId, device.deviceId, itemId, startTicks / 10_000L)
            }
            "Playstate" -> {
                val command = data?.get("Command")?.jsonPrimitive?.contentOrNull ?: return
                val seekTicks = data["SeekPositionTicks"]?.jsonPrimitive?.longOrNull
                tvEventBus.notifyPlaystateCommand(device.jellyfinUserId, device.deviceId, command, seekTicks?.let { it / 10_000L })
            }
            // ForceKeepAlive and anything else: no action needed — our own keepaliveJob answers pings.
            else -> Unit
        }
    }
}
