package dev.jellystructure.tv

import dev.jellystructure.model.AudiobookProgress
import dev.jellystructure.shared.tv.kindsPlayed
import dev.jellystructure.shared.tv.playsQuery
import dev.jellystructure.shared.tv.sessionKindOf
import dev.jellystructure.tv.SessionLoopback.Companion.loopback
import dev.jellystructure.tv.SessionLoopback.Companion.waitFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R380 (FR-R380-11 and FR-R380-12, amendment 2026-10-13, DEB-919) — the server's half of *a book plays on the place
 * that offered to play books*. Found on devices (QA-D, DEB-788, Stue TV): the TV advertised `book` and dropped every
 * book handed to it, and QA's own `kind: "book"` became a session in the **video** lane that nothing could have played.
 *
 * FR-R380-17 (amendment 2026-10-14, DEB-1100) — and the server's half of *a book's place of record is the server's*:
 * a book start that carries no place, or a zero, resolves it from that listener's `audiobook_progress` rather than
 * trusting the number the tapping device guessed at.
 *
 * Specs: `BookSessionLoadTest`.
 */
class BookSessionLoadTest {
    @Test fun `every session kind is covered by a plays token and nothing else is`() {
        assertEquals(
            setOf(SessionKind.FILM, SessionKind.EPISODE, SessionKind.MUSIC, SessionKind.AUDIOBOOK),
            kindsPlayed(playsQuery(video = true, music = true, book = true)),
            "a kind no `plays=` token covers can never be offered - or is offered and then dropped",
        )
        assertEquals(setOf(SessionKind.AUDIOBOOK), kindsPlayed("book"))
        assertEquals(setOf(SessionKind.MUSIC), kindsPlayed("music"))
        assertEquals(setOf(SessionKind.FILM, SessionKind.EPISODE), kindsPlayed(null))
    }

    @Test fun `an app's declared kinds come from its socket features and an older build plays video`() {
        assertEquals(setOf(SessionKind.FILM, SessionKind.EPISODE, SessionKind.MUSIC, SessionKind.AUDIOBOOK),
            appKinds(setOf("sessions", "plays:video", "plays:music", "plays:book")))
        assertEquals(setOf(SessionKind.MUSIC), appKinds(setOf("plays:music")))
        assertEquals(setOf(SessionKind.FILM, SessionKind.EPISODE), appKinds(setOf("sessions")))
    }

    @Test fun `the plays token book is read as the audiobook kind and an unknown kind is no kind`() {
        assertEquals(SessionKind.AUDIOBOOK, sessionKindOf("book"))
        assertEquals(SessionKind.AUDIOBOOK, sessionKindOf("audiobook"))
        assertEquals(SessionKind.FILM, sessionKindOf("film"))
        assertEquals("audio", sessionLane(sessionKindOf("book")!!), "QA's `book` used to land in the video lane")
        assertNull(sessionKindOf("podcast"))
    }

    @Test fun `book time is the parts before this one plus the place in it`() {
        val parts = listOf("p1" to 600_000L, "p2" to 900_000L, "p3" to 300_000L)
        assertEquals(60_000L, bookTimeOf(parts, "p1", 60_000L))
        assertEquals(600_000L + 60_000L, bookTimeOf(parts, "p2", 60_000L))
        assertEquals(1_500_000L + 1L, bookTimeOf(parts, "p3", 1L))
        assertEquals(42L, bookTimeOf(parts, "gone", 42L), "a part the book no longer lists keeps its own position")
        assertEquals(0L, bookTimeOf(parts, "p1", -5L))
        assertEquals(7L, bookTimeOf(emptyList(), "p1", 7L))
    }

    @Test fun `a book start names the book on the load and its queue row carries the book's own facts`() = loopback("book-start") {
        val phone = device("phone-a", "u-anna", "phone", "phone", name = "Pixel")
        val tv = device("tv-a", "u-anna", "tv", "android_tv", name = "Den TV")
        open(phone, "sessions,session_control", plays = "video,music,book")
        val ts = open(tv, "sessions,session_control", plays = "video,music,book")
        val r = post("/tv/playback/sessions", """{"target_id":"tv-a","kind":"book","items":["book-1"],"start_ms":2223874}""", phone)
        assertEquals(200, r.status, r.body)
        waitFor("the TV gets the book") { ts.frames("session_load").any { "book-1" in it } }
        val f = ts.frames("session_load").single()
        assertTrue("\"kind\":\"audiobook\"" in f, f)
        assertTrue("\"book_id\":\"book-1\"" in f, f)
        assertTrue("\"book_ms\":2223874" in f, f)
        val s = sessions.all().single { it.live }
        assertEquals("audio", s.lane)
        assertEquals("book-1", s.bookId)
        // FR-R380-12 — not `{"id": …}` alone any more: the row reads as a book while it lasts.
        val row = s.queue.single()
        assertEquals("Book book-1", row.title)
        assertNotNull(row.subtitle)
        assertNotNull(row.artwork)
    }

