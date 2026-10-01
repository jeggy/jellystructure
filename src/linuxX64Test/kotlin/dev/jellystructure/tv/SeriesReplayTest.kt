package dev.jellystructure.tv

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R347 (FR-R347-2) and R343 (FR-R343-4/5/8) — what a stop reports, the 5 % trigger, and a screen's carried shuffle. */
class SeriesReplayTest {
    private val elevenMin = 11 * 60_000L
    private val credits = 9 * 60_000L + 30_000L

    @Test fun `an episode stopped at its credits is reported at the end and ticked`() {
        val d = resolveStop(credits + 1_000L, SessionPlan(durationMs = elevenMin, creditsStartMs = credits))
        assertEquals(elevenMin, d.reportMs); assertTrue(d.markPlayed)
    }

    @Test fun `no marker — stopped at 86 percent — keeps its resume point`() {
        val pos = elevenMin * 86 / 100
        assertEquals(StopDecision(pos, false), resolveStop(pos, SessionPlan(durationMs = elevenMin)))
    }

    @Test fun `past 90 percent the playhead is reported and Jellyfin ticks it itself`() {
        val pos = elevenMin * 95 / 100
        assertEquals(StopDecision(pos, false), resolveStop(pos, SessionPlan(durationMs = elevenMin, creditsStartMs = credits)))
    }

    @Test fun `a shuffled entry stopped halfway puts back its position from before the shuffle`() {
        val d = resolveStop(elevenMin / 2, SessionPlan(durationMs = elevenMin, shuffle = true, priorPositionMs = 123_000L))
        assertEquals(StopDecision(123_000L, false), d)
    }

    @Test fun `a shuffled entry that finished at its credits is ticked`() {
        val d = resolveStop(credits, SessionPlan(durationMs = elevenMin, creditsStartMs = credits, shuffle = true, priorPositionMs = 5_000L))
        assertTrue(d.markPlayed); assertEquals(elevenMin, d.reportMs)
    }

    @Test fun `no plan and no duration reports the playhead`() = assertEquals(StopDecision(42L, false), resolveStop(42L, null))

    @Test fun `Start over clears at 5 percent — or after a minute with no duration`() {
        assertEquals(33_000L, startOverThresholdMs(elevenMin))
        assertEquals(60_000L, startOverThresholdMs(0L))
    }

    @Test fun `a carried shuffle advances on the head — ends on any other id — and is cleared after the last`() = runBlocking {
        val s = ScreenShuffles()
        val c = s.onPlay("tv", "a", listOf("b", "c"), startOver = false)!!
        assertTrue(c.shuffled); assertEquals(listOf("b", "c"), c.rest)
        assertEquals("a", s.forStart("tv", "a")?.currentId)
        assertNull(s.forStart("tv", "b"), "only the current id counts as shuffled")
        val c2 = s.onPlay("tv", "b", emptyList(), startOver = false)!!   // the screen's own Next posts the head
        assertEquals(listOf("c"), c2.rest); assertTrue(c2.shuffled)
        val c3 = s.onPlay("tv", "c", emptyList(), startOver = false)!!
        assertTrue(c3.rest.isEmpty(), "the last entry: no next")
        assertNull(s.onPlay("tv", "x", emptyList(), startOver = false), "any other id leaves the shuffle")
        assertNull(s.forStart("tv", "x"))
    }

    @Test fun `a picked id that is not the head ends the shuffle — and Start over alone is a context of its own`() = runBlocking {
        val s = ScreenShuffles()
        s.onPlay("tv", "a", listOf("b", "c"), startOver = false)
        assertNull(s.onPlay("tv", "c", emptyList(), startOver = false))
        val so = s.onPlay("tv", "s1e1", emptyList(), startOver = true)!!
        assertTrue(so.startOver); assertFalse(so.shuffled)
        s.clear("tv"); assertNull(s.forStart("tv", "s1e1"))
    }
}
