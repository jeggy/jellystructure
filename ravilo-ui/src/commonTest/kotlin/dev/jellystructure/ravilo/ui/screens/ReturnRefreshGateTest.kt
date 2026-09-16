package dev.jellystructure.ravilo.ui.screens

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R248 (FR-R248-1/2) — one refresh per return, not two; the server's push wins when it arrives first. */
class ReturnRefreshGateTest {

    @Test
    fun `a plain return refreshes`() {
        val gate = ReturnRefreshGate()
        gate.onLeave()
        assertTrue(gate.consumeReturn())
    }

    @Test
    fun `a push that refreshed the store while away skips the return refresh once`() {
        val gate = ReturnRefreshGate()
        gate.onLeave()
        gate.onEventRefresh()
        assertFalse(gate.consumeReturn())
        // The next round starts clean.
        gate.onLeave()
        assertTrue(gate.consumeReturn())
    }

    @Test
    fun `a push while the screen is visible says nothing about the next return`() {
        val gate = ReturnRefreshGate()
        gate.onEventRefresh() // visible: an event refreshed what is on screen right now
        gate.onLeave()
        assertTrue(gate.consumeReturn()) // the stop that happened while away still needs a re-pull
    }

    @Test
    fun `a first entry with no leave refreshes`() {
        assertTrue(ReturnRefreshGate().consumeReturn())
    }
}
