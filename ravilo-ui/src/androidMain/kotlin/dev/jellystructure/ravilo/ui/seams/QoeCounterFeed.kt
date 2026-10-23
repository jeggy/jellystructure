package dev.jellystructure.ravilo.ui.seams

import androidx.media3.common.Player
import androidx.media3.exoplayer.analytics.AnalyticsListener

/**
 * R381 — the part of the player's QoE listener that feeds [QoeCounter] from Media3's events: first frame, seek, the
 * playback state, and a pause. Its own class so FR-R381-6's test 4 drives the REAL wiring on a real ExoPlayer
 * (a fake clock, fake renderers) instead of a copy of it; [RaviloPlayer] binds one to every engine it builds.
 *
 * The events that need the player's own fields (a variant switch, a track switch, an engine rebuild) stay in
 * [RaviloPlayer]: they are told to the counter where they happen.
 *
 * @param lock guards [counter]: Media3 calls on its looper, the session store reads the snapshot elsewhere.
 * @param positionOf the playhead when a wait begins (null: the event's own position is used).
 * @param bufferedAheadOf how much is buffered ahead of the playhead when a wait begins.
 * @param variantBpsOf the video variant playing now, for the stall's record.
 * @param variantHeightOf that variant's height, and [pendingSubtitlesOf] how many app-fed subtitle fetches were pending
 *  (DEB-2205 FR-8): both only describe the moment on the stall's entry.
 */
internal class QoeCounterFeed(
    private val counter: QoeCounter,
    private val lock: Any,
    private val positionOf: () -> Long? = { null },
    private val bufferedAheadOf: () -> Long = { 0L },
    private val variantBpsOf: () -> Long? = { null },
    private val variantHeightOf: () -> Int? = { null },
    private val pendingSubtitlesOf: () -> Int? = { null },
) : AnalyticsListener {
    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
        synchronized(lock) { counter.firstFrame(eventTime.realtimeMs) }
    }

    override fun onPositionDiscontinuity(
        eventTime: AnalyticsListener.EventTime,
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) synchronized(lock) { counter.seek() }
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        when (state) {
            Player.STATE_BUFFERING -> {
                val pos = positionOf() ?: eventTime.currentPlaybackPositionMs
                synchronized(lock) { counter.buffering(eventTime.realtimeMs, pos, bufferedAheadOf(), variantBpsOf(), variantHeightOf(), pendingSubtitlesOf()) }
            }
            Player.STATE_READY -> synchronized(lock) { counter.ready(eventTime.realtimeMs) }
            else -> synchronized(lock) { counter.interrupted() }
        }
    }

    // A pause while waiting is the viewer's, not a stall.
    override fun onPlayWhenReadyChanged(eventTime: AnalyticsListener.EventTime, playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady) synchronized(lock) { counter.interrupted() }
    }
}
