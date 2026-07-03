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
        Logger.info("TV events: device $deviceId connected for user $userId (${sessions[userId]?.size} live)", "tv")
        true
    }

    suspend fun unregister(userId: String, deviceId: String, session: DefaultWebSocketServerSession) = mutex.withLock {
        sessions[userId]?.let { map ->
            if (map[deviceId] === session) map.remove(deviceId)
            if (map.isEmpty()) sessions.remove(userId)
        }
        Logger.info("TV events: device $deviceId disconnected for user $userId (${sessions[userId]?.size ?: 0} remaining)", "tv")
    }

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

    /** Jellyfin Play command from the dashboard cast menu / Home Assistant (→ R155). [kind] is resolved
     *  server-side ("movie" | "series" | "episode") so the app never has to look it up. */
    fun notifyPlayItem(userId: String, deviceId: String, jellyfinId: String, kind: String, title: String?, startPositionMs: Long) {
        scope.launch {
            val target = mutex.withLock { sessions[userId]?.get(deviceId) } ?: return@launch
            val msg = buildString {
                append("""{"type":"play_item","jellyfin_id":"$jellyfinId","kind":"$kind","start_position_ms":$startPositionMs""")
                if (title != null) append(""","title":${title.jsonEsc()}""")
                append("}")
            }
            runCatching { target.send(Frame.Text(msg)) }
        }
    }

    /** Jellyfin Playstate command (Stop/Pause/Unpause/Seek) from the dashboard/Home Assistant (→ R155). */
    fun notifyPlaystateCommand(userId: String, deviceId: String, command: String, seekPositionMs: Long?) {
        scope.launch {
            val target = mutex.withLock { sessions[userId]?.get(deviceId) } ?: return@launch
            val seek = seekPositionMs?.let { ""","seek_position_ms":$it""" } ?: ""
            val msg = """{"type":"playstate_command","command":${command.jsonEsc()}$seek}"""
            runCatching { target.send(Frame.Text(msg)) }
        }
    }

    /** Phase 111 (FR B.3) — the `home` remote-control command: send the TV back to its home screen. */
    fun notifyNavigate(userId: String, deviceId: String, destination: String) {
        scope.launch {
            val target = mutex.withLock { sessions[userId]?.get(deviceId) } ?: return@launch
            val msg = """{"type":"navigate","destination":${destination.jsonEsc()}}"""
            runCatching { target.send(Frame.Text(msg)) }
        }
    }
}
