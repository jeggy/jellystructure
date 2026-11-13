package dev.jellystructure.ravilo.ui.seams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DEB-4290 — which rows below the last visible one get their first tiles warmed. */
class RowPrefetchTest {
    @Test
    fun `the three rows below the last visible one are warmed`() {
        assertEquals(3..5, rowsToPrefetch(lastVisibleRow = 2, rowCount = 12, warmedThrough = -1))
    }

    @Test
    fun `an advancing scroll warms only the newly exposed row`() {
        assertEquals(6..6, rowsToPrefetch(lastVisibleRow = 3, rowCount = 12, warmedThrough = 5))
    }

    @Test
    fun `scrolling back up or staying put warms nothing`() {
        assertTrue(rowsToPrefetch(lastVisibleRow = 1, rowCount = 12, warmedThrough = 5).isEmpty())
        assertTrue(rowsToPrefetch(lastVisibleRow = 2, rowCount = 12, warmedThrough = 5).isEmpty())
    }

    @Test
    fun `a jump further than the lookahead skips the rows it passed`() {
        assertEquals(9..11, rowsToPrefetch(lastVisibleRow = 8, rowCount = 12, warmedThrough = 5))
    }

    @Test
    fun `the window stops at the last row and nothing visible warms nothing`() {
        assertEquals(10..11, rowsToPrefetch(lastVisibleRow = 9, rowCount = 12, warmedThrough = 8))
        assertTrue(rowsToPrefetch(lastVisibleRow = 11, rowCount = 12, warmedThrough = 11).isEmpty())
        assertTrue(rowsToPrefetch(lastVisibleRow = -1, rowCount = 12, warmedThrough = -1).isEmpty())
    }
}
