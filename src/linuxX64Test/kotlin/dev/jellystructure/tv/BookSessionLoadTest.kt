package dev.jellystructure.tv

import dev.jellystructure.shared.tv.kindsPlayed
import dev.jellystructure.shared.tv.playsQuery
import dev.jellystructure.shared.tv.sessionKindOf
import dev.jellystructure.tv.SessionLoopback.Companion.loopback
import dev.jellystructure.tv.SessionLoopback.Companion.waitFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R380 (FR-R380-11 and FR-R380-12, amendment 2026-10-13, DEB-919) — the server's half of *a book plays on the place
 * that offered to play books*. Found on devices (QA-D, DEB-788, Stue TV): the TV advertised `book` and dropped every
 * book handed to it, and QA's own `kind: "book"` became a session in the **video** lane that nothing could have played.
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

    @Test fun `a book moved to another place carries the book and its place in book time`() = loopback("book-move") {
        val phone = device("phone-a", "u-anna", "phone", "phone", name = "Pixel")
        val tv = device("tv-a", "u-anna", "tv", "android_tv", name = "Den TV")
        open(phone, "sessions,session_control", plays = "video,music,book")
        val ts = open(tv, "sessions,session_control", plays = "video,music,book")
        starter.bookParts = { listOf("part-1" to 600_000L, "part-2" to 600_000L) }
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
