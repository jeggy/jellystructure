package dev.jellystructure.tv

import dev.jellystructure.ai.AiRequests
import dev.jellystructure.auth.DeviceIdentityRegistry
import dev.jellystructure.auth.SessionService
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.Playback_session
import dev.jellystructure.db.createDatabase
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2675 (DEB-2672, FR-4, FR-5, FR-10, FR-11, FR-12) — on a real database. A user with rows in every table the removal covers, stored as
 * 32 hex digits, as dashed lowercase and as dashed uppercase, loses all of them; the operator's record stays; a sentinel is neither a
 * candidate nor removable.
 */
class GoneUserRemovalTest {
    private lateinit var dbPath: String
    private lateinit var db: JellystructureDb
    private lateinit var devices: RaviloDeviceService
    private lateinit var sessions: SessionService
    private lateinit var removal: GoneUserRemoval
    private var clock = 1_000_000L
    private val dropped = ArrayList<String>()

    @BeforeTest
    fun setUp() {
        dbPath = "/tmp/jellystructure-test-gone-removal-${getpid()}.db"
        db = createDatabase(dbPath)
        devices = RaviloDeviceService(db)
        sessions = SessionService(db)
        DeviceIdentityRegistry.clear()
        dropped.clear()
        removal = GoneUserRemoval(db, devices, sessions, dropCaches = { dropped += it }, clock = { clock })
    }

    @AfterTest
    fun tearDown() {
        for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
    }

    /** 32 hex digits that include letters, so the upper-case dashed form differs from the lower-case one. */
    private fun hex(n: Int) = ("abc" + n.toString(16)).padStart(32, '0')
    private fun dashed(h: String) = "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    private fun forms(h: String) = listOf(h, dashed(h), dashed(h).uppercase())

    /** One row in one table for [id], in the form the caller passes. */
    private val seeders: Map<String, (String) -> Unit> = mapOf(
        "ravilo_device" to { id -> devices.loginDevice("tv-$id", "TV", id, "name-$id", "tok-$id", isAdmin = false, isKids = false) },
        "session" to { id -> sessions.create(id, "name-$id", "jf-$id") },
        "api_key" to { id -> db.apiKeyQueries.insert("key-$id", "k", id, "name-$id", "hash-$id", 1L) },
        "cast_handoff" to { id -> db.castHandoffQueries.insert("code-$id", "dev", id, 1L, 2L) },
        "ravilo_pairing" to { id ->
            db.raviloPairingQueries.insertPairing("pair-$id", "pc-$id", "poll-$id", 9_999_999_999L, "TV")
            db.raviloPairingQueries.approve(id, "name-$id", "tok", 0L, 0L, "pc-$id", 1L)
        },
        "tv_signin_code" to { id ->
            db.tvSigninCodeQueries.insert("sc-$id", "dev", null, null, "secret", 1L, 9_999_999_999L)
            db.tvSigninCodeQueries.approve(id, "tok", null, "sc-$id")
        },
        "ravilo_behaviour" to { id -> db.raviloBehaviourQueries.upsert(id, "{}", 1L) },
        "ravilo_config" to { id -> db.raviloConfigQueries.upsert(id, "{}", 1L) },
        "recommendation" to { id -> db.recommendationQueries.putRecommendation(id, "scope", 0L, "item", 1.0, "r", null, "standard", 1L) },
        "ai_order" to { id -> db.aiQueries.putOrder(id, "scope", 0L, "item", "why", 1L) },
        "suggestion_viewer" to { id -> db.suggestionsQueries.putViewer(id, "scope", 0L, 1L, "{}") },
        "audiobook_progress" to { id -> db.audiobooksQueries.putProgress(id, "book", 0L, 0L, 1.0, 1L, null) },
        "audiobook_bookmark" to { id -> db.audiobooksQueries.addBookmark(id, "book", 5L, null, 1L) },
        "dashboard_visit" to { id -> db.dashboardQueries.mark(id, 1L) },
        "playback_outbox" to { id -> db.playbackOutboxQueries.upsert("dev", id, "jf", 1L, null, 0L, null, "user", null, null, null, 0L, 1L) },
        "ai_viewer" to { id -> db.aiQueries.putViewer(id.lowercase().replace("-", ""), 0L, "admin", 1L, null) },
    )

