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
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import dev.jellystructure.ravilo.ui.RaviloAppContext
import dev.jellystructure.ravilo.ui.raviloBaseUrl
import dev.jellystructure.ravilo.ui.TokenStore
import dev.jellystructure.ravilo.ui.screens.CLIMB_BLOCK_AFTER_RECOVERY_MS
import dev.jellystructure.ravilo.ui.screens.IN_PLACE_CAP_AFTER_MS
import dev.jellystructure.ravilo.ui.screens.MultiTokenStore
import dev.jellystructure.ravilo.ui.screens.rungBelow
import dev.jellystructure.ravilo.ui.screens.rungWords
import dev.jellystructure.ravilo.ui.screens.videoFamilyOfMime
import dev.jellystructure.shared.tv.AudioTrack
import dev.jellystructure.shared.tv.SubTrack
import kotlin.concurrent.Volatile

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

    // R354 (FR-R354-6) — the remote's output level, re-applied to a rebuilt engine.
    @Volatile private var outputVolume = 1f

    // R379 (FR-R379-2) — jellyfin-android's fallback: engines prefer the FFmpeg extension over the device's own
    // decoders once a platform decoder has failed, for the rest of this player's life (the player screen).
    @Volatile private var preferExtensions = false

    private fun exo(): ExoPlayer = engine ?: buildEngine().also { built ->
        engine = built
        bindEngine(built)
        built.volume = outputVolume
        if (releasedForBackground) {
            releasedForBackground = false
            qoeBackgroundReturns++
            synchronized(qoeLock) { qoeCounter.engineRebuilt() }   // R381 (FR-R381-2) — the rebuilt engine's start is not a stall
        }
    }

    private fun buildEngine(): ExoPlayer {
        // R56: Media3's MatroskaExtractor already parses embedded VobSub/DVDSub and PGS tracks.
        // R294: the Matroska extractor is swapped for :ravilo-player's patched copy, which follows
        // SeekHead to a Tracks element stored after the first Cluster instead of reading the file to
        // the end; every other extractor is Media3's own.
        // DEB-2205 (FR-6) — no text subtitle is a Media3 load any more: the app fetches the shown track itself
        // ([AppFedSubtitles]), so the 179 load-error policy for sideloaded subtitles has nothing left to govern.
        val builder = ExoPlayer.Builder(ctx, mediaSourceFactory(null))
        RaviloPlayerEngine.renderersFactoryProvider?.invoke(ctx, preferExtensions)?.let { builder.setRenderersFactory(it) }
        // R216 (FR-R216-3) — an explicit LoadControl instead of inheriting DefaultLoadControl's stock
        // bufferForPlaybackAfterRebufferMs: on a link that dips mid-playback, resuming on a thin buffer
        // turns one stall into a train of them. Only that one value is raised — min/max buffer and
        // bufferForPlaybackMs (start latency, R211/R212) are left at their defaults on purpose: the
        // reported TV's dalvik.vm.heapgrowthlimit (192 MB) leaves less headroom above Media3's own
        // 128 MB default video buffer than the time-based defaults suggest (see the phase's own doc) —
        // enlarging buffer SIZE needs FR-R216-4's telemetry to validate first, not a blind bump here.
        // 308 (FR-308-3) — the app-wide meter, seeded per stream with what this device measured on its recent plays.
        builder.setBandwidthMeter(bandwidthMeter)
        // 308 (FR-308-2) — step down early. Media3's default only steps down once less than 25 s is buffered, and a
        // lower variant is a NEW Jellyfin encode that took ~17 s to deliver its first segment (Pixel 9 Pro,
        // 2026-10-06: a 5 Mbps throttle drove a 52 s buffer down to 5 s before the 1.5 Mbps rung arrived). Stepping
        // down while up to 45 s is buffered leaves the new encode time to start. Stepping up waits for 25 s of buffer
        // (more than a new encode needs to start) and keeps everything already buffered (50 s, the whole buffer):
        // with the defaults (10 s, keep 25 s) a step-up threw away 30 s and the cold encode drained it to 1.4 s.
        // 309 (FR-309-4/-5) — bounded by LadderRules: one rung up at a time (after 30 s buffered, the stock 10 s on our
        // own encoder), and down as soon as the buffer is under 20 s and falling.
        builder.setTrackSelector(
            androidx.media3.exoplayer.trackselection.DefaultTrackSelector(ctx, ClimbingTrackSelection.Factory())
        )
        // DEB-2205 (FR-4) — a direct play's buffer is sized in seconds (time over size), bounded by the memory class; every other
        // stream keeps the byte bound it always had. See [TimeFirstLoadControl].
        builder.setLoadControl(TimeFirstLoadControl(deviceMemoryClassMb(), { PlayerLadderHints.directStreamBps }, QOE_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS))
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

    /** The engine's media sources: R294's extractors; [dataSourceFactory] null = the network
     *  (Media3's default), else R381's prefetch cache in front of it. */
    private fun mediaSourceFactory(dataSourceFactory: androidx.media3.datasource.DataSource.Factory?): androidx.media3.exoplayer.source.DefaultMediaSourceFactory {
        val extractors = RaviloPlayerEngine.extractorsFactoryProvider?.invoke()
        return when {
            dataSourceFactory != null && extractors != null -> androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory, extractors)
            dataSourceFactory != null -> androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
            extractors != null -> androidx.media3.exoplayer.source.DefaultMediaSourceFactory(ctx, extractors)
            else -> androidx.media3.exoplayer.source.DefaultMediaSourceFactory(ctx)
        }
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
        player.addAnalyticsListener(qoeFeed)
        // R291 — a debuggable build logs every load (tag R291); a release build binds nothing extra.
        if ((ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            player.addAnalyticsListener(DebugLoadLogger { player.currentPosition to player.bufferedPosition })
            bindDebugFreeze()   // DEB-2205 — QA's way to stop the picture
        }
        player.addListener(cueListener)
        if (shownTimeline != null) mainHandler.post { applyShownOnMain() }   // DEB-2205 — a rebuilt engine keeps showing the app-fed track
        currentSurfaceView?.let { player.setVideoSurfaceView(it) }
        if (RaviloAppContext.isTelevision && mediaSessionRef == null) {
            mediaSessionRef = buildSession(player)
        }
    }

    private val videoSizeListener = object : Player.Listener {
        override fun onVideoSizeChanged(size: VideoSize) { _videoSize.value = size }
    }

    private val cueListener = object : Player.Listener {
        override fun onCues(cueGroup: CueGroup) {
            if (shownTimeline != null) return   // DEB-2205 — an app-fed track is shown: Media3's text type is off and must not fight it
            val view = subtitleViewRef ?: return
            val cleaned = cueGroup.cues.map { cue ->
                val raw = cue.text?.toString() ?: return@map cue
                val clean = cleanCueText(raw)
                if (clean == raw) cue else cue.buildUpon().setText(clean).build()
            }
            view.setCues(cleaned)
        }
    }

    /** DEB-2205 (FR-6) — what the engine was handed: the test that no subtitle is part of its preparation reads this. */
    internal val loadedMediaItem: MediaItem? get() = engine?.currentMediaItem

    /** R292 (FR-R292-1) — see the expect's doc. Safe to call with no engine. */
    actual fun releaseEngine() {
        NextPrefetch.discard()   // R381 (dev review item 9) — a background release drops the preload
        mediaSessionRef?.release()
        mediaSessionRef = null
        val e = engine ?: return
        engine = null
        unbindDebugFreeze()
        mainHandler.removeCallbacks(inPlaceCap)
        mainHandler.removeCallbacks(liftCap)
        releasedForBackground = true
        _hasRenderedFirstFrame = false
        _isBuffering = false
        _playbackFailed = false
        _isSeeking = false
        _videoSize.value = VideoSize.UNKNOWN
        e.release()
    }

    actual fun recordRestoredAfterRecreate() { qoeRestoredAfterRecreate++ }

    /** R379 (FR-R379-2) — the same item, position, play state and track choices on a new engine built with
     *  [preferExtensions] set. A released or replaced engine (the screen left, the next item loaded) is left alone. */
    private fun restartPreferringExtensions(failed: ExoPlayer) {
        if (engine !== failed) return
        val item = failed.currentMediaItem ?: run { _playbackFailed = true; return }
        val positionMs = failed.currentPosition
        val playWhenReady = failed.playWhenReady
        val tracks = failed.trackSelectionParameters
        mediaSessionRef?.release()
        mediaSessionRef = null
        engine = null
        _hasRenderedFirstFrame = false
        _isBuffering = false
        _isSeeking = false
        failed.release()
        synchronized(qoeLock) { qoeCounter.engineRebuilt() }   // R381 (FR-R381-2) — R379's restart is not a stall
        val p = exo()
        p.trackSelectionParameters = tracks
        p.setMediaItem(item)
        p.seekTo(positionMs)
        p.playWhenReady = playWhenReady
        p.prepare()
        ensureMediaSession()
    }

    // 308 (FR-308-3) — see [SeededBandwidthMeter]: one for the app, over Media3's app-wide meter.
    private val bandwidthMeter: SeededBandwidthMeter get() = SeededBandwidthMeter.shared(ctx)

    actual fun seedBandwidthEstimate(bps: Long?) { bandwidthMeter.seed(bps) }

    // 308 (FR-308-5) — the video variant playing (its BANDWIDTH and height) and the switches between variants.
    @Volatile private var qoeVariantBps: Long? = null
    @Volatile private var qoeVariantHeight: Int? = null
    // DEB-2205 (FR-5) — the playing variant's Media3 `Format.bitrate` (what setMaxVideoBitrate compares) and its video family.
    @Volatile private var qoeVariantFormatBps: Long? = null
    @Volatile private var qoeVariantCodec: String? = null
    @Volatile private var qoeVariantDown: Int = 0
    @Volatile private var qoeVariantUp: Int = 0

    // R216 (FR-R216-4) — playback-quality counters, read by [qoeSnapshot]. R381 (FR-R381-1): per ITEM since R381 (reset
    // by [beginQoeItem] when a binge moves to the next item); they used to be the session's running totals.
    // @Volatile: read from PlayerStore's coroutine, written from whichever thread Media3 dispatches
    // analytics events on — plain field writes here are always whole-value replacements, never a
    // read-modify-write race (each field is only ever touched inside the single-threaded qoeListener
    // callbacks Media3 itself serializes), so @Volatile alone (no mutex) is sufficient for cross-thread
    // visibility, matching this file's other cross-thread state (_videoSize).
    @Volatile private var qoeDroppedFrames: Int = 0
    // DEB-2205 (FR-3) — frames drawn by video decoders that have since been released this item (the live decoder's are added on read).
    @Volatile private var qoeRenderedBase: Long = 0
    @Volatile private var qoeBandwidthEstimateBps: Long? = null
    // 309 (FR-309-13) — the transfers behind the estimate, this item's.
    @Volatile private var qoeBandwidthSamples: Int = 0
    @Volatile private var qoeBandwidthBytes: Long = 0
    @Volatile private var qoeVideoDecoder: String? = null
    // R379 — the audio decoder (Media3's onAudioDecoderInitialized): the engine's, like the video one, so kept across
    // items; a rebuild with FFmpeg preferred (FR-R379-2) reports its new decoder. Null = passthrough or none yet.
    @Volatile private var qoeAudioDecoder: String? = null
    // Phase 179 (FR-179-3) — counts every failed load whose URI matches PlaybackService.buildSubtracks()'
    // sideload pattern, retried or not; a nonzero count is itself the useful signal (something raced
    // Jellyfin's extraction this session), same "badge only when non-clean" philosophy as the other
    // counters here.
    @Volatile private var qoeSubtitleLoadErrors: Int = 0
    // Phase R220 (FR-R220-6) — bumped once per recovery-ladder firing (any rung) by
    // PlayerVideoSurface's detector; per item since R381, like the other qoe* counters.
    @Volatile private var qoeVideoOutputRecoveries: Int = 0
    // R292 (FR-R292-11) — the rung that recovered last and its time; the two return counters.
    @Volatile private var qoeVideoOutputRecoveryRung: Int = 0
    @Volatile private var qoeVideoOutputRecoveryMs: Long = 0
    @Volatile private var qoeBackgroundReturns: Int = 0
    @Volatile private var qoeRestoredAfterRecreate: Int = 0
    // R381 — every qoe* counter above is now THIS ITEM's (reset by [beginQoeItem] for a new item, kept across a
    // restream of the same item); stalls are counted by the shared [QoeCounter]: after this load's first frame, and
    // never for a seek, a track switch, a variant switch, an engine rebuild or a recovery (each counted by name).
    // Guarded by [qoeLock]: Media3 calls the listener on its looper, the session store reads the snapshot elsewhere.
    private val qoeLock = Any()
    private val qoeCounter = QoeCounter()
    // R381 — Media3's first frame, seeks, playback state and pauses reach [qoeCounter] through this; the rest of the
    // counter's events (variant, track switch, engine rebuild) are told to it where they happen below.
    private val qoeFeed = QoeCounterFeed(
        qoeCounter, qoeLock,
        positionOf = { engine?.currentPosition },
        bufferedAheadOf = { engine?.let { it.bufferedPosition - it.currentPosition } ?: 0L },
        variantBpsOf = { qoeVariantBps },
        variantHeightOf = { qoeVariantHeight },
        pendingSubtitlesOf = { appFed.pendingLoads },   // DEB-2205 (FR-8)
        onStallBegan = { mainHandler.postDelayed(inPlaceCap, IN_PLACE_CAP_AFTER_MS) },   // DEB-2205 (FR-5.1)
    )

    // R218 (FR-R218-1) — fed by this same qoeListener's callbacks below, but distinct fields from the
    // qoe* ones above: these are per LOAD (moment B, the cold start, reappears for every stream), the qoe* ones
    // per item (R381). These reset in load(). @Volatile for the same cross-thread-visibility reason as the
    // qoe* fields (read from PlayerScreen's poll loop, written from Media3's analytics thread).
    @Volatile private var _hasRenderedFirstFrame = false
    @Volatile private var _isBuffering = false
    // R306 (FR-R306-3) — a fatal engine error on THIS load; reset by every load and engine release.
    @Volatile private var _playbackFailed = false
    @Volatile private var _isSeeking = false

    private val qoeListener = object : AnalyticsListener {
        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
            _hasRenderedFirstFrame = true
        }
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            qoeDroppedFrames += droppedFrames
        }
        // DEB-2205 (FR-3) — a decoder going away (a new stream, a re-init) keeps the frames it drew in the item's total.
        override fun onVideoDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: androidx.media3.exoplayer.DecoderCounters) {
            decoderCounters.ensureUpdated()
            qoeRenderedBase += decoderCounters.renderedOutputBufferCount
        }
        override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
            qoeBandwidthEstimateBps = bitrateEstimate
            // 309a0 / FR-309-13 — what the estimate rests on: this item's transfers and bytes (each callback is one
            // completed transfer's worth of samples), so the backend can tell a measurement from Media3's guess.
            qoeBandwidthSamples += 1
            qoeBandwidthBytes += totalBytesLoaded
        }
        override fun onPositionDiscontinuity(
            eventTime: AnalyticsListener.EventTime,
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) _isSeeking = true
        }
        override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: androidx.media3.common.PlaybackException) {
            // R379 (FR-R379-2) — a platform decoder failed: once, restart the item on an engine that prefers FFmpeg
            // instead of reporting the failure. Posted, so the failed engine is released outside its own callback.
            val failed = engine
            if (!preferExtensions && failed != null &&
                error.cause is androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
            ) {
                preferExtensions = true
                android.os.Handler(failed.applicationLooper).post { restartPreferringExtensions(failed) }
                return
            }
            _playbackFailed = true
        }
        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> {
                    _isBuffering = true
                }
                Player.STATE_READY -> {
                    _isBuffering = false
                    _isSeeking = false
                }
            }
        }
        // 308 (FR-308-5) — a new video variant reached the playhead: an HLS variant's format carries the master's
        // BANDWIDTH (peak) and RESOLUTION. A muxed variant is reported as the DEFAULT track type, a demuxed one as VIDEO;
        // an audio rendition never counts.
        override fun onDownstreamFormatChanged(eventTime: AnalyticsListener.EventTime, mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData) {
            val f = mediaLoadData.trackFormat ?: return
            val video = mediaLoadData.trackType == C.TRACK_TYPE_VIDEO || (mediaLoadData.trackType == C.TRACK_TYPE_DEFAULT && f.height > 0)
            if (!video) return
            // DEB-2205 (FR-3) — a direct-played Matroska video track has no bitrate to report, but its codec is what a decode failure names.
            qoeVariantCodec = videoFamilyOfMime(f.codecs?.let { androidx.media3.common.MimeTypes.getVideoMediaMimeType(it) } ?: f.sampleMimeType)
            val bps = (f.peakBitrate.takeIf { it > 0 } ?: f.bitrate.takeIf { it > 0 })?.toLong() ?: return
            val was = qoeVariantBps
            if (was != null && bps != was) {
                if (bps < was) qoeVariantDown++ else qoeVariantUp++
                synchronized(qoeLock) { qoeCounter.variantSwitched(eventTime.realtimeMs) }   // R381 — a wait just after it is the switch
            }
            qoeVariantBps = bps
            qoeVariantHeight = f.height.takeIf { it > 0 }
            qoeVariantFormatBps = (f.bitrate.takeIf { it > 0 } ?: f.peakBitrate.takeIf { it > 0 })?.toLong()
        }
        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            qoeVideoDecoder = decoderName
        }
        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            qoeAudioDecoder = decoderName
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
        // R266 (dev review item 4) — ONE session: R44's own is the one Cast Connect's MediaManager drives (no second,
        // cast-only session). Every create/release goes through here, so the receiver always holds the live one.
        set(value) { if (field !== value) { val old = field; field = value; TvPlayerSessionHooks.sessionChanged(old, value) } }
    // R244 (FR-R244-10) — the attached caption view and the phone's chosen size multiplier.
    private var subtitleViewRef: SubtitleView? = null
    private var subtitleScale: Float = 1f

    // DEB-2205 (FR-6) — the ticket's URL-bearing text subtitles are NOT on the media item: the app fetches the one
    // being shown (off the player's thread, [SubtitleWorkers]) and draws its cues itself. Rows follow Media3's own
    // text groups, in ticket order, so the indices the picker sees are what they were with sideloaded configs.
    private var appFedTickets: List<SubTrack> = emptyList()
    private val subtitleWorkers = SubtitleWorkers()
    private val appFed = AppFedSubtitles(
        OkHttpSubtitleSource(OkHttpSubtitleSource.deriveFrom(RaviloAppContext.okHttp), bearerFor = { url ->
            SubtitleRequest.bearer(url, raviloBaseUrl(), MultiTokenStore.getActive()?.deviceToken ?: TokenStore.get())
        }),
        subtitleWorkers,
        object : AppFedSubtitles.Listener {
            override fun onShown(timeline: CueTimeline?) {
                shownTimeline = timeline
                mainHandler.post { applyShownOnMain() }
            }
            override fun onAttemptFailed(url: String) {
                synchronized(qoeLock) { qoeSubtitleLoadErrors++ }   // 179 (FR-179-3): the app's own fetches count too
            }
        },
    )
    /** The app-fed track being drawn (null = Media3's own cues, or none). Written by [appFed]'s listener, read on main. */
    @Volatile private var shownTimeline: CueTimeline? = null
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private var lastShownCues: List<TextCue>? = null   // main thread only: what the SubtitleView holds from us

    /** Draws the app-fed cues at the playhead: ~150 ms poll, [SubtitleView.setCues] only when the active set changes. */
    private val cuePoll = object : Runnable {
        override fun run() {
            val timeline = shownTimeline ?: return
            val e = engine ?: return   // no engine: [bindEngine] restarts the poll on the rebuilt one
            val view = subtitleViewRef
            if (view != null) {
                val active = timeline.activeAt(e.currentPosition)
                if (active != lastShownCues) {
                    lastShownCues = active
                    view.setCues(active.map { androidx.media3.common.text.Cue.Builder().setText(it.text).build() })
                }
            }
            mainHandler.postDelayed(this, CUE_POLL_MS)
        }
    }

    private fun applyShownOnMain() {
        mainHandler.removeCallbacks(cuePoll)
        if (shownTimeline == null) {
            if (lastShownCues != null) { subtitleViewRef?.setCues(emptyList()); lastShownCues = null }
            return
        }
        lastShownCues = null   // whatever the view holds is another track's: the next poll pushes this one
        cuePoll.run()
    }
    // R292 (FR-R292-14, DEB-569) — the one place a session is built: it refuses a play for a short window after a
    // standby return (a sender's or the Cast SDK's resume would otherwise start the film FR-R292-8a left paused).
    private fun buildSession(player: ExoPlayer): MediaSession =
        MediaSession.Builder(ctx, player).setId("ravilo-player").setCallback(object : MediaSession.Callback {
            override fun onPlayerCommandRequest(session: MediaSession, controller: MediaSession.ControllerInfo, playerCommand: Int): Int {
                if (playerCommand == Player.COMMAND_PLAY_PAUSE && !session.player.playWhenReady &&
                    TvPlayerSessionHooks.standbyHold.refusesPlay(android.os.SystemClock.elapsedRealtime())) {
                    android.util.Log.i("R292", "FR-R292-14: a play from ${controller.packageName} refused, the film came back from standby")
                    return SessionError.ERROR_NOT_SUPPORTED
                }
                return SessionResult.RESULT_SUCCESS
            }
        }).build()
    // R292 — a session only ever wraps a LIVE engine: with none, nothing to advertise (bindEngine creates it).
    private fun ensureMediaSession(): MediaSession? {
        if (!RaviloAppContext.isTelevision) return null
        val e = engine ?: return null
        return mediaSessionRef ?: buildSession(e).also { mediaSessionRef = it }
    }

    // R77: video geometry for automatic aspect-ratio correction in PlayerVideoSurface.
    private val _videoSize = MutableStateFlow(VideoSize.UNKNOWN)
    val videoSize: StateFlow<VideoSize> = _videoSize

    // R46: server-derived audio metadata (Jellyfin DisplayTitle, in container audio-stream order).
    private var audioMeta: List<AudioTrack> = emptyList()

    actual fun load(streamUrl: String, startPositionMs: Long, subtitles: List<SubTrack>, audio: List<AudioTrack>, title: String, subtitle: String?, artworkUrl: String?) {
        activeRaviloPlayer = this
        audioMeta = audio
        // R218 — a new stream is its own cold start (per load); the qoe* counters are per item (R381, [beginQoeItem]).
        _hasRenderedFirstFrame = false
        _isBuffering = false
        _playbackFailed = false
        _isSeeking = false
        // 308 — a new stream's first variant is not a switch.
        qoeVariantBps = null
        qoeVariantHeight = null
        qoeVariantFormatBps = null
        qoeVariantCodec = null
        // R381 — this stream's first wait is its start; 309 — and the item's first load is timed to its first frame
        // (elapsedRealtime: the clock Media3's eventTime.realtimeMs is on).
        synchronized(qoeLock) { qoeCounter.load(android.os.SystemClock.elapsedRealtime()) }
        // DEB-2205 (FR-6) — NO subtitle is part of the media item: its preparation never waits on a subtitle URL.
        // The ticket's URL-bearing text tracks are kept, in ticket order, for [AppFedSubtitles] to fetch the SHOWN one.
        val base = raviloBaseUrl()
        appFedTickets = subtitles.filter { it.url != null }
        appFed.reset(appFedTickets.map { SubtitleRequest.absolute(it.url!!, base) ?: it.url!! })
        // R192 — feeds the OS media session (TV only): title + the caller's own "S1 · E3"-style
        // kicker text as subtitle (Media3 has no separate numeric season/episode fields) + a
        // poster/still image. Harmless to set even when no session is ever created (phone).
        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setArtworkUri(artworkUrl?.let { Uri.parse(it) })
            .build()
        // R381 — the next episode a binge prefetched at the credits is read from that cache; any other stream drops it.
        val prefetchKey = NextPrefetch.cacheKeyFor(streamUrl).also { if (it == null) NextPrefetch.keepOnlyFor(streamUrl) }
        val mediaItem = MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaMetadata(mediaMetadata)
            .apply { if (prefetchKey != null) setCustomCacheKey(prefetchKey) }
            .build()
        val p = exo()   // R292 — builds and binds a fresh engine after a background release
        clearRecoveryState(p)   // DEB-2205 — a new stream starts uncapped, and a QA freeze does not follow it
        // Phase 314b (FR-314-6) — a `.mka` sidecar beside the video, merged in: its audio follows the container's own
        // tracks, which is the order the ticket lists them in (so the ordinal mapping below still holds).
        val sidecars = audio.mapNotNull { it.externalUrl?.takeIf { _ -> it.external } }
        val sidecarSources = sidecars.map { url ->
            androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(androidx.media3.datasource.DefaultDataSource.Factory(ctx))
                .createMediaSource(MediaItem.fromUri(url))
        }
        if (prefetchKey != null) {
            val cached = androidx.media3.datasource.cache.CacheDataSource.Factory()
                .setCache(NextPrefetch.cache)
                .setUpstreamDataSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(ctx))
                .setCacheWriteDataSinkFactory(null)   // read only: playback never writes this cache
                .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            val main = mediaSourceFactory(cached).createMediaSource(mediaItem)
            p.setMediaSource(if (sidecarSources.isEmpty()) main else androidx.media3.exoplayer.source.MergingMediaSource(main, *sidecarSources.toTypedArray()))
            android.util.Log.i("R381", "load: the prefetched next item, read from its cache")
        } else if (sidecarSources.isNotEmpty()) {
            val main = mediaSourceFactory(null).createMediaSource(mediaItem)
            p.setMediaSource(androidx.media3.exoplayer.source.MergingMediaSource(main, *sidecarSources.toTypedArray()))
            android.util.Log.i("314", "load: ${sidecarSources.size} sidecar audio file(s) merged with the video")
        } else {
            p.setMediaItem(mediaItem)
        }
        p.seekTo(startPositionMs)
        p.prepare()
        ensureMediaSession() // touch/recreate the session so it's active for the OS while this item plays (R44)
    }

    // ── DEB-2205 (FR-5) — a stalled or frozen ladder play recovers downward ──

    /** The viewer's wait has gone on [IN_PLACE_CAP_AFTER_MS] in a buffer-empty stall: cap the adaptive selection one rung down. */
    private val inPlaceCap = Runnable {
        if (!synchronized(qoeLock) { qoeCounter.inStall() }) return@Runnable   // it ended on its own
        val e = engine ?: return@Runnable
        val current = qoeVariantFormatBps ?: return@Runnable
        val rungs = e.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { g -> (0 until g.length).map { i -> g.getTrackFormat(i).let { f -> (f.bitrate.takeIf { b -> b > 0 } ?: f.peakBitrate.takeIf { b -> b > 0 } ?: 0).toLong() } } }
        val cap = rungBelow(rungs, current) ?: return@Runnable   // already on the lowest rung: nothing lower to ask for
        e.trackSelectionParameters = e.trackSelectionParameters.buildUpon().setMaxVideoBitrate(cap.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).build()
        // Nothing climbs for 2 min (FR-5.3); then the cap is lifted and FR-309-4's own rule applies again.
        PlayerLadderHints.climbBlockedUntilMs = System.currentTimeMillis() + CLIMB_BLOCK_AFTER_RECOVERY_MS
        mainHandler.removeCallbacks(liftCap)
        mainHandler.postDelayed(liftCap, CLIMB_BLOCK_AFTER_RECOVERY_MS)
        android.util.Log.i("DEB-2205", "recovery: buffer empty for ${IN_PLACE_CAP_AFTER_MS / 1000} s on ${rungWords(qoeVariantHeight, qoeVariantBps)} → capped in place at ${cap / 1000}k (DEB-2205 FR-5)")
    }

    private val liftCap = Runnable { engine?.let { liftMaxVideoBitrate(it) } }

    private fun liftMaxVideoBitrate(e: ExoPlayer) {
        if (e.trackSelectionParameters.maxVideoBitrate != Int.MAX_VALUE)
            e.trackSelectionParameters = e.trackSelectionParameters.buildUpon().setMaxVideoBitrate(Int.MAX_VALUE).build()
    }

    /** A new stream is its own start: the cap, its timers and a QA freeze are cleared (the restream's ticket carries the bound). */
    private fun clearRecoveryState(e: ExoPlayer) {
        mainHandler.removeCallbacks(inPlaceCap)
        mainHandler.removeCallbacks(liftCap)
        liftMaxVideoBitrate(e)
        debugRenderFrozen = false
    }

    // QA (spec Acceptance 5) — a DEBUGGABLE build stops the picture on `adb shell am broadcast -p <package>
    // -a dev.jellystructure.ravilo.DEBUG_FREEZE_RENDER` (`--ez on false` lifts it): the engine holds its frame and position
    // while the buffer fills, and [isPlaying] still says "wants to play", so the store sees what a frozen decoder looks like.
    // A release build never registers it.
    @Volatile private var debugRenderFrozen = false
    private var debugFreezeReceiver: android.content.BroadcastReceiver? = null

    private fun bindDebugFreeze() {
        if (debugFreezeReceiver != null) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: android.content.Intent) {
                val e = engine ?: return
                val on = intent.getBooleanExtra("on", true)
                debugRenderFrozen = on
                if (on) e.pause() else e.play()
                android.util.Log.i("DEB-2205", "QA: render ${if (on) "frozen" else "resumed"} at ${e.currentPosition} ms, ${e.bufferedPosition - e.currentPosition} ms buffered")
            }
        }
        debugFreezeReceiver = r
        val filter = android.content.IntentFilter(DEBUG_FREEZE_RENDER_ACTION)
        if (android.os.Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, filter, Context.RECEIVER_EXPORTED) else ctx.registerReceiver(r, filter)
    }

    private fun unbindDebugFreeze() {
        debugFreezeReceiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        debugFreezeReceiver = null
        debugRenderFrozen = false
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
    actual fun setVolume(level: Float) { outputVolume = level.coerceIn(0f, 1f); engine?.volume = outputVolume }

    // R376 (FR-R376-3) — Media3 selects in place; the answer is always "switched".
    actual fun selectAudioTrack(index: Int): Boolean { selectAudioInEngine(index); return true }

    // R382, amendment 2026-10-13 (FR-R382-8) — [index] is a row of [audioRows], i.e. a ticket position, and the
    // group it selects is the one that row IS. R291's rendition names (`a{position} …`, the backend's composeMaster,
    // which Media3 does not list in the ticket's order) are the first thing the pairing matches on, so a composed
    // master still selects exactly the rendition it did before; a file's own tracks on direct play are matched by
    // language, channels and codec instead of by their place in Media3's renderer-ordered list.
    private fun selectAudioInEngine(index: Int) {
        val exo = engine ?: return
        synchronized(qoeLock) { qoeCounter.trackSwitch() }   // R381 (FR-R381-2) — a switch's wait is not a stall
        val row = audioRows().getOrNull(index) ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(TrackSelectionOverride(row.group.mediaTrackGroup, 0))
            .build()
    }

    /** R291 — see [warmAudioRendition]. Reads the parsed master and the playhead here, on the player's thread. */
    internal fun warmAudioRendition(index: Int) {
        val exo = engine ?: return
        val manifest = exo.currentManifest as? androidx.media3.exoplayer.hls.HlsManifest ?: return
        AudioRenditionWarmer.warm(manifest, index, exo.currentPosition)
    }

    actual fun selectSubtitleTrack(index: Int) {
        val exo = engine ?: return
        val media3Text = media3TextGroups(exo)
        if (index < 0 || index - media3Text.size >= appFedTickets.size) {
            // Off. Media3's text type is disabled, the app-fed track dropped (and its fetch cancelled).
            appFed.select(-1)
            if (!exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) {
                // Turning the text renderer off re-prepares nothing: no wait follows, so no track-switch is told to the counter.
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            }
            return
        }
        if (index < media3Text.size) {
            // One of the container's own tracks (VobSub, `embed`): Media3 selects it in place, the app-fed one goes.
            appFed.select(-1)
            synchronized(qoeLock) { qoeCounter.trackSwitch() }   // R381 (FR-R381-2)
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(media3Text[index].mediaTrackGroup, 0))
                .build()
            return
        }
        // A track the app feeds: Media3's text type off (it emits nothing), THIS track fetched, drawn when it arrives.
        // No re-prepare, so no track-switch wait to tell the counter about.
        if (!exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        }
        appFed.select(index - media3Text.size)
    }

    /** Media3's own text groups, in the order the picker lists them (before the app-fed tracks). */
    private fun media3TextGroups(exo: ExoPlayer): List<Tracks.Group> = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }

    actual fun release() {
        if (activeRaviloPlayer === this) activeRaviloPlayer = null
        appFed.reset(emptyList())   // DEB-2205 — the screen is gone: no fetch outlives it
        releaseEngine()
        releasedForBackground = false   // the screen is gone; nothing will rebuild this
    }

    // R192: release (not just pause) on backgrounding, so the session stops being advertised to
    // Android's cross-device media surfacing; recreated on demand when foregrounded/reactivated. R292:
    // only ever around a LIVE engine — after releaseEngine() there is nothing to advertise until the
    // next load builds one (bindEngine creates the session then). A `false` on an already-released
    // session is a no-op.
    actual fun setSessionActive(active: Boolean) {
        // R266 (dev review item 4) — R192's visibility toggle never deactivates the session a live cast drives: the
        // phone's remote reaches the TV through it. Leaving playback still ends it (releaseEngine/release, FR-R266-2).
        if (!active && TvPlayerSessionHooks.castDriving) return
        if (active) {
            ensureMediaSession()
        } else {
            mediaSessionRef?.release()
            mediaSessionRef = null
        }
    }


    actual fun setSubtitleScale(scale: Float) {
        subtitleScale = scale.coerceIn(0.5f, 2f)
        subtitleViewRef?.let { applySubtitleSize(it) }
    }

    // R300 (FR-R300-1) — the height captions are sized against: a 16:9 frame's fitted inside the window, not the window's. The
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
    // R306 (FR-R306-4) — *wants to play*, as the web's `!video.paused` already is: Media3's own isPlaying
    // is false while buffering, which flipped the Pause glyph to ▶, made OK during a buffer call play()
    // and reported the session paused. Buffering is `isBuffering`'s to say (R218).
    actual val isPlaying: Boolean get() = engine?.let { (it.playWhenReady || debugRenderFrozen) && it.playbackState != Player.STATE_ENDED && it.playbackState != Player.STATE_IDLE } == true
    actual val playbackFailed: Boolean get() = _playbackFailed
    actual val isEnded: Boolean get() = engine?.playbackState == Player.STATE_ENDED
    actual val hasRenderedFirstFrame: Boolean get() = _hasRenderedFirstFrame
    actual val isBuffering: Boolean get() = _isBuffering
    actual val isSeeking: Boolean get() = _isSeeking
    actual val soundBlockedByBrowser: Boolean get() = false   // R376 — no autoplay rule on Android

    /** One picker row: the engine audio group, and the ticket entry it is (null for a group no entry claims). */
    private class AudioRow(val group: Tracks.Group, val ticket: dev.jellystructure.shared.tv.AudioTrack?)

    /**
     * R382, amendment 2026-10-13 (FR-R382-8) — the rows the picker and [selectAudioInEngine] share: the ticket's
     * audio tracks in the ticket's order, each paired with the engine group that IS it ([pairAudioToTicket]).
     *
     * `Tracks.groups` is in RENDERER order — media3's `TrackSelectionUtil.buildTracks` loops renderers outermost —
     * and R379's `EXTENSION_RENDERER_MODE_ON` puts the FFmpeg extension renderer after the platform one, so a track
     * only FFmpeg decodes (DTS, TrueHD on a Pixel) is listed after every other. The old `audioMeta.getOrNull(idx)`
     * zip therefore gave each group its NEIGHBOUR's language, label, channels and `copyOf` from that track on, and
     * the viewer tapped *Norsk* and heard Swedish (DEB-740).
     */
    private fun audioRows(): List<AudioRow> {
        val tracks = engine?.currentTracks ?: return emptyList()
        val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audioMeta.isEmpty()) return groups.map { AudioRow(it, null) }
        val engineTracks = groups.map { g ->
            val f = g.mediaTrackGroup.getFormat(0)
            EngineAudioTrack(f.language, f.label, f.channelCount.takeIf { it > 0 }, f.sampleMimeType)
        }
        return pairAudioToTicket(engineTracks, audioMeta).map { AudioRow(groups[it.enginePosition], it.ticketPosition?.let(audioMeta::get)) }
    }

    actual val audioTracks: List<PlayerAudioTrack>
        get() {
            val rows = audioRows()
            return rows.mapIndexed { idx, row ->
                val format = row.group.mediaTrackGroup.getFormat(0)
                // Prefer the server-derived label (Jellyfin DisplayTitle) of THIS row's own ticket entry; fall back
                // to the container track name, then a humanized language, then the raw code (R46). A group no ticket
                // entry claims keeps the container's own label, as a mismatched stream count always did.
                val meta = row.ticket
                val label = meta?.label?.takeIf { it.isNotBlank() }
                    ?: format.label
                    ?: languageName(format.language)
                    ?: format.language?.uppercase()
                    ?: "Track ${idx + 1}"
                // R180 — prefer the server-derived meta (Jellyfin MediaStreams); fall back to the
                // container format's own channel count when meta lacks it.
                val channels = meta?.channels ?: format.channelCount.takeIf { it > 0 }
                // Phase 314b — a copy's source by the ROW its ticket entry is on, which is where the picker folds it.
                val copyOf = meta?.copyOf?.let { src -> rows.indexOfFirst { it.ticket?.index == src } }?.takeIf { it >= 0 }
                PlayerAudioTrack(idx, label, meta?.language ?: format.language, channels, meta?.isDefault ?: false,
                    copyOf = copyOf, supported = row.group.isTrackSupported(0))
            }
        }

    actual val subtitleTracks: List<PlayerSubtitleTrack>
        get() {
            val result = mutableListOf<PlayerSubtitleTrack>()
            val tracks = engine?.currentTracks ?: return result
            // Until the engine has discovered the stream's tracks the list is empty, as it always was: the picker and
            // the resolver (R181) must not see app-fed rows at indices Media3's own groups are about to take over.
            if (tracks.groups.isEmpty()) return result
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
            // DEB-2205 (FR-6) — then the ticket's text tracks the app feeds, in ticket order (what sideloaded configs were).
            for (sub in appFedTickets) { result += appFedSubtitleRow(sub, idx); idx++ }
            return result
        }

    /** R381 (FR-R381-1) — a new item resets every per-item counter; the same item again (a restream) keeps them. */
    actual fun beginQoeItem(itemKey: String) {
        val fresh = synchronized(qoeLock) { qoeCounter.beginItem(itemKey) }
        if (!fresh) return
        qoeDroppedFrames = 0
        qoeRenderedBase = -renderedVideoFrameCount()   // the live decoder's frames so far are the previous item's
        qoeBandwidthEstimateBps = null
        // The decoder is the engine's, not the item's: a reused engine keeps it without a new init event (seen live on
        // Stue TV, 2026-10-08: the second item's report carried no decoder), so its name is kept.
        qoeSubtitleLoadErrors = 0
        qoeVideoOutputRecoveries = 0
        qoeVideoOutputRecoveryRung = 0
        qoeVideoOutputRecoveryMs = 0
        qoeBackgroundReturns = 0
        qoeRestoredAfterRecreate = 0
        qoeVariantDown = 0
        qoeVariantUp = 0
        qoeBandwidthSamples = 0
        qoeBandwidthBytes = 0
    }

    actual fun qoeSnapshot(): PlayerQoeSnapshot {
        // DEB-2509 — read before taking qoeLock: from a background thread this waits on the main thread, whose listener callbacks take the lock.
        val rendered = renderedVideoFrameCount()
        return synchronized(qoeLock) { PlayerQoeSnapshot(
        droppedFrames = qoeDroppedFrames,
        rebufferCount = qoeCounter.rebufferCount,
        rebufferMs = qoeCounter.rebufferMs,
        bandwidthEstimateBps = qoeBandwidthEstimateBps,
        bandwidthSamples = qoeBandwidthSamples,
        bandwidthBytes = qoeBandwidthBytes,
        firstFrameMs = qoeCounter.firstFrameMs(),
        videoDecoder = qoeVideoDecoder,
        audioDecoder = qoeAudioDecoder,
        subtitleLoadErrors = qoeSubtitleLoadErrors,
        videoOutputRecoveries = qoeVideoOutputRecoveries,
        videoOutputRecoveryRung = qoeVideoOutputRecoveryRung,
        videoOutputRecoveryMs = qoeVideoOutputRecoveryMs,
        backgroundReturns = qoeBackgroundReturns,
        restoredAfterRecreate = qoeRestoredAfterRecreate,
        variantSwitchesDown = qoeVariantDown,
        variantSwitchesUp = qoeVariantUp,
        variantBandwidthBps = qoeVariantBps,
        variantHeight = qoeVariantHeight,
        variantCodec = qoeVariantCodec,
        renderedFrames = (qoeRenderedBase + rendered).coerceAtLeast(0),
        perItem = true,
        stalls = qoeCounter.stallEvents(),
        sessionRebufferCount = qoeCounter.sessionRebufferCount,
        sessionRebufferMs = qoeCounter.sessionRebufferMs,
        waits = qoeCounter.waitCounts(),
    ) }
    }

    /**
     * Phase R220 (FR-R220-2) — a real frame COUNT, not a boolean, so the "playing but not rendering"
     * detector in [PlayerVideoSurface] can tell "no new frames since I last polled" from "genuinely
     * nothing rendered yet" (the latter is [hasRenderedFirstFrame]'s job, unaffected by this). 0 before
     * a video decoder exists yet or if the counters are ever unavailable — never throws.
     * `ensureUpdated()` is required by [DecoderCounters]'s own contract before a cross-thread read.
     */
    fun renderedVideoFrameCount(): Long {
        val e = engine ?: return 0L   // a released engine's frames are already in [qoeRenderedBase] (onVideoDisabled)
        return renderedRead.read {
            e.videoDecoderCounters?.let { counters ->
                counters.ensureUpdated()
                counters.renderedOutputBufferCount.toLong()
            } ?: 0L
        }
    }

    // DEB-2509 — ExoPlayer's counters are application-thread only; the QoE snapshot is taken on Dispatchers.Default.
    private val renderedRead = AppThreadRead(
        onAppThread = { engine?.let { android.os.Looper.myLooper() == it.applicationLooper } ?: true },
        runOnAppThread = { r -> engine?.let { android.os.Handler(it.applicationLooper).post(r) } ?: false },
    )

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

/** R216 (FR-R216-3) — raised from DefaultLoadControl's stock 5s so a recovered stall resumes with a
 *  real cushion instead of re-stalling seconds later; see this file's `setBufferDurationsMs` call site. */
private const val QOE_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 10_000

/** DEB-2205 (FR-6) — how often the app-fed subtitle is matched to the playhead; a cue shows within one tick of its time. */
private const val CUE_POLL_MS = 150L

/** DEB-2205 (spec Acceptance 5) — the broadcast a debuggable build stops the picture on, for QA. */
const val DEBUG_FREEZE_RENDER_ACTION = "dev.jellystructure.ravilo.DEBUG_FREEZE_RENDER"
