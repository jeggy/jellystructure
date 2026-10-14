package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.createDatabase
import dev.jellystructure.shared.tv.EVENTS_FEATURE_BOOK_SEEK
import dev.jellystructure.tv.SessionLoopback.Companion.loopback
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R368 (FR-R368-13) + R369 (FR-R369-9), amendments 2026-10-14, DEB-1099 — a book's session is **seen** in book time,
 * and a book's **seek** is book time or is not offered at all.
 *
 * The bug QA found (DEB-1088 → DEB-1099) was one row read in two clocks: `describeAudiobookPart` fills a part's queue
 * entry with the *book's* duration (right, by R380 FR-R380-12) and `toView` paired it with the row's position, which
 * for a book is the position *in the part* (FR-R368-12 made `item_id` the part). The TV's own screen read ~13% in while
 * the phone's remote read ~3.5% in, against a duration both agreed on.
 *
 * Specs: `BookSessionTimeTest`.
 */
class BookSessionTimeTest {
    private lateinit var dbPath: String
    private lateinit var db: JellystructureDb
    private lateinit var devices: RaviloDeviceService
    private var now = 1_000_000L

    /** The book under test: three parts, 10 min + 15 min + 5 min, so the book runs 30 min. */
    private val parts = listOf("part-1" to 600_000L, "part-2" to 900_000L, "part-3" to 300_000L)

    @BeforeTest fun setUp() {
        dbPath = "/tmp/jellystructure-test-booktime-${getpid()}.db"
        db = createDatabase(dbPath)
        devices = RaviloDeviceService(db)
    }

    @AfterTest fun tearDown() {
        dev.jellystructure.db.closeLastDatabaseForTests()
        for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
    }

    /** The service as the wiring builds it: the store's reverse lookup, the book's parts, and a book's own facts. */
    private fun service(): PlaybackSessions = PlaybackSessions(db) { now }.also { s ->
        s.bookOfPart = { id -> if (id.startsWith("part-")) "book-1" else null }
        // As `SessionWiring` wires it: the row's book, else the part's, and the parts in our order with their lengths.
        s.bookPartsOf = { rec ->
            if (rec.kind != SessionKind.AUDIOBOOK) emptyList()
            else (rec.bookId ?: s.bookOfPart(rec.itemId))?.let { if (it == "book-1") parts else emptyList() }.orEmpty()
        }
        s.describe = { id, bookId ->
            when {
                bookId != null -> SessionKind.AUDIOBOOK to SessionItem(id, "The Long Ferry", "Ingrid Lykke", "/api/tv/image/audiobook/book-1", 1_800_000)
                id.startsWith("song") -> SessionKind.MUSIC to SessionItem(id, "Song $id", "The Lanterns", null, 240_000)
                else -> SessionKind.FILM to SessionItem(id, "Night Train", null, null, 5_400_000)
            }
        }
    }

    private fun device(id: String, user: String = "u-anna", name: String = "Anna", kind: String = "phone"): DeviceData =
        devices.loginDevice(deviceId = id, deviceName = id.replaceFirstChar { it.uppercase() }, jellyfinUserId = user, jellyfinUsername = name,
            jellyfinUserToken = "jf-$user", isAdmin = false, isKids = false, kind = kind).first

    private fun publisherFor(s: PlaybackSessions) = SessionPublisher(s, devices, bus = null, canSee = { _, _, _, _ -> true }, clock = { now })

    private fun rec(kind: String, itemId: String, bookId: String?, positionMs: Long) = SessionRec(
        id = "s1", ownerUserId = "u-anna", ownerName = "Anna", targetKind = "app", targetId = "tv-1", targetName = "Den TV",
        lane = sessionLane(kind), kind = kind, itemId = itemId, bookId = bookId, queue = listOf(SessionItem(itemId, "The Long Ferry", null, null, 1_800_000)),
        queueIndex = 0, positionMs = positionMs, positionAt = now, state = SessionState.PLAYING, options = SessionOptions(),
        revision = 3, startedByDeviceId = "pixel", jellyfinPlaySessionId = null, offline = false, endReason = null, endedBy = null,
        createdAt = now, updatedAt = now, endedAt = null,
    )

    // ── FR-R368-13 — book time in the projection ─────────────────────────────────────────────────────────────────────

