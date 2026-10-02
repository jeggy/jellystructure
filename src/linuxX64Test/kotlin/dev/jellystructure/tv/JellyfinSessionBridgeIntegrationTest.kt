package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.DeviceIdentityRegistry
import dev.jellystructure.auth.JellyfinDeviceIdentity
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import io.ktor.server.websocket.DefaultWebSocketServerSession
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Phase 299 — the whole chain, in process, over the loopback: a fake Jellyfin (`/socket`, the capabilities route,
 * `/Sessions`, the token check) on a real Ktor CIO server, the real [JellyfinSessionBridge] and [TvEventBus], and one
 * real events socket per device kind — phone, desktop, web, TV, Cast receiver, and an app older than R354.
 *
 * It asserts what the dashboard depends on: each device registers what it declared (FR-299-1/-2), every Playstate and
 * volume command sent on ONE device's Jellyfin socket reaches that device and no other (FR-299-3/-4), a closed device's
 * bridge ends its Jellyfin socket after the grace (FR-299-5), and the sweep ends a session no socket holds (FR-299-6).
 */
class JellyfinSessionBridgeIntegrationTest {

    /** What the fake Jellyfin saw: socket opens/closes and capability posts, by Jellyfin DeviceId. */
    private class FakeJellyfin {
        val lock = Mutex()
        val log = mutableListOf<String>()
        val caps = mutableMapOf<String, String>()
        val sockets = mutableMapOf<String, DefaultWebSocketServerSession>()
        var sessionsBody = "[]"
        suspend fun record(line: String) = lock.withLock { log += line }
        suspend fun has(line: String) = lock.withLock { line in log }
        suspend fun count(line: String) = lock.withLock { log.count { it == line } }
    }

    private class DeviceSocket(val device: DeviceData) {
        val lock = Mutex()
        val received = mutableListOf<String>()
        suspend fun snapshot() = lock.withLock { received.toList() }
    }

    private suspend fun waitFor(what: String, timeoutMs: Long = 15_000, check: suspend () -> Boolean) {
        val ok = runCatching { withTimeout(timeoutMs) { while (!check()) delay(25) } }.isSuccess
        if (!ok) fail("timed out waiting for: $what")
    }

    private fun device(id: String, kind: String) = DeviceData(
        deviceId = id, deviceToken = "dt-$id", jellyfinUserId = "user-1", jellyfinUsername = "viewer",
        jellyfinUserToken = "jf-token-1", isAdmin = false, displayName = "Test $kind", kind = kind,
    )

    private val appCommands = REMOTE_COMMANDS
    private val receiverCommands = listOf("PlayState", "SetVolume", "VolumeUp", "VolumeDown", "Mute", "Unmute", "ToggleMute")

    @Test
    fun `dashboard commands reach the right device of every kind and sessions end`() = runBlocking {
        DeviceIdentityRegistry.clear()
        val fake = FakeJellyfin()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bus = TvEventBus(scope)

        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                // Jellyfin's token check (PlaybackService.pairedTokenCheck → GET /Users/{id}).
                get("/Users/{id}") { call.respondText("{}", ContentType.Application.Json) }
                post("/Sessions/Capabilities/Full") {
                    val id = Regex("DeviceId=\"([^\"]+)\"").find(call.request.headers["Authorization"].orEmpty())?.groupValues?.get(1) ?: "?"
                    val body = call.receiveText()
                    fake.lock.withLock { fake.caps[id] = body; fake.log += "caps:$id" }
                    call.respond(HttpStatusCode.NoContent)
                }
                get("/Sessions") { call.respondText(fake.lock.withLock { fake.sessionsBody }, ContentType.Application.Json) }
                webSocket("/socket") {
                    val id = call.request.queryParameters["deviceId"] ?: "?"
                    // The handshake must carry the credential as a header, never as a query parameter (phase 238).
                    if (call.request.headers["Authorization"]?.contains("Token=") != true) { fake.record("noauth:$id"); return@webSocket }
                    fake.lock.withLock { fake.sockets[id] = this; fake.log += "open:$id" }
                    send(Frame.Text("""{"MessageType":"ForceKeepAlive","Data":60}"""))
                    try { for (f in incoming) Unit } finally {
                        fake.lock.withLock { if (fake.sockets[id] === this) fake.sockets.remove(id); fake.log += "close:$id" }
                    }
                }
                // Stand-in for /api/tv/events: registers the socket on the real bus, as Server.kt does.
                webSocket("/device-events") {
                    val user = call.request.queryParameters["user"]!!
                    val dev = call.request.queryParameters["device"]!!
                    bus.tryRegister(user, dev, this)
                    try { for (f in incoming) Unit } finally { bus.unregister(user, dev, this) }
                }
            }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val base = "http://127.0.0.1:$port"

