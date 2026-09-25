package dev.jellystructure.ravilo.ui.seams

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.SurfaceView
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.media3.session.MediaSession
import dev.jellystructure.ravilo.ui.RaviloAppContext
import dev.jellystructure.shared.tv.AudioTrack
import dev.jellystructure.shared.tv.SubTrack
import kotlin.concurrent.Volatile

/** Strip ASS/SSA override tags from VTT cue text (R68). */
internal fun cleanCueText(text: String): String =
    text.replace(Regex("""\{\\[^}]*\}"""), "")
        .replace("""\N""", "\n")
        .replace("""\n""", "\n")
        .replace("""\h""", " ")
        .trim()

/**
 * Android actual backed by ExoPlayer/Media3.
 *
 * Exotic-codec support (DTS/TrueHD/AC3/E-AC3) comes from the FFmpeg extension decoders supplied by
 * the GPL-contained `:ravilo-player` module via [RaviloPlayerEngine.renderersFactoryProvider] (R31).
 * When the provider is unset (e.g. tests), this falls back to ExoPlayer's default renderers.
 */
actual class RaviloPlayer actual constructor() {
    private val ctx: Context get() = RaviloAppContext.get()
    // R292 (FR-R292-10) — the engine is a nullable ref, not a lazy: it is released on ON_STOP/screen-off
    // ([releaseEngine]) and rebuilt by the next [load], with EVERY binding re-applied from [bindEngine] —
    // R192's own "lazy can't be reset" pattern, applied to the engine. Null = no engine: every read answers
    // "nothing", every transport call is a no-op (FR-R292-4: nothing can touch a stream whose session ended).
    @Volatile private var engine: ExoPlayer? = null
    // R292 (FR-R292-3) — set by releaseEngine(); the next build counts as a return from the background.
    private var releasedForBackground = false

    private fun exo(): ExoPlayer = engine ?: buildEngine().also { built ->
        engine = built
        bindEngine(built)
        if (releasedForBackground) { releasedForBackground = false; qoeBackgroundReturns++ }
    }

    private fun buildEngine(): ExoPlayer {
        // R56: Media3's MatroskaExtractor already parses embedded VobSub/DVDSub and PGS tracks.
        // R294: the Matroska extractor is swapped for :ravilo-player's patched copy, which follows
        // SeekHead to a Tracks element stored after the first Cluster instead of reading the file to
        // the end; every other extractor is Media3's own.
        // Phase 179 (FR-179-2) — a sideloaded text-subtitle track (the `.../Subtitles/{index}/0/
        // Stream.vtt` URL PlaybackService.buildSubtracks() builds) used Media3's plain default policy,
        // which gave up and permanently disabled the track on the first load failure — observed live as
        // `Disabling track due to error: ... SocketTimeoutException` mid-session, with no further attempt
        // for the rest of playback. SubtitleRetryingLoadErrorHandlingPolicy only widens the retry
        // allowance for that one URL pattern; every other load (video/audio HLS segments, manifests)
        // delegates straight through to Media3's own DefaultLoadErrorHandlingPolicy, unchanged.
        val extractors = RaviloPlayerEngine.extractorsFactoryProvider?.invoke()
        val mediaSourceFactory = (
            if (extractors != null) androidx.media3.exoplayer.source.DefaultMediaSourceFactory(ctx, extractors)
            else androidx.media3.exoplayer.source.DefaultMediaSourceFactory(ctx)
        ).setLoadErrorHandlingPolicy(SubtitleRetryingLoadErrorHandlingPolicy())
        val builder = ExoPlayer.Builder(ctx, mediaSourceFactory)
        RaviloPlayerEngine.renderersFactoryProvider?.invoke(ctx)?.let { builder.setRenderersFactory(it) }
        // R216 (FR-R216-3) — an explicit LoadControl instead of inheriting DefaultLoadControl's stock
        // bufferForPlaybackAfterRebufferMs: on a link that dips mid-playback, resuming on a thin buffer
        // turns one stall into a train of them. Only that one value is raised — min/max buffer and
        // bufferForPlaybackMs (start latency, R211/R212) are left at their defaults on purpose: the
        // reported TV's dalvik.vm.heapgrowthlimit (192 MB) leaves less headroom above Media3's own
        // 128 MB default video buffer than the time-based defaults suggest (see the phase's own doc) —
        // enlarging buffer SIZE needs FR-R216-4's telemetry to validate first, not a blind bump here.
        builder.setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                    DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                    QOE_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                )
                .build()
        )
        // R292 (FR-R292-12) — take audio focus as a movie: Ravilo pauses when another app takes focus and
        // never plays over it; a may-duck transient ducks rather than pausing (Media3's own behaviour).
        builder.setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true,
        )
        return builder.build()
    }

    /**
     * R292 (FR-R292-10) — EVERYTHING bound to an engine, bound here and nowhere else, so a rebuilt engine
     * misses nothing: the video-size listener (R77), the QoE analytics listener (R216/R218/179/R220), the
     * cue listener that feeds the current SubtitleView (R55/R110/R244 — one listener per engine, reading
     * the CURRENT view at callback time, which also ends the one-listener-per-setSubtitleView
     * accumulation), the current SurfaceView (R220's tracking), and (TV only) the MediaSession (R192/R193).
     * The renderers factory, LoadControl, load-error policy, extractors factory and audio attributes are
     * construction-time and live in [buildEngine].
     */
    private fun bindEngine(player: ExoPlayer) {
        player.addListener(videoSizeListener)
        player.addAnalyticsListener(qoeListener)
        player.addListener(cueListener)
        currentSurfaceView?.let { player.setVideoSurfaceView(it) }
        if (RaviloAppContext.isTelevision && mediaSessionRef == null) {
            mediaSessionRef = MediaSession.Builder(ctx, player).setId("ravilo-player").build()
        }
    }

    private val videoSizeListener = object : Player.Listener {
        override fun onVideoSizeChanged(size: VideoSize) { _videoSize.value = size }
    }

    private val cueListener = object : Player.Listener {
        override fun onCues(cueGroup: CueGroup) {
            val view = subtitleViewRef ?: return
            val cleaned = cueGroup.cues.map { cue ->
                val raw = cue.text?.toString() ?: return@map cue
                val clean = cleanCueText(raw)
                if (clean == raw) cue else cue.buildUpon().setText(clean).build()
            }
            view.setCues(cleaned)
        }
    }

    /** R292 (FR-R292-1) — see the expect's doc. Safe to call with no engine. */
    actual fun releaseEngine() {
        mediaSessionRef?.release()
        mediaSessionRef = null
        val e = engine ?: return
        engine = null
        releasedForBackground = true
        _hasRenderedFirstFrame = false
        _isBuffering = false
        _isSeeking = false
        _videoSize.value = VideoSize.UNKNOWN
        e.release()
    }

    actual fun recordRestoredAfterRecreate() { qoeRestoredAfterRecreate++ }

    // R216 (FR-R216-4) — accumulated playback-quality counters for this session; read by [qoeSnapshot].
    // @Volatile: read from PlayerStore's coroutine, written from whichever thread Media3 dispatches
    // analytics events on — plain field writes here are always whole-value replacements, never a
    // read-modify-write race (each field is only ever touched inside the single-threaded qoeListener
    // callbacks Media3 itself serializes), so @Volatile alone (no mutex) is sufficient for cross-thread
    // visibility, matching this file's other cross-thread state (_videoSize).
    @Volatile private var qoeDroppedFrames: Int = 0
    @Volatile private var qoeRebufferCount: Int = 0
    @Volatile private var qoeRebufferMs: Long = 0
    @Volatile private var qoeBandwidthEstimateBps: Long? = null
    @Volatile private var qoeVideoDecoder: String? = null
    // Phase 179 (FR-179-3) — counts every failed load whose URI matches PlaybackService.buildSubtracks()'
    // sideload pattern, retried or not; a nonzero count is itself the useful signal (something raced
    // Jellyfin's extraction this session), same "badge only when non-clean" philosophy as the other
    // counters here.
    @Volatile private var qoeSubtitleLoadErrors: Int = 0
    // Phase R220 (FR-R220-6) — bumped once per recovery-ladder firing (any rung) by
    // PlayerVideoSurface's detector; deliberately persists across the whole session like the other
    // qoe* counters (not the R218 per-item ones below), since "did this happen at all this session" is
    // the useful signal.
    @Volatile private var qoeVideoOutputRecoveries: Int = 0
    // R292 (FR-R292-11) — the rung that recovered last and its time; the two return counters.
    @Volatile private var qoeVideoOutputRecoveryRung: Int = 0
    @Volatile private var qoeVideoOutputRecoveryMs: Long = 0
    @Volatile private var qoeBackgroundReturns: Int = 0
    @Volatile private var qoeRestoredAfterRecreate: Int = 0
    // Rebuffer bookkeeping: only counted once the first frame has rendered (excludes initial buffering)
    // and only when the buffering wasn't itself caused by a seek (excludes user-initiated seeks) — see
    // the phase's FR-R216-4 doc.
    private var qoeFirstFrameRendered = false
    private var qoeRebufferStartMs: Long = -1L
    private var qoeSuppressNextBuffering = false

    // R218 (FR-R218-1) — fed by this same qoeListener's callbacks below, but distinct fields from the
    // qoe* ones above: those deliberately persist across a binge's episode-to-episode player reuse
    // (never reset), which is correct for cumulative QoE counters but wrong here — moment B (cold start)
    // needs to reappear for episode 2 even though qoeFirstFrameRendered is already true from episode 1.
    // These three reset in load() instead. @Volatile for the same cross-thread-visibility reason as the
    // qoe* fields (read from PlayerScreen's poll loop, written from Media3's analytics thread).
    @Volatile private var _hasRenderedFirstFrame = false
    @Volatile private var _isBuffering = false
    @Volatile private var _isSeeking = false

    private val qoeListener = object : AnalyticsListener {
        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
            qoeFirstFrameRendered = true
            _hasRenderedFirstFrame = true
        }
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            qoeDroppedFrames += droppedFrames
        }
        override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
            qoeBandwidthEstimateBps = bitrateEstimate
        }
        override fun onPositionDiscontinuity(
            eventTime: AnalyticsListener.EventTime,
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                qoeSuppressNextBuffering = true
                _isSeeking = true
            }
        }
        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> {
                    _isBuffering = true
                    if (qoeFirstFrameRendered && !qoeSuppressNextBuffering) {
                        qoeRebufferStartMs = eventTime.realtimeMs
                    }
                    qoeSuppressNextBuffering = false
                }
                Player.STATE_READY -> {
                    _isBuffering = false
                    _isSeeking = false
                    if (qoeRebufferStartMs >= 0) {
                        qoeRebufferCount++
                        qoeRebufferMs += (eventTime.realtimeMs - qoeRebufferStartMs).coerceAtLeast(0)
                        qoeRebufferStartMs = -1L
                    }
                }
                else -> {}
            }
        }
        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            qoeVideoDecoder = decoderName
        }
        // Phase 179 (FR-179-3) — fires for every failed load attempt, including ones SubtitleRetryingLoad-
        // ErrorHandlingPolicy will go on to retry; counts attempts, not just terminal failures, since
        // "how many times did this stumble" is itself diagnostic value here (see this field's own doc).
        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
            mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
            error: java.io.IOException,
            wasCanceled: Boolean,
        ) {
            if (loadEventInfo.uri.toString().contains("/Subtitles/")) qoeSubtitleLoadErrors++
        }
    }

    // R44: a MediaSession bound to the player so the OS routes hardware transport keys (Play/Pause/
    // Stop/FF/Rew/Next/Prev) to us and external controllers (Assistant/Bluetooth/Now-Playing) work.
    // ExoPlayer maps the standard session commands to play/pause/seek; the shared chrome stays the
    // source of truth for position polling.
    // R192: a plain nullable ref, not `by lazy` — Media3's MediaSession has no public `isActive`
    // setter (only `release()`), so "deactivate without releasing" is implemented as release-and-
    // recreate-on-demand instead of a flag toggle. `lazy` can't be reset, hence the manual ref.
    // TV-only (RaviloAppContext.isTelevision): the TV's session is meant to be visible/controllable
    // from a household member's phone; a phone's own playback must never be advertised the same way
    // to other devices, so the phone build never creates a session in the first place.
    private var mediaSessionRef: MediaSession? = null
    // R244 (FR-R244-10) — the attached caption view and the phone's chosen size multiplier.
    private var subtitleViewRef: SubtitleView? = null
    private var subtitleScale: Float = 1f
    // R292 — a session only ever wraps a LIVE engine: with none, nothing to advertise (bindEngine creates it).
    private fun ensureMediaSession(): MediaSession? {
        if (!RaviloAppContext.isTelevision) return null
        val e = engine ?: return null
        return mediaSessionRef ?: MediaSession.Builder(ctx, e).setId("ravilo-player").build().also { mediaSessionRef = it }
    }

    // R77: video geometry for automatic aspect-ratio correction in PlayerVideoSurface.
    private val _videoSize = MutableStateFlow(VideoSize.UNKNOWN)
    val videoSize: StateFlow<VideoSize> = _videoSize

    // R46: server-derived audio metadata (Jellyfin DisplayTitle, in container audio-stream order).
    private var audioMeta: List<AudioTrack> = emptyList()

    actual fun load(streamUrl: String, startPositionMs: Long, subtitles: List<SubTrack>, audio: List<AudioTrack>, title: String, subtitle: String?, artworkUrl: String?) {
        audioMeta = audio
        // R218 — a new item is its own cold start; see these fields' own doc for why they reset here
        // and the qoe* counters above deliberately don't.
        _hasRenderedFirstFrame = false
        _isBuffering = false
        _isSeeking = false
        val subConfigs = subtitles.mapNotNull { sub ->
            val url = sub.url ?: return@mapNotNull null
            val mime = when {
                url.endsWith(".vtt", ignoreCase = true) -> MimeTypes.TEXT_VTT
                url.endsWith(".srt", ignoreCase = true) -> MimeTypes.APPLICATION_SUBRIP
                url.endsWith(".ass", ignoreCase = true) || url.endsWith(".ssa", ignoreCase = true) -> MimeTypes.TEXT_SSA
                else -> MimeTypes.TEXT_VTT
            }
            val flags = when {
                sub.isDefault -> C.SELECTION_FLAG_DEFAULT
                sub.forced    -> C.SELECTION_FLAG_FORCED
                else           -> 0
            }
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
                .setMimeType(mime)
                .setLanguage(sub.language)
                .setLabel(sub.label)
                .setSelectionFlags(flags)
                .build()
        }
        // R192 — feeds the OS media session (TV only): title + the caller's own "S1 · E3"-style
        // kicker text as subtitle (Media3 has no separate numeric season/episode fields) + a
        // poster/still image. Harmless to set even when no session is ever created (phone).
        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setArtworkUri(artworkUrl?.let { Uri.parse(it) })
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(streamUrl)
            .setSubtitleConfigurations(subConfigs)
            .setMediaMetadata(mediaMetadata)
            .build()
        val p = exo()   // R292 — builds and binds a fresh engine after a background release
        p.setMediaItem(mediaItem)
        p.seekTo(startPositionMs)
        p.prepare()
        ensureMediaSession() // touch/recreate the session so it's active for the OS while this item plays (R44)
    }

    // R292 — the SurfaceView PlayerVideoSurface currently owns, so a rebuilt engine is bound to it from
    // bindEngine(); every caller that (re)binds a surface goes through this setter.
    @Volatile private var currentSurfaceView: SurfaceView? = null

    fun setVideoSurfaceView(sv: SurfaceView) {
        currentSurfaceView = sv
        engine?.setVideoSurfaceView(sv)
    }

    /** Once a SurfaceView is torn down it must never be the one a later engine is bound to. */
    fun forgetVideoSurfaceView(sv: SurfaceView) {
        if (currentSurfaceView === sv) currentSurfaceView = null
    }

    /** R55 — attach a SubtitleView so ExoPlayer's text renderer can forward cues to the UI. */
    fun setSubtitleView(view: SubtitleView) {
        // R110: white text + a GENTLE OUTLINE (was EDGE_TYPE_DROP_SHADOW). A drop shadow only darkens
        // the bottom-right offset of each glyph, so white letters wash out on bright scenes; an outline
        // hugs every glyph on all sides so captions read on any background. The edge is ~80% black, not
        // solid — defined but soft/easy on the eyes, not a heavy hard border. ExoPlayer hardcodes the
        // outline to ~2dp (androidx/media#1834).
        view.setStyle(CaptionStyleCompat(
            Color.WHITE,
            Color.TRANSPARENT,
            Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            Color.argb(204, 0, 0, 0), // ~80% black — gentle outline, not a hard solid border
            null,
        ))
        applySubtitleSize(view)
        // R292 (FR-R292-10) — the cue listener is bound once per engine (bindEngine) and reads this ref at
        // callback time; no listener is added here any more.
        subtitleViewRef = view
    }

    // R292 (FR-R292-4) — no engine, no-op: a released engine's stream was stopped and is never touched again.
    actual fun play() { engine?.play() }
    actual fun pause() { engine?.pause() }
    actual fun seekTo(positionMs: Long) { engine?.seekTo(positionMs) }

    actual fun selectAudioTrack(index: Int) {
        val exo = engine ?: return
        val tracks = exo.currentTracks
        // R291 (FR-R291-2) — a composed master names every rendition `a{position} …` (the backend's
        // composeMaster), because Media3 lists the muxed audio first and the audio-only renditions after
        // it, not in the ticket's order. A rendition is found by that name; anything else (a file's own
        // tracks on direct play) keeps the ordinal match below.
        for (i in 0 until tracks.groups.size) {
            val group = tracks.groups[i]
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            if ((0 until group.length).any { t -> group.getTrackFormat(t).label?.startsWith("a$index ") == true || group.getTrackFormat(t).label == "a$index" }) {
                exo.trackSelectionParameters = exo.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                    .build()
                return
            }
        }
        var audioGroupIdx = 0
        for (i in 0 until tracks.groups.size) {
            val group = tracks.groups[i]
            if (group.type == C.TRACK_TYPE_AUDIO) {
                if (audioGroupIdx == index) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters
                        .buildUpon()
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                        .build()
                    return
                }
                audioGroupIdx++
            }
        }
    }

    actual fun selectSubtitleTrack(index: Int) {
        val exo = engine ?: return
        if (index < 0) {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            return
        }
        val tracks = exo.currentTracks
        var textIdx = 0
        for (i in 0 until tracks.groups.size) {
            val group = tracks.groups[i]
            if (group.type == C.TRACK_TYPE_TEXT) {
                if (textIdx == index) {
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                        .build()
                    return
                }
                textIdx++
            }
        }
    }

    actual fun release() {
        releaseEngine()
        releasedForBackground = false   // the screen is gone; nothing will rebuild this
    }

    // R192: release (not just pause) on backgrounding, so the session stops being advertised to
    // Android's cross-device media surfacing; recreated on demand when foregrounded/reactivated. R292:
    // only ever around a LIVE engine — after releaseEngine() there is nothing to advertise until the
    // next load builds one (bindEngine creates the session then). A `false` on an already-released
    // session is a no-op.
    actual fun setSessionActive(active: Boolean) {
        if (active) {
            ensureMediaSession()
        } else {
            mediaSessionRef?.release()
            mediaSessionRef = null
        }
    }

    // No-op — the video surface is already in-scene via a normal (non-Z-order-on-top) SurfaceView
    // inside the FrameLayout; nothing to swap z-order with.
    actual fun setChromeVisible(visible: Boolean) {}

    actual fun setSubtitleScale(scale: Float) {
        subtitleScale = scale.coerceIn(0.5f, 2f)
        subtitleViewRef?.let { applySubtitleSize(it) }
    }

    // R300 (FR-R300-1) — the height captions are sized against: the video box's, not the window's. The
    // SubtitleView spans the whole window (so its inset measures from the true screen edge, see
    // PlayerVideoSurface), and Media3's fractional size is a fraction of the VIEW — in portrait on a
    // phone that view is ~2.2× taller than the picture, so captions came out ~3× too big and covered
    // the rail. 0 means "unknown yet": fall back to the view's own height, as before.
    private var subtitleBasePx: Int = 0
    fun setSubtitleBaseHeightPx(px: Int) {
        if (px == subtitleBasePx) return
        subtitleBasePx = px
        subtitleViewRef?.let { applySubtitleSize(it) }
    }
    private fun applySubtitleSize(view: SubtitleView) {
        val fraction = SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 0.9f * subtitleScale
        if (subtitleBasePx > 0) view.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fraction * subtitleBasePx)
        else view.setFractionalTextSize(fraction)
    }

    actual val positionMs: Long get() = engine?.currentPosition?.coerceAtLeast(0) ?: 0L
    actual val durationMs: Long get() = engine?.duration?.let { if (it == C.TIME_UNSET) 0L else it.coerceAtLeast(0) } ?: 0L
    actual val bufferedMs: Long get() = engine?.bufferedPosition?.coerceAtLeast(0) ?: 0L
    actual val isPlaying: Boolean get() = engine?.isPlaying == true
    actual val isEnded: Boolean get() = engine?.playbackState == Player.STATE_ENDED
    actual val hasRenderedFirstFrame: Boolean get() = _hasRenderedFirstFrame
    actual val isBuffering: Boolean get() = _isBuffering
    actual val isSeeking: Boolean get() = _isSeeking

    actual val audioTracks: List<PlayerAudioTrack>
        get() {
            val result = mutableListOf<PlayerAudioTrack>()
            val tracks = engine?.currentTracks ?: return result
            var idx = 0
            for (i in 0 until tracks.groups.size) {
                val group = tracks.groups[i]
                if (group.type == C.TRACK_TYPE_AUDIO) {
                    val format = group.mediaTrackGroup.getFormat(0)
                    // Prefer the server-derived label (Jellyfin DisplayTitle), mapped by audio-stream
                    // order; fall back to the container track name, then a humanized language, then
                    // the raw code (R46). If stream counts differ, the container label still applies.
                    val meta = audioMeta.getOrNull(idx)
                    val label = meta?.label?.takeIf { it.isNotBlank() }
                        ?: format.label
                        ?: languageName(format.language)
                        ?: format.language?.uppercase()
                        ?: "Track ${idx + 1}"
                    // R180 — prefer the server-derived meta (Jellyfin MediaStreams); fall back to the
                    // container format's own channel count when meta lacks it.
                    val channels = meta?.channels ?: format.channelCount.takeIf { it > 0 }
                    result += PlayerAudioTrack(idx, label, meta?.language ?: format.language, channels, meta?.isDefault ?: false)
                    idx++
                }
            }
            return result
        }

    actual val subtitleTracks: List<PlayerSubtitleTrack>
        get() {
            val result = mutableListOf<PlayerSubtitleTrack>()
            val tracks = engine?.currentTracks ?: return result
            var idx = 0
            for (i in 0 until tracks.groups.size) {
                val group = tracks.groups[i]
                if (group.type == C.TRACK_TYPE_TEXT) {
                    val format = group.mediaTrackGroup.getFormat(0)
                    val label = format.label ?: languageName(format.language) ?: format.language?.uppercase() ?: "Track ${idx + 1}"
                    val forced = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0
                    val def = (format.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0
                    // R56: mark VobSub/DVDSub image subs as "embed" so the picker can show them
                    // without a VTT URL; PGS is exposed separately as encode subs via the server list.
                    val mime = format.sampleMimeType?.lowercase()
                    val deliveryMethod = when {
                        mime == "application/vobsub" || mime == "application/dvd-subtitle" -> "embed"
                        else -> "external"
                    }
                    result += PlayerSubtitleTrack(idx, label, format.language, forced, def, deliveryMethod)
                    idx++
                }
            }
            return result
        }

    actual fun qoeSnapshot(): PlayerQoeSnapshot = PlayerQoeSnapshot(
        droppedFrames = qoeDroppedFrames,
        rebufferCount = qoeRebufferCount,
        rebufferMs = qoeRebufferMs,
        bandwidthEstimateBps = qoeBandwidthEstimateBps,
        videoDecoder = qoeVideoDecoder,
        subtitleLoadErrors = qoeSubtitleLoadErrors,
        videoOutputRecoveries = qoeVideoOutputRecoveries,
        videoOutputRecoveryRung = qoeVideoOutputRecoveryRung,
        videoOutputRecoveryMs = qoeVideoOutputRecoveryMs,
        backgroundReturns = qoeBackgroundReturns,
        restoredAfterRecreate = qoeRestoredAfterRecreate,
    )

    /**
     * Phase R220 (FR-R220-2) — a real frame COUNT, not a boolean, so the "playing but not rendering"
     * detector in [PlayerVideoSurface] can tell "no new frames since I last polled" from "genuinely
     * nothing rendered yet" (the latter is [hasRenderedFirstFrame]'s job, unaffected by this). 0 before
     * a video decoder exists yet or if the counters are ever unavailable — never throws.
     * `ensureUpdated()` is required by [DecoderCounters]'s own contract before a cross-thread read.
     */
    fun renderedVideoFrameCount(): Long = try {
        engine?.videoDecoderCounters?.let { counters ->
            counters.ensureUpdated()
            counters.renderedOutputBufferCount.toLong()
        } ?: 0L
    } catch (e: Exception) {
        0L
    }

    /** Used directly by [PlayerVideoSurface]'s recovery-ladder rung 1 (detach immediately followed by
     *  re-[setVideoSurfaceView] on the same instance). */
    fun clearVideoSurfaceView(sv: SurfaceView) { engine?.clearVideoSurfaceView(sv) }

    /** R292 (FR-R292-11, R220 FR-R220-6 made real) — called by [PlayerVideoSurface]'s recovery ladder every
     *  time it fires: [rung] is the one that recovered (0 = exhausted into rung 4), [ms] the ladder's time
     *  from its start to the first new frame. */
    fun recordVideoOutputRecovery(rung: Int, ms: Long) {
        qoeVideoOutputRecoveries++
        qoeVideoOutputRecoveryRung = rung
        qoeVideoOutputRecoveryMs = ms
    }
}

