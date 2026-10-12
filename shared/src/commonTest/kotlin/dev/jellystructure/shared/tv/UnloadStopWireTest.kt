package dev.jellystructure.shared.tv

import dev.jellystructure.shared.RaviloHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R372 (FR-R372-11, amendment 2026-10-12) — the stop a closing browser tab posts keeps the device token in its
 * `Authorization` header, like every other call. `sendBeacon`'s query-string token (FR-R293-7's precedent) is the
 * fallback the spec allows and not what this build sends, so no header here may be dropped quietly.
 */
class UnloadStopWireTest {

    @Test fun `the unload stop's headers are the ones every other call sends`() {
        val h = plainPostHeaders(version = "1.51-45", platform = "web", token = "tok-123")
        assertEquals("application/json", h["Content-Type"])
        assertEquals("1.51-45", h[RaviloHeaders.VERSION])
        assertEquals("web", h[RaviloHeaders.PLATFORM])
        assertEquals("Bearer tok-123", h["Authorization"])
    }

    @Test fun `the token is in a header and never in the url`() {
        val h = plainPostHeaders("1.51-45", "web", "tok-123")
        assertTrue(h.keys.any { it.equals("Authorization", ignoreCase = true) })
        assertFalse(h.values.any { "?" in it || "&token=" in it })
    }

    /** Before the device is paired there is no token, exactly as `auth()` leaves the header out. */
    @Test fun `no token means no Authorization header`() {
        assertFalse("Authorization" in plainPostHeaders("1.51-45", "web", null))
        assertFalse("Authorization" in plainPostHeaders("1.51-45", "web", ""))
    }
}
