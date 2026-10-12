package dev.jellystructure.ravilo.ui.screens

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R372 (FR-R372-10, amendment 2026-10-12, acceptance A5) — a film watched to its very end ends its session at that
 * moment instead of heartbeating *paused* at the end position for as long as the app lives.
 */
class EndOfPlayTest {

    @Test fun `a film that runs out with nothing to advance to ends its session`() {
        assertTrue(endsPlayNow(loaded = true, ended = true, hasNext = false, advanceRequested = false))
    }

    @Test fun `an episode with a next one is unchanged`() {
        assertFalse(endsPlayNow(loaded = true, ended = true, hasNext = true, advanceRequested = false))
    }

    @Test fun `an advance already asked for ends nothing here`() {
        assertFalse(endsPlayNow(loaded = true, ended = true, hasNext = false, advanceRequested = true))
    }

    @Test fun `a play that has not run out, or a stream not loaded for this item, ends nothing`() {
        assertFalse(endsPlayNow(loaded = true, ended = false, hasNext = false, advanceRequested = false))
        assertFalse(endsPlayNow(loaded = false, ended = true, hasNext = false, advanceRequested = false))
    }

    /** The poll loop asks four times a second, and the credits card is usually already up: exactly one stop. */
    @Test fun `the end of one film is acted on once, however many ticks see it`() {
        val w = EndOfPlayWatch()
        assertTrue(w.observe("film", loaded = true, ended = true, hasNext = false, advanceRequested = false))
        assertFalse(w.observe("film", loaded = true, ended = true, hasNext = false, advanceRequested = false))
        assertFalse(w.observe("film", loaded = true, ended = true, hasNext = false, advanceRequested = false))
    }

    @Test fun `the next item arms it again`() {
        val w = EndOfPlayWatch()
        assertTrue(w.observe("s01e01", loaded = true, ended = true, hasNext = false, advanceRequested = false))
        assertFalse(w.observe("s01e01", loaded = true, ended = true, hasNext = false, advanceRequested = false))
        assertTrue(w.observe("s01e02", loaded = true, ended = true, hasNext = false, advanceRequested = false))
        // …and back to the first one (a viewer who picked it again from the rail) is a new play, not the latched one.
        assertTrue(w.observe("s01e01", loaded = true, ended = true, hasNext = false, advanceRequested = false))
    }

    @Test fun `an episode that advances is never latched, so its own end is still free`() {
        val w = EndOfPlayWatch()
        assertFalse(w.observe("s01e01", loaded = true, ended = true, hasNext = true, advanceRequested = false))
        assertFalse(w.observe("s01e01", loaded = true, ended = true, hasNext = true, advanceRequested = true))
        // The last episode of the season: nothing to advance to, and it ends.
        assertTrue(w.observe("s01e10", loaded = true, ended = true, hasNext = false, advanceRequested = false))
    }

    @Test fun `no item means no decision`() {
        assertFalse(EndOfPlayWatch().observe(null, loaded = true, ended = true, hasNext = false, advanceRequested = false))
    }
}
