package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals

/** R310 (FR-R310-7) — the summary is the three wall lists' sizes and nothing else, from the same facets. */
class FacetsSummaryTest {
    private fun items(n: Int) = List(n) { FacetItem("v$it", it + 1) }

    @Test
    fun theSummaryIsTheSizeOfEachWallList() {
        val facets = BrowseFacets(genres = items(41), studios = items(573), networks = items(66), tags = items(9))
        assertEquals(FacetsSummary(networks = 66, studios = 573, genres = 41), facets.wallSummary())
    }

    @Test
    fun anEmptyWallCountsZero() {
        assertEquals(FacetsSummary(networks = 0, studios = 3, genres = 2), BrowseFacets(genres = items(2), studios = items(3)).wallSummary())
    }
}
