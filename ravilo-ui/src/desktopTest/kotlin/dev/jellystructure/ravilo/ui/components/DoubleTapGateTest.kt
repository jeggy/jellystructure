package dev.jellystructure.ravilo.ui.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-2686 (FR-1, FR-3) — the rule behind the Profile double tap, with no Compose. */
class DoubleTapGateTest {
    @Test
    fun `a single tap is not a double`() {
        assertFalse(DoubleTapGate(300).tap(1_000))
    }

    @Test
    fun `a second tap at the timeout is a double`() {
        val gate = DoubleTapGate(300)
        gate.tap(1_000)
        assertTrue(gate.tap(1_300))
    }

    @Test
    fun `a second tap one millisecond past the timeout is a first tap`() {
        val gate = DoubleTapGate(300)
        gate.tap(1_000)
        assertFalse(gate.tap(1_301))
        // and it armed the gate: a tap inside the window after it is the double
        assertTrue(gate.tap(1_400))
    }

    @Test
    fun `a third tap straight after a double is a first tap`() {
        val gate = DoubleTapGate(300)
        gate.tap(1_000)
        assertTrue(gate.tap(1_100))
        assertFalse(gate.tap(1_200))
    }

    @Test
    fun `reset between two taps makes the second a first tap`() {
        val gate = DoubleTapGate(300)
        gate.tap(1_000)
        gate.reset()
        assertFalse(gate.tap(1_100))
    }
}
