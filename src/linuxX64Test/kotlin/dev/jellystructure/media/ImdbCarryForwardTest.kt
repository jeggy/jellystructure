package dev.jellystructure.media

import dev.jellystructure.model.ImdbRating
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * DEB-2093 (FR-131-1, FR-131-2 — "transient failures preserve the last good value"): a fresh scan item
 * carries no IMDb rating, and the id is null whenever TMDB's external-ids call failed.
 */
class ImdbCarryForwardTest {

    private val rating = ImdbRating(aggregateRating = 7.4, voteCount = 1200, syncedAt = 1000L)

    private fun item(
        tmdbId: Int? = 670,
        imdbId: String? = null,
        imdbRating: ImdbRating? = null,
        locked: Boolean = false,
    ) = MediaItem(
        id = "tenfold",
        title = "Tenfold",
        year = 2003,
        kind = MediaKind.MOVIE,
        path = "/mnt/media/movies/Tenfold (2003)/Tenfold.mkv",
        tmdbId = tmdbId,
        originalLanguage = "en",
        posterPath = "/poster.jpg",
        overview = "Overview",
        imdbId = imdbId,
        imdbRating = imdbRating,
        tracks = emptyList(),
        issueCount = 0,
        scannedAt = 0,
        tmdbMatchLocked = locked,
    )

    @Test
    fun `a failed external-ids call keeps the stored id and rating on the same tmdb id`() {
        val merged = preserveImdb(item(), item(imdbId = "tt0000670", imdbRating = rating))
        assertEquals("tt0000670", merged.imdbId)
        assertEquals(rating, merged.imdbRating)
    }

    @Test
    fun `an ordinary rescan with the same id keeps the stored rating`() {
        val merged = preserveImdb(item(imdbId = "tt0000670"), item(imdbId = "tt0000670", imdbRating = rating))
        assertEquals("tt0000670", merged.imdbId)
        assertEquals(rating, merged.imdbRating)
    }

    @Test
    fun `a fresh id that differs from the stored one drops the stored rating`() {
        val merged = preserveImdb(item(imdbId = "tt0009999"), item(imdbId = "tt0000670", imdbRating = rating))
        assertEquals("tt0009999", merged.imdbId)
        assertNull(merged.imdbRating)
    }

    @Test
    fun `a fresh rating is never overwritten by the stored one`() {
        val newer = rating.copy(aggregateRating = 8.0, syncedAt = 2000L)
        val merged = preserveImdb(item(imdbId = "tt0000670", imdbRating = newer), item(imdbId = "tt0000670", imdbRating = rating))
        assertEquals(newer, merged.imdbRating)
    }

    @Test
    fun `a re-match to another tmdb id carries neither field`() {
        val merged = preserveImdb(item(tmdbId = 671), item(imdbId = "tt0000670", imdbRating = rating))
        assertNull(merged.imdbId)
        assertNull(merged.imdbRating)
    }

    @Test
    fun `a locked cleared match stays cleared through the store merge order`() {
        val existing = clearTmdbMatch(item(imdbId = "tt0000670", imdbRating = rating))
        val fresh = item(imdbId = "tt0000670")  // the re-search the operator rejected
        val merged = preserveImdb(preserveTmdbMatchLock(fresh, existing), existing)
        assertNull(merged.tmdbId)
        assertNull(merged.imdbId)
        assertNull(merged.imdbRating)
    }

    @Test
    fun `a first scan with nothing stored is untouched`() {
        val fresh = item()
        assertEquals(fresh, preserveImdb(fresh, null))
    }
}
