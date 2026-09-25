package dev.jellystructure.tv

import dev.jellystructure.log.Logger
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private fun String.jsonEsc(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// Phase 134 (FR-OPS2 §D) — a defensive hard cap on distinct connected devices, so this class's own
// growth is bounded by construction (unlike every other consumer in the FD budget, this one previously
// had no ceiling at all: it registers unconditionally on every valid device token). 2.5× the stated
// 50-TV target, covering reconnect churn without ever letting the count grow unbounded.
private const val MAX_TV_EVENT_SESSIONS = 128

/**
 * Per-user + per-device push channel (R33; re-keyed Phase 110). Holds live device WebSocket sessions
 * keyed by `(jellyfinUserId, deviceId)` and fans out small change signals. Most events stay per-user
 * (config_changed) or global (acquisition_changed); Phase 110's Jellyfin session bridge adds
 * device-addressed events (`server_message`/`play_item`/`playstate_command`) so a dashboard message or
 * remote command reaches the ONE TV it was meant for, not every device signed into that user.
 * The event is a signal or a small directive only — the client re-pulls/derives state server-side
 * (constitution: the TV renders server-pushed state).
 */
class TvEventBus(private val scope: CoroutineScope) {
    private val mutex = Mutex()
    // userId -> (deviceId -> session). One session per device; a reconnect overwrites the old entry.
    private val sessions = mutableMapOf<String, MutableMap<String, DefaultWebSocketServerSession>>()
    private var rev = 0L
    private var homeRev = 0L  // R248 — home_changed has its own counter (see notifyHomeChanged)
    // Phase 256 (FR-256-3) — connects per device over a rolling hour; touched only under [mutex].
    private val flaps = DeviceFlapCounter()

    /**
     * Phase 134 (FR-OPS2 §D) — returns `false` (refuse) only when [deviceId] would be a genuinely NEW
     * entry and the bus is already at [MAX_TV_EVENT_SESSIONS]; a reconnect of an already-registered
     * device always succeeds (it just overwrites its own entry) so existing TVs are never punished by
     * the cap.
     */
    suspend fun tryRegister(userId: String, deviceId: String, session: DefaultWebSocketServerSession): Boolean = mutex.withLock {
        val totalDevices = sessions.values.sumOf { it.size }
        val isNewDevice = sessions[userId]?.containsKey(deviceId) != true
        if (isNewDevice && totalDevices >= MAX_TV_EVENT_SESSIONS) {
            Logger.warn("TV events: refused device $deviceId for user $userId — at the $MAX_TV_EVENT_SESSIONS-session cap", "tv")
            return@withLock false
        }
        sessions.getOrPut(userId) { mutableMapOf() }[deviceId] = session
        flaps.connected(deviceId)
        Logger.info("TV events: device $deviceId connected for user $userId (${sessions[userId]?.size} live)", "tv")
        true
    }

    /**
     * Phase 256 (FR-256-1, dev review item 1) — returns `true` when this session had already been REPLACED
     * by a newer socket of the same device (the `else` branch of the identity check *is* the `replaced`
     * cause: the older socket's cleanup finding a newer one in its slot). The caller logs the one close
     * line with the cause; [openMs] feeds the flap counter's median.
     */
    suspend fun unregister(userId: String, deviceId: String, session: DefaultWebSocketServerSession, openMs: Long = 0L): Boolean = mutex.withLock {
        var replaced = false
        sessions[userId]?.let { map ->
            if (map[deviceId] === session) map.remove(deviceId) else if (map.containsKey(deviceId)) replaced = true
            if (map.isEmpty()) sessions.remove(userId)
        }
        flaps.closed(deviceId, openMs)
        replaced
    }

    /** Phase 256 (FR-256-3) — the WARN, at most once per device per hour while it is unstable. */
    suspend fun flapWarningDue(deviceId: String): FlapStats? = mutex.withLock { flaps.warnDue(deviceId) }
    /** Phase 256 (FR-256-3) — this device's figures while it is above the threshold, else null. */
    suspend fun unstable(deviceId: String): FlapStats? = mutex.withLock { flaps.unstable(deviceId) }
    /** Phase 256 (FR-256-3) — every unstable device, for `/api/health/full`. */
    suspend fun unstableDevices(): List<FlapStats> = mutex.withLock { flaps.unstableSnapshot() }

    /** Phase 110 — is this device's `/api/tv/events` socket currently open? Used by the stop watchdog
     *  to force-stop playback the moment a TV disconnects, not just after the heartbeat timeout. */
    suspend fun isConnected(deviceId: String): Boolean = mutex.withLock { sessions.values.any { deviceId in it } }

    /** R141: monotonic rev — exposed so the degrade-to-poll `/api/tv/config/rev` endpoint can serve it. */
    suspend fun currentRev(): Long = mutex.withLock { rev }

    /** Notify all of [userId]'s connected devices that their RaviloConfig changed. Non-blocking. */
    fun notifyConfigChanged(userId: String) {
        scope.launch {
            val (r, targets) = mutex.withLock { (++rev) to (sessions[userId]?.values?.toList() ?: emptyList()) }
            if (targets.isEmpty()) return@launch
            val msg = """{"type":"config_changed","rev":$r}"""
            for (s in targets) runCatching { s.send(Frame.Text(msg)) }
        }
    }

    /**
     * R248 (FR-R248-2) — "your Home feed changed on the server; re-pull it": sent to all of [userId]'s
     * devices once a stop (or a played/mark write) has been folded into the feed caches and the
     * Continue Watching list has been rebuilt — success or failure — so the client refreshes on the
     * server's word instead of guessing on the way back from the player (which used to race the
     * server's own post-respond invalidation and cement the pre-stop row). Its own counter, not [rev]:
     * the R141 config-rev poll must not mistake a stop for a layout change. Non-blocking.
     */
    fun notifyHomeChanged(userId: String) {
        scope.launch {
            val (r, targets) = mutex.withLock { (++homeRev) to (sessions[userId]?.values?.toList() ?: emptyList()) }
            if (targets.isEmpty()) return@launch
            val msg = """{"type":"home_changed","rev":$r}"""
            for (s in targets) runCatching { s.send(Frame.Text(msg)) }
        }
    }

    /**
     * R51 — a global config write reaches all connected devices that are on the global layout
     * (i.e. don't have their own per-user config record). We broadcast to every session here;
     * clients that have a per-user override will re-pull and ignore the global change at the server side.
     */
    fun notifyGlobalConfigChanged() {
        scope.launch {
            val (r, targets) = mutex.withLock { (++rev) to sessions.values.flatMap { it.values } }
            if (targets.isEmpty()) return@launch
            val msg = """{"type":"config_changed","rev":$r}"""
            for (s in targets) runCatching { s.send(Frame.Text(msg)) }
        }
    }

    /**
     * Phase 56 — push an acquisition status change to **all** connected devices (acquisition is global,
     * not per-user). Unlike config_changed this is **payload-bearing** (the record inline) so the TV
     * patches the matching tile in place without a re-pull. Non-blocking.
     */
    fun notifyAcquisitionChanged(recordJson: String) {
        scope.launch {
            val targets = mutex.withLock { sessions.values.flatMap { it.values } }
            if (targets.isEmpty()) return@launch
            val msg = """{"type":"acquisition_changed","record":$recordJson}"""
            for (s in targets) runCatching { s.send(Frame.Text(msg)) }
        }
    }

    // ── Phase 110 — device-addressed session-bridge events (Jellyfin dashboard → this ONE TV) ────────

    /** GeneralCommand DisplayMessage from the Jellyfin dashboard (→ R152 toast). */
    fun notifyServerMessage(userId: String, deviceId: String, text: String, header: String?, timeoutMs: Long?) {
        scope.launch {
            val target = mutex.withLock { sessions[userId]?.get(deviceId) } ?: return@launch
            val msg = buildString {
                append("""{"type":"server_message","text":${text.jsonEsc()}""")
                if (header != null) append(""","header":${header.jsonEsc()}""")
                if (timeoutMs != null) append(""","timeout_ms":$timeoutMs""")
                append("}")
            }
            runCatching { target.send(Frame.Text(msg)) }
        }
    }

    /**
     * Must be called while holding [mutex]. Phase 236 (FR-236-4a, dev review item 3) — a shared screen's
     * socket is registered under whichever of its own tokens it happened to open with, which may not be
     * [userId] (a play for user B on a TV whose live socket opened as user A). Falls back to a
     * device-level lookup across every user's sessions when the direct hit misses, so a screen paired by
     * several people is reachable regardless of who is driving it right now — the route layer has
     * already authorized [userId] against this [deviceId] (`listSessions` includes them) before any
     * notify* call is made, so this fallback never reaches a device the caller wasn't already allowed to
     * command.
     */
    private fun targetFor(userId: String, deviceId: String): DefaultWebSocketServerSession? =
        sessions[userId]?.get(deviceId) ?: sessions.values.firstNotNullOfOrNull { it[deviceId] }

    /** Jellyfin Play command from the dashboard cast menu / Home Assistant (→ R155), and Phase 236's
     *  `POST /api/remote/play`. [kind] is resolved server-side ("movie" | "series" | "episode") so the
     *  app never has to look it up. [sessionUserId] (FR-236-4) tells a screen which of its own tokens to
     *  use for this play — absent (null) preserves today's single-session behaviour exactly. */
    fun notifyPlayItem(
        userId: String, deviceId: String, jellyfinId: String, kind: String, title: String?, startPositionMs: Long, sessionUserId: String? = null,
        // R303 (FR-R303-2) — what the player shows top right; see PlayItemEnvelope's doc. All optional, all additive.
        kicker: String? = null, seriesName: String? = null, logoUrl: String? = null, logoInk: String? = null,
        // R264 (FR-R264-3) — Skip Intro and the next-up card for a player that fetches nothing.
        segments: dev.jellystructure.shared.tv.TvSegmentMarkers? = null, next: dev.jellystructure.media.NextEpisode? = null,
    ) {
        scope.launch {
            val target = mutex.withLock { targetFor(userId, deviceId) } ?: return@launch
            val msg = buildString {
                append("""{"type":"play_item","jellyfin_id":"$jellyfinId","kind":"$kind","start_position_ms":$startPositionMs""")
                if (title != null) append(""","title":${title.jsonEsc()}""")
                if (sessionUserId != null) append(""","session_user_id":${sessionUserId.jsonEsc()}""")
                if (kicker != null) append(""","kicker":${kicker.jsonEsc()}""")
                if (seriesName != null) append(""","series_name":${seriesName.jsonEsc()}""")
                if (logoUrl != null) append(""","logo_url":${logoUrl.jsonEsc()}""")
                if (logoInk != null) append(""","logo_ink":${logoInk.jsonEsc()}""")
                if (segments != null) append(""","segments":${kotlinx.serialization.json.Json.encodeToString(dev.jellystructure.shared.tv.TvSegmentMarkers.serializer(), segments)}""")
                if (next != null) {
                    append(""","next_id":${next.jellyfinId.jsonEsc()}""")
                    next.title?.let { append(""","next_title":${it.jsonEsc()}""") }
                    next.kicker?.let { append(""","next_kicker":${it.jsonEsc()}""") }
                }
                append("}")
            }
            runCatching { target.send(Frame.Text(msg)) }
        }
    }

    /** Jellyfin Playstate command (Stop/Pause/Unpause/Seek) from the dashboard/Home Assistant (→ R155). */
    fun notifyPlaystateCommand(userId: String, deviceId: String, command: String, seekPositionMs: Long?) {
        scope.launch {
            val target = mutex.withLock { targetFor(userId, deviceId) } ?: return@launch
            val seek = seekPositionMs?.let { ""","seek_position_ms":$it""" } ?: ""
            val msg = """{"type":"playstate_command","command":${command.jsonEsc()}$seek}"""
            runCatching { target.send(Frame.Text(msg)) }
        }
    }

    /** Phase 111 (FR B.3) — the `home` remote-control command: send the TV back to its home screen. */
    fun notifyNavigate(userId: String, deviceId: String, destination: String) {
        scope.launch {
            val target = mutex.withLock { targetFor(userId, deviceId) } ?: return@launch
            val msg = """{"type":"navigate","destination":${destination.jsonEsc()}}"""
            runCatching { target.send(Frame.Text(msg)) }
        }
    }

    /** Phase 236 (FR-236-3) — everything `POST /api/remote/command` accepts beyond the original
     *  stop/pause/unpause/home quartet: seek/skip/next/previous/track selection/subtitle size/next-up/
     *  segment skip/volume. [argsJson] is the command's own already-encoded field object (e.g.
     *  `{"position_ms":30000}`), or `null` for a command with no arguments. */
    fun notifyPlayerCommand(userId: String, deviceId: String, command: String, argsJson: String?) {
        scope.launch {
            val target = mutex.withLock { targetFor(userId, deviceId) } ?: return@launch
            val args = argsJson?.let { ""","args":$it""" } ?: ""
            val msg = """{"type":"player_command","command":${command.jsonEsc()}$args}"""
            runCatching { target.send(Frame.Text(msg)) }
        }
    }

    // ── Phase 236 (FR-236-5) — device-status subscriptions, for the phone's remote and API callers ────

    // deviceId -> subscribed sessions. A session may subscribe to several devices (an API caller
    // watching its whole fleet); a device may have several subscribers (two phones paired to one TV).
    // Deliberately a flat map, not keyed by user — a subscribe request is authorized once, at the route
    // layer, against the caller's own device list before this is ever touched.
    private val statusSubscribers = mutableMapOf<String, MutableSet<DefaultWebSocketServerSession>>()

    suspend fun subscribeDeviceStatus(deviceId: String, session: DefaultWebSocketServerSession) = mutex.withLock {
        statusSubscribers.getOrPut(deviceId) { mutableSetOf() }.add(session)
    }

    suspend fun unsubscribeDeviceStatus(deviceId: String, session: DefaultWebSocketServerSession) = mutex.withLock {
        statusSubscribers[deviceId]?.remove(session)
        if (statusSubscribers[deviceId]?.isEmpty() == true) statusSubscribers.remove(deviceId)
    }

    /** Called when [session] itself closes, so a socket that subscribed to several devices doesn't leak
     *  a dangling reference in every one of them. */
    suspend fun unsubscribeAllDeviceStatus(session: DefaultWebSocketServerSession) = mutex.withLock {
        statusSubscribers.values.forEach { it.remove(session) }
        statusSubscribers.entries.removeAll { it.value.isEmpty() }
    }

    /** Pushes `{"type":"device_status", device_id, status}` to every subscriber of [deviceId] — the
     *  phone's own `/api/tv/events` socket (via `subscribe_device`) and any `/api/remote/events` caller
     *  alike. [statusJson] is the caller's own already-serialized [dev.jellystructure.shared.tv.ScreenStatus]. */
    fun notifyDeviceStatus(deviceId: String, statusJson: String) {
        scope.launch {
            val targets = mutex.withLock { statusSubscribers[deviceId]?.toList() ?: emptyList() }
            if (targets.isEmpty()) return@launch
            val msg = """{"type":"device_status","device_id":"$deviceId","status":$statusJson}"""
            for (s in targets) runCatching { s.send(Frame.Text(msg)) }
        }
    }

    /**
     * Home-feed playstate cache/concurrency fix — a live Jellyfin playstate fetch made to satisfy one
     * device's `/api/tv/home` request is broadcast here so every OTHER device signed into the same
     * [userId] patches its already-rendered tiles in place too, instead of each independently paying its
     * own live round trip on its own next load. Per-user (not per-device), like [notifyConfigChanged];
     * payload-bearing (the patch inline), like [notifyAcquisitionChanged]. [patchJson] is a pre-serialized
     * `Map<String, CardPlayState>` — the caller already has a `Json` instance configured, so this class
     * (which otherwise builds its small payloads by hand) doesn't need one of its own.
     */
    fun notifyPlaystateChanged(userId: String, patchJson: String) {
        scope.launch {
            val targets = mutex.withLock { sessions[userId]?.values?.toList() ?: emptyList() }
            if (targets.isEmpty()) return@launch
            val msg = """{"type":"playstate_changed","patch":$patchJson}"""
            for (s in targets) runCatching { s.send(Frame.Text(msg)) }
        }
    }
}
