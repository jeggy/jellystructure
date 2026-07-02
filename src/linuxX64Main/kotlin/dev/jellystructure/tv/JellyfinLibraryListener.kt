package dev.jellystructure.tv

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.RealtimeIngestService
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val KEEPALIVE_INTERVAL_MS = 30_000L
private const val RECONNECT_BASE_MS = 2_000L
private const val RECONNECT_MAX_MS = 60_000L
private const val DEBOUNCE_MS = 5_000L
// A stable identity for this one server-side listener socket — distinct from the per-TV Phase 110
// bridge sockets (`ravilo-<deviceId>`) and the shared admin-job identity.
private const val LISTENER_DEVICE_ID = "jellystructure-library-listener"

/**
 * Phase 114 (FR B) — one persistent, server-owned WebSocket to Jellyfin's own `/socket`, listening for
 * `LibraryChanged` so a manually-copied file (no *arr involved) still reaches Ravilo without waiting
 * for a scheduled scan. Distinct from Phase 110's [JellyfinSessionBridge]: that class holds one socket
 * *per connected Ravilo TV* under that TV's own identity; this is a single, always-on socket under a
 * fixed server identity, alive whenever `[ingest] realtime` is on and Jellyfin is configured —
 * independent of any TV being connected.
 */
class JellyfinLibraryListener(
    private val configStore: ConfigStore,
    private val jellyfinClient: JellyfinClient,
    private val store: MediaStore,
    private val realtimeIngest: RealtimeIngestService,
    private val scope: CoroutineScope,
) {
    private val http = HttpClient(Curl) { install(WebSockets) }
    private var job: Job? = null

    @Volatile var connected: Boolean = false
        private set
    @Volatile var lastEventAt: Long? = null
        private set

    fun start() {
        if (job != null) return
        job = scope.launch { runLoop() }
    }

    private suspend fun runLoop() {
        var backoff = RECONNECT_BASE_MS
        while (currentCoroutineContext().isActive) {
            val cfg = configStore.current
            val base = cfg.apiKeys.jellyfinUrl.trimEnd('/')
            val token = cfg.apiKeys.jellyfinToken
            if (!cfg.ingest.realtime || base.isBlank() || token.isBlank()) {
                connected = false
                delay(RECONNECT_MAX_MS)
                continue
            }
            val wsUrl = base.replaceFirst(Regex("^http"), "ws") + "/socket?api_key=$token&deviceId=$LISTENER_DEVICE_ID"
            try {
                http.webSocket(wsUrl) {
                    Logger.info("Jellyfin library listener connected", "ingest")
                    connected = true
                    backoff = RECONNECT_BASE_MS

                    val keepaliveJob = launch {
                        while (currentCoroutineContext().isActive) {
                            delay(KEEPALIVE_INTERVAL_MS)
                            runCatching { send(Frame.Text("""{"MessageType":"KeepAlive"}""")) }
                        }
                    }
                    val pendingIds = LinkedHashSet<String>()
                    var debounceJob: Job? = null

                    fun scheduleFlush() {
                        debounceJob?.cancel()
                        debounceJob = launch {
                            delay(DEBOUNCE_MS)
                            val ids = pendingIds.toList()
                            pendingIds.clear()
                            flush(ids)
                        }
                    }

                    try {
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            runCatching {
                                val added = parseItemsAdded(frame.readText())
                                if (added.isNotEmpty()) {
                                    lastEventAt = dev.jellystructure.nowEpochSec()
                                    pendingIds.addAll(added)
                                    scheduleFlush()
                                }
                            }.onFailure { Logger.warn("Jellyfin library listener: bad frame: ${it.message}", "ingest") }
                        }
                    } finally {
                        keepaliveJob.cancel()
                        debounceJob?.cancel()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.warn("Jellyfin library listener dropped: ${e.message}", "ingest")
            }
            connected = false
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(RECONNECT_MAX_MS)
        }
    }

    // LibraryChanged batches ItemsAdded/ItemsUpdated ids; ItemsUpdated is included only when we don't
    // already hold the item (Phase 95 non-destructive invariant: removal detection stays scan-only, and
    // re-ingesting every metadata nudge for items we already track would be noisy busywork).
    private fun parseItemsAdded(raw: String): List<String> {
        val json = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyList()
        if (json["MessageType"]?.jsonPrimitive?.contentOrNull != "LibraryChanged") return emptyList()
        val data = json["Data"] as? JsonObject ?: return emptyList()
        val added = data["ItemsAdded"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val updated = data["ItemsUpdated"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val newUpdated = updated.filter { store.resolveByJellyfinId(it) == null }
        return added + newUpdated
    }

    private suspend fun flush(ids: List<String>) {
        val cfg = configStore.current
        val base = cfg.apiKeys.jellyfinUrl.trimEnd('/')
        val token = cfg.apiKeys.jellyfinToken
        if (base.isBlank() || token.isBlank() || ids.isEmpty()) return
        val libraryPrefixes = cfg.libraries.filter { !it.skip }.map { it.jellyfinPath.ifBlank { it.localPath } }
        // Chunked at 200 ids/request — comfortably under any reasonable URL length limit.
        for (chunk in ids.chunked(200)) {
            val items = jellyfinClient.getItemsByIds(base, token, chunk)
            for (item in items) {
                if (item.type != "Movie" && item.type != "Series" && item.type != "Episode") continue
                val path = item.path ?: continue
                if (libraryPrefixes.isNotEmpty() && libraryPrefixes.none { it.isNotBlank() && path.startsWith(it) }) continue
                realtimeIngest.enqueue(item.id)
            }
        }
    }
}
