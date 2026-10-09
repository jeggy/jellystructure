package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.shared.tv.SessionRoom
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.createDatabase
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R368 — the session service on a temporary database with a fake clock: starts, joins across a song boundary, the 15 s
 * hold, lane replacement, heartbeats that push nothing, the watchdog, the 60 s linger, and a restart (304a: the
 * timeline it writes).
 */
class PlaybackSessionsTest {
    private lateinit var dbPath: String
    private lateinit var db: JellystructureDb
    private lateinit var devices: RaviloDeviceService
    private var now = 1_000_000L
    private val pushes = mutableListOf<SessionChange>()

    @BeforeTest fun setUp() {
        dbPath = "/tmp/jellystructure-test-sessions-${getpid()}.db"
        db = createDatabase(dbPath)
        devices = RaviloDeviceService(db)
    }

    @AfterTest fun tearDown() {
        dev.jellystructure.db.closeLastDatabaseForTests()
        for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
    }

    private fun service(): PlaybackSessions = PlaybackSessions(db) { now }.also { s ->
        s.notify = { pushes += it }
        s.describe = { id, bookId ->
            when {
                bookId != null -> SessionKind.AUDIOBOOK to SessionItem(id, "The Long Ferry")
                id.startsWith("song") -> SessionKind.MUSIC to SessionItem(id, "Song $id", "The Lanterns · Paper Harbour")
                id.startsWith("ep") -> SessionKind.EPISODE to SessionItem(id, "Little Foxes", "S01E0${id.last()}")
                else -> SessionKind.FILM to SessionItem(id, "Night Train")
            }
        }
    }

    private fun device(id: String, user: String = "u-anna", name: String = "Anna", kind: String = "phone"): DeviceData =
        devices.loginDevice(deviceId = id, deviceName = id.replaceFirstChar { it.uppercase() }, jellyfinUserId = user, jellyfinUsername = name,
            jellyfinUserToken = "jf-$user", isAdmin = false, isKids = false, kind = kind).first

    @Test fun `the first start creates one row with owner target lane and kind`() = runBlocking {
        val s = service()
        val phone = device("pixel")
        val id = s.onStart(phone, "song-1", 0, jellyfinPlaySessionId = "jf-play-1", kindHint = SessionKind.MUSIC)
        val rec = assertNotNull(s.get(id))
        assertEquals("u-anna", rec.ownerUserId)
        assertEquals("pixel", rec.targetId)
        assertEquals("audio", rec.lane)
        assertEquals(SessionKind.MUSIC, rec.kind)
        assertEquals(0, rec.queueIndex)
        assertEquals("pixel", rec.startedByDeviceId)
        assertEquals("Pixel", rec.targetName)
        assertEquals("jf-play-1", rec.jellyfinPlaySessionId)
        assertEquals(listOf<SessionChange>(SessionChange.List), pushes)
        assertEquals("started", s.timeline(id).single().second)
    }

    @Test fun `a song deep in the app's queue keeps its title after the queue report and the next song boundary`() = runBlocking {
        // Found on the Pixel: Play here resumed the queue at its 7th song, the queue report moved the index to 6 while the
        // server held one song, and the row had no title, artist, artwork or length; the next song lost them too.
        val s = service()
        val phone = device("pixel")
        val id = s.onStart(phone, "song-7", 0, kindHint = SessionKind.MUSIC)
        s.onQueueReport(phone, dev.jellystructure.shared.tv.SessionQueueReport(sessionId = id, itemId = "song-7",
            queue = (1..9).map { "song-$it" }, queueIndex = 6))
        assertEquals("Song song-7", s.get(id)?.current?.title)
        assertEquals(id, s.onStart(phone, "song-8", 0, kindHint = SessionKind.MUSIC))
        assertEquals("Song song-8", s.get(id)?.current?.title)
        assertEquals("The Lanterns · Paper Harbour", s.get(id)?.current?.subtitle)
    }

