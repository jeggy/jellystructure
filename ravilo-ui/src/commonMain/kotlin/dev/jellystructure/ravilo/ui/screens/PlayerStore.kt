package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.seams.detectAvcDecoderLimits
import dev.jellystructure.ravilo.ui.seams.detectHdrSupport
import dev.jellystructure.shared.tv.CardPlayState
import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.StreamTicket
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed class PlayerSessionState {
    data object Idle : PlayerSessionState()
    data object Loading : PlayerSessionState()
    data class Ready(val ticket: StreamTicket) : PlayerSessionState()
    data class Error(val message: String) : PlayerSessionState()
}

private const val PROGRESS_INTERVAL_MS = 10_000L

class PlayerStore(private val apiClient: TvApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Bug fix: the stop must outlive the store. `scope` is cancelled by close() the moment the owner
    // drops this store (e.g. an auto-advance to the next episode swaps in a new one), and a stop that
    // was launched into `scope` would be cancelled mid-flight — leaving the Jellyfin session "playing"
    // forever. Terminal writes (stop / markPlayed) therefore run on their own scope that close() never
    // touches: they are short, self-completing, fire-and-forget requests.
    private val exitScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<PlayerSessionState>(PlayerSessionState.Idle)
    val state: StateFlow<PlayerSessionState> = _state.asStateFlow()

    private var progressJob: Job? = null
    private var currentItemId: String? = null
    // Kept so close() can still report a final position (and take the same ≥90% mark-played decision)
    // if the owner tore the store down without calling stopSession() itself — which also makes the two
    // teardown paths order-independent: whichever runs first does the real stop, the other no-ops.
    private var positionProvider: (() -> Long)? = null
    private var durationProvider: (() -> Long)? = null

    fun startSession(
        itemId: String,
        positionProvider: () -> Long,
        isPausedProvider: () -> Boolean,
        durationProvider: () -> Long = { 0L },
    ) {
        currentItemId = itemId
        this.positionProvider = positionProvider
        this.durationProvider = durationProvider
        _state.value = PlayerSessionState.Loading
        scope.launch {
            // Bug fix: a failed startPlayback (e.g. a transient network blip during an auto-advance to
            // the next episode) used to surface as PlayerSessionState.Error with no code anywhere
            // reading that state — the player just sat frozen on the outgoing episode's last frame
            // forever, reported as "stuck" with "auto play next doesn't work". Retry with exponential
            // backoff — 1s, 2s, 4s, 8s — before giving up; shorter than HomeStore's 10-attempt policy
            // since this is mid-playback (the user is actively watching, not just browsing) — ~15s of
            // quiet retry survives a blip without leaving a spinner up for minutes. PlayerScreen now
            // also renders PlayerSessionState.Error with a manual Retry as the final fallback.
            var lastErr = "Failed to start playback"
            var delayMs = 1_000L
            repeat(5) { attempt ->
                val result = runCatching {
                    // Bug fix: this used to be a static literal with no HDR signal, so the server always
                    // assumed direct-play was safe even for HDR10/HLG sources the device might not be
                    // able to display correctly (see ClientCapabilities.supportsHdr10/supportsHlg docs).
                    val hdr = detectHdrSupport()
                    // R183: Dolby Vision + the real H.264 decode ceiling, so DV profile-8 titles
                    // direct-play and any fallback transcode is one this device can actually decode.
                    val avc = detectAvcDecoderLimits()
                    val ticket = apiClient.startPlayback(
                        itemId = itemId,
                        capabilities = ClientCapabilities(
                            containers = listOf("mkv", "mp4", "avi", "mov"),
                            videoCodecs = listOf("h264", "hevc", "vp9", "av1"),
                            audioCodecs = listOf("aac", "mp3", "flac", "opus", "ac3", "eac3"),
                            maxAudioChannels = 8,
                            supportsHdr10 = hdr.hdr10,
                            supportsHlg = hdr.hlg,
                            supportsDolbyVision = hdr.dolbyVision,
                            supportsDolbyVisionEl = hdr.dolbyVisionEl,
                            maxH264Width = avc.maxWidth,
                            maxH264Height = avc.maxHeight,
                            maxH264Level = avc.maxLevel,
                        ),
                    )
                    ticket
                }
                if (result.isSuccess) {
                    startHeartbeat(itemId, positionProvider, isPausedProvider)
                    _state.value = PlayerSessionState.Ready(result.getOrThrow())
                    return@launch
                }
                lastErr = result.exceptionOrNull()?.message ?: lastErr
                if (attempt < 4) {
                    delay(delayMs)
                    delayMs *= 2
                }
            }
            _state.value = PlayerSessionState.Error(lastErr)
        }
    }

    /** R56 — Re-stream with a PGS subtitle burned in; keeps the heartbeat running (same item). */
    fun restreamWithSub(itemId: String, subtitleStreamIndex: Int, positionMs: Long) {
        scope.launch {
            _state.value = PlayerSessionState.Loading
            _state.value = runCatching {
                PlayerSessionState.Ready(apiClient.restream(itemId, subtitleStreamIndex, positionMs))
            }.getOrElse { PlayerSessionState.Error(it.message ?: "Failed to restream") }
        }
    }

    /**
     * Idempotent: `currentItemId` is cleared up-front so a second call (a late lifecycle callback, or
     * close()'s own safety net below) can't post a duplicate stop — nor, worse, a stop carrying the
     * NEXT episode's playhead against this item's id.
     */
    fun stopSession(positionMs: Long, durationMs: Long = 0L) {
        progressJob?.cancel()
        progressJob = null
        val itemId = currentItemId ?: return
        currentItemId = null
        exitScope.launch {
            runCatching { apiClient.stopPlayback(itemId, positionMs) }
            // R142: finishing (≥90%) marks the item played so its tiles flip to ✓ and a series episode
            // advances up-next — no manual toggle. Below threshold it stays in-progress (resume preserved).
            if (durationMs > 0 && positionMs >= durationMs * 90 / 100) {
                runCatching { apiClient.markPlayed(itemId, watched = true) }
                WatchedBus.publish(mapOf(itemId to CardPlayState(played = true, playedPct = 1f)))  // R147
            }
        }
        _state.value = PlayerSessionState.Idle
    }

    /**
     * Bug fix: this store used to have no lifecycle at all — nothing ever cancelled [scope], so every
     * episode of a binge left a live store behind whose 10s heartbeat kept POSTing progress for its own
     * (already finished) item id, using the CURRENT episode's playhead. That showed up as several
     * concurrent "Now Playing" entries for one device in the Jellyfin dashboard, and it overwrote the
     * finished episodes' resume positions with nonsense — the wrong Continue Watching rows.
     *
     * Safe to call more than once, and safe to call before or after [stopSession]: if the session was
     * never stopped we report the final position ourselves (from the providers handed to [startSession],
     * so the ≥90% mark-played rule still applies) before the scope goes away.
     */
    fun close() {
        if (currentItemId != null) {
            stopSession(positionProvider?.invoke() ?: 0L, durationProvider?.invoke() ?: 0L)
        }
        progressJob?.cancel()
        progressJob = null
        positionProvider = null
        durationProvider = null
        scope.cancel()
        _state.value = PlayerSessionState.Idle
    }

    /** R182 — resolved per-viewer skip/autoplay behaviour for the player (skipIntro/skipCredits/
     *  skipSecs/autoplayNext), already viewer-resolved server-side (RaviloConfigService.getConfig).
     *  Suspends directly (unlike the other methods here, which fire-and-forget via scope.launch) so the
     *  caller's own LaunchedEffect can await it and update local state. */
    suspend fun getConfig(): RaviloConfig? = runCatching { apiClient.getConfig() }.getOrNull()

    /** R142: explicit played write-through used when advancing to the next episode (the finished one).
     *  Runs on [exitScope], not [scope]: its only caller (PlayerScreen.advanceNext) navigates away in the
     *  very next statement, which now closes this store — a request launched on `scope` would be cancelled
     *  before it ever reached the server, so the finished episode never got marked played. */
    fun markWatched(itemId: String) {
        exitScope.launch {
            runCatching { apiClient.markPlayed(itemId, watched = true) }
            WatchedBus.publish(mapOf(itemId to CardPlayState(played = true, playedPct = 1f)))  // R147
        }
    }

    private fun startHeartbeat(
        itemId: String,
        positionProvider: () -> Long,
        isPausedProvider: () -> Boolean,
    ) {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                runCatching {
                    apiClient.reportProgress(itemId, positionProvider(), isPausedProvider())
                }
            }
        }
    }
}
