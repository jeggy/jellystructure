package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.seams.PlayerQoeSnapshot
import dev.jellystructure.ravilo.ui.seams.detectDecoderLimits
import dev.jellystructure.ravilo.ui.seams.detectHdrSupport
import dev.jellystructure.ravilo.ui.seams.detectLinkState
import dev.jellystructure.ravilo.ui.seams.supportsEmbeddedTextSubtitles
import dev.jellystructure.shared.tv.CardPlayState
import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.shared.tv.PlaybackQoeReport
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
// R216 (FR-R216-4) — "a long-session interval" for QoE reporting so an abandoned/crashed session isn't
// lost entirely; 60 heartbeat ticks × PROGRESS_INTERVAL_MS = 10 minutes.
private const val QOE_REPORT_EVERY_N_TICKS = 60

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
    // R216 (FR-R216-4) — supplied by the caller (PlayerScreen owns the RaviloPlayer instance; the store
    // deliberately doesn't) so QoE reporting can reuse the exact same lambda-injection pattern as
    // position/duration above instead of coupling this store to a concrete player type.
    private var qoeSnapshotProvider: (() -> PlayerQoeSnapshot)? = null
    // Phase 185/R222 (FR-185-4 client half) — same lambda-injection shape as qoeSnapshotProvider: the
    // caller (PlayerScreen) owns the actual negotiation-to-first-frame timing, this store just reads the
    // result once at stop time. Null whenever the caller never measured one (no startSession call this
    // store's lifetime supplied it, or the measurement itself came back null — see the provider's own doc).
    private var startupMsProvider: (() -> Long?)? = null
    private var qoeLinkKind: String = "unknown"
    private var qoeLinkMbps: Int = 0
    private var qoeDirectPlay: Boolean = false
    private var qoeTicksSinceReport = 0

    fun startSession(
        itemId: String,
        positionProvider: () -> Long,
        isPausedProvider: () -> Boolean,
        durationProvider: () -> Long = { 0L },
        qoeSnapshotProvider: () -> PlayerQoeSnapshot = { PlayerQoeSnapshot() },
        startupMsProvider: () -> Long? = { null },
    ) {
        currentItemId = itemId
        this.positionProvider = positionProvider
        this.durationProvider = durationProvider
        this.qoeSnapshotProvider = qoeSnapshotProvider
        this.startupMsProvider = startupMsProvider
        qoeTicksSinceReport = 0
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
                    // R216 generalises this probe to also report the device's real decode-bitrate
                    // ceilings (see DecoderLimits' doc).
                    val decoderLimits = detectDecoderLimits()
                    // R216 (FR-R216-2) — this device's own network link, sampled once here (not
                    // continuously — see the phase's Out-of-scope section). Reused below for the QoE
                    // reports this same session posts, so it isn't re-sampled per report.
                    val link = detectLinkState()
                    // Phase 161: whether this client renders embedded text subs (SRT/ASS/SSA) natively
                    // in-container on a direct-played file, so the server can skip a redundant VTT
                    // sideload of the same stream (bug: it used to always sideload, double-delivering
                    // every text subtitle on a direct-played title — see PlaybackService.buildSubtracks).
                    val embeddedSubs = supportsEmbeddedTextSubtitles()
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
                            maxH264Width = decoderLimits.maxWidth,
                            maxH264Height = decoderLimits.maxHeight,
                            maxH264Level = decoderLimits.maxLevel,
                            supportsEmbeddedTextSubs = embeddedSubs,
                            maxVideoBitrate = decoderLimits.maxVideoBitrate,
                            maxHevcBitrate = decoderLimits.maxHevcBitrate,
                            maxH264Bitrate = decoderLimits.maxH264Bitrate,
                            linkKind = link.kind,
                            linkMbps = link.mbps,
                        ),
                    )
                    qoeLinkKind = link.kind
                    qoeLinkMbps = link.mbps
                    ticket
                }
                if (result.isSuccess) {
                    val ticket = result.getOrThrow()
                    qoeDirectPlay = ticket.directPlay
                    startHeartbeat(itemId, positionProvider, isPausedProvider)
                    _state.value = PlayerSessionState.Ready(ticket)
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
        // R216 (FR-R216-4) — "posted once at session end". Reads the snapshot before the provider is
        // cleared below, same ordering as positionProvider/durationProvider's own final-read use in close().
        postQoeNow(itemId)
        qoeSnapshotProvider = null
        // Phase 185/R222 (FR-185-4 client half) — read before the provider is cleared, same ordering as
        // qoeSnapshotProvider's own final-read above; null is a legitimate, honest answer (see the field's
        // own doc), not an error to work around.
        val startupMs = startupMsProvider?.invoke()
        startupMsProvider = null
        exitScope.launch {
            // Phase 180 — found live 2026-08-29 on real stue TV hardware: a single failed attempt here
            // permanently orphans whatever Jellyfin is doing for this session, up to and including a
            // real GPU transcode — confirmed live, an NVENC job survived 20+ seconds after Back with
            // no retry. Unlike postPlaybackQoe below (explicitly fine to lose — diagnostics only),
            // losing this call has a real resource cost, so it gets a short bounded retry. Still
            // fire-and-forget on exitScope, still never blocks the UI (this phase's own "teardown
            // never blocks a user action" invariant) — just no longer a single roll of the dice on one
            // transient network hiccup (this TV has documented WiFi flakiness).
            var delayMs = 1_000L
            for (attempt in 0 until 3) {
                if (runCatching { apiClient.stopPlayback(itemId, positionMs, startupMs) }.isSuccess) break
                if (attempt < 2) { delay(delayMs); delayMs *= 2 }
            }
            // R142: finishing (≥90%) marks the item played so its tiles flip to ✓ and a series episode
            // advances up-next — no manual toggle. Below threshold it stays in-progress (resume preserved).
            if (durationMs > 0 && positionMs >= durationMs * 90 / 100) {
                runCatching { apiClient.markPlayed(itemId, watched = true) }
                WatchedBus.publish(mapOf(itemId to CardPlayState(played = true, playedPct = 1f)))  // R147
            }
        }
        _state.value = PlayerSessionState.Idle
    }

    /** R216 (FR-R216-4) — fire-and-forget; a failed/slow report must never affect playback, so this runs
     *  on [exitScope] (survives the caller's own scope being torn down, same reasoning as stopSession's
     *  terminal writes) and is never awaited by the caller. */
    private fun postQoeNow(itemId: String) {
        val snapshot = qoeSnapshotProvider?.invoke() ?: return
        exitScope.launch {
            apiClient.postPlaybackQoe(
                PlaybackQoeReport(
                    itemId = itemId,
                    droppedFrames = snapshot.droppedFrames,
                    rebufferCount = snapshot.rebufferCount,
                    rebufferMs = snapshot.rebufferMs,
                    bandwidthEstimateBps = snapshot.bandwidthEstimateBps,
                    videoDecoder = snapshot.videoDecoder,
                    directPlay = qoeDirectPlay,
                    linkKind = qoeLinkKind,
                    linkMbps = qoeLinkMbps,
                    subtitleLoadErrors = snapshot.subtitleLoadErrors,
                ),
            )
        }
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
                // R216 (FR-R216-4) — "a long-session interval, so an abandoned/crashed session is not
                // lost" alongside the end-of-session report in stopSession().
                if (++qoeTicksSinceReport >= QOE_REPORT_EVERY_N_TICKS) {
                    qoeTicksSinceReport = 0
                    postQoeNow(itemId)
                }
            }
        }
    }
}