    private fun candidates() = removal.candidateIds().toSet()

    @Test
    fun `a user in each table is removed whichever form their id is stored in`() = runBlocking {
        var n = 0
        for ((table, seed) in seeders) for (form in forms(hex(0x1000 + ++n))) {
            val key = normalizeGuid(form)
            seed(form)
            assertTrue(key in candidates(), "$table row stored as $form is a candidate before the removal")
            removal.remove(key)   // handed the normalised form; the row is stored in another
            assertFalse(key in candidates(), "$table row stored as $form is gone, and the next pass does not find it")
        }
    }

    @Test
    fun `a user with rows in every table loses all of them and the kept ones stay`() = runBlocking {
        val key = hex(0x2000)
        for (form in forms(key)) seeders.values.forEach { it(form) }   // the three forms side by side, a row each
        db.aiQueries.enqueue(AiRequests.RERANK_JOB, "${dashed(key).uppercase()}|scope", "label", "reason", "system", "{}", 1L)
        db.aiQueries.enqueue(AiRequests.RERANK_JOB, "household", "label", "reason", "system", "{}", 1L)
        db.suggestionsQueries.putDismissal(1L, "t", null, "r", null, "admin", 1L, 0L, "", key)
        playbackSession("s1", key, "Alex")
        db.raviloConfigQueries.upsert("__global__", "{\"g\":1}", 1L)
        val other = hex(0x2001)
        seeders.values.forEach { it(other) }
        sessions.create(other, "other", "jf")
        assertTrue(key in candidates())

        val outcome = removal.remove(key)

        assertEquals(3, outcome.devices); assertEquals(3, outcome.sessions); assertEquals(3, outcome.apiKeys)
        assertTrue(outcome.username.startsWith("name-"), "named from their device row")
        assertFalse(key in candidates(), "nothing of theirs remains in any table the pass reads")
        assertEquals(listOf("household"), db.aiQueries.waitingFor(AiRequests.RERANK_JOB).executeAsList().map { it.subject }, "their queued re-rank went, a job's own key stayed")
        assertTrue(db.apiKeyQueries.listAll().executeAsList().filter { normalizeGuid(it.jellyfin_user_id) == key }.let { it.size == 3 && it.all { k -> k.revoked == 1L } }, "keys are revoked and their rows stay")
        // the operator's record
        assertEquals(key, db.suggestionsQueries.dismissals().executeAsList().single().for_user)
        assertEquals(1, db.playbackSessionQueries.liveOrEndedSince(0L).executeAsList().size)
        // not theirs
        assertEquals("{\"g\":1}", db.raviloConfigQueries.getByUser("__global__").executeAsOne())
        assertTrue(other in candidates(), "another user is untouched")
        assertEquals(1, devices.listByUserAnyForm(other).size)
        assertEquals(listOf(key), dropped, "the in-memory caches were asked to drop them")
    }

    @Test
    fun `a sentinel is never a candidate and is refused`() = runBlocking {
        db.raviloConfigQueries.upsert("__global__", "{}", 1L)
        db.raviloConfigQueries.upsert("household", "{}", 1L)
        val ids = candidates()
        // the query returns them (the table holds them); it is the 32-hex test that keeps them out of a pass
        assertFalse(ids.any { DevicePolicyReconciler.isUserId(it) })
        for (bad in listOf("__global__", "household", "", "abc", hex(1).dropLast(1), hex(1) + "0", "g".repeat(32))) {
            assertFailsWith<IllegalArgumentException>(bad) { removal.remove(bad) }
        }
        assertEquals("{}", db.raviloConfigQueries.getByUser("__global__").executeAsOne())
        assertEquals(emptyList(), dropped)
    }

