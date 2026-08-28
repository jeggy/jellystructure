package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.seams.detectDecoderLimits
import dev.jellystructure.ravilo.ui.seams.detectHdrSupport
import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.shared.tv.LiveTvChannel
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
    data class Error(val message: String) : LiveTvPlayerState()
}

private const val HEARTBEAT_INTERVAL_MS = 20_000L

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

    private fun capabilities(): ClientCapabilities {
        val hdr = detectHdrSupport()
        val avc = detectDecoderLimits() // R183 — declare an honest, decodable transcode target.
        return ClientCapabilities(
            containers = listOf("ts", "mp4"),
            videoCodecs = listOf("h264", "hevc"),
            audioCodecs = listOf("aac", "ac3", "eac3", "mp3"),
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
     *  re-tune, not a user-initiated stop, so this does NOT go through [stop]'s Idle transition). */
    fun tune(channelId: String) {
        heartbeatJob?.cancel()
        _state.value = LiveTvPlayerState.Loading
        scope.launch {
            openLiveStreamId?.let { runCatching { apiClient.stopLiveTv(it) } }
            openLiveStreamId = null
            ensureChannelList()
            val channel = channels.firstOrNull { it.channelId == channelId }
            if (channel == null) {
                _state.value = LiveTvPlayerState.Error("Channel not found")
                return@launch
            }
            val ticket = runCatching { apiClient.tuneLiveTv(channelId, capabilities()) }.getOrNull()
            if (ticket == null) {
                _state.value = LiveTvPlayerState.Error("livetv.tune_failed")
                return@launch
            }
            openLiveStreamId = ticket.liveStreamId
            _state.value = LiveTvPlayerState.Ready(channel, ticket)
            startHeartbeat()
        }
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
                runCatching { apiClient.liveTvHeartbeat() }
            }
        }
    }

    /** Explicit close — the player screen calls this on exit (its `DisposableEffect`'s onDispose). */
    fun stop() {
        heartbeatJob?.cancel(); heartbeatJob = null
        val id = openLiveStreamId ?: return
        openLiveStreamId = null
        scope.launch { runCatching { apiClient.stopLiveTv(id) } }
    }
}
