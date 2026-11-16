package dev.jellystructure.server

import dev.jellystructure.auth.SessionService
import dev.jellystructure.auth.installAuthPlugin
import dev.jellystructure.db.createDatabase
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import platform.posix.getpid

/**
 * Phase 247 (FR-247-3, FR-247-4, FR-247-5) — the CORS policies, wired by `installCorsPolicies` exactly as Server.kt
 * wires them, over routes registered the way Server.kt registers them (flat `"/tv/…"` paths under `route("/api")`,
 * after the policies). A Tizen widget's handshake (10.0, 5.0) carries `Origin: file://` and a 5.0 POST carries
 * `Origin: null` (measured on the 5.0 emulator, DEB-4721); `file://` must reach the token-only routes, `null` only
 * `/api/tv` minus the admin and password routes, and neither anything else. (WebSockets cannot run in a native test
 * client, so the events route answers a plain GET here; the handshake itself is asserted against the real binary in
 * `tests/e2e/ravilo-screen-cors.spec.ts`.)
 */
class CorsPolicyTest {
    private val devOrigin = "localhost:8080"
    private val dbPath = "/tmp/jellystructure-test-cors-${getpid()}.db"

    @AfterTest fun tearDown() {
        listOf(dbPath, "$dbPath-wal", "$dbPath-shm").forEach { runCatching { platform.posix.remove(it) } }
    }

    // What FR-247-5 admits `null` on, and what it admits `file://` on.
    private val nullPosts = listOf("/api/tv/signin/code", "/api/tv/playback/start")
    private val passwordPosts = listOf("/api/tv/login", "/api/tv/account/password")
    private val filePosts = nullPosts + passwordPosts + "/api/remote/play"
    private val cookiePosts = listOf("/api/tv/admin/config", "/api/auth/login", "/api/scan/cancel")