    // ── FR-R380-17 (DEB-1100) — a book start's place comes from the store, never from the device that tapped ──

    @Test fun `a book start with no place resolves this listeners stored place in book time`() = loopback("book-stored") {
        val phone = device("phone-a", "u-anna", "phone", "phone", name = "Pixel")
        val tv = device("tv-a", "u-anna", "tv", "android_tv", name = "Den TV")
        open(phone, "sessions,session_control", plays = "video,music,book")
        val ts = open(tv, "sessions,session_control", plays = "video,music,book")
        var asked: Pair<String, String>? = null
        // The store already computes book time (`AudiobookProgress.bookPositionMs`): a minute into part 2 of a book
        // whose first part runs ten minutes. The row's own position is the part's, the wire's is the book's.
        starter.bookPlace = { u, b ->
            asked = u to b
            AudiobookProgress("book-1", partIndex = 1, positionMs = 60_000, bookPositionMs = 660_000)
        }
        val r = post("/tv/playback/sessions", """{"target_id":"tv-a","kind":"book","items":["book-1"]}""", phone)
        assertEquals(200, r.status, r.body)
        waitFor("the TV gets the book") { ts.frames("session_load").any { "book-1" in it } }
        assertEquals("u-anna" to "book-1", asked, "the place is read for the session's own owner and that book")
        val f = ts.frames("session_load").single()
        assertTrue("\"book_ms\":660000" in f, f)
        assertEquals(660_000L, sessions.all().single { it.live }.positionMs, "the row's position is resolved too")
    }

    @Test fun `a book start whose place is zero resolves the stored place the same way`() = loopback("book-stored-zero") {
        // A zero is read as absent: the sheet is a book start's only road and never means *restart* there (a genuine
        // *Start over* has written the stored place to 0 and resolves to 0 back through the store), and the engine
        // answers 0 for a book it has loaded but not yet made ready.
        val phone = device("phone-a", "u-anna", "phone", "phone", name = "Pixel")
        val tv = device("tv-a", "u-anna", "tv", "android_tv", name = "Den TV")
        open(phone, "sessions,session_control", plays = "video,music,book")
        val ts = open(tv, "sessions,session_control", plays = "video,music,book")
        starter.bookPlace = { _, _ -> AudiobookProgress("book-1", partIndex = 0, positionMs = 42_000, bookPositionMs = 42_000) }
        assertEquals(200, post("/tv/playback/sessions", """{"target_id":"tv-a","kind":"book","items":["book-1"],"start_ms":0}""", phone).status)
        waitFor("the TV gets the book") { ts.frames("session_load").any { "book-1" in it } }
        assertTrue("\"book_ms\":42000" in ts.frames("session_load").single(), ts.frames("session_load").single())
        assertEquals(42_000L, sessions.all().single { it.live }.positionMs)
    }

    @Test fun `a stored place marked finished resolves to the beginning and not to the end of the book`() = loopback("book-stored-fin") {
        val phone = device("phone-a", "u-anna", "phone", "phone", name = "Pixel")
        val tv = device("tv-a", "u-anna", "tv", "android_tv", name = "Den TV")
        open(phone, "sessions,session_control", plays = "video,music,book")
        val ts = open(tv, "sessions,session_control", plays = "video,music,book")
        starter.bookPlace = { _, _ ->
            AudiobookProgress("book-1", partIndex = 2, positionMs = 290_000, bookPositionMs = 1_790_000, finishedAt = 1_760_000_000L)
        }
        assertEquals(200, post("/tv/playback/sessions", """{"target_id":"tv-a","kind":"book","items":["book-1"]}""", phone).status)
        waitFor("the TV gets the book") { ts.frames("session_load").any { "book-1" in it } }
        val f = ts.frames("session_load").single()
        // Nothing resolved, so nothing is carried - and the app's book arm reads the same `finished` flag off the
        // stored place and lands on 0:00 itself. A finished book never starts at its last five minutes.
        assertTrue("\"book_ms\"" !in f, f)
        assertEquals(0L, sessions.all().single { it.live }.positionMs)
    }

