package dev.jellystructure.ui

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-2916 (FR-17) — the admin's Episode order control, asserted on its markup. */
class EpisodeOrderHtmlTest {

    private fun onOption(html: String): String =
        Regex("""<span class="on" data-order="(\w+)"""").find(html)!!.groupValues[1]

    @Test
    fun `the control is the shipped seg with the two words and the help line`() {
        val h = episodeOrderHtml(EpisodeOrderState(newestFirst = false, newestFirstSource = "off"))
        assertTrue("""class="seg"""" in h, h)
        assertTrue(">Oldest first<" in h && ">Newest first<" in h, h)
        assertTrue("Episode order" in h, h)
        assertTrue("How Ravilo lists this series' episodes, and what <b>Play</b> starts." in h, h)
    }

    @Test
    fun `it shows the effective value`() {
        assertEquals("newest", onOption(episodeOrderHtml(EpisodeOrderState(true, "auto"))))
        assertEquals("oldest", onOption(episodeOrderHtml(EpisodeOrderState(false, "admin"))))
    }

    @Test
    fun `the note follows the source`() {
        assertTrue("Set by Auto-fetch: numbered by air date." in episodeOrderHtml(EpisodeOrderState(true, "auto")))
        assertTrue("Set by hand. Auto-fetch will not change it." in episodeOrderHtml(EpisodeOrderState(true, "admin")))
        val off = episodeOrderHtml(EpisodeOrderState(false, "off"))
        assertNull(episodeOrderNote("off"))
        assertFalse("ep-order-note" in off, "a series not fed by Auto-fetch has no note: $off")
    }

    @Test
    fun `a failed read says so and offers no control`() {
        val h = episodeOrderHtml(null)
        assertFalse("ep-order-seg" in h, h)
        assertTrue("Could not read the episode order." in h, h)
    }

    @Test
    fun `FR-2's two fields decode and a missing source reads as off`() {
        val json = Json { ignoreUnknownKeys = true }
        val full = json.decodeFromString(EpisodeOrderState.serializer(), """{"id":"x","newest_first":true,"newest_first_source":"auto"}""")
        assertEquals(EpisodeOrderState(true, "auto"), full)
        assertEquals(EpisodeOrderState(false, "off"), json.decodeFromString(EpisodeOrderState.serializer(), """{"id":"x"}"""))
    }
}
