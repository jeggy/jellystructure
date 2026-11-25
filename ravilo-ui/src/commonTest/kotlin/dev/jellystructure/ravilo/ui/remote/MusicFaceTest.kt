package dev.jellystructure.ravilo.ui.remote

import dev.jellystructure.ravilo.ui.music.MusicPlayerState
import dev.jellystructure.ravilo.ui.screens.remoteFillArtSize
import dev.jellystructure.ravilo.ui.screens.remoteSquareArtSize
import dev.jellystructure.shared.tv.MusicArtistRef
import dev.jellystructure.shared.tv.MusicTrackItem
import dev.jellystructure.shared.tv.SessionDetail
import dev.jellystructure.shared.tv.SessionOwner
import dev.jellystructure.shared.tv.SessionQueueEntry
import dev.jellystructure.shared.tv.SessionTarget
import dev.jellystructure.shared.tv.SessionView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2225 (one-remote FR-3, FR-4) — the pure parts of the music remote: the queue a song's *Up next* lists from either
 * driver, which parts the face says are there, and the cover's and the lyrics' room.
 */
class MusicFaceTest {
    private fun track(n: Int, durationMs: Long? = 200_000L + n) =
        MusicTrackItem(id = "t$n", title = "Song $n", artists = listOf(MusicArtistRef("a1", "Marra Quay")), durationMs = durationMs)

    @Test fun `this apps queue lists the playing song and the songs after it with their place in the queue`() {
        val st = MusicPlayerState(queue = (0..4).map(::track), index = 2)
        val (now, up) = upNextOf(st)
        assertEquals(2, now?.index)
        assertEquals("Song 2", now?.title)
        assertEquals(listOf(3, 4), up.map { it.index })
        assertEquals("Marra Quay", up.first().subtitle)
        assertEquals(200_003L, up.first().durationMs)
    }

    @Test fun `an empty queue lists nothing`() {
        val (now, up) = upNextOf(MusicPlayerState())
        assertNull(now)
        assertTrue(up.isEmpty())
    }

    private fun detail(offset: Int, index: Int, size: Int): SessionDetail {
        val v = SessionView(id = "s", revision = 1, owner = SessionOwner("u", "Olivar"), kind = "music", target = SessionTarget("cast", "t", "Stue", "speaker"), state = "playing")
        return SessionDetail(session = v, queue = (0 until size).map { SessionQueueEntry("q${offset + it}", "Song ${offset + it}", "Artist") }, queueOffset = offset, queueIndex = index, queueSize = 40)
    }

    @Test fun `the servers window gives each row its place in the whole queue`() {
        // The window starts at 10 of a queue of 40; the song playing is the 13th.
        val (now, up) = upNextOf(detail(offset = 10, index = 12, size = 6))
        assertEquals(12, now?.index)
        assertEquals("Song 12", now?.title)
        assertEquals(listOf(13, 14, 15), up.map { it.index })
    }

    @Test fun `a window that does not hold the playing song or has no queue lists nothing`() {
        assertNull(upNextOf(detail(offset = 10, index = 3, size = 6)).first)
        assertNull(upNextOf(detail(offset = 0, index = 0, size = 0)).first)
    }

    @Test fun `the face offers lyrics only on a queue this app holds and only when the song has them`() {
        val withLyrics = MusicFace(track = track(1).copy(hasLyrics = true), local = true)
        assertTrue(withLyrics.hasLyrics)
        assertFalse(withLyrics.copy(local = false).hasLyrics, "a session reached through the server carries no track")
        assertFalse(MusicFace(track = track(1), local = true).hasLyrics)
        assertFalse(MusicFace(local = true).hasLyrics)
    }

    @Test fun `Up next is offered when a song is playing`() {
        assertFalse(MusicFace().hasQueue)
        assertTrue(MusicFace(now = UpNextRow(0, "t0", "Song 0")).hasQueue)
    }

    @Test fun `the state line of a playback on this device says here`() {
        assertEquals("cast.playing_here", hereLineKey(true))
        assertEquals("cast.paused_here", hereLineKey(false))
    }

    @Test fun `the cover is a square no larger than eighty percent of the width and shrinks to the room the controls leave`() {
        assertEquals(320 to 320, remoteSquareArtSize(width = 400, fraction = 0.8f, room = null, minHeight = 72))
        assertEquals(320 to 320, remoteSquareArtSize(400, 0.8f, room = 500, minHeight = 72))
        assertEquals(180 to 180, remoteSquareArtSize(400, 0.8f, room = 180, minHeight = 72))
        assertNull(remoteSquareArtSize(400, 0.8f, room = 60, minHeight = 72), "below the minimum the cover is left out, not drawn as a sliver")
    }

    @Test fun `the lyrics take the whole width and the room that is left`() {
        assertEquals(400 to 260, remoteFillArtSize(400, room = 260, minHeight = 72))
        assertNull(remoteFillArtSize(400, room = 40, minHeight = 72))
        assertNull(remoteFillArtSize(400, room = null, minHeight = 72))
    }
}
