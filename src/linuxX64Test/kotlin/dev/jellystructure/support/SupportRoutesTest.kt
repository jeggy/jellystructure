package dev.jellystructure.support

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.DeviceKey
import dev.jellystructure.auth.SessionData
import dev.jellystructure.auth.SessionKey
import dev.jellystructure.config.AiConfig
import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.SupportConfig
import dev.jellystructure.server.routes.supportRoutes
import dev.jellystructure.shared.tv.SupportThread
import dev.jellystructure.tv.RaviloConfigService
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 320 (FR-320-1, FR-320-3, FR-320-5, FR-320-11, FR-320-19) — the support lane's routes through `testApplication`,
 * with the real service and store. The auth plugin is not under test: a request names its viewer with `X-Test-User` and
 * `X-Test-Device`, and `X-Test-Admin` stands for the admin cookie. [SupportHarness] holds the config the routes read.
 */
class SupportRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun routes(tag: String, body: suspend ApplicationTestBuilder.(SupportHarness) -> Unit) = testApplication {
        val h = SupportHarness(tag)
        try {
            application {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                intercept(ApplicationCallPipeline.Plugins) {
                    call.request.headers["X-Test-User"]?.let { u ->
                        val d = call.request.headers["X-Test-Device"] ?: "d1"
                        call.attributes.put(DeviceKey, DeviceData(d, "tok-$d", u, u.removePrefix("u-"), "jf", isAdmin = false))
                    }
                    if (call.request.headers["X-Test-Admin"] != null) call.attributes.put(SessionKey, SessionData("t", "u-root", "root", "jf", Long.MAX_VALUE))
                }
                routing { route("/api") { supportRoutes(h.service) } }
            }
            body(h)
        } finally { h.close() }
    }

    private suspend fun HttpClient.viewerPost(path: String, body: String, user: String = "u-anna", device: String = "d1", platform: String? = "phone"): HttpResponse =
        post("/api$path") {
            header("X-Test-User", user); header("X-Test-Device", device)
            platform?.let { header("X-Ravilo-Platform", it) }
            contentType(ContentType.Application.Json); setBody(body)
        }

    private suspend fun HttpClient.viewerGet(path: String, user: String = "u-anna", platform: String? = "phone"): HttpResponse =
        get("/api$path") { header("X-Test-User", user); platform?.let { header("X-Ravilo-Platform", it) } }

    private suspend fun HttpClient.adminPost(path: String, body: String = "{}"): HttpResponse =
        post("/api$path") { header("X-Test-Admin", "1"); contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpClient.fileTicket(text: String = "It stops after ten minutes"): Long {
        val r = viewerPost("/tv/support/tickets", """{"body":"$text"}""")
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        return json.decodeFromString(SupportThread.serializer(), r.bodyAsText()).ticket.number
    }

    @Test fun aPhoneFilesA201WithTheWholeThreadAndAnAdminAnswersAndTheViewerReadsItAsUnread() = routes("flow") {
        val n = client.fileTicket()
        val got = json.decodeFromString(SupportThread.serializer(), client.viewerGet("/tv/support/tickets/$n").bodyAsText())
        assertEquals(listOf("MESSAGE", "SYSTEM"), got.messages.map { it.kind })
        val reply = client.adminPost("/support/tickets/$n/messages", """{"body":"Try the new build"}""")
        assertEquals(HttpStatusCode.OK, reply.status, reply.bodyAsText())
        val after = json.decodeFromString(SupportThread.serializer(), client.viewerGet("/tv/support/tickets/$n").bodyAsText())
        assertEquals(1, after.ticket.unread)
        assertEquals("""{"tickets":1}""", client.viewerGet("/tv/support/unread").bodyAsText())
        assertEquals(HttpStatusCode.NoContent, client.viewerPost("/tv/support/tickets/$n/read", """{"upTo":${after.messages.last().id}}""").status)
        assertEquals("""{"tickets":0}""", client.viewerGet("/tv/support/unread").bodyAsText())
    }

    @Test fun aTvIsRefusedWith403OnFileAndPostButItsSameHereLands() = routes("tv") {
        val n = client.fileTicket()
        for (p in listOf("tv", "tizen", "cast", "cast-audio")) {
            assertEquals(HttpStatusCode.Forbidden, client.viewerPost("/tv/support/tickets", """{"body":"hi"}""", platform = p).status, p)
            assertEquals(HttpStatusCode.Forbidden, client.viewerPost("/tv/support/tickets/$n/messages", """{"body":"hi"}""", platform = p).status, p)
        }
        assertEquals(HttpStatusCode.Forbidden, client.viewerPost("/tv/support/tickets", """{"body":"hi"}""", platform = null).status)
        // A TV learns nothing about a ticket that is not its profile's: 403 whatever the number.
        assertEquals(HttpStatusCode.Forbidden, client.viewerPost("/tv/support/tickets/9999/messages", """{"body":"hi"}""", platform = "tv").status)
        val same = client.viewerPost("/tv/support/tickets/$n/same-here", "{}", device = "tv-1", platform = "tv")
        assertEquals(HttpStatusCode.OK, same.status)
        assertTrue("SAME_HERE" in same.bodyAsText())
    }

    @Test fun anotherViewersTicketIs404OnEveryViewerRoute() = routes("owner") {
        val n = client.fileTicket()
        val ben = "u-ben"
        assertEquals(HttpStatusCode.NotFound, client.viewerGet("/tv/support/tickets/$n", ben).status)
        assertEquals(HttpStatusCode.NotFound, client.viewerPost("/tv/support/tickets/$n/messages", """{"body":"x"}""", ben).status)
        assertEquals(HttpStatusCode.NotFound, client.viewerPost("/tv/support/tickets/$n/same-here", "{}", ben).status)
        assertEquals(HttpStatusCode.NotFound, client.viewerPost("/tv/support/tickets/$n/read", """{"upTo":1}""", ben).status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/tv/support/tickets/$n") { header("X-Test-User", ben) }.status)
        assertEquals("""{"tickets":[]}""", client.viewerGet("/tv/support/tickets", ben).bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.viewerGet("/tv/support/tickets/abc").status)
        assertEquals(HttpStatusCode.OK, client.viewerGet("/tv/support/tickets/$n").status, "the owner still reads it")
    }

    @Test fun aLimitAnswers429WithRetryAfterAndABadBodyAnswers400() = routes("limits") { h ->
        h.configure { copy(ticketsPerDay = 1, maxBodyChars = 20) }
        assertEquals(HttpStatusCode.BadRequest, client.viewerPost("/tv/support/tickets", """{"body":"   "}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.viewerPost("/tv/support/tickets", """{"body":"${"a".repeat(21)}"}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.viewerPost("/tv/support/tickets", """not json""").status)
        client.fileTicket("one")
        val r = client.viewerPost("/tv/support/tickets", """{"body":"two"}""")
        assertEquals(HttpStatusCode.TooManyRequests, r.status)
        assertTrue((r.headers[HttpHeaders.RetryAfter]?.toLongOrNull() ?: 0) > 0, "Retry-After")
    }

    // ── FR-320-1 — availability, across four configs ─────────────────────────────────────────────────────────────────

    private val enabled = SupportConfig(enabled = true)

    private suspend fun ApplicationTestBuilder.assertUnavailable(n: Long, label: String) {
        val c = client
        for ((what, r) in listOf(
            "list" to c.viewerGet("/tv/support/tickets"),
            "unread" to c.viewerGet("/tv/support/unread"),
            "file" to c.viewerPost("/tv/support/tickets", """{"body":"hi"}"""),
            "thread" to c.viewerGet("/tv/support/tickets/$n"),
            "post" to c.viewerPost("/tv/support/tickets/$n/messages", """{"body":"hi"}"""),
            "same-here" to c.viewerPost("/tv/support/tickets/$n/same-here", "{}"),
            "read" to c.viewerPost("/tv/support/tickets/$n/read", """{"upTo":1}"""),
            "delete" to c.delete("/api/tv/support/tickets/$n") { header("X-Test-User", "u-anna") },
        )) assertEquals(HttpStatusCode.NotFound, r.status, "$label: viewer $what")
        for ((what, path, body) in listOf(
            Triple("reply", "/support/tickets/$n/messages", """{"body":"hi"}"""),
            Triple("forward", "/support/tickets/$n/forward", """{"holder":"ADMIN","owes":"ADMIN"}"""),
            Triple("state", "/support/tickets/$n/state", """{"state":"RESOLVED"}"""),
            Triple("class", "/support/tickets/$n/class", """{"class":"BUG"}"""),
            Triple("flag", "/support/tickets/$n/flag", """{"flagged":true}"""),
            Triple("read", "/support/tickets/$n/read", """{"upTo":1}"""),
        )) {
            val r = c.adminPost(path, body)
            assertEquals(HttpStatusCode.Conflict, r.status, "$label: admin $what")
            assertTrue("support_unavailable" in r.bodyAsText(), "$label: admin $what: ${r.bodyAsText()}")
        }
        assertEquals(HttpStatusCode.Conflict, c.delete("/api/support/tickets/$n") { header("X-Test-Admin", "1") }.status, "$label: admin delete")
        // Admin reads still answer, so the data stays readable.
        assertEquals(HttpStatusCode.OK, c.get("/api/support/tickets") { header("X-Test-Admin", "1") }.status, "$label: admin list")
        assertEquals(HttpStatusCode.OK, c.get("/api/support/tickets/$n") { header("X-Test-Admin", "1") }.status, "$label: admin thread")
        assertEquals(HttpStatusCode.OK, c.get("/api/support/summary") { header("X-Test-Admin", "1") }.status, "$label: admin summary")
    }

    private fun supportEnabledFor(h: SupportHarness): Boolean {
        val db = h.db
        return RaviloConfigService(db, supportAvailable = { h.service.available }).getConfig("u-anna").supportEnabled
    }

    @Test fun noKeyABlankKeyAndTheSwitchOffEachRemoveTheLaneWhileAnAiOffKeyedConfigKeepsIt() = routes("avail") { h ->
        val n = client.fileTicket()
        val before = client.viewerGet("/tv/support/tickets/$n").bodyAsText()
        assertTrue(supportEnabledFor(h))

        // 1. `[ai]` absent entirely (the default block: no key).
        h.cfg = AppConfig(support = enabled)
        assertFalse(supportEnabledFor(h), "[ai] absent")
        assertUnavailable(n, "ai absent")
        // 2. A blank key.
        h.cfg = AppConfig(ai = AiConfig(apiKey = "   "), support = enabled)
        assertFalse(supportEnabledFor(h), "blank key")
        assertUnavailable(n, "blank key")
        // 3. A key, and the switch off.
        h.cfg = AppConfig(ai = AiConfig(apiKey = "sk-test"), support = SupportConfig(enabled = false))
        assertFalse(supportEnabledFor(h), "switch off")
        assertUnavailable(n, "support disabled")

        // 4. A key, AI itself switched off: fully available, and nothing here calls Anthropic (no client exists).
        h.cfg = AppConfig(ai = AiConfig(enabled = false, apiKey = "sk-test"), support = enabled)
        assertTrue(supportEnabledFor(h))
        assertEquals(before, client.viewerGet("/tv/support/tickets/$n").bodyAsText(), "removing the key and putting it back loses nothing")
        assertEquals(HttpStatusCode.OK, client.adminPost("/support/tickets/$n/messages", """{"body":"by hand"}""").status)
        assertEquals(HttpStatusCode.OK, client.adminPost("/support/tickets/$n/forward", """{"holder":"SEAT","seat":"ops","owes":"ADMIN"}""").status)
        assertEquals(HttpStatusCode.OK, client.adminPost("/support/tickets/$n/state", """{"state":"IN_PROGRESS"}""").status)
        assertEquals(HttpStatusCode.OK, client.adminPost("/support/tickets/$n/class", """{"class":"BUG"}""").status)
        assertEquals(HttpStatusCode.OK, client.adminPost("/support/tickets/$n/flag", """{"flagged":true}""").status)
        assertEquals(HttpStatusCode.NoContent, client.adminPost("/support/tickets/$n/read", """{"upTo":1}""").status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/support/tickets/$n") { header("X-Test-Admin", "1") }.status)
    }

    @Test fun theConfigBlockIsOffByDefaultAndTheDefaultAppConfigHasNoLane() {
        val c = AppConfig()
        assertFalse(c.support.enabled)
        assertFalse(c.support.availableWith(c.ai))
        assertFalse(SupportConfig(enabled = true).availableWith(AiConfig()))
        assertTrue(SupportConfig(enabled = true).availableWith(AiConfig(apiKey = "k")))
        assertFalse(SupportConfig(enabled = false).availableWith(AiConfig(apiKey = "k")))
    }

    @Test fun supportEnabledIsResolvedOnEveryReadAndNeverStored() = routes("cfgdto") { h ->
        val svc = RaviloConfigService(h.db, supportAvailable = { h.service.available })
        assertTrue(svc.getConfig("u-anna").supportEnabled)
        assertTrue(svc.getGlobalConfig().supportEnabled)
        svc.save("u-anna", svc.getConfig("u-anna"))   // a layout saved while the lane was on…
        h.cfg = AppConfig(support = enabled)          // …and then the key goes
        assertFalse(svc.getConfig("u-anna").supportEnabled, "a stale stored true must not draw an entry")
        assertFalse(svc.getGlobalConfig().supportEnabled)
        assertTrue("\"supportEnabled\":true" in Json.encodeToString(dev.jellystructure.shared.tv.RaviloConfig.serializer(), svc.getConfig("u-anna").copy(supportEnabled = true)))
    }
}
