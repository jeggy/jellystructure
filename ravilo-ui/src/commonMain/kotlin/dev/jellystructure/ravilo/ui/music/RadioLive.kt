package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.ravilo.ui.screens.VIA_JELLYFIN
import dev.jellystructure.ravilo.ui.screens.radioPlayUrl
import dev.jellystructure.ravilo.ui.seams.LiveFallbackAction
import dev.jellystructure.ravilo.ui.seams.LiveFallbackPolicy
import dev.jellystructure.ravilo.ui.seams.STREAM_KIND_AUDIO
import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.shared.tv.LiveTvChannel
import dev.jellystructure.shared.tv.LiveTvHeartbeatRequest
import dev.jellystructure.shared.tv.LiveTvProgramInfo
import dev.jellystructure.shared.tv.LiveTvStreamTicket
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/*
 * DEB-2659 (DEB-2628 FR-10) — a radio station as a live item in the music engine. Everything here is engine-neutral: the
 * station as the screens and the system controls read it ([RadioLive]), and the control plane of a tune ([RadioTuner]:
 * tune, heartbeat, programme turnover, the one fallback, stop). The platform engines own only the player.
 */

/**
 * The station the engine is on, and the programme now. The title is the programme and the artist line the station; with no
 * guide the title is the station's name and the second line reads *Live radio* (FR-10).
 */
data class RadioLive(val station: LiveTvChannel, val programme: LiveTvProgramInfo? = station.currentProgram) {
    val title: String get() = programme?.name ?: station.name
    /** The cover is the station's logo. */
    val coverUrl: String? get() = station.logoUrl
    /** The artist line: the station when a programme is the title, else [liveRadio] (the localised *Live radio*). */
    fun subtitle(liveRadio: String): String = if (programme != null) station.name else liveRadio
}

/** The localised *Live radio* for the system controls, which are drawn outside Compose and so have no `str()`. */
fun liveRadioLabel(): String = dev.jellystructure.ravilo.ui.i18n.t("radio.live_radio", dev.jellystructure.ravilo.i18n.LastLanguage.read() ?: "en")

/** What a player is handed for one tune: the URL (absolute), and for the relay the upstream's audio type. */
data class RadioStream(val url: String, val contentType: String?, val hls: Boolean)

/** The four calls a tune makes. [TvApiClient] is the real one; a test hands in a fake. */
interface RadioApi {
    suspend fun tune(channelId: String, capabilities: ClientCapabilities, via: String?): LiveTvStreamTicket
    suspend fun heartbeat(report: LiveTvHeartbeatRequest)
    suspend fun stop(liveStreamId: String)
    /** The stations alone, in lineup order, each with the programme now (`/tv/livetv/channels?kind=radio&radio=1`). */
    suspend fun stations(): List<LiveTvChannel>
}

fun TvApiClient.asRadioApi(): RadioApi = object : RadioApi {
    override suspend fun tune(channelId: String, capabilities: ClientCapabilities, via: String?) = tuneLiveTv(channelId, capabilities, via)
    override suspend fun heartbeat(report: LiveTvHeartbeatRequest) = liveTvHeartbeat(report)
    override suspend fun stop(liveStreamId: String) = stopLiveTv(liveStreamId)
    override suspend fun stations() = getLiveTvChannels(kind = "radio")
}

/** The engine's reactions to a tune. All three arrive on the tuner's scope (the engines' main thread). */
interface RadioListener {
    /** Play this: a first load, a re-load of the same stream after a retry, or the fallback's stream. */
    fun onLoad(stream: RadioStream)
    /** The programme turned over (or the guide filled in): the title, the cards and the system controls follow. */
    fun onChanged(radio: RadioLive)
    /** No tune could be made, or the stream could not play: the engine's *couldn't play* state, with the station's name. */
    fun onFailed()
}

/** FR-10 — how long until the engine re-reads the station: the programme's end, 60 s while a guide has no programme now, never with no guide. */
fun radioRefreshDelayMs(radio: RadioLive, nowMs: Long): Long? {
    val end = radio.programme?.endMs
    return when {
        end != null && end > nowMs -> (end - nowMs + RadioTuner.TURNOVER_SLACK_MS).coerceAtLeast(RadioTuner.MIN_REFRESH_MS)
        radio.station.hasGuide -> RadioTuner.GUIDE_GAP_REFRESH_MS
        else -> null
    }
}

/** The heartbeat's `delivery`: Jellyfin's own transcoding after the fallback, the source (here: the relay) otherwise. */
private fun deliveryOf(via: String?, ticket: LiveTvStreamTicket): String =
    if (via == VIA_JELLYFIN || ticket.hlsUrl.startsWith(ticket.jellyfinBaseUrl)) "jellyfin" else "source"

/**
 * FR-10 — one station at a time. [start] tunes (closing the tune it replaces) and hands the stream to [listener]; from then
 * until [stop] or [endTune] the tuner sends the engine's heartbeat every [heartbeatMs] (so a locked phone keeps it going — it
 * runs on the engine's scope, not a screen's), re-reads the programme at its end, and times the first audio for the one
 * fallback (DEB-2627 FR-4, slice 4's rule). [stop] and [endTune] send `/tv/livetv/stop`.
 *
 * [closing] runs a stop so that it outlives [scope] — the engines pass their teardown scope.
 */
