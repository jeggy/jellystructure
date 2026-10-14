package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.createDatabase
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R372 — the ending rules of its *Amendment 2026-10-14* (FR-R372-13, FR-R372-14, DEB-1054), on the injected clock and
 * nothing else: no sleeping, no device, no place.
 *
 * FR-R372-13 — `failed` means a play that never reported, not a position that reads 0. The watchdog's own forced stop
 * writes the tracker's position into the row immediately before the reap, so a session that played and paused reached
 * `onReaped` holding a `0` it never earned and was ended `failed` (measured on prod for one book session: `started`
 * 07:57:38.446, `paused` 07:57:55.600, `ended … failed` 07:59:54.510 with `position_ms = 0` written 1 ms earlier, and
 * no `offline` event at all — 90 s to the millisecond after the last heartbeat for the part left behind).
 *
 * FR-R372-14 — no live session may outlive every clock: a session that is `playing`, not offline, with no load
 * deadline and no stop-hold matched no arm in [PlaybackSessions.tick] and could never end from inside the service.
 */
class SessionEndingTest {
    private lateinit var dbPath: String
    private lateinit var db: JellystructureDb
    private lateinit var devices: RaviloDeviceService
    private var now = 1_000_000L

    @BeforeTest fun setUp() {
        dbPath = "/tmp/jellystructure-test-session-ending-${getpid()}.db"
        db = createDatabase(dbPath)
        devices = RaviloDeviceService(db)
    }

    @AfterTest fun tearDown() {
        dev.jellystructure.db.closeLastDatabaseForTests()
        for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
    }

    private fun service(): PlaybackSessions = PlaybackSessions(db) { now }.also { s ->
        s.bookOfPart = { id -> if (id.startsWith("part-")) "book-1" else null }
        s.describe = { id, bookId ->
            if (bookId != null) SessionKind.AUDIOBOOK to SessionItem(id, "The Long Ferry", "Ingrid Lykke")
            else SessionKind.FILM to SessionItem(id, "Night Train")
        }
    }

    private fun device(id: String, user: String = "u-anna", kind: String = "phone"): DeviceData =
        devices.loginDevice(deviceId = id, deviceName = id.replaceFirstChar { it.uppercase() }, jellyfinUserId = user,
            jellyfinUsername = "Anna", jellyfinUserToken = "jf-$user", isAdmin = false, isKids = false, kind = kind).first

    /**
     * FR-R372-13, acceptance 1, first half — the prod sequence, whose position the forced stop zeroes: the row stays
     * *paused · offline* with no `end_reason`, and takes FR-R372-8's 10 min from that first `offline`.
     */
    @Test fun `a reap of a session that played and paused keeps it offline whatever its position reads`() = runBlocking {
        val s = service()
        val tv = device("living-tv", kind = "tv")
        val id = s.onStart(tv, "part-1", 0, bookId = "book-1")
        s.onProgress(tv, "part-1", 0, paused = false, bookId = "book-1")
        now += 17_000
        s.onProgress(tv, "part-1", 26_038, paused = true, bookId = "book-1")
        now += 119_000
        // The watchdog: its forced stop writes the tracker's position (0 here, as prod's did), then it reaps.
        s.onStop(tv, "part-1", 0)
        s.onReaped(tv, "part-1")
        val r = s.get(id)!!
        assertTrue(r.live, "a session that reported is not ended by a reap")
        assertTrue(r.offline)
        assertEquals(SessionState.PAUSED, r.state)
        assertNull(r.endReason)
        assertTrue(s.timeline(id).any { it.second == "offline" }, "the spell's one offline event is written")
        // FR-R372-8's clock, from that first offline and not before it.
        now += SESSION_OFFLINE_KEEP_MS - 1_000; s.tick()
        assertTrue(s.get(id)!!.live, "9 min 59 s is not yet the 10 min")
        now += 2_000; s.tick()
        assertEquals("offline", s.get(id)!!.endReason)
    }

    /** The same for a film: the rule is not book-specific, and a nonzero position is no longer what decides. */
    @Test fun `a reap of a film that played then paused at zero is offline not failed`() = runBlocking {
        val s = service()
        val mac = device("mac")
        val id = s.onStart(mac, "film-1", 0)
        s.onProgress(mac, "film-1", 4_000, paused = false)
        s.onProgress(mac, "film-1", 0, paused = true)   // a scrub back to the head, then silence
        now += 90_000
        s.onStop(mac, "film-1", 0)
        s.onReaped(mac, "film-1")
        assertTrue(s.get(id)!!.live)
        assertEquals(SessionState.PAUSED, s.get(id)!!.state)
        assertNull(s.get(id)!!.endReason)
    }

