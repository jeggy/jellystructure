package dev.jellystructure

import kotlinx.coroutines.sync.Semaphore

/**
 * Global cap on simultaneous outbound HTTP connections.
 *
 * Kotlin/Native's CIO server uses select() which crashes fatally when any file descriptor
 * reaches FD_SETSIZE (1024). Every live Curl connection consumes one FD, and with 15 scan
 * workers each making concurrent TMDB + Jellyfin + artwork calls the total easily exceeds
 * the ceiling. All backend HTTP clients must acquire a permit before opening a connection.
 *
 * Budget: 24 concurrent outbound connections. Remaining headroom covers inbound server
 * connections, WebSocket sessions, SQLite WAL (3 FDs), ffprobe pipes, and stdio.
 */
object OutboundHttp {
    private val sem = Semaphore(24)
    suspend fun <T> withPermit(block: suspend () -> T): T {
        sem.acquire()
        return try { block() } finally { sem.release() }
    }
}
