package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-1264 slice 3 (FR-7, FR-8, FR-9) — the picker's ranking, mark, toggle and chip counts. */
class HideTagsPickerTest {
    private val cat = listOf(
        TagCount("cartoons", 14), TagCount("kids-tv", 27), TagCount("kidnapping", 8), TagCount("kids", 6),
        TagCount("børnefilm", 1), TagCount("animated-family-christmas-specials-from-the-archive", 2), TagCount("old-kids-block", 0),
        TagCount("noir", 5),
    )

    @Test fun `nothing typed lists the six most used tags`() {
        assertEquals(listOf("kids-tv", "cartoons", "kidnapping", "kids", "noir", "animated-family-christmas-specials-from-the-archive"),
            HideTagsPicker.results(cat, "").map { it.name })
        assertEquals(6, HideTagsPicker.results(cat, "  ").size)
    }

    @Test fun `a query matches substrings with prefixes first and then by count`() {
        // "kid": kids-tv 27, kidnapping 8, kids 6 start with it; old-kids-block merely contains it.
        assertEquals(listOf("kids-tv", "kidnapping", "kids", "old-kids-block"), HideTagsPicker.results(cat, "kid").map { it.name })
    }

    @Test fun `a query is case-insensitive and a count tie falls back to the name`() {
        assertEquals(listOf("kids-tv", "kidnapping", "kids", "old-kids-block"), HideTagsPicker.results(cat, "KID").map { it.name })
        val tied = listOf(TagCount("b-x", 3), TagCount("a-x", 3))
        assertEquals(listOf("a-x", "b-x"), HideTagsPicker.results(tied, "x").map { it.name })
    }

    @Test fun `a query with no match gives no rows and never invents a tag`() {
        assertTrue(HideTagsPicker.results(cat, "zzz").isEmpty())
        assertTrue(HideTagsPicker.results(emptyList(), "").isEmpty())
    }

    @Test fun `the mark covers the typed text in the name's own spelling`() {
        assertEquals(0..2, HideTagsPicker.matchRange("kids-tv", "KID"))
        assertEquals(4..6, HideTagsPicker.matchRange("old-kids-block", "kid"))
        assertNull(HideTagsPicker.matchRange("kids-tv", ""))
        assertNull(HideTagsPicker.matchRange("noir", "kid"))
    }

    @Test fun `picking toggles the exact tag and ignores case when it finds a ticked one`() {
        assertEquals(listOf("kids-tv"), HideTagsPicker.toggled(emptyList(), "kids-tv"))
        assertEquals(listOf("kids-tv", "cartoons"), HideTagsPicker.toggled(listOf("kids-tv"), "cartoons"))
        assertEquals(listOf("cartoons"), HideTagsPicker.toggled(listOf("Kids-TV", "cartoons"), "kids-tv"))
        assertTrue(HideTagsPicker.isHidden(listOf("Kids-TV"), "kids-tv"))
        assertEquals(listOf("a"), HideTagsPicker.removed(listOf("a", "b"), "b"))
    }

    @Test fun `a chip count is the tag's titles and 0 for an unknown tag and null with no catalogue`() {
        assertEquals(27, HideTagsPicker.countOf(cat, "KIDS-TV"))
        assertEquals(0, HideTagsPicker.countOf(cat, "old-kids-block"))
        assertEquals(0, HideTagsPicker.countOf(cat, "not-a-tag"))
        assertNull(HideTagsPicker.countOf(null, "kids-tv"))
    }

    @Test fun `the catalogue holds one entry per name`() {
        assertEquals(listOf("a", "b"), HideTagsPicker.catalogueOf(listOf(TagCount("a", 1), TagCount("A", 9), TagCount("", 3), TagCount("b", 2))).map { it.name })
    }
}