    @Test fun `a book on a later part is projected in book time and on the first part is unchanged`() {
        val s = service()
        // A minute into the second part: 10 min of part one before it.
        assertEquals(660_000L, viewPositionMs(rec(SessionKind.AUDIOBOOK, "part-2", "book-1", 60_000), s.bookPartsOf))
        // The third part: 10 + 15 min before it.
        assertEquals(1_500_001L, viewPositionMs(rec(SessionKind.AUDIOBOOK, "part-3", "book-1", 1), s.bookPartsOf))
        // The first part is already book time, as it was before this amendment.
        assertEquals(60_000L, viewPositionMs(rec(SessionKind.AUDIOBOOK, "part-1", "book-1", 60_000), s.bookPartsOf))
    }

    @Test fun `a book row with no book id or a part the book no longer lists keeps the position it has`() {
        val s = service()
        // DEB-1054's ghosts: `kind = audiobook` with `book_id` NULL. The part id resolves the book as the floor...
        assertEquals(660_000L, viewPositionMs(rec(SessionKind.AUDIOBOOK, "part-2", null, 60_000), s.bookPartsOf))
        // ...and when nothing resolves, the row's own position is drawn rather than the call failing.
        assertEquals(60_000L, viewPositionMs(rec(SessionKind.AUDIOBOOK, "gone-1", null, 60_000), s.bookPartsOf))
        assertEquals(60_000L, viewPositionMs(rec(SessionKind.AUDIOBOOK, "part-9", "book-1", 60_000), s.bookPartsOf))
    }

    @Test fun `a film and a song are projected exactly as before`() {
        val s = service()
        assertEquals(60_000L, viewPositionMs(rec(SessionKind.FILM, "film-1", null, 60_000), s.bookPartsOf))
        assertEquals(60_000L, viewPositionMs(rec(SessionKind.MUSIC, "song-1", null, 60_000), s.bookPartsOf))
        // A film's resolver is never asked for parts (a store read a film does not need).
        var asked = 0
        val counting: (SessionRec) -> List<Pair<String, Long>> = { asked++; parts }
        viewPositionMs(rec(SessionKind.FILM, "film-1", null, 60_000), counting)
        assertEquals(0, asked)
    }

    @Test fun `a viewers list and the admins report the same pair for the same book row`() = runBlocking {
        val s = service()
        val publisher = publisherFor(s)
        val phone = device("pixel")
        val id = s.onStart(phone, "part-1", 0, bookId = "book-1")
        s.onProgress(phone, "part-2", 60_000, paused = false, bookId = "book-1")
        assertEquals(60_000L, assertNotNull(s.get(id)).positionMs, "the row keeps part time — every rule above it reads it that way")

        val mine = publisher.listFor(phone).sessions.single()
        assertEquals(660_000L, mine.positionMs)
        assertEquals(1_800_000L, mine.durationMs, "the book's own length, as the queue entry carries it")

        val row = publisher.adminList().sessions.single()
        assertEquals(660_000L, row.positionMs, "304's page drew the part offset against the book's duration")
        assertEquals(1_800_000L, row.durationMs)

        // The detail the remote reads (`GET …/sessions/{id}`) is the same view.
        assertEquals(660_000L, assertNotNull(publisher.detailFor(phone, assertNotNull(s.get(id)))).session.positionMs)
        Unit
    }

    @Test fun `a row rebuilt from the database after a restart still projects book time`() = runBlocking {
        val s = service()
        val phone = device("pixel")
        val id = s.onStart(phone, "part-1", 0, bookId = "book-1")
        s.onProgress(phone, "part-2", 60_000, paused = false, bookId = "book-1")

        // A restart: the row comes back from `playback_session`, its queue from `queue_json`, and no describe has run.
        val restarted = service()
        restarted.restore()
        val back = assertNotNull(restarted.get(id))
        assertEquals(60_000L, back.positionMs)
        assertEquals(660_000L, viewPositionMs(back, restarted.bookPartsOf),
            "the offset is computed where the view is built, never cached in queue_json")
        assertEquals(660_000L, assertNotNull(publisherFor(restarted).listFor(phone).sessions.firstOrNull { it.id == id }).positionMs)
        Unit
    }

