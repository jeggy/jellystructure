package dev.jellystructure.ravilo.ui.seams

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-2205 (FR-4) — a direct play's buffer is sized in seconds and bounded by memory; every other stream keeps its byte bound. */
class TimeFirstLoadControlTest {
    private val remux = 71_000_000L

    private fun control(memoryClassMb: Int, streamBps: Long) =
        TimeFirstLoadControl(memoryClassMb, { streamBps }, bufferForPlaybackAfterRebufferMs = 10_000).also { it.onPrepared(PlayerId.UNSET) }

    private fun params(bufferedSeconds: Double, rebuffering: Boolean = false) = LoadControl.Parameters(
        PlayerId.UNSET, Timeline.EMPTY, LoadControl.EMPTY_MEDIA_PERIOD_ID, 0L, (bufferedSeconds * 1_000_000).toLong(), 1f, true, rebuffering, C.TIME_UNSET, 0L,
    )

    @Test fun `a direct play of a big file stops loading at what the memory holds`() {
        val c = control(memoryClassMb = 256, streamBps = remux)         // 64 MB = 7.6 s of 71 Mbps
        assertTrue(c.shouldContinueLoading(params(3.0)))
        assertTrue(c.shouldContinueLoading(params(7.0)))
        assertFalse(c.shouldContinueLoading(params(8.0)), "past what 64 MB of this file holds")
    }

    @Test fun `a device that holds less than the restart threshold still restarts`() {
        val c = control(memoryClassMb = 256, streamBps = remux)
        // The stock restart after a stall wants 10 s buffered; this device can hold 7.6 s. It restarts with what it can hold.
        assertFalse(c.shouldStartPlayback(params(3.0, rebuffering = true)))
        assertTrue(c.shouldStartPlayback(params(7.0, rebuffering = true)), "7 s is nine tenths of what it holds")
    }

    @Test fun `a small file direct plays to the full target in seconds`() {
        val c = control(memoryClassMb = 256, streamBps = 5_000_000)     // 64 MB = 102 s of 5 Mbps: the 50 s target decides
        assertTrue(c.shouldContinueLoading(params(40.0)))
        assertFalse(c.shouldContinueLoading(params(51.0)))
    }

    @Test fun `a stream that is not a direct play is not capped by the direct play rule`() {
        val c = control(memoryClassMb = 256, streamBps = 0)
        assertTrue(c.shouldContinueLoading(params(8.0)), "below the stock buffer, nothing here to stop it")
        assertTrue(c.shouldContinueLoading(params(40.0)))
        assertFalse(c.shouldStartPlayback(params(3.0, rebuffering = true)), "the stock threshold, unchanged")
    }
}
