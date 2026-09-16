package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.config.ChromecastConfig
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.createDatabase
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 218 — the hand-off code (FR-218-9): minted by a phone session, redeemed ONCE by a receiver for
 * its own `ravilo_device` row carrying the phone user's policy; the capability rule (FR-218-3: off or
 * no app id ⇒ absent); and the concurrent-cast ceiling (FR-218-8).
 */
class CastServiceTest {
    private lateinit var dbPath: String
    private lateinit var configStore: ConfigStore
    private lateinit var devices: RaviloDeviceService
    private lateinit var cast: CastService

    @BeforeTest
    fun setUp() {
        val suffix = getpid()
        dbPath = "/tmp/jellystructure-test-cast-$suffix.db"
        val db = createDatabase(dbPath)
        configStore = ConfigStore("/tmp/jellystructure-test-cast-$suffix.toml")
        devices = RaviloDeviceService(db)
        cast = CastService(db, configStore, devices)
    }

    @AfterTest
    fun tearDown() {
        for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
    }

    private fun phone(): DeviceData = devices.loginDevice(
        deviceId = "pixel9", deviceName = "Pixel 9", jellyfinUserId = "u1", jellyfinUsername = "jeggy",
        jellyfinUserToken = "jf-token", isAdmin = false, isKids = true,
        allowedLibraries = setOf("lib-a"), allowedTags = emptySet(), blockedTags = setOf("adult"),
    ).first

    @Test
    fun `capability is absent unless enabled with an 8-hex app id`() = runBlocking {
        assertNull(cast.capability(), "no block ⇒ no button")
        configStore.update(configStore.current.copy(chromecast = ChromecastConfig(enabled = true, appId = "")))
        assertNull(cast.capability(), "enabled but no app id ⇒ still absent, never greyed")
        configStore.update(configStore.current.copy(chromecast = ChromecastConfig(enabled = true, appId = "a1b2c3d4", publicUrl = "https://js.example/")))
        val cap = assertNotNull(cast.capability())
        assertEquals("A1B2C3D4", cap.appId)
        assertEquals("https://js.example/cast/", cap.receiverUrl)
        configStore.update(configStore.current.copy(chromecast = ChromecastConfig(enabled = false, appId = "A1B2C3D4")))
        assertNull(cast.capability(), "switched off ⇒ absent")
        assertEquals(2, ChromecastConfig(maxSessions = 99).effectiveMaxSessions(), "junk max_sessions reads back as 2")
    }

    @Test
    fun `a code enrols the receiver once as the phone user with that users policy`() {
        val p = phone()
        val handoff = cast.mint(p)
        assertEquals(6, handoff.code.length)
        assertTrue(handoff.expiresAt > 0)

        val (receiver, token) = assertNotNull(cast.redeem(handoff.code.lowercase(), "Living room", null), "case-insensitive redeem")
        assertTrue(receiver.deviceId.startsWith("cast-"))
        assertEquals("Chromecast via Ravilo · Living room", receiver.displayName, "FR-218-10 — the dashboard name")
        assertEquals("u1", receiver.jellyfinUserId)
        assertEquals("jf-token", receiver.jellyfinUserToken, "the Jellyfin token lives on the row, server-side — never on the receiver")
        assertTrue(receiver.isKids)
        assertEquals(setOf("liba"), receiver.allowedLibraries, "GUID-normalised at the boundary, like any login")
        assertEquals(setOf("adult"), receiver.blockedTags)
        assertNotNull(devices.validateDeviceToken(token), "the receiver's own device token is real")
        assertTrue(CastService.isCastDevice(receiver))

        assertNull(cast.redeem(handoff.code, "Living room", null), "single-use")
        assertNull(cast.redeem("ZZZZZZ", null, null), "unknown code")

        // A receiver whose storage survived re-uses its row (same id ⇒ same token).
        val again = cast.mint(p)
        val (receiver2, token2) = assertNotNull(cast.redeem(again.code, "Living room", receiver.deviceId))
        assertEquals(receiver.deviceId, receiver2.deviceId)
        assertEquals(token, token2)
    }

    @Test
    fun `the ceiling refuses a receiver only past max_sessions and never a TV`() = runBlocking {
        configStore.update(configStore.current.copy(chromecast = ChromecastConfig(enabled = true, appId = "A1B2C3D4", maxSessions = 1)))
        val p = phone()
        val (r1, _) = assertNotNull(cast.redeem(cast.mint(p).code, "One", null))
        val (r2, _) = assertNotNull(cast.redeem(cast.mint(p).code, "Two", null))
        cast.checkCeiling(r1, emptyList())                       // nothing playing ⇒ fine
        cast.checkCeiling(r1, listOf(r1.displayName))            // its own session never counts against it
        val e = assertFailsWith<CastCeilingException> { cast.checkCeiling(r2, listOf(r1.displayName)) }
        assertEquals(30, e.retryAfterSeconds)
        cast.checkCeiling(p, listOf(r1.displayName, r2.displayName))   // a phone/TV is never gated
    }
}
