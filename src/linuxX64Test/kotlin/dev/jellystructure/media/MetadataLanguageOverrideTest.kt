package dev.jellystructure.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Phase 191 — an operator's explicit metadataLanguage choice must win the TMDB query-language order on
 * every fetch path, not only the Re-pull button. These cover the two shared helpers every scan/sync
 * path now calls; the case that reproduces the live report (`oldboy-2003`, override "en", library audio
 * Korean) is the second test below.
 */
class MetadataLanguageOverrideTest {

    @Test
    fun `blank or null override normalizes to null`() {
        assertNull(normalizedMetadataLanguageOverride(null))
        assertNull(normalizedMetadataLanguageOverride(""))
        assertNull(normalizedMetadataLanguageOverride("   "))
    }

    @Test
    fun `override moves to the front of the audio-derived priority list`() {
        // oldboy-2003's shape: Korean audio drives the base priority, but the operator set English.
        val basePriority = listOf("ko", "en")
        assertEquals(listOf("en", "ko"), overriddenLangPriority(basePriority, "en"))
    }

    @Test
    fun `override already first is a no-op`() {
        val basePriority = listOf("en", "ko")
        assertEquals(basePriority, overriddenLangPriority(basePriority, "en"))
    }

    @Test
    fun `no override leaves the audio-derived list untouched`() {
        val basePriority = listOf("ko", "en")
        assertEquals(basePriority, overriddenLangPriority(basePriority, null))
    }

    @Test
    fun `override not present in the base list is still prepended`() {
        // A Faroese override on a Danish-audio title: fo isn't derivable from any audio track.
        val basePriority = listOf("da", "en")
        assertEquals(listOf("fo", "da", "en"), overriddenLangPriority(basePriority, "fo"))
    }
}
