package dev.jellystructure.ravilo.ui.focus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** R361 (FR-R361-1/5) — the decisions behind "Back to a title that is gone lands on its neighbour". */
class FocusReturnTest {

    @Test fun fallbackIndexKeepsThePositionElseTheLastElseNothing() {
        assertEquals(3, fallbackIndex(3, 10))
        assertEquals(8, fallbackIndex(9, 9), "the end of a shorter list: the new last")
        assertEquals(1, fallbackIndex(5, 2))
        assertNull(fallbackIndex(0, 0))
        assertEquals(0, fallbackIndex(-1, 4), "a negative index clamps to the first")
    }

    private val rows = listOf(
        "cw" to listOf("a", "b", "c"),
        "rec" to listOf("d", "e", "f", "g"),
        "new" to listOf("h", "i"),
    )

    @Test fun aSurvivingTitleIsFoundWhereverItMoved() {
        assertEquals(ReturnTarget("rec", "f"), resolveReturn(rows, "rec", "f", 1, 2))
        assertEquals(ReturnTarget("rec", "f"), resolveReturn(rows, "rec", "f", 1, 0), "a new index in the same row")
    }

    @Test fun aGoneTitleLandsOnTheTileNowAtItsIndex() {
        // "e" was at index 1 of rec; the row is now d, f, g → the tile at index 1 is f.
        val after = listOf("cw" to listOf("a"), "rec" to listOf("d", "f", "g"), "new" to listOf("h"))
        assertEquals(ReturnTarget("rec", "f"), resolveReturn(after, "rec", "e", 1, 1))
    }

    @Test fun aGoneTitleFromTheEndLandsOnTheNewLast() {
        val after = listOf("rec" to listOf("d", "e", "f"))
        assertEquals(ReturnTarget("rec", "f"), resolveReturn(after, "rec", "g", 0, 3))
    }

    @Test fun aGoneRowLandsOnTheRowNowInItsPlaceWithTheColumnClamped() {
        // rec (index 1) is gone: new slid up into index 1. Column 3 clamps to new's last (i).
        val after = listOf("cw" to listOf("a", "b", "c"), "new" to listOf("h", "i"))
        assertEquals(ReturnTarget("new", "i"), resolveReturn(after, "rec", "g", 1, 3))
        assertEquals(ReturnTarget("new", "h"), resolveReturn(after, "rec", "d", 1, 0))
    }

    @Test fun aGoneLastRowLandsOnTheRowAbove() {
        val after = listOf("cw" to listOf("a", "b", "c"), "rec" to listOf("d", "e"))
        assertEquals(ReturnTarget("rec", "e"), resolveReturn(after, "new", "i", 2, 1))
    }

    @Test fun anEmptyRowCountsAsGone() {
        val after = listOf("cw" to listOf("a"), "rec" to emptyList(), "new" to listOf("h", "i"))
        assertEquals(ReturnTarget("new", "i"), resolveReturn(after, "rec", "e", 1, 1))
        val emptyLast = listOf("cw" to listOf("a", "b"), "rec" to emptyList())
        assertEquals(ReturnTarget("cw", "b"), resolveReturn(emptyLast, "rec", "e", 1, 1))
    }

    @Test fun everyRowEmptyResolvesToNothing() {
        assertNull(resolveReturn(listOf("cw" to emptyList(), "rec" to emptyList()), "rec", "e", 1, 1))
        assertNull(resolveReturn(emptyList(), "rec", "e", 1, 1))
    }
}
