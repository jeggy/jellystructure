package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.seams.LiveFallbackAction
import dev.jellystructure.ravilo.ui.seams.LiveFallbackPolicy
import dev.jellystructure.ravilo.ui.seams.supportedAudioCodecs
import dev.jellystructure.ravilo.ui.seams.supportsHevcOverHls
import dev.jellystructure.ravilo.ui.seams.platformAudioDecoders
import dev.jellystructure.ravilo.ui.components.LoadErrorKind
import dev.jellystructure.ravilo.ui.seams.detectDecoderLimits
import dev.jellystructure.ravilo.ui.seams.detectHdrSupport
import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.shared.tv.LiveTvChannel
import dev.jellystructure.shared.tv.LiveTvHeartbeatRequest
import dev.jellystructure.shared.tv.LiveTvStreamTicket
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed class LiveTvPlayerState {
    data object Loading : LiveTvPlayerState()
    data class Ready(val channel: LiveTvChannel, val ticket: LiveTvStreamTicket) : LiveTvPlayerState()
    data class Error(val message: String, val kind: LoadErrorKind = LoadErrorKind.GENERIC) : LiveTvPlayerState()
}

private const val HEARTBEAT_INTERVAL_MS = 20_000L

/** DEB-2627 (FR-2/FR-4) — `LiveTvTuneRequest.via`: Jellyfin's own remux of the channel instead of the source. */
const val VIA_JELLYFIN = "jellyfin"

/** DEB-2627 (FR-5) — the heartbeat's `delivery`: the broadcaster's own master, or what Jellyfin serves. */
private const val DELIVERY_SOURCE = "source"
private const val DELIVERY_JELLYFIN = "jellyfin"

/**
 * Phase R177 — a deliberate SIBLING to [PlayerStore], not a shared/branched path: live channels have
 * no resume position and Jellyfin's Live TV requires an explicit open (tune)/close (stop) handshake
 * that the finite-item VOD flow doesn't model (dev-review addendum D, Phase 147). Reuses the same
 * [dev.jellystructure.ravilo.ui.seams.RaviloPlayer]/[dev.jellystructure.ravilo.ui.seams.PlayerVideoSurface]
 * byte-streaming engine as VOD — only the control plane here is new.
 */
