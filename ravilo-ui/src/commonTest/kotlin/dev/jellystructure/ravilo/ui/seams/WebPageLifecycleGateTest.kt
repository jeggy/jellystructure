package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.ravilo.ui.seams.WebPageLifecycleGate.Action
import dev.jellystructure.ravilo.ui.seams.WebPageLifecycleGate.Signal
import kotlin.test.Test
import kotlin.test.assertEquals

/** R376 (FR-R376-4f–4h, DEB-2998) — a computer's hidden tab keeps its film; leaving still stops once; a phone still stops. */
class WebPageLifecycleGateTest {
    @Test
    fun `on a computer hidden and visible answer nothing in either order and any number of times`() {
        val g = WebPageLifecycleGate(phone = false)
        repeat(3) {
            assertEquals(Action.None, g.on(Signal.HIDDEN))
            assertEquals(Action.None, g.on(Signal.VISIBLE))
        }
        assertEquals(Action.None, g.on(Signal.HIDDEN, exempt = true))
        assertEquals(Action.None, g.on(Signal.VISIBLE))
    }

    @Test
    fun `on a computer pagehide answers one stop and a second answers none`() {
        val g = WebPageLifecycleGate(phone = false)
        assertEquals(Action.Stop, g.on(Signal.PAGE_HIDE))
        assertEquals(Action.None, g.on(Signal.PAGE_HIDE))
        assertEquals(Action.None, g.on(Signal.HIDDEN), "a hidden that follows the pagehide is not a second stop")
    }

    @Test
    fun `on a computer the return from the back-forward cache answers one foreground`() {
        val g = WebPageLifecycleGate(phone = false)
        assertEquals(Action.Stop, g.on(Signal.PAGE_HIDE))
        assertEquals(Action.Foreground, g.on(Signal.PAGE_SHOW))
        assertEquals(Action.None, g.on(Signal.VISIBLE), "the visibilitychange that comes with the pageshow is the same return")
        assertEquals(Action.Stop, g.on(Signal.PAGE_HIDE), "a second departure stops again")
    }

    @Test
    fun `a pageshow with no pagehide before it answers nothing`() {
        assertEquals(Action.None, WebPageLifecycleGate(phone = false).on(Signal.PAGE_SHOW))
        assertEquals(Action.None, WebPageLifecycleGate(phone = true).on(Signal.PAGE_SHOW))
    }

    @Test
    fun `on a phone hidden answers one stop and visible one foreground`() {
        val g = WebPageLifecycleGate(phone = true)
        assertEquals(Action.Stop, g.on(Signal.HIDDEN))
        assertEquals(Action.None, g.on(Signal.HIDDEN), "a second hidden is nothing")
        assertEquals(Action.None, g.on(Signal.PAGE_HIDE), "the pagehide that follows the hidden is the same departure")
        assertEquals(Action.Foreground, g.on(Signal.VISIBLE))
        assertEquals(Action.None, g.on(Signal.VISIBLE))
    }

    @Test
    fun `on a phone hidden in picture-in-picture or on AirPlay answers nothing`() {
        val g = WebPageLifecycleGate(phone = true)
        assertEquals(Action.None, g.on(Signal.HIDDEN, exempt = true))
        assertEquals(Action.None, g.on(Signal.VISIBLE))
        assertEquals(Action.Stop, g.on(Signal.PAGE_HIDE), "leaving is never exempt")
    }
}