        val cfgPath = "/tmp/jellystructure-test-bridge296-${getpid()}.toml"
        val configStore = ConfigStore(cfgPath)
        configStore.update(configStore.current.copy(apiKeys = configStore.current.apiKeys.copy(jellyfinUrl = base, jellyfinToken = "server-token")))
        val db = createDatabase("/tmp/jellystructure-test-bridge296-${getpid()}.db")
        val mediaStore = MediaStore(db, JsTagStore("/tmp/jellystructure-test-bridge296-${getpid()}-tags.json"), configStore)
        val bridge = JellyfinSessionBridge(configStore, bus, scope, mediaStore, graceMs = 400)
        val client = HttpClient(Curl) { install(io.ktor.client.plugins.websocket.WebSockets) }

        val phone = device("phone-1", "phone")
        val desktop = device("desktop-1", "desktop")
        val web = device("web-1", "web")
        val tv = device("tv-1", "tv")
        val receiver = device("cast-0123456789", "cast")
        val oldApp = device("old-1", "phone")
        val declarations = mapOf(phone to appCommands, desktop to appCommands, web to appCommands, tv to appCommands, receiver to receiverCommands, oldApp to null)
        val sockets = declarations.keys.associateWith { DeviceSocket(it) }
        val jfId = { d: DeviceData -> JellyfinDeviceIdentity.forDevice(d).deviceId }

