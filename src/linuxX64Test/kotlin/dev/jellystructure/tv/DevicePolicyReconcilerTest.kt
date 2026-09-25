package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceIdentityRegistry
import dev.jellystructure.auth.JellyfinPolicy
import dev.jellystructure.auth.JellyfinUser
import dev.jellystructure.db.createDatabase
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Phase 258 (FR-258-2/3/4) — one `/Users` fetch per pass; a failed fetch changes nothing; a user missing
 * from the answer is skipped; `home_changed` goes only to the users whose rows changed; the connect
 * trigger fires once per minute per user, not once per reconnect.
 */
class DevicePolicyReconcilerTest {
    private lateinit var dbPath: String
    private lateinit var db: dev.jellystructure.db.JellystructureDb
    private lateinit var devices: RaviloDeviceService
    private var clock = 100_000L
    private var fetchCount = 0
    private var answer: List<JellyfinUser>? = emptyList()
    private val notified = ArrayList<String>()

    @BeforeTest
    fun setUp() {
        dbPath = "/tmp/jellystructure-test-reconciler-${getpid()}.db"
        db = createDatabase(dbPath)
        devices = RaviloDeviceService(db)
        DeviceIdentityRegistry.clear()
        fetchCount = 0; notified.clear(); clock = 100_000L
    }

    @AfterTest
    fun tearDown() {
        for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
    }

    private fun reconciler() = DevicePolicyReconciler(
        deviceService = devices,
        fetchUsers = { fetchCount++; answer },
        onPolicyChanged = { notified += it },
        clock = { clock },
    )

    private fun user(id: String, tags: List<String>) =
        JellyfinUser(id = id, name = "user-$id", policy = JellyfinPolicy(isAdministrator = false, enableAllFolders = true, allowedTags = tags))

    private fun login(deviceId: String, user: String, tags: Set<String>) = devices.loginDevice(
        deviceId = deviceId, deviceName = "TV $deviceId", jellyfinUserId = user, jellyfinUsername = "user-$user",
        jellyfinUserToken = "jf-$user", isAdmin = false, isKids = false, allowedLibraries = null, allowedTags = tags,
    )

    @Test
    fun `a pass rewrites the drifted rows and pushes home_changed to those users only`() = runBlocking {
        val (_, a) = login("tv-a", "u1", setOf("family"))
        val (_, b) = login("tv-b", "u2", setOf("kids"))
        answer = listOf(user("u1", listOf("family", "docs")), user("u2", listOf("kids")))
        val pass = assertNotNull(reconciler().reconcileAll("start"))
        assertEquals(1, fetchCount)
        assertEquals(2, pass.usersSeen); assertEquals(0, pass.usersSkipped)
        assertEquals(1, pass.changes.size)
        assertEquals(listOf("u1"), notified)
        assertEquals(setOf("family", "docs"), assertNotNull(devices.validateDeviceToken(a)).allowedTags)
        assertEquals(setOf("kids"), assertNotNull(devices.validateDeviceToken(b)).allowedTags)
    }

    @Test
    fun `a failed fetch changes nothing and says so`() = runBlocking {
        val (_, a) = login("tv-a", "u1", setOf("family"))
        answer = null
        assertNull(reconciler().reconcileAll("interval"))
        assertEquals(setOf("family"), assertNotNull(devices.validateDeviceToken(a)).allowedTags)
        assertEquals(emptyList(), notified)
    }

    @Test
    fun `a user missing from the answer is skipped never widened never narrowed`() = runBlocking {
        val (_, a) = login("tv-a", "u1", setOf("family"))
        answer = listOf(user("u2", listOf("other")))
        val pass = assertNotNull(reconciler().reconcileAll("start"))
        assertEquals(1, pass.usersSkipped); assertEquals(0, pass.usersSeen)
        assertEquals(setOf("family"), assertNotNull(devices.validateDeviceToken(a)).allowedTags)
        assertEquals(emptyList(), notified)
    }

    @Test
    fun `the connect trigger fetches once per minute per user`() = runBlocking {
        login("tv-a", "u1", setOf("family"))
        answer = listOf(user("u1", listOf("family")))
        val r = reconciler()
        assertNotNull(r.reconcileOnConnect("u1"))
        assertEquals(1, fetchCount)
        clock += 30_000L
        assertNull(r.reconcileOnConnect("u1"), "30 s later: fresh, no fetch")
        assertEquals(1, fetchCount)
        clock += 31_000L
        assertNotNull(r.reconcileOnConnect("u1"), "61 s later: a new pass")
        assertEquals(2, fetchCount)
        // A user the reconciler has never seen is never "fresh".
        login("tv-c", "u9", setOf("x"))
        assertNotNull(r.reconcileOnConnect("u9"))
        assertEquals(3, fetchCount)
    }

    @Test
    fun `a quiet pass logs no change and notifies nobody`() = runBlocking {
        login("tv-a", "u1", setOf("family"))
        answer = listOf(user("u1", listOf("FAMILY")))
        val pass = assertNotNull(reconciler().reconcileAll("interval"))
        assertEquals(emptyList(), pass.changes)
        assertEquals(emptyList(), notified)
    }
}
