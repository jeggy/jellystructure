@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.jellystructure.ravilo.ui.seams

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.test.utils.FakeAudioRenderer
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.FakeMediaSource
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R381 (FR-R381-6 test 4) — [QoeCounterFeed] on a real ExoPlayer (fake clock, fake renderers): load → first frame →
 * load the next item on the same engine → first frame counts no stall. This is the binge case that counted 91 of Stue
 * TV's 100 "new stalls" before the phase: the reused engine's first STATE_BUFFERING comes after "a first frame".
 *
 * The driver does what [RaviloPlayer.load] does around the engine: tell the counter about the new item and load, then
 * hand the engine the next media source.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class QoeCounterMedia3Test {
    private val video = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(1280).setHeight(720).build()
    private val audio = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build()

    private val clock = FakeClock(/* isAutoAdvancing = */ true)
    private val counter = QoeCounter()
    private val lock = Any()
    private var bufferingEvents = 0
    private lateinit var player: ExoPlayer

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val renderers = RenderersFactory { handler, videoListener, audioListener, _, _ ->
            val wrapped = clock.createHandler(handler.looper, /* callback = */ null)
            arrayOf<Renderer>(FakeVideoRenderer(wrapped, videoListener), FakeAudioRenderer(wrapped, audioListener))
        }
        player = ExoPlayer.Builder(ctx, renderers).setClock(clock).build()
        player.setVideoSurface(android.view.Surface(android.graphics.SurfaceTexture(0)))   // a first frame needs an output
        player.addAnalyticsListener(QoeCounterFeed(counter, lock, positionOf = { player.currentPosition }))
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_BUFFERING) bufferingEvents++
            }
        })
    }

    @After
    fun tearDown() = player.release()

    private fun source() = FakeMediaSource(FakeTimeline(), video, audio)

    /** What [RaviloPlayer.load] does: the item and the load are told to the counter, then the engine gets the stream. */
    private fun playItem(key: String, tellCounter: Boolean = true) {
        if (tellCounter) synchronized(lock) {
            counter.beginItem(key)
            counter.load(clock.elapsedRealtime())
        }
        player.setMediaSource(source())
        player.prepare()
        player.playWhenReady = true
        TestPlayerRunHelper.runUntilRenderedFirstFrame(player)
        TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_READY)
    }

    @Test
    fun `a binge on one engine counts no stall for any item's start`() {
        playItem("episode-1")
        assertEquals(0, counter.rebufferCount, "the first item's own start")

        playItem("episode-2")
        // The engine was reused and went back to buffering AFTER the previous item's first frame: the very event the
        // old counting reported as a stall. It happened, and it is not one.
        assertTrue(bufferingEvents >= 2, "Media3 buffered again for the second item ($bufferingEvents events)")
        assertEquals(0, counter.rebufferCount)
        assertEquals(0, counter.rebufferMs)
        assertTrue(counter.stallEvents().isEmpty())

        playItem("episode-3")
        assertEquals(0, counter.rebufferCount)
        assertEquals(0, counter.sessionRebufferCount, "no item's start reached the session totals either")
        assertEquals(mapOf("start" to 1), counter.waitCounts(), "the start is counted under its own name, once, for the item")
    }

    @Test
    fun `a player that never tells the counter about the next item, the same sequence counts the start as a stall (the 2026-10-08 bug)`() {
        playItem("episode-1")
        playItem("episode-2", tellCounter = false)
        // Proves the test above can fail: before R381 nothing reset the first-frame flag, and this is what was counted.
        assertEquals(1, counter.rebufferCount)
    }
}
