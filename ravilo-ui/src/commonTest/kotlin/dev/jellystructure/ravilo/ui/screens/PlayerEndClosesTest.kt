package dev.jellystructure.ravilo.ui.screens

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-3950 (R182 amendment 2026-11-08, FR-1..FR-3) — a title with nothing after it closes when it ends; after the end one Back leaves. */
class PlayerEndClosesTest {
    @Test fun `FR-1 the end with nothing to advance to closes the player`() {
        assertTrue(endClosesPlayer(ended = true, canAdvance = false))
    }

    @Test fun `FR-1 the end with a next episode to advance to does not close it`() {
        assertFalse(endClosesPlayer(ended = true, canAdvance = true))
    }

    @Test fun `FR-1 before the end nothing closes`() {
        assertFalse(endClosesPlayer(ended = false, canAdvance = false))
        assertFalse(endClosesPlayer(ended = false, canAdvance = true))
    }

    @Test fun `FR-2 no marker and nothing follows raises no early card of any kind`() {
        for (mode in CreditsCardMode.entries) assertFalse(earlyCardAllowed(trustedMarker = false, mode = mode, canAdvance = false), "$mode")
    }

    @Test fun `FR-2 no marker and a next episode raises the Next Episode card`() {
        assertTrue(earlyCardAllowed(trustedMarker = false, mode = CreditsCardMode.NEXT_EPISODE, canAdvance = true))
    }

    @Test fun `FR-2 no marker never raises Skip credits or Skip to scene`() {
        for (canAdvance in listOf(true, false)) {
            assertFalse(earlyCardAllowed(false, CreditsCardMode.SKIP_CREDITS, canAdvance))
            assertFalse(earlyCardAllowed(false, CreditsCardMode.STINGER, canAdvance))
        }
    }

    @Test fun `FR-2 a trusted marker changes nothing`() {
        for (mode in CreditsCardMode.entries) for (canAdvance in listOf(true, false))
            assertTrue(earlyCardAllowed(trustedMarker = true, mode = mode, canAdvance = canAdvance), "$mode next=$canAdvance")
    }

    @Test fun `FR-3 once ended Back leaves, before the end it does not`() {
        assertTrue(backLeavesAtEnd(ended = true))
        assertFalse(backLeavesAtEnd(ended = false))
    }
}
