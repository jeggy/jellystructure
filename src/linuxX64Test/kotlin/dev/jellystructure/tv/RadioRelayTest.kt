package dev.jellystructure.tv

import dev.jellystructure.OutboundHttp
import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.DeviceIdentityRegistry
import dev.jellystructure.auth.DeviceKey
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.server.routes.liveTvRoutes
import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.shared.tv.LiveTvStreamTicket
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2656 (DEB-2628, FR-4, FR-5). A fake Jellyfin lists one station and one TV channel and opens whatever `Path` the test
 * names; a second fake server is the broadcaster. The real [LiveTvService] mints the relay, and the real route serves it over
 * a loopback socket, so the bytes, the headers, the limits and the lifetime are read off a real connection.
 */
class RadioRelayTest {
    private val run = getpid().toString()
    private val dir = "/tmp/jellystructure-test-radiorelay-$run"
    private val wire = Json { ignoreUnknownKeys = true }

    /** What the broadcaster saw: one entry per request, its headers lower-cased. */
    private class Seen { val requests = mutableListOf<Pair<String, Map<String, String>>>() }

    private var openPath = "/up/finite"
    private var lsSeq = 0
    private val closes = mutableListOf<String>()
    private val seen = Seen()
    private val clients = mutableListOf<Job>()

    @AfterTest fun tearDown() {
        for (f in listOf("$dir.toml", "$dir-store.json")) runCatching { platform.posix.remove(f) }
        DeviceIdentityRegistry.clear()
    }

    private fun device(id: String, user: String = "u1", name: String = "Phone", platform: String? = null, kind: String = "phone") = DeviceData(
        deviceId = "dev-$id-$run", deviceToken = "dt-$id-$run", jellyfinUserId = user, jellyfinUsername = "Anna",
        jellyfinUserToken = "tok-$id", isAdmin = false, displayName = name, kind = kind, platform = platform ?: "android-phone",
    )

    private class Harness(val service: LiveTvService, val appBase: String, val upBase: String, val scope: CoroutineScope, val restart: suspend () -> LiveTvService)