    /**
     * FR-R372-13, acceptance 1, second half — the arm DEB-919 added still fires at once for a load that never left
     * `starting`, which is what the 2026-10-09 rule was written for.
     */
    @Test fun `a reap of a session that never left starting still ends failed at once`() = runBlocking {
        val s = service()
        val tv = device("living-tv", kind = "tv")
        val id = s.onStart(tv, "film-1", 0)
        now += 90_000
        s.onStop(tv, "film-1", 0)
        s.onReaped(tv, "film-1")
        val r = s.get(id)!!
        assertFalse(r.live)
        assertEquals("failed", r.endReason)
        assertFalse(r.offline, "it never went offline: it ended")
    }

    /** And a load that reported a position it never moved past still counts as having reported (it is not a failure). */
    @Test fun `a load that managed one playing report at zero waits out the offline window instead of failing`() = runBlocking {
        val s = service()
        val mac = device("mac")
        val id = s.onStart(mac, "film-1", 0)
        s.onProgress(mac, "film-1", 0, paused = false)
        now += 90_000
        s.onStop(mac, "film-1", 0)
        s.onReaped(mac, "film-1")
        assertTrue(s.get(id)!!.live, "a row that lingers beats a row that lies")
        now += SESSION_OFFLINE_KEEP_MS + 1_000; s.tick()
        assertEquals("offline", s.get(id)!!.endReason)
    }

    /** A restart does not erase the fact that a row reported: it comes back `playing`/`paused`, which is the proof. */
    @Test fun `a restored playing row is not reaped as a failed load`() = runBlocking {
        val first = service()
        val mac = device("mac")
        val id = first.onStart(mac, "film-1", 0)
        first.onProgress(mac, "film-1", 600_000, paused = false)
        val after = service()
        assertEquals(listOf(id), after.restore().map { it.id })
        after.onStop(mac, "film-1", 0)
        after.onReaped(mac, "film-1")
        assertTrue(after.get(id)!!.live)
        assertNull(after.get(id)!!.endReason)
    }

    /**
     * FR-R372-14, acceptance 2 — a live `playing` session with no report for 4 h ends `idle`; at 3 h 59 m it is still
     * live. Before the amendment this row matched no arm in [PlaybackSessions.tick] at all: not offline, not paused,
     * no load deadline, no stop-hold — so nothing inside the service could ever end it.
     */
    @Test fun `a playing session silent for four hours ends idle and at 3 h 59 m is still live`() = runBlocking {
        val s = service()
        val tv = device("living-tv", kind = "tv")
        val id = s.onStart(tv, "film-1", 0)
        s.onProgress(tv, "film-1", 1_000, paused = false)
        now += SESSION_PLAYING_SILENT_MS - 60_000; s.tick()
        val live = s.get(id)!!
        assertTrue(live.live, "3 h 59 m of silence is not yet the 4 h")
        assertEquals(SessionState.PLAYING, live.state)
        now += 60_000; s.tick()
        assertEquals("idle", s.get(id)!!.endReason)
        assertEquals(SessionState.ENDED, s.get(id)!!.state)
    }

    /** Nothing is exempt — a `cast` place is exempt from the socket test (FR-218-11), never from this floor. */
    @Test fun `a cast session silent for four hours ends idle too`() = runBlocking {
        val s = service()
        val speaker = device("rx-stue", kind = "cast")
        val id = s.onStart(speaker, "film-1", 0)
        s.onProgress(speaker, "film-1", 2_000, paused = false)
        now += SESSION_PLAYING_SILENT_MS; s.tick()
        assertEquals("idle", s.get(id)!!.endReason)
    }

    /** A session that keeps reporting is never touched by the floor, however long it plays. */
    @Test fun `a session that reports every ten seconds survives past four hours`() = runBlocking {
        val s = service()
        val tv = device("living-tv", kind = "tv")
        val id = s.onStart(tv, "film-1", 0)
        var pos = 0L
        // 4 h 10 m of a 10 s heartbeat, the cadence a real player keeps.
        repeat((SESSION_PLAYING_SILENT_MS + 10 * 60_000L).toInt() / 10_000) {
            now += 10_000; pos += 10_000
            s.onProgress(tv, "film-1", pos, paused = false)
            s.tick()
        }
        assertTrue(s.get(id)!!.live)
        assertEquals(SessionState.PLAYING, s.get(id)!!.state)
    }

    /** The floor is under the whole service, so a `starting` row with no load deadline cannot sit for ever either. */
    @Test fun `a starting row with no load deadline also ends on the floor`() = runBlocking {
        val s = service()
        val tv = device("living-tv", kind = "tv")
        val id = s.onStart(tv, "film-1", 0)   // onStart, not createStarting: no loadDeadline on this row
        assertNull(s.get(id)!!.loadDeadline)
        now += SESSION_PLAYING_SILENT_MS; s.tick()
        assertEquals("idle", s.get(id)!!.endReason)
    }
}
