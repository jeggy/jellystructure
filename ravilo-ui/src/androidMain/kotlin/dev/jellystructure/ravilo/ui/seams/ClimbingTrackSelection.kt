package dev.jellystructure.ravilo.ui.seams

import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.chunk.MediaChunk
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.google.common.collect.ImmutableList
import dev.jellystructure.shared.tv.LadderRules

/**
 * Phase 309 (FR-309-4/-5) — Media3's adaptive selection, bounded by [LadderRules]: never more than one rung up, a climb
 * only with enough buffered (30 s on Jellyfin's per-rung jobs, the stock 10 s on our own encoder, [PlayerLadderHints]), and a
 * step down as soon as the buffer is under 20 s and falling, whatever the estimate says. Within the bound, Media3's own
 * rule (its estimate × 0.7) chooses.
 *
 * Built at the stock minimum for a quality increase (10 s) so the bound, not the constructor, sets the 30 s; decreases
 * are allowed while up to 45 s is buffered (308) and everything buffered is kept on a switch up (50 s).
 */
@UnstableApi
internal class ClimbingTrackSelection(
    group: TrackGroup, tracks: IntArray, type: Int, bandwidthMeter: BandwidthMeter,
    checkpoints: List<AdaptiveTrackSelection.AdaptationCheckpoint>,
) : AdaptiveTrackSelection(
    group, tracks, type, bandwidthMeter,
    MIN_INCREASE_MS, MAX_DECREASE_MS, RETAIN_AFTER_DISCARD_MS,
    AdaptiveTrackSelection.DEFAULT_MAX_WIDTH_TO_DISCARD, AdaptiveTrackSelection.DEFAULT_MAX_HEIGHT_TO_DISCARD,
    BANDWIDTH_FRACTION, AdaptiveTrackSelection.DEFAULT_BUFFERED_FRACTION_TO_LIVE_EDGE_FOR_QUALITY_INCREASE,
    checkpoints, Clock.DEFAULT,
) {
    @Volatile private var allowedMaxBps: Long? = null
    private var lastBufferedMs: Long? = null

    override fun updateSelectedTrack(
        playbackPositionUs: Long, bufferedDurationUs: Long, availableDurationUs: Long,
        queue: List<MediaChunk>, mediaChunkIterators: Array<MediaChunkIterator>,
    ) {
        val playing = queue.lastOrNull()?.trackFormat?.bitrate?.takeIf { it > 0 }?.toLong()
        val rungs = (0 until length()).map { getFormat(it).bitrate.toLong() }.filter { it > 0 }
        val bufferedMs = bufferedDurationUs / 1000
        allowedMaxBps = LadderRules.allowedMaxBps(rungs, playing, bufferedMs, lastBufferedMs, PlayerLadderHints.oursEncoder)
        lastBufferedMs = bufferedMs
        super.updateSelectedTrack(playbackPositionUs, bufferedDurationUs, availableDurationUs, queue, mediaChunkIterators)
    }

    override fun canSelectFormat(format: Format, trackBitrate: Int, effectiveBitrate: Long): Boolean {
        val max = allowedMaxBps
        if (max != null && format.bitrate > 0 && format.bitrate > max) return false
        return super.canSelectFormat(format, trackBitrate, effectiveBitrate)
    }

    @UnstableApi
    class Factory : AdaptiveTrackSelection.Factory(MIN_INCREASE_MS.toInt(), MAX_DECREASE_MS.toInt(), RETAIN_AFTER_DISCARD_MS.toInt(), BANDWIDTH_FRACTION) {
        override fun createAdaptiveTrackSelection(
            group: TrackGroup, tracks: IntArray, type: Int, bandwidthMeter: BandwidthMeter,
            adaptationCheckpoints: ImmutableList<AdaptiveTrackSelection.AdaptationCheckpoint>,
        ): AdaptiveTrackSelection = ClimbingTrackSelection(group, tracks, type, bandwidthMeter, adaptationCheckpoints)
    }

    companion object {
        private const val MIN_INCREASE_MS = 10_000L
        private const val MAX_DECREASE_MS = 45_000L
        private const val RETAIN_AFTER_DISCARD_MS = 50_000L
        private const val BANDWIDTH_FRACTION = 0.7f
    }
}
