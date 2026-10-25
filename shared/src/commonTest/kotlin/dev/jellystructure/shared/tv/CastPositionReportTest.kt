package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-2456 (DEB-2252, FR-R330-7a) — when the receiver says where a song is, and the fields it says it in. */
class CastPositionReportTest {
    private fun first(music: Boolean = true, start: Long = 0L, time: Long = 1_000L, sent: String? = null, key: String = "a#1") =
        castFirstPositionDue(music, start, time, sent, key)

    @Test
    fun `the first time update a second past the start reports once`() {
        assertTrue(first(time = 1_000L))
        assertTrue(first(start = 30_000L, time = 31_400L))
    }

    @Test
    fun `less than a second past the start does not report`() {
        assertFalse(first(time = 999L))
        assertFalse(first(start = 30_000L, time = 30_000L))
    }

    @Test
    fun `a load already reported for is not reported twice but the next load is`() {
        assertFalse(first(sent = "a#1", key = "a#1"))
        assertTrue(first(sent = "a#1", key = "a#2"))
        assertTrue(first(sent = "a#1", key = "b#3"))
    }

    @Test
    fun `a film never reports its place`() {
        assertFalse(first(music = false))
        assertFalse(castPeriodicPositionDue(false, true, 0L, 60_000L))
    }

    @Test
    fun `a playing song reports every fifteen seconds`() {
        assertFalse(castPeriodicPositionDue(true, true, 1_000L, 15_999L))
        assertTrue(castPeriodicPositionDue(true, true, 1_000L, 16_000L))
    }

    @Test
    fun `a paused or buffering song does not report on the clock`() {
        assertFalse(castPeriodicPositionDue(true, false, 0L, 60_000L))
    }

    @Test
    fun `the place and the play state survive the wire and an older message has neither`() {
        val said = CastReceiverMessage(type = "status", itemId = "a", positionMs = 41_250L, playing = true)
        val back = RaviloWireJsonWithDefaults.decodeFromString(CastReceiverMessage.serializer(), RaviloWireJsonWithDefaults.encodeToString(CastReceiverMessage.serializer(), said))
        assertEquals(41_250L, back.positionMs)
        assertEquals(true, back.playing)
        val text = RaviloWireJsonWithDefaults.encodeToString(CastReceiverMessage.serializer(), said)
        assertTrue("\"position_ms\":41250" in text, text)
        val old = RaviloWireJsonWithDefaults.decodeFromString(CastReceiverMessage.serializer(), """{"type":"status","item_id":"a"}""")
        assertNull(old.positionMs)
        assertNull(old.playing)
    }
}
