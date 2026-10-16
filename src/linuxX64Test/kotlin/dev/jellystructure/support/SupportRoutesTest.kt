package dev.jellystructure.support

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.DeviceKey
import dev.jellystructure.auth.SessionData
import dev.jellystructure.auth.SessionKey
import dev.jellystructure.config.AiConfig
import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.ConfigStore
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import platform.posix.getpid
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

    private fun routes(
        tag: String,
        store: ConfigStore? = null,
        serviceOf: (SupportHarness) -> SupportService = { it.service },
        body: suspend ApplicationTestBuilder.(SupportHarness) -> Unit,
    ) = testApplication {
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
                routing { route("/api") { supportRoutes(serviceOf(h), store) } }
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
    }

    // ── DEB-1500 (FR-3, FR-4) — the lane's switch ───────────────────────────────────────────────────────────────────────

    @Test fun postingEnabledOpensAndClosesTheLaneAnswersTheNewSummaryAndEmitsConfigChangedOnlyWhenAvailabilityFlips() {
        val toml = "/tmp/jellystructure-test-lane-${getpid()}.toml"
        val store = ConfigStore(toml)
        runBlocking { store.update(AppConfig(ai = AiConfig(apiKey = "sk-test"), support = SupportConfig(enabled = false, hervorEnabled = true))) }
        var configChanged = 0
        store.onSupportAvailabilityChanged = { configChanged++ }
        try {
            routes("lane", store = store, serviceOf = { SupportService(it.db, { store.current }) }) {
                val closed = json.parseToJsonElement(client.get("/api/support/summary") { header("X-Test-Admin", "1") }.bodyAsText())
                assertTrue("\"laneOpen\":false" in closed.toString(), closed.toString())

                val open = client.adminPost("/support/enabled", """{"enabled":true}""")
                assertEquals(HttpStatusCode.OK, open.status, open.bodyAsText())
                val openBody = open.bodyAsText()
                assertTrue("\"laneOpen\":true" in openBody && "\"hervorEnabled\":true" in openBody, "it answers with the new summary: $openBody")
                assertTrue(store.current.support.enabled, "written through ConfigStore.update")
                assertEquals(1, configChanged, "availability flipped: config_changed is emitted")
                client.adminPost("/support/enabled", """{"enabled":true}""")
                assertEquals(1, configChanged, "the same value again is neither a write nor an event")

                val shut = client.adminPost("/support/enabled", """{"enabled":false}""")
                assertTrue("\"laneOpen\":false" in shut.bodyAsText(), shut.bodyAsText())
                assertFalse(store.current.support.enabled)
                assertEquals(2, configChanged)
                // Closed: a viewer route is 404 again, as FR-320-1 says, and the switch itself still answers.
                assertEquals(HttpStatusCode.NotFound, client.viewerGet("/tv/support/tickets").status)

                assertEquals(HttpStatusCode.BadRequest, client.adminPost("/support/enabled", """{"enabled":"yes"}""").status)
                assertEquals(HttpStatusCode.BadRequest, client.adminPost("/support/enabled", "{}").status)
            }
            // The toml on disk says what the route says: the switch survives a restart.
            assertFalse(ConfigStore(toml).also { runBlocking { it.load() } }.current.support.enabled)
        } finally {
            runCatching { platform.posix.remove(toml) }
        }
    }

    @Test fun theSwitchIsUnavailableWithoutAConfigStoreAndATicketIsStillReadableWhileTheLaneIsClosed() = routes("lane-closed") { h ->
        assertEquals(HttpStatusCode.ServiceUnavailable, client.adminPost("/support/enabled", """{"enabled":true}""").status)
        val n = client.fileTicket()
        h.configure { copy(enabled = false) }
        val one = client.get("/api/support/tickets/$n") { header("X-Test-Admin", "1") }
        assertEquals(HttpStatusCode.OK, one.status, "a closed lane still has tickets an admin can read (FR-320-1)")
        assertEquals(HttpStatusCode.Conflict, client.adminPost("/support/tickets/$n/messages", """{"body":"x"}""").status)
    }

    // ── DEB-1500 (FR-12) — GET /api/support/log ─────────────────────────────────────────────────────────────────────────

    @Test fun theLogRouteNamesTicketsAndEventsAndNeverTheirText() = routes("log") {
        val n = client.fileTicket("PRIVATE-WORDS that the viewer typed")
        client.adminPost("/support/tickets/$n/messages", """{"body":"PRIVATE-REPLY"}""")
        client.adminPost("/support/tickets/$n/forward", """{"holder":"ADMIN","owes":"ADMIN","note":"PRIVATE-NOTE"}""")
        val r = client.get("/api/support/log?limit=50") { header("X-Test-Admin", "1") }
        assertEquals(HttpStatusCode.OK, r.status)
        val text = r.bodyAsText()
        assertFalse("PRIVATE" in text, text)
        for (action in listOf("received", "replied", "forwarded")) assertTrue("\"action\":\"$action\"" in text, "$action in $text")
        val first = json.parseToJsonElement(text).let { (it as kotlinx.serialization.json.JsonObject)["entries"] as kotlinx.serialization.json.JsonArray }
        assertEquals(3, first.size)
        // `before` is the entry id; limit is clamped, a junk value falls back to the page size.
        val firstId = (first[0] as kotlinx.serialization.json.JsonObject)["id"].toString()
        val older = client.get("/api/support/log?before=$firstId&limit=abc") { header("X-Test-Admin", "1") }.bodyAsText()
        assertFalse("\"action\":\"forwarded\"" in older, older)
        assertTrue("\"action\":\"received\"" in older)
    }

    // FR-320-1 (DEB-1273) — the wire name is snake_case and the key is omitted, not `false`, while support is unavailable.
    // `wire` is the server's own `Json` (Server.kt installs `Json { ignoreUnknownKeys = true }`: defaults are omitted).
    @Test fun theWireKeyIsSupportEnabledPresentWhenAvailableAndOmittedOtherwise() = routes("cfgwire") { h ->
        val wire = Json { ignoreUnknownKeys = true }
        val ser = dev.jellystructure.shared.tv.RaviloConfig.serializer()
        val svc = RaviloConfigService(h.db, supportAvailable = { h.service.available })
        fun encoded(): String = wire.encodeToString(ser, svc.getConfig("u-anna"))

        val on = encoded()
        assertTrue("\"support_enabled\":true" in on, "available: $on")
        assertFalse("supportEnabled" in on, "available: the Kotlin name never reaches the wire")

        val unavailable = mapOf(
            "[ai] absent" to AppConfig(support = enabled),
            "blank key" to AppConfig(ai = AiConfig(apiKey = "   "), support = enabled),
            "switch off" to AppConfig(ai = AiConfig(apiKey = "sk-test"), support = SupportConfig(enabled = false)),
        )
        for ((label, cfg) in unavailable) {
            h.cfg = cfg
            val off = encoded()
            assertFalse("support_enabled" in off, "$label: no key at all, not an explicit false: $off")
            assertFalse("supportEnabled" in off, "$label: $off")
        }
    }
}
