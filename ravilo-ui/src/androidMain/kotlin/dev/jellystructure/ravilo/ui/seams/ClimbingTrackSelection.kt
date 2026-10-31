package dev.jellystructure.ravilo.ui.seams

import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.chunk.Chunk
import androidx.media3.exoplayer.source.chunk.MediaChunk
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.google.common.collect.ImmutableList
import dev.jellystructure.shared.tv.BufferTrend
import dev.jellystructure.shared.tv.LadderRules

/**
 * Phase 309 (FR-309-4/-5) — Media3's adaptive selection, bounded by [LadderRules]: never more than one rung up, a climb
 * only with enough buffered (30 s on Jellyfin's per-rung jobs, the stock 10 s on our own encoder, [PlayerLadderHints]), and a
 * step down as soon as the buffer is under 20 s and falling, whatever the estimate says. Within the bound, Media3's own
 * rule (its estimate × 0.7) chooses.
 *
 * DEB-3102 (FR-4): a step-down also fires when the buffer has fallen for 6 s and is under 30 s ([BufferTrend]); a segment
 * that would land later than the buffer minus 5 s is abandoned ([shouldCancelChunkLoad]) for the rung below, and the climb
 * then waits 30 s; a rung whose load failed is excluded by [LadderLoadErrorPolicy] and Media3's own exclusion keeps it out.
 *
 * Built at the stock minimum for a quality increase (10 s) so the bound, not the constructor, sets the 30 s; decreases
 * are allowed while up to 45 s is buffered (308) and everything buffered is kept on a switch up (50 s).
 */
