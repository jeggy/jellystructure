package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.BrowseCard
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.TvImdbRating
import kotlin.test.Test
import kotlin.test.assertEquals

/** DEB-2837 (FR-7) — *See all* from a best-rated row opens best-rated first; a title only TMDB rates sorts by its
 *  `rating_score`; unrated titles go last either way; an older server (no `rating_score`) sorts by IMDb alone. */
class BrowseRatingOrderTest {
    private fun card(id: String, score: Double? = null, imdb: Double? = null) = BrowseCard(
        MediaCard(id = id, title = id, year = null, genre = null, rating = null, posterUrl = null, backdropUrl = null),
        ratingScore = score, imdbRating = imdb?.let { TvImdbRating(aggregateRating = it, voteCount = 1000) },
    )
    private val cards = listOf(card("imdb-high", score = 8.5, imdb = 8.5), card("none-b"), card("tmdb-only", score = 7.8), card("imdb-low", score = 6.0, imdb = 6.0), card("none-a"))
    private fun ids(dir: SortDir) = browseOrder(cards, SortField.IMDB, dir).map { it.card.id }

    @Test fun ratingMapsToTheImdbSortWithTheRowsDirection() {
        assertEquals(SortField.IMDB to SortDir.DESC, initialBrowseSort("rating", true))
        assertEquals(SortField.IMDB to SortDir.ASC, initialBrowseSort("rating", false))
        assertEquals(SortField.IMDB to SortDir.DESC, initialBrowseSort("rating", null))
    }
    @Test fun aTmdbOnlyTitleSortsBetweenTwoImdbRatedOnes() = assertEquals(listOf("imdb-high", "tmdb-only", "imdb-low", "none-a", "none-b"), ids(SortDir.DESC))
    @Test fun lowestFirstStillPutsUnratedLast() = assertEquals(listOf("imdb-low", "tmdb-only", "imdb-high", "none-a", "none-b"), ids(SortDir.ASC))
    @Test fun anOlderServerSortsByImdbAlone() {
        val old = listOf(card("a", imdb = 6.0), card("b", imdb = 9.0), card("c"))
        assertEquals(listOf("b", "a", "c"), browseOrder(old, SortField.IMDB, SortDir.DESC).map { it.card.id })
    }
}
