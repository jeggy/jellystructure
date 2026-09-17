package dev.jellystructure.ravilo.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

/** R253 (FR-R253-2) — See all opens in the row's own order. */
class InitialBrowseSortTest {
    @Test fun addedBothWays() { assertEquals(SortField.RECENT to SortDir.DESC, initialBrowseSort("added", true)); assertEquals(SortField.RECENT to SortDir.ASC, initialBrowseSort("added", false)) }
    @Test fun titleBothWays() { assertEquals(SortField.TITLE to SortDir.ASC, initialBrowseSort("title", false)); assertEquals(SortField.TITLE to SortDir.DESC, initialBrowseSort("title", true)) }
    @Test fun yearBothWays() { assertEquals(SortField.YEAR to SortDir.DESC, initialBrowseSort("year", true)); assertEquals(SortField.YEAR to SortDir.ASC, initialBrowseSort("year", false)) }
    @Test fun absentOrUnknownIsTheOldDefault() { assertEquals(SortField.RECENT to SortDir.DESC, initialBrowseSort(null, null)); assertEquals(SortField.RECENT to SortDir.DESC, initialBrowseSort("rating", false)) }
}
