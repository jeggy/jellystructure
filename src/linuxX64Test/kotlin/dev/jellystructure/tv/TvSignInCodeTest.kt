package dev.jellystructure.tv

import dev.jellystructure.auth.ApiKeyData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.LoginRateLimiter
import dev.jellystructure.auth.SessionService
import dev.jellystructure.auth.installAuthPlugin
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.server.routes.TvSignInLimits
import dev.jellystructure.server.routes.tvSignInRoutes
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
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2233 (FR-1 to FR-4, FR-15, FR-16, FR-17; slice 1) — a TV gets a code, a signed-in viewer approves it as themselves, and
 * the TV polls into a `PairResult`. The real [TvSignInService], the real routes behind the real `AuthPlugin`, the real
 * [RaviloDeviceService] on a temporary database, and a fake Jellyfin on the loopback that records what it is sent: which
 * token reached the authorise step, which identity each sign-in was minted under, and which tokens are still live.
 */
private val run = getpid().toString()

class TvSignInCodeTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val cleanup = mutableListOf<String>()

    @AfterTest fun tearDown() { cleanup.forEach { runCatching { platform.posix.remove(it) } } }

    /** Jellyfin as far as sign-in goes. Every call it receives is kept, so a test can say what the server was told. */
    private class FakeJellyfin {
        @kotlin.concurrent.Volatile var enabled = true
        @kotlin.concurrent.Volatile var enabledReads = 0
        @kotlin.concurrent.Volatile var refuseInitiate = false
        /** viewer token → user id; only these authorise. */
        val viewerTokens = mutableMapOf<String, String>()
        val userNames = mutableMapOf<String, String>()
        val authorizeTokens = mutableListOf<String>()
        val initiateDeviceIds = mutableListOf<String>()
        private val secrets = mutableMapOf<String, Pair<String, String>>()   // secret → (code, DeviceId)
        private val authorized = mutableMapOf<String, String>()              // code → user id
        /** (DeviceId, user id) → the live token: a sign-in replaces only an earlier one for the same pair. */
        val live = mutableMapOf<Pair<String, String>, String>()
        private var n = 0

        private fun field(h: String?, name: String) = Regex("""$name="([^"]*)"""").find(h.orEmpty())?.groupValues?.get(1)

        fun install(app: io.ktor.server.application.Application) = with(app) {
            routing {
                get("/QuickConnect/Enabled") { enabledReads++; call.respondText(enabled.toString(), ContentType.Application.Json) }
                post("/QuickConnect/Initiate") {
                    if (refuseInitiate) return@post call.respond(HttpStatusCode.Unauthorized)
                    val deviceId = field(call.request.headers["Authorization"], "DeviceId") ?: return@post call.respond(HttpStatusCode.BadRequest)
                    initiateDeviceIds += deviceId
                    val secret = "secret-${++n}-$run"; val code = (100000 + n).toString()
                    secrets[secret] = code to deviceId
                    call.respondText("""{"Secret":"$secret","Code":"$code","Authenticated":false}""", ContentType.Application.Json)
                }
                post("/QuickConnect/Authorize") {
                    val token = field(call.request.headers["Authorization"], "Token") ?: ""
                    authorizeTokens += token
                    val user = viewerTokens[token] ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    authorized[call.request.queryParameters["code"].orEmpty()] = user
                    call.respondText("true", ContentType.Application.Json)
                }
                post("/Users/AuthenticateWithQuickConnect") {
                    val secret = Regex(""""Secret"\s*:\s*"([^"]*)"""").find(call.receiveText())?.groupValues?.get(1)
                    val (code, deviceId) = secrets[secret] ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    val user = authorized[code] ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    val token = "tv-token-${++n}-$run"
                    live[deviceId to user] = token
                    call.respondText(
                        """{"User":{"Id":"$user","Name":"${userNames[user]}","Policy":{"IsAdministrator":false}},"AccessToken":"$token"}""",
                        ContentType.Application.Json,
                    )
                }
            }
        }
    }

    private class Rig(
        val fake: FakeJellyfin, val devices: RaviloDeviceService, val service: TvSignInService, val client: HttpClient,
        val limits: TvSignInLimits, val loginLimiter: LoginRateLimiter, val store: ConfigStore,
    ) {
        var now = 1_700_000_000_000L
        val phones = mutableMapOf<String, String>()   // label → device token

        fun signIn(label: String, deviceId: String, user: String, jfToken: String, kind: String): String {
            fake.viewerTokens[jfToken] = user; fake.userNames[user] = user
            return devices.loginDevice(
                deviceId = deviceId, deviceName = label, jellyfinUserId = user, jellyfinUsername = user, jellyfinUserToken = jfToken,
                isAdmin = false, isKids = false, kind = kind,
            ).second.also { phones[label] = it }
        }

        suspend fun post(path: String, body: String, bearer: String? = null, ip: String? = null): HttpResponse = client.post(path) {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            ip?.let { header("X-Forwarded-For", it) }
            setBody(body)
        }

        suspend fun mint(deviceId: String, ip: String? = null) = post("/api/tv/signin/code", """{"deviceId":"$deviceId","deviceName":"Living room","platform":"tv"}""", ip = ip)
        suspend fun poll(code: String, secret: String, ip: String? = null) = post("/api/tv/signin/poll", """{"code":"$code","secret":"$secret"}""", ip = ip)
        suspend fun approve(code: String, bearer: String) = post("/api/tv/signin/approve", """{"code":"$code"}""", bearer)
        suspend fun preview(code: String, bearer: String) = client.get("/api/tv/signin/preview?code=$code") { header(HttpHeaders.Authorization, "Bearer $bearer") }
    }

    private fun obj(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    private fun rig(tag: String, body: suspend ApplicationTestBuilder.(Rig) -> Unit) = testApplication {
        val fake = FakeJellyfin()
        val jf = embeddedServer(CIO, port = 0, host = "127.0.0.1") { fake.install(this) }.start(wait = false)
        val base = "http://127.0.0.1:${jf.engine.resolvedConnectors().first().port}"
        val cfg = "/tmp/jellystructure-test-signin-$tag-$run.toml"
        val dbPath = "/tmp/jellystructure-test-signin-$tag-$run.db"
        cleanup += listOf(cfg, "$cfg.tmp", dbPath, "$dbPath-wal", "$dbPath-shm")
        runCatching { platform.posix.remove(dbPath) }
        val store = ConfigStore(cfg)
        runBlocking {
            store.update(store.current.copy(
                apiKeys = store.current.apiKeys.copy(jellyfinUrl = base, jellyfinToken = "machine-token-$run"),
                raviloWebUrl = "https://ravilo.example.org",
            ))
        }
        val db = createDatabase(dbPath)
        val devices = RaviloDeviceService(db)
        val sessions = SessionService(db)
        val limits = TvSignInLimits()
        val loginLimiter = LoginRateLimiter()   // the real /api/tv/login bucket: five a minute per IP
        lateinit var service: TvSignInService
        var clockNow = { 0L }
        service = TvSignInService(db, devices, JellyfinClient(), store, clock = { clockNow() })
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            installAuthPlugin(
                sessions,
                validateDeviceToken = { t, v, p -> devices.validateDeviceToken(t, v, p) },
                validateApiKey = { if (it == "api-key-$run") ApiKeyData("k1", "ha", "u-alice", "alice") else null },
            )
            routing {
                route("/api") {
                    tvSignInRoutes(service, limits)
                    // Stands in for /api/tv/login: the shared bucket the new routes must stay out of.
                    post("/tv/login") {
                        val key = LoginRateLimiter.clientKey(call.request.local.remoteHost, call.request.headers["X-Forwarded-For"])
                        if (loginLimiter.tryAcquire(key)) call.respondText("login") else call.respond(HttpStatusCode.TooManyRequests)
                    }
                }
            }
        }
        val r = Rig(fake, devices, service, client, limits, loginLimiter, store)
        clockNow = { r.now }
        try { body(r) } finally { runCatching { jf.stop(0, 0) } }
    }

    private fun mintedOf(r: HttpResponse, text: String) = obj(text).also { assertEquals(HttpStatusCode.OK, r.status, text) }

    // ── FR-2, FR-3: the whole cycle ────────────────────────────────────────────

    @Test fun `a code is approved by a signed-in phone and polled into a PairResult exactly once`() = rig("cycle") { r ->
        val phone = r.signIn("alice phone", "phone-1", "u-alice", "alice-jf-token", "phone")
        val minted = r.mint("tv-1"); val m = mintedOf(minted, minted.bodyAsText())
        val code = m["code"]!!.jsonPrimitive.content; val secret = m["secret"]!!.jsonPrimitive.content
        assertEquals(6, code.length)
        assertTrue(code.all { it in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" }, code)
        assertEquals(64, secret.length)
        assertEquals("https://ravilo.example.org/tv?c=$code", m["linkUrl"]!!.jsonPrimitive.content)
        assertEquals(r.now + 600_000L, m["expiresAt"]!!.jsonPrimitive.content.toLong())
        assertEquals("true", m["available"]!!.jsonPrimitive.content)

        // waiting → approving (a preview) → signed in (an approve) → collected once.
        val w = r.poll(code, secret)
        assertEquals(HttpStatusCode.Accepted, w.status)
        assertEquals("waiting", obj(w.bodyAsText())["state"]!!.jsonPrimitive.content)
        val pv = r.preview(code, phone)
        assertEquals(HttpStatusCode.OK, pv.status)
        assertEquals("Living room", obj(pv.bodyAsText())["deviceName"]!!.jsonPrimitive.content)
        val approving = obj(r.poll(code, secret).also { assertEquals(HttpStatusCode.Accepted, it.status) }.bodyAsText())
        assertEquals("approving", approving["state"]!!.jsonPrimitive.content)
        assertEquals("u-alice", approving["approverName"]!!.jsonPrimitive.content)

        assertEquals(HttpStatusCode.NoContent, r.approve(code, phone).status)
        val done = r.poll(code, secret)
        assertEquals(HttpStatusCode.OK, done.status)
        val pair = obj(done.bodyAsText())
        assertEquals("u-alice", pair["session"]!!.jsonObject["user_id"]!!.jsonPrimitive.content)
        assertEquals("tv-1", pair["session"]!!.jsonObject["device_id"]!!.jsonPrimitive.content)
        val deviceToken = pair["device_token"]!!.jsonPrimitive.content
        val tv = assertNotNull(r.devices.validateDeviceToken(deviceToken), "the TV's device token is valid")
        assertEquals("tv", tv.kind)
        assertEquals("u-alice", tv.jellyfinUserId)
        assertEquals(HttpStatusCode.Unauthorized, r.poll(code, secret).status, "a code signs a TV in exactly once")
    }

    @Test fun `the token that reaches the authorise step is the callers own never the machine token`() = rig("token") { r ->
        val phone = r.signIn("alice phone", "phone-1", "u-alice", "alice-jf-token", "phone")
        val m = obj(r.mint("tv-1").bodyAsText())
        assertEquals(HttpStatusCode.NoContent, r.approve(m["code"]!!.jsonPrimitive.content, phone).status)
        assertEquals(listOf("alice-jf-token"), r.fake.authorizeTokens)
        assertTrue(r.fake.authorizeTokens.none { it.startsWith("machine-token") }, "the machine token never reaches the authorise step")
    }

    @Test fun `the TV is signed in under the identity of a password sign-in so a second viewer evicts nobody`() = rig("two") { r ->
        val alice = r.signIn("alice phone", "phone-1", "u-alice", "alice-jf-token", "phone")
        val bob = r.signIn("bob phone", "phone-2", "u-bob", "bob-jf-token", "phone")
        suspend fun signTv(by: String) {
            val m = obj(r.mint("tv-1").bodyAsText())
            assertEquals(HttpStatusCode.NoContent, r.approve(m["code"]!!.jsonPrimitive.content, by).status)
        }
        signTv(alice); signTv(bob)
        assertEquals(listOf("ravilo-tv-1-u-alice", "ravilo-tv-1-u-bob"), r.fake.initiateDeviceIds)
        assertEquals(2, r.devices.listSessions("tv-1").size, "two viewers, two rows on one TV")
        val aliceTv = r.fake.live.getValue("ravilo-tv-1-u-alice" to "u-alice"); val bobTv = r.fake.live.getValue("ravilo-tv-1-u-bob" to "u-bob")
        assertNotEquals(aliceTv, bobTv)
        assertEquals(2, r.fake.live.size, "the second viewer's sign-in replaced nothing")
        signTv(alice)   // the same viewer again replaces only their own earlier token
        assertEquals(2, r.fake.live.size)
        assertNotEquals(aliceTv, r.fake.live.getValue("ravilo-tv-1-u-alice" to "u-alice"))
        assertEquals(bobTv, r.fake.live.getValue("ravilo-tv-1-u-bob" to "u-bob"))
    }

    // ── FR-3: who may approve ──────────────────────────────────────────────────

    @Test fun `a cast row and a screen row are refused with 403 and reach nothing`() = rig("borrow") { r ->
        val cast = r.signIn("cast", "recv-1", "u-alice", "alice-jf-token", "cast")
        val screen = r.signIn("screen", "screen-1", "u-alice", "alice-jf-token", "screen")
        val m = obj(r.mint("tv-1").bodyAsText()); val code = m["code"]!!.jsonPrimitive.content
        for (token in listOf(cast, screen)) {
            assertEquals(HttpStatusCode.Forbidden, r.approve(code, token).status)
            assertEquals(HttpStatusCode.Forbidden, r.preview(code, token).status)
        }
        assertTrue(r.fake.authorizeTokens.isEmpty())
        assertEquals(HttpStatusCode.Accepted, r.poll(code, m["secret"]!!.jsonPrimitive.content).status, "the code is untouched")
    }

    @Test fun `an API key and the machine token are refused and never reach the authorise step`() = rig("apikey") { r ->
        r.signIn("alice phone", "phone-1", "u-alice", "alice-jf-token", "phone")
        val code = obj(r.mint("tv-1").bodyAsText())["code"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.Forbidden, r.approve(code, "api-key-$run").status)
        assertEquals(HttpStatusCode.Unauthorized, r.approve(code, "machine-token-$run").status)
        assertEquals(HttpStatusCode.Unauthorized, r.post("/api/tv/signin/approve", """{"code":"$code"}""").status, "no credential at all")
        assertTrue(r.fake.authorizeTokens.isEmpty())
    }

    // ── FR-2: mint ──────────────────────────────────────────────────────────────

    @Test fun `a second mint from the same device kills the first code`() = rig("remint") { r ->
        val phone = r.signIn("alice phone", "phone-1", "u-alice", "alice-jf-token", "phone")
        val a = obj(r.mint("tv-1").bodyAsText()); val b = obj(r.mint("tv-1").bodyAsText())
        val ca = a["code"]!!.jsonPrimitive.content; val cb = b["code"]!!.jsonPrimitive.content
        assertNotEquals(ca, cb)
        assertEquals(HttpStatusCode.Unauthorized, r.poll(ca, a["secret"]!!.jsonPrimitive.content).status)
        assertEquals(HttpStatusCode.NotFound, r.approve(ca, phone).status)
        assertEquals(HttpStatusCode.Accepted, r.poll(cb, b["secret"]!!.jsonPrimitive.content).status)
        val other = obj(r.mint("tv-2").bodyAsText())   // another device's code is its own
        assertEquals(HttpStatusCode.Accepted, r.poll(cb, b["secret"]!!.jsonPrimitive.content).status)
        assertNotNull(other["code"])
    }

    @Test fun `a wrong secret and an unknown code are 401 and a used code cannot be approved twice`() = rig("used") { r ->
        val phone = r.signIn("alice phone", "phone-1", "u-alice", "alice-jf-token", "phone")
        val m = obj(r.mint("tv-1").bodyAsText()); val code = m["code"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.Unauthorized, r.poll(code, "0".repeat(64)).status)
        assertEquals(HttpStatusCode.Unauthorized, r.poll("ZZZZZZ", m["secret"]!!.jsonPrimitive.content).status)
        assertEquals(HttpStatusCode.NoContent, r.approve(code, phone).status)
        assertEquals(HttpStatusCode.Conflict, r.approve(code, phone).status, "already used")
        assertEquals(1, r.fake.initiateDeviceIds.size, "the second approval ran no second cycle")
    }

    @Test fun `an expired code is 404 on approve and 401 on poll`() = rig("expired") { r ->
        val phone = r.signIn("alice phone", "phone-1", "u-alice", "alice-jf-token", "phone")
        val m = obj(r.mint("tv-1").bodyAsText()); val code = m["code"]!!.jsonPrimitive.content
        r.now += 10 * 60_000L + 1
        assertEquals(HttpStatusCode.NotFound, r.approve(code, phone).status)
        assertEquals(HttpStatusCode.NotFound, r.preview(code, phone).status)
        assertEquals(HttpStatusCode.Unauthorized, r.poll(code, m["secret"]!!.jsonPrimitive.content).status)
    }

    @Test fun `QuickConnect disabled gives available false with no code and the read is cached for a minute`() = rig("disabled") { r ->
        r.fake.enabled = false
        val first = r.mint("tv-1")
        assertEquals(HttpStatusCode.OK, first.status)
        val body = obj(first.bodyAsText())
        assertEquals("false", body["available"]!!.jsonPrimitive.content)
        assertNull(body["code"]); assertNull(body["secret"]); assertNull(body["linkUrl"])
        r.fake.enabled = true
        assertEquals("false", obj(r.mint("tv-1").bodyAsText())["available"]!!.jsonPrimitive.content, "still the cached answer")
        assertEquals(1, r.fake.enabledReads)
        r.now += 61_000L
        assertEquals("true", obj(r.mint("tv-1").bodyAsText())["available"]!!.jsonPrimitive.content)
        assertEquals(2, r.fake.enabledReads)
    }

    @Test fun `with no Ravilo Web address the link is absent`() = rig("nolink") { r ->
        r.store.update(r.store.current.copy(raviloWebUrl = ""))
        val m = obj(r.mint("tv-1").bodyAsText())
        assertNull(m["linkUrl"])
        assertNotNull(m["code"])
    }

    // ── FR-3: unavailable ───────────────────────────────────────────────────────

    @Test fun `a refused initiate or a dead viewer token is 503 unavailable and signs nobody in`() = rig("unavailable") { r ->
        val phone = r.signIn("alice phone", "phone-1", "u-alice", "alice-jf-token", "phone")
        val code = obj(r.mint("tv-1").bodyAsText())["code"]!!.jsonPrimitive.content
        r.fake.refuseInitiate = true
        val refused = r.approve(code, phone)
        assertEquals(HttpStatusCode.ServiceUnavailable, refused.status)
        assertEquals("unavailable", obj(refused.bodyAsText())["reason"]!!.jsonPrimitive.content)
        r.fake.refuseInitiate = false
        r.fake.viewerTokens.clear()   // the viewer's own sign-in has been revoked on the server
        assertEquals(HttpStatusCode.ServiceUnavailable, r.approve(code, phone).status)
        assertTrue(r.devices.listSessions("tv-1").isEmpty())
    }

    // ── FR-16: buckets ──────────────────────────────────────────────────────────

    @Test fun `the mint poll and approve buckets are their own and stay out of the login bucket`() = rig("buckets") { r ->
        val phone = r.signIn("alice phone", "phone-1", "u-alice", "alice-jf-token", "phone")
        // Mint: ten a minute per IP.
        repeat(10) { assertEquals(HttpStatusCode.OK, r.mint("tv-$it", ip = "10.0.0.1").status) }
        assertEquals(HttpStatusCode.TooManyRequests, r.mint("tv-x", ip = "10.0.0.1").status)
        // Poll: forty a minute per IP.
        repeat(40) { assertEquals(HttpStatusCode.Unauthorized, r.poll("ZZZZZZ", "s", ip = "10.0.0.2").status) }
        assertEquals(HttpStatusCode.TooManyRequests, r.poll("ZZZZZZ", "s", ip = "10.0.0.2").status)
        // Approve: five a minute per approving device.
        repeat(5) { assertEquals(HttpStatusCode.NotFound, r.approve("ZZZZZZ", phone).status) }
        assertEquals(HttpStatusCode.TooManyRequests, r.approve("ZZZZZZ", phone).status)
        // Login's bucket was not touched by any of it, from the same addresses.
        repeat(5) { assertEquals(HttpStatusCode.OK, r.post("/api/tv/login", "{}", ip = "10.0.0.1").status) }
        // ...and spending it does not touch theirs.
        assertEquals(HttpStatusCode.TooManyRequests, r.post("/api/tv/login", "{}", ip = "10.0.0.1").status)
        assertEquals(HttpStatusCode.TooManyRequests, r.mint("tv-y", ip = "10.0.0.1").status, "still spent: its own window")
        assertEquals(HttpStatusCode.OK, r.mint("tv-y", ip = "10.0.0.3").status, "a different address is untouched")
        // A different IP has a full login allowance while the first one's mint bucket is spent: independent keys, independent limiters.
        assertEquals(HttpStatusCode.OK, r.post("/api/tv/login", "{}", ip = "10.0.0.3").status)
    }

    // ── FR-15: no word that names the server software ───────────────────────────

    @Test fun `no error body names the server software or a status code`() = rig("words") { r ->
        val phone = r.signIn("alice phone", "phone-1", "u-alice", "alice-jf-token", "phone")
        val cast = r.signIn("cast", "recv-1", "u-alice", "alice-jf-token", "cast")
        val m = obj(r.mint("tv-1").bodyAsText()); val code = m["code"]!!.jsonPrimitive.content
        val bodies = mutableListOf<Pair<HttpStatusCode, String>>()
        suspend fun keep(res: HttpResponse) { if (res.status.value >= 400) bodies += res.status to res.bodyAsText() }
        keep(r.poll(code, "bad")); keep(r.poll("ZZZZZZ", "bad"))
        keep(r.post("/api/tv/signin/poll", "not json")); keep(r.post("/api/tv/signin/code", "{}"))
        keep(r.approve(code, cast)); keep(r.preview(code, cast)); keep(r.approve("ZZZZZZ", phone)); keep(r.preview("ZZZZZZ", phone))
        keep(r.approve(code, "api-key-$run")); keep(r.post("/api/tv/signin/approve", "nonsense", phone))
        r.fake.refuseInitiate = true; keep(r.approve(code, phone)); r.fake.refuseInitiate = false
        assertEquals(HttpStatusCode.NoContent, r.approve(code, phone).status); keep(r.approve(code, phone))
        repeat(6) { keep(r.approve("ZZZZZZ", phone)) }   // the 429
        assertTrue(bodies.map { it.first }.containsAll(listOf(
            HttpStatusCode.Unauthorized, HttpStatusCode.NotFound, HttpStatusCode.Forbidden, HttpStatusCode.Conflict, HttpStatusCode.ServiceUnavailable,
            HttpStatusCode.TooManyRequests, HttpStatusCode.BadRequest,
        )), "every kind of refusal was exercised: ${bodies.map { it.first.value }.toSet()}")
        val banned = Regex("""jellyfin|ldap|quick\s*connect|quickconnect|plugin|sso|\b[1-5]\d\d\b""", RegexOption.IGNORE_CASE)
        for ((status, text) in bodies) assertFalse(banned.containsMatchIn(text), "${status.value} body names something it must not: $text")
    }
}
