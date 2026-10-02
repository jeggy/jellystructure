package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.DeviceIdentityRegistry
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.JellyfinDeviceIdentity
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
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
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Phase 300 — a Cast receiver's borrowed Jellyfin token follows a live sign-in, end to end in process: a fake Jellyfin
 * over the loopback that accepts only the tokens it currently holds (its `/Users/{id}` is the paired-token check), the
 * real [RaviloDeviceService], [CastService] and paired-token check on a temporary database, and the real
 * [JellyfinSessionBridge] for FR-300-6.
 *
 * "Plays" is asserted the way a playback start decides it: [tvTokenForClient] on the receiver's row as its device token
 * resolves it is the token `startPlayback` and `playMusic` put into the stream URL, and null is the 409 *re-pair*.
 */
class ReceiverTokenRefreshIntegrationTest {

    private class FakeJellyfin {
        val lock = Mutex()
        val live = mutableSetOf<String>()
        val opens = mutableListOf<String>()   // "deviceId token" per /socket open
        suspend fun accepts(token: String?) = lock.withLock { token != null && token in live }
        suspend fun revoke(token: String) = lock.withLock { live.remove(token); Unit }
        suspend fun issue(token: String) = lock.withLock { live.add(token); Unit }
        suspend fun opened(line: String) = lock.withLock { line in opens }
    }

    private suspend fun waitFor(what: String, timeoutMs: Long = 15_000, check: suspend () -> Boolean) {
        val ok = runCatching { withTimeout(timeoutMs) { while (!check()) delay(25) } }.isSuccess
        if (!ok) fail("timed out waiting for: $what")
    }

    private fun tokenOf(authorization: String?): String? =
        Regex("Token=\"([^\"]+)\"").find(authorization.orEmpty())?.groupValues?.get(1)

