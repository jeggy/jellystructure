package dev.jellystructure.ravilo.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R290 (FR-R290-1/2/4) — what is on screen during a start is one derived phase, and the latch opens once. */
class PlayerStartPhaseTest {
    @Test
    fun `black under 400 ms then the start screen then playing once latched`() {
        assertEquals(StartPhase.BLACK, startPhase(latched = false, startScreenDue = false))
        assertEquals(StartPhase.START, startPhase(latched = false, startScreenDue = true))
        assertEquals(StartPhase.PLAYING, startPhase(latched = true, startScreenDue = true))
        assertEquals(StartPhase.PLAYING, startPhase(latched = true, startScreenDue = false), "a fast direct play is playing before the start screen was ever due")
    }

    @Test
    fun `the latch needs a ready session with a rendered frame and a settled resolver`() {
        assertTrue(startLatchOpens(sessionReady = true, renderedFirstFrame = true, resolverSettled = true, restreamPending = false, renderedForMs = 0))
        assertFalse(startLatchOpens(sessionReady = false, renderedFirstFrame = true, resolverSettled = true, restreamPending = false, renderedForMs = 0), "negotiating")
        assertFalse(startLatchOpens(sessionReady = true, renderedFirstFrame = false, resolverSettled = true, restreamPending = false, renderedForMs = 0), "no frame yet")
        assertFalse(startLatchOpens(sessionReady = true, renderedFirstFrame = true, resolverSettled = false, restreamPending = false, renderedForMs = 100), "tracks not resolved yet")
    }

    @Test
    fun `a stream the resolver sent back for a restream never opens the latch`() {
        // FR-R290-4 — the discarded stream rendered a frame and its resolver ran; it asked for another stream.
        assertFalse(startLatchOpens(sessionReady = true, renderedFirstFrame = true, resolverSettled = true, restreamPending = true, renderedForMs = 5_000))
    }

    @Test
    fun `a silent stream opens the latch after the grace period`() {
        assertFalse(startLatchOpens(sessionReady = true, renderedFirstFrame = true, resolverSettled = false, restreamPending = false, renderedForMs = RESOLVER_GRACE_MS - 1))
        assertTrue(startLatchOpens(sessionReady = true, renderedFirstFrame = true, resolverSettled = false, restreamPending = false, renderedForMs = RESOLVER_GRACE_MS))
    }
}