    // ── FR-R369-9 — a book's seek is book time, offered only where it is obeyed ───────────────────────────────────────

    @Test fun `seek is offered on a book only to a target that says it obeys book time`() {
        // A book: the op is absent without the token (FR-R369-6 — absent, never greyed) and present with it.
        assertFalse("seek" in opsFor(targetControl = true, music = false, kind = SessionKind.AUDIOBOOK, bookSeek = false))
        assertTrue("seek" in opsFor(targetControl = true, music = false, kind = SessionKind.AUDIOBOOK, bookSeek = true))
        // The legacy carrier is gated the same way: an old build is not offered it through `playstate_command` either.
        assertFalse("seek" in opsFor(targetControl = false, music = false, kind = SessionKind.AUDIOBOOK, bookSeek = false))
        // Every other kind is untouched, with the token and without it.
        for (kind in listOf(SessionKind.FILM, SessionKind.EPISODE, SessionKind.MUSIC)) {
            assertTrue("seek" in opsFor(targetControl = true, music = kind == SessionKind.MUSIC, kind = kind, bookSeek = false), kind)
            assertTrue("seek" in opsFor(targetControl = true, music = kind == SessionKind.MUSIC, kind = kind, bookSeek = true), kind)
            assertTrue("seek" in opsFor(targetControl = false, music = kind == SessionKind.MUSIC, kind = kind, bookSeek = false), kind)
        }
        // Nothing else moves: a book's remote keeps everything it had but the thumb.
        val book = opsFor(targetControl = true, music = false, kind = SessionKind.AUDIOBOOK, bookSeek = false)
        val film = opsFor(targetControl = true, music = false, kind = SessionKind.FILM, bookSeek = false)
        assertEquals((film - "seek").toSet(), book.toSet())
    }

    @Test fun `the ops a book session offers follow the targets own socket`() = loopback("book-seek-ops") {
        val phone = device("phone-a", "u-anna", "phone", "phone", name = "Pixel")
        val plain = device("tv-plain", "u-anna", "tv", "android_tv", name = "Old TV")
        val fresh = device("tv-seek", "u-anna", "tv", "android_tv", name = "New TV")
        open(phone, "sessions,session_control", plays = "video,music,book")
        open(plain, "sessions,session_control", plays = "video,music,book")
        open(fresh, "sessions,session_control,$EVENTS_FEATURE_BOOK_SEEK", plays = "video,music,book")

        assertEquals(200, post("/tv/playback/sessions", """{"target_id":"tv-plain","kind":"book","items":["book-1"],"start_ms":1000}""", phone).status)
        assertEquals(200, post("/tv/playback/sessions", """{"target_id":"tv-seek","kind":"book","items":["book-1"],"start_ms":1000}""", phone).status)
        val old = sessions.all().single { it.live && it.targetId == "tv-plain" }
        val new = sessions.all().single { it.live && it.targetId == "tv-seek" }
        assertFalse("seek" in control.opsOf(old), "an installed build that cannot obey a book-time seek is not offered one")
        assertTrue("seek" in control.opsOf(new))
        // And the command itself is refused on the build that never advertised it, in either direction of the gate.
        assertTrue(control.command(old.id, dev.jellystructure.shared.tv.SessionCommandRequest(op = "seek", positionMs = 900_000, revision = old.revision), fresh(phone), admin = false)
            is CommandResult.NotOffered)
        // A film on that same old build still seeks exactly as it does today (the video lane, beside the book).
        assertEquals(200, post("/tv/playback/sessions", """{"target_id":"tv-plain","kind":"film","items":["film-1"]}""", phone).status)
        assertTrue("seek" in control.opsOf(sessions.all().single { it.live && it.kind == SessionKind.FILM }))
    }

    @Test fun `the predicate behind the offer and behind the command is one`() {
        assertFalse(bookSeekAllowed(SessionKind.AUDIOBOOK, bookSeek = false))
        assertTrue(bookSeekAllowed(SessionKind.AUDIOBOOK, bookSeek = true))
        for (kind in listOf(SessionKind.FILM, SessionKind.EPISODE, SessionKind.MUSIC)) {
            assertTrue(bookSeekAllowed(kind, bookSeek = false), kind)
        }
    }
}