    @Test
    fun `a receiver follows a live sign-in through re-sign-ins and hand-offs and a heal`() = runBlocking {
        DeviceIdentityRegistry.clear()
        val run = getpid().toString()
        fun t(name: String) = "jf-$name-$run"      // unique per run: the token caches are process-wide
        val fake = FakeJellyfin()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                get("/Users/{id}") {
                    if (fake.accepts(tokenOf(call.request.headers["Authorization"]))) call.respondText("{}", ContentType.Application.Json)
                    else call.respond(HttpStatusCode.Unauthorized)
                }
                post("/Sessions/Capabilities/Full") { call.respond(HttpStatusCode.NoContent) }
                webSocket("/socket") {
                    val token = tokenOf(call.request.headers["Authorization"])
                    if (!fake.accepts(token)) return@webSocket
                    fake.lock.withLock { fake.opens += "${call.request.queryParameters["deviceId"]} $token" }
                    send(Frame.Text("""{"MessageType":"ForceKeepAlive","Data":60}"""))
                    for (f in incoming) Unit
                }
            }
        }.start(wait = false)
        val base = "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}"

        val cfgPath = "/tmp/jellystructure-test-300-$run.toml"
        val dbPath = "/tmp/jellystructure-test-300-$run.db"
        val configStore = ConfigStore(cfgPath)
        configStore.update(configStore.current.copy(apiKeys = configStore.current.apiKeys.copy(jellyfinUrl = base, jellyfinToken = "server-token-$run")))
        val db = createDatabase(dbPath)
        val devices = RaviloDeviceService(db)
        val cast = CastService(db, configStore, devices)
        val mediaStore = MediaStore(db, JsTagStore("/tmp/jellystructure-test-300-$run-tags.json"), configStore)
        val bridge = JellyfinSessionBridge(configStore, TvEventBus(scope), scope, mediaStore, graceMs = 400)
        val jellyfin = JellyfinClient()
        installBorrowedTokenStore(devices)
        // Main.kt's wiring: a changed token is re-checked before it is trusted, and an open bridge restarts on the new one.
        devices.tokenListener = { d, old -> scope.launch { forgetTokenValidity(old); bridge.refresh(d) } }

        fun signIn(id: String, kind: String, user: String, token: String) = devices.loginDevice(
            deviceId = id, deviceName = id, jellyfinUserId = user, jellyfinUsername = user,
            jellyfinUserToken = token, isAdmin = false, isKids = false, kind = kind,
        ).first

        /** The receiver as its own device token resolves it, and the token a playback start would use (null = 409). */
        suspend fun receiverPlaysWith(deviceToken: String): String? {
            val d = assertNotNull(devices.validateDeviceToken(deviceToken), "the receiver's device token is valid")
            return jellyfin.tvTokenForClient(base, d)
        }

        try {
            fake.issue(t("mac1")); fake.issue(t("pixel"))
            val mac = signIn("mac-1", "tv", "u1", t("mac1"))
            val pixel = signIn("pixel-1", "phone", "u1", t("pixel"))

            // Sender A (the Mac) enrols the receiver; it plays with A's token.
            val (recv, recvDt) = assertNotNull(cast.redeem(cast.mint(mac).code, "Stue", null, platform = "cast-audio"))
            assertEquals(t("mac1"), receiverPlaysWith(recvDt))
            bridge.connect(recv, listOf("PlayState"))
            val jfId = JellyfinDeviceIdentity.forDevice(recv).deviceId
            waitFor("the receiver's bridge opens on the Mac's token") { fake.opened("$jfId ${t("mac1")}") }

            // A signs in again: Jellyfin replaces A's token (the old one is now 401). FR-300-3 — the receiver follows.
            fake.revoke(t("mac1")); fake.issue(t("mac2"))
            signIn("mac-1", "tv", "u1", t("mac2"))
            assertEquals(t("mac2"), devices.storedJellyfinToken(recv.deviceId, "u1"))
            waitFor("FR-300-5: the old token is checked again, not served from the 5-minute cache") {
                jellyfin.tvTokenForClient(base, recv) != t("mac1")
            }
            assertEquals(t("mac2"), receiverPlaysWith(recvDt), "plays with A's new token, no hand-off needed")
            waitFor("FR-300-6: the bridge restarts on the new sign-in") { fake.opened("$jfId ${t("mac2")}") }

            // Sender B (the Pixel) casts: the receiver redeems B's code (FR-300-1) and plays with B's token (FR-300-2).
            val (recvB, recvBDt) = assertNotNull(cast.redeem(cast.mint(pixel).code, "Stue", recv.deviceId))
            assertEquals(recv.deviceId, recvB.deviceId, "the same receiver row")
            assertEquals(recvDt, recvBDt, "the same device token: the receiver keeps its events socket")
            assertEquals(t("pixel"), receiverPlaysWith(recvDt))
            waitFor("the bridge follows the hand-off") { fake.opened("$jfId ${t("pixel")}") }

            // A casts again: A's live token.
            val macNow = assertNotNull(devices.listSessions("mac-1").firstOrNull())
            assertNotNull(cast.redeem(cast.mint(macNow).code, "Stue", recv.deviceId))
            assertEquals(t("mac2"), receiverPlaysWith(recvDt))

            // FR-300-4 — the production state: the receiver holds a copy no row holds any more (A's token revoked with
            // no re-sign-in we saw, and no hand-off yet). Its next request heals from the user's live device.
            fake.revoke(t("mac2")); forgetTokenValidity(t("mac2"))
            assertEquals(t("pixel"), receiverPlaysWith(recvDt), "healed from the Pixel's sign-in")
            assertEquals(t("pixel"), devices.storedJellyfinToken(recv.deviceId, "u1"), "and stored")
            assertEquals(t("pixel"), receiverPlaysWith(recvDt), "the next request reads the stored token")

            // A second user casting gets their own row and device token: their playback runs under their sign-in.
            fake.issue(t("kid"))
            val kid = signIn("kid-phone", "phone", "u2", t("kid"))
            val (recvKid, recvKidDt) = assertNotNull(cast.redeem(cast.mint(kid).code, "Stue", recv.deviceId))
            assertEquals(recv.deviceId, recvKid.deviceId)
            assertEquals("u2", recvKid.jellyfinUserId)
            assertNotEquals(recvDt, recvKidDt)
            assertEquals(t("kid"), receiverPlaysWith(recvKidDt))
            assertEquals(t("pixel"), devices.storedJellyfinToken(recv.deviceId, "u1"), "u1's row is untouched")

            // A user with no live sign-in anywhere stays rejected (409 re-pair), as before: nothing to borrow.
            fake.revoke(t("kid")); forgetTokenValidity(t("kid"))
            assertNull(receiverPlaysWith(recvKidDt))
            assertEquals(t("kid"), devices.storedJellyfinToken(recv.deviceId, "u2"), "never another user's token")

            // A TV's own dead sign-in is not healed from another device: it signs in itself.
            fake.revoke(t("pixel")); forgetTokenValidity(t("pixel"))
            fake.issue(t("tv"))
            signIn("tv-1", "tv", "u1", t("tv"))
            val pixelRow = assertNotNull(devices.validateDeviceToken(pixel.deviceToken))
            assertNull(jellyfin.tvTokenForClient(base, pixelRow), "a phone's own token is never replaced")
            assertTrue(devices.storedJellyfinToken("pixel-1", "u1") == t("pixel"))
        } finally {
            installBorrowedTokenStore(null)
            devices.tokenListener = null
            for (d in devices.allDevices()) bridge.disconnect(d.deviceId)
            server.stop(100, 500)
            scope.cancel()
            for (f in listOf(cfgPath, dbPath, "$dbPath-wal", "$dbPath-shm", "/tmp/jellystructure-test-300-$run-tags.json")) platform.posix.remove(f)
            DeviceIdentityRegistry.clear()
        }
    }
}
