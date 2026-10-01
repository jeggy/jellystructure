package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.components.cardEpisodeCode
import dev.jellystructure.ravilo.ui.focus.arrowFocusDirection
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** R350 (FR-R350-10/11) — an episode card's code is R346's one spelling; the arrows map to the D-pad's directions. */
class EpisodeCardCodeTest {
    @Test fun `a card in its season reads S01E05, not E5`() {
        assertEquals("S01E01", cardEpisodeCode(1, 1))
        assertEquals("S13E120", cardEpisodeCode(13, 120))
        assertEquals("E4", cardEpisodeCode(null, 4))   // no season known: the bare number, as before
    }

    @Test fun `the four arrows and nothing else move focus on a computer`() {
        assertEquals(FocusDirection.Up, arrowFocusDirection(Key.DirectionUp))
        assertEquals(FocusDirection.Down, arrowFocusDirection(Key.DirectionDown))
        assertEquals(FocusDirection.Left, arrowFocusDirection(Key.DirectionLeft))
        assertEquals(FocusDirection.Right, arrowFocusDirection(Key.DirectionRight))
        assertNull(arrowFocusDirection(Key.Tab))
        assertNull(arrowFocusDirection(Key.Enter))
    }
}