    @Test fun `music cast from a phone ends the phone's paused music session and leaves a film alone`() = runBlocking {
        // Found on the Pixel 9 Pro: after a relaunch the phone held the last song paused; casting it left that session
        // paused beside the speaker's, the same song twice in Playing everywhere.
        val s = service()
        val phone = device("pixel")
        val paused = s.onStart(phone, "song-3", 45_825, kindHint = SessionKind.MUSIC)
        s.onProgress(phone, "song-3", 45_825, paused = true)
        val film = s.onStart(phone, "film-1", 0)
        val speaker = device("rx-guest", kind = "cast")
        s.onReceiverRedeemed(speaker, "pixel", "c-guest", null)
        val cast = s.onStart(speaker, "song-3", 45_825, kindHint = SessionKind.MUSIC)
        assertEquals("replaced", s.get(paused)?.endReason)
        assertTrue(s.get(cast)!!.live)
        assertTrue(s.get(film)!!.live, "another lane is not touched")
    }

    @Test fun `a progress report naming the next song before its start keeps a title`() = runBlocking {
        // Found with the Mac: two nexts through the server, the receiver's progress for the new song came before its
        // start, and the row and the remote's queue had no title for it.
        val s = service()
        val speaker = device("rx-guest", kind = "cast")
        val id = s.onStart(speaker, "song-1", 0, kindHint = SessionKind.MUSIC)
        s.onQueueReport(speaker, dev.jellystructure.shared.tv.SessionQueueReport(sessionId = id, itemId = "song-1", queue = listOf("song-0", "song-1", "song-2"), queueIndex = 1))
        s.onProgress(speaker, "song-2", 1_000, paused = false, sessionId = id)
        assertEquals("Song song-2", s.get(id)?.current?.title)
        assertEquals(id, s.onStart(speaker, "song-2", 1_000, kindHint = SessionKind.MUSIC))
        assertEquals("Song song-2", s.get(id)?.current?.title)
    }

    @Test fun `a failed room add is said on the session and the rooms stay`() = runBlocking {
        val s = service()
        val phone = device("pixel")
        val speaker = device("rx-guest", kind = "cast")
        s.onReceiverRedeemed(speaker, "pixel", "c-guest", null)
        val id = s.onStart(speaker, "song-1", 0, kindHint = SessionKind.MUSIC)
        val guest = SessionRoom("c-guest", "Guest room", 6)
        s.onMembersReport(phone, dev.jellystructure.shared.tv.SessionMembersReport("song-1", listOf(guest), selectable = listOf(SessionRoom("c-stue", "Lounge"))))
        val rev = s.get(id)!!.revision
        s.onMembersReport(phone, dev.jellystructure.shared.tv.SessionMembersReport("song-1", emptyList(), failed = "Kitchen hub"))
        val after = s.get(id)!!
        assertEquals("Kitchen hub", after.roomFailed)
        assertTrue(after.revision > rev, "the remote's press is answered")
        assertEquals(listOf(guest), after.options.rooms)
        assertEquals(listOf("c-stue"), after.options.addable?.map { it.castDeviceId })
    }

    @Test fun `a music start with a book id is an audiobook`() = runBlocking {
        val s = service()
        val id = s.onStart(device("pixel"), "part-1", 0, bookId = "book-1")
        assertEquals(SessionKind.AUDIOBOOK, s.get(id)?.kind)
    }

    @Test fun `a song boundary inside fifteen seconds keeps the session`() = runBlocking {
        val s = service()
        val phone = device("pixel")
        val id = s.onStart(phone, "song-1", 0)
        val rev = s.get(id)!!.revision
        pushes.clear()
        s.onStop(phone, "song-1", 180_000)
        now += 14_000
        s.tick()
        val again = s.onStart(phone, "song-2", 0)
        assertEquals(id, again)
        assertEquals(rev + 1, s.get(id)!!.revision)
        assertEquals("song-2", s.get(id)!!.itemId)
        assertFalse(SessionChange.List in pushes, "a song boundary is a state, never a new list")
    }

    @Test fun `an episode auto-advance stays one session`() = runBlocking {
        val s = service()
        val tv = device("living-tv", kind = "tv")
        val id = s.onStart(tv, "ep-1", 0)
        s.onStop(tv, "ep-1", 1_300_000)
        now += 3_000
        assertEquals(id, s.onStart(tv, "ep-2", 0))
        assertEquals(SessionKind.EPISODE, s.get(id)!!.kind)
    }

    @Test fun `a book's next part stays one session`() = runBlocking {
        val s = service()
        val phone = device("pixel")
        val id = s.onStart(phone, "part-1", 0, bookId = "book-1")
        s.onStop(phone, "part-1", 600_000)
        assertEquals(id, s.onStart(phone, "part-2", 0, bookId = "book-1"))
    }

