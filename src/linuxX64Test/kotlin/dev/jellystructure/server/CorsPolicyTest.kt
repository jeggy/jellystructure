package dev.jellystructure.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Phase 247 (FR-247-3, FR-247-4) — the CORS policies, wired by `installCorsPolicies` exactly as Server.kt wires
 * them, over routes registered the way Server.kt registers them (flat `"/tv/…"` paths under `route("/api")`,
 * after the policies). A Tizen widget's handshake (10.0) and every POST (5.0) carry `Origin: file://`; that
 * origin must reach the token-only routes and nothing else, and `null` — which any web page can forge — must
 * reach nothing. (WebSockets cannot run in a native test client, so the events route answers a plain GET here;
 * the handshake itself is asserted against the real binary in `tests/e2e/ravilo-screen-cors.spec.ts`.)
 */
class CorsPolicyTest {
    private val devOrigin = "localhost:8080"

    private val tokenOnlyPosts = listOf("/api/tv/login", "/api/tv/signin/code", "/api/tv/playback/start", "/api/remote/play")
    private val cookiePosts = listOf("/api/tv/admin/config", "/api/auth/login", "/api/scan/cancel")

    private fun ApplicationTestBuilder.wired() = application {
        routing {
            installCorsPolicies(listOf(devOrigin))
            route("/api") {
                post("/tv/login") { call.respondText("login") }
                post("/tv/signin/code") { call.respondText("code") }
                post("/tv/playback/start") { call.respondText("start") }
                post("/tv/admin/config") { call.respondText("admin") }
                post("/remote/play") { call.respondText("play") }
                post("/auth/login") { call.respondText("auth") }
                post("/scan/cancel") { call.respondText("scan") }
                get("/tv/home") { call.respondText("home") }
            }
            route("/api/tv/events") { get { call.respondText("events") } }
            get("/ws") { call.respondText("admin socket") }
        }
    }

    private suspend fun ApplicationTestBuilder.post(path: String, origin: String): HttpResponse =
        client.post(path) { header(HttpHeaders.Origin, origin) }

    @Test
    fun `the widget origin reaches every token-only route including the events handshake`() = testApplication {
        wired()
        for (path in tokenOnlyPosts) {
            val r = post(path, TIZEN_WIDGET_ORIGIN)
            assertEquals(200, r.status.value, path)
            assertEquals(TIZEN_WIDGET_ORIGIN, r.headers[HttpHeaders.AccessControlAllowOrigin], path)
        }
        for (path in listOf("/api/tv/events", "/api/tv/home")) {
            val r = client.get(path) { header(HttpHeaders.Origin, TIZEN_WIDGET_ORIGIN) }
            assertEquals(200, r.status.value, path)
            assertEquals(TIZEN_WIDGET_ORIGIN, r.headers[HttpHeaders.AccessControlAllowOrigin], path)
        }
    }

    @Test
    fun `the widget origin is refused on the admin routes the socket and every cookie route`() = testApplication {
        wired()
        for (path in cookiePosts) {
            val r = post(path, TIZEN_WIDGET_ORIGIN)
            assertEquals(403, r.status.value, path)
            assertNull(r.headers[HttpHeaders.AccessControlAllowOrigin], path)
        }
        val ws = client.get("/ws") { header(HttpHeaders.Origin, TIZEN_WIDGET_ORIGIN) }
        assertEquals(403, ws.status.value)
        assertNull(ws.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `null and a foreign origin reach nothing`() = testApplication {
        wired()
        for (origin in listOf("null", "https://evil.example.com", "file://evil")) {
            for (path in tokenOnlyPosts + cookiePosts) {
                val r = post(path, origin)
                assertEquals(403, r.status.value, "$origin $path")
                assertNull(r.headers[HttpHeaders.AccessControlAllowOrigin], "$origin $path")
            }
            for (path in listOf("/api/tv/events", "/ws", "/api/tv/home")) {
                val r = client.get(path) { header(HttpHeaders.Origin, origin) }
                assertEquals(403, r.status.value, "$origin $path")
                assertNull(r.headers[HttpHeaders.AccessControlAllowOrigin], "$origin $path")
            }
        }
    }

    @Test
    fun `an allow-listed dev origin still reaches every route because the widget policy restates the shared one`() = testApplication {
        wired()
        for (path in tokenOnlyPosts + cookiePosts) {
            val r = post(path, "http://$devOrigin")
            assertEquals(200, r.status.value, path)
            assertEquals("http://$devOrigin", r.headers[HttpHeaders.AccessControlAllowOrigin], path)
        }
        for (path in listOf("/api/tv/events", "/ws", "/api/tv/home")) {
            val r = client.get(path) { header(HttpHeaders.Origin, "http://$devOrigin") }
            assertEquals(200, r.status.value, path)
            assertEquals("http://$devOrigin", r.headers[HttpHeaders.AccessControlAllowOrigin], path)
        }
    }

    @Test
    fun `a preflight from a foreign origin is refused and the widget origin is answered only on a token-only route`() = testApplication {
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
        val widget = client.options("/api/tv/login") {
            header(HttpHeaders.Origin, TIZEN_WIDGET_ORIGIN)
            header(HttpHeaders.AccessControlRequestMethod, "POST")
        }
        assertEquals(200, widget.status.value)
        val admin = client.options("/api/tv/admin/config") {
            header(HttpHeaders.Origin, TIZEN_WIDGET_ORIGIN)
            header(HttpHeaders.AccessControlRequestMethod, "POST")
        }
        assertEquals(403, admin.status.value)
    }
}
