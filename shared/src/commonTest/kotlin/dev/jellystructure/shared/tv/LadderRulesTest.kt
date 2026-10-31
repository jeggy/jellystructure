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

    @Test fun `on our encoder a climb waits for the stock 10 s and then goes straight to the top`() {
        assertEquals(6_000_000, LadderRules.allowedMaxBps(rungs, 6_000_000, 9_999, 5_000, oursEncoder = true))
        assertEquals(24_000_000, LadderRules.allowedMaxBps(rungs, 6_000_000, 10_000, 5_000, oursEncoder = true))   // DEB-3104 (FR-7)
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

    // ── DEB-2205 (FR-5.3) — nothing climbs for 2 minutes after a recovery ──
    @Test fun `a blocked climb stays on the rung however much is buffered`() {
        assertEquals(6_000_000, LadderRules.allowedMaxBps(rungs, 6_000_000, 90_000, 80_000, oursEncoder = true, climbBlocked = true))
        assertEquals(6_000_000, LadderRules.allowedMaxBps(rungs, 6_000_000, 90_000, 80_000, oursEncoder = false, climbBlocked = true))
        assertEquals(12_000_000, LadderRules.allowedMaxBps(rungs, 6_000_000, 90_000, 80_000, oursEncoder = false), "unblocked it climbs")
    }

    @Test fun `a blocked climb still steps down`() {
        assertEquals(12_000_000, LadderRules.allowedMaxBps(rungs, 24_000_000, 19_000, 21_000, oursEncoder = true, climbBlocked = true))
    }

    @Test fun `a blocked climb on the lowest rung stays on it`() {
        assertEquals(2_400_000, LadderRules.allowedMaxBps(rungs, 2_400_000, 90_000, 80_000, oursEncoder = true, climbBlocked = true))
    }

    // ── DEB-3102 (FR-4) — retries, the falling buffer, and the abandon rule ──
    @Test fun `retry delays are 0_5 1 2 4 then 5 s for good`() {
        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L, 5_000L, 5_000L, 5_000L), (1..7).map { LadderRules.retryDelayMs(it) })
        assertEquals(500L, LadderRules.retryDelayMs(0), "never below the first")
    }

    @Test fun `a load is retried without end while the buffer holds picture and five times once it is empty`() {
        assertEquals(Int.MAX_VALUE, LadderRules.retryLimit(1))
        assertEquals(5, LadderRules.retryLimit(0))
    }

    @Test fun `a buffer that fell for 6 s and is under 30 s steps down even when it is above 20 s`() {
        assertEquals(12_000_000, LadderRules.allowedMaxBps(rungs, 24_000_000, 25_000, 25_000, oursEncoder = true, fallenForWindow = true))
        assertEquals(24_000_000, LadderRules.allowedMaxBps(rungs, 24_000_000, 25_000, 25_000, oursEncoder = true, fallenForWindow = false))
        assertEquals(24_000_000, LadderRules.allowedMaxBps(rungs, 24_000_000, 31_000, 31_000, oursEncoder = true, fallenForWindow = true), "30 s or more is not low")
    }

    @Test fun `the trend needs a full window and a real fall`() {
        val t = BufferTrend()
        assertFalse(t.observe(0, 25_000))
        assertFalse(t.observe(3_000, 22_000), "only 3 s of history")
        assertTrue(t.observe(6_000, 19_000), "6 s ago it was 25 s")
        assertTrue(t.observe(9_000, 16_000))
    }

    @Test fun `a sawtooth that refills every segment is not falling`() {
        val t = BufferTrend()
        var fallen = false
        for (s in 0..40) {
            val phase = s % 6   // 6 s segments: the buffer drains 6 s and refills at the boundary
            fallen = fallen || t.observe(s * 1_000L, 24_000L - phase * 1_000L)
        }
        assertFalse(fallen)
    }

    @Test fun `a buffer holding level is not falling and a reset forgets the history`() {
        val t = BufferTrend()
        for (s in 0..20) assertFalse(t.observe(s * 1_000L, 20_000))
        val u = BufferTrend()
        u.observe(0, 25_000)
        u.reset()
        assertFalse(u.observe(6_000, 10_000), "no history after a reset")
    }

    @Test fun `a segment that would land after the buffer minus 5 s is abandoned`() {
        // 12 s buffered: 7 s to spare. 2 MB of 6 MB in 2 s = 1 MB/s, so 4 s more: lands at 6 s. Kept.
        assertFalse(LadderRules.shouldAbandonSegment(2_000, 2_000_000, 6_000_000, 8_000_000, 12_000, lowerRungExists = true))
        // 1 MB in 2 s = 0.5 MB/s: 10 s more, lands at 12 s. Abandoned.
        assertTrue(LadderRules.shouldAbandonSegment(2_000, 1_000_000, 6_000_000, 8_000_000, 12_000, lowerRungExists = true))
    }

    @Test fun `with no lower rung nothing is abandoned`() {
        assertFalse(LadderRules.shouldAbandonSegment(20_000, 0, 6_000_000, 8_000_000, 6_000, lowerRungExists = false))
    }

    @Test fun `a load with no bytes yet is judged on the estimate and on its age`() {
        // 30 s buffered, 25 s to spare; 6 MB at 8 Mb/s is 6 s: fine.
        assertFalse(LadderRules.shouldAbandonSegment(1_500, 0, 6_000_000, 8_000_000, 30_000, lowerRungExists = true))
        // Still nothing after 26 s: past the budget whatever the rate.
        assertTrue(LadderRules.shouldAbandonSegment(26_000, 0, 6_000_000, 0, 30_000, lowerRungExists = true))
        // The estimate says 6 MB takes 48 s at 1 Mb/s.
        assertTrue(LadderRules.shouldAbandonSegment(1_500, 0, 6_000_000, 1_000_000, 30_000, lowerRungExists = true))
    }

    @Test fun `a first second is never judged and a buffer under 5 s abandons after it`() {
        assertFalse(LadderRules.shouldAbandonSegment(999, 0, 6_000_000, 1_000, 2_000, lowerRungExists = true))
        assertTrue(LadderRules.shouldAbandonSegment(1_000, 500_000, 6_000_000, 8_000_000, 4_000, lowerRungExists = true))
    }
}