        try {
            // Each device opens its events socket; the bridge starts with what it declared (Server.kt's two lines).
            for ((d, sock) in sockets) {
                scope.launch {
                    client.webSocket("ws://127.0.0.1:$port/device-events?user=${d.jellyfinUserId}&device=${d.deviceId}") {
                        for (f in incoming) if (f is Frame.Text) { val t = f.readText(); sock.lock.withLock { sock.received += t } }
                    }
                }
                waitFor("${d.kind} events socket registered") { bus.isConnected(d.deviceId) }
                bridge.connect(d, declarations[d])
            }
            for (d in sockets.keys) waitFor("${d.kind} bridge open and registered") { fake.has("open:${jfId(d)}") && fake.has("caps:${jfId(d)}") }

            // FR-299-1/-2 — capabilities as declared; an old app keeps phase 110's bytes.
            val caps = fake.lock.withLock { fake.caps.toMap() }
            for (d in listOf(phone, desktop, web, tv)) assertEquals(capabilitiesBody(appCommands), caps[jfId(d)], "${d.kind} capabilities")
            assertEquals("""{"PlayableMediaTypes":[],"SupportedCommands":["PlayState","SetVolume","VolumeUp","VolumeDown","Mute","Unmute","ToggleMute"],"SupportsMediaControl":true}""", caps[jfId(receiver)])
            assertEquals(LEGACY_CAPABILITIES, caps[jfId(oldApp)], "an app older than R354 registers what it always did")

            // FR-299-3/-4 — every command, on each device's own Jellyfin socket, to that device alone.
            val frames = listOf(
                """{"MessageType":"Playstate","Data":{"Command":"Pause","ControllingUserId":"u"}}""" to """{"type":"playstate_command","command":"Pause"}""",
                """{"MessageType":"Playstate","Data":{"Command":"Unpause","ControllingUserId":"u"}}""" to """{"type":"playstate_command","command":"Unpause"}""",
                """{"MessageType":"Playstate","Data":{"Command":"PlayPause","ControllingUserId":"u"}}""" to """{"type":"playstate_command","command":"PlayPause"}""",
                """{"MessageType":"Playstate","Data":{"Command":"Seek","SeekPositionTicks":1234560000,"ControllingUserId":"u"}}""" to """{"type":"playstate_command","command":"Seek","seek_position_ms":123456}""",
                """{"MessageType":"Playstate","Data":{"Command":"NextTrack","ControllingUserId":"u"}}""" to """{"type":"playstate_command","command":"NextTrack"}""",
                """{"MessageType":"Playstate","Data":{"Command":"PreviousTrack","ControllingUserId":"u"}}""" to """{"type":"playstate_command","command":"PreviousTrack"}""",
                """{"MessageType":"Playstate","Data":{"Command":"Rewind","ControllingUserId":"u"}}""" to """{"type":"playstate_command","command":"Rewind"}""",
                """{"MessageType":"Playstate","Data":{"Command":"FastForward","ControllingUserId":"u"}}""" to """{"type":"playstate_command","command":"FastForward"}""",
                """{"MessageType":"GeneralCommand","Data":{"Name":"SetVolume","Arguments":{"Volume":"35"}}}""" to """{"type":"player_command","command":"set_volume","args":{"volume":35}}""",
                """{"MessageType":"GeneralCommand","Data":{"Name":"VolumeUp","Arguments":{}}}""" to """{"type":"player_command","command":"volume_up"}""",
                """{"MessageType":"GeneralCommand","Data":{"Name":"VolumeDown","Arguments":{}}}""" to """{"type":"player_command","command":"volume_down"}""",
                """{"MessageType":"GeneralCommand","Data":{"Name":"Mute","Arguments":{}}}""" to """{"type":"player_command","command":"mute","args":{"muted":true}}""",
                """{"MessageType":"GeneralCommand","Data":{"Name":"Unmute","Arguments":{}}}""" to """{"type":"player_command","command":"mute","args":{"muted":false}}""",
                """{"MessageType":"GeneralCommand","Data":{"Name":"ToggleMute","Arguments":{}}}""" to """{"type":"player_command","command":"mute"}""",
                """{"MessageType":"Playstate","Data":{"Command":"Stop","ControllingUserId":"u"}}""" to """{"type":"playstate_command","command":"Stop"}""",
            )
            for (d in sockets.keys) {
                val jf = fake.lock.withLock { fake.sockets[jfId(d)] } ?: fail("no Jellyfin socket for ${d.kind}")
                for ((frame, expected) in frames) {
                    jf.send(Frame.Text(frame))
                    // One at a time: the bus delivers each on its own coroutine, so order is asserted per command.
                    val want = sockets.getValue(d).snapshot().size + 1
                    waitFor("${d.kind} received $expected") { sockets.getValue(d).snapshot().size >= want }
                    assertEquals(expected, sockets.getValue(d).snapshot().last(), "${d.kind}: $frame")
                }
            }
            delay(300)
            for ((d, sock) in sockets) assertEquals(frames.map { it.second }, sock.snapshot(), "${d.kind} got exactly its own commands, nobody else's")

            // FR-299-5 — the receiver's events socket closed: its bridge (and Jellyfin socket) ends after the grace.
            bridge.disconnectAfterGrace(receiver.deviceId)
            assertTrue(bridge.isBridged(receiver.deviceId), "inside the grace it is still bridged")
            waitFor("the receiver's Jellyfin socket closed after the grace", 5_000) { fake.has("close:${jfId(receiver)}") }
            assertTrue(!bridge.isBridged(receiver.deviceId))

            // FR-299-6 — the sweep ends an idle Ravilo session no socket holds, and nothing else.
            fake.lock.withLock {
                fake.sessionsBody = """[
                  {"Client":"Ravilo","DeviceId":"${jfId(receiver)}","IsActive":false,"LastActivityDate":"2020-01-01T00:00:00.0000000Z"},
                  {"Client":"Ravilo","DeviceId":"${jfId(phone)}","IsActive":true,"LastActivityDate":"2020-01-01T00:00:00.0000000Z"},
                  {"Client":"Ravilo","DeviceId":"${jfId(tv)}","IsActive":false,"LastActivityDate":"2020-01-01T00:00:00.0000000Z","NowPlayingItem":{"Id":"x"}}
                ]"""
            }
            val reaper = JellyfinSessionReaper(configStore, { sockets.keys.toList() }, bridge)
            val opensBefore = fake.count("open:${jfId(receiver)}")
            val ended = reaper.sweep()
            assertEquals(listOf(receiver.deviceId), ended)
            waitFor("the sweep's socket opened and closed under the receiver's identity", 5_000) {
                fake.count("open:${jfId(receiver)}") == opensBefore + 1 && fake.count("close:${jfId(receiver)}") == 2
            }
            assertEquals(1, fake.count("open:${jfId(tv)}"), "a playing session is never touched")
            assertTrue(!fake.has("noauth:${jfId(receiver)}"))
        } finally {
            for (d in sockets.keys) bridge.disconnect(d.deviceId)
            client.close()
            server.stop(100, 500)
            scope.cancel()
            for (f in listOf(cfgPath, "/tmp/jellystructure-test-bridge296-${getpid()}.db", "/tmp/jellystructure-test-bridge296-${getpid()}-tags.json")) platform.posix.remove(f)
        }
    }
}
