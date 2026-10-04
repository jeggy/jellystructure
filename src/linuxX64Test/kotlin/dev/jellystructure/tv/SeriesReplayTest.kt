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

    // ── R375 (FR-R375-6) — which LastPlayedDate a stop puts back ──

    private val prior = "2026-08-12T20:00:00.0000000Z"
    private val anchor = "2026-10-01T18:30:00.2500000Z"

    @Test fun `a shuffled play that did not finish puts its prior date back`() {
        assertEquals(prior, lastPlayedRestore(SessionPlan(shuffle = true, priorLastPlayed = prior, anchorLastPlayed = anchor), finished = false))
    }

    @Test fun `a never-played episode left unfinished writes no date`() {
        assertNull(lastPlayedRestore(SessionPlan(shuffle = true, priorLastPlayed = null, anchorLastPlayed = anchor), finished = false))
    }

    @Test fun `a finished shuffle with no prior date lands one second before the anchor`() {
        assertEquals("2026-10-01T18:29:59.2500000Z", lastPlayedRestore(SessionPlan(shuffle = true, anchorLastPlayed = anchor), finished = true))
    }

    @Test fun `a finished shuffle keeps an older prior date — and caps a newer one at anchor minus one second`() {
        assertEquals(prior, lastPlayedRestore(SessionPlan(shuffle = true, priorLastPlayed = prior, anchorLastPlayed = anchor), finished = true))
        val newer = "2026-10-03T09:00:00.0000000Z"   // briefly opened after the last finish
        assertEquals("2026-10-01T18:29:59.2500000Z", lastPlayedRestore(SessionPlan(shuffle = true, priorLastPlayed = newer, anchorLastPlayed = anchor), finished = true))
    }

    @Test fun `a finished shuffle with nothing finished before leaves Jellyfins date`() {
        assertNull(lastPlayedRestore(SessionPlan(shuffle = true, priorLastPlayed = prior), finished = true))
    }

    @Test fun `a replay of a watched episode stopped early puts its date back — finished it moves the position`() {
        val plan = SessionPlan(watchedAtStart = true, priorLastPlayed = prior)
        assertEquals(prior, lastPlayedRestore(plan, finished = false), "under 5 percent Jellyfin keeps Played: without this it reads as the newest finish")
        assertNull(lastPlayedRestore(plan, finished = true))
    }

    @Test fun `an ordinary first play or a film writes nothing`() {
        assertNull(lastPlayedRestore(SessionPlan(priorLastPlayed = prior), finished = false))
        assertNull(lastPlayedRestore(SessionPlan(), finished = true))
        assertNull(lastPlayedRestore(null, finished = false))
    }
}
