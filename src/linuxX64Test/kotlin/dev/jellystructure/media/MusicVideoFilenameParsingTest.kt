package dev.jellystructure.media

import kotlin.test.Test
import kotlin.test.assertEquals

/** Phase 168 (FR-168-3) — filename-only artist/title parsing (`parseMusicVideoArtistTitle`). */
class MusicVideoFilenameParsingTest {

    @Test
    fun `Artist - Title splits on the first separator`() {
        val (artist, title) = parseMusicVideoArtistTitle("Artist - Title.mkv")
        assertEquals("Artist", artist)
        assertEquals("Title", title)
    }

    @Test
    fun `no separator leaves artist null and title as the whole filename`() {
        val (artist, title) = parseMusicVideoArtistTitle("JustATitle.mkv")
        assertEquals(null, artist)
        assertEquals("JustATitle", title)
    }

    @Test
    fun `multiple separators split on the first only`() {
        val (artist, title) = parseMusicVideoArtistTitle("Artist - Title - Live Version.mkv")
        assertEquals("Artist", artist)
        assertEquals("Title - Live Version", title)
    }

    @Test
    fun `extension is stripped before parsing`() {
        val (artist, title) = parseMusicVideoArtistTitle("Daft Punk - Around the World.mp4")
        assertEquals("Daft Punk", artist)
        assertEquals("Around the World", title)
    }

    @Test
    fun `blank left side falls back to no artist`() {
        val (artist, title) = parseMusicVideoArtistTitle(" - Title.mkv")
        assertEquals(null, artist)
        assertEquals("Title", title)
    }
}
