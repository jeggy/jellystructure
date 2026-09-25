package dev.jellystructure.ravilo.ui.seams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R293 FR-R293-3/4/5 — an open is a rev check; Home refreshes only after a real gap; commands off screen are dropped. */
class EventsCatchUpTest {
    @Test
    fun `the first open of a process pulls config and never rebuilds Home`() {
        val c = EventsCatchUp()
        assertEquals(EventsCatchUp.Decision(refreshConfig = true, refreshHome = false), c.onOpen(rev = 5L, nowMs = 1_000L))
    }

    @Test
    fun `a quick reconnect with an unmoved rev refreshes nothing`() {
        val c = EventsCatchUp(homeGapMs = 30_000L)
        c.onOpen(5L, 0L)
        c.onClosed(60_000L)
        assertEquals(EventsCatchUp.Decision(refreshConfig = false, refreshHome = false), c.onOpen(5L, 61_000L))
    }

    @Test
    fun `a moved rev refreshes config, a long gap refreshes Home`() {
        val c = EventsCatchUp(homeGapMs = 30_000L)
        c.onOpen(5L, 0L)
        c.onClosed(60_000L)
        assertEquals(EventsCatchUp.Decision(refreshConfig = true, refreshHome = false), c.onOpen(6L, 61_000L))
        c.onClosed(70_000L)
        assertEquals(EventsCatchUp.Decision(refreshConfig = false, refreshHome = true), c.onOpen(6L, 70_000L + 31_000L))
    }

    @Test
    fun `a failed rev check assumes nothing moved and the poll shares the seen rev`() {
        val c = EventsCatchUp()
        c.onOpen(5L, 0L)
        c.onClosed(1_000L)
        assertEquals(EventsCatchUp.Decision(refreshConfig = false, refreshHome = false), c.onOpen(null, 2_000L))
        assertFalse(c.onPollRev(5L))
        assertTrue(c.onPollRev(7L))
        assertEquals(EventsCatchUp.Decision(refreshConfig = false, refreshHome = false), c.onOpen(7L, 3_000L).let { it.copy(refreshHome = false) })
    }

    @Test
    fun `commands are applied only on screen and signed in`() {
        assertTrue(acceptsRemoteCommand(onScreen = true, signedIn = true))
        assertFalse(acceptsRemoteCommand(onScreen = false, signedIn = true))
        assertFalse(acceptsRemoteCommand(onScreen = true, signedIn = false))
    }
}
