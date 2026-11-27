package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.Dest
import dev.jellystructure.ravilo.ui.playPushReplacesTop
import dev.jellystructure.ravilo.ui.seams.CastLinkState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-4767 (R370 amendment 2026-11-16, FR-R370-9, FR-R370-10) — a push over the player replaces it; each item starts with fresh latches. */
class PlayerItemLatchesTest {
    @Test fun `FR-R370-10 beginItem clears all three latches, one set for the very item starting again`() {
        val bk = PlayerBookkeeping(CastLinkState.NONE)
        bk.advanceRequestedForItemId = "item-a"
        bk.endCloseRequestedForItemId = "item-a"
        bk.prefetchedForItemId = "item-a"
        bk.beginItem()
        assertNull(bk.advanceRequestedForItemId)
        assertNull(bk.endCloseRequestedForItemId)
        assertNull(bk.prefetchedForItemId)
    }

    @Test fun `FR-R370-9 a push over the player replaces it`() {
        assertTrue(playPushReplacesTop(Dest.Player(itemId = "item-a", title = "A", displayName = "Home")))
    }

    @Test fun `FR-R370-9 a push over any other page, or nothing, is stacked`() {
        assertFalse(playPushReplacesTop(Dest.Home("Home")))
        assertFalse(playPushReplacesTop(Dest.SeriesDetail("series-a", "Home")))
        assertFalse(playPushReplacesTop(null))
    }
}
