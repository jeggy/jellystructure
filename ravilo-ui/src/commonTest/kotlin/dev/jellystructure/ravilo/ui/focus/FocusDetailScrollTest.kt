package dev.jellystructure.ravilo.ui.focus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** FR-R240-9 — the row's own vertical scroll target, `max(rowTop − peek, rowFoot + footMargin − screenHeight)`. */
class FocusDetailScrollTest {
    private val peek = 150f
    private val footMargin = 40f
    private val screen = 1080f

    @Test
    fun `a row that already fits with room to spare yields a non positive delta`() {
        // top above the peek line, foot well clear of the screen's bottom.
        val delta = focusDetailRowOpenScrollDelta(rowTopPx = 100f, rowFootPx = 500f, screenHeightPx = screen, peekPx = peek, footMarginPx = footMargin)
        assertTrue(delta <= 0f, "expected no scroll, got $delta")
    }

    @Test
    fun `a row pushed low by growth is pulled up to clear its own foot`() {
        // foot clipped past the bottom of the screen — the foot constraint should win.
        val delta = focusDetailRowOpenScrollDelta(rowTopPx = 300f, rowFootPx = 1200f, screenHeightPx = screen, peekPx = peek, footMarginPx = footMargin)
        assertEquals(1200f + footMargin - screen, delta)
        assertTrue(delta > 0f)
    }

    @Test
    fun `a row whose top sits deep past the peek line is pulled up to the peek constraint`() {
        // top far down the screen, foot comfortably on screen — the top constraint should win.
        val delta = focusDetailRowOpenScrollDelta(rowTopPx = 900f, rowFootPx = 1000f, screenHeightPx = screen, peekPx = peek, footMarginPx = footMargin)
        assertEquals(900f - peek, delta)
        assertTrue(delta > 0f)
    }

    @Test
    fun `the larger of the two constraints wins, never the smaller`() {
        val topDrivenDelta = focusDetailRowOpenScrollDelta(rowTopPx = 1000f, rowFootPx = 1050f, screenHeightPx = screen, peekPx = peek, footMarginPx = footMargin)
        // top constraint: 1000 - 150 = 850; foot constraint: 1050 + 40 - 1080 = 10 -> top wins
        assertEquals(850f, topDrivenDelta)

        val footDrivenDelta = focusDetailRowOpenScrollDelta(rowTopPx = 200f, rowFootPx = 1300f, screenHeightPx = screen, peekPx = peek, footMarginPx = footMargin)
        // top constraint: 200 - 150 = 50; foot constraint: 1300 + 40 - 1080 = 260 -> foot wins
        assertEquals(260f, footDrivenDelta)
    }

    @Test
    fun `a zero sized viewport still returns a finite value, never NaN or infinite`() {
        val delta = focusDetailRowOpenScrollDelta(rowTopPx = 0f, rowFootPx = 0f, screenHeightPx = 0f, peekPx = peek, footMarginPx = footMargin)
        // top constraint: 0 - 150 = -150; foot constraint: 0 + 40 - 0 = 40 -> foot wins
        assertEquals(footMargin, delta)
    }

    // R257 (FR-R257-5) — stue TV numbers: bar ends at 120 px, heading seen at ~112 px, grown poster row foot ~835 px.
    @Test fun headingUnderTheAppBarIsBroughtDownToTheClearLine() =
        assertEquals(-40f, focusDetailRowOpenHeadingDelta(rowTopPx = 112f, rowFootPx = 835f, screenHeightPx = 1080f, minTopPx = 152f, footMarginPx = 80f))
    @Test fun headingAlreadyClearMovesNothing() =
        assertEquals(0f, focusDetailRowOpenHeadingDelta(rowTopPx = 254f, rowFootPx = 900f, screenHeightPx = 1080f, minTopPx = 152f, footMarginPx = 80f))
    @Test fun theFootWinsWhenTheGrownRowLeavesNoRoom() =
        assertEquals(-10f, focusDetailRowOpenHeadingDelta(rowTopPx = 100f, rowFootPx = 990f, screenHeightPx = 1080f, minTopPx = 152f, footMarginPx = 80f))
    @Test fun noRoomAtAllMovesNothing() =
        assertEquals(0f, focusDetailRowOpenHeadingDelta(rowTopPx = 100f, rowFootPx = 1040f, screenHeightPx = 1080f, minTopPx = 152f, footMarginPx = 80f))
}
