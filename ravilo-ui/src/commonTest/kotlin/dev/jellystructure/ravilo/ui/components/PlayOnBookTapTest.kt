package dev.jellystructure.ravilo.ui.components

import dev.jellystructure.ravilo.ui.sessions.mergeTargets
import dev.jellystructure.ravilo.ui.sessions.playOnTiers
import dev.jellystructure.ravilo.ui.seams.CastRoute
import dev.jellystructure.shared.tv.PlaybackTarget
import dev.jellystructure.shared.tv.SessionOwner
import dev.jellystructure.shared.tv.SessionTarget
import dev.jellystructure.shared.tv.SessionView
import dev.jellystructure.shared.tv.TargetCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R380 (FR-R380-15, DEB-1064) — the app bar's *Play on…* sheet and a loaded book: the kind it asks the four tiers
 * about is the kind that is **loaded**, a book playing here moves instead of starting at 0:00, a book playing nowhere
 * starts in **book time**, and the *"Play something first"* hint is raised in neither book case.
 *
 * The sheet's decision is a pure function of what is loaded and what plays where, so all of it is tested here and
 * none of it needs a device.
 */
class PlayOnBookTapTest {
    private val book = SheetBook("bk-7", bookMs = 571_948L)
    private fun loadedBook(bookMs: Long = 571_948L) = SheetLoaded(music = true, book = SheetBook("bk-7", bookMs))
    private fun loadedQueue() = SheetLoaded(music = true, queue = listOf("tr-1", "tr-2"), index = 1, positionMs = 42_000L)
    private fun loadedFilm() = SheetLoaded(music = false, title = ScreenPlayContext("film-3", 90_000L))

    private fun session(kind: String, here: Boolean, state: String = "playing", id: String = "s1") =
        SessionView(id = id, revision = 4, owner = SessionOwner("u", "Anna"), mine = true, kind = kind, title = "Something",
            target = SessionTarget("app", "d", "Pixel 9 Pro", "phone"), state = state, here = here)

    // ── Obligation 1 — the kind is the kind that is loaded, never the mode ──

    @Test fun `a loaded book makes the sheet a books sheet`() {
        assertEquals("audiobook", sheetKind(music = true, bookLoaded = true))
        assertEquals("music", sheetKind(music = true, bookLoaded = false))
        assertEquals("film", sheetKind(music = false, bookLoaded = false))
        // The video-mode sheet stays about films: a book sitting in the engine never takes the displays away from it.
        assertEquals("film", sheetKind(music = false, bookLoaded = true))
    }

    @Test fun `with a book loaded a Cast speaker and a music-only place are absent from all four tiers`() {
        val rows = mergeTargets(
            listOf(
                target("pixel", "Pixel 9 Pro", "phone", TargetCapabilities(audio = true, book = true), here = true),
                target("tv-stue", "Stue TV", "tv", TargetCapabilities(video = true, audio = true, display = true, book = true)),
                // A place that plays music but no books, and a busy one at that, and an unreachable one.
                target("mac", "Mac", "computer", TargetCapabilities(video = true, audio = true), busy = session("music", here = false, id = "s9")),
                target("old-tv", "Loft TV", "tv", TargetCapabilities(video = true, audio = true), reachable = false),
                // A Cast speaker that declares `book`: owner decision 4 keeps a book off it all the same.
                target("cast:a", "Kitchen", "speaker", TargetCapabilities(audio = true, book = true), castId = "a", kind = "cast"),
            ),
            listOf(CastRoute(id = "r1", name = "Office", selected = false, select = {}, kind = "speaker")),
        )
        val tiers = playOnTiers(rows, sheetKind(music = true, bookLoaded = true))
        assertEquals("pixel", tiers.thisDevice?.id)
        assertEquals(listOf("Stue TV"), tiers.free.map { it.name })
        assertTrue(tiers.playingNow.isEmpty(), "a music-only place is absent, not listed as busy: ${tiers.playingNow.map { it.name }}")
        assertTrue(tiers.unreachable.isEmpty(), "a music-only place is absent from the dimmed tier too: ${tiers.unreachable.map { it.name }}")
        val listed = listOfNotNull(tiers.thisDevice).map { it.name } + (tiers.playingNow + tiers.free + tiers.unreachable).map { it.name }
        assertEquals(listOf("Pixel 9 Pro", "Stue TV"), listed, "a book is offered to a book's places and to nothing else")
        // The same places, asked about music, do list the speakers — the filter is the kind, not a new rule.
        val musicTiers = playOnTiers(rows, sheetKind(music = true, bookLoaded = false))
        assertTrue(musicTiers.free.map { it.name }.containsAll(listOf("Kitchen", "Office")))
    }