    @Test fun `a stop with no start for fifteen seconds ends it`() = runBlocking {
        val s = service()
        val phone = device("pixel")
        val id = s.onStart(phone, "film-1", 0)
        pushes.clear()
        s.onStop(phone, "film-1", 60_000)
        now += 14_000; s.tick()
        assertTrue(s.get(id)!!.live)
        now += 2_000; s.tick()
        val ended = s.get(id)!!
        assertEquals(SessionState.ENDED, ended.state)
        assertEquals("stopped", ended.endReason)
        assertEquals(listOf<SessionChange>(SessionChange.List), pushes)
    }

    @Test fun `the last song paused at zero stays paused while it heartbeats`() = runBlocking {
        val s = service()
        val phone = device("pixel")
        val id = s.onStart(phone, "song-9", 0)
        s.onProgress(phone, "song-9", 0, paused = true)
        repeat(10) { now += 10_000; s.onProgress(phone, "song-9", 0, paused = true); s.tick() }
        assertEquals(SessionState.PAUSED, s.get(id)!!.state)
        assertTrue(s.get(id)!!.live)
    }

    @Test fun `another owner on the same receiver ends the first as replaced`() = runBlocking {
        val s = service()
        val speakerAnna = device("speaker-1", kind = "cast")
        val speakerBen = device("speaker-1", user = "u-ben", name = "Ben", kind = "cast")
        val first = s.onStart(speakerAnna, "song-1", 0)
        val second = s.onStart(speakerBen, "song-7", 0)
        assertNotEquals(first, second)
        assertEquals("replaced", s.get(first)!!.endReason)
        assertTrue(s.get(second)!!.live)
    }

    @Test fun `a film and a song on one device are two sessions`() = runBlocking {
        val s = service()
        val mac = device("mac")
        val song = s.onStart(mac, "song-1", 0)
        val film = s.onStart(mac, "film-1", 0)
        assertNotEquals(song, film)
        assertTrue(s.get(song)!!.live && s.get(film)!!.live)
    }

    @Test fun `a missing or unknown session id matches by device and item`() = runBlocking {
        val s = service()
        val phone = device("pixel")
        val id = s.onStart(phone, "film-1", 0)
        s.onProgress(phone, "film-1", 5_000, paused = false, sessionId = "ps-not-there")
        assertEquals(5_000, s.get(id)!!.positionMs)
        assertEquals(SessionState.PLAYING, s.get(id)!!.state)
    }

    @Test fun `heartbeats store the position and push nothing`() = runBlocking {
        val s = service()
        val phone = device("pixel")
        val id = s.onStart(phone, "film-1", 0)
        s.onProgress(phone, "film-1", 0, paused = false)
        pushes.clear()
        val rev = s.get(id)!!.revision
        var pos = 0L
        repeat(10) { now += 10_000; pos += 10_000; s.onProgress(phone, "film-1", pos, paused = false) }
        assertEquals(pos, s.get(id)!!.positionMs)
        assertEquals(rev, s.get(id)!!.revision)
        assertTrue(pushes.isEmpty())
        s.onProgress(phone, "film-1", pos, paused = true)
        assertEquals(listOf<SessionChange>(SessionChange.State(id)), pushes)
        assertEquals(listOf("started", "paused"), s.timeline(id).map { it.second })
    }

    @Test fun `a watchdog reap leaves the session paused and offline`() = runBlocking {
        val s = service()
        val phone = device("pixel")
        val id = s.onStart(phone, "film-1", 0)
        s.onStop(phone, "film-1", 30_000)
        s.onReaped(phone, "film-1")
        val r = s.get(id)!!
        assertTrue(r.live)
        assertTrue(r.offline)
        assertEquals(SessionState.PAUSED, r.state)
        assertEquals(30_000, r.positionMs)
        // A report from the place clears offline.
        s.onProgress(phone, "film-1", 30_000, paused = true)
        assertFalse(s.get(id)!!.offline)
    }