class LiveTvPlayerStore(private val apiClient: TvApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<LiveTvPlayerState>(LiveTvPlayerState.Loading)
    val state: StateFlow<LiveTvPlayerState> = _state.asStateFlow()

    // The ordered, shown+available lineup — loaded once, reused for zap (channel up/down) and
    // number-entry without a round-trip per keypress.
    private var channels: List<LiveTvChannel> = emptyList()
    val channelList: List<LiveTvChannel> get() = channels

    private var heartbeatJob: Job? = null
    private var openLiveStreamId: String? = null

    /** DEB-2627 (FR-4) — this tune's fallback decision; null until a ticket arrives. A zap or any new tune builds a new one. */
    private var fallback: LiveFallbackPolicy? = null
    private var currentChannelId: String? = null

    /** DEB-2627 (FR-5) — the delivery of the ticket on screen (`source` or `jellyfin`), which the heartbeat reports. */
    private var delivery: String = DELIVERY_SOURCE

    /**
     * DEB-2627 (FR-5) — the rung on screen now, read from the player when a heartbeat goes out: its `BANDWIDTH` (bits/s)
     * and picture height. The screen sets it; a player that cannot tell answers nulls and the heartbeat carries no rung.
     */
    var rungProvider: () -> Pair<Long?, Int?> = { null to null }

    private fun capabilities(): ClientCapabilities {
        val hdr = detectHdrSupport()
        val avc = detectDecoderLimits() // R183 — declare an honest, decodable transcode target.
        return ClientCapabilities(
            containers = listOf("ts", "mp4"),
            videoCodecs = listOf("h264", "hevc"),
            platformAudioDecoders = platformAudioDecoders(), // R379 — a channel's AC-3 is converted on a device without one
            audioCodecs = supportedAudioCodecs(), // R284 (FR-R284-7) — one list, the same as VOD; was a literal
            maxAudioChannels = 8,
            hlsOnly = true, // live channels are always HLS (IsInfiniteStream — Phase 147 addendum D)
            supportsHdr10 = hdr.hdr10,
            supportsHlg = hdr.hlg,
            supportsDolbyVision = hdr.dolbyVision,
            supportsDolbyVisionEl = hdr.dolbyVisionEl,
            maxH264Width = avc.maxWidth,
            maxH264Height = avc.maxHeight,
            maxH264Level = avc.maxLevel,
        )
    }

    suspend fun ensureChannelList(): List<LiveTvChannel> {
        if (channels.isEmpty()) channels = runCatching { apiClient.getLiveTvChannels() }.getOrDefault(emptyList())
        return channels
    }

    /** Tunes [channelId] — closes whatever stream was previously open first (a bare zap/number-entry
     *  re-tune, not a user-initiated stop, so this does NOT go through [stop]'s Idle transition).
     *  DEB-2627 (FR-4) — [via] is only ever [VIA_JELLYFIN], from [fallBackToJellyfin]: a zap or re-tune starts from the source. */
    fun tune(channelId: String, via: String? = null) {
        heartbeatJob?.cancel()
        fallback = null
        currentChannelId = channelId
        _state.value = LiveTvPlayerState.Loading
        scope.launch {
            openLiveStreamId?.let { runCatching { apiClient.stopLiveTv(it) } }
            openLiveStreamId = null
            ensureChannelList()
            val channel = channels.firstOrNull { it.channelId == channelId }
            if (channel == null) {
                // R280 (FR-R280-2) — this one knows its own cause outright; no throwable to classify.
                _state.value = LiveTvPlayerState.Error("channel not found", LoadErrorKind.GONE)
                return@launch
            }
            val ticket = runCatching { apiClient.tuneLiveTv(channelId, capabilities(), via) }.getOrNull()
            if (ticket == null) {
                _state.value = LiveTvPlayerState.Error("livetv.tune_failed")
                return@launch
            }
            openLiveStreamId = ticket.liveStreamId
            fallback = LiveFallbackPolicy(ticket.streamKind, viaJellyfin = via == VIA_JELLYFIN)
            // The ticket's own `via`: Jellyfin's remux, or a path on Jellyfin itself, is Jellyfin's; anything else is the broadcaster's.
            delivery = if (via == VIA_JELLYFIN || ticket.hlsUrl.startsWith(ticket.jellyfinBaseUrl)) DELIVERY_JELLYFIN else DELIVERY_SOURCE
            _state.value = LiveTvPlayerState.Ready(channel, ticket)
            startHeartbeat()
        }
    }

    /** DEB-2627 (FR-4) — the player drew a frame of this tune. */
    fun onFirstFrame() { fallback?.onFirstFrame() }

    /** Whether the screen should time this tune's first frame (only the source is). */
    val timesFirstFrame: Boolean get() = fallback?.timesFirstFrame == true

    /** DEB-2627 (FR-4) — the source gave no first frame within [LiveFallbackPolicy.NO_FIRST_FRAME_MS]. */
    fun onNoFirstFrame(): LiveFallbackAction = act(fallback?.onNoFirstFrame() ?: LiveFallbackAction.IGNORE)

    /** DEB-2627 (FR-4) — the player raised a fatal error. [LiveFallbackAction.RETRY_SOURCE] is the screen's to carry out (it reloads the same ticket). */
    fun onPlayerFatal(): LiveFallbackAction = act(fallback?.onFatal() ?: LiveFallbackAction.SHOW_ERROR)

    private fun act(action: LiveFallbackAction): LiveFallbackAction {
        when (action) {
            LiveFallbackAction.FALL_BACK -> currentChannelId?.let { tune(it, via = VIA_JELLYFIN) }
            LiveFallbackAction.SHOW_ERROR -> _state.value = LiveTvPlayerState.Error("livetv.tune_failed")
            else -> Unit
        }
        return action
    }

    /** Zaps to the adjacent channel in lineup order ([direction] = +1/-1), wrapping around. */
    fun zap(direction: Int) {
        val cur = (_state.value as? LiveTvPlayerState.Ready)?.channel ?: return
        if (channels.isEmpty()) return
        val idx = channels.indexOfFirst { it.channelId == cur.channelId }
        if (idx < 0) return
        val next = ((idx + direction) % channels.size + channels.size) % channels.size
        tune(channels[next].channelId)
    }

    /** Number-entry tune-by-channel-number; returns false (no-op) if no channel has that number. */
    fun tuneByNumber(number: Int): Boolean {
        val target = channels.firstOrNull { it.number == number } ?: return false
        tune(target.channelId)
        return true
    }

    private fun startHeartbeat() {
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                runCatching { apiClient.liveTvHeartbeat(heartbeatReport()) }
            }
        }
    }

    /** DEB-2627 (FR-5) — the stream, the rung and the delivery, so the backend can re-register a stream it lost to a restart. */
    internal fun heartbeatReport(): LiveTvHeartbeatRequest? {
        val streamId = openLiveStreamId ?: return null
        val (bps, height) = runCatching { rungProvider() }.getOrDefault(null to null)
        return LiveTvHeartbeatRequest(
            channelId = currentChannelId, liveStreamId = streamId,
            variantBandwidthBps = bps?.takeIf { it > 0 }, height = height?.takeIf { it > 0 }, delivery = delivery,
        )
    }

    /** Explicit close — the player screen calls this on exit (its `DisposableEffect`'s onDispose). */
    fun stop() {
        heartbeatJob?.cancel(); heartbeatJob = null
        val id = openLiveStreamId ?: return
        openLiveStreamId = null
        scope.launch { runCatching { apiClient.stopLiveTv(id) } }
    }
}
