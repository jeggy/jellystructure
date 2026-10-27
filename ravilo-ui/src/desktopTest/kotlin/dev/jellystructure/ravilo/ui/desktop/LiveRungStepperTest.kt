package dev.jellystructure.ravilo.ui.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** DEB-2627 (FR-3) — Linux plays a live ladder by choosing the rung itself: the start from the link estimate, a step per bad stall, never up. */
class LiveRungStepperTest {
    private val rungs = listOf(7_700_000L, 4_000_000L, 2_000_000L, 1_000_000L, 560_000L)

    @Test fun `the start rung is the highest at or below 0_7 of the estimate`() {
        assertEquals(4_000_000L, LiveRungStepper.startRung(rungs, 6_000_000L))   // 4.2 Mb/s of headroom
        assertEquals(7_700_000L, LiveRungStepper.startRung(rungs, 12_000_000L))  // 8.4 Mb/s
        assertEquals(1_000_000L, LiveRungStepper.startRung(rungs, 2_857_142L))   // 1.99 Mb/s is just under 2 Mb/s: the next rung down
        assertEquals(2_000_000L, LiveRungStepper.startRung(rungs, 2_857_143L))   // 2.0 Mb/s holds exactly
    }

    @Test fun `a line under every rung starts on the bottom one`() {
        assertEquals(560_000L, LiveRungStepper.startRung(rungs, 300_000L))
    }

    @Test fun `no estimate starts on max`() {
        assertNull(LiveRungStepper.startRung(rungs, null))
        assertNull(LiveRungStepper(rungs, null).startBitrate)
        assertEquals(7_700_000L, LiveRungStepper(rungs, null).current)
    }

    @Test fun `the next rung below and the bottom stays`() {
        assertEquals(4_000_000L, LiveRungStepper.rungBelow(rungs, 7_700_000L))
        assertEquals(560_000L, LiveRungStepper.rungBelow(rungs, 1_000_000L))
        assertEquals(560_000L, LiveRungStepper.rungBelow(rungs, 560_000L))
    }

    @Test fun `a rebuffer over five seconds steps down one rung`() {
        val s = LiveRungStepper(rungs, null)
        assertNull(s.onSample(0, buffering = false))
        assertNull(s.onSample(1_000, buffering = true))
        assertNull(s.onSample(6_000, buffering = true))   // 5 s exactly: not over
        assertEquals(4_000_000L, s.onSample(6_001, buffering = true))
        assertEquals(4_000_000L, s.current)
    }

    @Test fun `a short rebuffer alone steps nothing`() {
        val s = LiveRungStepper(rungs, null)
        s.onSample(1_000, true); s.onSample(3_000, true)
        assertNull(s.onSample(4_000, false))
        assertEquals(7_700_000L, s.current)
    }

    @Test fun `a second rebuffer within thirty seconds steps down at once`() {
        val s = LiveRungStepper(rungs, null)
        s.onSample(1_000, true)
        s.onSample(2_000, false)
        assertEquals(4_000_000L, s.onSample(31_000, true))   // 30 s after the first began
    }

    @Test fun `a second rebuffer after thirty seconds is a first one again`() {
        val s = LiveRungStepper(rungs, null)
        s.onSample(1_000, true)
        s.onSample(2_000, false)
        assertNull(s.onSample(31_001, true))
        assertEquals(7_700_000L, s.current)
    }

    @Test fun `it never climbs and the bottom rung stays without a reload`() {
        val s = LiveRungStepper(listOf(2_000_000L, 560_000L), 1_000_000L)   // 0.7 Mb/s: starts on the bottom
        assertEquals(560_000L, s.current)
        assertNull(s.onSample(0, true))
        assertNull(s.onSample(6_001, true))
        assertEquals(560_000L, s.current)
        // a long calm stretch does not climb either
        assertNull(s.onSample(100_000, false))
        assertEquals(560_000L, s.current)
    }

    @Test fun `a step starts the watch afresh so the reload's own wait is not a stall`() {
        val s = LiveRungStepper(rungs, null)
        s.onSample(0, true)
        assertEquals(4_000_000L, s.onSample(5_001, true))
        s.reloaded()
        assertNull(s.onSample(5_500, true))            // the reload's first wait: a first stall again
        assertEquals(2_000_000L, s.onSample(10_501, true))
    }
}
