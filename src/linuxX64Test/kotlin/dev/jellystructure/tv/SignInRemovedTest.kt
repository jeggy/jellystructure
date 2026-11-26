package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceIdentityRegistry
import dev.jellystructure.auth.SessionService
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * DEB-5777 (Phase 143 amendment 2026-11-26, FR-143-1..4, FR-143-6) — a sign-in removed by someone other than its device is pushed
 * `{"type":"sign_in_removed"}` on its own socket and no other.
 */
class SignInRemovedTest {
    private lateinit var dbPath: String
    private lateinit var devices: RaviloDeviceService
    private lateinit var sessions: SessionService

    private fun hex(n: Int) = ("abc" + n.toString(16)).padStart(32, '0')
    private fun dashed(h: String) = "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"

    @BeforeTest
    fun setUp() {
        dbPath = "/tmp/jellystructure-test-sign-in-removed-${getpid()}.db"
        val db = createDatabase(dbPath)
        devices = RaviloDeviceService(db)
        sessions = SessionService(db)
        DeviceIdentityRegistry.clear()
    }

    @AfterTest
    fun tearDown() {
        dev.jellystructure.db.closeLastDatabaseForTests()
        for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
    }

    private fun login(deviceId: String, userId: String) =
        devices.loginDevice(deviceId, deviceId, userId, "name", "jf-$deviceId-$userId", isAdmin = false, isKids = false)

    @Test
    fun `the event is one type and not the password route one`() {
        assertEquals("""{"type":"sign_in_removed"}""", OtherDevicesResign.SIGN_IN_REMOVED_EVENT)
        assertTrue(OtherDevicesResign.SIGN_IN_REMOVED_EVENT != OtherDevicesResign.SIGNED_OUT_EVENT)
    }

    @Test
    fun `a revoke gives back the row under the ids it was stored with and a miss gives back none`() {
        val key = hex(0x7000)
        login("tv-1", dashed(key).uppercase())
        assertEquals(emptyList(), devices.removeSession("tv-1", hex(0x7001)), "another user's id removes nothing")
        assertEquals(
            listOf(RaviloDeviceService.RemovedSignIn(dashed(key).uppercase(), "tv-1")),
            devices.removeSession("tv-1", key),   // matched on the normalised form, answered in the stored one
        )
        assertEquals(emptyList(), devices.removeSession("tv-1", key), "a second revoke finds nothing, so nothing is pushed twice")
    }

    @Test
    fun `sign out everywhere gives back every row each under its own stored ids`() {
        val key = hex(0x7100)
        login("tv-1", dashed(key).uppercase()); login("tv-2", key); login("phone", hex(0x7101))
        val removed = devices.deleteAllForUser(dashed(key))
        assertEquals(
            setOf(RaviloDeviceService.RemovedSignIn(dashed(key).uppercase(), "tv-1"), RaviloDeviceService.RemovedSignIn(key, "tv-2")),
            removed.toSet(),
        )
        assertEquals(2, removed.size, "the count the route answers with is this list's size")
        assertEquals(1, devices.listByUserAnyForm(hex(0x7101)).size, "another user's device stays")
    }

    @Test
    fun `a gone user removal pushes once per removed row after the rows are gone`() = runBlocking {
        val key = hex(0x7200)
        login("tv-1", dashed(key).uppercase()); login("tv-2", key)
        val pushed = ArrayList<Pair<String, String>>()
        val removal = GoneUserRemoval(createDatabase(dbPath), devices, sessions, clock = { 1L }) { user, device ->
            assertEquals(0, devices.listByUserAnyForm(key).size, "the row is gone before its socket hears it")
            pushed += user to device
        }
        removal.remove(key)
        assertEquals(setOf(dashed(key).uppercase() to "tv-1", key to "tv-2"), pushed.toSet())
        assertEquals(2, pushed.size)
    }

    @Test
    fun `a removal that is refused pushes nothing`() = runBlocking {
        login("tv-1", "household")
        val pushed = ArrayList<String>()
        val removal = GoneUserRemoval(createDatabase(dbPath), devices, sessions, clock = { 1L }) { _, device -> pushed += device }
        assertFailsWith<IllegalArgumentException> { removal.remove("household") }
        assertEquals(emptyList(), pushed)
        assertEquals(1, devices.listByUserAnyForm("household").size, "and nothing was removed")
    }

    @Test
    fun `only the socket of the exact sign-in hears a removal or a password-route signed_out`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bus = TvEventBus(scope)
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                webSocket("/events") {
                    val user = call.request.queryParameters["user"]!!
                    val device = call.request.queryParameters["device"]!!
                    bus.tryRegister(user, device, this)
                    try { for (f in incoming) Unit } finally { bus.unregister(user, device, this) }
                }
            }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(Curl) { install(io.ktor.client.plugins.websocket.WebSockets) }
        val lock = Mutex()
        val frames = mutableMapOf<String, MutableList<String>>()
        fun open(user: String, device: String) = scope.launch {
            runCatching {
                client.webSocket("ws://127.0.0.1:$port/events?user=$user&device=$device") {
                    for (f in incoming) if (f is Frame.Text) lock.withLock { frames.getOrPut("$user/$device") { mutableListOf() } += f.readText() }
                }
            }
        }
        suspend fun got(who: String) = lock.withLock { frames[who].orEmpty().toList() }
        suspend fun waitFor(what: String, check: suspend () -> Boolean) {
            if (runCatching { withTimeout(10_000) { while (!check()) delay(25) } }.isFailure) fail("timed out waiting for: $what")
        }
        try {
            open("u-b", "tv-x")   // the TV's open profile is B
            open("u-c", "tv-y")   // a second TV
            waitFor("both registered") { bus.isConnected("tv-x") && bus.isConnected("tv-y") }

            bus.notifySignInRemoved("u-a", "tv-x")   // profile A of the same TV, which has no socket
            bus.notifySignedOut("u-a", "tv-x")       // FR-187-8 rule 4 holds to the same match
            bus.notifySignInRemoved("u-b", "tv-y")   // right device id, wrong user
            delay(400)
            assertEquals(emptyList(), got("u-b/tv-x"), "B's socket does not hear A's removal")
            assertEquals(emptyList(), got("u-c/tv-y"))

            bus.notifySignInRemoved("u-b", "tv-x")
            waitFor("the exact socket hears it") { got("u-b/tv-x") == listOf("""{"type":"sign_in_removed"}""") }
            bus.notifySignedOut("u-c", "tv-y")
            waitFor("rule 4 still reaches its own socket as signed_out") { got("u-c/tv-y") == listOf("""{"type":"signed_out"}""") }
        } finally {
            client.close()
            server.stop(100, 500)
            scope.cancel()
        }
    }
}
