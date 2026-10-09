package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.RemoteControl
import dev.jellystructure.ravilo.ui.RemoteTarget
import dev.jellystructure.ravilo.ui.seams.PlayerQoeSnapshot
import dev.jellystructure.ravilo.ui.seams.detectDecoderLimits
import dev.jellystructure.ravilo.ui.seams.detectHdrSupport
import dev.jellystructure.ravilo.ui.seams.detectLinkState
import dev.jellystructure.ravilo.ui.seams.supportedAudioCodecs
import dev.jellystructure.ravilo.ui.seams.platformAudioDecoders
import dev.jellystructure.ravilo.ui.seams.supportedVideoCodecs
import dev.jellystructure.ravilo.ui.seams.playsHlsForAirPlay
import dev.jellystructure.ravilo.ui.seams.playsOnlyHls
import dev.jellystructure.ravilo.ui.seams.audioPickNeedsHls
import dev.jellystructure.ravilo.ui.seams.airplayCapabilities
import dev.jellystructure.ravilo.ui.seams.platformAirPlay
import dev.jellystructure.ravilo.ui.seams.startsAsAirPlayHls
import dev.jellystructure.ravilo.ui.seams.supportedContainers
import dev.jellystructure.ravilo.ui.seams.switchesAudioInFile
import dev.jellystructure.ravilo.ui.seams.switchesHlsAudioRenditions
import dev.jellystructure.ravilo.ui.seams.playsAdaptiveHls
import dev.jellystructure.ravilo.ui.seams.mergesExternalAudio
import dev.jellystructure.ravilo.ui.seams.supportsHevcOverHls
import dev.jellystructure.ravilo.ui.seams.supportsEmbeddedTextSubtitles
import dev.jellystructure.shared.tv.CardPlayState
import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.ravilo.ui.seams.PlayerLadderHints
import dev.jellystructure.shared.tv.PlaybackQoeReport
import dev.jellystructure.shared.tv.QoeStall
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.RestreamStepper
import dev.jellystructure.shared.tv.StallRule
import dev.jellystructure.shared.tv.StreamTicket
import dev.jellystructure.shared.tv.TvApiClient
import dev.jellystructure.shared.tv.TvApiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import dev.jellystructure.ravilo.ui.components.FailureClass
import dev.jellystructure.ravilo.ui.components.LoadErrorKind
import dev.jellystructure.ravilo.ui.components.PlaybackAvailability
import dev.jellystructure.ravilo.ui.components.classifyLoadFailure
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed class PlayerSessionState {
    data object Idle : PlayerSessionState()

    /** [retrying] — R237 (FR-R237-5): the first attempt has already failed, so this is no longer an
     *  ordinary cold start. Drives one extra line in R218's existing presentation, nothing more. */
    data class Loading(val retrying: Boolean = false) : PlayerSessionState()
    data class Ready(val ticket: StreamTicket) : PlayerSessionState()

    /**
     * R280 (FR-R280-4) — [message] is the raw failure text and is for the log. Nothing renders it:
     * it is `TvApiError.Http.message`, which is the HTTP response body verbatim.
     */
    data class Error(
        val message: String,
        val kind: LoadErrorKind = LoadErrorKind.GENERIC,
        val httpStatus: Int? = null,
    ) : PlayerSessionState()
}

// R280 (FR-R280-1) — the classifier and its verdict type moved to `components/LoadError.kt`; every
// store in the app shares them now, and `PlayerErrorKind` became `LoadErrorKind`. Kept here so
// R237's own call site reads unchanged.
internal fun classifyStartFailure(t: Throwable?): FailureClass = classifyLoadFailure(t)

private const val PROGRESS_INTERVAL_MS = 10_000L
// R306 (FR-R306-3) — how often the store looks at the player's fatal-error flag.
internal const val FAILURE_POLL_MS = 500L

/** R306 (FR-R306-3) — fires on the first failure seen *after* the flag was seen clear: a flag still set from
 *  the stream before this one (the player clears it on load, a moment after Ready) never counts. */
internal class FailureLatch {
    private var armed = false
    fun observe(failed: Boolean): Boolean {
        if (!failed) { armed = true; return false }
        return armed
    }
}

/**
 * 313 (found live 2026-10-09, Mac) — a stream from our own encoder that never moves: ffmpeg refused the plan, AVPlayer
 * kept retrying the stream's segments and the viewer saw "Loading…" forever with no engine error to catch. True once
 * the position has not moved (by more than [toleranceMs]) for [stuckMs] while the viewer wants it to play; a pause, a
 * move, or [reset] starts the clock again.
 */
