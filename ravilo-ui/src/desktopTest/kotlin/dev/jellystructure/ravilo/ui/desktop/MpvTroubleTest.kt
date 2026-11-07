package dev.jellystructure.ravilo.ui.desktop

import dev.jellystructure.ravilo.ui.seams.holdsLastFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DEB-3215 (DEB-3093 FR-9, FR-3, FR-4, FR-5, FR-6) — what the Linux client (mpv) decides when trouble comes, on the pure pieces: libmpv, the
 * network and the picture are the QA row's.
 */
class MpvTroubleTest {
    @Test
    fun `a film's mpv waits 20 s for a byte, reconnects, and resumes a stall at 4 s buffered`() {
        val options = MpvTrouble.filmOptions().toMap()
        assertEquals("20", options["network-timeout"])
        assertEquals("4", options["cache-pause-wait"])
        assertEquals(setOf("network-timeout", "stream-lavf-o", "cache-pause-wait"), options.keys)
    }

    @Test
    fun `the reconnect list reaches ffmpeg whole - the status list is length-quoted because it holds a comma`() {
        val list = MpvTrouble.streamLavfOptions()
        assertEquals(
            "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_on_http_error=%7%4xx,5xx,reconnect_delay_max=5",
            list,
        )
        assertEquals(7, "4xx,5xx".length)   // the number between the percent signs is the quoted value's length
    }

    @Test
    fun `a rise in mpv's cache pauses after the picture is up is a stall`() {
        val w = MpvStallWatch()
        assertEquals(0L, w.onTick(stalls = 0, firstFrame = true, settling = false))
        assertEquals(1L, w.onTick(stalls = 1, firstFrame = true, settling = false))
        // Answered once: the same count again is nothing, a second pause is one more.
        assertEquals(0L, w.onTick(stalls = 1, firstFrame = true, settling = false))
        assertEquals(1L, w.onTick(stalls = 2, firstFrame = true, settling = false))
    }

    @Test
    fun `the first fill, a seek's refill and the seconds after a load are not stalls`() {
        val w = MpvStallWatch()
        // No picture yet: the stream is filling for the first time.
        assertEquals(0L, w.onTick(stalls = 1, firstFrame = false, settling = false))
        // A seek inside the settle window: the rise is taken as answered, not held for later.
        assertEquals(0L, w.onTick(stalls = 2, firstFrame = true, settling = true))
        assertEquals(0L, w.onTick(stalls = 2, firstFrame = true, settling = false))
        assertEquals(3_000L, MpvStallWatch.SETTLE_MS)
    }

    @Test
    fun `a load restarts mpv's count and the watch starts again from nothing`() {
        val w = MpvStallWatch()
        assertEquals(2L, w.onTick(stalls = 2, firstFrame = true, settling = false))
        w.reset()
        assertEquals(1L, w.onTick(stalls = 1, firstFrame = true, settling = false))
    }

    @Test
    fun `Linux on mpv's software renderer holds the last frame, mpv in its own window does not`() {
        assertTrue(holdsLastFrame(isMac = true, isMpv = false, videoPath = null))
        assertTrue(holdsLastFrame(isMac = false, isMpv = true, videoPath = null))
        assertTrue(holdsLastFrame(isMac = false, isMpv = true, videoPath = "sw"))
        assertFalse(holdsLastFrame(isMac = false, isMpv = true, videoPath = "gpu"))
        // No engine, nothing to hold.
        assertFalse(holdsLastFrame(isMac = false, isMpv = false, videoPath = null))
    }
}
