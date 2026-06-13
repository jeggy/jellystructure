package dev.jellystructure.jobs

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { classDiscriminator = "type" }

class WsBroadcaster {
    private val mutex = Mutex()
    private val sessions = mutableSetOf<DefaultWebSocketServerSession>()

    suspend fun register(session: DefaultWebSocketServerSession) = mutex.withLock {
        sessions.add(session)
    }

    suspend fun unregister(session: DefaultWebSocketServerSession) = mutex.withLock {
        sessions.remove(session)
    }

    suspend fun broadcast(event: JobEvent) {
        val text = json.encodeToString(event)
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
