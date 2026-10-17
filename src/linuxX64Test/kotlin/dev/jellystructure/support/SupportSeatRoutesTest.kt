package dev.jellystructure.support

import dev.jellystructure.auth.SessionService
import dev.jellystructure.auth.installAuthPlugin
import dev.jellystructure.auth.sha256Hex
import dev.jellystructure.config.AiConfig
import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.SupportConfig
import dev.jellystructure.server.routes.supportRoutes
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * DEB-1508 (FR-3, FR-4, FR-5, FR-8, FR-2, FR-9) — Hervør's seat token through the **real** `AuthPlugin` and the real routes: the
 * fence a `jss_` bearer meets on every kind of route (cookie, open, device, remote, `/ws`), what the five allowed routes
 * answer, and the admin's token and switch routes. The plugin is installed here, unlike [SupportRoutesTest], because the
 * fence is the thing under test. Stub routes stand in for the routes the seat must never reach.
 */
class SupportSeatRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private class Rig(val h: SupportHarness, val store: ConfigStore, val service: SupportService, val cookie: String)

    private fun rig(tag: String, body: suspend ApplicationTestBuilder.(Rig) -> Unit) = testApplication {
        val h = SupportHarness(tag)
        val toml = "/tmp/jellystructure-test-seat-$tag-${getpid()}.toml"
        val store = ConfigStore(toml)
        runBlocking { store.update(AppConfig(ai = AiConfig(apiKey = "sk-test"), support = SupportConfig(enabled = true))) }
        val service = SupportService(h.db, { store.current })
        val sessions = SessionService(h.db)
        val rig = Rig(h, store, service, sessions.create("u-root", "root", "jf-token"))
        try {
            application {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                installAuthPlugin(sessions, validateSeatToken = { service.checkSeatToken(it) })
                routing {
                    route("/api") {
                        supportRoutes(service, store)
                        get("/config") { call.respondText("the config") }
                        get("/health") { call.respondText("ok") }
                        get("/remote/devices") { call.respondText("devices") }
                        get("/tv/config") { call.respondText("tv config") }
                        post("/tv/login") { call.respondText("login") }
                    }
                    get("/ws") { call.respondText("socket") }
                }
            }
            body(rig)
        } finally {
            h.close()
            runCatching { platform.posix.remove(toml) }
        }
    }

    private fun HttpRequestBuilder.admin(r: Rig) = header(HttpHeaders.Cookie, "js_session=${r.cookie}")
    private fun HttpRequestBuilder.seat(token: String) = header(HttpHeaders.Authorization, "Bearer $token")
    private fun HttpRequestBuilder.jsonBody(body: String) { contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpClient.adminPost(r: Rig, path: String, body: String = "{}"): HttpResponse =
        post("/api$path") { admin(r); jsonBody(body) }

    private suspend fun HttpClient.seatPost(token: String, path: String, body: String = "{}"): HttpResponse =
        post("/api$path") { seat(token); jsonBody(body) }

    private suspend fun HttpClient.seatGet(token: String, path: String): HttpResponse = get("/api$path") { seat(token) }

    private suspend fun tokenOf(res: HttpResponse): String = json.parseToJsonElement(res.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content

    /** Issues a token through the route and switches her on through the route. Returns the plaintext. */
    private suspend fun ApplicationTestBuilder.issueAndSwitchOn(r: Rig): String {
        val issued = client.adminPost(r, "/support/seat-token")
        assertEquals(HttpStatusCode.Created, issued.status, issued.bodyAsText())
        val token = tokenOf(issued)
        val on = client.adminPost(r, "/support/hervor", """{"enabled":true}""")
        assertEquals(HttpStatusCode.OK, on.status, on.bodyAsText())
        return token
    }

    private fun Rig.toHervor(user: String = "u-anna"): Long {
        val n = h.ok(service.file(user, "phone", "d1", "It stops after ten minutes")).ticket.number
        h.ok(service.forward(n, "root", "HERVOR", null, "HERVOR", ""))
        return n
    }

    // ── FR-3, FR-4 — the fence ───────────────────────────────────────────────────────────────────────────────────────

    @Test fun anUnknownOrRevokedBearerIs401OnEveryKindOfRouteTheOpenOnesIncluded() = rig("fence-401") { r ->
        val replaced = tokenOf(client.adminPost(r, "/support/seat-token"))
        val live = tokenOf(client.adminPost(r, "/support/seat-token"))
        for (path in listOf("/support/tickets", "/support/summary", "/config", "/health", "/tv/config", "/remote/devices")) {
            val bad = client.seatGet("jss_not-a-token", path)
            assertEquals(HttpStatusCode.Unauthorized, bad.status, path)
            assertTrue("seat_token_invalid" in bad.bodyAsText(), path)
        }
        assertEquals(HttpStatusCode.Unauthorized, client.seatPost("jss_not-a-token", "/tv/login").status, "an open path is still judged as a seat call")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/ws") { seat("jss_not-a-token") }.status, "the /ws upgrade too")
        // The replaced token is just as unknown; the live one is judged on the switch (off here), not as unknown.
        assertEquals(HttpStatusCode.Unauthorized, client.seatGet(replaced, "/support/tickets").status)
        assertEquals(HttpStatusCode.Forbidden, client.seatGet(live, "/support/tickets").status)
        // With no bearer at all nothing changed: the cookie branch, and a plain 401 without one.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/support/tickets").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/support/tickets") { header(HttpHeaders.Authorization, "Bearer nope") }.status)
    }

    @Test fun aLiveTokenWithTheSwitchOffIs403HervorDisabledEverywhere() = rig("fence-off") { r ->
        val token = tokenOf(client.adminPost(r, "/support/seat-token"))
        for (path in listOf("/support/tickets", "/support/summary", "/support/log", "/config", "/health")) {
            val res = client.seatGet(token, path)
            assertEquals(HttpStatusCode.Forbidden, res.status, path)
            assertTrue("hervor_disabled" in res.bodyAsText(), path)
        }
    }

    @Test fun aLiveTokenWithTheSwitchOnIs403NotForSeatOnEveryRouteButTheFive() = rig("fence-on") { r ->
        val token = issueAndSwitchOn(r)
        val n = r.toHervor()
        val refused = listOf(
            "GET" to "/config", "GET" to "/health", "GET" to "/remote/devices", "GET" to "/tv/config", "POST" to "/tv/login", "GET" to "/ws",
            "POST" to "/support/enabled", "POST" to "/support/hervor", "GET" to "/support/seat-token", "POST" to "/support/seat-token",
            "DELETE" to "/support/seat-token", "GET" to "/support/log", "GET" to "/support/tickets/$n/transcript",
            "POST" to "/support/tickets/$n/state", "POST" to "/support/tickets/$n/class", "POST" to "/support/tickets/$n/flag",
            "POST" to "/support/tickets/$n/read", "DELETE" to "/support/tickets/$n", "PUT" to "/support/tickets/$n", "GET" to "/support/tickets/",
            "POST" to "/support/tickets", "GET" to "/support/tickets/$n/messages", "POST" to "/support/tickets/$n/messages/",
        )
        for ((method, path) in refused) {
            val url = if (path == "/ws") path else "/api$path"
            val res = when (method) {
                "GET" -> client.get(url) { seat(token) }
                "POST" -> client.post(url) { seat(token); jsonBody("{}") }
                "PUT" -> client.put(url) { seat(token); jsonBody("{}") }
                else -> client.delete(url) { seat(token) }
            }
            assertEquals(HttpStatusCode.Forbidden, res.status, "$method $path")
            assertTrue("not_for_seat" in res.bodyAsText(), "$method $path: ${res.bodyAsText()}")
        }
        // The percent-encoded spelling of a refused route is the same route (the decoded path is what is matched).
        assertEquals(HttpStatusCode.Forbidden, client.get("/%61pi/config") { seat(token) }.status)
        // A jss_ bearer beside a valid admin cookie is still a seat call.
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/config") { seat(token); admin(r) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/support/log") { seat(token); admin(r) }.status)
        // The admin's cookie alone is unchanged.
        assertEquals(HttpStatusCode.OK, client.get("/api/config") { admin(r) }.status)
        // …and none of it moved the ticket.
        assertEquals("HERVOR", r.h.db.supportQueries.ticket(n).executeAsOne().holder)
    }

    @Test fun theFiveAllowedRoutesAnswerTheSeatInsteadOfTheAdmin() = rig("fence-five") { r ->
        val token = issueAndSwitchOn(r)
        val mine = r.toHervor()
        val notMine = r.h.ok(r.service.file("u-ben", "phone", "d1", "Another viewer")).ticket.number

        val summary = json.parseToJsonElement(client.seatGet(token, "/support/summary").bodyAsText()).jsonObject
        assertEquals("1", summary["owedByHervor"]!!.jsonPrimitive.content)
        assertEquals("true", summary["hervorEnabled"]!!.jsonPrimitive.content)

        val list = client.seatGet(token, "/support/tickets?owes=HERVOR&flagged=true")
        assertEquals(HttpStatusCode.OK, list.status)
        val rows = json.parseToJsonElement(list.bodyAsText()).jsonObject["tickets"]!!.toString()
        assertTrue("\"number\":$mine" in rows && "\"number\":$notMine" !in rows, rows)
        assertTrue("\"ownerName\":\"\"" in rows && "\"ownerId\":\"\"" in rows && "u-anna" !in rows && "Anna" !in rows, rows)

        assertEquals(HttpStatusCode.OK, client.seatGet(token, "/support/tickets/$mine").status)
        assertEquals(HttpStatusCode.NotFound, client.seatGet(token, "/support/tickets/$notMine").status, "another ticket is a 404 and never a 403")
        assertEquals(HttpStatusCode.NotFound, client.seatGet(token, "/support/tickets/9999").status)
        assertEquals(HttpStatusCode.NotFound, client.seatPost(token, "/support/tickets/$notMine/messages", """{"body":"hello"}""").status)
        assertEquals(HttpStatusCode.NotFound, client.seatPost(token, "/support/tickets/$notMine/forward", """{"holder":"ADMIN","note":"x"}""").status)

        // A reply lands as Hervør, in the thread the route answers with, and the viewer reads it.
        val reply = client.seatPost(token, "/support/tickets/$mine/messages", """{"body":"Try the other player"}""")
        assertEquals(HttpStatusCode.OK, reply.status, reply.bodyAsText())
        assertTrue("\"author\":\"HERVOR\"" in reply.bodyAsText() && "Hervør" in reply.bodyAsText())
        assertEquals("HERVOR", r.service.thread("u-anna", mine)!!.messages.last().author)
        val internal = client.seatPost(token, "/support/tickets/$mine/messages", """{"body":"see FR-321-4"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, internal.status)
        assertTrue("internal_string" in internal.bodyAsText() && "321" !in internal.bodyAsText().substringAfter("reason"), internal.bodyAsText())
        r.service.post("u-anna", mine, "phone", "d1", "thanks")
        runBlocking { r.store.update(r.store.current.copy(support = r.store.current.support.copy(hervorMessagesPerTicketPerDay = 1))) }
        val rate = client.seatPost(token, "/support/tickets/$mine/messages", """{"body":"again"}""")
        assertEquals(HttpStatusCode.TooManyRequests, rate.status)
        assertNotNull(rate.headers[HttpHeaders.RetryAfter])

        // A forward needs a note, and it is hers.
        assertEquals(HttpStatusCode.BadRequest, client.seatPost(token, "/support/tickets/$mine/forward", """{"holder":"ADMIN","owes":"ADMIN"}""").status)
        val fwd = client.seatPost(token, "/support/tickets/$mine/forward", """{"holder":"ADMIN","owes":"ADMIN","note":"Needs a person"}""")
        assertEquals(HttpStatusCode.OK, fwd.status, fwd.bodyAsText())
        assertEquals("HERVOR", r.h.db.supportQueries.messages(mine).executeAsList().last { it.kind == "MESSAGE" }.author)
        // It is no longer her turn: readable, not answerable.
        assertEquals(HttpStatusCode.OK, client.seatGet(token, "/support/tickets/$mine").status)
        assertEquals(HttpStatusCode.Conflict, client.seatPost(token, "/support/tickets/$mine/messages", """{"body":"hello"}""").status)
    }

    @Test fun theAdminKeepsEverySupportRouteByCookieExactlyAsBefore() = rig("fence-admin") { r ->
        val n = r.toHervor()
        val thread = client.get("/api/support/tickets/$n") { admin(r) }
        assertEquals(HttpStatusCode.OK, thread.status)
        assertTrue("\"ownerId\":\"u-anna\"" in thread.bodyAsText() && "\"ownerName\":\"u-anna\"" in thread.bodyAsText(), "an admin still reads who the viewer is")
        val reply = client.adminPost(r, "/support/tickets/$n/messages", """{"body":"An admin"}""")
        assertEquals(HttpStatusCode.OK, reply.status)
        assertTrue("\"author\":\"ADMIN\"" in reply.bodyAsText())
        assertEquals(HttpStatusCode.OK, client.get("/api/support/log") { admin(r) }.status)
    }

    // ── FR-2, FR-8, FR-9 — the admin's token and switch routes ───────────────────────────────────────────────────────

    @Test fun theTokenIsIssuedOnceReadAsAHistoryReplacedAndRevokedInOneClick() = rig("token-routes") { r ->
        assertEquals("""{"live":null,"past":[]}""", client.get("/api/support/seat-token") { admin(r) }.bodyAsText().replace(" ", ""))
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/support/seat-token") { admin(r) }.status, "none live: nothing changes")

        val issued = client.adminPost(r, "/support/seat-token")
        assertEquals(HttpStatusCode.Created, issued.status)
        val first = tokenOf(issued)
        assertTrue(first.startsWith("jss_"))
        val read = client.get("/api/support/seat-token") { admin(r) }.bodyAsText()
        assertTrue(first !in read && sha256Hex(first) !in read && "\"issuedBy\":\"root\"" in read, read)

        // Switching on needs a live token; with one it works and survives a restart of the service (the file says so).
        assertEquals(HttpStatusCode.OK, client.adminPost(r, "/support/hervor", """{"enabled":true}""").status)
        assertTrue(r.store.current.support.hervorEnabled)

        // Replace: the switch stays on, the old token is dead on its next request, the new one works.
        val second = tokenOf(client.adminPost(r, "/support/seat-token"))
        assertTrue(r.store.current.support.hervorEnabled && r.service.hervorEffective)
        assertEquals(HttpStatusCode.Unauthorized, client.seatGet(first, "/support/summary").status)
        assertEquals(HttpStatusCode.OK, client.seatGet(second, "/support/summary").status)

        // Revoke: one click. It answers the GET shape, switches her off, hands her tickets back, and the next request is a 401.
        val held = r.toHervor()
        val revoke = client.delete("/api/support/seat-token") { admin(r) }
        assertEquals(HttpStatusCode.OK, revoke.status, revoke.bodyAsText())
        val state = json.parseToJsonElement(revoke.bodyAsText()).jsonObject
        assertTrue(state["live"] is kotlinx.serialization.json.JsonNull)
        assertEquals(2, state["past"]!!.toString().split("revokedBy").size - 1)
        assertEquals(HttpStatusCode.Unauthorized, client.seatGet(second, "/support/summary").status)
        assertFalse(r.store.current.support.hervorEnabled, "revoking also switches her off")
        assertEquals("ADMIN", r.h.db.supportQueries.ticket(held).executeAsOne().holder)
        assertEquals("ADMIN", r.h.db.supportQueries.ticket(held).executeAsOne().owes)
        // A later token never switches her back on by itself.
        client.adminPost(r, "/support/seat-token")
        assertFalse(r.service.hervorEffective)
    }

    @Test fun switchingOnWithNoTokenIs409AndSwitchingOffHandsBackWithoutANotice() = rig("hervor-switch") { r ->
        val refused = client.adminPost(r, "/support/hervor", """{"enabled":true}""")
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertTrue("no_seat_token" in refused.bodyAsText())
        assertFalse(r.store.current.support.hervorEnabled)

        val token = issueAndSwitchOn(r)
        val n = r.toHervor()
        assertEquals(HttpStatusCode.BadRequest, client.adminPost(r, "/support/hervor", """{"enabled":"yes"}""").status)
        val off = client.adminPost(r, "/support/hervor", """{"enabled":false}""")
        assertEquals(HttpStatusCode.OK, off.status)
        assertTrue("\"hervorEnabled\":false" in off.bodyAsText(), off.bodyAsText())
        assertFalse(r.store.current.support.hervorEnabled)
        val row = r.h.db.supportQueries.ticket(n).executeAsOne()
        assertEquals("ADMIN" to "ADMIN", row.holder to row.owes)
        assertTrue(r.h.db.supportQueries.messages(n).executeAsList().none { it.author == "HERVOR" })
        // The token is still live: the seat is told why she is stopped, not that she is unknown.
        val stopped = client.seatGet(token, "/support/tickets")
        assertEquals(HttpStatusCode.Forbidden, stopped.status)
        assertTrue("hervor_disabled" in stopped.bodyAsText())
        // Switching her back on moves nothing back.
        client.adminPost(r, "/support/hervor", """{"enabled":true}""")
        assertEquals("ADMIN", r.h.db.supportQueries.ticket(n).executeAsOne().holder)
    }
}
