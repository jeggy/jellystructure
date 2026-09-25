package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.CastLoadData
import dev.jellystructure.shared.tv.CastCommand
import dev.jellystructure.shared.tv.CastTrack
import dev.jellystructure.shared.tv.RemoteDevice
import dev.jellystructure.shared.tv.ScreenStatus
import dev.jellystructure.shared.tv.ScreenTrack
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * R265 (FR-R265-6) — a [CastSender] backed by 236's `/api/remote` routes, for a receiver-only screen
 * (a Tizen TV, R264) rather than a Chromecast. commonMain, so any platform's phone can drive a screen
 * with no platform SDK involved — [rememberCastSender] composes this with the platform's own Chromecast
 * sender (Android only) into one status, applying the "at most one linked at a time" rule (dev review
 * item 4: starting a screen while a Chromecast session is live stops the Chromecast session first, and
 * the reverse).
 *
 * Unlike the Chromecast sender there is no hand-off code — a screen is already paired (FR-R265-5) — so
 * [link] to it by its [RemoteDevice] once known and call [playItem] directly. [load] exists only to
 * satisfy the [CastSender] interface (built around Chromecast's [CastLoadData] hand-off); [CastController]
 * routes a screen row to [playItem] instead of through it.
 */
class ScreenSender(private val api: TvApiClient) : CastSender {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var socketJob: Job? = null
    private var deviceId: String? = null

    private val _link = MutableStateFlow(CastLinkState.NONE)
    override val link: StateFlow<CastLinkState> = _link.asStateFlow()
    private val _deviceName = MutableStateFlow<String?>(null)
    override val deviceName: StateFlow<String?> = _deviceName.asStateFlow()
    private val _status = MutableStateFlow<CastRemoteStatus?>(null)
    override val status: StateFlow<CastRemoteStatus?> = _status.asStateFlow()

    /** FR-R265-6/7 — link to an already-paired screen: a tapped sheet row, or a reconnect on app start
     *  that found a `now_playing` on this device's [RemoteDevice] (FR-R265-7's two outcomes). */
    fun link(device: RemoteDevice) {
        socketJob?.cancel()
        deviceId = device.deviceId
        _deviceName.value = device.name
        _link.value = CastLinkState.CONNECTING
        _status.value = device.nowPlaying?.let(::toRemoteStatus)
        socketJob = scope.launch { runSocket(device.deviceId) }
    }

    /** FR-R265-6 — starts a title on the linked screen. Not part of [CastSender] (that shape is
     *  Chromecast's hand-off-code [load]) — [CastController] calls this for a screen row directly. */
    fun playItem(itemId: String, startPositionMs: Long = 0) {
        val id = deviceId ?: return
        scope.launch { runCatching { api.remotePlay(id, itemId, startPositionMs) } }
    }

    private suspend fun runSocket(id: String) {
        val backoff = ReconnectBackoff()   // R293 (FR-R293-4, dev review item 3) — the same rule as /api/tv/events
        while (deviceId == id) {
            var openedAt: kotlin.time.Instant? = null
            runCatching {
                api.connectRemoteEvents(
                    deviceId = id,
                    onOpen = { openedAt = Clock.System.now(); if (deviceId == id) _link.value = CastLinkState.CONNECTED },
                    onStatus = { s -> if (deviceId == id) _status.value = toRemoteStatus(s) },
                )
            }
            if (deviceId != id) return
            _link.value = CastLinkState.RECONNECTING
            val heldOpenMs = openedAt?.let { (Clock.System.now() - it).inWholeMilliseconds } ?: 0L
            delay(backoff.next(heldOpenMs))
        }
    }

    /** Ends the link locally (the screen itself keeps playing — this only stops THIS phone watching it;
     *  [stop] is the explicit "end the session on the screen" action, FR-R245-10's rule applied here). */
    fun unlink() {
        socketJob?.cancel(); socketJob = null
        deviceId = null
        _link.value = CastLinkState.NONE
        _deviceName.value = null
        _status.value = null
    }

    override fun setAppId(appId: String) {} // Chromecast-only; a screen has no per-installation app id.

    override fun load(data: CastLoadData) {
        // A screen never starts this way (see the class doc) — CastController.playScreen() calls
        // playItem() directly instead of routing a screen row through this Chromecast-shaped method.
    }

    override fun play() { deviceId?.let { id -> scope.launch { runCatching { api.remoteCommand(id, "unpause") } } } }
    override fun pause() { deviceId?.let { id -> scope.launch { runCatching { api.remoteCommand(id, "pause") } } } }
    override fun seekTo(positionMs: Long) { deviceId?.let { id -> scope.launch { runCatching { api.remoteCommand(id, "seek", positionMs = positionMs) } } } }
    override fun stop() {
        deviceId?.let { id -> scope.launch { runCatching { api.remoteCommand(id, "stop") } } }
        unlink()
    }
    override fun selectSubtitle(trackId: Long?) { deviceId?.let { id -> scope.launch { runCatching { api.remoteCommand(id, "set_subtitle", index = trackId?.toInt() ?: -1) } } } }
    override fun selectAudio(trackId: Long?) { deviceId?.let { id -> scope.launch { runCatching { api.remoteCommand(id, "set_audio", index = trackId?.toInt()) } } } }

    /** [CastController.command] sends the small fixed [CastCommand] set the common remote UI already
     *  uses (next/nextup_cancel/nextup_play/subsize/status) — translated to the matching
     *  `/api/remote/command` name. "status" needs nothing sent: a screen answers every command with its
     *  own next status push regardless (FR-236-11), which is what refreshes the remote either way. */
    override fun send(json: String) {
        val id = deviceId ?: return
        val cmd = runCatching { screenCommandJson.decodeFromString(CastCommand.serializer(), json) }.getOrNull() ?: return
        scope.launch {
            runCatching {
                when (cmd.type) {
                    "next", "nextup_play" -> api.remoteCommand(id, "next")
                    "nextup_cancel" -> api.remoteCommand(id, "cancel_next_up")
                    "subsize" -> api.remoteCommand(id, "set_subtitle_size", size = cmd.size)
                    else -> Unit
                }
            }
        }
    }

    private companion object {
        val screenCommandJson = Json { ignoreUnknownKeys = true }
    }
}

private fun toRemoteStatus(s: ScreenStatus): CastRemoteStatus = CastRemoteStatus(
    itemId = s.itemId, title = s.title, kicker = s.kicker, artUrl = s.artUrl,
    positionMs = s.positionMs, durationMs = s.durationMs, playing = s.playing, buffering = s.buffering,
    loaded = s.loaded, ended = s.ended, hasNext = s.hasNext, nextUpSecs = s.nextUpSecs, nextTitle = s.nextTitle,
    busyRetryAfter = s.busyRetryAfter, busySinceMs = s.busySinceMs, noServer = s.noServer,
    audioTracks = s.audioTracks.map { it.toCastTrack() }, subtitleTracks = s.subtitleTracks.map { it.toCastTrack() },
    selectedAudio = s.selectedAudio, selectedSub = s.selectedSub, subSize = s.subSize.firstOrNull() ?: 'M',
    receiverId = null, transcoding = s.transcoding,
)

// A screen's own tracks carry no trackId (they're selected by plain index, FR-236-3) — the index doubles
// as CastRemoteStatus's Long trackId so the shared remote UI's selectAudio/selectSubtitle round-trip it
// straight back to an index in ScreenSender's own overrides above.
private fun ScreenTrack.toCastTrack() = CastTrack(index = index, label = label, language = language, forced = forced, isDefault = isDefault, trackId = index.toLong())
