package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.BrowseCard
import dev.jellystructure.shared.tv.MediaCard
import kotlin.test.Test
import kotlin.test.assertEquals

/** R318 (FR-R318-2b) — the Recommended page opens in the list's own (rank) order, and flips like any sort. */
class BrowseSourceOrderTest {
    private fun card(id: String, year: Int) = BrowseCard(MediaCard(id = id, title = id, year = year, genre = null, rating = null, posterUrl = null, backdropUrl = null))
    private val ranked = listOf(card("first", 1999), card("second", 2024), card("third", 2010))

    @Test fun sourceDescendingIsTheServersOrder() =
        assertEquals(listOf("first", "second", "third"), browseOrder(ranked, SortField.SOURCE, SortDir.DESC).map { it.card.id })

    @Test fun sourceAscendingIsItsReverse() =
        assertEquals(listOf("third", "second", "first"), browseOrder(ranked, SortField.SOURCE, SortDir.ASC).map { it.card.id })

    @Test fun theOtherSortsStillWorkOnTheList() =
        assertEquals(listOf("second", "third", "first"), browseOrder(ranked, SortField.YEAR, SortDir.DESC).map { it.card.id })
}
