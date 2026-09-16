package dev.jellystructure.ravilo.ui.seams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R247 (FR-R247-7) — one canonical identity for every string the player produces. The cases below
 * include every string observed on the stue TV during the 2026-09-16 sweep (`el`, `hbs-srp`,
 * `hbs-hrv`, `dan`/`da`, `eng`/`en`, `gre`, `iw`) and every language code the production library held
 * that day.
 */
class LanguageIdentityTest {

    private val observedOnDevice = mapOf(
        "el" to "el", "gre" to "el", "ell" to "el",
        "hbs-srp" to "sr", "hbs-hrv" to "hr", "hbs-bos" to "bs", "hbs-cnr" to "cnr",
        "dan" to "da", "da" to "da",
        "eng" to "en", "en" to "en", "EN" to "en", " Eng " to "en",
        "iw" to "he", "in" to "id", "ji" to "yi",
        "ar-eg" to "ar", "zh-hans" to "zh", "zh-Hant-TW" to "zh", "ms-my" to "ms", "pt-BR" to "pt",
        "ger" to "de", "deu" to "de", "de" to "de",
        "fre" to "fr", "fra" to "fr",
        "cze" to "cs", "ces" to "cs",
        "dut" to "nl", "nld" to "nl",
        "rum" to "ro", "ron" to "ro",
        "slo" to "sk", "slk" to "sk",
        "ice" to "is", "isl" to "is",
        "chi" to "zh", "zho" to "zh",
        "per" to "fa", "fas" to "fa",
        "may" to "ms", "msa" to "ms",
        "arm" to "hy", "hye" to "hy",
        "alb" to "sq", "sqi" to "sq",
        "bur" to "my", "mya" to "my",
        "geo" to "ka", "kat" to "ka",
        "mac" to "mk", "mkd" to "mk",
        "wel" to "cy", "cym" to "cy",
        "baq" to "eu", "eus" to "eu",
        "tib" to "bo", "bod" to "bo",
        "mao" to "mi", "mri" to "mi",
        "scc" to "sr", "scr" to "hr",
    )

    /** Every distinct `language` value in `media.json` across the production library, 2026-09-16. */
    private val productionCodes = listOf(
        "eng", "dan", "swe", "spa", "nor", "fin", "da", "por", "fre", "chi", "en", "ger", "hr", "tur", "kor",
        "dut", "ita", "pol", "gre", "sr", "jpn", "rum", "hun", "cze", "fao", "tha", "rus", "nob", "ind", "heb",
        "slo", "may", "ara", "vie", "bul", "hrv", "ukr", "slv", "lav", "lit", "est", "hin", "tel", "tam", "srp",
        "mac", "fil", "ice", "cat", "glg", "baq", "mal", "kan", "fra", "zho", "mon", "sv", "nld", "no", "fi",
        "deu", "msa", "ell", "ces", "ben", "urd", "slk", "pan", "nep", "mar", "guj", "zxx", "cpe", "fo", "ron",
        "pl", "nl", "is", "de", "sin", "mkd", "isl", "arc", "alb", "tgl", "tah", "ru", "pt", "per", "kir", "khm",
        "kaz", "geo", "fa", "eus", "bos", "aze", "arm", "ar",
    )

    @Test
    fun `canonicalLanguage maps every observed string to one key`() {
        for ((input, expected) in observedOnDevice) {
            assertEquals(expected, canonicalLanguage(input), "canonicalLanguage($input)")
        }
    }

    @Test
    fun `an unrecognised code comes back unchanged and never null`() {
        assertEquals("qqq", canonicalLanguage("qqq"))
        assertEquals("qqq", canonicalLanguage(" QQQ "))
        assertNull(canonicalLanguage(null))
        assertNull(canonicalLanguage("   "))
    }

    @Test
    fun `every alias target has a name so a recognised code can never reach the screen as a code`() {
        for ((alias, target) in ISO_ALIASES) {
            assertTrue(LANGUAGE_TABLE.containsKey(target), "alias $alias → $target has no name entry")
        }
    }

    @Test
    fun `every production code resolves to a name and an endonym`() {
        for (code in productionCodes) {
            assertNotNull(languageName(code), "languageName($code)")
            assertNotNull(endonymOf(code), "endonymOf($code)")
            assertTrue(isKnownLanguage(code), "isKnownLanguage($code)")
        }
    }

    @Test
    fun `the sweep's codes get their names`() {
        assertEquals("Ελληνικά", endonymOf("el"))
        assertEquals("Ελληνικά", endonymOf("gre"))
        assertEquals("Srpski", endonymOf("hbs-srp"))
        assertEquals("Hrvatski", endonymOf("hbs-hrv"))
        assertEquals("Magyar", endonymOf("hun"))
        assertEquals("Română", endonymOf("rum"))
        assertEquals("Slovenčina", endonymOf("slo"))
        assertEquals("Greek", languageName("gre"))
        assertEquals("Serbian", languageName("hbs-srp"))
        assertEquals("Hebrew", languageName("iw"))
        assertEquals("Dansk", endonymOf("dan"))
        assertEquals("Danish", languageName("DA"))
        assertNull(languageName("qqq"))
        assertNull(endonymOf("qqq"))
        assertFalse(isKnownLanguage("qqq"))
    }

    @Test
    fun `sameLanguage is canonical identity`() {
        assertTrue(sameLanguage("eng", "en"))
        assertTrue(sameLanguage("da", "dan"))
        assertTrue(sameLanguage("hbs-srp", "sr"))
        assertTrue(sameLanguage("gre", "el"))
        assertTrue(sameLanguage("iw", "heb"))
        assertTrue(sameLanguage("qqq", "QQQ"))
        assertTrue(sameLanguage(null, null))
        assertFalse(sameLanguage("da", "sv"))
        assertFalse(sameLanguage(null, "da"))
        assertFalse(sameLanguage("sr", "hr"))
    }
}