    /** R372 — found live 2026-10-09 (evening, Mac): a failed play stayed *paused · offline* at 0:00 for hours. */
    @Test fun `a watchdog reap of a play that never got past zero ends it as failed`() = runBlocking {
        val s = service()
        val phone = device("pixel")
        val id = s.onStart(phone, "film-1", 0)
        s.onProgress(phone, "film-1", 0, paused = true)
        pushes.clear()
        s.onReaped(phone, "film-1")
        val r = s.get(id)!!
        assertFalse(r.live)
        assertEquals("failed", r.endReason)
        assertEquals(listOf<SessionChange>(SessionChange.List), pushes)
    }

    /** R372 — found live 2026-10-09 (evening): a report already on the wire when the app stopped undid the stop. */
    @Test fun `a paused report that lands after the stop keeps the hold and the session ends`() = runBlocking {
        val s = service()
        val phone = device("mac")
        val id = s.onStart(phone, "film-1", 0)
        s.onProgress(phone, "film-1", 60_000, paused = false)
        s.onStop(phone, "film-1", 61_000)
        now += 1_000
        s.onProgress(phone, "film-1", 61_000, paused = true)
        assertNotNull(s.get(id)!!.stopHoldUntil)
        now += 15_000; s.tick()
        assertEquals("stopped", s.get(id)!!.endReason)
    }

    @Test fun `a playing report after a stop still keeps the session`() = runBlocking {
        val s = service()
        val tv = device("living-tv", kind = "tv")
        val id = s.onStart(tv, "film-1", 0)
        s.onStop(tv, "film-1", 30_000)
        s.onProgress(tv, "film-1", 31_000, paused = false)
        now += 20_000; s.tick()
        assertTrue(s.get(id)!!.live)
    }

    @Test fun `the 24 h sweep ends a paused offline session and a paused one`() = runBlocking {
        val s = service()
        val phone = device("pixel"); val tv = device("living-tv", kind = "tv")
        val off = s.onStart(phone, "film-1", 0)
        s.onProgress(phone, "film-1", 5_000, paused = false)
        s.onReaped(phone, "film-1")
        val paused = s.onStart(tv, "song-1", 0)
        s.onProgress(tv, "song-1", 1_000, paused = true)
        now += SESSION_PAUSED_KEEP_MS - 1_000; s.tick()
        assertTrue(s.get(off)!!.live && s.get(paused)!!.live)
        now += 2_000; s.tick()
        assertEquals("offline", s.get(off)!!.endReason)
        assertEquals("idle", s.get(paused)!!.endReason)
    }

    @Test fun `a move keeps the session id stops the old place and starts the new one`() = runBlocking {
        val s = service()
        val stopped = mutableListOf<String>()
        s.stopPlace = { _, from -> stopped += from }
        val pixel = device("pixel"); val mac = device("mac")
        val id = s.onStart(pixel, "song-1", 0)
        s.onProgress(pixel, "song-1", 50_000, paused = false)
        val moving = s.beginMove(id, "mac", "Mac")!!
        assertEquals("Mac", moving.movingTo)
        assertEquals(id, s.onStart(mac, "song-1", moveStartMs(50_000)))
        val r = s.get(id)!!
        assertEquals("mac", r.targetId)
        assertEquals(48_000, r.positionMs)
        assertEquals(null, r.movingTo)
        assertEquals(listOf("pixel"), stopped)
        // The old place's late report starts nothing.
        s.onProgress(pixel, "song-1", 52_000, paused = false)
        assertEquals(1, s.all().count { it.live })
    }

    @Test fun `a move onto a device ends what else was live in its lane there`() = runBlocking {
        val s = service()
        s.stopPlace = { _, _ -> }
        val pixel = device("pixel"); val tv = device("tv")
        val stale = s.onStart(pixel, "film-1", 0)   // the phone's own copy, never ended (the app was reinstalled)
        val id = s.onStart(tv, "film-1", 150_000)
        s.beginMove(id, "pixel", "Pixel")!!
        assertEquals(id, s.onStart(pixel, "film-1", 148_000))
        assertEquals(false, s.get(stale)!!.live)
        // The next report from the phone lands on the moved session.
        s.onProgress(pixel, "film-1", 160_000, paused = false)
        assertEquals(160_000, s.get(id)!!.positionMs)
    }

