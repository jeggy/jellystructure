package dev.jellystructure.ui

import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-5345 (FR-1, FR-3, FR-4, FR-5) — the Library grid's card, asserted on its markup. */
class PosterCardHtmlTest {

    private fun item(
        id: String = "abc1",
        jellyfinId: String? = null,
        title: String = "Sample Film",
        year: Int? = 1999,
        issues: Int = 0,
        mix: Boolean = false,
    ) = MediaItem(
        id = id, title = title, year = year, kind = MediaKind.MOVIE, path = "/x", jellyfinId = jellyfinId,
        tmdbId = null, originalLanguage = null, posterPath = null, overview = null,
        issueCount = issues, languageMix = mix, scannedAt = 0L, tracks = emptyList(),
    )

    @Test
    fun `the card is a link to the item with its id`() {
        val h = posterCardHtml(item()).trim()
        assertTrue(h.startsWith("""<a class="poster" href="#/media/abc1""""), h)
        assertTrue("""data-id="abc1"""" in h, h)
    }

    @Test
    fun `the Jellyfin id is used when set and the id when it is not`() {
        assertTrue("""href="#/media/jf9"""" in posterCardHtml(item(jellyfinId = "jf9")))
        assertTrue("""href="#/media/abc1"""" in posterCardHtml(item(jellyfinId = null)))
    }

    @Test
    fun `a quote in the id or the title is escaped`() {
        val h = posterCardHtml(item(id = "a\"b", title = "Say \"hi\""))
        assertTrue("""href="#/media/a&quot;b"""" in h, h)
        assertTrue("""data-id="a&quot;b"""" in h, h)
        assertTrue("Say &quot;hi&quot;" in h, h)
    }

    @Test
    fun `the card holds no second link or button or inline cursor`() {
        val h = posterCardHtml(item(issues = 2))
        assertEquals(1, Regex("<a[ >]").findAll(h).count(), h)
        assertFalse("<button" in h, h)
        assertFalse("cursor:" in h, h)
    }

    @Test
    fun `the title and the year and the badge are present`() {
        val ok = posterCardHtml(item())
        assertTrue(">Sample Film<" in ok && "1999" in ok && ">ok<" in ok, ok)
        assertTrue("—" in posterCardHtml(item(year = null)))
        assertTrue(">1 issue<" in posterCardHtml(item(issues = 1)))
        assertTrue(">3 issues<" in posterCardHtml(item(issues = 3)))
        assertTrue(">lang mix<" in posterCardHtml(item(mix = true)))
    }
}
