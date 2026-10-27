package dev.jellystructure.ravilo.ui.remote

import dev.jellystructure.shared.tv.CastTrack
import dev.jellystructure.shared.tv.SessionDetail
import dev.jellystructure.shared.tv.SessionOwner
import dev.jellystructure.shared.tv.SessionTarget
import dev.jellystructure.shared.tv.SessionView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-2185 (FR-1, FR-5, FR-8) — the server driver reads as the same status the link reports, and picks who holds the link. */
class SessionSenderTest {
    private fun view(
        id: String = "s", kind: String = "film", state: String = "playing", mine: Boolean = true, controllable: Boolean = true,
        place: String = "Stue TV", here: Boolean = false, createdAt: Long = 1,
    ) = SessionView(
        id = id, revision = 1, owner = SessionOwner("u", "Olivar"), mine = mine, kind = kind, title = "Slack Tide", subtitle = "S01E04",
        artwork = "/art.jpg", target = SessionTarget("cast", "t", place, "tv"), state = state, positionMs = 1_000, positionAt = 10_000,
        durationMs = 100_000, here = here, controllable = controllable, createdAt = createdAt,
    )

    private fun detail(v: SessionView, ops: List<String> = listOf("play", "pause", "seek", "stop", "next")) = SessionDetail(
        session = v, ops = ops, volume = 37,
        audioTracks = listOf(CastTrack(0, "English", "en"), CastTrack(3, "Dansk", "da")),
        subtitleTracks = listOf(CastTrack(1, "English", "en")),
        audioIndex = 3, subtitleIndex = null,
    )

    @Test fun `a playing film reads as a playing status with the position drawn on the servers clock`() {
        val s = SessionSender.status(view(), null, serverNowMs = 20_000, receivedAtMs = 500, nowMs = 1_500)
        assertTrue(s.playing && s.loaded && !s.ended && !s.failed)
        assertEquals("Slack Tide", s.title)
        // 1 000 at 10 000; the server clock is 20 000 at receipt and 1 s more has passed on this device.
        assertEquals(1_000L + 10_000L + 1_000L, s.positionMs)
        assertEquals(100_000L, s.durationMs)
    }

    @Test fun `an episodes number is its kicker and a films is none`() {
        assertEquals("S01E04", SessionSender.status(view(kind = "episode"), null, 0, 0, 0).kicker)
        assertNull(SessionSender.status(view(kind = "film"), null, 0, 0, 0).kicker)
    }

    @Test fun `a paused or ended session is not playing and an ended one has nothing loaded`() {
        assertFalse(SessionSender.status(view(state = "paused"), null, 0, 0, 0).playing)
        val ended = SessionSender.status(view(state = "ended"), null, 0, 0, 0)
        assertTrue(ended.ended && !ended.loaded)
        assertTrue(SessionSender.status(view(state = "failed"), null, 0, 0, 0).failed)
        assertTrue(SessionSender.status(view(state = "starting"), null, 0, 0, 0).buffering)
    }

    @Test fun `the tracks carry their index as the id the sheet picks them by and the picks are positions`() {
        val v = view()
        val s = SessionSender.status(v, detail(v), 0, 0, 0)
        assertEquals(listOf(0L, 3L), s.audioTracks.map { it.trackId })
        assertEquals(1, s.selectedAudio)   // Dansk, index 3, is the second entry
        assertEquals(-1, s.selectedSub)    // none picked
        assertTrue(s.hasNext)
    }

    @Test fun `next exists only where the place obeys it`() {
        val v = view()
        assertFalse(SessionSender.status(v, detail(v, ops = listOf("play", "pause")), 0, 0, 0).hasNext)
    }

    @Test fun `the link is the places when its name is the places`() {
        assertTrue(holdsLinkTo(view(place = "Stue TV"), " stue tv "))
        assertFalse(holdsLinkTo(view(place = "Stue TV"), "Soveværelse TV"))
        assertFalse(holdsLinkTo(view(), null))
        assertFalse(holdsLinkTo(null, "Stue TV"))
    }

    @Test fun `the row of the cast this app holds is the viewers own live playback there and the newest`() {
        val rows = listOf(
            view(id = "old", createdAt = 1), view(id = "new", createdAt = 5), view(id = "theirs", mine = false, createdAt = 9),
            view(id = "ended", state = "ended", createdAt = 8),
            view(id = "here", here = true, createdAt = 6), view(id = "other", place = "Kontor", createdAt = 4),
        )
        assertEquals("new", rowForLink(rows, "Stue TV")?.id)
        assertNull(rowForLink(rows, null))
        assertNull(rowForLink(rows, "Gæsteværelse"))
        // DEB-2611 — a song cast from this app is the cast it holds; a newer song beats an older film at the same place.
        assertEquals("song", rowForLink(rows + view(id = "song", kind = "music", createdAt = 7), "Stue TV")?.id)
        assertEquals("book", rowForLink(rows + view(id = "book", kind = "audiobook", createdAt = 7), "Stue TV")?.id)
    }

    @Test fun `a songs artist and a books chapter are the kicker the server driver draws under the title`() {
        assertEquals("S01E04", SessionSender.status(view(kind = "music"), null, 0, 0, 0).kicker)
        assertEquals("S01E04", SessionSender.status(view(kind = "audiobook"), null, 0, 0, 0).kicker)
    }

    @Test fun `someone elses playback is view-only only while the server says this app may not control it`() {
        assertTrue(viewOnlyOf(view(mine = false, controllable = false)))
        assertFalse(viewOnlyOf(view(mine = false, controllable = true)))
        assertFalse(viewOnlyOf(view(mine = true, controllable = false)))
        assertFalse(viewOnlyOf(view(mine = false, controllable = false, state = "ended")))
    }

    @Test fun `a control is offered when the model lists no ops or lists it`() {
        val none = RemoteModel(null, "p", null, dev.jellystructure.ravilo.ui.seams.CastRemoteStatus(), "film")
        assertTrue(none.offers("anything"))
        val some = none.copy(ops = setOf("play"))
        assertTrue(some.offers("play"))
        assertFalse(some.offers("stop"))
    }
}
