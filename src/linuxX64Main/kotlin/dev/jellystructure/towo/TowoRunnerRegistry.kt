package dev.jellystructure.towo

import dev.jellystructure.log.Logger
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

private val json = Json { classDiscriminator = "type"; encodeDefaults = true }

/**
 * Phase 162 (Towo) — live WS connections to runner daemons, keyed by runnerId. One connection per
 * runner (a reconnect overwrites the old entry, same as TvEventBus). "Online/offline" is derived
 * ENTIRELY from presence here, never from a DB column (dev-review addendum item 2/3) — a runner row
 * can exist in towo_runner with no live connection at all, and that is exactly what "offline" means.
 */
class TowoRunnerRegistry {
    private val mutex = Mutex()
    private val sessions = mutableMapOf<String, DefaultWebSocketServerSession>()

    suspend fun register(runnerId: String, session: DefaultWebSocketServerSession) = mutex.withLock {
        sessions[runnerId]?.let { old ->
            if (old !== session) runCatching { old.close(CloseReason(CloseReason.Codes.NORMAL, "superseded by a new connection")) }
        }
        sessions[runnerId] = session
        Logger.info("Towo runner $runnerId connected", "towo")
    }

    suspend fun unregister(runnerId: String, session: DefaultWebSocketServerSession) = mutex.withLock {
        if (sessions[runnerId] === session) {
            sessions.remove(runnerId)
            Logger.info("Towo runner $runnerId disconnected", "towo")
        }
    }

    suspend fun isOnline(runnerId: String): Boolean = mutex.withLock { runnerId in sessions }

    suspend fun onlineRunnerIds(): Set<String> = mutex.withLock { sessions.keys.toSet() }

    /** Sends a command down a specific runner's connection. Returns false if that runner isn't
     *  currently connected (spec §7: the caller is expected to surface this as "runner offline",
     *  not retry silently). */
    suspend fun send(runnerId: String, message: ControlToRunner): Boolean {
        val session = mutex.withLock { sessions[runnerId] } ?: return false
        return runCatching { session.send(Frame.Text(json.encodeToString(ControlToRunner.serializer(), message))) }
            .onFailure { Logger.warn("Towo: failed to send to runner $runnerId: ${it.message}", "towo") }
            .isSuccess
    }
}