    private fun ApplicationTestBuilder.wired() = application {
        routing {
            installCorsPolicies(listOf(devOrigin))
            route("/api") {
                post("/tv/login") { call.respondText("login") }
                post("/tv/account/password") { call.respondText("password") }
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

    private suspend fun ApplicationTestBuilder.assertAdmitted(r: HttpResponse, origin: String, what: String) {
        assertEquals(200, r.status.value, what)
        assertEquals(origin, r.headers[HttpHeaders.AccessControlAllowOrigin], what)
    }

    private fun assertRefused(r: HttpResponse, what: String) {
        assertEquals(403, r.status.value, what)
        assertNull(r.headers[HttpHeaders.AccessControlAllowOrigin], what)
    }

    @Test
    fun `the widget origin reaches every token-only route including the events handshake`() = testApplication {
        wired()
        for (path in filePosts) assertAdmitted(post(path, TIZEN_WIDGET_ORIGIN), TIZEN_WIDGET_ORIGIN, path)
        for (path in listOf("/api/tv/events", "/api/tv/home")) {
            assertAdmitted(client.get(path) { header(HttpHeaders.Origin, TIZEN_WIDGET_ORIGIN) }, TIZEN_WIDGET_ORIGIN, path)
        }
    }

    @Test
    fun `the widget origin is refused on the admin routes the socket and every cookie route`() = testApplication {
        wired()
        for (path in cookiePosts) assertRefused(post(path, TIZEN_WIDGET_ORIGIN), path)
        assertRefused(client.get("/ws") { header(HttpHeaders.Origin, TIZEN_WIDGET_ORIGIN) }, "/ws")
    }

    @Test
    fun `null reaches the token-only TV routes and the events handshake and nothing else`() = testApplication {
        wired()
        for (path in nullPosts) assertAdmitted(post(path, "null"), "null", "null $path")
        for (path in listOf("/api/tv/events", "/api/tv/home")) {
            assertAdmitted(client.get(path) { header(HttpHeaders.Origin, "null") }, "null", "null $path")
        }
        for (path in passwordPosts + "/api/remote/play" + cookiePosts) assertRefused(post(path, "null"), "null $path")
        assertRefused(client.get("/ws") { header(HttpHeaders.Origin, "null") }, "null /ws")
    }

    @Test
    fun `a foreign origin reaches nothing`() = testApplication {
        wired()
        for (origin in listOf("https://evil.example.com", "file://evil")) {
            for (path in filePosts + cookiePosts) assertRefused(post(path, origin), "$origin $path")
            for (path in listOf("/api/tv/events", "/ws", "/api/tv/home")) {
                assertRefused(client.get(path) { header(HttpHeaders.Origin, origin) }, "$origin $path")
            }
        }
    }

    @Test
    fun `an allow-listed dev origin still reaches every route because each widget policy restates the shared one`() = testApplication {
        wired()
        for (path in filePosts + cookiePosts) assertAdmitted(post(path, "http://$devOrigin"), "http://$devOrigin", path)
        for (path in listOf("/api/tv/events", "/ws", "/api/tv/home")) {
            assertAdmitted(client.get(path) { header(HttpHeaders.Origin, "http://$devOrigin") }, "http://$devOrigin", path)
        }
    }

    @Test
    fun `a preflight from a foreign origin is refused and the widget origins are answered only where admitted`() = testApplication {
        wired()
        suspend fun preflight(path: String, origin: String) = client.options(path) {
            header(HttpHeaders.Origin, origin)
            header(HttpHeaders.AccessControlRequestMethod, "POST")
        }
        assertEquals(403, preflight("/api/tv/home", "https://evil.example.com").status.value)
        assertEquals(200, preflight("/api/tv/home", "http://$devOrigin").status.value)
        assertEquals(200, preflight("/api/tv/signin/code", "null").status.value)
        assertEquals(200, preflight("/api/tv/login", TIZEN_WIDGET_ORIGIN).status.value)
        assertEquals(403, preflight("/api/tv/login", "null").status.value)
        assertEquals(403, preflight("/api/tv/admin/config", TIZEN_WIDGET_ORIGIN).status.value)
    }

    /**
     * FR-247-5's condition, pinned: `null` is admitted only where a cookie is no credential. Real AuthPlugin, a real
     * admin session, and the same routes as above — a request carrying only that cookie must be answered exactly as
     * one carrying nothing (401 on a device-token route, the anonymous answer on an open path), never as a signed-in
     * admin. A route added under `/api/tv` that starts reading `js_session` breaks this test.
     */
    @Test
    fun `a cookie alone is no credential on any route that admits null`() = testApplication {
        val db = createDatabase(dbPath)
        val sessions = SessionService(db)
        val cookie = "js_session=" + sessions.create("u-admin", "admin", "admin-jf-token")
        application {
            install(ContentNegotiation) { json() }   // AuthPlugin answers 401 with a JSON map
            installAuthPlugin(sessions, validateDeviceToken = { _, _, _ -> null })
            routing {
                installCorsPolicies(listOf(devOrigin))
                route("/api") {
                    post("/tv/signin/code") { call.respondText("anonymous") }
                    post("/tv/playback/start") { call.respondText("start") }
                    get("/tv/home") { call.respondText("home") }
                }
                route("/api/tv/events") { get { call.respondText("events") } }
            }
        }
        for (path in nullPosts + "/api/tv/home") {
            suspend fun ask(withCookie: Boolean) = if (path == "/api/tv/home") {
                client.get(path) { header(HttpHeaders.Origin, "null"); if (withCookie) header(HttpHeaders.Cookie, cookie) }
            } else {
                client.post(path) { header(HttpHeaders.Origin, "null"); if (withCookie) header(HttpHeaders.Cookie, cookie) }
            }
            val bare = ask(false); val withCookie = ask(true)
            assertEquals(bare.status.value, withCookie.status.value, path)
            assertEquals(bare.bodyAsText(), withCookie.bodyAsText(), path)
        }
        val start = client.post("/api/tv/playback/start") { header(HttpHeaders.Origin, "null"); header(HttpHeaders.Cookie, cookie) }
        assertEquals(401, start.status.value)
    }
}
