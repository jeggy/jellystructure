@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.jellystructure.ravilo.ui.seams

import android.content.Context
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.Clock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.chunk.Chunk
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.test.utils.FakeAdaptiveDataSet
import androidx.media3.test.utils.FakeAdaptiveMediaSource
import androidx.media3.test.utils.FakeAudioRenderer
import androidx.media3.test.utils.FakeChunkSource
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.FakeDataSource
import androidx.media3.test.utils.FakeTimeline
import androidx.media3.test.utils.FakeVideoRenderer
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-3102 (FR-4, done means) — a blocked rung shows as a lower picture, not a stop. A real ExoPlayer (fake clock, fake
 * renderers) plays a three-rung ladder through the real [ClimbingTrackSelection] and [LadderLoadErrorPolicy]; from the fourth
 * segment on, every segment of the top rung answers 502. The play must carry on from the next rung down with no stall counted
 * and no error.
 *
 * The chunk source is Media3's [FakeChunkSource], which answers a load error with "no" by itself; here it excludes the failed
 * rung the way `HlsChunkSource.maybeExcludeTrack` does, on the policy's fallback selection. The rest — the loader, the retry
 * and the track selection — is the player's own.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LadderFailingRungMedia3Test {
    private fun rung(bps: Int) = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setAverageBitrate(bps).setPeakBitrate(bps).build()   // no size: the display must not decide the rung
    private val top = rung(8_000_000)
    private val mid = rung(4_000_000)
    private val low = rung(1_500_000)
    private val audio = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build()

    private val clock = FakeClock(/* isAutoAdvancing = */ true)
    private val counter = QoeCounter()
    private val lock = Any()
    private val buffer = LadderBuffer { clock.elapsedRealtime() }
    private val ladderPolicy = LadderLoadErrorPolicy(buffer)
    private val formatsLoaded = mutableListOf<Int>()
    private val loadErrors = mutableListOf<String>()
    private lateinit var player: ExoPlayer

    /** Every load of the first rung from this chunk on fails; -1 = none do. */
    @Volatile private var failTopFromChunk = -1

    private val meter = object : BandwidthMeter {
        override fun getBitrateEstimate() = 100_000_000L
        override fun getTransferListener(): TransferListener? = null
        override fun addEventListener(eventHandler: Handler, eventListener: BandwidthMeter.EventListener) {}
        override fun removeEventListener(eventListener: BandwidthMeter.EventListener) {}
    }

    /**
     * Media3's fake data keeps every rung of the whole film in one blob, so a segment is told by its length: the top rung's are the
     * 4 MB ones (8 Mb/s × 4 s), the first of them is segment 0 and the rest follow at their own length.
     */
    private inner class FailingTop(private val inner: DataSource) : DataSource by inner {
        private var topBase = -1L
        private var opened = false

        override fun open(dataSpec: DataSpec): Long {
            if (dataSpec.length == TOP_SEGMENT_BYTES) {
                if (topBase < 0) topBase = dataSpec.position
                val segment = (dataSpec.position - topBase) / TOP_SEGMENT_BYTES
                if (failTopFromChunk >= 0 && segment >= failTopFromChunk)
                    throw HttpDataSource.InvalidResponseCodeException(502, "Bad Gateway", null, emptyMap(), dataSpec, ByteArray(0))
            }
            return inner.open(dataSpec).also { opened = true }
        }

        // A refused open leaves nothing to close, and the fake data source asserts it.
        override fun close() {
            if (opened) inner.close()
            opened = false
        }
    }

    private inner class RigChunkSource(
        private val selection: ExoTrackSelection, dataSource: DataSource, dataSet: FakeAdaptiveDataSet,
    ) : FakeChunkSource(selection, dataSource, dataSet) {
        override fun onChunkLoadError(chunk: Chunk, cancelable: Boolean, info: LoadErrorHandlingPolicy.LoadErrorInfo, policy: LoadErrorHandlingPolicy): Boolean {
            loadErrors += info.exception.message.orEmpty()
            if (!cancelable) return false
            val now = Clock.DEFAULT.elapsedRealtime()
            val excluded = (0 until selection.length()).count { selection.isTrackExcluded(it, now) }
            val options = LoadErrorHandlingPolicy.FallbackOptions(1, 0, selection.length(), excluded)
            val fallback = ladderPolicy.getFallbackSelectionFor(options, info) ?: return false
            if (fallback.type != LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK || !options.isFallbackAvailable(fallback.type)) return false
            return selection.excludeTrack(selection.indexOf(chunk.trackFormat), fallback.exclusionDurationMs)
        }
    }

    private inner class RigFactory : FakeChunkSource.Factory(FakeAdaptiveDataSet.Factory(4_000_000L, 0.0, Random(1)), FakeDataSource.Factory()) {
        override fun createChunkSource(trackSelection: ExoTrackSelection, durationUs: Long, transferListener: TransferListener?): FakeChunkSource {
            val dataSet = dataSetFactory.createDataSet(trackSelection.trackGroup, durationUs)
            val source = dataSourceFactory.setFakeDataSet(dataSet).createDataSource()
            transferListener?.let { source.addTransferListener(it) }
            return RigChunkSource(trackSelection, FailingTop(source), dataSet)
        }
    }

    @Before
    fun setUp() {
        PlayerLadderHints.climbBlockedUntilMs = 0L
        PlayerLadderHints.live = false
        PlayerLadderHints.oursEncoder = true
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val renderers = RenderersFactory { handler, videoListener, audioListener, _, _ ->
            val wrapped = clock.createHandler(handler.looper, /* callback = */ null)
            arrayOf<Renderer>(FakeVideoRenderer(wrapped, videoListener), FakeAudioRenderer(wrapped, audioListener))
        }
        player = ExoPlayer.Builder(ctx, renderers).setClock(clock).setBandwidthMeter(meter)
            .setTrackSelector(DefaultTrackSelector(ctx, ClimbingTrackSelection.Factory(buffer)))
            .build()
        player.setVideoSurface(android.view.Surface(android.graphics.SurfaceTexture(0)))
        player.addAnalyticsListener(QoeCounterFeed(counter, lock, positionOf = { player.currentPosition }))
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onDownstreamFormatChanged(eventTime: AnalyticsListener.EventTime, mediaLoadData: MediaLoadData) {
                mediaLoadData.trackFormat?.takeIf { it.sampleMimeType == MimeTypes.VIDEO_H264 }?.let { formatsLoaded += it.bitrate }
            }
        })
    }

    @After
    fun tearDown() = player.release()

    private fun source(): FakeAdaptiveMediaSource {
        val window = FakeTimeline.TimelineWindowDefinition.Builder().setSeekable(true).setDurationUs(60_000_000L).build()
        return FakeAdaptiveMediaSource(FakeTimeline(window), TrackGroupArray(TrackGroup(top, mid, low)), RigFactory())
    }

    private fun playToTheEnd() {
        synchronized(lock) { counter.beginItem("film"); counter.load(clock.elapsedRealtime()) }
        player.setMediaSource(source())
        player.prepare()
        player.playWhenReady = true
        TestPlayerRunHelper.runUntilRenderedFirstFrame(player)
        // The refused segments are load errors the helper would call non-fatal failures: they are the point of the test.
        TestPlayerRunHelper.run(player).ignoringNonFatalErrors().untilState(Player.STATE_ENDED)
    }

    @Test
    fun `a blocked top rung plays on from the rung below with no stall and no error`() {
        failTopFromChunk = 3
        playToTheEnd()
        assertNull(player.playerError)
        assertTrue(loadErrors.isNotEmpty(), "the top rung's segments were refused")
        assertEquals(0, counter.rebufferCount, "a lower picture, not a stop")
        assertTrue(formatsLoaded.first() == 8_000_000, "it began on the top rung: $formatsLoaded")
        assertTrue(formatsLoaded.drop(1).any { it < 8_000_000 }, "and carried on below it: $formatsLoaded")
    }

    @Test
    fun `the same ladder with nothing blocked never leaves the top rung`() {
        playToTheEnd()
        assertNull(player.playerError)
        assertTrue(loadErrors.isEmpty())
        assertEquals(0, counter.rebufferCount)
        assertTrue(formatsLoaded.all { it == 8_000_000 }, "$formatsLoaded")
    }

    private companion object {
        const val TOP_SEGMENT_BYTES = 4_000_000L
    }
}
