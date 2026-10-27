package dev.jellystructure.ravilo.ui.remote

import dev.jellystructure.ravilo.ui.seams.CastRemoteStatus
import dev.jellystructure.shared.tv.CastTrack
import dev.jellystructure.shared.tv.SessionDetail
import dev.jellystructure.shared.tv.SessionOwner
import dev.jellystructure.shared.tv.SessionTarget
import dev.jellystructure.shared.tv.SessionView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2611 (DEB-1076 amendment 2026-10-27; FR-1, FR-3, FR-10; R371 FR-R371-2) — a song or a book opens the interim music
 * shape: no subtitle control, and R371's volume block (rooms, *Add a speaker…*) on both drivers.
 */
class OneRemoteMusicShapeTest {
    private fun view(kind: String) = SessionView(
        id = "s", revision = 1, owner = SessionOwner("u", "Olivar"), mine = true, kind = kind, title = "Track", subtitle = "Artist",
        target = SessionTarget("cast", "t", "Stue", "speaker"), state = "playing", controllable = true,
    )

    // The file carries a text stream and two audio streams: a film would offer *Audio & Subs* for it.
    private fun detail(v: SessionView, ops: List<String> = listOf("play", "pause", "seek", "stop", "next", "set_audio", "set_subtitle")) = SessionDetail(
        session = v, ops = ops, shuffle = true, repeat = "all",
        audioTracks = listOf(CastTrack(0, "English", "en"), CastTrack(1, "Dansk", "da")),
        subtitleTracks = listOf(CastTrack(2, "English", "en")),
    )

    private val withTracks = CastRemoteStatus(
        title = "Track", loaded = true, playing = true,
        audioTracks = listOf(CastTrack(0, "English", "en"), CastTrack(1, "Dansk", "da")),
        subtitleTracks = listOf(CastTrack(2, "English", "en")),
    )

    private val extras = RemoteExtras(volume = { })

    private fun link(kind: String, detail: SessionDetail?) =
        linkRemote(NoActions, withTracks, false, "Stue", view(kind), detail, extras, linkVolume = null)

    private fun model(kind: String, ops: Set<String>? = null) =
        RemoteModel(sessionId = "s", placeName = "Stue", placeIcon = null, status = withTracks, kind = kind, ops = ops)

    @Test fun `a song and a book offer no tracks control on either driver`() {
        for (kind in listOf("music", "audiobook")) {
            assertFalse(model(kind).offersTracks(), "link driver, $kind")
            assertFalse(model(kind, ops = setOf("set_audio", "set_subtitle")).offersTracks(), "server driver, $kind")
            assertTrue(model(kind).musicShape)
        }
    }

    @Test fun `a film and an episode still offer tracks`() {
        for (kind in listOf("film", "episode")) {
            assertTrue(model(kind).offersTracks(), "link driver, $kind")
            assertTrue(model(kind, ops = setOf("set_audio", "set_subtitle")).offersTracks(), "server driver, $kind")
            assertFalse(model(kind).musicShape)
        }
    }

    @Test fun `a song or a book over the link keeps its kind and gets the volume block`() {
        for (kind in listOf("music", "audiobook")) {
            val v = view(kind)
            val r = link(kind, detail(v))
            assertEquals(kind, r.model.kind)
            assertNotNull(r.sessionVolume, "the rooms and Add a speaker… come with the remote over the link, $kind")
            assertNull(r.linkVolume)
            assertFalse(r.model.offersTracks())
        }
        assertTrue(link("music", detail(view("music"))).model.shuffle)
        assertEquals("all", link("music", detail(view("music"))).model.repeat)
    }

    @Test fun `a song over the link before its detail arrives keeps the computers own slider and no tracks`() {
        val slider: @androidx.compose.runtime.Composable (androidx.compose.ui.Modifier) -> Unit = { }
        val r = linkRemote(NoActions, withTracks, false, "Stue", view("music"), null, extras, linkVolume = slider)
        assertNull(r.sessionVolume)
        assertNotNull(r.linkVolume)
        assertFalse(r.model.offersTracks())
    }

    @Test fun `a film over the link is unchanged - the computers slider and the tracks`() {
        val slider: @androidx.compose.runtime.Composable (androidx.compose.ui.Modifier) -> Unit = { }
        val r = linkRemote(NoActions, withTracks, false, "Stue TV", view("film"), detail(view("film")), extras, linkVolume = slider)
        assertEquals("film", r.model.kind)
        assertNull(r.sessionVolume)
        assertNotNull(r.linkVolume)
        assertTrue(r.model.offersTracks())
    }

    @Test fun `the kind follows what plays and never which driver reached it`() {
        assertEquals("music", remoteKindOf("music"))
        assertEquals("audiobook", remoteKindOf("audiobook", episodeLike = true))
        assertEquals("episode", remoteKindOf("episode"))
        assertEquals("episode", remoteKindOf(null, episodeLike = true))
        assertEquals("film", remoteKindOf("film"))
        assertEquals("film", remoteKindOf(null))
    }

    private object NoActions : RemoteActions {
        override fun play() {}
        override fun pause() {}
        override fun seekTo(positionMs: Long) {}
        override fun next() {}
        override fun stop() {}
        override fun selectAudio(trackId: Long?) {}
        override fun selectSubtitle(trackId: Long?) {}
        override fun nextUpPlay() {}
        override fun nextUpCancel() {}
        override fun subtitleSize(size: Char) {}
        override fun retry() {}
    }
}
