package dev.jellystructure.shared.tv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ─── DEB-2660 (DEB-2628 FR-11) — a radio station on a speaker, the receiver's side ──────────────────────────────────
//
// The Chromecast receiver (`ravilo-cast`) tunes a station itself, as its own enrolled device, and plays the relay as a
// live item. What it decides — what the station says, what the speaker may be asked, which URL and type it loads, when the
// tune opens and closes — is plain code here so it is tested in CI (`ravilo-cast` has no JS test runner there); the receiver
// is glue over it.

/** The CAF commands a station offers a sender: pause and volume only — never `SEEK`, never a `QUEUE_*`. */
val CAST_RADIO_COMMANDS: List<String> = listOf("PAUSE", "STREAM_VOLUME", "STREAM_MUTE")

/** The commands a song offers, for the receiver to restore when a song replaces a station. */
val CAST_MUSIC_COMMANDS: List<String> = listOf("PAUSE", "SEEK", "STREAM_VOLUME", "STREAM_MUTE", "QUEUE_NEXT", "QUEUE_PREV")

/** What a station says on a speaker's display and in the Home app: the programme as the title, the station as the artist, the logo as the image. */
data class CastRadioMeta(val title: String, val artist: String, val imageUrl: String?)

/** With no programme the title is the station's name (FR-10's rule, which the speaker's display follows). */
fun castRadioMeta(radio: CastRadioItem, absolute: (String?) -> String? = { it }): CastRadioMeta =
    CastRadioMeta(
        title = radio.programme?.takeIf { it.isNotBlank() } ?: radio.name,
        artist = radio.name,
        imageUrl = absolute(radio.logoUrl),
    )

/** The relay's path is relative (`/api/tv/livetv/radio/{relay_id}`) and resolved against the server; any other ticket's URL is used as it is. */
fun castRadioUrl(ticket: LiveTvStreamTicket, serverUrl: String): String =
    if (ticket.streamKind == "audio" && ticket.hlsUrl.startsWith("/") && serverUrl.isNotBlank()) serverUrl.trimEnd('/') + ticket.hlsUrl
    else ticket.hlsUrl

/**
 * The type the receiver loads the stream with: the ticket's `content_type` (the upstream's audio type), else — when the
 * backend could not read one — HLS for a playlist and MP3 for the rest, the type radio most often is.
 */
fun castRadioContentType(ticket: LiveTvStreamTicket, url: String): String =
    ticket.contentType?.takeIf { it.isNotBlank() } ?: if (url.substringBefore('?').endsWith(".m3u8")) "application/x-mpegURL" else "audio/mpeg"

/** What the receiver hands its player for one tune. */
data class CastRadioStream(val url: String, val contentType: String, val liveStreamId: String)

/**
 * A station's `status`: the programme (or the station's name) as the title, the station as the kicker, the logo as the art,
 * `radio = true`, and no queue. [artUrl] is the absolute logo.
 */
fun castRadioStatus(radio: CastRadioItem, artUrl: String?, playing: Boolean, receiverId: String?, headless: Boolean?): CastReceiverMessage {
    val meta = castRadioMeta(radio)
    return CastReceiverMessage(
        type = "status", itemId = radio.channelId, title = meta.title, kicker = meta.artist, artUrl = artUrl,
        receiverId = receiverId, headless = headless, playing = playing, radio = true,
    )
}

/**
 * A finished station that was not stopped is the upstream dropping a listener that paused (the relay is a pipe): the receiver
 * tunes it again at the live edge — [CAST_RADIO_RETUNES] times in [CAST_RADIO_RETUNE_WINDOW_MS], then it reports `failed`.
 * [earlier] holds the times (ms) of the tunes made for this station by a finish; [nowMs] is now.
 */
const val CAST_RADIO_RETUNES = 3
const val CAST_RADIO_RETUNE_WINDOW_MS = 60_000L
fun castRadioMayRetune(earlier: List<Long>, nowMs: Long): Boolean =
    earlier.count { nowMs - it in 0 until CAST_RADIO_RETUNE_WINDOW_MS } < CAST_RADIO_RETUNES

/** The three calls a tune makes. [TvApiClient] is the real one; a test hands in a fake. */
interface CastRadioApi {
    suspend fun tune(channelId: String, capabilities: ClientCapabilities): LiveTvStreamTicket
    suspend fun heartbeat(report: LiveTvHeartbeatRequest)
    suspend fun stop(liveStreamId: String)
}

fun TvApiClient.asCastRadioApi(): CastRadioApi = object : CastRadioApi {
    override suspend fun tune(channelId: String, capabilities: ClientCapabilities) = tuneLiveTv(channelId, capabilities)
    override suspend fun heartbeat(report: LiveTvHeartbeatRequest) = liveTvHeartbeat(report)
    override suspend fun stop(liveStreamId: String) = stopLiveTv(liveStreamId)
}

/**
 * The receiver's one tune. [start] closes the tune it replaces (and waits for that stop), tunes the station as this device and
 * hands back what to load; from then until [stop] the tune is heartbeaten every [heartbeatMs] with DEB-2627 FR-5's body.
 * [stop] closes it on the server and is safe to call again (a stop on a stop, an idle after a stop, a song load after either).
 */
class CastRadioTune(
    private val scope: CoroutineScope,
    private val api: () -> CastRadioApi?,
    private val capabilities: () -> ClientCapabilities,
    private val serverUrl: () -> String,
    private val heartbeatMs: Long = HEARTBEAT_MS,
) {
    /** The station tuned, or null when no tune is open. */
    var radio: CastRadioItem? = null
        private set
    val open: Boolean get() = liveStreamId != null

    private var liveStreamId: String? = null
    private var heartbeat: Job? = null

    /** Null when no tune could be made: nothing is open then, and the receiver reports `failed`. */
    suspend fun start(station: CastRadioItem): CastRadioStream? {
        stop()
        val a = api() ?: return null
        val ticket = runCatching { a.tune(station.channelId, capabilities()) }.getOrNull() ?: return null
        radio = station
        liveStreamId = ticket.liveStreamId
        heartbeat = scope.launch {
            while (isActive) {
                delay(heartbeatMs)
                val id = liveStreamId ?: return@launch
                runCatching { api()?.heartbeat(LiveTvHeartbeatRequest(channelId = station.channelId, liveStreamId = id, delivery = "source")) }
            }
        }
        val url = castRadioUrl(ticket, serverUrl())
        return CastRadioStream(url, castRadioContentType(ticket, url), ticket.liveStreamId)
    }

    /** The tune ends here and now: the heartbeat stops and nothing is open. The stream to close on the server is returned. */
    private fun detach(): String? {
        heartbeat?.cancel(); heartbeat = null
        val id = liveStreamId
        liveStreamId = null; radio = null
        return id
    }

    /** Closes the tune, if one is open: the heartbeat ends first, then the server hears the stop. */
    suspend fun stop() { detach()?.let { id -> runCatching { api()?.stop(id) } } }

    /**
     * The same as [stop] for a caller that cannot wait (the receiver's idle and stop handlers). The tune is detached at once, so a
     * station tuned straight after is never the one a late stop closes.
     */
    fun stopInBackground() { val id = detach() ?: return; scope.launch { runCatching { api()?.stop(id) } } }

    companion object {
        /** FR-10's heartbeat, the engine's: every 20 s. */
        const val HEARTBEAT_MS = 20_000L
    }
}
