package dev.jellystructure.ravilo.ui.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-3034 (a-song-that-waits-says-so.md FR-1, FR-2) — the wait decision, on a test clock. */
class SongWaitTest {
    private fun sample(now: Long, buffering: Boolean, pos: Long = 0, wants: Boolean = true, buffered: Long = 0, observed: Long? = null, why: String? = null) =
        SongWaitSample(now, wants, buffering, pos, buffered, observed, why)

    /** A song that plays from 0 and has been heard. */
    private fun heard(t: SongWaitTracker, until: Long = 3_000): Long {
        var now = 0L
        while (now <= until) { assertEquals(SongWaitEdge.NONE, t.feed(sample(now, false, pos = now))); now += 250 }
        return now
    }

    @Test fun `a 1_9 s wait sends nothing`() {
        val t = SongWaitTracker()
        val start = heard(t)
        assertEquals(SongWaitEdge.NONE, t.feed(sample(start, true, pos = 3_000)))
        assertEquals(SongWaitEdge.NONE, t.feed(sample(start + 1_900, true, pos = 3_000)))
        assertEquals(SongWaitEdge.NONE, t.feed(sample(start + 1_900, false, pos = 3_100)))   // data again: no end report either
        assertFalse(t.waiting)
    }

    @Test fun `a 2 s wait sends once, its later samples nothing, and its end once`() {
        val t = SongWaitTracker()
        val start = heard(t)
        assertEquals(SongWaitEdge.NONE, t.feed(sample(start, true, pos = 3_000)))
        assertEquals(SongWaitEdge.STARTED, t.feed(sample(start + 2_000, true, pos = 3_000, buffered = 40, observed = 96_000)))
        assertTrue(t.waiting)
        assertEquals(SongWaitEdge.NONE, t.feed(sample(start + 2_250, true, pos = 3_000)))
        assertEquals(SongWaitEdge.NONE, t.feed(sample(start + 9_000, true, pos = 3_000)))
        assertEquals(SongWaitEdge.ENDED, t.feed(sample(start + 9_250, false, pos = 3_050)))
        assertFalse(t.waiting)
        assertEquals(SongWaitEdge.NONE, t.feed(sample(start + 9_500, false, pos = 3_300)))
    }

    @Test fun `a pause ends a wait and a paused player never starts one`() {
        val t = SongWaitTracker()
        val start = heard(t)
        t.feed(sample(start, true, pos = 3_000))
        assertEquals(SongWaitEdge.STARTED, t.feed(sample(start + 2_000, true, pos = 3_000)))
        // The listener paused: the engine still says buffering for a moment, but it no longer wants the song.
        assertEquals(SongWaitEdge.ENDED, t.feed(sample(start + 2_250, true, pos = 3_000, wants = false)))
        assertEquals(SongWaitEdge.NONE, t.feed(sample(start + 20_000, true, pos = 3_000, wants = false)))
    }

    @Test fun `a second wait is its own wait`() {
        val t = SongWaitTracker()
        val start = heard(t)
        t.feed(sample(start, true, pos = 3_000)); assertEquals(SongWaitEdge.STARTED, t.feed(sample(start + 2_000, true, pos = 3_000)))
        assertEquals(SongWaitEdge.ENDED, t.feed(sample(start + 3_000, false, pos = 3_100)))
        assertEquals(SongWaitEdge.NONE, t.feed(sample(start + 4_000, true, pos = 3_100)))
        assertEquals(SongWaitEdge.NONE, t.feed(sample(start + 5_900, true, pos = 3_100)))   // 1.9 s into the second: still nothing
        assertEquals(SongWaitEdge.STARTED, t.feed(sample(start + 6_000, true, pos = 3_100)))
    }

    @Test fun `phase is start before any sound, seek within a second of a seek, else mid`() {
        val a = SongWaitTracker()
        a.newSong()
        a.feed(sample(0, true)); assertEquals(SongWaitEdge.STARTED, a.feed(sample(2_000, true)))
        assertEquals("phase=start pos=0 buffered=0", a.detail(sample(2_000, true)))

        val b = SongWaitTracker()
        val s = heard(b)
        b.seeked(s + 100)
        b.feed(sample(s + 400, true, pos = 60_000))   // buffering began 300 ms after the seek
        assertEquals(SongWaitEdge.STARTED, b.feed(sample(s + 2_400, true, pos = 60_000)))
        assertTrue(b.detail(sample(s + 2_400, true, pos = 60_000))!!.startsWith("phase=seek "))

        val c = SongWaitTracker()
        val u = heard(c)
        c.seeked(u - 3_000)   // a seek long ago
        c.feed(sample(u, true, pos = 3_000))
        assertEquals(SongWaitEdge.STARTED, c.feed(sample(u + 2_000, true, pos = 3_000)))
        assertTrue(c.detail(sample(u + 2_000, true, pos = 3_000))!!.startsWith("phase=mid "))
    }

    @Test fun `a new song starts over at start`() {
        val t = SongWaitTracker()
        val s = heard(t)
        t.newSong()
        t.feed(sample(s, true, pos = 0)); assertEquals(SongWaitEdge.STARTED, t.feed(sample(s + 2_000, true, pos = 0)))
        assertTrue(t.detail(sample(s + 2_000, true))!!.startsWith("phase=start "))
    }

    @Test fun `the detail names position, buffered ahead and observed, leaves observed out without a figure, and adds why on the Mac`() {
        val t = SongWaitTracker()
        val s = heard(t)
        t.feed(sample(s, true, pos = 3_000)); t.feed(sample(s + 2_000, true, pos = 3_000))
        assertEquals("phase=mid pos=3000 buffered=1200 observed=96000", t.detail(sample(s + 2_000, true, pos = 3_000, buffered = 1_200, observed = 96_000)))
        assertEquals("phase=mid pos=3000 buffered=0", t.detail(sample(s + 2_000, true, pos = 3_000, observed = null)))
        assertEquals("phase=mid pos=3000 buffered=0", t.detail(sample(s + 2_000, true, pos = 3_000, observed = 0)))
        assertEquals("phase=mid pos=3000 buffered=0 why=AVPlayerWaitingToMinimizeStallsReason",
            t.detail(sample(s + 2_000, true, pos = 3_000, why = "AVPlayerWaitingToMinimizeStallsReason")))
    }

    @Test fun `the detail is never longer than 120 characters`() {
        val t = SongWaitTracker()
        val s = heard(t)
        t.feed(sample(s, true, pos = 3_000)); t.feed(sample(s + 2_000, true, pos = 3_000))
        val d = t.detail(sample(s + 2_000, true, pos = 3_000, buffered = 9_999_999, observed = 123_456_789, why = "w".repeat(300)))!!
        assertEquals(120, d.length)
        assertTrue(d.startsWith("phase=mid pos=3000 buffered=9999999 observed=123456789 why=w"))
    }

    @Test fun `no wait reported means no detail`() {
        assertNull(SongWaitTracker().detail(sample(0, false)))
    }
}
