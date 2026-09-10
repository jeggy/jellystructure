package dev.jellystructure.media

import dev.jellystructure.auth.JellyfinMediaStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 200 (FR-200-6) — the guard on the "scan the directory ourselves" decision: our subtitle
 * language set and Jellyfin's own `MediaStreams` (embedded + external alike, exactly what the player
 * picker reads) must agree once sidecars are ingested.
 */
class SubtitleReconciliationTest {

    private fun stream(type: String, lang: String?) = JellyfinMediaStream(type = type, language = lang)

    @Test
    fun `identical language sets agree`() {
        val d = SubtitleReconciliation.compare("28-days-later", listOf("da", "en"), listOf(stream("Subtitle", "da"), stream("Subtitle", "eng")))

        assertTrue(d.agrees)
    }

    @Test
    fun `a language Jellyfin has that we are missing is reported as onlyJellyfin`() {
        val d = SubtitleReconciliation.compare("28-days-later", listOf("en"), listOf(stream("Subtitle", "eng"), stream("Subtitle", "dan")))

        assertEquals(setOf("da"), d.onlyJellyfin)
        assertTrue(d.onlyOurs.isEmpty())
    }

    @Test
    fun `a language we have that Jellyfin does not is reported as onlyOurs`() {
        val d = SubtitleReconciliation.compare("28-days-later", listOf("en", "hr"), listOf(stream("Subtitle", "eng")))

        assertEquals(setOf("hr"), d.onlyOurs)
    }

    @Test
    fun `B-T form differences never register as a divergence`() {
        // Jellyfin/ffprobe can report either the bibliographic or terminological ISO 639-2 form.
        val d = SubtitleReconciliation.compare("movie", listOf("fo"), listOf(stream("Subtitle", "fao")))

        assertTrue(d.agrees)
    }

    @Test
    fun `non-subtitle streams never contribute a language`() {
        val d = SubtitleReconciliation.compare("movie", emptyList(), listOf(stream("Audio", "eng"), stream("Video", null)))

        assertTrue(d.agrees)
    }

    @Test
    fun `a blank Jellyfin language is never treated as a real one`() {
        val d = SubtitleReconciliation.compare("movie", emptyList(), listOf(stream("Subtitle", "")))

        assertTrue(d.agrees)
    }
}