    @Test fun `a move onto a speaker from its group takes that speaker's id and name and leaves the group's rooms`() = runBlocking {
        // Found on the Pixel 9 Pro (2026-10-05): Stue + Gæsteværelse, remove Stue. The receiver on Gæsteværelse came up
        // named after the group, and the row kept Stue's Cast device: Play on… listed Stue as busy, Gæsteværelse as free.
        val s = service()
        s.stopPlace = { _, _ -> }
        val phone = device("pixel")
        val stue = device("rx-stue", kind = "cast")
        s.onReceiverRedeemed(stue, "pixel", "c-stue", null)
        val id = s.onStart(stue, "song-1", 0, kindHint = SessionKind.MUSIC)
        s.onMembersReport(phone, dev.jellystructure.shared.tv.SessionMembersReport("song-1",
            listOf(SessionRoom("c-stue", "Lounge"), SessionRoom("c-guest", "Guest room"))))
        assertEquals("Lounge + Guest room", placeName(s.get(id)!!))
        s.beginMove(id, "cast:c-guest", "Guest room")
        val guest = device("rx-guest", kind = "cast").copy(displayName = "Lounge + Guest room")
        s.onReceiverRedeemed(guest, "pixel", "c-guest", id)
        assertEquals(id, s.onStart(guest, "song-1", 40_000, kindHint = SessionKind.MUSIC))
        val r = s.get(id)!!
        assertEquals("rx-guest", r.targetId)
        assertEquals("c-guest", r.castDeviceId)
        assertEquals("Guest room", placeName(r))
        assertTrue(r.options.rooms.isEmpty(), "the old group's rooms stay behind")
        // The link holder's report of the new place's one room names it too.
        s.onMembersReport(phone, dev.jellystructure.shared.tv.SessionMembersReport("song-1", listOf(SessionRoom("c-guest", "Guest room"))))
        assertEquals("Guest room", placeName(s.get(id)!!))
    }

    @Test fun `a cast session keeps its Cast device and link holder across a restart`() = runBlocking {
        // Found on the Pixel 9 Pro (2026-10-05): after a backend deploy, Add a speaker said no phone nearby could reach it.
        val s = service()
        val speaker = device("rx-guest", kind = "cast")
        s.onReceiverRedeemed(speaker, "pixel", "c-guest", null)
        val id = s.onStart(speaker, "song-1", 0, kindHint = SessionKind.MUSIC)
        val after = service()
        after.restore()
        assertEquals("c-guest", after.get(id)!!.castDeviceId)
        assertEquals("pixel", after.castMinterOf("rx-guest"))
        assertEquals("c-guest", after.castDeviceOfReceiver("rx-guest"))
    }

    @Test fun `a move from a speaker to the phone clears the Cast device`() = runBlocking {
        val s = service()
        s.stopPlace = { _, _ -> }
        val phone = device("pixel")
        val stue = device("rx-stue", kind = "cast")
        s.onReceiverRedeemed(stue, "pixel", "c-stue", null)
        val id = s.onStart(stue, "song-1", 0, kindHint = SessionKind.MUSIC)
        s.beginMove(id, "pixel", "Pixel")
        assertEquals(id, s.onStart(phone, "song-1", 10_000, kindHint = SessionKind.MUSIC))
        assertNull(s.get(id)!!.castDeviceId)
    }

    @Test fun `a move with no report in 10 s fails and the old place carries on`() = runBlocking {
        val s = service()
        val pixel = device("pixel")
        val id = s.onStart(pixel, "song-1", 0)
        s.beginMove(id, "mac", "Mac")
        now += 10_001; s.tick()
        val r = s.get(id)!!
        assertEquals("Mac", r.moveFailed)
        assertEquals("pixel", r.targetId)
        assertTrue(r.live)
    }

    @Test fun `the move failure is said for 8 s then the place line comes back`() = runBlocking {
        val s = service()
        val id = s.onStart(device("pixel"), "song-1", 0)
        s.beginMove(id, "mac", "Mac")
        now += 10_001; s.tick()
        now += 7_000; s.tick()
        assertEquals("Mac", s.get(id)!!.moveFailed)
        now += 1_001; s.tick()
        assertNull(s.get(id)!!.moveFailed)
        assertTrue(s.get(id)!!.live)
    }

    @Test fun `every move starts 2 s back and never below 0`() {
        assertEquals(2_888_000, moveStartMs(2_890_000))
        assertEquals(0, moveStartMs(1_500))
    }

