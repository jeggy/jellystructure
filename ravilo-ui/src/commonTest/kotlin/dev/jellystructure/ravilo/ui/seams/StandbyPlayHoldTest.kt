package dev.jellystructure.ravilo.ui.seams

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R292 (FR-R292-14, DEB-569) — a play through the session is refused for 15 s after a standby return, and only then. */
class StandbyPlayHoldTest {
    @Test
    fun `with no standby return nothing is refused`() {
        assertFalse(StandbyPlayHold().refusesPlay(1_000))
    }

    @Test
    fun `inside the window a play is refused, every time`() {
        val h = StandbyPlayHold()
        h.arm(10_000)
        assertTrue(h.refusesPlay(10_000))
        assertTrue(h.refusesPlay(10_000 + StandbyPlayHold.WINDOW_MS - 1))
        assertTrue(h.refusesPlay(12_000), "a refusal does not spend the window")
    }

    @Test
    fun `at and after the window a play is allowed, and stays allowed`() {
        val h = StandbyPlayHold()
        h.arm(10_000)
        assertFalse(h.refusesPlay(10_000 + StandbyPlayHold.WINDOW_MS))
        assertFalse(h.refusesPlay(10_000 + 1), "an expired window is spent, not re-opened by an earlier clock reading")
    }

    @Test
    fun `a new cast ends the window`() {
        val h = StandbyPlayHold()
        h.arm(10_000)
        h.released()
        assertFalse(h.refusesPlay(10_100))
    }

    @Test
    fun `a second standby return opens a fresh window`() {
        val h = StandbyPlayHold()
        h.arm(0)
        assertFalse(h.refusesPlay(20_000))
        h.arm(100_000)
        assertTrue(h.refusesPlay(101_000))
    }

    @Test
    fun `a clock reading before the arming is not a refusal`() {
        val h = StandbyPlayHold()
        h.arm(50_000)
        assertFalse(h.refusesPlay(49_000))
    }
}
