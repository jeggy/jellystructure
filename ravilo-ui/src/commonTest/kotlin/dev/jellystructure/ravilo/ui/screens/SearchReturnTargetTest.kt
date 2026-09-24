package dev.jellystructure.ravilo.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// R295 (FR-R295-1) — Back from a search result lands on that result; a fresh visit does not.
class SearchReturnTargetTest {
    @Test fun theSameVisitComingBackGetsTheOpenedResultOnce() {
        val t = SearchReturnTarget()
        t.remember(visit = 3, itemId = "abc")
        assertEquals("abc", t.take(visit = 3))
        assertNull(t.take(visit = 3), "read once — a second composition must not steal focus again")
    }

    @Test fun aNewVisitIgnoresAndForgetsTheOldResult() {
        val t = SearchReturnTarget()
        t.remember(visit = 3, itemId = "abc")
        assertNull(t.take(visit = 4))
        assertNull(t.take(visit = 3), "forgotten on the fresh visit, so going Back later cannot resurrect it")
    }

    @Test fun nothingOpenedMeansNothingToReturnTo() {
        assertNull(SearchReturnTarget().take(visit = 1))
    }

    @Test fun theLatestOpenedResultWins() {
        val t = SearchReturnTarget()
        t.remember(visit = 5, itemId = "first")
        t.remember(visit = 5, itemId = "second")
        assertEquals("second", t.take(visit = 5))
    }
}
