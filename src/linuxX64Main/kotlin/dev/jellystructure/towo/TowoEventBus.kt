package dev.jellystructure.towo

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

private val json = Json { classDiscriminator = "type"; encodeDefaults = true }

/** Phase 162 (Towo) — fans TowoEvent out to every connected browser tab on `/api/towo/stream`.
 *  Single global stream (spec §6): a handful of sessions on 2-3 hosts doesn't need per-session
 *  subscription filtering, the client filters client-side. Modeled directly on WsBroadcaster. */
class TowoEventBus {
    private val mutex = Mutex()
    private val sessions = mutableSetOf<DefaultWebSocketServerSession>()

    suspend fun register(session: DefaultWebSocketServerSession) = mutex.withLock { sessions.add(session) }

    suspend fun unregister(session: DefaultWebSocketServerSession) = mutex.withLock { sessions.remove(session) }

    suspend fun broadcast(event: TowoEvent) {
        val text = json.encodeToString(TowoEvent.serializer(), event)
        val frame = Frame.Text(text)
        val active = mutex.withLock { sessions.toList() }
        for (session in active) {
            runCatching { session.send(frame) }
                .onFailure { mutex.withLock { sessions.remove(session) } }
        }
    }

    suspend fun closeAll() {
        val all = mutex.withLock { sessions.toList().also { sessions.clear() } }
        for (session in all) {
            runCatching { session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "server shutdown")) }
        }
    }
}
