package dev.jellystructure.ravilo.ui.focus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Bug fix — the row's own horizontal scroll target so a panel opened near the right edge of the
 *  screen isn't clipped: `panelRight + margin − screenWidth`. */
class FocusDetailHorizontalScrollTest {
    private val margin = 24f
    private val screen = 1920f

    @Test
    fun `a panel that already fits with room to spare yields a non positive delta`() {
        val delta = focusDetailRowOpenHorizontalScrollDelta(panelRightPx = 1200f, screenWidthPx = screen, marginPx = margin)
        assertTrue(delta <= 0f, "expected no scroll, got $delta")
    }

    @Test
    fun `a panel clipped past the right edge is pulled fully into view`() {
        val delta = focusDetailRowOpenHorizontalScrollDelta(panelRightPx = 2100f, screenWidthPx = screen, marginPx = margin)
        assertEquals(2100f + margin - screen, delta)
        assertTrue(delta > 0f)
    }

    @Test
    fun `a panel landing exactly on the margin line yields zero, not a stray scroll`() {
        val delta = focusDetailRowOpenHorizontalScrollDelta(panelRightPx = screen - margin, screenWidthPx = screen, marginPx = margin)
        assertEquals(0f, delta)
    }

    @Test
    fun `a zero sized viewport still returns a finite value, never NaN or infinite`() {
        val delta = focusDetailRowOpenHorizontalScrollDelta(panelRightPx = 0f, screenWidthPx = 0f, marginPx = margin)
        assertEquals(margin, delta)
    }
}
