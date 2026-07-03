package dev.jellystructure

import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.Json

/**
 * Global cap on simultaneous outbound HTTP connections, plus (Phase 129) the single shared Curl
 * client every stateless outbound caller should use.
 *
 * Kotlin/Native's CIO server uses select() which crashes fatally when any file descriptor
 * reaches FD_SETSIZE (1024). Every live Curl connection consumes one FD, and with 15 scan
 * workers each making concurrent TMDB + Jellyfin + artwork calls the total easily exceeds
 * the ceiling. All backend HTTP clients must acquire a permit before opening a connection.
 *
 * Budget: 24 concurrent outbound connections (in-flight, via [withPermit]) + one shared idle
 * connection pool (resting keep-alive sockets, via [client]) instead of the ~12 independent
 * pools a `HttpClient(Curl)` per caller used to leave resting. Remaining headroom covers inbound
 * server connections, WebSocket sessions, SQLite WAL, ffprobe pipes, and stdio.
 */
object OutboundHttp {
    private val sem = Semaphore(24)
    suspend fun <T> withPermit(block: suspend () -> T): T {
        sem.acquire()
        return try { block() } finally { sem.release() }
    }

    /**
     * Phase 129 (FR-OPS1 §B.1) — the one shared client every stateless outbound caller (TMDB,
     * Jellyfin, *arr, artwork/logo downloaders, Ravilo artwork, chart providers) uses instead of
     * constructing its own `HttpClient(Curl)`. Timeouts are the most generous value any current
     * caller needed (artwork/logo downloads: 10s connect, 120s socket/request) — safe for callers
     * that used a shorter timeout (Jellyfin, Sonarr/Radarr, TMDB: 5-10s connect, 30-60s socket),
     * since a longer timeout only waits longer on a truly-hung peer, never a premature failure.
     * The two long-lived WebSocket clients (`JellyfinLibraryListener`, `JellyfinSessionBridge`)
     * are NOT stateless request/response callers and stay on their own dedicated clients.
     */
    val client: HttpClient by lazy {
        HttpClient(Curl) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 120_000
                requestTimeoutMillis = 120_000
            }
        }
    }
}
