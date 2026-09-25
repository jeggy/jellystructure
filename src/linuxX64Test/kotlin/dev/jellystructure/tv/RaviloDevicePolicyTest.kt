package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceIdentityRegistry
import dev.jellystructure.auth.JellyfinPolicy
import dev.jellystructure.db.createDatabase
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 258 (FR-258-1/2/4/5/6) — the five policy fields on `ravilo_device` follow Jellyfin: a refresh
 * rewrites every row of the user that differs, is seen at once through the token cache, stamps
 * `policy_refreshed_at` either way, and compares after the same normalisation the login applies.
 */
class RaviloDevicePolicyTest {
    private lateinit var dbPath: String
    private lateinit var db: dev.jellystructure.db.JellystructureDb
    private lateinit var devices: RaviloDeviceService

    @BeforeTest
    fun setUp() {
        dbPath = "/tmp/jellystructure-test-devpolicy-${getpid()}.db"
        db = createDatabase(dbPath)
        devices = RaviloDeviceService(db)
        DeviceIdentityRegistry.clear()
    }

    @AfterTest
    fun tearDown() {
        for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
    }

    private fun login(deviceId: String, name: String, user: String = "u1", tags: Set<String> = setOf("family"), libs: Set<String>? = setOf("AAAA-bbbb"), kind: String? = null) =
        devices.loginDevice(
            deviceId = deviceId, deviceName = name, jellyfinUserId = user, jellyfinUsername = "eydun",
            jellyfinUserToken = "jf-$user", isAdmin = false, isKids = false,
            allowedLibraries = libs, allowedTags = tags, blockedTags = emptySet(), kind = kind,
        )

    private fun live(tags: List<String> = listOf("family"), libs: List<String> = listOf("AAAA-bbbb"), allFolders: Boolean = false, admin: Boolean = false, rating: Int? = null) =
        DevicePolicy.of(JellyfinPolicy(isAdministrator = admin, maxParentalRating = rating, enableAllFolders = allFolders, enabledFolders = libs, allowedTags = tags, blockedTags = emptyList()))

    @Test
    fun `a widened tag set reaches every row of the user at once and is visible through the cache immediately`() {
        val (_, tvToken) = login("bravia", "Stue TV")
        val (_, phoneToken) = login("pixel", "Pixel 9", kind = "phone")
        // A screen minted from the phone's row (ScreenPairingService.claim's shape): it inherits the stale copy.
        val (_, screenToken) = login("tizen", "Bedroom TV", kind = "screen")
        // Warm the token cache — this is exactly the copy a rewrite must not leave behind (dev review item 1).
        assertEquals(setOf("family"), assertNotNull(devices.validateDeviceToken(tvToken)).allowedTags)

        val changes = devices.refreshPolicy("u1", live(tags = listOf("Family", "Kids", "DOCS")), now = 1_000L)

        assertEquals(3, changes.size, "one change per row, all on tags: $changes")
        assertTrue(changes.all { it.field == "allowed tags" && it.from == "family" && it.to == "docs,family,kids" }, "$changes")
        assertEquals(setOf("Stue TV", "Pixel 9", "Bedroom TV"), changes.map { it.deviceName }.toSet())
        for (t in listOf(tvToken, phoneToken, screenToken)) {
            val d = assertNotNull(devices.validateDeviceToken(t))
            assertEquals(setOf("family", "kids", "docs"), d.allowedTags, "the cache served the old row for ${d.displayName}")
        }
        assertEquals(1_000L, db.raviloDeviceQueries.getByToken(tvToken).executeAsOne().policy_refreshed_at)
    }

    @Test
    fun `a row that already agrees is not a change but is stamped as confirmed`() {
        val (_, token) = login("bravia", "Stue TV")
        val before = db.raviloDeviceQueries.getByToken(token).executeAsOne().policy_refreshed_at
        assertNotNull(before, "login stamps policy_refreshed_at (FR-258-5/6)")
        val changes = devices.refreshPolicy("u1", live(), now = before + 5_000L)
        assertEquals(emptyList(), changes)
        assertEquals(before + 5_000L, db.raviloDeviceQueries.getByToken(token).executeAsOne().policy_refreshed_at)
    }

    @Test
    fun `raw Jellyfin values compare equal to the normalised row so a quiet pass rewrites nothing`() {
        // Dev review item 4: EnabledFolders are raw GUIDs (dashes, any case); tags any case.
        val (_, token) = login("bravia", "Stue TV", tags = setOf("Family"), libs = setOf("AAAA-BBBB-cccc"))
        val row = assertNotNull(devices.validateDeviceToken(token))
        assertEquals(setOf("aaaabbbbcccc"), row.allowedLibraries)
        val changes = devices.refreshPolicy("u1", live(tags = listOf("FAMILY"), libs = listOf("aaaa-bbbb-CCCC")))
        assertEquals(emptyList(), changes, "a difference in dashing or case is not a policy change")
    }

    @Test
    fun `libraries admin and kids follow too and EnableAllFolders becomes unrestricted`() {
        val (_, token) = login("bravia", "Stue TV")
        val changes = devices.refreshPolicy("u1", live(allFolders = true, admin = true, rating = 7))
        assertEquals(setOf("libraries", "admin", "kids"), changes.map { it.field }.toSet(), "$changes")
        val d = assertNotNull(devices.validateDeviceToken(token))
        assertNull(d.allowedLibraries, "EnableAllFolders ⇒ null, exactly as login stores it")
        assertTrue(d.isAdmin); assertTrue(d.isKids)
    }

    @Test
    fun `a refresh for one user leaves every other user's rows alone`() {
        val (_, a) = login("bravia", "Stue TV", user = "u1")
        val (_, b) = login("bravia", "Stue TV", user = "u2", tags = setOf("kids"))
        devices.refreshPolicy("u1", live(tags = listOf("docs")))
        assertEquals(setOf("docs"), assertNotNull(devices.validateDeviceToken(a)).allowedTags)
        assertEquals(setOf("kids"), assertNotNull(devices.validateDeviceToken(b)).allowedTags)
    }

    @Test
    fun `a user with no rows is a no-op`() {
        assertEquals(emptyList(), devices.refreshPolicy("nobody", live()))
    }
}