    @Test
    fun `a revoked key a redeemed grant and the kept tables do not make a candidate`() {
        val key = hex(0x3000)
        db.apiKeyQueries.insert("k1", "k", key, "n", "h1", 1L)
        db.apiKeyQueries.revoke("k1")
        db.castHandoffQueries.insert("c1", "dev", key, 1L, 2L)
        db.castHandoffQueries.markRedeemed(5L, "c1")
        db.suggestionsQueries.putDismissal(1L, "t", null, "r", null, "admin", 1L, 0L, "", key)
        playbackSession("s1", key, "Alex")
        assertFalse(key in candidates())
        db.castHandoffQueries.insert("c2", "dev", key, 1L, 2L)
        assertTrue(key in candidates(), "an unredeemed grant does")
    }

    @Test
    fun `a user with no device row is named from their session their key or a playback`() = runBlocking {
        val withSession = hex(0x4001); val withKey = hex(0x4002); val withPlayback = hex(0x4003); val nobody = hex(0x4004)
        sessions.create(dashed(withSession), "Sess Name", "jf")
        db.apiKeyQueries.insert("k", "k", withKey, "Key Name", "h", 1L)
        playbackSession("s1", withPlayback, "Play Name")
        db.dashboardQueries.mark(nobody, 1L)
        assertEquals("Sess Name", removal.nameFromData(withSession))
        assertEquals("Key Name", removal.nameFromData(withKey))
        assertEquals("Play Name", removal.nameFromData(withPlayback))
        assertNull(removal.nameFromData(nobody))
        // a session or a live key is signed out and a user holding neither only has their data removed
        assertEquals(Triple(0, 1, 0), removal.remove(withSession).let { Triple(it.devices, it.sessions, it.apiKeys) })
        assertEquals(Triple(0, 0, 1), removal.remove(withKey).let { Triple(it.devices, it.sessions, it.apiKeys) })
        assertEquals(DevicePolicyReconciler.anOldAccount(nobody), removal.remove(nobody).username)
        // …and only a user the page lists (a device or an admin session) joins removed_users
        assertEquals(listOf("Sess Name"), removal.removedUsers().map { it.username })
    }

    @Test
    fun `removed users are kept seven days`() = runBlocking {
        val key = hex(0x5000)
        devices.loginDevice("tv-1", "TV", dashed(key), "Alex", "tok", isAdmin = false, isKids = false)
        removal.remove(key)
        assertEquals(listOf(GoneUserRemoval.Removed("Alex", clock, 1)), removal.removedUsers())
        clock += GoneUserRemoval.REMOVED_KEEP_MS + 1
        assertEquals(emptyList(), removal.removedUsers())
    }

    @Test
    fun `sign out everywhere finds rows in either form and counts them`() {
        val key = hex(0x6000)
        devices.loginDevice("tv-1", "TV", dashed(key).uppercase(), "Alex", "tok1", isAdmin = false, isKids = false)
        devices.loginDevice("tv-2", "TV", key, "Alex", "tok2", isAdmin = false, isKids = false)
        sessions.create(dashed(key), "Alex", "jf")
        assertEquals(2, devices.listByUserAnyForm(dashed(key)).size)
        assertEquals(2, devices.deleteAllForUser(dashed(key)))
        assertEquals(1, sessions.revokeAllForUser(key.uppercase()))
        assertEquals(0, devices.deleteAllForUser(key), "0 is how a caller tells that no row matched")
    }

    private fun playbackSession(id: String, owner: String, name: String) = db.playbackSessionQueries.upsert(
        Playback_session(
            id = id, owner_user_id = owner, owner_name = name, target_kind = "device", target_id = "d", target_name = "", lane = "video",
            kind = "film", item_id = "", book_id = null, queue_json = "[]", queue_index = 0L, position_ms = 0L, position_at = 0L,
            state = "playing", options_json = "{}", revision = 1L, started_by_device_id = null, jellyfin_play_session_id = null,
            offline = 0L, end_reason = null, ended_by = null, created_at = 1L, updated_at = 1L, ended_at = null,
        )
    )
}
