package dev.jellystructure.ravilo.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/** R383 (FR-R383-3) — the stepped phase wraps once per period and starts at 0. */
class SteppedPhaseTest {
    @Test fun `a phase starts at zero, reaches the middle at half a period and wraps`() {
        assertEquals(0f, phaseAt(0, 900))
        assertEquals(0.5f, phaseAt(450, 900))
        assertEquals(0f, phaseAt(900, 900))
        assertEquals(0.5f, phaseAt(2_250, 900))
    }
}
