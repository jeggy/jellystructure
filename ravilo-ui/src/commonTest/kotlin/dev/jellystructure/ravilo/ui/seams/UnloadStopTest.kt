package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.PlainPost
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R372 (FR-R372-11, amendment 2026-10-12, acceptance A1) — a stop sent while the page is going away leaves through a
 * transport that survives it, and every other platform's stop is untouched.
 */
class UnloadStopTest {

    @AfterTest fun clear() { UnloadStop.transport = null }

    private fun aStop() = PlainPost("https://example.invalid/api/tv/playback/stop", mapOf("Authorization" to "Bearer t"), "{}")

    @Test fun `with no transport installed nothing is posted here`() {
        UnloadStop.transport = null
        assertFalse(UnloadStop.post(aStop()))
    }

    @Test fun `the browser's transport takes the stop whole`() {
        val taken = mutableListOf<PlainPost>()
        UnloadStop.transport = { taken += it; true }
        assertTrue(UnloadStop.post(aStop()))
        assertEquals(1, taken.size)
        assertEquals("Bearer t", taken.single().headers["Authorization"])
        assertEquals("{}", taken.single().body)
    }

    @Test fun `a transport that refuses, or throws, leaves the ordinary stop to run`() {
        UnloadStop.transport = { false }
        assertFalse(UnloadStop.post(aStop()))
        UnloadStop.transport = { throw IllegalStateException("fetch is gone") }
        assertFalse(UnloadStop.post(aStop()))
    }

    @Test fun `unloading is set only while the handler runs`() {
        assertFalse(UnloadStop.unloading)
        var seen = false
        UnloadStop.whileUnloading { seen = UnloadStop.unloading }
        assertTrue(seen)
        assertFalse(UnloadStop.unloading)
    }

    /** A stop that throws out of the handler must not leave the flag standing for every later stop. */
    @Test fun `a handler that throws clears unloading again`() {
        runCatching { UnloadStop.whileUnloading { throw IllegalStateException("the page went") } }
        assertFalse(UnloadStop.unloading)
    }
}