    // ── Obligation 2 — a book playing nowhere starts, naming the book, in book time ──

    @Test fun `a loaded book builds an audiobook start naming the book at book time`() {
        val tap = sheetTap(loadedBook(), targetId = "tv-stue", sessions = emptyList())
        val req = (tap as SheetTap.Start).request
        assertEquals("audiobook", req.kind)
        assertEquals(listOf("bk-7"), req.items)
        assertEquals(571_948L, req.startMs)
        assertEquals(0, req.index)
        assertEquals("tv-stue", req.targetId)
        assertNull(req.replace)
    }

    @Test fun `a book the engine has loaded but not made ready starts at this viewers stored place`() {
        val req = (sheetTap(loadedBook(bookMs = 0L), "tv-stue", emptyList()) as SheetTap.Start).request
        assertEquals("audiobook", req.kind)
        assertNull(req.startMs, "0 is sent as null — the server's place — rather than restarting the book on the TV")
    }

    // ── Obligation 3 — a book playing HERE moves; it does not start a second session at 0:00 ──

    @Test fun `a live book session on this device moves instead of starting`() {
        val here = session("audiobook", here = true, id = "s-book")
        val tap = sheetTap(loadedBook(), "tv-stue", listOf(here))
        assertEquals("s-book", (tap as SheetTap.Move).session.id)
    }

    @Test fun `a book session that ended or plays elsewhere starts rather than moves`() {
        assertTrue(sheetTap(loadedBook(), "tv-stue", listOf(session("audiobook", here = true, state = "ended"))) is SheetTap.Start)
        assertTrue(sheetTap(loadedBook(), "tv-stue", listOf(session("audiobook", here = false))) is SheetTap.Start)
    }

    @Test fun `replacing what plays on the tapped place is a start carrying the replace never a move`() {
        val here = session("audiobook", here = true, id = "s-book")
        val busy = session("music", here = false, id = "s-busy")
        val req = (sheetTap(loadedBook(), "tv-stue", listOf(here), replace = busy) as SheetTap.Start).request
        assertEquals("s-busy", req.replace?.sessionId)
        assertEquals(4L, req.replace?.revision)
    }

    @Test fun `a music session playing here is not a book and is not moved by a loaded book`() {
        // One listening queue at a time (R323): a book in the engine with a music session still open here must not
        // hand that music session to the TV and call it the book.
        assertTrue(sheetTap(loadedBook(), "tv-stue", listOf(session("music", here = true))) is SheetTap.Start)
    }

    // ── Obligation 4 — the hint tells the truth ──

    @Test fun `the hint is raised in neither book case`() {
        assertTrue(sheetTap(loadedBook(), "tv-stue", emptyList()) !is SheetTap.NothingLoaded)
        assertTrue(sheetTap(loadedBook(), "tv-stue", listOf(session("audiobook", here = true))) !is SheetTap.NothingLoaded)
    }

    @Test fun `the hint is raised only when nothing at all is loaded`() {
        assertEquals(SheetTap.NothingLoaded, sheetTap(SheetLoaded(music = true), "tv-stue", emptyList()))
        assertEquals(SheetTap.NothingLoaded, sheetTap(SheetLoaded(music = false), "tv-stue", emptyList()))
        assertEquals("screens.nothing_to_start_music", nothingToStartKey(music = true))
    }

    // ── FR-R380-16 (DEB-1090) — a sheet listing something live on THIS device says neither hint ──

    @Test fun `the video sheet with a book playing here raises neither hint`() {
        // The defect QA photographed: the book plays audibly on the phone, the mode is persisted, and the app bar's
        // sheet is opened in video mode from a page with no film — so nothing is startable and the hint used to be
        // raised underneath the sheet's own *Playing everywhere* row for that very book.
        val videoSheet = SheetLoaded(music = false, title = null)
        val tap = sheetTap(videoSheet, "tv-stue", listOf(session("audiobook", here = true, id = "s-book")))
        assertEquals(SheetTap.NothingStartableWhileLiveHere, tap)
        assertTrue(tap !is SheetTap.NothingLoaded, "the hint is driven by the decision, and this is not that branch")
    }

