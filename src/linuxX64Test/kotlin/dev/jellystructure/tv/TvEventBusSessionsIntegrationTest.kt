package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.db.createDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
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
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * R368 (dev review items 2 and 9) — session events go only to a socket that opted in with `features=sessions`, the
 * whole list on connect, and each viewer's list is built for that viewer. An installed app (no `features`) and the TV
 * receive no session frame at all, so they never reach their `else → refreshConfig()`. In process: the real
 * [TvEventBus], [PlaybackSessions] and [SessionPublisher] on a temporary database, sockets over the loopback.
 */
class TvEventBusSessionsIntegrationTest {
    private suspend fun waitFor(what: String, timeoutMs: Long = 10_000, check: suspend () -> Boolean) {
        val ok = runCatching { withTimeout(timeoutMs) { while (!check()) delay(25) } }.isSuccess
        if (!ok) fail("timed out waiting for: $what")
    }

    @Test
    fun `only a socket that asked for sessions receives them and the list is built per viewer`() = runBlocking {
        val run = getpid().toString()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dbPath = "/tmp/jellystructure-test-busses-$run.db"
        val db = createDatabase(dbPath)
        val devices = RaviloDeviceService(db)
        val bus = TvEventBus(scope)
        val sessions = PlaybackSessions(db)
        val publisher = SessionPublisher(sessions, devices, bus, canSee = { _, _, _, _ -> true }, scope = scope)
        sessions.notify = { publisher.publish(it) }
        sessions.describe = { id, _ -> SessionKind.FILM to SessionItem(id, "Night Train") }
        fun dev(id: String, user: String, kind: String): DeviceData = devices.loginDevice(
            deviceId = id, deviceName = id, jellyfinUserId = user, jellyfinUsername = user, jellyfinUserToken = "jf",
            isAdmin = false, isKids = false, kind = kind,
        ).first.also { devices.recordAddress(id, user, "198.51.100.7") }
        val a = dev("phone-a", "u-anna", "phone")
        dev("phone-b", "u-anna", "phone")
        dev("tv-c", "u-anna", "tv")
        dev("phone-d", "u-ben", "phone")

        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                webSocket("/events") {
                    val user = call.request.queryParameters["user"]!!
                    val device = call.request.queryParameters["device"]!!
                    val features = parseEventFeatures(call.request.queryParameters["features"])
                    bus.tryRegister(user, device, this, features)
                    if ("sessions" in features) {
                        val viewer = devices.listSessions(device).first { it.jellyfinUserId == user }
                        send(Frame.Text(publisher.listFrameFor(viewer)))
                    }
                    try { for (f in incoming) Unit } finally { bus.unregister(user, device, this) }
                }
            }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(Curl) { install(io.ktor.client.plugins.websocket.WebSockets) }
        val lock = Mutex()
        val frames = mutableMapOf<String, MutableList<String>>()
        fun open(device: String, user: String, features: String?) = scope.launch {
            val q = "user=$user&device=$device" + (features?.let { "&features=$it" } ?: "")
            runCatching {
                client.webSocket("ws://127.0.0.1:$port/events?$q") {
                    for (f in incoming) if (f is Frame.Text) lock.withLock { frames.getOrPut(device) { mutableListOf() } += f.readText() }
                }
            }
        }
        suspend fun got(device: String) = lock.withLock { frames[device].orEmpty().toList() }
        try {
            open("phone-a", "u-anna", "sessions")
            open("phone-b", "u-anna", null)          // today's app
            open("tv-c", "u-anna", null)             // the TV does not ask (FR-R368-10)
            open("phone-d", "u-ben", "sessions")
            waitFor("both opted-in sockets get the list on connect") {
                got("phone-a").any { "\"session_list\"" in it } && got("phone-d").any { "\"session_list\"" in it }
            }
            waitFor("all four registered") { bus.isConnected("phone-b") && bus.isConnected("tv-c") }

            val id = sessions.onStart(a, "film-1", 0)
            waitFor("a start reaches the opted-in sockets as a list") { got("phone-a").count { "session_list" in it } >= 2 }
            sessions.onProgress(a, "film-1", 0, paused = true)
            waitFor("a pause reaches them as one state") { got("phone-a").any { "session_state" in it } && got("phone-d").any { "session_state" in it } }

            // Built per viewer: Anna's row is hers and here; Ben sees it as not his.
            val annaList = got("phone-a").last { "session_list" in it }
            val benList = got("phone-d").last { "session_list" in it }
            assertTrue("\"mine\":true" in annaList && "\"here\":true" in annaList && id in annaList, annaList)
            assertTrue("\"mine\":false" in benList && "\"here\":false" in benList && id in benList, benList)

            delay(300)
            assertTrue(got("phone-b").isEmpty(), "an installed app gets no frame at all: ${got("phone-b")}")
            assertTrue(got("tv-c").isEmpty(), "the TV gets no frame at all: ${got("tv-c")}")
        } finally {
            client.close()
            server.stop(100, 500)
            scope.cancel()
            for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
        }
    }
}
