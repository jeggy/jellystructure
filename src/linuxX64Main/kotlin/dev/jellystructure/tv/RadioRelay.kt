package dev.jellystructure.tv

import dev.jellystructure.OutboundHttp
import dev.jellystructure.auth.jellyfinAuth
import dev.jellystructure.log.Logger
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.discard
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException

// DEB-2656 (DEB-2628 FR-4, FR-5) — jellystructure's audio relay for a radio station: the one place a station's bytes
// pass through this server. The credential, the limits and the lifetime live in [LiveTvService]; this file is the wire.

/** `GET /api/tv/livetv/radio/{relay_id}` — the path a ticket's `hls_url` carries (the client resolves it against its own server base). */
internal const val RADIO_RELAY_PATH = "/api/tv/livetv/radio/"

/** FR-4 — at most this many open connections on one `relay_id` (a reconnect may overlap the old one) … */
internal const val RADIO_RELAY_MAX_PER_ID = 2
/** … and this many across the server, one upstream each. */
internal const val RADIO_RELAY_MAX_TOTAL = 32
/** FR-4 — the upstream must answer within this long. */
private const val RADIO_CONNECT_TIMEOUT_MS = 10_000L
/** A live station never goes this long without a byte; a stalled upstream is a dead one. */
private const val RADIO_STALL_TIMEOUT_MS = 30_000L
private const val RADIO_MAX_REDIRECTS = 3
private const val RADIO_CHUNK_BYTES = 16 * 1024
/** The most of a non-audio answer the relay reads before it gives up on the body (it answers 502 either way). */
private const val RADIO_DISCARD_MAX_BYTES = 64L * 1024

/**
 * FR-4 — what a tune's relay holds, kept on its [LiveTvService.ActiveLive] row so a stop, a re-tune and the watchdog end
 * it with the tune. [id] is the credential (128 bits, [secureHexId]); [upstreamUrl] is absolute. [bearer] is the device's
 * own Jellyfin token and is only ever sent to Jellyfin's own host (a Jellyfin-local stream needs it; the broadcaster
 * must never see it). [open] is the connections now streaming; [endedMs] is when the last of them closed (0 = none yet).
 */
internal data class RelayState(
    val id: String,
    val upstreamUrl: String,
    val jellyfinBase: String,
    val bearer: String?,
    val contentType: String?,
    val open: Int = 0,
    val endedMs: Long = 0L,
)

/** One open connection's claim on a relay: handed out by [LiveTvService.relayOpen], given back with [LiveTvService.relayRelease]. */
internal class RelayLease(
    val deviceId: String,
    val liveStreamId: String,
    val relayId: String,
    val upstreamUrl: String,
    val jellyfinBase: String,
    val bearer: String?,
)

internal sealed interface RelayOpen {
    /** An unknown, stopped, re-tuned or watchdog-closed id. */
    data object NotFound : RelayOpen
    /** Over the per-id or the server-wide limit. */
    data object Busy : RelayOpen
    class Granted(val lease: RelayLease) : RelayOpen
}

/** FR-4 — what the relay will pass on: any audio type (`audio/` and anything after it), and `application/ogg`. Anything else (a playlist, a page) is a 502. */
internal fun isRelayableAudio(contentType: String?): Boolean {
    val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
    return type.startsWith("audio/") || type == "application/ogg"
}

/** The format on the admin's live row — *MP3 · 192 kb/s*; the bitrate is left out when the source did not state one. */
internal fun radioFormatLabel(contentType: String?, kbps: Int?): String? {
    val type = contentType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    val name = when (type) {
        "audio/mpeg", "audio/mp3" -> "MP3"
        "audio/aac", "audio/aacp", "audio/x-aac", "audio/mp4", "audio/x-m4a" -> "AAC"
        "audio/ogg", "application/ogg" -> "Ogg"
        "audio/flac", "audio/x-flac" -> "FLAC"
        else -> type.substringAfter('/').removePrefix("x-").uppercase()
    }
    return if (kbps != null && kbps > 0) "$name · $kbps kb/s" else name
}

/**
 * FR-4 — a request path as a log line may carry it: the relay's credential is the last segment and never goes to a log,
 * so everything after `/radio/` is dropped. Any other path is returned as it was.
 */
internal fun redactedPath(path: String): String {
    val at = path.indexOf(RADIO_RELAY_PATH)
    return if (at < 0) path else path.substring(0, at + RADIO_RELAY_PATH.length) + "…"
}

private val relayHttp by lazy { OutboundHttp.client.config { followRedirects = false } }

private sealed interface RelayStep {
    data class Redirect(val url: String) : RelayStep
    data object Done : RelayStep
}

