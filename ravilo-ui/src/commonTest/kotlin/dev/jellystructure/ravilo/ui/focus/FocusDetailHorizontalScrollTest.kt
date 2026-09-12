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

/**
 * The measurement-free target — the one actually used, since the panel is routinely not laid out at
 * the moment its room has to be found (see [focusDetailRowOpenTargetScrollDelta]'s own doc).
 */
class FocusDetailRowOpenTargetScrollTest {
    private val scale = 300f / 210f     // FR-R240-7's ROW_OPEN_WIDTH_SCALE
    private val spacing = 20f
    private val panel = 620f
    private val viewportEnd = 1920f

    private fun delta(tileOffset: Float, tileWidth: Float = 210f, viewport: Float = viewportEnd) =
        focusDetailRowOpenTargetScrollDelta(
            openTileOffsetPx = tileOffset,
            openTileWidthPx = tileWidth,
            widthScale = scale,
            itemSpacingPx = spacing,
            panelWidthPx = panel,
            viewportEndPx = viewport,
        )

    @Test
    fun `a tile at the row start with room for its panel does not scroll at all`() {
        // 0 + 300 + 20 + 620 = 940, well inside 1920.
        assertEquals(0f, delta(tileOffset = 0f))
    }

    @Test
    fun `a tile far enough right that its panel would overflow scrolls exactly the shortfall`() {
        // 700 + 300 + 20 + 620 = 1640 -> fits; push further out so it doesn't.
        val tileOffset = 1100f
        // 1100 + 300 + 20 + 620 = 2040 -> overflow 120, and 120 <= tileOffset so no clamping.
        assertEquals(120f, delta(tileOffset))
    }

    @Test
    fun `the opening tile is NEVER scrolled past the row's own content start`() {
        // The case the clamp exists for: a viewport too narrow to EVER fit tile + panel together
        // (here 800px against a 300 + 20 + 620 = 940px requirement). The honest shortfall is 180px,
        // but the tile sits only 40px in — scrolling the full 180 would drag it 140px off the left
        // edge and visibly clip its poster, the exact bug reported live. The clamp must win, even
        // though that leaves the panel's tail off-screen: the tile is what the viewer is pointing at.
        val tileOffset = 40f
        val d = delta(tileOffset, viewport = 800f)
        assertEquals(40f, d, "expected the clamp to cap the scroll at the tile's own offset")
        assertTrue(d <= tileOffset, "scrolling $d would clip a tile sitting at $tileOffset")
    }

    @Test
    fun `an unclamped shortfall would have clipped, proving the clamp is load-bearing`() {
        // Same geometry as above, stated as the raw arithmetic the clamp overrides: 40 + 300 + 20 +
        // 620 - 800 = 180 needed, vs 40 available. Without the clamp this would scroll 180.
        val rawNeeded = 40f + 210f * scale + spacing + panel - 800f
        assertTrue(rawNeeded > 40f, "test geometry is wrong — nothing to clamp (needed=$rawNeeded)")
        assertEquals(40f, delta(tileOffset = 40f, viewport = 800f))
    }

    @Test
    fun `a wider LANDSCAPE tile needs more room than a POSTER one, and is clamped the same way`() {
        // Continue Watching's landscape tiles are much wider, so their panel overflows sooner — this
        // is why the clipping reproduced on some rows and not others.
        val poster = delta(tileOffset = 600f, tileWidth = 210f)      // 600+300+20+620 = 1540, fits
        val landscape = delta(tileOffset = 600f, tileWidth = 380f)   // 600+543+20+620 = 1783, fits
        assertEquals(0f, poster)
        assertEquals(0f, landscape)
        // Push both right until the wider one overflows first.
        assertTrue(delta(tileOffset = 900f, tileWidth = 380f) > delta(tileOffset = 900f, tileWidth = 210f))
    }

    @Test
    fun `a negative tile offset (already clipped at the start) never scrolls further into the clip`() {
        val d = delta(tileOffset = -80f)
        assertEquals(0f, d, "a tile already clipped at the start must not be pushed further left")
    }
}
