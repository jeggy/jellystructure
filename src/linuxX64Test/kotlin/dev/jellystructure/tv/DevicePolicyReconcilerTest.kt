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

    // ── DEB-2675 (DEB-2672) — a user Jellyfin no longer lists ───────────────────────────────────────────────────────────────────

    private fun hex(n: Int) = n.toString(16).padStart(32, '0')
    private val admin = hex(0xad)
    private val alice = hex(0xa1)
    private val bob = hex(0xb0)
    private val data = hex(0xda)   // a user with data and no device row

    private fun listed(vararg ids: String, withAdmin: Boolean = true) =
        ids.map { id -> JellyfinUser(id = id, name = "user-$id", policy = JellyfinPolicy(isAdministrator = withAdmin && id == admin, enableAllFolders = true)) }

    private val gone = ArrayList<String>()
    private var candidateIds: List<String> = emptyList()

    private fun goneReconciler() = DevicePolicyReconciler(
        deviceService = devices,
        fetchUsers = { fetchCount++; answer },
        onPolicyChanged = { notified += it },
        candidates = { candidateIds },
        onUserGone = { gone += it },
        nameFromData = { "named-$it".take(12) },
        clock = { clock },
    )

    /** A household: the administrator, Alice (listed) and Bob (absent) — the last two hold a device. */
    private fun household() {
        login("tv-admin", admin, setOf()); login("tv-alice", alice, setOf()); login("tv-bob", bob, setOf())
        gone.clear()
    }

    @Test
    fun `two complete reads in a row that lack a user make them gone once`() = runBlocking {
        household()
        val r = goneReconciler()
        answer = listed(admin, alice)
        val first = assertNotNull(r.reconcileAll("start"))
        assertEquals(listOf(bob), first.marked); assertEquals(emptyList(), first.gone)
        assertEquals(true, r.isMarked(bob)); assertEquals(false, r.isMarked(alice))
        assertEquals(emptyList(), gone, "the first read only marks")
        val second = assertNotNull(r.reconcileAll("interval"))
        assertEquals(listOf(bob), second.gone)
        assertEquals(listOf(bob), gone)
        assertEquals(false, r.isMarked(bob), "a removed user is no longer marked")
        assertNotNull(r.reconcileAll("interval"))
        assertEquals(listOf(bob), gone, "onUserGone ran once")
    }

    @Test
    fun `a failed or partial read in between neither clears nor removes`() = runBlocking {
        household()
        val r = goneReconciler()
        answer = listed(admin, alice)
        r.reconcileAll("start")
        for (neutral in listOf<List<JellyfinUser>?>(null, emptyList(), listed(alice, withAdmin = false))) {
            answer = neutral
            r.reconcileAll("interval")
            assertEquals(true, r.isMarked(bob), "still marked after $neutral")
            assertEquals(emptyList(), gone)
        }
        answer = listed(admin, alice)
        assertEquals(listOf(bob), assertNotNull(r.reconcileAll("interval")).gone)
    }

    @Test
    fun `a complete read that lists the user clears the mark`() = runBlocking {
        household()
        val r = goneReconciler()
        answer = listed(admin, alice)
        r.reconcileAll("start")
        assertEquals(true, r.isMarked(bob))
        answer = listed(admin, alice, bob)
        r.reconcileAll("interval")
        assertEquals(false, r.isMarked(bob))
        answer = listed(admin, alice)
        r.reconcileAll("interval")
        assertEquals(true, r.isMarked(bob), "marked afresh, not gone")
        assertEquals(emptyList(), gone)
    }

    @Test
    fun `ids are compared normalised on both sides`() = runBlocking {
        login("tv-admin", admin, setOf())
        // stored dashed and upper case, the way Jellyfin's own table shows it; listed in the 32-hex form
        val dashed = "A1A1A1A1-A1A1-A1A1-A1A1-A1A1A1A1A1A1"
        login("tv-alice", dashed, setOf())
        gone.clear()
        val r = goneReconciler()
        answer = listed(admin, "a1".repeat(16))
        repeat(2) { assertNotNull(r.reconcileAll("interval")) }
        assertEquals(emptyList(), gone)
        assertEquals(false, r.isMarked(dashed))
    }

    @Test
    fun `a user with data and no device row becomes gone`() = runBlocking {
        login("tv-admin", admin, setOf())
        candidateIds = listOf(data.uppercase(), data)   // either form: one candidate
        gone.clear()
        val r = goneReconciler()
        answer = listed(admin)
        assertEquals(listOf(data), assertNotNull(r.reconcileAll("start")).marked)
        assertEquals(listOf(data), assertNotNull(r.reconcileAll("interval")).gone)
        assertEquals(listOf(data), gone)
    }

    @Test
    fun `a read that lists only a data-only id while every device user is absent marks nobody`() = runBlocking {
        household()
        candidateIds = listOf(data)
        val r = goneReconciler()
        answer = listed(admin, alice)
        r.reconcileAll("start")
        assertEquals(true, r.isMarked(bob)); assertEquals(true, r.isMarked(data))
        // Jellyfin repointed: it lists the data-only id but none of the users with a device row.
        answer = listed(data, withAdmin = false) + listed(admin).map { it.copy(id = hex(0xee)) }
        val pass = assertNotNull(r.reconcileAll("interval"))
        assertEquals(emptyList(), pass.marked); assertEquals(emptyList(), pass.gone)
        assertEquals(emptyList(), gone, "an existing mark does not become gone either")
        assertEquals(true, r.isMarked(bob), "and it is not cleared")
    }

    @Test
    fun `with no device row at all the guard falls back to the candidates`() = runBlocking {
        candidateIds = listOf(data, hex(0xdb))
        val r = goneReconciler()
        answer = listed(admin)   // lists neither candidate
        assertEquals(emptyList(), assertNotNull(r.reconcileAll("start")).marked)
        answer = listed(admin, data)
        assertEquals(listOf(hex(0xdb)), assertNotNull(r.reconcileAll("start")).marked)
    }

    @Test
    fun `a sentinel is not a user`() = runBlocking {
        login("tv-admin", admin, setOf())
        candidateIds = listOf("__global__", "", "household", "abc", data)
        gone.clear()
        val r = goneReconciler()
        answer = listed(admin)
        assertEquals(listOf(data), assertNotNull(r.reconcileAll("start")).marked)
        assertEquals(false, r.isMarked("__global__")); assertEquals(false, r.isMarked("household"))
        assertEquals(listOf(data), assertNotNull(r.reconcileAll("interval")).gone)
        assertEquals(listOf(data), gone)
    }

    @Test
    fun `a failure while removing leaves the user marked for the next pass`() = runBlocking {
        household()
        var fail = true
        val r = DevicePolicyReconciler(devices, { answer }, {}, onUserGone = { if (fail) error("db locked") else gone += it }, clock = { clock })
        answer = listed(admin, alice)
        r.reconcileAll("start")
        assertEquals(emptyList(), assertNotNull(r.reconcileAll("interval")).gone)
        assertEquals(true, r.isMarked(bob))
        fail = false
        assertEquals(listOf(bob), assertNotNull(r.reconcileAll("interval")).gone)
    }

    @Test
    fun `a burst of connect checks for an absent user costs one read a minute`() = runBlocking {
        household()
        val r = goneReconciler()
        answer = listed(admin, alice)
        assertNotNull(r.reconcileOnConnect(bob))
        assertEquals(1, fetchCount)
        repeat(20) { clock += 1_000L; assertNull(r.reconcileOnConnect(bob)) }
        assertEquals(1, fetchCount, "an absent user is fresh for 60 s too")
        assertEquals(emptyList(), gone)
        clock += 41_000L
        assertNotNull(r.reconcileOnConnect(bob))
        assertEquals(2, fetchCount)
        assertEquals(listOf(bob), gone, "the second read is at least a minute after the first")
    }

    @Test
    fun `a 404 hint on a listed user marks nobody`() = runBlocking {
        household()
        val r = goneReconciler()
        answer = listed(admin, alice, bob)
        r.notFoundHint(bob)
        assertEquals(1, fetchCount)
        assertEquals(false, r.isMarked(bob)); assertEquals(emptyList(), gone)
        r.notFoundHint(bob)
        assertEquals(1, fetchCount, "behind the 60 s rule")
    }

    @Test
    fun `no Jellyfin configured is a neutral read`() = runBlocking {
        household()
        val r = goneReconciler()
        answer = emptyList()
        repeat(3) { assertNotNull(r.reconcileAll("interval")) }
        assertEquals(false, r.isMarked(bob)); assertEquals(emptyList(), gone)
    }
}
