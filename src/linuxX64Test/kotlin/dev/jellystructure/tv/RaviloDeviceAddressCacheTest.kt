package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceIdentityRegistry
import dev.jellystructure.db.createDatabase
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * FR-236-6 / FR-R370-1 (DEB-1461) — `recordAddress` writes the row, and the five-minute token cache `validateDeviceToken`
 * serves from must see it at once: a client that has just signed in is "nearby" its household's places immediately,
 * not when the login-time entry expires.
 */
class RaviloDeviceAddressCacheTest {
    private lateinit var dbPath: String
    private lateinit var db: dev.jellystructure.db.JellystructureDb
    private lateinit var devices: RaviloDeviceService

    @BeforeTest
    fun setUp() {
        dbPath = "/tmp/jellystructure-test-devaddr-${getpid()}.db"
        db = createDatabase(dbPath)
        devices = RaviloDeviceService(db)
        DeviceIdentityRegistry.clear()
    }

    @AfterTest
    fun tearDown() {
        for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
    }

    private fun login() = devices.loginDevice(
        deviceId = "web", deviceName = "Ravilo Web", jellyfinUserId = "u1", jellyfinUsername = "jeggy",
        jellyfinUserToken = "jf-1", isAdmin = false, isKids = false, appVersion = null, platform = "web",
    )

    @Test
    fun `a cached login-time device sees the address the events socket records`() {
        val (_, token) = login()
        assertNull(assertNotNull(devices.validateDeviceToken(token)).lastPublicAddress)   // caches the entry

        devices.recordAddress("web", "u1", "203.0.113.9")
        assertEquals("203.0.113.9", devices.validateDeviceToken(token)?.lastPublicAddress, "served from the cache, no wait for the TTL")
        assertEquals("203.0.113.9", db.raviloDeviceQueries.getByToken(token).executeAsOne().last_public_address)
    }

    @Test
    fun `an address that changes reaches the cache while a repeat and a blank change nothing`() {
        val (_, token) = login()
        devices.validateDeviceToken(token)
        devices.recordAddress("web", "u1", "198.51.100.7")
        devices.recordAddress("web", "u1", "  ")
        devices.recordAddress("web", "u1", null)
        assertEquals("198.51.100.7", devices.validateDeviceToken(token)?.lastPublicAddress, "a blank never clears it")

        devices.recordAddress("web", "u1", "203.0.113.9")   // mobile data → home Wi-Fi
        assertEquals("203.0.113.9", devices.validateDeviceToken(token)?.lastPublicAddress)
    }

    @Test
    fun `an address recorded for another user's row leaves this one alone`() {
        val (_, token) = login()
        devices.validateDeviceToken(token)
        devices.recordAddress("web", "someone-else", "203.0.113.9")
        assertNull(devices.validateDeviceToken(token)?.lastPublicAddress)
    }
}
