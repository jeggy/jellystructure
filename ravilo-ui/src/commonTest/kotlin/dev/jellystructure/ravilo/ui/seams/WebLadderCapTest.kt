package dev.jellystructure.ravilo.ui.seams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/** Phase 309 (FR-309-16) — hls.js bounded by the shared ladder rule. */
class WebLadderCapTest {
    // hls.js's order: ascending, index = level.
    private val levels = listOf(2_400_000L, 6_000_000L, 12_000_000L, 24_000_000L)

    @Test fun `no bound before a level plays or without a ladder`() {
        val c = WebLadderCap()
        assertNull(c.evaluate(levels, -1, 40_000, oursEncoder = true))
        assertNull(c.evaluate(listOf(6_000_000L), 0, 40_000, oursEncoder = true))
        assertNull(c.evaluate(emptyList(), 0, 40_000, oursEncoder = true))
    }

    @Test fun `a climb is one level — and waits for the buffer`() {
        val c = WebLadderCap()
        assertEquals(WebLadderCap.Decision(1, false), c.evaluate(levels, 1, 5_000, oursEncoder = true))    // holds the rung playing
        assertEquals(WebLadderCap.Decision(2, false), c.evaluate(levels, 1, 10_000, oursEncoder = true))   // one up on our encoder
        assertEquals(WebLadderCap.Decision(1, false), c.evaluate(levels, 1, 29_000, oursEncoder = false))  // Jellyfin's jobs want 30 s
        assertEquals(WebLadderCap.Decision(2, false), c.evaluate(levels, 1, 30_000, oursEncoder = false))
        assertEquals(WebLadderCap.Decision(3, false), c.evaluate(levels, 3, 60_000, oursEncoder = false))   // the top
    }

    @Test fun `under 20 s and falling steps down — again while it keeps falling, never below the lowest`() {
        val c = WebLadderCap()
        assertEquals(WebLadderCap.Decision(3, false), c.evaluate(levels, 3, 25_000, oursEncoder = true))
        assertEquals(WebLadderCap.Decision(2, true), c.evaluate(levels, 3, 18_000, oursEncoder = true))
        assertEquals(WebLadderCap.Decision(1, true), c.evaluate(levels, 2, 14_000, oursEncoder = true))
        assertEquals(WebLadderCap.Decision(0, true), c.evaluate(levels, 1, 9_000, oursEncoder = true))
        val floor = c.evaluate(levels, 0, 3_000, oursEncoder = true)!!
        assertEquals(0, floor.capLevel); assertFalse(floor.stepDown)
    }

    @Test fun `under 20 s but rising holds`() {
        val c = WebLadderCap()
        c.evaluate(levels, 1, 12_000, oursEncoder = false)
        assertEquals(WebLadderCap.Decision(1, false), c.evaluate(levels, 1, 15_000, oursEncoder = false))
    }

    @Test fun `levels of equal bandwidth map to the highest index`() {
        val c = WebLadderCap()
        val d = c.evaluate(listOf(2_400_000L, 6_000_000L, 6_000_000L, 12_000_000L), 0, 40_000, oursEncoder = true)!!
        assertEquals(2, d.capLevel)
    }

    @Test fun `a seek forgets the last sample — an emptied buffer after it is not a fall`() {
        val c = WebLadderCap()
        c.evaluate(levels, 2, 40_000, oursEncoder = true)
        c.reset()
        val d = c.evaluate(levels, 2, 0, oursEncoder = true)!!
        assertFalse(d.stepDown); assertEquals(2, d.capLevel)
        assertTrue(c.evaluate(levels, 2, 0, oursEncoder = true)!!.capLevel == 2)
    }
}
