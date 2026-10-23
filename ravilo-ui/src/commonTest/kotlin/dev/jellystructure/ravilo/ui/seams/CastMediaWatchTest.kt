package dev.jellystructure.ravilo.ui.seams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** DEB-2294 (DEB-2252, FR-R330-7) — when the Mac's cast bar asks the speaker for its media status again. */
class CastMediaWatchTest {
    private fun watch() = CastMediaWatch()

    @Test
    fun `a stale buffering status asks once and a playing reply settles it`() {
        val w = watch()
        w.mediaArrived(0, 7)
        // 0 s: the song's status reads BUFFERING; nothing is asked for the first 3 s.
        assertNull(w.due(0, true, "BUFFERING", "a#0"))
        assertNull(w.due(2_000, true, "BUFFERING", "a#0"))
        assertNull(w.due(3_000, true, "BUFFERING", "a#0"))
        assertEquals("buffering", w.due(4_000, true, "BUFFERING", "a#0"))
        // The ask is bounded: nothing more for 3 s whatever the status reads.
        assertNull(w.due(5_000, true, "BUFFERING", "a#0"))
        assertNull(w.due(6_999, true, "BUFFERING", "a#0"))
        // The reply: PLAYING. Nothing more is asked while it holds.
        w.mediaArrived(7_000, 7)
        for (t in 7_000L..20_000L step 1_000) assertNull(w.due(t, true, "PLAYING", "a#0"), "at $t")
    }

    @Test
    fun `a media status that never came is asked for as well`() {
        val w = watch()
        assertNull(w.due(0, true, null, "a#0"))
        assertEquals("buffering", w.due(3_001, true, null, "a#0"))
    }

    @Test
    fun `buffering is asked again every three seconds while it lasts`() {
        val w = watch()
        w.mediaArrived(0, 1)
        val asked = (0L..12_000L step 1_000).filter { w.due(it, true, "LOADING", "a#0") != null }
        assertEquals(listOf(4_000L, 7_000L, 10_000L), asked)
    }

    @Test
    fun `a song change on our channel with the media still on the old song asks`() {
        val w = watch()
        w.mediaArrived(0, 1)
        assertNull(w.due(0, true, "PLAYING", "a#0"))
        assertNull(w.due(20_000, true, "PLAYING", "a#0"))
        // Our channel moves to song b; the media status is still the first song's PLAYING.
        assertNull(w.due(30_000, true, "PLAYING", "b#1"), "not at once: the media status usually follows within a moment")
        assertEquals("song", w.due(31_000, true, "PLAYING", "b#1"))
        assertNull(w.due(32_000, true, "PLAYING", "b#1"))
        // The answer is a status for the new song: settled.
        w.mediaArrived(32_500, 2)
        for (t in 33_000L..40_000L step 1_000) assertNull(w.due(t, true, "PLAYING", "b#1"), "at $t")
    }

    @Test
    fun `a song change the media followed in either order asks nothing`() {
        val w = watch()
        w.mediaArrived(0, 1)
        w.due(0, true, "PLAYING", "a#0")
        // The media's new session first, then our channel's word.
        w.mediaArrived(30_000, 2)
        w.due(30_100, true, "PLAYING", "a#0")
        for (t in 30_200L..40_000L step 1_000) assertNull(w.due(t, true, "PLAYING", "b#1"), "at $t")
        // Our channel's word first, then the media's new session.
        val w2 = watch()
        w2.mediaArrived(0, 1)
        w2.due(0, true, "PLAYING", "a#0")
        assertNull(w2.due(30_000, true, "PLAYING", "b#1"))
        w2.mediaArrived(30_300, 2)
        for (t in 31_000L..40_000L step 1_000) assertNull(w2.due(t, true, "PLAYING", "b#1"), "at $t")
    }

    @Test
    fun `a fresh playing status asks nothing`() {
        val w = watch()
        w.mediaArrived(0, 1)
        for (t in 0L..60_000L step 1_000) assertNull(w.due(t, true, "PLAYING", "a#0"), "at $t")
        for (t in 61_000L..90_000L step 1_000) assertNull(w.due(t, true, "PAUSED", "a#0"), "paused at $t")
    }

    @Test
    fun `nothing is asked when the link is down or no music is loaded`() {
        val w = watch()
        for (t in 0L..20_000L step 1_000) assertNull(w.due(t, false, "BUFFERING", "a#0"), "at $t")
        // And the clock of a stale status starts when the link is live, not before.
        assertNull(w.due(21_000, true, "BUFFERING", "a#0"))
        assertNull(w.due(23_000, true, "BUFFERING", "a#0"))
        assertEquals("buffering", w.due(24_500, true, "BUFFERING", "a#0"))
    }

    @Test
    fun `raw arrivals are counted since the last song change and an identical reply still counts`() {
        val w = watch()
        assertEquals(0, w.rawSinceSongChange())
        w.due(0, true, "PLAYING", "a#0")
        w.rawArrived(); w.rawArrived()
        assertEquals(2, w.rawSinceSongChange())
        // Our channel moves to song b: the count starts again from there.
        w.due(10_000, true, "BUFFERING", "b#1")
        assertEquals(0, w.rawSinceSongChange())
        // The same BUFFERING reply four times: the StateFlow would hand one to mediaArrived, the raw count sees all four.
        w.mediaArrived(10_100, 5)
        repeat(4) { w.rawArrived() }
        w.due(11_000, true, "BUFFERING", "b#1")
        assertEquals(4, w.rawSinceSongChange())
    }
}
