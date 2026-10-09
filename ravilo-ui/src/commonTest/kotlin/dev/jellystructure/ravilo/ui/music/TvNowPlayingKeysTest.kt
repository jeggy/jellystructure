package dev.jellystructure.ravilo.ui.music

import kotlin.test.Test
import kotlin.test.assertEquals

/** R380 (FR-R380-4) — the owner's TV-remote rules on the TV's Now playing, as a pure key map. */
class TvNowPlayingKeysTest {
    @Test fun `OK and Play-Pause toggle, Play and Pause are what they say, Stop stops`() {
        assertEquals(TvMusicAction.Toggle, tvMusicAction(TvMusicKey.OK, book = false))
        assertEquals(TvMusicAction.Toggle, tvMusicAction(TvMusicKey.PLAY_PAUSE, book = false))
        assertEquals(TvMusicAction.Play, tvMusicAction(TvMusicKey.PLAY, book = false))
        assertEquals(TvMusicAction.Pause, tvMusicAction(TvMusicKey.PAUSE, book = false))
        assertEquals(TvMusicAction.Stop, tvMusicAction(TvMusicKey.STOP, book = true))
    }

    @Test fun `left and right are songs for music and 30 s for a book`() {
        assertEquals(TvMusicAction.Previous, tvMusicAction(TvMusicKey.LEFT, book = false))
        assertEquals(TvMusicAction.Next, tvMusicAction(TvMusicKey.RIGHT, book = false))
        assertEquals(TvMusicAction.SkipBook(-30_000L), tvMusicAction(TvMusicKey.LEFT, book = true))
        assertEquals(TvMusicAction.SkipBook(30_000L), tvMusicAction(TvMusicKey.RIGHT, book = true))
    }

    @Test fun `a held arrow seeks 10 s`() {
        assertEquals(TvMusicAction.Seek(-10_000L), tvMusicAction(TvMusicKey.LEFT_HOLD, book = false))
        assertEquals(TvMusicAction.Seek(10_000L), tvMusicAction(TvMusicKey.RIGHT_HOLD, book = true))
    }

    @Test fun `down is lyrics, up the queue, Back only hides`() {
        assertEquals(TvMusicAction.Lyrics, tvMusicAction(TvMusicKey.DOWN, book = false))
        assertEquals(TvMusicAction.Queue, tvMusicAction(TvMusicKey.UP, book = false))
        assertEquals(TvMusicAction.Hide, tvMusicAction(TvMusicKey.BACK, book = false))
    }

    @Test fun `the down hint names what down will do`() {
        assertEquals("tvmusic.lyrics_on", lyricsHintKey(lyricsOn = false))
        assertEquals("tvmusic.lyrics_off", lyricsHintKey(lyricsOn = true))
    }

    @Test fun `Playing from names the sender's device only when it sent one`() {
        assertEquals(mapOf("device" to "Pixel 9 Pro"), fromLineArgs("Pixel 9 Pro"))
        assertEquals(null, fromLineArgs(null))
        assertEquals(null, fromLineArgs("   "))
    }

    @Test fun `the queue heading names the album a cast came from, else just Up next`() {
        assertEquals("tvmusic.up_next_from" to mapOf("x" to "Kite Weather"), upNextHeading(MusicContext("queue", "Kite Weather")))
        assertEquals("tvmusic.up_next_from" to mapOf("x" to "Harbour Lights"), upNextHeading(MusicContext("artist", "Harbour Lights", "a1")))
        assertEquals("music.up_next" to emptyMap(), upNextHeading(MusicContext("queue", "")))
        assertEquals("music.up_next" to emptyMap(), upNextHeading(MusicContext("played", "Recently played")))
        assertEquals("music.up_next" to emptyMap(), upNextHeading(MusicContext("search", "kite")))
        assertEquals("music.up_next" to emptyMap(), upNextHeading(null))
    }
}