    @Test fun `a book nobody has a stored place in starts at the beginning as it always has`() = loopback("book-stored-none") {
        val phone = device("phone-a", "u-anna", "phone", "phone", name = "Pixel")
        val tv = device("tv-a", "u-anna", "tv", "android_tv", name = "Den TV")
        open(phone, "sessions,session_control", plays = "video,music,book")
        val ts = open(tv, "sessions,session_control", plays = "video,music,book")
        starter.bookPlace = { _, _ -> null }
        assertEquals(200, post("/tv/playback/sessions", """{"target_id":"tv-a","kind":"book","items":["book-1"]}""", phone).status)
        waitFor("the TV gets the book") { ts.frames("session_load").any { "book-1" in it } }
        assertTrue("\"book_ms\"" !in ts.frames("session_load").single(), ts.frames("session_load").single())
        assertEquals(0L, sessions.all().single { it.live }.positionMs)
    }

    @Test fun `a place a client did supply is passed through and the store is not read for it`() = loopback("book-supplied") {
        // FR-R380-17's second deliberate non-goal: filling an *absent* place is what the client documents `null` to
        // mean; overriding a supplied one is the amendment's open owner question and is not done here.
        val phone = device("phone-a", "u-anna", "phone", "phone", name = "Pixel")
        val tv = device("tv-a", "u-anna", "tv", "android_tv", name = "Den TV")
        open(phone, "sessions,session_control", plays = "video,music,book")
        val ts = open(tv, "sessions,session_control", plays = "video,music,book")
        var read = false
        starter.bookPlace = { _, _ -> read = true; AudiobookProgress("book-1", bookPositionMs = 9_999_000) }
        assertEquals(200, post("/tv/playback/sessions", """{"target_id":"tv-a","kind":"book","items":["book-1"],"start_ms":123456}""", phone).status)
        waitFor("the TV gets the book") { ts.frames("session_load").any { "book-1" in it } }
        assertTrue("\"book_ms\":123456" in ts.frames("session_load").single(), ts.frames("session_load").single())
        assertFalse(read, "a start that carried its own place does not ask the store at all")
    }

    @Test fun `a film start with no place is not given a book's stored place`() = loopback("book-stored-film") {
        val phone = device("phone-a", "u-anna", "phone", "phone", name = "Pixel")
        val tv = device("tv-a", "u-anna", "tv", "android_tv", name = "Den TV")
        open(phone, "sessions,session_control", plays = "video,music,book")
        val ts = open(tv, "sessions,session_control", plays = "video,music,book")
        var read = false
        starter.bookPlace = { _, _ -> read = true; AudiobookProgress("film-1", bookPositionMs = 9_999_000) }
        assertEquals(200, post("/tv/playback/sessions", """{"target_id":"tv-a","kind":"film","items":["film-1"]}""", phone).status)
        waitFor("the TV gets the film") { ts.frames("session_load").any { "film-1" in it } }
        val f = ts.frames("session_load").single()
        assertTrue("\"start_ms\":0" in f || "\"start_ms\"" !in f, f)
        assertTrue("\"book_ms\"" !in f, f)
        assertFalse(read, "the store is read on the book arm and nowhere else")
        assertEquals(0L, sessions.all().single { it.live }.positionMs)
    }

