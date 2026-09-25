package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceIdentityRegistry
import dev.jellystructure.db.createDatabase
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 259 (FR-259-1/2/5) — a device remembers every Ravilo version it ran: one history row per change
 * (acceptance 1), one history per physical device however many viewers (acceptance 2), written by both
 * 224 write sites, and gone with the device's last row.
 */
class RaviloDeviceVersionHistoryTest {
    private lateinit var dbPath: String
    private lateinit var db: dev.jellystructure.db.JellystructureDb
    private lateinit var devices: RaviloDeviceService

    @BeforeTest
    fun setUp() {
        dbPath = "/tmp/jellystructure-test-devvh-${getpid()}.db"
        db = createDatabase(dbPath)
        devices = RaviloDeviceService(db)
        DeviceIdentityRegistry.clear()
    }

    @AfterTest
    fun tearDown() {
        for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
    }

    private fun login(user: String = "u1", deviceId: String = "bravia", appVersion: String? = null) = devices.loginDevice(
        deviceId = deviceId, deviceName = "Stue TV", jellyfinUserId = user, jellyfinUsername = "user-$user",
        jellyfinUserToken = "jf-$user", isAdmin = false, isKids = false, appVersion = appVersion, platform = "tv",
    )

    @Test
    fun `1_36 then 1_38 yields two rows and a thousand more 1_38 requests yield none`() {
        val (_, token) = login()
        assertEquals(emptyList(), devices.versionHistory("bravia"), "no version reported yet ⇒ no history")
        devices.validateDeviceToken(token, "1.36", "tv")
        devices.validateDeviceToken(token, "1.38", "tv")
        repeat(1000) { devices.validateDeviceToken(token, "1.38", "tv") }
        val h = devices.versionHistory("bravia")
        assertEquals(listOf("1.38", "1.36"), h.map { it.appVersion }, "newest first")
        assertTrue(h.all { it.observed })
        assertTrue(h[0].firstSeenAt >= h[1].firstSeenAt)
    }

    @Test
    fun `two viewers on one TV produce one history`() {
        val (_, a) = login(user = "u1")
        val (_, b) = login(user = "u2")
        devices.validateDeviceToken(a, "1.38", "tv")
        devices.validateDeviceToken(b, "1.38", "tv")   // the second viewer's row catches up: no new history row
        assertEquals(listOf("1.38"), devices.versionHistory("bravia").map { it.appVersion })
        devices.validateDeviceToken(b, "1.39", "tv")
        assertEquals(listOf("1.39", "1.38"), devices.versionHistory("bravia").map { it.appVersion })
    }

    @Test
    fun `a login that carries a version writes the history too and a re-login with the same one does not`() {
        login(appVersion = "1.37")
        login(appVersion = "1.37")
        assertEquals(listOf("1.37"), devices.versionHistory("bravia").map { it.appVersion })
        login(appVersion = "1.37-68-gade0523d-dirty")
        assertEquals(listOf("1.37-68-gade0523d-dirty", "1.37"), devices.versionHistory("bravia").map { it.appVersion }, "a dev build is a version like any other, stored verbatim")
    }

    @Test
    fun `revoking one viewer of two keeps the history and revoking the last takes it`() {
        login(user = "u1", appVersion = "1.38")
        val (_, b) = login(user = "u2", appVersion = "1.38")
        devices.removeSession("bravia", "u1")
        assertEquals(1, devices.versionHistory("bravia").size, "one viewer of two keeps it")
        devices.unpair(b)
        assertEquals(emptyList(), devices.versionHistory("bravia"), "the last row takes it")
    }

    @Test
    fun `sign out everywhere drops the history of every device that has no row left`() {
        login(user = "u1", deviceId = "tv-a", appVersion = "1.38")
        login(user = "u1", deviceId = "tv-b", appVersion = "1.38")
        login(user = "u2", deviceId = "tv-b", appVersion = "1.38")
        devices.deleteAllForUser("u1")
        assertEquals(emptyList(), devices.versionHistory("tv-a"))
        assertEquals(1, devices.versionHistory("tv-b").size, "u2 still has tv-b")
        assertNotNull(devices.listSessions("tv-b").singleOrNull())
    }
}