/**
 * Phase 179 (FR-179-2) — widens the retry allowance for a sideloaded text-subtitle load (the
 * `.../Subtitles/{index}/0/Stream.vtt` URL PlaybackService.buildSubtracks() builds) only. Every other
 * load type — video/audio HLS segments, manifests, everything else — delegates straight through to
 * Media3's own [DefaultLoadErrorHandlingPolicy], completely unchanged: this must never make a genuinely
 * dead video/audio connection wait longer to fail, only give a subtitle sideload — which a live incident
 * showed racing Jellyfin's own concurrent ffmpeg extraction of the same source file (see phase-179's
 * Root cause §4) — more patience than Media3's plain default (one attempt, no retry) gave it.
 */
private class SubtitleRetryingLoadErrorHandlingPolicy(
    private val default: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy =
        androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(),
) : androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy {

    private fun isSubtitleLoad(loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Boolean =
        loadErrorInfo.loadEventInfo.uri.toString().contains("/Subtitles/")

    override fun getFallbackSelectionFor(
        fallbackOptions: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo,
    ) = default.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)

    override fun getRetryDelayMsFor(
        loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo,
    ): Long {
        if (isSubtitleLoad(loadErrorInfo) && loadErrorInfo.errorCount <= SUBTITLE_MAX_RETRIES) {
            return SUBTITLE_RETRY_DELAY_MS
        }
        return default.getRetryDelayMsFor(loadErrorInfo)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = default.getMinimumLoadableRetryCount(dataType)

    private companion object {
        const val SUBTITLE_MAX_RETRIES = 2
        const val SUBTITLE_RETRY_DELAY_MS = 3_000L
    }
}

/** R216 (FR-R216-3) — raised from DefaultLoadControl's stock 5s so a recovered stall resumes with a
 *  real cushion instead of re-stalling seconds later; see this file's `setBufferDurationsMs` call site. */
private const val QOE_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 10_000