    @Test fun `a book moved to another place carries the book and its place in book time`() = loopback("book-move") {
        val phone = device("phone-a", "u-anna", "phone", "phone", name = "Pixel")
        val tv = device("tv-a", "u-anna", "tv", "android_tv", name = "Den TV")
        open(phone, "sessions,session_control", plays = "video,music,book")
        val ts = open(tv, "sessions,session_control", plays = "video,music,book")
        starter.bookParts = { listOf("part-1" to 600_000L, "part-2" to 600_000L) }
        // FR-R380-17's first deliberate non-goal — a move is untouched: it converts the live row's own position, which
        // is authoritative by definition, so a stored place sitting half an hour away changes nothing below.
        starter.bookPlace = { _, _ -> AudiobookProgress("book-1", partIndex = 0, positionMs = 1_800_000, bookPositionMs = 1_800_000) }
        // The phone is a minute into part 2: a live book row's own position is the PART's (281's heartbeat), and the
        // place on the wire is book time - the parts before it included.
        val id = sessions.onStart(fresh(phone), "part-2", 60_000, bookId = "book-1")
        val r = post("/tv/playback/sessions/$id/move", """{"target_id":"tv-a"}""", phone)
        assertEquals(200, r.status, r.body)
        waitFor("the TV gets the book") { ts.frames("session_load").any { "book-1" in it } }
        val f = ts.frames("session_load").single()
        assertTrue("\"kind\":\"audiobook\"" in f, f)
        assertTrue("\"book_id\":\"book-1\"" in f, f)
        assertTrue("\"book_ms\":658000" in f, f)   // 600 000 + 60 000 - R372's 2 s back
    }

    @Test fun `a book is not moved to a place that does not play books`() = loopback("book-move-video") {
        val phone = device("phone-a", "u-anna", "phone", "phone")
        val web = device("web-a", "u-anna", "web", "web", name = "Firefox")
        open(phone, "sessions,session_control", plays = "video,music,book")
        open(web, "sessions,session_control", plays = "video")
        val id = sessions.onStart(fresh(phone), "part-1", 0, bookId = "book-1")
        assertEquals(400, post("/tv/playback/sessions/$id/move", """{"target_id":"web-a"}""", phone).status)
    }

    @Test fun `a kind this server does not know is refused instead of becoming a session nothing can play`() = loopback("book-kind") {
        val phone = device("phone-a", "u-anna", "phone", "phone")
        val tv = device("tv-a", "u-anna", "tv", "android_tv", name = "Den TV")
        open(phone, "sessions,session_control", plays = "video,music,book")
        open(tv, "sessions,session_control", plays = "video,music,book")
        val r = post("/tv/playback/sessions", """{"target_id":"tv-a","kind":"podcast","items":["x-1"]}""", phone)
        assertEquals(400, r.status, r.body)
        assertTrue(sessions.all().none { it.live }, "no session is created for a kind nothing plays")
    }

    @Test fun `a place that refuses a load ends the session at once with the kind in its timeline`() = loopback("book-refuse") {
        val phone = device("phone-a", "u-anna", "phone", "phone")
        val tv = device("tv-a", "u-anna", "tv", "android_tv", name = "Den TV")
        open(phone, "sessions,session_control", plays = "video,music,book")
        val ts = open(tv, "sessions,session_control", plays = "video,music,book")
        assertEquals(200, post("/tv/playback/sessions", """{"target_id":"tv-a","kind":"book","items":["book-1"]}""", phone).status)
        val s = sessions.all().single { it.live }
        assertEquals(SessionState.STARTING, s.state)
        ts.out.send("""{"type":"session_load_failed","id":"${s.id}","kind":"audiobook"}""")
        waitFor("the session ends without waiting for the 10 s watchdog") { sessions.get(s.id)?.live == false }
        assertEquals("failed", sessions.get(s.id)!!.endReason)
        assertTrue(sessions.timeline(s.id).any { it.second == "load_refused" && it.third.second == "audiobook" },
            sessions.timeline(s.id).toString())
    }

    @Test fun `only the session's own place may refuse its load`() = loopback("book-refuse-other") {
        val phone = device("phone-a", "u-anna", "phone", "phone")
        val tv = device("tv-a", "u-anna", "tv", "android_tv", name = "Den TV")
        val ps = open(phone, "sessions,session_control", plays = "video,music,book")
        open(tv, "sessions,session_control", plays = "video,music,book")
        assertEquals(200, post("/tv/playback/sessions", """{"target_id":"tv-a","kind":"book","items":["book-1"]}""", phone).status)
        val s = sessions.all().single { it.live }
        ps.out.send("""{"type":"session_load_failed","id":"${s.id}","kind":"audiobook"}""")
        ps.out.send("""{"type":"attach_session","id":"${s.id}"}""")
        waitFor("the phone's frames were handled") { ps.frames("session_detail").isNotEmpty() }
        assertTrue(sessions.get(s.id)!!.live, "the session stands - the refusal came from somewhere else")
    }
}
