package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-2205 (FR-1) — only a stall the path caused counts toward what a device can take; a frozen picture or a recovery does not. */
class StallRuleCauseTest {
    private fun stop(after: Long, ms: Long, cause: String?) =
        QoeStall(afterFirstFrameMs = after, positionMs = after, bufferedMs = 9_000, durationMs = ms, phase = "mid", cause = cause)

    @Test fun `an entry with no cause is a delivery stall because an older app sends only those`() {
        assertTrue(stop(0, 100, null).isDeliveryStall)
        assertTrue(stop(0, 100, QoeStallCause.BUFFER_EMPTY).isDeliveryStall)
    }

    @Test fun `a frozen picture and a recovery and a decode signal are not delivery stalls`() {
        for (c in listOf(QoeStallCause.FROZEN_WITH_BUFFER, QoeStallCause.RECOVERY, QoeStallCause.DECODE)) assertFalse(stop(0, 100, c).isDeliveryStall, c)
    }

    @Test fun `a long frozen stop does not count as a stall that lowers the record`() {
        assertEquals(emptyList(), StallRule.counting(listOf(stop(10_000, 9_000, QoeStallCause.FROZEN_WITH_BUFFER))))
        assertFalse(StallRule.anyCounts(listOf(stop(10_000, 9_000, QoeStallCause.RECOVERY))))
    }

    @Test fun `a frozen stop beside a short buffer empty stall does not pair it into a counting one`() {
        val empty = stop(10_000, 300, QoeStallCause.BUFFER_EMPTY)
        val frozen = stop(40_000, 6_000, QoeStallCause.FROZEN_WITH_BUFFER)
        assertEquals(emptyList(), StallRule.counting(listOf(empty, frozen)))
        assertEquals(2, StallRule.counting(listOf(empty, stop(40_000, 400, QoeStallCause.BUFFER_EMPTY), frozen)).size)
    }
}