    private fun harness(body: suspend Harness.() -> Unit) = runBlocking {
        DeviceIdentityRegistry.clear(); closes.clear(); seen.requests.clear(); openPath = "/up/finite"
        val broadcaster = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                fun io.ktor.server.application.ApplicationCall.note() {
                    seen.requests += request.local.uri to request.headers.entries().associate { it.key.lowercase() to it.value.joinToString(",") }
                }
                get("/up/finite") {
                    call.note()
                    call.response.header("icy-br", "192")
                    call.respondBytesWriter(ContentType("audio", "mpeg")) { writeFully("RADIO-BYTES-0123456789".encodeToByteArray()) }
                }
                get("/up/endless") {
                    call.note()
                    call.response.header("icy-br", "192")
                    call.respondBytesWriter(ContentType("audio", "mpeg")) {
                        val chunk = ByteArray(512) { 'a'.code.toByte() }
                        while (true) { writeFully(chunk); flush(); delay(20) }
                    }
                }
                get("/up/html") { call.note(); call.respondText("<html>not audio</html>", ContentType.Text.Html) }
                get("/up/hop/{n}") {
                    call.note()
                    val n = call.parameters["n"]!!.toInt()
                    call.response.header(HttpHeaders.Location, if (n == 0) "/up/finite" else "/up/hop/${n - 1}")
                    // An empty body with a stated length: a redirect with neither leaves the client's call open after its handler returns.
                    call.respondText("", status = HttpStatusCode.Found)
                }
            }
        }.start(wait = false)
        val upBase = "http://127.0.0.1:${broadcaster.engine.resolvedConnectors().first().port}"
        val jellyfin = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/Users/{id}") { call.respondText("{}", ContentType.Application.Json) }
                get("/LiveTv/Channels") {
                    call.respondText(
                        """{"Items":[{"Id":"r1","Name":"Station One","ChannelType":"Radio","CurrentProgram":{"Name":"Morning Show","StartDate":"2000-01-01T00:00:00Z","EndDate":"2999-01-01T00:00:00Z"}},""" +
                            """{"Id":"tv1","Name":"News 1","ChannelType":"TV"}]}""", ContentType.Application.Json,
                    )
                }
                post("/Items/{id}/PlaybackInfo") { call.respondText("""{"PlaySessionId":"ps1","MediaSources":[{"Id":"ms1","OpenToken":"open1"}]}""", ContentType.Application.Json) }
                post("/LiveStreams/Open") {
                    val path = if (openPath.startsWith("/up/")) "$upBase$openPath" else openPath
                    call.respondText("""{"MediaSource":{"Id":"ms1","Path":"$path","LiveStreamId":"ls-${++lsSeq}"}}""", ContentType.Application.Json)
                }
                post("/LiveStreams/Close") { closes += Regex("\"LiveStreamId\":\"([^\"]+)\"").find(call.receiveText())?.groupValues?.get(1).orEmpty(); call.respond(HttpStatusCode.NoContent) }
            }
        }.start(wait = false)
        val jfBase = "http://127.0.0.1:${jellyfin.engine.resolvedConnectors().first().port}"
        val configStore = ConfigStore("$dir.toml")
        configStore.update(configStore.current.copy(apiKeys = configStore.current.apiKeys.copy(jellyfinUrl = jfBase, jellyfinToken = "server-key")))
        val service = LiveTvService(dir, LiveTvStore("$dir-store.json"), JellyfinClient(), configStore)
        val app = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing {
                route("/api") {
                    intercept(ApplicationCallPipeline.Plugins) { call.attributes.put(DeviceKey, device("route")) }
                    liveTvRoutes(service)
                }
            }
        }.start(wait = false)
        val appBase = "http://127.0.0.1:${app.engine.resolvedConnectors().first().port}"
        try {
            service.sync(); service.bulkShowNew()
            Harness(service, appBase, upBase, this) {
                ConfigStore("$dir.toml").also { it.load() }.let { cs -> LiveTvService(dir, LiveTvStore("$dir-store.json"), JellyfinClient(), cs) }.also { it.sync(); it.bulkShowNew() }
            }.body()
        } finally {
            clients.forEach { it.cancel() }; clients.clear()
            app.stop(100, 500); jellyfin.stop(100, 500); broadcaster.stop(100, 500)
        }
    }

    private suspend fun Harness.tune(d: DeviceData, channel: String = "r1"): LiveTvStreamTicket =
        assertNotNull(service.tune(d, channel, ClientCapabilities()), "the tune opened")

    private suspend fun eventually(what: String, ms: Long = 6_000, check: () -> Boolean) {
        var waited = 0L
        while (!check()) { if (waited >= ms) error("never became true: $what"); delay(50); waited += 50 }
    }

    /** Opens a relay connection and keeps it open until cancelled; completes with the status once the headers are in. */
    private fun Harness.hold(url: String): Pair<Job, CompletableDeferred<Int>> {
        val status = CompletableDeferred<Int>()
        val job = scope.launch {
            try {
                OutboundHttp.client.prepareGet(url) { timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS; socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS } }.execute { r ->
                    status.complete(r.status.value)
                    if (r.status.value == 200) { val ch = r.bodyAsChannel(); val b = ByteArray(1024); while (ch.readAvailable(b, 0, b.size) >= 0) { /* listening */ } }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { status.complete(-1) }
        }
        clients += job
        return job to status
    }

    /** A GET that gives up after 15 s: a relay that never answers must fail one test, not hold the run for the client's two minutes. */
    private suspend fun fetch(url: String) = OutboundHttp.client.get(url) { timeout { requestTimeoutMillis = 15_000 } }

    private fun Harness.url(ticket: LiveTvStreamTicket) = appBase + ticket.hlsUrl

    @Test fun `a station tune is audio with its type a relay path its kind and a row that says relayed`() = harness {
        val ticket = tune(device("a"))
        assertEquals("audio", ticket.streamKind)
        assertEquals("audio/mpeg", ticket.contentType)
        assertEquals("radio", ticket.channelKind)
        assertTrue(Regex("/api/tv/livetv/radio/[0-9a-f]{32}").matches(ticket.hlsUrl), "a path against the client's own server base, 128 bits of hex: ${ticket.hlsUrl.take(40)}")
        val row = service.adminRows().single()
        assertEquals("radio", row.channelKind)
        assertEquals("Station One", row.title)
        assertEquals("Morning Show", row.subtitle)
        assertEquals("MP3 · 192 kb/s", row.format)
        assertEquals("relayed", row.encoder)
    }

    @Test fun `every tune mints its own id`() = harness {
        val a = tune(device("a")); val b = tune(device("b"))
        assertTrue(a.hlsUrl != b.hlsUrl)
        val d = device("c"); val first = tune(d); val second = tune(d)
        assertTrue(first.hlsUrl != second.hlsUrl)
    }

    @Test fun `bytes pass through with the upstream type no length no store and any origin`() = harness {
        val ticket = tune(device("a"))
        val resp = OutboundHttp.client.get(url(ticket))
        assertEquals(200, resp.status.value)
        assertEquals("RADIO-BYTES-0123456789", resp.bodyAsText())
        assertEquals("audio/mpeg", resp.headers[HttpHeaders.ContentType]?.substringBefore(';'))
        assertEquals("no-store", resp.headers[HttpHeaders.CacheControl])
        assertEquals("*", resp.headers[HttpHeaders.AccessControlAllowOrigin])
        assertNull(resp.headers[HttpHeaders.ContentLength], "a live stream states no length")
    }

    @Test fun `the broadcaster is never sent Icy-MetaData nor Jellyfin's token`() = harness {
        val ticket = tune(device("a"))
        OutboundHttp.client.get(url(ticket)).readRawBytes()
        val relayed = seen.requests.last().second
        assertFalse("icy-metadata" in relayed, "no in-band metadata is asked for: $relayed")
        assertFalse("authorization" in relayed, "Jellyfin's token goes to Jellyfin only")
    }

    @Test fun `a non-audio upstream is a 502 and the ticket carries no type`() = harness {
        openPath = "/up/html"
        val ticket = tune(device("a"))
        assertEquals("audio", ticket.streamKind)
        assertNull(ticket.contentType)
        assertEquals(502, OutboundHttp.client.get(url(ticket)).status.value)
    }

    @Test fun `up to three redirects are followed`() = harness {
        openPath = "/up/hop/2"
        assertEquals("RADIO-BYTES-0123456789", fetch(url(tune(device("a")))).bodyAsText())
    }

    @Test fun `a fourth redirect is a 502`() = harness {
        openPath = "/up/hop/3"
        assertEquals(502, fetch(url(tune(device("b")))).status.value)
    }

    @Test fun `an upstream that cannot be reached is a 502`() = harness {
        openPath = "http://127.0.0.1:1/nothing"
        assertEquals(502, OutboundHttp.client.get(url(tune(device("a")))).status.value)
    }

    @Test fun `an unknown a stopped and a re-tuned id are 404`() = harness {
        assertEquals(404, OutboundHttp.client.get("$appBase/api/tv/livetv/radio/${"0".repeat(32)}").status.value)
        assertEquals(404, OutboundHttp.client.get("$appBase/api/tv/livetv/radio/short").status.value)
        val d = device("a")
        val old = tune(d)
        val current = tune(d)
        assertEquals(404, OutboundHttp.client.get(url(old)).status.value, "a re-tune ends the old id")
        assertEquals(200, OutboundHttp.client.get(url(current)).status.value)
        service.stopTune(d, current.liveStreamId)
        assertEquals(404, OutboundHttp.client.get(url(current)).status.value, "a stop ends the id")
    }

    @Test fun `a third connection on one id is 503 and a closed one makes room`() = harness {
        openPath = "/up/endless"
        val ticket = tune(device("a"))
        val (first, s1) = hold(url(ticket)); val (_, s2) = hold(url(ticket))
        assertEquals(200, s1.await()); assertEquals(200, s2.await())
        assertEquals(503, OutboundHttp.client.get(url(ticket)).status.value)
        first.cancel()
        var admitted = false
        var tries = 0
        while (!admitted && tries++ < 100) {
            val (_, again) = hold(url(ticket))
            if (again.await() == 200) admitted = true else delay(50)
        }
        assertTrue(admitted, "a connection that ended makes room for another")
    }

    @Test fun `the thirty-third connection on the server is 503`() = harness {
        openPath = "/up/endless"
        val statuses = mutableListOf<CompletableDeferred<Int>>()
        for (i in 0 until 16) {
            val ticket = tune(device("d$i"))
            repeat(2) { statuses += hold(url(ticket)).second }
        }
        for (s in statuses) assertEquals(200, s.await())
        assertEquals(503, OutboundHttp.client.get(url(tune(device("late")))).status.value, "32 are open; the 33rd is refused")
    }

    @Test fun `an open connection holds off the watchdog and it closes sixty seconds after the last one ends`() = harness {
        openPath = "/up/endless"
        var now = 1_000_000_000L
        service.clock = { now }
        val d = device("a")
        val ticket = tune(d)
        val (conn, status) = hold(url(ticket))
        assertEquals(200, status.await())

        now += 10 * 60_000L   // ten minutes with no heartbeat and no events socket
        service.stopWatchdogTick { false }
        assertTrue(service.anyLiveActive(), "a listener keeps the tune alive")
        assertTrue(closes.isEmpty())

        conn.cancel()
        eventually("the connection is given back") { service.relayOpenCount(ticket.hlsUrl.substringAfterLast('/')) == 0 }
        now += 59_000L
        service.stopWatchdogTick { true }
        assertTrue(service.anyLiveActive(), "59 s after the last connection ended is too soon")
        now += 2_000L
        service.stopWatchdogTick { true }
        assertFalse(service.anyLiveActive(), "61 s is closed")
        assertEquals(listOf(ticket.liveStreamId), closes)
        assertEquals(404, OutboundHttp.client.get(url(ticket)).status.value, "a closed tune's id answers 404")
    }

    @Test fun `a heartbeat keeps a relayed tune with no connection alive`() = harness {
        var now = 1_000_000_000L
        service.clock = { now }
        val d = device("a")
        val ticket = tune(d)
        now += 50_000L; service.heartbeat(d)
        now += 50_000L
        service.stopWatchdogTick { true }
        assertTrue(service.anyLiveActive())
    }

    @Test fun `a heartbeat after a restart re-registers a station as radio and the watchdog leaves it`() = harness {
        val d = device("a")
        val ticket = tune(d)
        val after = restart()
        assertFalse(after.anyLiveActive(), "a restart empties the table")
        after.heartbeat(d, dev.jellystructure.shared.tv.LiveTvHeartbeatRequest(channelId = "r1", liveStreamId = ticket.liveStreamId, delivery = "source"))
        val row = after.adminRows().single()
        assertEquals("radio", row.channelKind)
        assertEquals("Station One", row.title)
        after.stopWatchdogTick { true }
        assertTrue(after.anyLiveActive())
    }

    @Test fun `a speaker's tune is its own and a phone stopping does not end it`() = harness {
        openPath = "/up/endless"
        val phone = device("phone")
        val speaker = device("spk", name = "Stue speaker", platform = "cast-audio", kind = "cast")
        val onPhone = tune(phone); val onSpeaker = tune(speaker)
        val rows = service.adminRows().sortedBy { it.targetName }
        assertEquals(2, rows.size, "one stream per device: the two do not replace each other")
        assertEquals("speaker", rows.single { it.targetName == "Stue speaker" }.targetIcon)
        assertEquals("Station One", rows.single { it.targetName == "Stue speaker" }.title)
        val (_, status) = hold(url(onSpeaker))
        assertEquals(200, status.await())
        service.stopTune(phone, onPhone.liveStreamId)
        assertEquals(404, OutboundHttp.client.get(url(onPhone)).status.value)
        assertTrue(service.relayStillLive(RelayLease(speaker.deviceId, onSpeaker.liveStreamId, onSpeaker.hlsUrl.substringAfterLast('/'), "", "", null)), "the speaker's tune is untouched")
    }

    @Test fun `redactedPath keeps the path only up to radio`() {
        assertEquals("/api/tv/livetv/radio/…", redactedPath("/api/tv/livetv/radio/0123456789abcdef0123456789abcdef"))
        assertEquals("/api/tv/livetv/logo/c1", redactedPath("/api/tv/livetv/logo/c1"))
    }

    @Test fun `format labels`() {
        assertEquals("MP3 · 192 kb/s", radioFormatLabel("audio/mpeg", 192))
        assertEquals("AAC", radioFormatLabel("audio/aacp", null))
        assertEquals("Ogg · 96 kb/s", radioFormatLabel("application/ogg; charset=x", 96))
        assertNull(radioFormatLabel(null, 128))
    }

    @Test fun `only audio and ogg are relayable`() {
        assertTrue(isRelayableAudio("audio/mpeg")); assertTrue(isRelayableAudio("Audio/AAC; x=y")); assertTrue(isRelayableAudio("application/ogg"))
        assertFalse(isRelayableAudio("text/html")); assertFalse(isRelayableAudio("application/vnd.apple.mpegurl")); assertFalse(isRelayableAudio(null))
    }
}
