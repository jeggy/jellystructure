package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-4899 — the language-name table moved here from `:ravilo-ui` with no change in behaviour (R247, R180 FR-RV-ASP1-1), so the
 * Samsung picker names a track by its language: every spelling of one language is one name, an unknown code is not a name.
 */
class LanguageNamesTest {
    @Test
    fun `every spelling of a language finds its own name`() {
        for (code in listOf("da", "dan", "DAN", "da-DK")) assertEquals("Dansk", endonymOf(code), code)
        assertEquals("Danish", languageName("dan"))
        assertEquals("Ελληνικά", endonymOf("gre"))
        assertEquals("Srpski", endonymOf("hbs-srp"))
    }

    @Test
    fun `a code the table does not know is not a name`() {
        assertNull(endonymOf("xx"))
        assertNull(languageName(null))
        assertFalse(isKnownLanguage("xx"))
        assertTrue(isKnownLanguage("fao"))
    }

    @Test
    fun `a language with no endonym falls back to its English name`() {
        assertEquals("Unknown", endonymOf("und"))
    }

    @Test
    fun `every alias lands on a language that has a name`() {
        for ((alias, target) in ISO_ALIASES) assertTrue(LANGUAGE_TABLE.containsKey(target), "alias $alias → $target has no name entry")
    }
}