class RadioTuner(
    private val scope: CoroutineScope,
    private val api: () -> RadioApi?,
    private val capabilities: () -> ClientCapabilities,
    private val baseUrl: () -> String,
    private val listener: RadioListener,
    private val closing: (suspend () -> Unit) -> Unit = { work -> scope.launch { work() } },
    private val nowMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    private val heartbeatMs: Long = HEARTBEAT_MS,
    private val noAudioMs: Long = LiveFallbackPolicy.NO_FIRST_FRAME_MS,
) {
    /** The station the engine is on — kept while paused, failed or stopped-by-pause so *play* can re-tune; null after [stop]. */
    var radio: RadioLive? = null
        private set

    /** A tune is open or being opened: false when paused, failed or stopped. This is *playing*, as far as the viewer wants it. */
    var listening: Boolean = false
        private set

    /** The stream has been handed to the player and not yet reported anything: the player is still buffering. */
    var loading: Boolean = false
        private set

    private var liveStreamId: String? = null
    private var delivery = "source"
    private var policy: LiveFallbackPolicy? = null
    private var stream: RadioStream? = null
    private var tuneJob: Job? = null
    private var heartbeatJob: Job? = null
    private var refreshJob: Job? = null
    private var clockJob: Job? = null

    /** Starts [station] — the first tune of a station, or a re-tune at the live edge (*play* after a pause). */
    fun start(station: LiveTvChannel) {
        val keep = radio?.takeIf { it.station.channelId == station.channelId }
        radio = RadioLive(station, keep?.programme ?: station.currentProgram)
        tune(via = null)
    }

    /** *Play* on a paused or failed station: re-tune it. */
    fun retune() { radio?.let { tune(via = null) } }

    private fun tune(via: String?) {
        val r = radio ?: return
        releaseTune()
        listening = true; loading = true
        tuneJob = scope.launch {
            val a = api()
            val ticket = if (a == null) null else runCatching { a.tune(r.station.channelId, capabilities(), via) }.getOrNull()
            if (!isActive) return@launch   // replaced by a newer tune — cancelled, not failed
            if (ticket == null) { fail(); return@launch }
            liveStreamId = ticket.liveStreamId
            delivery = deliveryOf(via, ticket)
            policy = LiveFallbackPolicy(ticket.streamKind, viaJellyfin = via == VIA_JELLYFIN)
            val s = RadioStream(radioPlayUrl(ticket, baseUrl()), ticket.contentType, hls = ticket.streamKind != STREAM_KIND_AUDIO)
            stream = s
            listener.onLoad(s)
            startHeartbeat(r.station.channelId)
            startRefresh()
            startClock()
        }
    }

    /** The player is past buffering with a position off zero: the station is heard, so the 15 s clock stops. */
    fun heard() {
        if (!loading) return
        loading = false
        policy?.onFirstFrame()
        clockJob?.cancel(); clockJob = null
    }

    /** The player raised a fatal error on the current load. */
    fun onPlayerError() { act(policy?.onFatal() ?: LiveFallbackAction.SHOW_ERROR) }

    private fun act(action: LiveFallbackAction) {
        when (action) {
            LiveFallbackAction.FALL_BACK -> tune(via = VIA_JELLYFIN)
            LiveFallbackAction.SHOW_ERROR -> fail()
            LiveFallbackAction.RETRY_SOURCE -> stream?.let { loading = true; listener.onLoad(it); startClock() }
            LiveFallbackAction.IGNORE -> Unit
        }
    }

    private fun fail() {
        releaseTune()
        listener.onFailed()
    }

    private fun startClock() {
        clockJob?.cancel()
        if (policy?.timesFirstFrame != true) return
        clockJob = scope.launch { delay(noAudioMs); act(policy?.onNoFirstFrame() ?: LiveFallbackAction.IGNORE) }
    }

    private fun startHeartbeat(channelId: String) {
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(heartbeatMs)
                val id = liveStreamId ?: return@launch
                runCatching { api()?.heartbeat(LiveTvHeartbeatRequest(channelId = channelId, liveStreamId = id, delivery = delivery)) }
            }
        }
    }

    private fun startRefresh() {
        refreshJob = scope.launch {
            while (isActive) {
                val wait = radio?.let { radioRefreshDelayMs(it, nowMs()) } ?: return@launch
                delay(wait)
                val id = radio?.station?.channelId ?: return@launch
                val fresh = runCatching { api()?.stations() }.getOrNull()?.firstOrNull { it.channelId == id } ?: continue
                if (!isActive) return@launch
                val next = RadioLive(fresh, fresh.currentProgram)
                if (next != radio) { radio = next; listener.onChanged(next) }
            }
        }
    }

    /** Everything a tune owns goes, and the open stream is closed on the server. The station stays. */
    private fun releaseTune() {
        tuneJob?.cancel(); heartbeatJob?.cancel(); refreshJob?.cancel(); clockJob?.cancel()
        tuneJob = null; heartbeatJob = null; refreshJob = null; clockJob = null
        listening = false; loading = false; policy = null; stream = null
        val id = liveStreamId ?: return
        liveStreamId = null
        closing { runCatching { api()?.stop(id) } }
    }

    /** The process is ending: the open stream is closed on the server now, on the caller's coroutine, with nothing left to run it later. */
    suspend fun closeOpenStream() {
        val id = liveStreamId ?: return
        liveStreamId = null
        runCatching { api()?.stop(id) }
    }

    /** *Pause*: a station cannot be paused, it is stopped — the tune closes, the station stays, *play* re-tunes. */
    fun endTune() { releaseTune() }

    /** Another kind of item took the engine, or the viewer closed the bar: the tune closes and the station goes. */
    fun stop() { releaseTune(); radio = null }

    companion object {
        /** FR-10 — the engine's heartbeat, DEB-2627 FR-5's body, every 20 s. */
        const val HEARTBEAT_MS = 20_000L
        /** A hair after the programme's end, so the re-read lands on the next one. */
        const val TURNOVER_SLACK_MS = 1_000L
        /** A stale answer (the same programme again) never makes this loop spin. */
        const val MIN_REFRESH_MS = 15_000L
        const val GUIDE_GAP_REFRESH_MS = 60_000L
    }
}