@UnstableApi
internal class ClimbingTrackSelection(
    group: TrackGroup, tracks: IntArray, type: Int, private val bandwidthMeter: BandwidthMeter,
    checkpoints: List<AdaptiveTrackSelection.AdaptationCheckpoint>,
    private val buffer: LadderBuffer,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val onLadderEvent: (String) -> Unit = {},
) : AdaptiveTrackSelection(
    group, tracks, type, bandwidthMeter,
    MIN_INCREASE_MS, MAX_DECREASE_MS, RETAIN_AFTER_DISCARD_MS,
    AdaptiveTrackSelection.DEFAULT_MAX_WIDTH_TO_DISCARD, AdaptiveTrackSelection.DEFAULT_MAX_HEIGHT_TO_DISCARD,
    BANDWIDTH_FRACTION, AdaptiveTrackSelection.DEFAULT_BUFFERED_FRACTION_TO_LIVE_EDGE_FOR_QUALITY_INCREASE,
    checkpoints, Clock.DEFAULT,
) {
    @Volatile private var allowedMaxBps: Long? = null
    private var lastBufferedMs: Long? = null
    private val trend = BufferTrend()

    /** DEB-3102 (FR-4) — after an abandon, nothing above this until [abandonCapUntilMs]. */
    @Volatile private var abandonCapBps: Long = Long.MAX_VALUE
    @Volatile private var abandonCapUntilMs: Long = 0L
    // The load being judged and when it was first seen (the chunk does not say when it began).
    private var watchedLoadId = -1L
    private var watchedSinceMs = 0L
    private var watchedFirstByteMs = -1L
    private var abandonedLoadId = -1L

    /** DEB-3172 (FR-4) — the buffer ahead of a loading segment has once reached the margin plus its length since the start or the last seek outside the buffer. */
    private var judgeable = false

    override fun onDiscontinuity() {
        judgeable = false
        super.onDiscontinuity()
    }

    override fun updateSelectedTrack(
        playbackPositionUs: Long, bufferedDurationUs: Long, availableDurationUs: Long,
        queue: List<MediaChunk>, mediaChunkIterators: Array<MediaChunkIterator>,
    ) {
        val playing = queue.lastOrNull()?.trackFormat?.bitrate?.takeIf { it > 0 }?.toLong()
        val rungs = (0 until length()).map { getFormat(it).bitrate.toLong() }.filter { it > 0 }
        val bufferedMs = bufferedDurationUs / 1000
        buffer.note(bufferedMs)
        val fallen = trend.observe(nowMs(), bufferedMs)
        // DEB-2627 (FR-3) — a live channel adapts over its source's rungs on Media3's own rule: no 309 bound.
        allowedMaxBps = if (PlayerLadderHints.live) null else LadderRules.allowedMaxBps(rungs, playing, bufferedMs, lastBufferedMs, PlayerLadderHints.oursEncoder,
            climbBlocked = PlayerLadderHints.climbBlocked(nowMs()), fallenForWindow = fallen)
        lastBufferedMs = bufferedMs
        super.updateSelectedTrack(playbackPositionUs, bufferedDurationUs, availableDurationUs, queue, mediaChunkIterators)
    }

    override fun canSelectFormat(format: Format, trackBitrate: Int, effectiveBitrate: Long): Boolean {
        val max = allowedMaxBps
        if (max != null && format.bitrate > 0 && format.bitrate > max) return false
        if (format.bitrate > abandonCapBps && nowMs() < abandonCapUntilMs) return false
        return super.canSelectFormat(format, trackBitrate, effectiveBitrate)
    }

    /**
     * DEB-3102 (FR-4) — a slow segment is abandoned: its projected finish ([LadderRules.shouldAbandonSegment]) is later than the
     * buffered time minus 5 s and a lower rung that is not excluded exists. The rung below is then the ceiling for 30 s, and the
     * climb waits that long.
     */
    override fun shouldCancelChunkLoad(playbackPositionUs: Long, loadingChunk: Chunk, queue: List<MediaChunk>): Boolean {
        val now = nowMs()
        if (loadingChunk.loadTaskId != watchedLoadId) {
            watchedLoadId = loadingChunk.loadTaskId
            watchedSinceMs = now
            watchedFirstByteMs = -1L
        }
        val bytes = loadingChunk.bytesLoaded()
        if (bytes > 0 && watchedFirstByteMs < 0) watchedFirstByteMs = now   // seen on this poll: every poll sees it, judged or not
        // The queue holds the chunk being loaded (Media3 adds it when the load starts), so it is never empty here: what is in hand is read from the clock.
        val buffered = (loadingChunk.startTimeUs - playbackPositionUs) / 1000   // what is in hand before this segment is needed
        if (buffered <= 0) return false
        // After a start or a seek outside the buffer nothing is judged until the buffer has once held the margin plus this segment, or the encoder's wait is read as the link.
        if (!judgeable && buffered >= LadderRules.ABANDON_MARGIN_MS + loadingChunk.durationUs / 1000) judgeable = true
        if (!judgeable) return false
        // One abandon is one line and one 30 s cap: Media3 asks again until the cancelled load is gone.
        if (loadingChunk.loadTaskId == abandonedLoadId) return true
        val rung = loadingChunk.trackFormat.bitrate.takeIf { it > 0 }?.toLong() ?: return false
        val below = (0 until length())
            .filter { !isTrackExcluded(it, Clock.DEFAULT.elapsedRealtime()) }
            .map { getFormat(it).bitrate.toLong() }
            .filter { it in 1 until rung }
            .maxOrNull()
        val durationMs = loadingChunk.durationUs / 1000
        val cancel = LadderRules.shouldAbandonSegment(
            elapsedMs = now - watchedSinceMs, bytesLoaded = bytes,
            expectedBytes = rung * durationMs / 8_000, estimateBps = bandwidthMeter.bitrateEstimate,
            bufferedMs = buffered, lowerRungExists = below != null,
            sinceFirstByteMs = if (watchedFirstByteMs < 0) -1L else now - watchedFirstByteMs,
        )
        if (!cancel || below == null) return false
        abandonedLoadId = loadingChunk.loadTaskId
        abandonCapBps = below
        abandonCapUntilMs = now + LadderRules.CLIMB_WAIT_AFTER_ABANDON_MS
        PlayerLadderHints.climbBlockedUntilMs = maxOf(PlayerLadderHints.climbBlockedUntilMs, abandonCapUntilMs)
        onLadderEvent("abandoned a ${rung / 1000}k segment after ${now - watchedSinceMs} ms with ${buffered / 1000} s in hand → ${below / 1000}k")
        return true
    }

    @UnstableApi
    class Factory(
        private val buffer: LadderBuffer,
        private val onLadderEvent: (String) -> Unit = {},
    ) : AdaptiveTrackSelection.Factory(MIN_INCREASE_MS.toInt(), MAX_DECREASE_MS.toInt(), RETAIN_AFTER_DISCARD_MS.toInt(), BANDWIDTH_FRACTION) {
        override fun createAdaptiveTrackSelection(
            group: TrackGroup, tracks: IntArray, type: Int, bandwidthMeter: BandwidthMeter,
            adaptationCheckpoints: ImmutableList<AdaptiveTrackSelection.AdaptationCheckpoint>,
        ): AdaptiveTrackSelection = ClimbingTrackSelection(group, tracks, type, bandwidthMeter, adaptationCheckpoints, buffer, onLadderEvent = onLadderEvent)
    }

    companion object {
        private const val MIN_INCREASE_MS = 10_000L
        private const val MAX_DECREASE_MS = 45_000L
        private const val RETAIN_AFTER_DISCARD_MS = 50_000L
        private const val BANDWIDTH_FRACTION = 0.7f
    }
}
