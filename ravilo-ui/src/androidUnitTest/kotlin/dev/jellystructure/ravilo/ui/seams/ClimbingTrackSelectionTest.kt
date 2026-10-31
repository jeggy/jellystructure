@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.jellystructure.ravilo.ui.seams

import android.net.Uri
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.chunk.Chunk
import androidx.media3.exoplayer.source.chunk.MediaChunk
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.test.utils.FakeDataSet
import androidx.media3.test.utils.FakeDataSource
import androidx.media3.test.utils.FakeMediaChunk
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DEB-3102 (FR-4) — [ClimbingTrackSelection]'s abandon rule on a real [androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection],
 * and the step-down for a buffer that has fallen for 6 s. The chunk under load is a [Chunk] whose bytes come through a real
 * StatsDataSource, so `bytesLoaded()` is what Media3 would report.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ClimbingTrackSelectionTest {
    private fun rung(bps: Int) = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setAverageBitrate(bps).setPeakBitrate(bps).setWidth(bps / 4000).setHeight(bps / 8000).build()
    private val top = rung(8_000_000)
    private val mid = rung(4_000_000)
    private val low = rung(1_500_000)
    private val group = TrackGroup(top, mid, low)

    private var nowMs = 1_000_000L
    private var estimate = 100_000_000L
    private val meter = object : BandwidthMeter {
        override fun getBitrateEstimate() = estimate
        override fun getTransferListener(): TransferListener? = null
        override fun addEventListener(eventHandler: Handler, eventListener: BandwidthMeter.EventListener) {}
        override fun removeEventListener(eventListener: BandwidthMeter.EventListener) {}
    }
    private val events = mutableListOf<String>()
    private lateinit var selection: ClimbingTrackSelection

    @Before fun setUp() {
        PlayerLadderHints.climbBlockedUntilMs = 0L
        PlayerLadderHints.live = false
        PlayerLadderHints.oursEncoder = true
        selection = ClimbingTrackSelection(group, intArrayOf(0, 1, 2), C.TRACK_TYPE_VIDEO, meter, emptyList(), LadderBuffer { nowMs }, { nowMs }, { events += it })
        selection.enable()
    }

    private fun queueOf(format: Format): List<MediaChunk> = listOf(FakeMediaChunk(format, 0, 6_000_000))

    /** A segment 6 s long that starts [bufferedS] seconds after the playhead (0), with [loaded] bytes through the data source. */
    private class LoadingChunk(format: Format, bufferedS: Long) : Chunk(
        FakeDataSource(FakeDataSet().setRandomData("fake:///seg", 6_000_000)), DataSpec(Uri.parse("fake:///seg")), C.DATA_TYPE_MEDIA, format, C.SELECTION_REASON_ADAPTIVE, null,
        bufferedS * 1_000_000, (bufferedS + 6) * 1_000_000,
    ) {
        init { dataSource.open(dataSpec) }
        fun pull(bytes: Int) { dataSource.read(ByteArray(bytes), 0, bytes) }
        override fun cancelLoad() {}
        override fun load() {}
    }

    @Test fun `a segment that would land after the buffer minus 5 s is abandoned for the rung below and the climb waits 30 s`() {
        val chunk = LoadingChunk(top, bufferedS = 12)
        assertFalse(selection.shouldCancelChunkLoad(0, chunk, queueOf(top)), "first sight: nothing measured yet")
        nowMs += 2_000
        chunk.pull(1_000_000)   // 0.5 MB/s: 5 MB more takes 10 s, it lands at 12 s, past 12 − 5
        assertTrue(selection.shouldCancelChunkLoad(0, chunk, queueOf(top)))
        assertTrue(PlayerLadderHints.climbBlocked(nowMs + 29_000))
        assertFalse(PlayerLadderHints.climbBlocked(nowMs + 31_000))
        assertEquals(1, events.size)
        // And the next chunk is chosen below the abandoned rung, whatever the estimate says.
        selection.updateSelectedTrack(0, 12_000_000, 12_000_000, queueOf(top), Array(3) { MediaChunkIterator.EMPTY })
        assertEquals(4_000_000, selection.selectedFormat.bitrate)
    }

    @Test fun `a segment that arrives in time is left alone`() {
        val chunk = LoadingChunk(top, bufferedS = 12)
        selection.shouldCancelChunkLoad(0, chunk, queueOf(top))
        nowMs += 2_000
        chunk.pull(3_000_000)   // 1.5 MB/s: 3 MB more in 2 s
        assertFalse(selection.shouldCancelChunkLoad(0, chunk, queueOf(top)))
        assertFalse(PlayerLadderHints.climbBlocked(nowMs))
    }

    @Test fun `the lowest rung has nothing to fall to`() {
        val chunk = LoadingChunk(low, bufferedS = 6)
        selection.shouldCancelChunkLoad(0, chunk, queueOf(low))
        nowMs += 20_000
        assertFalse(selection.shouldCancelChunkLoad(0, chunk, queueOf(low)))
    }

    @Test fun `a lower rung that is excluded does not count`() {
        selection.excludeTrack(selection.indexOf(mid), 15_000)
        selection.excludeTrack(selection.indexOf(low), 15_000)
        val chunk = LoadingChunk(top, bufferedS = 6)
        selection.shouldCancelChunkLoad(0, chunk, queueOf(top))
        nowMs += 20_000
        assertFalse(selection.shouldCancelChunkLoad(0, chunk, queueOf(top)))
    }

    @Test fun `a first picture is not held to the buffer's budget`() {
        val chunk = LoadingChunk(top, bufferedS = 0)
        selection.shouldCancelChunkLoad(0, chunk, emptyList())
        nowMs += 20_000
        assertFalse(selection.shouldCancelChunkLoad(0, chunk, emptyList()))
    }

    @Test fun `a buffer that fell for 6 s from 28 s steps down although it is above 20 s`() {
        var chosen = 0
        for (s in 0..8) {
            selection.updateSelectedTrack(0, (28_000L - s * 500L) * 1000, 40_000_000, queueOf(top), Array(3) { MediaChunkIterator.EMPTY })
            chosen = selection.selectedFormat.bitrate
            if (s < 6) assertEquals(8_000_000, chosen, "$s s in: not fallen for 6 s yet")
            nowMs += 1_000
        }
        assertEquals(4_000_000, chosen)
    }
}
