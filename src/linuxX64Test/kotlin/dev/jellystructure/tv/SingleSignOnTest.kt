package dev.jellystructure.tv

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.JellyfinPolicy
import dev.jellystructure.auth.LoginRateLimiter
import dev.jellystructure.auth.SessionService
import dev.jellystructure.auth.installAuthPlugin
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.server.routes.SsoLimits
import dev.jellystructure.server.routes.singleSignOnApiRoutes
import dev.jellystructure.server.routes.singleSignOnReturnRoute
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.queryString
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2233 (FR-8 to FR-11, FR-13, FR-14, FR-15, FR-16; slice 4) — single sign-on behind jellystructure's own address. The real
 * [SingleSignOnService], the real routes behind the real `AuthPlugin`, the real [RaviloDeviceService] on a temporary database, and a
 * fake Jellyfin on the loopback that plays the plugin's four routes (`GetNames`, `start`, `redirect`, `Auth`) plus Quick Connect and
 * `Sessions/Logout`, and records what it was sent: which `Host` each plugin call carried, which tokens approved a code, which
 * were logged out.
 */
private val run = getpid().toString()

class SingleSignOnTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val cleanup = mutableListOf<String>()

    @AfterTest fun tearDown() { cleanup.forEach { runCatching { platform.posix.remove(it) } }; SsoSetupFlag.clear() }

    private val adminProvider = "Jellyfin.Plugin.SSO_Auth.Api.SSOController"

    /** The plugin and the server around it. [names] null = the plugin is absent (its routes 404). */
    private class FakeJellyfin {
        @kotlin.concurrent.Volatile var names: List<String>? = listOf("Harbour ID")
        @kotlin.concurrent.Volatile var redirectHostOverride: String? = null
        @kotlin.concurrent.Volatile var pluginRefuses = false            // the callback answers 401: the group check failed
        @kotlin.concurrent.Volatile var nameReads = 0
        @kotlin.concurrent.Volatile var idpHits = 0                      // a followed redirect lands here
        @kotlin.concurrent.Volatile var base = ""
        @kotlin.concurrent.Volatile var admin = false
        val startHosts = mutableListOf<String>()
        val redirectHosts = mutableListOf<String>()
        val redirectQueries = mutableListOf<String>()
        val authBodies = mutableListOf<String>()
        val initiateDeviceIds = mutableListOf<String>()
        val authorizeTokens = mutableListOf<String>()
        val loggedOut = mutableListOf<String>()
        val states = mutableSetOf<String>()
        private val usedStates = mutableSetOf<String>()
        private val secrets = mutableMapOf<String, Pair<String, String>>()   // secret → (code, DeviceId)
        private val authorized = mutableMapOf<String, String>()              // code → user id
        private val bootTokens = mutableMapOf<String, String>()              // plugin-issued token → user id
        val live = mutableMapOf<String, String>()                            // real token → DeviceId
        private var n = 0

        private fun field(h: String?, name: String) = Regex("""$name="([^"]*)"""").find(h.orEmpty())?.groupValues?.get(1)
        private fun bodyField(body: String, name: String) = Regex(""""$name"\s*:\s*"([^"]*)"""").find(body)?.groupValues?.get(1)
        private fun user(id: String = "u-alice") =
            """{"Id":"$id","Name":"alice","Policy":{"IsAdministrator":$admin,"AuthenticationProviderId":"Jellyfin.Plugin.SSO_Auth.Api.SSOController"}}"""

        fun install(app: io.ktor.server.application.Application) = with(app) {
            routing {
                get("/SSO/OID/GetNames") {
                    nameReads++
                    val list = names ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respondText(list.joinToString(",", "[", "]") { "\"$it\"" }, ContentType.Application.Json)
                }
                get("/SSO/OID/start/{p}") {
                    val host = call.request.headers[HttpHeaders.Host].orEmpty()
                    startHosts += host
                    val provider = call.parameters["p"].orEmpty()
                    val state = "state-${++n}-$run"; states += state
                    val redirectUri = "https://${redirectHostOverride ?: host}/sso/OID/redirect/${provider.encodeURLParameter()}"
                    call.response.headers.append(HttpHeaders.Location, "$base/idp/authorize?client_id=ravilo&state=$state&redirect_uri=${redirectUri.encodeURLParameter()}")
                    call.respond(HttpStatusCode.Found)
                }
                get("/idp/authorize") { idpHits++; call.respondText("idp") }
                get("/sso/OID/redirect/{p}") {
                    redirectHosts += call.request.headers[HttpHeaders.Host].orEmpty()
                    redirectQueries += call.request.queryString()
                    if (pluginRefuses) return@get call.respondText("<html>plugin: not allowed</html>", ContentType.Text.Html, HttpStatusCode.Unauthorized)
                    call.respondText("<html>plugin: you may close this window</html>", ContentType.Text.Html)
                }
                post("/sso/OID/Auth/{p}") {
                    val body = call.receiveText(); authBodies += body
                    val state = bodyField(body, "Data").orEmpty()
                    if (state !in states || !usedStates.add(state)) return@post call.respond(HttpStatusCode.InternalServerError)
                    val token = "boot-token-${++n}-$run"
                    bootTokens[token] = "u-alice"
                    call.respondText("""{"User":${user()},"AccessToken":"$token"}""", ContentType.Application.Json)
                }
                get("/QuickConnect/Enabled") { call.respondText("true", ContentType.Application.Json) }
                post("/QuickConnect/Initiate") {
                    val deviceId = field(call.request.headers["Authorization"], "DeviceId") ?: return@post call.respond(HttpStatusCode.BadRequest)
                    initiateDeviceIds += deviceId
                    val secret = "secret-${++n}-$run"; val code = (100000 + n).toString()
                    secrets[secret] = code to deviceId
                    call.respondText("""{"Secret":"$secret","Code":"$code","Authenticated":false}""", ContentType.Application.Json)
                }
                post("/QuickConnect/Authorize") {
                    val token = field(call.request.headers["Authorization"], "Token") ?: ""
                    authorizeTokens += token
                    val u = bootTokens[token] ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    authorized[call.request.queryParameters["code"].orEmpty()] = u
                    call.respondText("true", ContentType.Application.Json)
                }
                post("/Users/AuthenticateWithQuickConnect") {
                    val secret = bodyField(call.receiveText(), "Secret")
                    val (code, deviceId) = secrets[secret] ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    val u = authorized[code] ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    val token = "real-token-${++n}-$run"
                    live[token] = deviceId
                    call.respondText("""{"User":${user(u)},"AccessToken":"$token"}""", ContentType.Application.Json)
                }
                post("/Sessions/Logout") {
                    val token = field(call.request.headers["Authorization"], "Token") ?: ""
                    loggedOut += token
                    bootTokens.remove(token)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }

    private class Rig(
        val fake: FakeJellyfin, val devices: RaviloDeviceService, val service: SingleSignOnService, val sessions: SessionService,
        val client: HttpClient, val limits: SsoLimits, val loginLimiter: LoginRateLimiter, val store: ConfigStore,
    ) {
        var now = 1_700_000_000_000L
        private val json = Json { ignoreUnknownKeys = true }

        suspend fun post(path: String, body: String, ip: String? = null, cookie: String? = null): HttpResponse = client.post(path) {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            ip?.let { header("X-Forwarded-For", it) }
            cookie?.let { header(HttpHeaders.Cookie, it) }
            setBody(body)
        }

        suspend fun start(returnTo: String = "app", deviceId: String = "tv-1", provider: String = "Harbour ID", ip: String? = null) =
            post("/api/tv/login/sso/start", """{"provider":"$provider","deviceId":"$deviceId","deviceName":"Living room","platform":"android-tv","returnTo":"$returnTo"}""", ip)

        suspend fun claim(attemptId: String, secret: String, handoff: String, ip: String? = null) =
            post("/api/tv/login/sso/claim", """{"attemptId":"$attemptId","secret":"$secret","handoff":"$handoff"}""", ip)

        /** The identity provider returns the browser to `/sso/OID/redirect/{provider}`. */
        suspend fun back(state: String, provider: String = "Harbour%20ID", extra: String = "", lang: String? = null, cookie: String? = null): HttpResponse =
            client.get("/sso/OID/redirect/$provider?code=idp-code&state=$state$extra") {
                lang?.let { header(HttpHeaders.AcceptLanguage, it) }
                cookie?.let { header(HttpHeaders.Cookie, it) }
            }

        fun obj(text: String): JsonObject = json.parseToJsonElement(text).jsonObject
    }

    private class Started(val authorizeUrl: String, val attemptId: String, val secret: String) {
        val state: String get() = Url(authorizeUrl).parameters["state"]!!
    }

    private fun rig(tag: String, body: suspend ApplicationTestBuilder.(Rig) -> Unit) = testApplication {
        val fake = FakeJellyfin()
        val jf = embeddedServer(CIO, port = 0, host = "127.0.0.1") { fake.install(this) }.start(wait = false)
        val base = "http://127.0.0.1:${jf.engine.resolvedConnectors().first().port}"
        fake.base = base
        val cfg = "/tmp/jellystructure-test-sso-$tag-$run.toml"
        val dbPath = "/tmp/jellystructure-test-sso-$tag-$run.db"
        cleanup += listOf(cfg, "$cfg.tmp", dbPath, "$dbPath-wal", "$dbPath-shm")
        runCatching { platform.posix.remove(dbPath) }
        val store = ConfigStore(cfg)
        runBlocking {
            store.update(store.current.copy(
                apiKeys = store.current.apiKeys.copy(jellyfinUrl = base, jellyfinToken = "machine-token-$run"),
                publicUrl = "https://jelly.example.org",
                raviloWebUrl = "https://ravilo.example.org",
                jellyfinInternalUrl = base,
            ))
        }
        val db = createDatabase(dbPath)
        val devices = RaviloDeviceService(db)
        val sessions = SessionService(db)
        val limits = SsoLimits()
        val loginLimiter = LoginRateLimiter()
        var clockNow = { 0L }
        val service = SingleSignOnService(db, devices, JellyfinClient(), store, sessions, clock = { clockNow() })
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            installAuthPlugin(sessions, validateDeviceToken = { t, v, p -> devices.validateDeviceToken(t, v, p) })
            routing {
                route("/api") {
                    singleSignOnApiRoutes(service, limits)
                    // Stands in for /api/tv/login: the shared bucket the new routes must stay out of.
                    post("/tv/login") {
                        val key = LoginRateLimiter.clientKey(call.request.local.remoteHost, call.request.headers["X-Forwarded-For"])
                        if (loginLimiter.tryAcquire(key)) call.respondText("login") else call.respond(HttpStatusCode.TooManyRequests)
                    }
                }
                singleSignOnReturnRoute(service)
            }
        }
        val r = Rig(fake, devices, service, sessions, createClient { followRedirects = false }, limits, loginLimiter, store)
        clockNow = { r.now }
        try { body(r) } finally { runCatching { jf.stop(0, 0) } }
    }

    private suspend fun Rig.started(returnTo: String = "app", deviceId: String = "tv-1"): Started {
        val res = start(returnTo, deviceId)
        val text = res.bodyAsText()
        assertEquals(HttpStatusCode.OK, res.status, text)
        val o = obj(text)
        return Started(o["authorizeUrl"]!!.jsonPrimitive.content, o["attemptId"]!!.jsonPrimitive.content, o["secret"]!!.jsonPrimitive.content)
    }

    /** The hand-off a success page delivers: the target of its refresh, parsed. */
    private fun handBackOf(html: String): Url {
        val target = Regex("""http-equiv="refresh" content="1;url=([^"]*)"""").find(html)?.groupValues?.get(1)
        return Url(assertNotNull(target, "the success page redirects the browser back: $html").replace("&amp;", "&"))
    }

    private fun titleOf(html: String) = Regex("<title>([^<]*)</title>").find(html)!!.groupValues[1].replace("&#39;", "'").replace("&amp;", "&")

    // ── FR-8: discovery ────────────────────────────────────────────────────────

    @Test fun `providers are the plugins names cached for a minute and empty when the plugin is absent`() = rig("providers") { r ->
        val a = r.client.get("/api/tv/login/providers")
        assertEquals(HttpStatusCode.OK, a.status, "open: no token")
        assertEquals("""{"providers":["Harbour ID"]}""", a.bodyAsText())
        assertEquals("""{"providers":["Harbour ID"]}""", r.client.get("/api/auth/providers").bodyAsText(), "the admin's sign-in page reads the same list")
        assertEquals(1, r.fake.nameReads, "both answers came from one read")
        r.now += 59_000
        r.client.get("/api/tv/login/providers"); assertEquals(1, r.fake.nameReads)
        r.fake.names = null; r.now += 2_000
        assertEquals("""{"providers":[]}""", r.client.get("/api/tv/login/providers").bodyAsText(), "an absent plugin lists nothing, and that is not an error")
        assertEquals(2, r.fake.nameReads)
        r.fake.names = emptyList(); r.now += 61_000
        assertEquals("""{"providers":[]}""", r.client.get("/api/tv/login/providers").bodyAsText())
    }

    // ── FR-9: start ────────────────────────────────────────────────────────────

    @Test fun `start asks the plugin as the public host and reads its redirect without following it`() = rig("start") { r ->
        val s = r.started()
        assertEquals(listOf("jelly.example.org"), r.fake.startHosts, "the plugin is called as jellystructure's public host")
        assertEquals(0, r.fake.idpHits, "the redirect is read, never followed")
        assertTrue(s.authorizeUrl.startsWith("${r.fake.base}/idp/authorize?"), s.authorizeUrl)
        assertEquals("https://jelly.example.org/sso/OID/redirect/Harbour%20ID", Url(s.authorizeUrl).parameters["redirect_uri"])
        assertEquals(32, s.attemptId.length); assertEquals(64, s.secret.length)
    }

    @Test fun `a redirect address that is not ours answers unavailable and raises the dashboard row`() = rig("foreign") { r ->
        r.fake.redirectHostOverride = "jellyfin.example.net"
        val res = r.start()
        assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
        assertEquals("""{"reason":"unavailable"}""", res.bodyAsText())
        val row = assertNotNull(SsoSetupFlag.dashboardRow(r.store), "the dashboard says where viewers would be sent")
        assertEquals("Single sign-on is set up wrong: the identity provider would return viewers to jellyfin.example.net", row.label)
        // A config change hides it until the next start measures again; a start that passes takes it down for good.
        r.store.update(r.store.current.copy(publicUrl = "https://jelly2.example.org"))
        assertNull(SsoSetupFlag.dashboardRow(r.store))
        r.fake.redirectHostOverride = null
        assertEquals(HttpStatusCode.OK, r.start().status)
        r.store.update(r.store.current.copy(publicUrl = "https://jelly.example.org"))
        assertNull(SsoSetupFlag.dashboardRow(r.store))
    }

    @Test fun `start is refused for a provider the plugin does not list and for a malformed return address`() = rig("badstart") { r ->
        assertEquals(HttpStatusCode.ServiceUnavailable, r.start(provider = "Nobody").status)
        for (bad in listOf("https://evil.example.net/signin", "http://127.0.0.1:7845/other", "http://127.0.0.1.evil.net:80/signin", "http://127.0.0.1:0/signin", "http://127.0.0.1:99999/signin", "javascript:alert(1)", "")) {
            assertEquals(HttpStatusCode.BadRequest, r.start(returnTo = bad).status, bad)
        }
        assertEquals(HttpStatusCode.BadRequest, r.start(deviceId = " ").status)
        assertEquals(0, r.fake.startHosts.size, "a request that fails its own checks never reaches the plugin")
        r.store.update(r.store.current.copy(publicUrl = ""))
        assertEquals(HttpStatusCode.ServiceUnavailable, r.start().status, "with no public address there is nothing to present as")
    }

    // ── FR-10, FR-11: the return and the claim ─────────────────────────────────

    @Test fun `the whole round trip mints the device its own token and yields a claimable PairResult`() = rig("trip") { r ->
        r.fake.admin = false
        val s = r.started()
        val back = r.back(s.state, extra = "&scope=openid")
        val html = back.bodyAsText()
        assertEquals(HttpStatusCode.OK, back.status, html)
        assertEquals("You’re signed in — go back to Ravilo", titleOf(html))
        // The replay carries the query as it came, and the same Host the start presented.
        assertEquals(listOf("jelly.example.org"), r.fake.redirectHosts)
        assertEquals(listOf("code=idp-code&state=${s.state}&scope=openid"), r.fake.redirectQueries)
        // The bootstrap identity, the real token's identity, and who approved what.
        assertTrue(r.fake.authBodies.single().contains(""""DeviceID":"ravilo-tv-1-sso""""), r.fake.authBodies.single())
        assertTrue(r.fake.authBodies.single().contains(""""AppName":"Ravilo""""))
        assertEquals(listOf("ravilo-tv-1-alice"), r.fake.initiateDeviceIds, "the device's own token is minted under ravilo-<deviceId>-<username>")
        val boot = r.fake.authorizeTokens.single()
        assertTrue(boot.startsWith("boot-token-"), "the account approves its own code with the plugin's token")
        assertEquals(listOf(boot), r.fake.loggedOut, "the bootstrap token is logged out")
        // Only a redirect carries the hand-off; the attempt is complete but nothing is collectable without it.
        val target = handBackOf(html)
        assertEquals("dev.jellystructure.ravilo", target.protocol.name)
        assertEquals(s.attemptId, target.parameters["attempt"])
        val handoff = target.parameters["h"]!!
        assertFalse(html.contains(s.secret))
        val claim = r.claim(s.attemptId, s.secret, handoff)
        assertEquals(HttpStatusCode.OK, claim.status, claim.bodyAsText())
        val pair = r.obj(claim.bodyAsText())
        assertEquals("u-alice", pair["session"]!!.jsonObject["user_id"]!!.jsonPrimitive.content)
        assertEquals("tv-1", pair["session"]!!.jsonObject["device_id"]!!.jsonPrimitive.content)
        val row = assertNotNull(r.devices.validateDeviceToken(pair["device_token"]!!.jsonPrimitive.content))
        assertEquals("alice", row.jellyfinUsername)
        assertTrue(row.jellyfinUserToken.startsWith("real-token-"), "the row holds the device's own token, not the bootstrap one")
        // FR-14 — such an account reads as managed elsewhere, from the sign-in's own policy.
        assertTrue(r.devices.passwordManagement.isManagedElsewhere("u-alice"))
        // Single use.
        assertEquals(HttpStatusCode.Unauthorized, r.claim(s.attemptId, s.secret, handoff).status)
    }

    @Test fun `started on one device and finished on another the claim is refused without the handoff`() = rig("phish") { r ->
        val s = r.started()
        // The victim opens the authorizeUrl in their own browser and signs in; the page they see redirects THEIR browser, not ours.
        val html = r.back(s.state).bodyAsText()
        val handoff = handBackOf(html).parameters["h"]!!
        // The attacker holds the attempt id (it is in the redirect they cannot see) and the secret (theirs), but never the hand-off.
        assertEquals(HttpStatusCode.Unauthorized, r.claim(s.attemptId, s.secret, "").status)
        assertEquals(HttpStatusCode.Unauthorized, r.claim(s.attemptId, s.secret, "0".repeat(64)).status)
        assertEquals(HttpStatusCode.Unauthorized, r.claim(s.attemptId, "wrong-secret", handoff).status, "the hand-off alone is no better")
        assertEquals(HttpStatusCode.Unauthorized, r.claim("no-such-attempt", s.secret, handoff).status)
        // None of those spent the attempt: the device that really started it, with both, still collects.
        assertEquals(HttpStatusCode.OK, r.claim(s.attemptId, s.secret, handoff).status)
    }

    @Test fun `a claim before the sign-in has finished is refused`() = rig("early") { r ->
        val s = r.started()
        assertEquals(HttpStatusCode.Unauthorized, r.claim(s.attemptId, s.secret, "anything").status)
    }

    @Test fun `the web hand-off never leaves the Ravilo Web address and the desktop's goes to its own loopback`() = rig("web") { r ->
        val web = r.started("web", "web-1")
        val target = handBackOf(r.back(web.state).bodyAsText())
        assertEquals("ravilo.example.org", target.host)
        assertEquals("https", target.protocol.name)
        assertEquals(web.attemptId, target.parameters["signin"])
        assertTrue(target.parameters["h"]!!.isNotEmpty())

        val desk = r.started("http://127.0.0.1:7845/signin", "mac-1")
        val loop = handBackOf(r.back(desk.state).bodyAsText())
        assertEquals("127.0.0.1", loop.host); assertEquals(7845, loop.port); assertEquals("/signin", loop.encodedPath)
        assertEquals(desk.attemptId, loop.parameters["attempt"])

        // Ravilo Web's address blank: the backend's own origin, and still nothing a client supplied.
        r.store.update(r.store.current.copy(raviloWebUrl = ""))
        val own = r.started("web", "web-2")
        assertEquals("jelly.example.org", handBackOf(r.back(own.state).bodyAsText()).host)
    }

    @Test fun `a plugin refusal is the not allowed page and spends the attempt`() = rig("refused") { r ->
        r.fake.pluginRefuses = true
        val s = r.started()
        val back = r.back(s.state, lang = "da")
        assertEquals(HttpStatusCode.Forbidden, back.status)
        assertEquals("Denne konto kan ikke bruge Ravilo — spørg den, der står for den", titleOf(back.bodyAsText()))
        assertTrue(r.fake.authBodies.isEmpty(), "a refused account is never asked for a token")
        assertEquals(HttpStatusCode.Unauthorized, r.claim(s.attemptId, s.secret, "x").status)
        assertEquals("That took too long — try again", titleOf(r.back(s.state).bodyAsText()), "and the state cannot be tried again")
    }

    @Test fun `an unknown or expired state is the took too long page and never reaches the plugin`() = rig("toolong") { r ->
        val unknown = r.back("state-that-was-never-issued")
        assertEquals("That took too long — try again", titleOf(unknown.bodyAsText()))
        assertTrue(r.fake.redirectHosts.isEmpty(), "an unknown state is not forwarded: jellystructure is not an open proxy to the plugin")
        val s = r.started()
        r.now += 10 * 60_000L + 1
        val late = r.back(s.state, lang = "fo")
        assertEquals("Tað tók ov langa tíð — royn aftur", titleOf(late.bodyAsText()))
        assertTrue(r.fake.redirectHosts.isEmpty())
        assertEquals(HttpStatusCode.Unauthorized, r.claim(s.attemptId, s.secret, "x").status)
        // No state at all.
        assertEquals("That took too long — try again", titleOf(r.client.get("/sso/OID/redirect/Harbour%20ID").bodyAsText()))
    }

    @Test fun `access denied is the cancelled page and another provider error is the unavailable page`() = rig("cancelled") { r ->
        val s = r.started()
        val c = r.client.get("/sso/OID/redirect/Harbour%20ID?error=access_denied&state=${s.state}")
        assertEquals("You didn’t finish signing in — try again when you’re ready", titleOf(c.bodyAsText()))
        assertTrue(r.fake.redirectHosts.isEmpty(), "nothing is replayed for a return that carries an error")
        val s2 = r.started("app", "tv-2")
        val u = r.client.get("/sso/OID/redirect/Harbour%20ID?error=server_error&state=${s2.state}") { header(HttpHeaders.AcceptLanguage, "da, en;q=0.5") }
        assertEquals("Login er ikke muligt lige nu — prøv igen om et par minutter", titleOf(u.bodyAsText()))
    }

    @Test fun `the language comes from Accept-Language in q order and falls back to English`() {
        assertEquals("da", SingleSignOnPages.languageFor("da-DK,da;q=0.9,en;q=0.8"))
        assertEquals("fo", SingleSignOnPages.languageFor("de, fo;q=0.7, en;q=0.2"))
        assertEquals("en", SingleSignOnPages.languageFor("fo;q=0.1, en;q=0.9"))
        assertEquals("en", SingleSignOnPages.languageFor("de, fr;q=0.8"))
        assertEquals("en", SingleSignOnPages.languageFor(null))
        assertEquals("en", SingleSignOnPages.languageFor("da;q=0, en;q=0.1"))
    }

    // ── FR-10, FR-13: the admin's path ─────────────────────────────────────────

    private fun cookieOf(res: HttpResponse, name: String): String? =
        res.headers.getAll(HttpHeaders.SetCookie).orEmpty().firstOrNull { it.startsWith("$name=") }

    @Test fun `an admin attempt needs its cookie and an administrator and ends in the ordinary session`() = rig("admin") { r ->
        r.fake.admin = true
        val start = r.post("/api/auth/sso/start", """{"provider":"Harbour ID"}""")
        assertEquals(HttpStatusCode.OK, start.status, start.bodyAsText())
        val setCookie = assertNotNull(cookieOf(start, "js_sso"), "the attempt secret rides a cookie")
        assertTrue(setCookie.contains("HttpOnly", ignoreCase = true) && setCookie.contains("SameSite=Lax", ignoreCase = true), setCookie)
        val secret = setCookie.substringAfter("js_sso=").substringBefore(';')
        assertEquals(64, secret.length)
        assertFalse(start.bodyAsText().contains(secret), "the secret is in the cookie and nowhere in the body")
        val state = Url(r.obj(start.bodyAsText())["authorizeUrl"]!!.jsonPrimitive.content).parameters["state"]!!

        // No cookie (another browser): nothing happens, and the plugin's code is not spent.
        val other = r.back(state)
        assertEquals(HttpStatusCode.Forbidden, other.status)
        assertTrue(other.bodyAsText().contains("not started in this browser"), "the admin's page may say so plainly")
        assertTrue(r.fake.redirectHosts.isEmpty())
        assertNull(cookieOf(other, "js_session"))

        // The right cookie, an administrator: the ordinary session and the admin page.
        val ok = r.back(state, cookie = "js_sso=$secret")
        assertEquals(HttpStatusCode.Found, ok.status, ok.bodyAsText())
        assertEquals("/admin", ok.headers[HttpHeaders.Location])
        val session = assertNotNull(cookieOf(ok, "js_session"))
        assertTrue(session.contains("HttpOnly", ignoreCase = true) && session.contains("Max-Age=604800"), session)
        val valid = assertNotNull(r.sessions.validate(session.substringAfter("js_session=").substringBefore(';')))
        assertEquals("alice", valid.jellyfinUsername)
        assertTrue(valid.jellyfinUserToken.startsWith("boot-token-"), "the admin session keeps the account's token (no device to mint for)")
    }

    @Test fun `an admin attempt by a non-administrator is refused with the admin's own sentence`() = rig("notadmin") { r ->
        r.fake.admin = false
        val start = r.post("/api/auth/sso/start", """{"provider":"Harbour ID"}""")
        val secret = cookieOf(start, "js_sso")!!.substringAfter("js_sso=").substringBefore(';')
        val state = Url(r.obj(start.bodyAsText())["authorizeUrl"]!!.jsonPrimitive.content).parameters["state"]!!
        val back = r.back(state, cookie = "js_sso=$secret")
        assertEquals(HttpStatusCode.Forbidden, back.status)
        assertTrue(back.bodyAsText().contains("Jellystructure requires a Jellyfin administrator account"))
        assertNull(cookieOf(back, "js_session"), "no session for a viewer")
    }

    // ── FR-14, FR-15, FR-16 ────────────────────────────────────────────────────

    @Test fun `a row whose provider id is the plugins reads as password managed elsewhere`() {
        assertTrue(JellyfinPolicy(isAdministrator = false, authenticationProviderId = adminProvider).passwordManagedElsewhere)
        assertFalse(JellyfinPolicy(isAdministrator = false, authenticationProviderId = dev.jellystructure.auth.DEFAULT_AUTHENTICATION_PROVIDER_ID).passwordManagedElsewhere)
    }

    private val forbidden = Regex("jellyfin|ldap|quick ?connect|plugin|\\b(400|401|403|404|500|503)\\b|/sso/|oidc", RegexOption.IGNORE_CASE)

    @Test fun `no viewer page names the server software in any language`() {
        for (lang in SingleSignOnPages.LANGUAGES) for (kind in SingleSignOnService.PageKind.values()) {
            val html = SingleSignOnPages.render(kind, lang, admin = false, handBack = "dev.jellystructure.ravilo:/signin?attempt=a&h=b".takeIf { kind == SingleSignOnService.PageKind.SIGNED_IN }, open = "https://ravilo.example.org")
            // The visible words only: the markup's own attributes and style are checked for the same words below.
            assertNull(forbidden.find(html.substringAfter("<body>").replace(Regex("<[^>]*>"), " ")), "$lang $kind")
            assertNull(forbidden.find(html.replace(Regex("""(href|content|url)="[^"]*""""), "")), "$lang $kind: ${forbidden.find(html)?.value}")
            assertFalse(html.contains("http://") || html.contains("src=") || html.contains("@import") || html.contains("<script"), "$lang $kind is one self-contained file")
        }
    }

    @Test fun `no viewer answer body names the server software either`() = rig("bodies") { r ->
        r.fake.redirectHostOverride = "jellyfin.example.net"
        val bodies = listOf(
            r.start().bodyAsText(), r.start(returnTo = "nope").bodyAsText(), r.claim("a", "b", "c").bodyAsText(),
            r.post("/api/tv/login/sso/start", "{").bodyAsText(), r.post("/api/tv/login/sso/claim", "{").bodyAsText(),
            r.client.get("/api/tv/login/providers").bodyAsText(),
        )
        for (b in bodies) assertNull(forbidden.find(b), b)
        r.fake.redirectHostOverride = null
        for (lang in SingleSignOnPages.LANGUAGES) {
            for (state in listOf("unknown", r.started("app", "tv-$lang").state)) {
                assertNull(forbidden.find(titleOf(r.back(state, lang = lang).bodyAsText())), lang)
            }
        }
    }

    @Test fun `starts are ten a minute per address in their own bucket`() = rig("startlimit") { r ->
        repeat(10) { assertEquals(HttpStatusCode.OK, r.start(deviceId = "tv-$it", ip = "198.51.100.7").status) }
        val eleventh = r.start(ip = "198.51.100.7")
        assertEquals(HttpStatusCode.TooManyRequests, eleventh.status)
        assertEquals("60", eleventh.headers[HttpHeaders.RetryAfter])
        assertEquals(HttpStatusCode.OK, r.start(ip = "198.51.100.8").status, "another address has its own count")
        assertEquals("login", r.post("/api/tv/login", "{}", ip = "198.51.100.7").bodyAsText(), "password sign-in keeps its own five")
    }

    @Test fun `claims are forty a minute per address and a success is refunded`() = rig("claimlimit") { r ->
        val s = r.started()
        val handoff = handBackOf(r.back(s.state).bodyAsText()).parameters["h"]!!
        assertEquals(HttpStatusCode.OK, r.claim(s.attemptId, s.secret, handoff, ip = "198.51.100.9").status)   // spends one, gives it back
        repeat(40) { assertEquals(HttpStatusCode.Unauthorized, r.claim("x", "y", "z", ip = "198.51.100.9").status, "failure ${it + 1}") }
        assertEquals(HttpStatusCode.TooManyRequests, r.claim("x", "y", "z", ip = "198.51.100.9").status)
        assertEquals(HttpStatusCode.Unauthorized, r.claim("x", "y", "z", ip = "198.51.100.10").status)
    }
}
