package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.JellyfinDeviceIdentity
import dev.jellystructure.auth.jellyfinAuth
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger
import dev.jellystructure.media.MediaStore
import dev.jellystructure.ops.SpinLock
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
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

// Phase 238 (FR-238-2) — "log once" and "log once ever" are different things, and the code used to
// implement the second: a permanently broken bridge produced exactly one warn line and then nothing,
// forever, while the retry loop ran for the life of the process. That is how a live feature stayed
// broken for hours after the Jellyfin 12.1 upgrade with a single 20:16:51 log line to show for it.
private const val BRIDGE_RELOG_INTERVAL_MS = 15 * 60_000L

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
    private val mediaStore: MediaStore,
    // R303 (FR-R303-2) — the dashboard's own *Play on* names what is playing too; null = kind + title only.
    private val playPushResolver: PlayPushResolver? = null,
) {
    private val http = HttpClient(Curl) { install(WebSockets) }
    private val jellyfinClient = JellyfinClient()
    private val bridgeSemaphore = Semaphore(MAX_BRIDGE_CONNECTIONS)

    /**
     * Phase 238 (dev review item 5) — every piece of cross-thread bridge state, behind one lock.
     *
     * `active` and the old `bridgeDropLogged` were plain collections mutated by `connect`/`disconnect`
     * from the `/api/tv/events` route and read by the retry loop running on `rootScope`, which carries
     * no dispatcher and is therefore `Dispatchers.Default` — genuinely multi-threaded on Kotlin/Native.
     * That was already a latent race; FR-238-2's counters and FR-238-3's health read (on a third
     * thread) make it a certainty.
     *
     * A `SpinLock` rather than the `Mutex` [TvEventBus] uses for its own map: the health handler's read
     * must not suspend on a lock the retry loop holds. Every guarded section here is a map put or get,
     * never I/O and never a suspension point, which is exactly what `SpinLock` documents itself for.
     */
    private val lock = SpinLock()
    private val active = HashMap<String, Job>() // deviceId -> connection loop job
    private val state = HashMap<String, BridgeState>() // deviceId -> FR-238-2/-3 observability state

    /** Mutable per-device bridge state. Only ever touched under [lock]. */
    private data class BridgeState(
        var connected: Boolean = false,
        var consecutiveFailures: Int = 0,
        var lastError: String? = null,
        var lastConnectedAtMs: Long? = null,
        var lastLoggedAtMs: Long = 0,
        // FR-238-6 — the runLoop guard branch, which throws nothing and so used to log nothing at all.
        var neverAttempted: Boolean = false,
    )

    /** Starts (or no-ops if already running) the bridge for [device]. Safe to call repeatedly. */
    fun connect(device: DeviceData) {
        val start = lock.withLock {
            if (active.containsKey(device.deviceId)) return@withLock false
            state.getOrPut(device.deviceId) { BridgeState() }
            true
        }
        if (!start) return
        val job = scope.launch { runLoop(device) }
        // Registered after launch so the map never holds a job that failed to start. A second connect()
        // racing this one is covered by the guard above: it returns before reaching here.
        lock.withLock { active[device.deviceId] = job }
    }

    /** Stops the bridge for [deviceId] — closes the Jellyfin session promptly on the dashboard. */
    fun disconnect(deviceId: String) {
        val job = lock.withLock {
            state.remove(deviceId)
            active.remove(deviceId)
        }
        job?.cancel()
    }

    /**
     * FR-238-3 — a cheap snapshot for `/api/health/full`. Plain map reads under the spin lock, no
     * probing: the handler may not reach Jellyfin to answer a health question about reaching Jellyfin.
     */
    fun healthSnapshot(): List<BridgeHealth> {
        val now = nowMs()
        return lock.withLock {
            state.entries.map { (deviceId, st) ->
                BridgeHealth(
                    deviceId = deviceId,
                    connected = st.connected,
                    consecutiveFailures = st.consecutiveFailures,
                    lastError = st.lastError,
                    lastConnectedMsAgo = st.lastConnectedAtMs?.let { now - it },
                    neverAttempted = st.neverAttempted,
                )
            }.sortedBy { it.deviceId }
        }
    }

    /** FR-238-3 — the counts `/api/health` carries. That endpoint is unauthenticated, so it gets
     *  numbers and nothing that names a device (dev review item 4). */
    fun healthCounts(): Pair<Int, Int> = lock.withLock {
        val connected = state.values.count { it.connected }
        connected to (state.size - connected)
    }

    /**
     * FR-238-2 — record a failure and decide whether it may speak.
     *
     * The first drop per device logs, as before. A device that keeps failing logs again at most every
     * [BRIDGE_RELOG_INTERVAL_MS], carrying the consecutive-failure count and how long it has been since
     * the last success — so "this has been broken for three hours" is a thing the log can say, which it
     * could not before. A successful connect resets both the window and the counter.
     */
    private fun recordFailure(deviceId: String, reason: String, neverAttempted: Boolean = false): BridgeState? {
        val now = nowMs()
        return lock.withLock {
            val st = state.getOrPut(deviceId) { BridgeState() }
            st.connected = false
            st.consecutiveFailures += 1
            st.lastError = reason
            st.neverAttempted = neverAttempted
            if (st.consecutiveFailures == 1 || now - st.lastLoggedAtMs >= BRIDGE_RELOG_INTERVAL_MS) {
                st.lastLoggedAtMs = now
                st.copy()
            } else null
        }
    }

    private fun recordConnected(deviceId: String) {
        val now = nowMs()
        lock.withLock {
            val st = state.getOrPut(deviceId) { BridgeState() }
            st.connected = true
            st.consecutiveFailures = 0
            st.lastError = null
            st.neverAttempted = false
            st.lastConnectedAtMs = now
            st.lastLoggedAtMs = 0
        }
    }

    private fun recordDisconnectedFromLoop(deviceId: String) {
        lock.withLock { state[deviceId]?.connected = false }
    }

    private suspend fun logFailure(device: DeviceData, reason: String, st: BridgeState) {
        val sinceSuccess = st.lastConnectedAtMs?.let { " · ${(nowMs() - it) / 1000}s since last connect" }
            ?: " · never connected"
        val repeat = if (st.consecutiveFailures > 1) " · ${st.consecutiveFailures} consecutive failures" else ""
        Logger.warn(
            "Jellyfin session bridge down for device=${device.deviceId}: $reason$repeat$sinceSuccess",
            "tv",
        )
    }

    private suspend fun runLoop(device: DeviceData) {
        // FR C.5: the semaphore caps how many bridges run AT ONCE — a 17th connected TV simply waits
        // here until one frees up, rather than an unbounded FD count.
        //
        // Phase 238 (dev review item 7) records the interaction rather than changing it: the permit is
        // held for the WHOLE retry loop, so a permanently failing bridge occupies one of sixteen slots
        // forever. Open question 2's lean (keep retrying) stands for a household of this size, and
        // FR-238-3 now makes the waste observable. If a give-up rule is ever added it must RELEASE the
        // permit, not merely stop logging.
        bridgeSemaphore.withPermit {
            var backoff = RECONNECT_BASE_MS
            val identity = JellyfinDeviceIdentity.forDevice(device)
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val cfg = configStore.current
                val base = cfg.apiKeys.jellyfinUrl.trimEnd('/')
                if (base.isBlank() || device.jellyfinUserToken.isBlank()) {
                    // FR-238-6 — this branch throws nothing, so before this phase it logged nothing at
                    // all, not even the first line, and on a health endpoint it would have been
                    // indistinguishable from a handshake being refused. It is its own state now.
                    val reason = if (base.isBlank()) BridgeFailure.NO_JELLYFIN_URL else BridgeFailure.NO_DEVICE_TOKEN
                    recordFailure(device.deviceId, reason, neverAttempted = true)
                        ?.let { logFailure(device, reason, it) }
                    delay(RECONNECT_MAX_MS)
                    continue
                }
                // Phase 141 (§C) — the same negative-cache + server-token fallback PlaybackService's
                // REST calls already use: a dead paired token opens the bridge under the server
                // identity instead of 403ing the WS handshake forever.
                //
                // FR-238-4 does NOT delete this (dev review item 8). It forbids a second WIRE FORMAT,
                // not a second credential: this line chooses which token to present, never how. Note
                // the fallback has never actually been exercised against 12.1, because until FR-238-1 a
                // dead device token and a good server token failed the handshake identically.
                val effectiveToken = jellyfinClient.tvToken(base, device, cfg.apiKeys.jellyfinToken)
                // FR-238-1 — the credential is an `Authorization` header, never a query parameter.
                // Jellyfin 12.1 answers 403 to `/socket?api_key=…` (measured live 2026-09-18: `apikey`
                // and the header both 101, `api_key` and no credential both 403), so phase 110's bridge
                // could not open at all and every guarantee that rides it — dashboard pause/seek,
                // remote control, Home Assistant, TvEventBus commands — was quietly false.
                //
                // The header rather than `apikey`, though both work: it is the mechanism every other
                // authenticated call already uses, it keeps the token out of proxy and access logs, and
                // it leaves exactly one place in the codebase where a Jellyfin credential becomes wire
                // format. `deviceId` stays in the query string — it is not a credential, and the
                // header's own DeviceId is the same string by construction (both come from `identity`),
                // so Jellyfin binds this socket to the session the REST calls already use.
                val wsUrl = base.replaceFirst(Regex("^http"), "ws") +
                    "/socket?deviceId=${identity.deviceId}"
                try {
                    http.webSocket(wsUrl, request = { jellyfinAuth(effectiveToken, identity) }) {
                        Logger.info("Jellyfin session bridge connected: device=${device.deviceId} user=${device.jellyfinUserId}", "tv")
                        backoff = RECONNECT_BASE_MS
                        recordConnected(device.deviceId)
                        runCatching { jellyfinClient.postCapabilities(base, effectiveToken, identity) }

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
                            recordDisconnectedFromLoop(device.deviceId)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // A CLASSIFIED reason, never `e.message` (dev review item 4b): curl and Ktor failure
                    // text routinely carries the request URL, and until the line above that URL had the
                    // token in it — so echoing it onto a health endpoint would have published the
                    // household's Jellyfin credential.
                    val reason = BridgeFailure.classify(e)
                    recordFailure(device.deviceId, reason)?.let { logFailure(device, reason, it) }
                }
                val stillWanted = lock.withLock { active.containsKey(device.deviceId) }
                if (!stillWanted) return // disconnect() removed us — stop reconnecting
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
                val push = playPushResolver?.resolve(itemId)
                val (kind, title) = push?.let { it.kind to it.title } ?: mediaStore.resolvePlayTarget(itemId) ?: ("movie" to null)
                tvEventBus.notifyPlayItem(
                    device.jellyfinUserId, device.deviceId, itemId, kind, title, startTicks / 10_000L,
                    kicker = push?.kicker, seriesName = push?.seriesName, logoUrl = push?.logoUrl, logoInk = push?.logoInk,
                )
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

/** Phase 238 — second-granularity is enough for a 15-minute suppression window and a "how long has
 *  this been broken" line; matches the sibling helpers in this package. */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun nowMs(): Long = platform.posix.time(null) * 1000L
