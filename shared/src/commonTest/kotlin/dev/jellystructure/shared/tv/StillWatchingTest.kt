package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-5843 (FR-1) — the "Are you still watching?" rule. */
class StillWatchingTest {
    private val min = 60_000L

    @Test
    fun `episodes 1 2 and 3 play on their own and the 4th asks`() {
        val s = StillWatching(tvClass = false)
        s.onInput(0L)
        assertFalse(s.decide(20 * min), "1 -> 2")
        assertFalse(s.decide(40 * min), "2 -> 3")
        assertTrue(s.decide(60 * min), "3 -> 4 asks")
    }

    @Test
    fun `an ask does not count as an advance`() {
        val s = StillWatching(tvClass = false)
        repeat(2) { s.decide(0L) }
        assertTrue(s.decide(0L))
        assertTrue(s.decide(0L))
        assertEquals(2, s.unattended)
    }

    @Test
    fun `a TV needs 90 minutes since the last input as well`() {
        val s = StillWatching(tvClass = true)
        s.onInput(0L)
        assertFalse(s.decide(20 * min))
        assertFalse(s.decide(40 * min))
        assertFalse(s.decide(60 * min), "three short episodes in an hour do not ask on a TV")
        assertFalse(s.decide(89 * min))
        assertTrue(s.decide(90 * min))
    }

    @Test
    fun `phone web and desktop ignore the clock`() {
        val s = StillWatching(tvClass = false)
        s.onInput(0L)
        assertFalse(s.decide(1L))
        assertFalse(s.decide(2L))
        assertTrue(s.decide(3L))
    }

    @Test
    fun `input zeroes the count and the clock`() {
        val s = StillWatching(tvClass = true)
        s.onInput(0L)
        repeat(2) { s.decide(10 * min) }
        s.onInput(100 * min)
        assertEquals(0, s.unattended)
        assertEquals(100 * min, s.lastInputMs)
        assertFalse(s.decide(120 * min))
        assertFalse(s.decide(140 * min))
        assertFalse(s.decide(160 * min), "only 60 minutes since the input")
        assertTrue(s.decide(190 * min))
    }

    @Test
    fun `one input during the third episode pushes the ask three episodes on`() {
        val s = StillWatching(tvClass = false)
        assertFalse(s.decide(0L))
        assertFalse(s.decide(0L))
        s.onInput(0L)
        assertFalse(s.decide(0L))
        assertFalse(s.decide(0L))
        assertTrue(s.decide(0L))
    }

    @Test
    fun `the pure rule matches the constants`() {
        assertFalse(stillWatchingDue(0, STILL_WATCHING_TV_MS, tvClass = false))
        assertFalse(stillWatchingDue(1, STILL_WATCHING_TV_MS, tvClass = false))
        assertTrue(stillWatchingDue(STILL_WATCHING_EPISODES - 1, 0L, tvClass = false))
        assertFalse(stillWatchingDue(STILL_WATCHING_EPISODES - 1, STILL_WATCHING_TV_MS - 1, tvClass = true))
        assertTrue(stillWatchingDue(STILL_WATCHING_EPISODES - 1, STILL_WATCHING_TV_MS, tvClass = true))
        assertEquals(30 * min, STILL_WATCHING_CLOSE_MS)
    }
}
