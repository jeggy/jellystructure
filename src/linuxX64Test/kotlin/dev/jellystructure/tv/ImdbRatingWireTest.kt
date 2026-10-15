package dev.jellystructure.tv

import dev.jellystructure.model.ImdbRating
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.shared.tv.RaviloWireJson
import dev.jellystructure.shared.tv.TvImdbRating
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R387 (FR-R387-1, FR-R387-8) — the stored IMDb id rides the rating on every payload that carries the
 * rating (detail, browse, focus facts all build it through [tvImdbRating]), or is null when malformed.
 */
class ImdbRatingWireTest {
    private fun item(imdbId: String?, rating: ImdbRating? = ImdbRating(8.7, 24_800L, 0L)) = MediaItem(
        id = "t", title = "t", year = 2026, kind = MediaKind.MOVIE, path = "/m/t.mkv", tmdbId = null,
        originalLanguage = "en", posterPath = null, overview = null, tracks = emptyList(),
        issueCount = 0, scannedAt = 0L, imdbId = imdbId, imdbRating = rating,
    )

    @Test
    fun `a stored rating and id put imdb_id on the payload`() {
        val wire = item("tt0903747").tvImdbRating()
        assertEquals("tt0903747", wire?.imdbId)
        val json = RaviloWireJson.encodeToString(TvImdbRating.serializer(), wire!!)
        assertTrue("\"imdb_id\":\"tt0903747\"" in json, json)
    }

    @Test
    fun `a malformed stored id puts null and never a link`() {
        for (bad in listOf("", " ", "tt", "nm0000093", "tt1/x", "tt1?x", "tt1#x", "tt1 2", "https://x/tt1")) {
            assertNull(item(bad).tvImdbRating()?.imdbId, "'$bad'")
        }
    }

    @Test
    fun `no stored id puts null and the rating is still there`() {
        val wire = item(null).tvImdbRating()
        assertNull(wire?.imdbId)
        assertEquals(8.7, wire?.aggregateRating)
    }

    @Test
    fun `no stored rating means no chip at all`() {
        assertNull(item("tt0903747", rating = null).tvImdbRating())
    }
}
