package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.cleanSavedBaseUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerAddressTest {
    @Test fun `strips hidden characters around the address before inferring the scheme`() {
        assertEquals("https://jelly.example.net", serverUrlFromAddress("jelly.example.net "))
        assertEquals("https://jelly.example.net", serverUrlFromAddress("\tjelly.example.net"))
        assertEquals("https://jelly.example.net", serverUrlFromAddress("jelly.example.net/"))
        assertEquals("http://10.0.0.5:8097", serverUrlFromAddress(" \thttp://10.0.0.5:8097/ "))
        assertEquals("https://jelly.example.net", serverUrlFromAddress("jelly.example.net\uFEFF"))
    }

    @Test fun `hidden characters only leave Connect disabled`() {
        assertNull(serverUrlFromAddress(" \t\u200B\uFEFF"))
        assertNull(serverUrlFromAddress("/"))
    }

    @Test fun `dirty saved address is rewritten when read`() {
        val writes = mutableListOf<String>()
        assertEquals("https://jelly.example.net", cleanSavedBaseUrl("https://jelly.example.net ", writes::add))
        assertEquals(listOf("https://jelly.example.net"), writes)
        assertEquals("https://jelly.example.net", cleanSavedBaseUrl(writes.single(), writes::add))
        assertEquals(1, writes.size)
        assertEquals("", cleanSavedBaseUrl("", writes::add))
        assertEquals(1, writes.size)
    }

    // ── DEB-5013 (FR-4, FR-5): the warning under the field ─────────────────────────────────────────────

    private fun fixed(text: String) = hiddenAddressWarning(text)?.fixed

    @Test fun `the fix drops everything after a pasted line break`() {
        assertEquals("jelly.example.net", fixed("jelly.example.net\n[21:20]"))
        assertEquals("jelly.example.net", fixed("jelly.example.net\r\n[21:20]"))
        assertEquals("jelly.example.net", fixed("jelly.example.net\u2028[21:20]"))
    }

    @Test fun `the fix removes a space or an invisible character inside the address`() {
        assertEquals("jelly.example.net", fixed("jelly. example.net"))
        assertEquals("jelly.example.net", fixed("jelly.exam\u200Bple.net"))
        assertEquals("jelly.example.net", fixed("jelly.ex\tample.net"))
    }

    @Test fun `the fix takes the first line that is not blank`() {
        // Hidden characters at the ends are FR-1's: they are dropped before the warning is considered, so a leading
        // blank line leaves the first real line first.
        assertEquals("jelly.example.net", fixed("\n  \njelly.example.net\n[21:20]"))
        // Inside, a blank line is skipped.
        assertEquals("jelly.example.net", fixed("jelly.example.net\n\n[21:20]"))
    }

    @Test fun `a typed http scheme survives the fix`() {
        assertEquals("http://10.0.0.5:8097", fixed("http://10.0.0.5:8097\n[21:20]"))
    }

    @Test fun `a clean address, or one with only hidden characters at its ends, shows no warning`() {
        assertNull(hiddenAddressWarning("jelly.example.net"))
        assertNull(hiddenAddressWarning(" \tjelly.example.net \n"))
        assertNull(hiddenAddressWarning("jelly.example.net\uFEFF"))
        assertNull(hiddenAddressWarning(""))
        assertNull(hiddenAddressWarning(" \t\u200B"))
    }

    @Test fun `the sentence is the line break one only when there is a line break`() {
        assertTrue(hiddenAddressWarning("jelly.example.net\n[21:20]")!!.lineBreak)
        assertTrue(hiddenAddressWarning("jelly.example.net\u2029[21:20]")!!.lineBreak)
        assertFalse(hiddenAddressWarning("jelly. example.net")!!.lineBreak)
        assertFalse(hiddenAddressWarning("jelly.exam\u200Bple.net")!!.lineBreak)
        // A line break wins when both are there.
        assertTrue(hiddenAddressWarning("jelly. example.net\n[21:20]")!!.lineBreak)
    }

    @Test fun `a line break is marked with a pilcrow and any other hidden character with a middle dot`() {
        assertEquals(
            listOf(MarkRun("jelly.example.net", false), MarkRun("\u00B6", true), MarkRun("[21:20]", false)),
            hiddenAddressWarning("jelly.example.net\n[21:20]")!!.marks,
        )
        assertEquals(
            listOf(MarkRun("jelly.", false), MarkRun("\u00B7", true), MarkRun("example.net", false)),
            hiddenAddressWarning("jelly. example.net")!!.marks,
        )
        assertEquals(
            listOf(MarkRun("a", false), MarkRun("\u00B7", true), MarkRun("b", false)),
            hiddenAddressWarning("a\u200Bb")!!.marks,
        )
    }

    @Test fun `a Windows line break is one mark`() {
        assertEquals(
            listOf(MarkRun("jelly.example.net", false), MarkRun("\u00B6", true), MarkRun("[21:20]", false)),
            hiddenAddressWarning("jelly.example.net\r\n[21:20]")!!.marks,
        )
    }

    @Test fun `the warning blocks the address even though FR-1 alone would connect to it`() {
        // serverUrlFromAddress is FR-1's only: the screen, not this function, withholds the URL while the warning shows.
        assertEquals("https://jelly.example.net\n[21:20]", serverUrlFromAddress("jelly.example.net\n[21:20]"))
        assertTrue(hiddenAddressWarning("jelly.example.net\n[21:20]") != null)
    }
}
