package dev.jellystructure.ravilo.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
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
