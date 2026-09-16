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
import kotlin.test.assertSame

/**
 * Phase 224 (FR-224-2, FR-224-4) — the device row remembers which build and platform last spoke, written
 * on login and on change only; and every resolution of a device registers whose Jellyfin token it is.
 */
class RaviloDeviceVersionTest {
    private lateinit var dbPath: String
    private lateinit var db: dev.jellystructure.db.JellystructureDb
    private lateinit var devices: RaviloDeviceService

    @BeforeTest
    fun setUp() {
        dbPath = "/tmp/jellystructure-test-devver-${getpid()}.db"
        db = createDatabase(dbPath)
        devices = RaviloDeviceService(db)
        DeviceIdentityRegistry.clear()
    }

    @AfterTest
    fun tearDown() {
        for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
    }

    private fun login(appVersion: String? = null, platform: String? = null) = devices.loginDevice(
        deviceId = "bravia", deviceName = "Stue TV", jellyfinUserId = "u1", jellyfinUsername = "jeggy",
        jellyfinUserToken = "jf-1", isAdmin = false, isKids = false, appVersion = appVersion, platform = platform,
    )

    @Test
    fun `login stores the pair and a later login without it keeps it`() {
        val (first, _) = login(appVersion = "1.18", platform = "tv")
        assertEquals("1.18", first.appVersion); assertEquals("tv", first.platform)
        val (again, _) = login()
        assertEquals("1.18", again.appVersion, "an older client signing in must not blank a newer report")
        assertEquals("tv", again.platform)
        assertEquals("1.18", db.raviloDeviceQueries.getByToken(again.deviceToken).executeAsOne().app_version)
    }

    @Test
    fun `a versioned request writes once then a repeat is a no-op and a change writes again`() {
        val (dev, token) = login()
        assertNull(dev.appVersion)
        val d1 = assertNotNull(devices.validateDeviceToken(token, "1.18", "tv"))
        assertEquals("1.18", d1.appVersion); assertEquals("tv", d1.platform)
        val row = db.raviloDeviceQueries.getByToken(token).executeAsOne()
        assertEquals("1.18", row.app_version); assertEquals("tv", row.platform)

        // Same pair again: served from the cache untouched — the identical object, so nothing was rewritten.
        assertSame(d1, devices.validateDeviceToken(token, "1.18", "tv"))
        // No headers at all: changes nothing.
        assertSame(d1, devices.validateDeviceToken(token))
        assertEquals("1.18", db.raviloDeviceQueries.getByToken(token).executeAsOne().app_version)

        // A new build: written once, visible on the row and on the cached data.
        val d3 = assertNotNull(devices.validateDeviceToken(token, "1.19", "tv"))
        assertEquals("1.19", d3.appVersion)
        assertEquals("1.19", db.raviloDeviceQueries.getByToken(token).executeAsOne().app_version)
        assertSame(d3, devices.validateDeviceToken(token, "1.19", "tv"))
    }

    @Test
    fun `whitespace and blanks never reach the row`() {
        val (_, token) = login()
        val d = assertNotNull(devices.validateDeviceToken(token, "  1.18  ", "   "))
        assertEquals("1.18", d.appVersion)
        assertNull(d.platform)
        assertEquals("1.18", devices.validateDeviceToken(token, "   ", "tv")?.appVersion, "a blank version says nothing, so the row keeps what it knew")
    }

    @Test
    fun `resolving a device registers whose Jellyfin token it is`() {
        val (_, token) = login()
        devices.validateDeviceToken(token, "1.18", "tv")
        val identity = assertNotNull(DeviceIdentityRegistry.identityFor("jf-1"))
        assertEquals("ravilo-bravia-u1", identity.deviceId)
        assertEquals("Stue TV", identity.deviceName)
        assertEquals("1.18", identity.appVersion)
        devices.unpair(token)
        assertNull(DeviceIdentityRegistry.identityFor("jf-1"), "a revoked row forgets its token")
    }
}
