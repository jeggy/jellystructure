package dev.jellystructure.ravilo.ui.music

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-2305 (FR-R337-6 amendment) — the desktop bar's rule has no page term, so Playing gets the bar like any page. */
class DesktopBarOnPlayingTest {
    @Test fun music_mode_shows_the_bar() =
        assertTrue(deskMusicBarVisible(framed = true, musicActive = true, sessionOverMusic = false, inMusic = true, playing = false, keptOnThisPage = false))

    @Test fun films_mode_while_music_plays_shows_the_bar() =
        assertTrue(deskMusicBarVisible(framed = true, musicActive = true, sessionOverMusic = false, inMusic = false, playing = true, keptOnThisPage = false))

    @Test fun films_mode_paused_and_not_kept_hides_the_bar() =
        assertFalse(deskMusicBarVisible(framed = true, musicActive = true, sessionOverMusic = false, inMusic = false, playing = false, keptOnThisPage = false))

    @Test fun films_mode_paused_and_kept_on_this_page_shows_the_bar() =
        assertTrue(deskMusicBarVisible(framed = true, musicActive = true, sessionOverMusic = false, inMusic = false, playing = false, keptOnThisPage = true))

    @Test fun a_session_over_music_hides_the_bar() =
        assertFalse(deskMusicBarVisible(framed = true, musicActive = true, sessionOverMusic = true, inMusic = true, playing = true, keptOnThisPage = true))

    @Test fun an_unframed_page_hides_the_bar() =
        assertFalse(deskMusicBarVisible(framed = false, musicActive = true, sessionOverMusic = false, inMusic = true, playing = true, keptOnThisPage = true))
}
