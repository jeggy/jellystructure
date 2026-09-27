package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.BrowseCard
import dev.jellystructure.shared.tv.MediaCard
import kotlin.test.Test
import kotlin.test.assertEquals

/** R317 (FR-R317-1/2) — the Size sort: largest first by default, unknown sizes last either way, ties by title. */
class BrowseSizeOrderTest {
    private fun card(id: String, size: Long?) = BrowseCard(MediaCard(id = id, title = id, year = null, genre = null, rating = null, posterUrl = null, backdropUrl = null), sizeBytes = size)
    private val cards = listOf(card("small", 10), card("none", null), card("big", 900), card("tie-b", 50), card("tie-a", 50))

    @Test fun largestFirst() = assertEquals(listOf("big", "tie-a", "tie-b", "small", "none"), browseOrder(cards, SortField.SIZE, SortDir.DESC).map { it.card.id })
    @Test fun smallestFirst() = assertEquals(listOf("small", "tie-a", "tie-b", "big", "none"), browseOrder(cards, SortField.SIZE, SortDir.ASC).map { it.card.id })
    @Test fun aRowOrderedBySizeOpensBySize() {
        assertEquals(SortField.SIZE to SortDir.DESC, initialBrowseSort("size", null))
        assertEquals(SortField.SIZE to SortDir.ASC, initialBrowseSort("size", false))
    }
}