/** 313 — what the failure watch does with a stream that failed or does not move. */
internal enum class StreamRecovery { RESTREAM_FROM_JELLYFIN, FAILED, NOT_STARTED }

/**
 * 313 — our encoder's stream gets one restream, and (found live 2026-10-09, evening) that restream asks for Jellyfin's
 * stream: a failure on the player's side is not one the server knows about, so asking again gave the same stream back.
 * Anything else that fails or never moves ends on R237's error.
 */
internal fun streamRecovery(ours: Boolean, restreamedAlready: Boolean, failed: Boolean): StreamRecovery = when {
    ours && !restreamedAlready -> StreamRecovery.RESTREAM_FROM_JELLYFIN
    failed -> StreamRecovery.FAILED
    else -> StreamRecovery.NOT_STARTED
}

internal class StuckWatch(private val stuckMs: Long = OUR_STREAM_STUCK_MS, private val toleranceMs: Long = 250) {
    private var lastPos = -1L
    private var sinceMs = -1L
    fun reset() { lastPos = -1L; sinceMs = -1L }
    fun observe(positionMs: Long, paused: Boolean, nowMs: Long): Boolean {
        if (paused || lastPos < 0 || kotlin.math.abs(positionMs - lastPos) > toleranceMs) {
            lastPos = positionMs; sinceMs = nowMs
            return false
        }
        return nowMs - sinceMs >= stuckMs
    }
}
internal const val OUR_STREAM_STUCK_MS = 20_000L
// R216 (FR-R216-4) — "a long-session interval" for QoE reporting so an abandoned/crashed session isn't
// lost entirely (was 60 ticks = 10 minutes). 309 (FR-309-1) — now two minutes: a stream is proven after two minutes
// held without a stall, so the server needs a post at least that often (one row per play, upserted).
private const val QOE_PROOF_EVERY_N_TICKS = 12

/**
 * The capabilities a start negotiates with (every platform probe, sampled now). 309 (FR-309-6) — shared by
 * [PlayerStore.startSession] and the detail page's early encode ([DetailPrewarm]), so the two ask for the same stream.
 */
internal fun currentClientCapabilities(
    link: dev.jellystructure.ravilo.ui.seams.LinkState = detectLinkState(),
    /** R376 — the picture is already on AirPlay (Safari): start as HLS so AirPlay has a stream to carry. */
    airplayWireless: Boolean = false,
): ClientCapabilities {
    // Bug fix: this used to be a static literal with no HDR signal, so the server always
    // assumed direct-play was safe even for HDR10/HLG sources the device might not be
    // able to display correctly (see ClientCapabilities.supportsHdr10/supportsHlg docs).
    val hdr = detectHdrSupport()
    // R183: Dolby Vision + the real H.264 decode ceiling, so DV profile-8 titles
    // direct-play and any fallback transcode is one this device can actually decode.
    // R216 generalises this probe to also report the device's real decode-bitrate
    // ceilings (see DecoderLimits' doc).
    val decoderLimits = detectDecoderLimits()
    // Phase 161: whether this client renders embedded text subs (SRT/ASS/SSA) natively
    // in-container on a direct-played file, so the server can skip a redundant VTT
    // sideload of the same stream (bug: it used to always sideload, double-delivering
    // every text subtitle on a direct-played title — see PlaybackService.buildSubtracks).
    val embeddedSubs = supportsEmbeddedTextSubtitles()
    // R265 (FR-R265-8), changed by R376 (owner, 2026-10-08): Safari direct-plays what it can, like
    // any browser, and takes HLS (with the manifest's subtitles, which AirPlay carries to the TV) only
    // once the picture is on AirPlay — a start while already on AirPlay (the next episode), or the
    // restart [restreamForAirPlay] makes the moment AirPlay is picked.
    val airplayHls = startsAsAirPlayHls(playsHlsForAirPlay(), airplayWireless)
    // R329 (FR-R329-3) — a player that takes nothing but HLS (the Mac's AVPlayer), and HEVC in it
    // where it says so; Android and the web negotiate exactly as before (hlsHevc stays false).
    val hlsOnly = airplayHls || playsOnlyHls()
    val capabilities = ClientCapabilities(
        containers = supportedContainers(),   // R376 (FR-R376-5) — the web asks its browser
        videoCodecs = supportedVideoCodecs(),
        hlsOnly = hlsOnly,
        hlsHevc = hlsOnly && !airplayHls && supportsHevcOverHls(),
        hlsSubtitles = airplayHls,
        // R291 (FR-R291-2) — every audio track in one master, switched in the player.
        hlsAudioRenditions = switchesHlsAudioRenditions(),
        // 308 (FR-308-2) — a transcode as a ladder of variants the player chooses between.
        hlsAdaptive = playsAdaptiveHls(),
        // R283 — what this build really decodes (the Android actual adds TrueHD/DTS
        // when the FFmpeg extension is installed); was a literal that omitted both.
        audioCodecs = supportedAudioCodecs(),
        // R379 — the AC-3 family this device decodes itself; the server re-encodes a missing one.
        platformAudioDecoders = platformAudioDecoders(),
        hlsHevcCapable = supportsHevcOverHls(),
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
        // Phase 314b — this player merges a `.mka` sidecar with a direct-played video.
        externalAudio = mergesExternalAudio(),
    )
    return capabilities
}

