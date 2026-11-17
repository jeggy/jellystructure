package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-4889 (FR-19) — the flag table and counting rule, moved from `:ravilo-ui` with no change in behaviour (R239, R247):
 * `+N` counts every language a group has, a group of only unmapped languages still says so, aliases are one language.
 */
class LanguageFlagsTest {
    @Test
    fun `a code finds its flag through the canonical language, aliases and BCP-47 forms alike`() {
        assertEquals("gb", flagCountryFor("en"))
        assertEquals("gb", flagCountryFor("ENG"))
        assertEquals("dk", flagCountryFor("dan"))
        assertEquals("rs", flagCountryFor("hbs-srp"))
        assertEquals("gr", flagCountryFor("gre"))
        assertNull(flagCountryFor(null))
        assertNull(flagCountryFor(" "))
        assertNull(flagCountryFor("xx"))
    }

    @Test
    fun `an empty group has no languages`() {
        val counts = countFlagStrip(emptyList())
        assertFalse(counts.hasAnyLanguage)
        assertTrue(counts.shown.isEmpty())
        assertEquals(0, counts.extra)
    }

    @Test
    fun `five mapped languages show five flags and no overflow`() {
        val counts = countFlagStrip(listOf("en", "da", "fr", "de", "es"))
        assertEquals(listOf("gb", "dk", "fr", "de", "es"), counts.shown)
        assertEquals(0, counts.extra)
    }

    @Test
    fun `seven subtitle languages one of them unmapped show five flags and plus two`() {
        val counts = countFlagStrip(listOf("en", "da", "fr", "de", "es", "it", "tam"))
        assertEquals(5, counts.shown.size)
        assertEquals(2, counts.extra)
    }

    @Test
    fun `the unmapped languages beyond the shown flags still count`() {
        val counts = countFlagStrip(listOf("en", "da", "fr", "de", "es", "it") + (1..15).map { "xx$it" })
        assertEquals(5, counts.shown.size)
        assertEquals(16, counts.extra)
    }

    @Test
    fun `a group of only unmapped languages still reports languages present`() {
        val counts = countFlagStrip(listOf("tam", "tel"))
        assertTrue(counts.hasAnyLanguage)
        assertTrue(counts.shown.isEmpty())
        assertEquals(2, counts.extra)
    }

    @Test
    fun `aliases of one language are one flag and a repeated unmapped code is one language`() {
        assertEquals(listOf("no"), countFlagStrip(listOf("no", "nor", "nob", "nno")).shown)
        assertEquals(1, countFlagStrip(listOf("tam", "tam", "tam")).extra)
        assertEquals(listOf("dk"), countFlagStrip(listOf("da", "dan")).shown)
    }

    @Test
    fun `a smaller flag budget folds the dropped flags into the count`() {
        val langs = listOf("eng", "dan", "fin", "nor", "swe", "fre", "ger")
        assertEquals(2, countFlagStrip(langs).extra)
        assertEquals(4, countFlagStrip(langs, maxFlags = 3).extra)
        val none = countFlagStrip(langs, maxFlags = 0)
        assertEquals(7, none.extra)
        assertTrue(none.hasAnyLanguage)
    }

    @Test
    fun `Catalan has its own flag and Tamil and Telugu stay without one`() {
        assertEquals("ct", flagCountryFor("cat"))
        assertNull(flagCountryFor("tam"))
        assertNull(flagCountryFor("tel"))
    }

    @Test
    fun `every flag in the table is a two-letter lower-case country code`() {
        assertTrue(LANG_FLAG_CC.values.all { it.length == 2 && it == it.lowercase() })
        assertEquals(41, LANG_FLAG_CC.values.toSet().size)
    }
}
