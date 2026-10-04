package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.UpcomingFeed
import dev.jellystructure.shared.tv.UpcomingItem
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * R366 (owner decision, 2026-10-04) — the pure step that turns the upcoming store's last answer into
 * `defaultDiscoverSegment`'s `upcomingEmpty`.
 */
class UpcomingEmptyTest {
    private val item = UpcomingItem(id = "u1", title = "Stand-in Lighthouse", date = "2026-10-10")
    private val overdue = UpcomingItem(id = "m1", title = "Stand-in Ferry", date = "2026-09-01")

    private fun loaded(items: List<UpcomingItem>, missing: List<UpcomingItem> = emptyList()) =
        UpcomingState.Loaded(UpcomingFeed(enabled = true, items = items, missing = missing))

    @Test
    fun aCalendarWithAnItemIsNotEmpty() {
        for (prev in listOf<Boolean?>(null, false, true)) assertEquals(false, upcomingEmptyAfter(prev, loaded(listOf(item))))
    }

    @Test
    fun aCalendarWithNoItemsIsEmpty() {
        for (prev in listOf<Boolean?>(null, false, true)) assertEquals(true, upcomingEmptyAfter(prev, loaded(emptyList())))
    }

    @Test
    fun overdueEpisodesAloneCountAsEmpty() {
        // The page still reads "Nothing scheduled" under All; `missing` alone does not change that sentence.
        assertEquals(true, upcomingEmptyAfter(false, loaded(emptyList(), missing = listOf(overdue))))
    }

    @Test
    fun loadingAndErrorKeepTheLastAnswer() {
        for (prev in listOf<Boolean?>(null, false, true)) {
            assertEquals(prev, upcomingEmptyAfter(prev, UpcomingState.Loading))
            assertEquals(prev, upcomingEmptyAfter(prev, UpcomingState.Error("offline")))
        }
    }
}
