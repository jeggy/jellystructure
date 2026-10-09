package dev.jellystructure.tv

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Found live 2026-10-09 (Mac, R329) — a search is blind to the punctuation in a title. */
class SearchQueryTest {
    private val title = "Kite-Man: Paper New Dawn"

    @Test fun `the first words of a title with a colon find it`() {
        assertTrue(SearchQuery("Kite-Man Paper").matches(title))
        assertTrue(SearchQuery("kite man paper").matches(title))
        assertTrue(SearchQuery("Kite-Man: Pap").matches(title))
    }

    @Test fun `the words with the hyphen dropped or in another order find it`() {
        assertTrue(SearchQuery("kiteman").matches(title))
        assertTrue(SearchQuery("dawn kite").matches(title))
    }

    @Test fun `a plain substring still matches as before`() {
        assertTrue(SearchQuery("New Da").matches(title))
        assertTrue(SearchQuery("e-Man").matches(title))
    }

    @Test fun `words the title does not hold never match`() {
        assertFalse(SearchQuery("Kite-Man Glass").matches(title))
        assertFalse(SearchQuery("paper moon").matches(title))
        assertFalse(SearchQuery("--").matches(title))
    }
}
