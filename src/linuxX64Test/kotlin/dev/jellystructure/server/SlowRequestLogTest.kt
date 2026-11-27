package dev.jellystructure.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlowRequestLogTest {
    private val t = "/api/music/artist/{id}/page"

    @Test fun overOneSecondNamesMethodTemplateStatusAndMs() {
        val line = assertNotNull(slowRequestLine("GET", t, 200, 1001))
        assertTrue("GET" in line && t in line && "200" in line && "1001" in line)
    }

    @Test fun atOrUnderOneSecondIsSilent() {
        assertNull(slowRequestLine("GET", t, 200, 999))
        assertNull(slowRequestLine("GET", t, 200, 1000))
    }

    @Test fun imageStreamAndWsAreExcluded() {
        assertNull(slowRequestLine("GET", "/api/image/{id}", 200, 5000))
        assertNull(slowRequestLine("GET", "/api/tv/stream/{id}/master.m3u8", 200, 5000))
        assertNull(slowRequestLine("GET", "/ws", 200, 5000))
    }

    @Test fun templateStripsSelectors() {
        assertEquals(t, routeTemplate("$t/(method:GET)"))
    }
}
