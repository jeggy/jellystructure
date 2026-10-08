package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 309 (FR-309-4/-5) — one rung up at a time, down before the buffer runs dry. */
class LadderRulesTest {
    private val rungs = listOf(24_000_000L, 12_000_000L, 6_000_000L, 2_400_000L)

    @Test fun `no bound before anything plays`() {
        assertNull(LadderRules.allowedMaxBps(rungs, null, 0, null, oursEncoder = false))
        assertNull(LadderRules.allowedMaxBps(emptyList(), 6_000_000, 40_000, null, oursEncoder = false))
    }

    @Test fun `a climb is one rung — it waits for 30 s of buffer on Jellyfin's jobs`() {
        assertEquals(6_000_000, LadderRules.allowedMaxBps(rungs, 6_000_000, 29_999, 25_000, oursEncoder = false))
        assertEquals(12_000_000, LadderRules.allowedMaxBps(rungs, 6_000_000, 30_000, 25_000, oursEncoder = false))
        assertEquals(24_000_000, LadderRules.allowedMaxBps(rungs, 24_000_000, 60_000, 50_000, oursEncoder = false))   // the top
    }

    @Test fun `on our encoder a climb waits for the stock 10 s`() {
        assertEquals(6_000_000, LadderRules.allowedMaxBps(rungs, 6_000_000, 9_999, 5_000, oursEncoder = true))
        assertEquals(12_000_000, LadderRules.allowedMaxBps(rungs, 6_000_000, 10_000, 5_000, oursEncoder = true))
    }

    @Test fun `under 20 s and falling steps down — again each time it keeps falling`() {
        assertEquals(12_000_000, LadderRules.allowedMaxBps(rungs, 24_000_000, 19_000, 21_000, oursEncoder = true))
        assertEquals(6_000_000, LadderRules.allowedMaxBps(rungs, 12_000_000, 15_000, 19_000, oursEncoder = true))
        assertEquals(2_400_000, LadderRules.allowedMaxBps(rungs, 2_400_000, 1_000, 15_000, oursEncoder = true))   // the floor
        // Under 20 s but rising (the lower rung is catching up): hold, no climb yet.
        assertEquals(6_000_000, LadderRules.allowedMaxBps(rungs, 6_000_000, 15_000, 12_000, oursEncoder = false))
    }

    @Test fun `falling means more than a segment's jitter`() {
        assertFalse(LadderRules.mustStepDown(19_800, 20_000))
        assertTrue(LadderRules.mustStepDown(19_400, 20_000))
        assertFalse(LadderRules.mustStepDown(19_000, null))
        assertFalse(LadderRules.mustStepDown(25_000, 40_000))
    }
}
