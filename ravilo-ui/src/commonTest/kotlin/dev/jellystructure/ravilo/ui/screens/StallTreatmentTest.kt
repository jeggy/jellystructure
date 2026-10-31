package dev.jellystructure.ravilo.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

/** DEB-3102 (FR-5) — a short wait draws nothing, and a stall caps one rung down at once. */
class StallTreatmentTest {
    @Test fun `a stall draws nothing for its first 2 s`() {
        assertEquals(2_000L, bufferMomentDebounceMs(PlBufferMoment.STALL))
    }

    @Test fun `the cold start and a seek keep R218's 400 ms`() {
        assertEquals(400L, bufferMomentDebounceMs(PlBufferMoment.COLD))
        assertEquals(400L, bufferMomentDebounceMs(PlBufferMoment.SEEK))
    }

    @Test fun `the in-place cap is not delayed`() {
        assertEquals(0L, IN_PLACE_CAP_AFTER_MS)
    }
}
