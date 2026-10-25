package dev.jellystructure.ravilo.ui.seams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-2456 (DEB-2252, FR-R330-7b) — the bar takes a song's place from whichever report is newer. */
class CastPlaceTest {
    private fun heard(pos: Long, playing: Boolean = true, at: Long = 10_000L) = CastPlaceReport(pos, playing, at)

    @Test
    fun `the receiver newer than CAF gives the place`() {
        val p = castPlaceNow(heard(41_000L, at = 10_000L), cafAtMs = 9_000L, durationMs = 200_000L, nowMs = 10_000L)!!
        assertEquals(41_000L, p.positionMs)
        assertTrue(p.playing)
        assertFalse(p.buffering)
    }

    @Test
    fun `CAF newer than the receiver stands`() {
        assertNull(castPlaceNow(heard(41_000L, at = 10_000L), cafAtMs = 10_001L, durationMs = 200_000L, nowMs = 12_000L))
    }

    @Test
    fun `a tie goes to the receiver`() {
        assertEquals(41_000L, castPlaceNow(heard(41_000L, at = 10_000L), cafAtMs = 10_000L, durationMs = 0L, nowMs = 10_000L)?.positionMs)
    }

    @Test
    fun `CAF having said nothing for the song leaves the receiver`() {
        assertEquals(5_000L, castPlaceNow(heard(5_000L), cafAtMs = null, durationMs = 0L, nowMs = 10_000L)?.positionMs)
    }

    @Test
    fun `an old receiver with no place leaves CAF`() {
        assertNull(castPlaceNow(null, cafAtMs = null, durationMs = 200_000L, nowMs = 10_000L))
        assertNull(castPlaceNow(null, cafAtMs = 5_000L, durationMs = 200_000L, nowMs = 10_000L))
    }

    @Test
    fun `a playing report is moved on by the time since it arrived`() {
        assertEquals(44_500L, castPlaceNow(heard(41_000L, at = 10_000L), cafAtMs = null, durationMs = 200_000L, nowMs = 13_500L)?.positionMs)
    }

    @Test
    fun `a paused report holds however long ago it arrived`() {
        val p = castPlaceNow(heard(41_000L, playing = false, at = 10_000L), cafAtMs = null, durationMs = 200_000L, nowMs = 60_000L)!!
        assertEquals(41_000L, p.positionMs)
        assertFalse(p.playing)
        assertFalse(p.buffering)
    }

    @Test
    fun `a report that says not playing in the first second is a song still starting`() {
        val p = castPlaceNow(heard(0L, playing = false), cafAtMs = null, durationMs = 200_000L, nowMs = 11_000L)!!
        assertTrue(p.buffering)
        assertEquals(0L, p.positionMs)
    }

    @Test
    fun `the place never passes the song's length`() {
        assertEquals(200_000L, castPlaceNow(heard(199_000L, at = 10_000L), cafAtMs = null, durationMs = 200_000L, nowMs = 30_000L)?.positionMs)
        assertEquals(219_000L, castPlaceNow(heard(199_000L, at = 10_000L), cafAtMs = null, durationMs = 0L, nowMs = 30_000L)?.positionMs)
    }

    @Test
    fun `laid over CAF's snapshot it replaces the place and the play state and clears a stale finish`() {
        val caf = CastMediaSnapshot(playerState = "IDLE", idleFinished = true, positionMs = 0L, durationMs = 200_000L, title = "t")
        val over = caf.withPlace(CastPlace(41_000L, playing = true, buffering = false))
        assertEquals("PLAYING", over.playerState)
        assertFalse(over.idleFinished)
        assertEquals(41_000L, over.positionMs)
        assertEquals("t", over.title)
        assertEquals("PAUSED", caf.withPlace(CastPlace(41_000L, playing = false, buffering = false)).playerState)
        assertEquals("BUFFERING", caf.withPlace(CastPlace(0L, playing = false, buffering = true)).playerState)
    }
}
