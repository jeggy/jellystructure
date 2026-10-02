package dev.jellystructure.ravilo.ui.seams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R356 (FR-R356-6) — connected but silent: ask, then rejoin. */
class CastSilenceWatchTest {
    @Test
    fun `nothing is due without a nudge, however long the receiver is quiet`() {
        val w = CastSilenceWatch()
        assertEquals(CastSilenceAction.NONE, w.tick(600_000, connected = true))
        assertFalse(w.waiting)
    }

    @Test
    fun `on screen it asks at once and rejoins three seconds later if nothing came`() {
        val w = CastSilenceWatch()
        assertEquals(CastSilenceAction.ASK, w.nudge(1_000, askNow = true))
        assertEquals(CastSilenceAction.NONE, w.tick(3_500, connected = true))
        assertEquals(CastSilenceAction.REJOIN, w.tick(4_000, connected = true))
        assertEquals(CastSilenceAction.NONE, w.tick(9_000, connected = true), "one rejoin per nudge")
    }

    @Test
    fun `an answer settles it`() {
        val w = CastSilenceWatch()
        w.nudge(0, askNow = true)
        w.heard()
        assertEquals(CastSilenceAction.NONE, w.tick(10_000, connected = true))
        assertFalse(w.waiting)
    }

    @Test
    fun `after a command it waits three seconds, asks, then rejoins three seconds later`() {
        val w = CastSilenceWatch()
        assertEquals(CastSilenceAction.NONE, w.nudge(0, askNow = false))
        assertEquals(CastSilenceAction.NONE, w.tick(2_900, connected = true))
        assertEquals(CastSilenceAction.ASK, w.tick(3_000, connected = true))
        // A second command while waiting does not postpone anything.
        assertEquals(CastSilenceAction.NONE, w.nudge(4_000, askNow = false))
        assertEquals(CastSilenceAction.NONE, w.tick(5_900, connected = true))
        assertEquals(CastSilenceAction.REJOIN, w.tick(6_000, connected = true))
    }

    @Test
    fun `a command answered in time never asks`() {
        val w = CastSilenceWatch()
        w.nudge(0, askNow = false)
        w.tick(1_000, connected = true)
        w.heard()
        assertEquals(CastSilenceAction.NONE, w.tick(20_000, connected = true))
    }

    @Test
    fun `a session that ends drops the nudge`() {
        val w = CastSilenceWatch()
        w.nudge(0, askNow = true)
        assertEquals(CastSilenceAction.NONE, w.tick(5_000, connected = false))
        assertFalse(w.waiting)
        assertEquals(CastSilenceAction.ASK, w.nudge(6_000, askNow = true), "a new nudge asks again")
        assertTrue(w.waiting)
    }
}