    @Test fun `a live film session on this device suppresses the hint through the same line`() {
        // Written on `here` + live and never on `audiobook`: the film case was always the same false sentence.
        assertEquals(SheetTap.NothingStartableWhileLiveHere,
            sheetTap(SheetLoaded(music = false), "tv-stue", listOf(session("film", here = true))))
        // And in music mode with no queue and no book, for the same reason.
        assertEquals(SheetTap.NothingStartableWhileLiveHere,
            sheetTap(SheetLoaded(music = true), "tv-stue", listOf(session("music", here = true))))
    }

    @Test fun `the true case is kept - nothing loaded and nothing live here still raises the hint`() {
        assertEquals(SheetTap.NothingLoaded, sheetTap(SheetLoaded(music = false), "tv-stue", emptyList()))
        // A live session ELSEWHERE does not suppress it: nothing is loaded here, so the sentence is true (FR-R265-7a).
        assertEquals(SheetTap.NothingLoaded,
            sheetTap(SheetLoaded(music = false), "tv-stue", listOf(session("audiobook", here = false))))
        assertEquals(SheetTap.NothingLoaded,
            sheetTap(SheetLoaded(music = true), "tv-stue", listOf(session("music", here = false))))
        // Nor does a session on this device that has ended.
        assertEquals(SheetTap.NothingLoaded,
            sheetTap(SheetLoaded(music = false), "tv-stue", listOf(session("audiobook", here = true, state = "ended"))))
    }

    // ── What was already there, unchanged ──

    @Test fun `a music queue still starts with its place in the song and its modes`() {
        val req = (sheetTap(loadedQueue().copy(shuffle = true, repeat = "all"), "cast:a", emptyList()) as SheetTap.Start).request
        assertEquals("music", req.kind)
        assertEquals(listOf("tr-1", "tr-2"), req.items)
        assertEquals(1, req.index)
        assertEquals(42_000L, req.startMs)
        assertTrue(req.shuffle)
        assertEquals("all", req.repeat)
    }

    @Test fun `a music queue playing here starts a second session as it always has`() =
        assertTrue(sheetTap(loadedQueue(), "cast:a", listOf(session("music", here = true))) is SheetTap.Start)

    @Test fun `this pages film still starts and still moves when it plays here`() {
        val req = (sheetTap(loadedFilm(), "tv-stue", emptyList()) as SheetTap.Start).request
        assertEquals("film", req.kind)
        assertEquals(listOf("film-3"), req.items)
        assertEquals(90_000L, req.startMs)
        assertEquals("s1", (sheetTap(loadedFilm(), "tv-stue", listOf(session("film", here = true))) as SheetTap.Move).session.id)
        assertEquals("s1", (sheetTap(loadedFilm(), "tv-stue", listOf(session("episode", here = true))) as SheetTap.Move).session.id)
    }

    @Test fun `the join-only sheet moves nothing and starts nothing`() {
        // A page with no film publishes no title, so the film lane is empty and this device's own film session is not
        // handed anywhere. FR-R380-16 only changes which silent answer comes back, never that it is silent.
        val tap = sheetTap(SheetLoaded(music = false), "tv-stue", listOf(session("film", here = true)))
        assertTrue(tap !is SheetTap.Move && tap !is SheetTap.Start, "nothing is moved and nothing is started: $tap")
    }

    @Test fun `a books queue is empty and that is not nothing loaded`() {
        // The shipped gate was `st.queue.isEmpty() || st.book != null` → null → the hint. A book has no queue by
        // construction, so the emptiness of the queue must not decide anything while a book is loaded.
        assertEquals(book.id, ((sheetTap(loadedBook(), "tv-stue", emptyList()) as SheetTap.Start).request).items.single())
    }

    private fun target(id: String, name: String, icon: String, caps: TargetCapabilities, busy: SessionView? = null,
                       reachable: Boolean = true, here: Boolean = false, castId: String? = null, kind: String = "app") =
        PlaybackTarget(id = id, kind = kind, name = name, icon = icon, capabilities = caps, busy = busy,
            reachable = reachable, here = here, castDeviceId = castId)
}
