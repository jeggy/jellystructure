package dev.jellystructure.nfo

import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Phase 168 (FR-168-4) → Phase 171 — the real `<musicvideo>` NFO shape: `<title>`/`<artist>` always
 *  come from the filename parse; `<tmdbid>` etc. appear only when Phase 171's TMDB match found one. */
class NfoWriterMusicVideoTest {

    private fun item(title: String, artist: String?, tmdbId: Int? = null) = MediaItem(
        id = "artist-title",
        title = title,
        year = null,
        kind = MediaKind.MUSIC_VIDEO,
        path = "/mnt/musicvideos/$title.mkv",
        tmdbId = tmdbId,
        originalLanguage = null,
        posterPath = null,
        overview = null,
        director = artist,
        tracks = emptyList(),
        issueCount = 0,
        scannedAt = 0,
    )

    @Test
    fun `unmatched writes title and artist and no tmdbid block`() {
        val xml = NfoWriter.buildXml(item("Around the World", "Daft Punk"))
        assertTrue(xml.contains("<musicvideo>"))
        assertTrue(xml.contains("<title>Around the World</title>"))
        assertTrue(xml.contains("<artist>Daft Punk</artist>"))
        assertFalse(xml.contains("<tmdbid>"))
        assertFalse(xml.contains("uniqueid"))
    }

    @Test
    fun `a real TMDB match writes the tmdbid block and keeps the filename-parsed artist`() {
        val xml = NfoWriter.buildXml(item("HAARP - Live from Wembley Stadium", "Muse", tmdbId = 25352))
        assertTrue(xml.contains("<tmdbid>25352</tmdbid>"))
        assertTrue(xml.contains("""<uniqueid type="tmdb" default="true">25352</uniqueid>"""))
        assertTrue(xml.contains("<artist>Muse</artist>"))
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
