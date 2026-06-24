package dev.jellystructure.tv

import dev.jellystructure.log.Logger
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-user push channel (R33). Holds live device WebSocket sessions keyed by `jellyfinUserId` and
 * fans out small change signals to all of that user's connected devices. The event is a signal only;
 * the client re-pulls the authoritative feed/config (constitution: the TV renders server-pushed state).
 */
class TvEventBus(private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private val sessions = mutableMapOf<String, MutableSet<DefaultWebSocketServerSession>>()
    private var rev = 0L

    suspend fun register(userId: String, session: DefaultWebSocketServerSession) = mutex.withLock {
        sessions.getOrPut(userId) { mutableSetOf() }.add(session)
        Logger.info("TV events: device connected for user $userId (${sessions[userId]?.size} live)", "tv")
    }

    suspend fun unregister(userId: String, session: DefaultWebSocketServerSession) = mutex.withLock {
        sessions[userId]?.let { set ->
            set.remove(session)
            if (set.isEmpty()) sessions.remove(userId)
        }
    }

    /** Notify all of [userId]'s connected devices that their RaviloConfig changed. Non-blocking. */
    fun notifyConfigChanged(userId: String) {
        scope.launch {
            val (r, targets) = mutex.withLock { (++rev) to (sessions[userId]?.toList() ?: emptyList()) }
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
            val (r, targets) = mutex.withLock { (++rev) to sessions.values.flatten() }
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
            val targets = mutex.withLock { sessions.values.flatten() }
            if (targets.isEmpty()) return@launch
            val msg = """{"type":"acquisition_changed","record":$recordJson}"""
            for (s in targets) runCatching { s.send(Frame.Text(msg)) }
        }
    }
}
