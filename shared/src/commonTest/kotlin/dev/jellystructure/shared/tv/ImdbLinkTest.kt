package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** R387 (FR-R387-2, FR-R387-8) — an IMDb id becomes the title page's URL, or nothing. */
class ImdbLinkTest {
    @Test
    fun aWellFormedIdIsTheCanonicalTitlePage() {
        assertEquals("https://www.imdb.com/title/tt0111161/", imdbTitleUrl("tt0111161"))
        assertEquals("https://www.imdb.com/title/tt1/", imdbTitleUrl("tt1"))
    }

    @Test
    fun anythingElseIsNull() {
        for (bad in listOf(
            null, "", "   ", "tt", "nm0000093", "0111161", "TT0111161", "tt01x1161", "tt-1",
            "tt0111161/", "tt0111161/reviews", "tt0111161?ref=x", "tt0111161#cast",
            "tt0111161 ", " tt0111161", "tt0111\t161", "tt0111161\n", "https://evil.example/tt0111161",
        )) {
            assertNull(imdbTitleUrl(bad), "expected null for ${bad?.let { "'$it'" }}")
            assertNull(validImdbTitleId(bad))
        }
    }

    @Test
    fun theValidIdIsReturnedUnchanged() {
        assertEquals("tt0903747", validImdbTitleId("tt0903747"))
    }

    @Test
    fun aRatingPayloadWithoutAnIdStillDecodes() {
        val old = """{"aggregate_rating":8.7,"vote_count":24800}"""
        val decoded = RaviloWireJson.decodeFromString(TvImdbRating.serializer(), old)
        assertEquals(8.7, decoded.aggregateRating)
        assertEquals(24800L, decoded.voteCount)
        assertNull(decoded.imdbId)
        assertNull(imdbTitleUrl(decoded.imdbId))
    }

    @Test
    fun aRatingPayloadWithTheIdCarriesIt() {
        val decoded = RaviloWireJson.decodeFromString(
            TvImdbRating.serializer(), """{"aggregate_rating":8.7,"vote_count":24800,"imdb_id":"tt0903747"}""",
        )
        assertEquals("tt0903747", decoded.imdbId)
        assertNotNull(imdbTitleUrl(decoded.imdbId))
    }
}