    @Test fun `an ended session stays listed for sixty seconds as ended`() = runBlocking {
        val s = service()
        val phone = device("pixel")
        val id = s.onStart(phone, "film-1", 0)
        assertTrue(s.end(id, "stopped"))
        now += 59_000; s.tick()
        assertEquals(SessionState.ENDED, s.get(id)?.state)
        pushes.clear()
        now += 2_000; s.tick()
        assertNull(s.get(id))
        assertEquals(listOf<SessionChange>(SessionChange.List), pushes)
    }

    @Test fun `every live session comes back reconnecting in its last state`() = runBlocking {
        val s = service()
        val phone = device("pixel"); val tv = device("living-tv", kind = "tv")
        val playing = s.onStart(phone, "song-1", 0)
        s.onProgress(phone, "song-1", 40_000, paused = false)
        val paused = s.onStart(tv, "film-1", 0)
        s.onProgress(tv, "film-1", 90_000, paused = true)
        val ended = s.onStart(device("ipad"), "film-2", 0)
        s.end(ended, "stopped")
        now += 120_000   // past the 60 s linger, so the ended row does not come back

        val restarted = service()
        val back = restarted.restore()
        assertEquals(setOf(playing, paused), back.map { it.id }.toSet())
        assertTrue(back.all { it.reconnecting })
        assertEquals(SessionState.PLAYING, restarted.get(playing)!!.state)
        assertEquals(SessionState.PAUSED, restarted.get(paused)!!.state)
        assertEquals(90_000, restarted.get(paused)!!.positionMs)
        assertTrue(restarted.isReconnecting("living-tv"))
    }

    @Test fun `shuffle and start over survive in options`() = runBlocking {
        val s = service()
        val tv = device("living-tv", kind = "tv")
        val plan = SessionPlan(startOverSeriesId = "series-1", durationMs = 1_400_000, shuffle = true, priorPositionMs = 300_000)
        val id = s.onStart(tv, "ep-3", 0, jellyfinPlaySessionId = "jf-play-9", plan = plan, directPlay = true)
        val restarted = service()
        val back = restarted.restore().single { it.id == id }
        assertEquals(plan, back.options.plan())
        assertEquals("jf-play-9", back.jellyfinPlaySessionId)
        assertTrue(back.options.directPlay)
    }

    @Test fun `a report clears reconnecting and pushes one state`() = runBlocking {
        val s = service()
        val tv = device("living-tv", kind = "tv")
        val id = s.onStart(tv, "film-1", 0)
        val restarted = service()
        restarted.restore()
        pushes.clear()
        restarted.onProgress(tv, "film-1", 12_000, paused = false)
        assertFalse(restarted.get(id)!!.reconnecting)
        assertEquals(listOf<SessionChange>(SessionChange.State(id)), pushes)
        assertTrue(restarted.timeline(id).any { it.second == "reconnected" })
    }

    @Test fun `a place silent after a restart is paused and offline for 24 h not ended at 2 min`() = runBlocking {
        val s = service()
        val tv = device("living-tv", kind = "tv")
        val id = s.onStart(tv, "film-1", 0)
        s.onProgress(tv, "film-1", 50_000, paused = false)
        val restarted = service()
        restarted.restore()
        now += 119_000; restarted.tick()
        assertTrue(restarted.get(id)!!.reconnecting)
        now += 2_000; restarted.tick()
        val r = restarted.get(id)!!
        assertEquals(50_000, r.positionMs)
        assertTrue(r.live)
        assertTrue(r.offline)
        assertEquals(SessionState.PAUSED, r.state)
    }

    @Test fun `the hourly tick drops events and ended sessions older than seven days`() = runBlocking {
        val s = service()
        val phone = device("pixel")
        val old = s.onStart(phone, "film-1", 0)
        s.end(old, "stopped")
        now += SESSION_KEEP_MS + 2 * 60 * 60_000L
        val live = s.onStart(phone, "film-2", 0)
        s.tick()
        assertTrue(s.timeline(old).isEmpty())
        assertTrue(s.timeline(live).isNotEmpty())
        assertTrue(s.get(live)!!.live)
    }

    @Test fun `the admin title of a device is the live session's`() = runBlocking {
        val s = service()
        val speaker = device("speaker-1", kind = "cast")
        s.onStart(speaker, "song-1", 0)
        assertEquals("Song song-1 · The Lanterns · Paper Harbour", s.titleOn("speaker-1"))
        assertNull(s.titleOn("nowhere"))
    }
}
