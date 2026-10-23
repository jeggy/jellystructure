@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.jellystructure.ravilo.ui.seams

import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.analytics.AnalyticsListener
import org.junit.Test
import kotlin.test.assertEquals

/** DEB-2205 (FR-5.1) — [QoeCounterFeed] tells the player when a wait that counts as a stall begins, and only then. */
class QoeStallBeganTest {
    private val counter = QoeCounter()
    private var began = 0
    private val feed = QoeCounterFeed(counter, Any(), onStallBegan = { began++ })

    private fun at(ms: Long) = AnalyticsListener.EventTime(ms, Timeline.EMPTY, 0, null, 0L, Timeline.EMPTY, 0, null, 0L, 0L)

    @Test fun `the start and a seek do not begin a stall and a mid play buffer run dry does`() {
        counter.beginItem("film"); counter.load(0)
        feed.onPlaybackStateChanged(at(0), Player.STATE_BUFFERING)
        assertEquals(0, began, "the start")
        feed.onPlaybackStateChanged(at(900), Player.STATE_READY)
        feed.onRenderedFirstFrame(at(950), Any(), 950)

        counter.seek()
        feed.onPlaybackStateChanged(at(60_000), Player.STATE_BUFFERING)
        feed.onPlaybackStateChanged(at(61_000), Player.STATE_READY)
        assertEquals(0, began, "the viewer's own seek")

        feed.onPlaybackStateChanged(at(120_000), Player.STATE_BUFFERING)
        assertEquals(1, began, "the buffer ran dry")
        assertEquals(true, counter.inStall())
        feed.onPlaybackStateChanged(at(121_000), Player.STATE_READY)
        assertEquals(false, counter.inStall(), "the timer finds it ended")
    }
}