class PlayerStore(
    private val apiClient: TvApiClient,
    /** R347 (FR-R347-1) — this item's credits marker, for the stop's "finished" rule (null = none). */
    private val creditsStartMs: Long? = null,
    /** R343 (FR-R343-4) — this start is *Start over*: sent as `start_over` on every start of this store's
     *  item (a return from the background re-sends it; the server's clear is idempotent), from 0:00. */
    val startOver: Boolean = false,
    /** R343 (FR-R343-5, dev review item 9) — this entry is part of a shuffle: always from 0:00, sent as
     *  `shuffle` so the server leaves no resume point behind an unfinished shuffled play. */
    val shuffle: Boolean = false,
) {
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
    // R306 (FR-R306-3) — watches the player for a fatal error while the session is Ready.
    private var failureWatchJob: Job? = null
    private var failedProvider: () -> Boolean = { false }
    private var currentItemId: String? = null
    // Kept so close() can still report a final position (and take the same ≥90% mark-played decision)
    // if the owner tore the store down without calling stopSession() itself — which also makes the two
    // teardown paths order-independent: whichever runs first does the real stop, the other no-ops.
    private var positionProvider: (() -> Long)? = null
    private var isPausedProvider: (() -> Boolean)? = null   // 313 — the stuck watch reads it
    /** 313 (found live 2026-10-09) — the item whose stuck stream from our encoder was already restreamed (once). */
    private var stuckRestreamedFor: String? = null
    /** 313 — the audio the last restream of this item asked for (a rendition pick is not in the session's ticket). */
    private var lastRestreamAudio: Int? = null
    /** 313 (found live 2026-10-09, evening) — the item whose player could not play our stream: its restreams ask for Jellyfin's. */
    private var notOursFor: String? = null
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
    // 309 (FR-309-11) — the rung the play started on (the ticket's), and what the last post already said.
    private var qoeStartVariantBps: Long? = null
    private var reportedStalls = 0
    private var reportedFirstFrame = false

    fun startSession(
        itemId: String,
        // R292 (FR-R292-2) — a return from the background asks for the stream cut at the record's position;
        // null = an ordinary start, Jellyfin's user data decides.
        startPositionMs: Long? = null,
        // R291 (FR-R291-1) — the remembered audio choice, sent with the FIRST negotiation.
        audioLanguage: String? = null,
        audioVariant: String? = null,
        positionProvider: () -> Long,
        isPausedProvider: () -> Boolean,
        durationProvider: () -> Long = { 0L },
        qoeSnapshotProvider: () -> PlayerQoeSnapshot = { PlayerQoeSnapshot() },
        startupMsProvider: () -> Long? = { null },
        // R306 (FR-R306-3) — the player's fatal-error flag. Null keeps the one a previous start gave (R237's
        // Retry re-starts without it, and must still be watched).
        failedProvider: (() -> Boolean)? = null,
    ) {
        failedProvider?.let { this.failedProvider = it }
        failureWatchJob?.cancel()
        DetailPrewarm.playStarted(itemId)   // 309 — the detail page was left for this play: its early encode is ours now
        if (currentItemId != itemId) { stuckRestreamedFor = null; lastRestreamAudio = null; notOursFor = null }
        currentItemId = itemId
        this.positionProvider = positionProvider
        this.isPausedProvider = isPausedProvider
        this.durationProvider = durationProvider
        this.qoeSnapshotProvider = qoeSnapshotProvider
        this.startupMsProvider = startupMsProvider
        qoeTicksSinceReport = 0
        _state.value = PlayerSessionState.Loading()
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
            var lastKind = LoadErrorKind.UNREACHABLE
            var lastStatus: Int? = null
            var delayMs = 1_000L
            repeat(5) { attempt ->
                val result = runCatching {
                    // R216 (FR-R216-2) — this device's own network link, sampled once here (not
                    // continuously — see the phase's Out-of-scope section). Reused below for the QoE
                    // reports this same session posts, so it isn't re-sampled per report.
                    val link = detectLinkState()
                    // 309 (FR-309-6) — one builder, shared with the detail page's early encode, so the prewarm and the
                    // play negotiate the same thing (and Play adopts the prewarm's job).
                    val capabilities = currentClientCapabilities(link, airplayWireless = platformAirPlay?.wireless?.value == true)
                    lastCapabilities = capabilities   // R282 (FR-R282-5)
                    // R343 — a Start over or a shuffled entry starts at 0:00 (the client's position wins over
                    // Jellyfin's on any server, so even an older one never resumes a shuffled episode); a
                    // return from the background still carries its own position.
                    val startAt = startPositionMs ?: (if (startOver || shuffle) 0L else null)
                    // R376 (FR-R376-3, owner 2026-10-08) — a multi-audio file starts as direct play too; only an audio
                    // pick the player cannot make inside the file moves the item to HLS ([restreamWithSub]).
                    val ticket = apiClient.startPlayback(itemId = itemId, capabilities = capabilities, startPositionMs = startAt, audioLanguage = audioLanguage, audioVariant = audioVariant, shuffle = shuffle, startOver = startOver)
                    qoeLinkKind = link.kind
                    qoeLinkMbps = link.mbps
                    ticket
                }
                if (result.isSuccess) {
                    val ticket = result.getOrThrow()
                    // R271 (FR-R271-2) — a ticket with no stream URL is a failed start, said once
                    // here. The player used to answer it by TEMPLATING a Jellyfin URL out of the
                    // ticket's raw access token, with a parameter spelling 12.1 does not honour — so
                    // the "fallback" could only ever have produced a second, quieter failure. Both
                    // server-side producers set `hls_url` unconditionally, so this is unreachable
                    // today; it is here so that if it ever becomes reachable it surfaces through
                    // R237's existing per-cause copy instead of through a dead URL.
                    if (ticket.hlsUrl == null) {
                        _state.value = PlayerSessionState.Error(
                            "The server did not return a stream URL",
                            LoadErrorKind.GENERIC,
                        )
                        reportStartFailure(itemId, null)
                        return@launch
                    }
                    qoeDirectPlay = ticket.directPlay
                    qoeStartVariantBps = ticket.startVariantBps
                    reportedStalls = 0
                    reportedFirstFrame = false
                    startHeartbeat(itemId, positionProvider, isPausedProvider)
                    _state.value = PlayerSessionState.Ready(ticket.onThisServer())
                    startFailureWatch()
                    return@launch
                }
                val cause = result.exceptionOrNull()
                lastErr = cause?.message ?: lastErr
                // R237 (FR-R237-1) — the status code was always right here, one `as?` away, and never
                // consulted. A failure that cannot succeed on retry is surfaced immediately.
                val failure = classifyStartFailure(cause)
                lastKind = failure.kind
                lastStatus = failure.status
                if (!failure.retryable) {
                    _state.value = PlayerSessionState.Error(lastErr, failure.kind, failure.status)
                    reportStartFailure(itemId, failure.status)
                    return@launch
                }
                if (attempt < 4) {
                    // FR-R237-5 — one failed attempt is already proof this is not a normal start. Say so
                    // in R218's existing presentation rather than leaving the viewer to infer it from how
                    // long a spinner has been up (measured behaviour: they give up at ~4 s).
                    _state.value = PlayerSessionState.Loading(retrying = true)
                    delay(failure.retryAfterMs ?: delayMs)
                    if (failure.retryAfterMs == null) delayMs *= 2
                }
            }
            _state.value = PlayerSessionState.Error(lastErr, lastKind, lastStatus)
            reportStartFailure(itemId, lastStatus)
        }
    }

    /** R291 (FR-R291-2) — a composed master is a path on THIS server (`/api/tv/stream/{id}/master.m3u8`):
     *  resolved against the address the app already talks to, never a host the server guessed. */
    private fun StreamTicket.onThisServer(): StreamTicket {
        currentMediaSourceId = mediaSourceId   // phase 314c — every ticket says which version it plays
        currentTicket = this                   // 309 (FR-309-8/-9) — what the store's own restreams start from
        dev.jellystructure.ravilo.ui.seams.PlayerLadderHints.noteTicket(this)
        val base = apiClient.baseUrl.trimEnd('/')
        val withHls = hlsUrl?.takeIf { it.startsWith("/") }?.let { copy(hlsUrl = base + it) } ?: this
        // Phase 314b — a sidecar's URL is a path on this server too.
        return if (withHls.audio.none { it.externalUrl?.startsWith("/") == true }) withHls
        else withHls.copy(audio = withHls.audio.map { a -> a.externalUrl?.takeIf { it.startsWith("/") }?.let { a.copy(externalUrl = base + it) } ?: a })
    }

    /** Phase 314c — the picture version the current stream plays (`StreamTicket.mediaSourceId`), kept across restreams. */
    var currentMediaSourceId: String? = null
        private set

    /** Phase 314c — the viewer picked another picture version: the same item at [positionMs] from that version's file. */
    fun restreamVersion(itemId: String, mediaSourceId: String, positionMs: Long, subtitleStreamIndex: Int, audioStreamIndex: Int?) {
        currentMediaSourceId = mediaSourceId
        restreamWithSub(itemId, subtitleStreamIndex, positionMs, audioStreamIndex, mediaSourceId)
    }

    // R282 (FR-R282-5) — what startSession last told the server this device can do, re-sent with every
    // restream so an un-burn (252) negotiates as the real device instead of conservative defaults.
    private var lastCapabilities: ClientCapabilities? = null

    /**
     * R381 (owner, 2026-10-08) — at the credits, prepare the next episode (no side effects on the server: FR-R381-7) and
     * prefetch its start if it is a direct play. Fire-and-forget on [scope]: a preload that fails or comes too late
     * changes nothing (the next episode then starts as it always did). With autoplay on or off alike (owner).
     */
    fun prepareNext(nextItemId: String) {
        val caps = lastCapabilities ?: return
        scope.launch {
            val prepared = apiClient.preparePlayback(nextItemId, caps) ?: return@launch
            if (prepared.directPlay) dev.jellystructure.ravilo.ui.seams.NextPrefetch.prefetch(prepared)
        }
    }

    /** R381 — the viewer sought back out of the credits: the preload is dropped. */
    fun discardNext() = dev.jellystructure.ravilo.ui.seams.NextPrefetch.discard()

    /** R56 — Re-stream with a PGS subtitle burned in; keeps the heartbeat running (same item).
     *  R282 — a negative [subtitleStreamIndex] re-streams with NO burn-in (252 FR-252-2).
     *  R284 — [audioStreamIndex] is the audio track the new stream must carry (253 FR-253-1); callers
     *  pass the current one on a subtitle change so the two choices never reset each other. */
    /**
     * R376 (owner, 2026-10-08) — the viewer picked AirPlay while a file direct-plays in Safari: the same item restarts
     * as HLS at [positionMs] (what AirPlay hands to the TV, subtitles inside the manifest), keeping the subtitle and
     * audio choices; the item stays on HLS for its later restreams.
     */
    fun restreamForAirPlay(itemId: String, subtitleStreamIndex: Int, positionMs: Long, audioStreamIndex: Int?) {
        lastCapabilities = lastCapabilities?.let { airplayCapabilities(it) }
        restreamWithSub(itemId, subtitleStreamIndex, positionMs, audioStreamIndex)
    }

    fun restreamWithSub(itemId: String, subtitleStreamIndex: Int, positionMs: Long, audioStreamIndex: Int? = null, mediaSourceId: String? = currentMediaSourceId) {
        // R376 (FR-R376-3) — an audio pick on a player that cannot switch tracks inside one file asks for HLS, where
        // every track is a rendition; the item stays on HLS for its later restreams.
        lastCapabilities?.let { caps ->
            if (audioPickNeedsHls(audioStreamIndex != null, caps.hlsOnly, switchesAudioInFile())) lastCapabilities = caps.copy(hlsOnly = true)
        }
        if (itemId == currentItemId) lastRestreamAudio = audioStreamIndex   // 313 — a recovery restream keeps it
        scope.launch {
            _state.value = PlayerSessionState.Loading()
            _state.value = runCatching {
                PlayerSessionState.Ready(apiClient.restream(itemId, subtitleStreamIndex, positionMs, lastCapabilities, audioStreamIndex, mediaSourceId,
                    notOurEncoder = itemId == notOursFor).onThisServer())
            }.getOrElse {
                val f = classifyLoadFailure(it)
                PlayerSessionState.Error(it.message ?: "", f.kind, f.status)
            }
            // 313 (found live 2026-10-09) — the watch ends when the state leaves Ready (this restream's Loading), so a
            // stream that broke after a restream was never caught: "Loading…" forever. Armed again for the new stream.
            if (_state.value is PlayerSessionState.Ready) startFailureWatch()
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
        failureWatchJob?.cancel()
        failureWatchJob = null
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
        // R328 (FR-R328-9) — tracked, so a desktop that quits waits for the stop to reach the server.
        dev.jellystructure.ravilo.ui.TeardownWork.track(exitScope.launch {
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
            // R142: finishing marks the item played so its tiles flip to ✓ and a series episode advances
            // up-next — no manual toggle. Below threshold it stays in-progress (resume preserved). R347
            // (FR-R347-1) — finished is past 90 % or past the item's trusted credits marker.
            if (dev.jellystructure.shared.tv.playbackFinished(positionMs, durationMs, creditsStartMs) && markedWatched.add(itemId)) {
                runCatching { apiClient.markPlayed(itemId, watched = true) }
                WatchedBus.publish(mapOf(itemId to CardPlayState(played = true, playedPct = 1f)))  // R147
            }
        })
        _state.value = PlayerSessionState.Idle
    }

    /** R216 (FR-R216-4) — fire-and-forget; a failed/slow report must never affect playback, so this runs
     *  on [exitScope] (survives the caller's own scope being torn down, same reasoning as stopSession's
     *  terminal writes) and is never awaited by the caller. */
    /**
     * R237 (FR-R237-6) — a start that never reached the player still leaves a readable row. The
     * 2026-09-06 incident's three attempts wrote `playback_qoe` rows of nulls and zeroes, which proved
     * only *that* they failed; the reason had to be reconstructed by correlating log lines against row
     * timestamps by hand. We already know the status by the time we get here, so post it.
     *
     * Deliberately does not go through [postQoeNow]: there is no player snapshot to take (that is the
     * whole point), and requiring one would drop exactly the reports that matter most. Fire-and-forget
     * on [exitScope], same as every other QoE write — a failed report must never affect playback.
     */
    private fun reportStartFailure(itemId: String, status: Int?) {
        exitScope.launch {
            runCatching {
                apiClient.postPlaybackQoe(
                    PlaybackQoeReport(
                        itemId = itemId,
                        directPlay = false,
                        linkKind = qoeLinkKind,
                        linkMbps = qoeLinkMbps,
                        startFailureStatus = status ?: 0, // 0 = failed with no HTTP response at all
                    ),
                )
            }
        }
    }

    /** Posts this play's QoE now; the returned job ends when the post has (309: a ladder switch waits for it). */
    private fun postQoeNow(itemId: String): Job? {
        val snapshot = qoeSnapshotProvider?.invoke() ?: return null
        return exitScope.launch {
            apiClient.postPlaybackQoe(
                PlaybackQoeReport(
                    itemId = itemId,
                    droppedFrames = snapshot.droppedFrames,
                    rebufferCount = snapshot.rebufferCount,
                    rebufferMs = snapshot.rebufferMs,
                    bandwidthEstimateBps = snapshot.bandwidthEstimateBps,
                    // 309 (FR-309-13/-11) — what the estimate rests on, the time to the first frame and the start rung.
                    bandwidthSamples = snapshot.bandwidthSamples,
                    bandwidthBytes = snapshot.bandwidthBytes,
                    firstFrameMs = snapshot.firstFrameMs,
                    startVariantBps = qoeStartVariantBps,
                    videoDecoder = snapshot.videoDecoder,
                    audioDecoder = snapshot.audioDecoder,   // R379
                    directPlay = qoeDirectPlay,
                    linkKind = qoeLinkKind,
                    linkMbps = qoeLinkMbps,
                    subtitleLoadErrors = snapshot.subtitleLoadErrors,
                    // 308 (FR-308-5) — the variant switches and the variant playing now.
                    variantSwitchesDown = snapshot.variantSwitchesDown,
                    variantSwitchesUp = snapshot.variantSwitchesUp,
                    variantBandwidthBps = snapshot.variantBandwidthBps,
                    variantHeight = snapshot.variantHeight,
                    // R381 (FR-R381-1/-3) — this item's own counts, its stalls, the session totals and the named waits.
                    perItem = snapshot.perItem,
                    stalls = snapshot.stalls,
                    sessionRebufferCount = snapshot.sessionRebufferCount,
                    sessionRebufferMs = snapshot.sessionRebufferMs,
                    waits = snapshot.waits,
                    // R292 (FR-R292-11) — carried since R292 but never posted from here.
                    videoOutputRecoveries = snapshot.videoOutputRecoveries,
                    videoOutputRecoveryRung = snapshot.videoOutputRecoveryRung,
                    videoOutputRecoveryMs = snapshot.videoOutputRecoveryMs,
                    backgroundReturns = snapshot.backgroundReturns,
                    restoredAfterRecreate = snapshot.restoredAfterRecreate,
                ),
            )
        }
    }

    /** 308 (FR-308-5) — the variant last reported; a different one is reported at the next tick, not in ten minutes. */
    private var reportedVariant: Triple<Long?, Int, Int>? = null

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
    /** 312 (found live 2026-10-08) — the items this store has already marked watched: advancing at the credits and the
     *  stop that follows both used to send a mark, so a finished episode was marked twice. One mark per item. */
    private val markedWatched = mutableSetOf<String>()

    fun markWatched(itemId: String) {
        if (!markedWatched.add(itemId)) return
        exitScope.launch {
            runCatching { apiClient.markPlayed(itemId, watched = true) }
            WatchedBus.publish(mapOf(itemId to CardPlayState(played = true, playedPct = 1f)))  // R147
        }
    }

    /**
     * R306 (FR-R306-3) — a fatal error after the stream started (an engine error, not a wait) becomes R237's
     * error state, generic kind: *"Something went wrong"*, Retry and Back — never R218's spinner forever.
     * The watch arms only after it has seen the flag clear, so a flag left from the stream before this one
     * (the player resets it on load, which follows this Ready) can never count.
     */
    private fun startFailureWatch() {
        failureWatchJob?.cancel()
        val failed = failedProvider
        failureWatchJob = scope.launch {
            val latch = FailureLatch()
            val stuck = StuckWatch()
            while (isActive && _state.value is PlayerSessionState.Ready) {
                delay(FAILURE_POLL_MS)
                // 313 (found live 2026-10-09) — a stream from our own encoder that fails (a refused plan answers 410) or
                // never moves is restreamed once at the same place with the same picks, asking for Jellyfin's stream
                // (found live the same evening: a failure on the player's side was answered with our stream again).
                // Failing or stuck again: R237's error, never "Loading…" forever.
                val t = currentTicket
                val item = currentItemId
                val ours = t?.encoder == "ours" && item != null
                val pos = positionProvider?.invoke()
                val failedNow = latch.observe(failed())
                val stuckNow = !failedNow && ours && pos != null &&
                    stuck.observe(pos, isPausedProvider?.invoke() == true, kotlin.time.Clock.System.now().toEpochMilliseconds())
                if (!failedNow && !stuckNow) continue
                when (streamRecovery(ours, stuckRestreamedFor == item, failedNow)) {
                    StreamRecovery.RESTREAM_FROM_JELLYFIN -> {
                        stuckRestreamedFor = item
                        notOursFor = item
                        println("[player] 313: our encoder's stream ${if (failedNow) "failed" else "has not moved for ${OUR_STREAM_STUCK_MS / 1000} s"} — restreaming once from Jellyfin at ${pos ?: 0}ms")
                        restreamWithSub(item!!, t!!.burnedSubtitleIndex ?: -1, pos ?: 0L, lastRestreamAudio ?: t.audioStreamIndex)
                    }
                    StreamRecovery.FAILED -> endOnError(PlayerSessionState.Error("The player failed after the stream started",
                        if (PlaybackAvailability.unavailable) LoadErrorKind.PLAYBACK_UNAVAILABLE else LoadErrorKind.GENERIC), pos)
                    StreamRecovery.NOT_STARTED -> endOnError(PlayerSessionState.Error("The stream did not start", LoadErrorKind.GENERIC), pos)
                }
                break
            }
        }
    }

    /**
     * R372 (found live 2026-10-09, evening, Mac) — a play that ends on R237's error stops its session now: the error
     * screen stood for minutes with the session *paused · 0:00* in every *Playing everywhere*, and a failure the
     * watchdog reaped stayed 24 h. The row goes 15 s later; *Retry* starts a new session; leaving sends nothing more.
     */
    private fun endOnError(error: PlayerSessionState.Error, positionMs: Long?) {
        stopSession(positionMs ?: 0L)
        _state.value = error
    }

    private fun startHeartbeat(
        itemId: String,
        positionProvider: () -> Long,
        isPausedProvider: () -> Boolean,
    ) {
        progressJob?.cancel()
        progressJob = scope.launch {
            // R357 (FR-R357-3/-4) — every report says the player's level and mute (the ones the dashboard's commands
            // move), and a change to them reports once at once rather than at the next tick.
            launch {
                RemoteControl.onVolumeSettled(RemoteTarget.VIDEO) {
                    runCatching { apiClient.reportProgress(itemId, positionProvider(), isPausedProvider(), RemoteControl.videoVolumeReport()) }
                }
            }
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                runCatching {
                    apiClient.reportProgress(itemId, positionProvider(), isPausedProvider(), RemoteControl.videoVolumeReport())
                }
                // R216 (FR-R216-4) — "a long-session interval, so an abandoned/crashed session is not
                // lost" alongside the end-of-session report in stopSession().
                val snap = qoeSnapshotProvider?.invoke()
                val variant = snap?.let { Triple(it.variantBandwidthBps, it.variantSwitchesDown, it.variantSwitchesUp) }
                val variantMoved = variant?.first != null && variant != reportedVariant
                // 309 (FR-309-11) — also at the first frame and at every new stall, and every two minutes so the server
                // sees a stream held long enough to prove it (FR-309-1; one row per play, upserted).
                val firstFrame = snap?.firstFrameMs != null && !reportedFirstFrame
                val newStall = (snap?.rebufferCount ?: 0) > reportedStalls
                if (++qoeTicksSinceReport >= QOE_PROOF_EVERY_N_TICKS || variantMoved || firstFrame || newStall) {
                    qoeTicksSinceReport = 0
                    reportedVariant = variant
                    if (firstFrame) reportedFirstFrame = true
                    reportedStalls = snap?.rebufferCount ?: reportedStalls
                    val posted = postQoeNow(itemId)
                    // 309 (FR-309-8) — a direct play that stalls the way that counts moves to the ladder, once.
                    if (newStall && snap != null) maybeLadderAfterStall(itemId, snap.stalls, posted, positionProvider())
                }
                // 309 (FR-309-9) — a player that cannot switch variants itself is stepped by restreams.
                maybeStepRestream(itemId, positionProvider())
            }
        }
    }

    // ── 309 (FR-309-8/-9): restreams the store starts on its own ──

    /** The ticket the player plays now (every Ready passes [onThisServer]). */
    private var currentTicket: StreamTicket? = null
    /** The item whose direct play was already moved to the ladder (never twice for one item). */
    private var ladderSwitchedFor: String? = null
    private var stepper = RestreamStepper()
    private var stepperFor: String? = null
    /** The device's own `max_video_bitrate` before any stepping cap (restored when the cap is lifted). */
    private var stepBaseMaxVideo: Int? = null

    private suspend fun maybeLadderAfterStall(itemId: String, stalls: List<QoeStall>, posted: Job?, positionMs: Long) {
        val t = currentTicket ?: return
        if (!shouldMoveToLadder(t.directPlay, ladderSwitchedFor == itemId, stalls)) return
        ladderSwitchedFor = itemId
        // The server's record has the stall before it negotiates again: the record's cap (FR-309-1, the stalled stream
        // × 0.8) is what turns this item's next negotiation into a transcode with a ladder.
        posted?.join()
        println("309: the direct play of $itemId stalled (${StallRule.counting(stalls).size} counting) — restarting it on the ladder at $positionMs ms")
        PlayerLadderHints.rearmTracks = true
        restreamWithSub(itemId, -1, positionMs, null)
    }

    private fun maybeStepRestream(itemId: String, positionMs: Long) {
        if (!PlayerLadderHints.restreamStepping) return
        val t = currentTicket ?: return
        if (t.directPlay || t.adaptive) return
        val ahead = PlayerLadderHints.bufferedAheadMs.takeIf { it >= 0 } ?: return
        val caps = lastCapabilities ?: return
        if (stepperFor != itemId) { stepper = RestreamStepper(); stepperFor = itemId; stepBaseMaxVideo = caps.maxVideoBitrate }
        val step = stepper.evaluate(kotlin.time.Clock.System.now().toEpochMilliseconds(), ahead, RestreamStepper.videoBpsOf(t.startVariantBps)) ?: return
        lastCapabilities = caps.copy(maxVideoBitrate = steppedMaxVideo(stepBaseMaxVideo ?: 0, step.capVideoBps))
        println("309: stepping $itemId ${if (step.down) "down" else "up"} — cap ${step.capVideoBps / 1000} kb/s, ${ahead} ms buffered")
        PlayerLadderHints.rearmTracks = true
        restreamWithSub(itemId, t.burnedSubtitleIndex ?: -1, positionMs, t.audioStreamIndex)
    }
}

/** 309 (FR-309-8) — a direct play that stalled the way that counts ([StallRule]) moves to the ladder, once per item. */
internal fun shouldMoveToLadder(directPlay: Boolean, alreadyMoved: Boolean, stalls: List<QoeStall>): Boolean =
    directPlay && !alreadyMoved && StallRule.anyCounts(stalls)

/** 309 (FR-309-9) — the `max_video_bitrate` a stepping restream sends: the step's cap under the device's own, or the
 *  device's own again when the cap is lifted (0). */
internal fun steppedMaxVideo(deviceMaxVideo: Int, capVideoBps: Long): Int {
    if (capVideoBps <= 0) return deviceMaxVideo
    val cap = capVideoBps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return if (deviceMaxVideo > 0) minOf(deviceMaxVideo, cap) else cap
}
