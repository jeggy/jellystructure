package dev.jellystructure.ravilo.ui.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-4072 (DEB-3995 FR-16) — the ticket an offline play is handed is built from what is on the phone, and claims nothing it cannot do. */
class LocalPlayTest {
    private val sub = SavedSubtitleFile(2, "da", "Dansk", forced = false, isDefault = true, url = "file:///data/sub-2.vtt")

    @Test fun `an original is a direct play of the saved stream with the saved text subtitles`() {
        val t = buildLocalTicket("m1", SavedDownload("file", "https://srv/Videos/m1/stream?x=1", listOf(sub)), 42_000)
        assertTrue(t.directPlay)
        assertEquals("https://srv/Videos/m1/stream?x=1", t.hlsUrl)
        assertEquals(42_000L, t.startPositionMs)
        assertEquals(listOf("da"), t.subtitles.map { it.language })
        assertEquals("file:///data/sub-2.vtt", t.subtitles.single().url)
        // The player's own tracks stand: the ticket lists no audio of its own, so every audio track the file carries is offered.
        assertTrue(t.audio.isEmpty())
    }

    @Test fun `a copy is an adaptive-free HLS play that invites no restream`() {
        val t = buildLocalTicket("e1", SavedDownload("hls", "https://srv/api/tv/stream/dl-9/master.m3u8", emptyList()), 0)
        assertEquals(false, t.directPlay)
        assertNull(t.encoder)   // not "ours": nothing here can ask the encoder for another stream
        assertTrue(t.subtitles.isEmpty())
    }

    @Test fun `a negative resume position is the start`() =
        assertEquals(0L, buildLocalTicket("m1", SavedDownload("file", "u", emptyList()), -5).startPositionMs)

    @Test fun `an episode's kicker is the shipped S01E03 form, a film has none`() {
        assertEquals("S01E03", downloadKicker(DownloadRecord("e", "episode", "T", season = 1, episode = 3, quality = "original")))
        assertNull(downloadKicker(DownloadRecord("m", "movie", "T", quality = "original")))
        assertNull(downloadKicker(DownloadRecord("e", "episode", "T", quality = "original")))
    }
}
