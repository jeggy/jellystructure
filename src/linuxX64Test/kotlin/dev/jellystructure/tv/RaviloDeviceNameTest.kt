package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceIdentityRegistry
import dev.jellystructure.db.createDatabase
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * DEB-5719 (DEB-5711, FR-1, FR-2) — a TV that signed in under its model string takes the name its events socket
 * carries (`cast_name=`): in the row, in the token cache `validateDeviceToken` serves from, on the device the bus
 * registers, and so in every session it starts afterwards.
 */
class RaviloDeviceNameTest {
    private lateinit var dbPath: String
    private lateinit var db: dev.jellystructure.db.JellystructureDb
    private lateinit var devices: RaviloDeviceService

    @BeforeTest
    fun setUp() {
        dbPath = "/tmp/jellystructure-test-devname-${getpid()}.db"
        db = createDatabase(dbPath)
        devices = RaviloDeviceService(db)
        DeviceIdentityRegistry.clear()
    }

    @AfterTest
    fun tearDown() {
        for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
    }

    private fun login() = devices.loginDevice(
        deviceId = "tv-1", deviceName = "sdk_model_x86_64", jellyfinUserId = "u1", jellyfinUsername = "jeggy",
        jellyfinUserToken = "jf-1", isAdmin = false, isKids = false, appVersion = null, platform = "android",
    )

    private fun storedName(token: String) = db.raviloDeviceQueries.getByToken(token).executeAsOne().display_name

    @Test
    fun `the name the socket carries reaches the returned device and the cache and the row`() {
        val (signedIn, token) = login()
        assertEquals("sdk_model_x86_64", assertNotNull(devices.validateDeviceToken(token)).displayName)   // caches the entry

        val renamed = devices.recordName(signedIn, castNameOf("  Stue TV\n"))
        assertEquals("Stue TV", renamed.displayName)
        assertEquals("Stue TV", devices.validateDeviceToken(token)?.displayName, "served from the cache, no wait for the TTL")
        assertEquals("Stue TV", storedName(token))
    }

    @Test
    fun `the same name again changes nothing`() {
        val (signedIn, token) = login()
        val renamed = devices.recordName(signedIn, "Stue TV")
        val again = devices.recordName(renamed, "Stue TV")
        assertEquals(renamed, again)
        assertEquals("Stue TV", storedName(token))
    }

    @Test
    fun `a socket without a name keeps the stored one`() {
        val (signedIn, token) = login()
        assertEquals(signedIn, devices.recordName(signedIn, castNameOf(null)))
        assertEquals(signedIn, devices.recordName(signedIn, castNameOf("   ")))
        assertEquals("sdk_model_x86_64", storedName(token))
        assertEquals("sdk_model_x86_64", devices.validateDeviceToken(token)?.displayName)

        val renamed = devices.recordName(signedIn, "Stue TV")
        devices.recordName(renamed, null)
        assertEquals("Stue TV", storedName(token), "a later socket without cast_name never clears it")
    }

    @Test
    fun `a session the renamed device starts is named by the new name`() {
        val (signedIn, _) = login()
        val sessions = PlaybackSessions(db)
        assertEquals("sdk_model_x86_64", sessions.placeNameOf(signedIn))
        assertEquals("Stue TV", sessions.placeNameOf(devices.recordName(signedIn, "Stue TV")))
    }

    @Test
    fun `another user's row on the same device keeps its name`() {
        val (signedIn, _) = login()
        val (_, otherToken) = devices.loginDevice(
            deviceId = "tv-1", deviceName = "sdk_model_x86_64", jellyfinUserId = "u2", jellyfinUsername = "anna",
            jellyfinUserToken = "jf-2", isAdmin = false, isKids = false, appVersion = null, platform = "android",
        )
        devices.recordName(signedIn, "Stue TV")
        assertEquals("sdk_model_x86_64", storedName(otherToken))

    }
}