/** A 502, answered at once and from inside the upstream's own call (a Curl response is not left half-read while we answer). */
private suspend fun refuse(call: ApplicationCall, reason: String): RelayStep {
    Logger.info("Radio relay: $reason", "livetv")
    call.respond(HttpStatusCode.BadGateway, mapOf("error" to "the station could not be relayed"))
    return RelayStep.Done
}

/**
 * FR-4 — streams [lease]'s upstream into [call]: one upstream per connection (no fan-out), at most three redirects, the
 * upstream's `Content-Type` and `Cache-Control: no-store`, no `Content-Length`, `Access-Control-Allow-Origin: *`. It never
 * sends `Icy-MetaData`, so no in-band metadata is mixed into the audio. [stillLive] is asked after every chunk: a stop, a
 * re-tune or the watchdog ends the tune, and the connection ends with it.
 *
 * It holds no [OutboundHttp] permit: a permit lasts as long as the call, a listener lasts hours, and 32 of them would hold
 * half the gate the rest of the server shares. The 32-connection cap is this relay's own bound on file descriptors.
 * Nothing here writes the relay id, or the upstream URL (a source's URL can carry its own credentials), to a log.
 */
internal suspend fun pumpRadioRelay(call: ApplicationCall, lease: RelayLease, stillLive: () -> Boolean) {
    var url = lease.upstreamUrl
    var committed = false
    try {
        for (hop in 0..RADIO_MAX_REDIRECTS) {
            val here = url
            val step = relayHttp.prepareGet(here) {
                header(HttpHeaders.Accept, "audio/*, application/ogg;q=0.9")
                // Only Jellyfin's own host gets Jellyfin's token (FR-238-1: one place a credential becomes wire format).
                if (lease.bearer != null && here.startsWith(lease.jellyfinBase, ignoreCase = true)) jellyfinAuth(lease.bearer)
                timeout {
                    connectTimeoutMillis = RADIO_CONNECT_TIMEOUT_MS
                    socketTimeoutMillis = RADIO_STALL_TIMEOUT_MS
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                }
            }.execute { resp ->
                // A call that returns with its response unread is cancelled under Curl's easy handle and finishes only when the engine
                // next wakes: in a handler, with the server otherwise quiet, that is never (measured, DEB-2656). So an answer we do not
                // stream is read to its end first (a redirect's is empty; a refusal's is bounded) and the call is complete when we leave.
                if (!(resp.status.isSuccess() && isRelayableAudio(resp.headers[HttpHeaders.ContentType]))) {
                    runCatching { resp.bodyAsChannel().discard(RADIO_DISCARD_MAX_BYTES) }
                }
                when {
                    resp.status.value in 300..399 ->
                        resp.headers[HttpHeaders.Location]
                            ?.let { URLBuilder(here).takeFrom(it).buildString() }
                            ?.takeIf { it.startsWith("http", ignoreCase = true) }
                            ?.let<String, RelayStep> { if (hop == RADIO_MAX_REDIRECTS) refuse(call, "too many redirects") else RelayStep.Redirect(it) }
                            ?: refuse(call, "a redirect with no usable Location")
                    !resp.status.isSuccess() -> refuse(call, "upstream answered ${resp.status.value}")
                    else -> {
                        val type = resp.headers[HttpHeaders.ContentType]
                        if (!isRelayableAudio(type)) {
                            refuse(call, "upstream type is not audio")
                        } else {
                            committed = true
                            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                            call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
                            val source = resp.bodyAsChannel()
                            call.respondBytesWriter(ContentType.parse(type!!), HttpStatusCode.OK) {
                                copyWhileLive(source, this, stillLive)
                            }
                            RelayStep.Done
                        }
                    }
                }
            }
            when (step) {
                is RelayStep.Done -> return
                is RelayStep.Redirect -> url = step.url
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Before the first byte the caller still gets an answer; after it the connection simply ends — the player re-tunes.
        // The cause is not logged with its message: a libcurl error text can quote the URL.
        Logger.info("Radio relay: upstream failed (${e::class.simpleName})", "livetv")
        if (!committed) runCatching { call.respond(HttpStatusCode.BadGateway, mapOf("error" to "the station could not be relayed")) }
    }
}

private suspend fun copyWhileLive(source: ByteReadChannel, sink: io.ktor.utils.io.ByteWriteChannel, stillLive: () -> Boolean) {
    val buf = ByteArray(RADIO_CHUNK_BYTES)
    while (true) {
        val n = source.readAvailable(buf, 0, buf.size)
        if (n < 0) return
        if (n == 0) continue
        sink.writeFully(buf, 0, n)
        sink.flush()
        if (!stillLive()) return
    }
}
