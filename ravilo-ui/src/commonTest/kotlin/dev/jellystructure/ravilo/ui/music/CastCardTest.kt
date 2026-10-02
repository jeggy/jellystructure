package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.ravilo.ui.seams.CastLinkState
import dev.jellystructure.ravilo.ui.seams.CastRemoteStatus
import dev.jellystructure.shared.tv.CastTrackItem
import dev.jellystructure.shared.tv.MusicArtistRef
import dev.jellystructure.shared.tv.MusicTrackItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R356 (FR-R356-1/4) — Ravilo's media card while casting: one per song, and the song's own album cover. */
class CastCardTest {
    private val songs = listOf(
        MusicTrackItem(id = "s1", title = "First", albumId = "al1", album = "Album One", artists = listOf(MusicArtistRef("ar", "Band")), durationMs = 200_000, imageUrl = "/api/tv/image/music/album/al1?v=1"),
        MusicTrackItem(id = "s2", title = "Second", albumId = "al2", album = "Album Two", artists = listOf(MusicArtistRef("ar", "Band")), durationMs = 180_000, imageUrl = "/api/tv/image/music/album/al2?v=7"),
    )
    private fun live(index: Int) = CastRemoteStatus(
        itemId = songs[index].id, title = songs[index].title, loaded = true, playing = true, positionMs = 12_000, durationMs = 0,
        music = true, queue = songs.map { CastTrackItem(id = it.id, title = it.title) }, queueIndex = index,
    )
    private fun page(index: Int) = MusicPlayerState(queue = songs, index = index, playing = true)

    @Test
    fun `a song's card is the Playing page's song, with its own album's cover`() {
        val first = castCard(CastLinkState.CONNECTED, live(0), 0.08, page(0))!!
        assertEquals("s1", first.key); assertEquals("First", first.title); assertEquals("Band", first.subtitle)
        assertEquals("/api/tv/image/music/album/al1?v=1", first.artUrl); assertEquals(200_000, first.durationMs, "the song's length while the device says none")
        assertTrue(first.music); assertTrue(first.hasNext); assertEquals(0.08, first.volume)
        val second = castCard(CastLinkState.CONNECTED, live(1), 0.08, page(1))!!
        assertEquals("/api/tv/image/music/album/al2?v=7", second.artUrl, "each song brings its own album's cover")
        assertNotEquals(first.key, second.key, "a new song is a new media item, so the platform reloads the artwork")
        assertFalse(second.hasNext)
    }

    @Test
    fun `no card without a live cast`() {
        assertNull(castCard(CastLinkState.RECONNECTING, live(0), null, page(0)))
        assertNull(castCard(CastLinkState.CONNECTED, null, null, page(0)))
        assertNull(castCard(CastLinkState.CONNECTED, live(0).copy(ended = true), null, page(0)), "the queue played out: the phone's own player again")
        assertNull(castCard(CastLinkState.CONNECTED, live(0).copy(failed = true), null, page(0)))
        assertNull(castCard(CastLinkState.CONNECTED, live(0).copy(loaded = false), null, page(0)), "stopped on the device")
        assertNull(castCard(CastLinkState.CONNECTED, live(0), null, MusicPlayerState()), "nothing to name yet")
    }

    @Test
    fun `a film's card is the receiver's report`() {
        val film = CastRemoteStatus(itemId = "f1", title = "A Film", kicker = "2019", artUrl = "https://x/art.jpg", loaded = true, playing = false, positionMs = 61_000, durationMs = 5_400_000, hasNext = false)
        val c = castCard(CastLinkState.CONNECTED, film, null, null)!!
        assertEquals("f1", c.key); assertEquals("2019", c.subtitle); assertEquals("https://x/art.jpg", c.artUrl)
        assertFalse(c.music); assertFalse(c.playing); assertEquals(61_000, c.positionMs)
    }
}
