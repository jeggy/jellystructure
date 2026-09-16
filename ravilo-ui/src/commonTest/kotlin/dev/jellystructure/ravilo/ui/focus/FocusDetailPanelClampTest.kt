package dev.jellystructure.ravilo.ui.focus

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * R250 (FR-R250-4/5) — the panel ends at the end gutter whatever the tile did. Numbers are the stue
 * TV's own: 1920 px wide, 96 px gutters, a LANDSCAPE tile grown to 660 px, 32 px item gap.
 * The Compose UI test the spec asks for (960 × 540 dp, bounds assertion) is not built — ravilo-ui has
 * no UI-test source set; this covers the arithmetic the composable feeds.
 */
class FocusDetailPanelClampTest {
    private val endGutter = 1920f - 96f - 96f   // viewportEndOffset − afterContentPadding, in content space
    private val grown = 660f
    private val gap = 32f
    private val declared = endGutter - grown - gap   // what focusDetailPanelWidthFor declares for a tile AT the content start

    @Test
    fun `a tile that landed at the content start leaves exactly the declared width`() {
        val available = focusDetailPanelAvailableWidthPx(landedTileOffsetPx = 0f, grownTileWidthPx = grown, itemSpacingPx = gap, endGutterPx = endGutter)
        assertEquals(declared, available)
        assertEquals(declared, focusDetailPanelClampedWidthPx(declared, available, minPx = 280f))
    }

    @Test
    fun `a tile that landed 66 px short narrows the panel by 66 px so it still ends at the gutter`() {
        val available = focusDetailPanelAvailableWidthPx(landedTileOffsetPx = 66f, grownTileWidthPx = grown, itemSpacingPx = gap, endGutterPx = endGutter)
        assertEquals(declared - 66f, available)
        assertEquals(declared - 66f, focusDetailPanelClampedWidthPx(declared, available, minPx = 280f))
    }

    @Test
    fun `a sub-pixel rounding difference does not reflow the panel`() {
        assertEquals(declared, focusDetailPanelClampedWidthPx(declared, declared - 1.5f, minPx = 280f))
    }

    @Test
    fun `the clamp never widens and never drops below the floor`() {
        assertEquals(declared, focusDetailPanelClampedWidthPx(declared, declared + 400f, minPx = 280f))
        assertEquals(280f, focusDetailPanelClampedWidthPx(declared, 40f, minPx = 280f))
    }

    @Test
    fun `targeting the end gutter lands the tile at the content start (FR-R250-5)`() {
        // The old call site passed viewportEndOffset (= the screen edge, 1824 in content space) as the
        // viewport end: a tile at offset 900 then scrolled 96 px too little and landed at 96, not 0.
        val tileOffset = 900f
        val base = grown / (300f / 210f)
        val oldDelta = focusDetailRowOpenTargetScrollDelta(tileOffset, base, 300f / 210f, gap, declared, viewportEndPx = 1920f - 96f)
        val newDelta = focusDetailRowOpenTargetScrollDelta(tileOffset, base, 300f / 210f, gap, declared, viewportEndPx = endGutter)
        assertEquals(tileOffset - 96f, oldDelta)
        assertEquals(tileOffset, newDelta)
    }
}

/** R250 (FR-R250-6) — a focused pill is never closer than the gutter to either screen edge. */
class GutterBringIntoViewTest {
    private val container = 1920f
    private val gutter = 96f

    @Test
    fun `a pill fully inside the safe area does not move`() {
        assertEquals(0f, gutterBringIntoViewDistance(offset = 500f, size = 180f, containerSize = container, gutterPx = gutter))
        assertEquals(0f, gutterBringIntoViewDistance(offset = gutter, size = 180f, containerSize = container, gutterPx = gutter))
    }

    @Test
    fun `a pill 25 px from the right edge is pulled back to the gutter`() {
        val d = gutterBringIntoViewDistance(offset = container - 25f - 180f, size = 180f, containerSize = container, gutterPx = gutter)
        assertEquals(gutter - 25f, d)
    }

    @Test
    fun `a pill inside the left gutter or clipped left is revealed at the gutter`() {
        assertEquals(-56f, gutterBringIntoViewDistance(offset = 40f, size = 180f, containerSize = container, gutterPx = gutter))
        assertEquals(-296f, gutterBringIntoViewDistance(offset = -200f, size = 180f, containerSize = container, gutterPx = gutter))
    }
}
