package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.cleanSavedBaseUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
