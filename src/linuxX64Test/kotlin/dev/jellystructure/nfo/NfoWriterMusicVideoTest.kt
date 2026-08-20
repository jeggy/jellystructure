package dev.jellystructure.nfo

import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Phase 168 (FR-168-4) — the real `<musicvideo>` NFO shape: title + artist only, never a `<tmdbid>`. */
class NfoWriterMusicVideoTest {

    private fun item(title: String, artist: String?) = MediaItem(
        id = "artist-title",
        title = title,
        year = null,
        kind = MediaKind.MUSIC_VIDEO,
        path = "/mnt/musicvideos/$title.mkv",
        tmdbId = null,
        originalLanguage = null,
        posterPath = null,
        overview = null,
        director = artist,
        tracks = emptyList(),
        issueCount = 0,
        scannedAt = 0,
    )

    @Test
    fun `writes title and artist and never a tmdbid block`() {
        val xml = NfoWriter.buildXml(item("Around the World", "Daft Punk"))
        assertTrue(xml.contains("<musicvideo>"))
        assertTrue(xml.contains("<title>Around the World</title>"))
        assertTrue(xml.contains("<artist>Daft Punk</artist>"))
        assertFalse(xml.contains("<tmdbid>"))
        assertFalse(xml.contains("uniqueid"))
    }

    @Test
    fun `no artist parsed from the filename omits the artist tag entirely`() {
        val xml = NfoWriter.buildXml(item("JustATitle", null))
        assertTrue(xml.contains("<title>JustATitle</title>"))
        assertFalse(xml.contains("<artist>"))
    }

    @Test
    fun `nfoPath uses the video's own basename not a fixed musicvideo-nfo name`() {
        val path = NfoWriter.nfoPath(item("Around the World", "Daft Punk"))
        assertEquals("/mnt/musicvideos/Around the World.nfo", path)
    }
}
