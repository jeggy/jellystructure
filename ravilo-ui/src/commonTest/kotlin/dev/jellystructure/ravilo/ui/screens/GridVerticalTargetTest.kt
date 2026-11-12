package dev.jellystructure.ravilo.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

/** R362 (FR-R362-5) — Down and Up in a 6-column browse grid keep the column. */
class GridVerticalTargetTest {
    @Test fun downAndUpMoveByOneRow() {
        assertEquals(9, gridVerticalTarget(3, 6, 18, down = true))
        assertEquals(3, gridVerticalTarget(9, 6, 18, down = false))
    }

    @Test fun theFirstRowHasNoUpAndTheLastRowNoDown() {
        assertNull(gridVerticalTarget(3, 6, 18, down = false))
        assertNull(gridVerticalTarget(15, 6, 18, down = true))
        assertNull(gridVerticalTarget(2, 6, 6, down = true), "one row: nothing below")
    }

    @Test fun aShortLastRowTakesItsLastTile() {
        assertEquals(15, gridVerticalTarget(9, 6, 16, down = true))
        assertEquals(15, gridVerticalTarget(11, 6, 16, down = true))
        assertEquals(8, gridVerticalTarget(14, 6, 16, down = false))
    }
}

/** DEB-4277 (FR-R362-5, amended) — Right at a 6-column grid's right edge is consumed. */
class GridRightEdgeTest {
    @Test fun theLastColumnIsTheEdge() {
        assertTrue(gridRightEdge(5, 6, 18))
        assertTrue(gridRightEdge(11, 6, 18))
        assertFalse(gridRightEdge(4, 6, 18))
        assertFalse(gridRightEdge(6, 6, 18))
    }

    @Test fun theLastTileOfAShortLastRowIsTheEdge() {
        assertTrue(gridRightEdge(15, 6, 16))
        assertFalse(gridRightEdge(14, 6, 16))
    }

    @Test fun noTileMeansNoEdge() {
        assertFalse(gridRightEdge(-1, 6, 18))
        assertFalse(gridRightEdge(18, 6, 18))
        assertFalse(gridRightEdge(0, 0, 18))
    }
}

/** R362 (FR-R362-1/2) — the facet bar's entry chip. */
class FacetEntryTargetTest {
    @Test fun theLastChipWhileItIsFullyOnScreen() {
        assertEquals("YEAR", facetEntryTarget("YEAR", setOf("GENRE", "MATURITY", "YEAR")))
    }

    @Test fun otherwiseScrollToTheStartAndGenre() {
        assertNull(facetEntryTarget("sort", setOf("GENRE", "MATURITY")), "scrolled off or only partly visible")
        assertNull(facetEntryTarget(null, setOf("GENRE")), "never set")
    }
}

/** R362 (FR-R362-6) — a popover opens under its chip, clamped inside the screen's side padding. */
class PopoverOffsetTest {
    @Test fun underTheChip() = assertEquals(400f, popoverOffsetX(400f, 260f, 1920f, 48f))
    @Test fun clampedAtTheRightEdge() = assertEquals(1920f - 48f - 260f, popoverOffsetX(1800f, 260f, 1920f, 48f))
    @Test fun clampedAtThePaddingOnTheLeft() = assertEquals(48f, popoverOffsetX(10f, 260f, 1920f, 48f))
    @Test fun aPopoverWiderThanTheScreenStartsAtThePadding() = assertEquals(48f, popoverOffsetX(300f, 2000f, 1920f, 48f))
}
