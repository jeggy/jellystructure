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
        for (r in listOf("/api/tv/image/{itemId}/{type}", "/api/tv/stream/{id}/master.m3u8", "/api/tv/livetv/logo/{id}", "/api/track/{id}/stream")) {
            assertNull(slowRequestLine("GET", r, 200, 5000), r)
        }
    }

    @Test fun handlerWorkRoutesThatLookLikeMediaAreLogged() {
        for (r in listOf("/api/auth/logout", "/api/media/batch/artwork", "/api/{id}/artwork/use", "/api/tv/download/start", "/api/stream/stop")) {
            assertNotNull(slowRequestLine("POST", r, 200, 5000), r)
        }
    }

    @Test fun templateStripsSelectors() {
        assertEquals(t, routeTemplate("$t/(method:GET)"))
    }
}
