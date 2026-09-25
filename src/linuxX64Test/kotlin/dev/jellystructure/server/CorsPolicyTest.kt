package dev.jellystructure.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.http.HttpHeaders
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Phase 247 (FR-247-3) — the two CORS policies, wired the way Server.kt wires them: the shared one at the
 * routing root, and `/api/tv/events` with its own. A Tizen widget's WebSocket handshake carries
 * `Origin: file://` (measured on the emulator); that origin must reach the events route and nothing else,
 * and `null` — which any web page can forge — must reach nothing. (WebSockets cannot run in a native
 * test client, so the events route answers a plain GET here; the handshake itself is asserted against
 * the real binary in `tests/e2e/ravilo-screen-cors.spec.ts`.)
 */
class CorsPolicyTest {
    private val devOrigin = "localhost:8080"

    private fun ApplicationTestBuilder.wired() = application {
        routing {
            install(CORS) { jellystructurePolicy(listOf(devOrigin)) }
            route("/api/tv/events") {
                install(CORS) { tvEventsPolicy(listOf(devOrigin)) }
                get { call.respondText("events") }
            }
            get("/ws") { call.respondText("admin socket") }
            get("/api/tv/home") { call.respondText("home") }
        }
    }

    @Test
    fun `the widget origin reaches the events route and nothing else`() = testApplication {
        wired()
        val events = client.get("/api/tv/events") { header(HttpHeaders.Origin, TIZEN_WIDGET_ORIGIN) }
        assertEquals(200, events.status.value)
        assertEquals(TIZEN_WIDGET_ORIGIN, events.headers[HttpHeaders.AccessControlAllowOrigin])
        for (path in listOf("/ws", "/api/tv/home")) {
            val r = client.get(path) { header(HttpHeaders.Origin, TIZEN_WIDGET_ORIGIN) }
            assertEquals(403, r.status.value, path)
            assertNull(r.headers[HttpHeaders.AccessControlAllowOrigin], path)
        }
    }

    @Test
    fun `null and a foreign origin reach nothing`() = testApplication {
        wired()
        for (origin in listOf("null", "https://evil.example.com", "file://evil")) {
            for (path in listOf("/api/tv/events", "/ws", "/api/tv/home")) {
                val r = client.get(path) { header(HttpHeaders.Origin, origin) }
                assertEquals(403, r.status.value, "$origin $path")
                assertNull(r.headers[HttpHeaders.AccessControlAllowOrigin], "$origin $path")
            }
        }
    }

    @Test
    fun `an allow-listed dev origin still reaches both because the events policy restates the shared one`() = testApplication {
        wired()
        for (path in listOf("/api/tv/events", "/ws", "/api/tv/home")) {
            val r = client.get(path) { header(HttpHeaders.Origin, "http://$devOrigin") }
            assertEquals(200, r.status.value, path)
            assertEquals("http://$devOrigin", r.headers[HttpHeaders.AccessControlAllowOrigin], path)
        }
    }

    @Test
    fun `a preflight from a foreign origin is refused at the root`() = testApplication {
        wired()
        val r = client.options("/api/tv/home") {
            header(HttpHeaders.Origin, "https://evil.example.com")
            header(HttpHeaders.AccessControlRequestMethod, "GET")
        }
        assertEquals(403, r.status.value)
        val ok = client.options("/api/tv/home") {
            header(HttpHeaders.Origin, "http://$devOrigin")
            header(HttpHeaders.AccessControlRequestMethod, "GET")
        }
        assertEquals(200, ok.status.value)
    }
}
