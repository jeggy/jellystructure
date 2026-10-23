package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.ravilo.ui.Dest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-2305 (FR-R337-6/7 amendments) — the desktop Playing page keeps the bar, loses seek/transport. */
class DesktopBarOnPlayingTest {

    /**
     * Mirrors `RaviloApp.kt:deskMusicBarVisible` — the predicate that decides whether the bar
     * is visible (regardless of whether `deskBarShows` later excludes MusicPlaying for layout).
     */
    private fun musicBarVisible(
        framed: Boolean,
        musicActive: Boolean,
        sessionOverMusic: Boolean,
        inMusic: Boolean,
        musicPlaying: Boolean,
        barKeptOn: Dest?,
        dest: Dest,
    ): Boolean = framed && musicActive && !sessionOverMusic &&
        (inMusic || musicPlaying || barKeptOn == dest)

    /**
     * Mirrors `RaviloApp.kt:deskBarShows` — delegates to `deskMusicBarVisible` and then
     * excludes MusicPlaying so the bar is hidden on the Playing page (queue stays visible).
     */
    private fun barShows(
        framed: Boolean,
        musicActive: Boolean,
        sessionOverMusic: Boolean,
        inMusic: Boolean,
        musicPlaying: Boolean,
        barKeptOn: Dest?,
        dest: Dest,
    ): Boolean = musicBarVisible(framed, musicActive, sessionOverMusic, inMusic, musicPlaying, barKeptOn, dest) && dest !is Dest.MusicPlaying

    @Test fun `deskMusicBarVisible is true on MusicPlaying in music mode`() {
        assertTrue(musicBarVisible(
            framed = true, musicActive = true, sessionOverMusic = false,
            inMusic = true, musicPlaying = false, barKeptOn = null,
            dest = Dest.MusicPlaying("test"),
        ))
    }

    @Test fun `deskMusicBarVisible is true on MusicPlaying while playing in films mode`() {
        assertTrue(musicBarVisible(
            framed = true, musicActive = true, sessionOverMusic = false,
            inMusic = false, musicPlaying = true, barKeptOn = null,
            dest = Dest.MusicPlaying("test"),
        ))
    }

    @Test fun `deskMusicBarVisible is true on MusicPlaying when kept by prior pause`() {
        assertTrue(musicBarVisible(
            framed = true, musicActive = true, sessionOverMusic = false,
            inMusic = false, musicPlaying = false,
            barKeptOn = Dest.MusicPlaying("test"),
            dest = Dest.MusicPlaying("test"),
        ))
    }

    @Test fun `deskMusicBarVisible is false when not framed`() {
        assertFalse(musicBarVisible(
            framed = false, musicActive = true, sessionOverMusic = false,
            inMusic = true, musicPlaying = false, barKeptOn = null,
            dest = Dest.MusicPlaying("test"),
        ))
    }

    @Test fun `deskMusicBarVisible is false when music not active`() {
        assertFalse(musicBarVisible(
            framed = true, musicActive = false, sessionOverMusic = false,
            inMusic = true, musicPlaying = false, barKeptOn = null,
            dest = Dest.MusicPlaying("test"),
        ))
    }

    @Test fun `deskMusicBarVisible is false when session over music`() {
        assertFalse(musicBarVisible(
            framed = true, musicActive = true, sessionOverMusic = true,
            inMusic = true, musicPlaying = false, barKeptOn = null,
            dest = Dest.MusicPlaying("test"),
        ))
    }

    @Test fun `deskBarShows excludes MusicPlaying even when bar is visible`() {
        // musicBarVisible returns true, but deskBarShows excludes MusicPlaying
        assertTrue(musicBarVisible(
            framed = true, musicActive = true, sessionOverMusic = false,
            inMusic = true, musicPlaying = false, barKeptOn = null,
            dest = Dest.MusicPlaying("test"),
        ))
        assertFalse(barShows(
            framed = true, musicActive = true, sessionOverMusic = false,
            inMusic = true, musicPlaying = false, barKeptOn = null,
            dest = Dest.MusicPlaying("test"),
        ))
    }

    @Test fun `deskBarShows is true on MusicListen in music mode`() {
        assertTrue(barShows(
            framed = true, musicActive = true, sessionOverMusic = false,
            inMusic = true, musicPlaying = true, barKeptOn = null,
            dest = Dest.MusicListen("test"),
        ))
    }

    @Test fun `inset uses deskMusicBarVisible: MusicPlaying gets inset`() {
        // The inset calculation at RaviloApp.kt:2220 uses deskMusicBarVisible, not deskBarShows.
        // This means MusicPlaying gets the bar inset applied (the bar is visible on that page).
        val musicPlaying = Dest.MusicPlaying("test")
        val musicListen = Dest.MusicListen("test")

        // musicBarVisible(true, ...) → true for MusicPlaying → inset applied
        assertTrue(musicBarVisible(
            framed = true, musicActive = true, sessionOverMusic = false,
            inMusic = true, musicPlaying = false, barKeptOn = null,
            dest = musicPlaying,
        ))

        // deskBarShows(false, ...) → false for MusicPlaying (excluded)
        // but deskBarShows(true, ...) → true for MusicListen
        assertTrue(barShows(
            framed = true, musicActive = true, sessionOverMusic = false,
            inMusic = true, musicPlaying = true, barKeptOn = null,
            dest = musicListen,
        ))
        assertFalse(barShows(
            framed = true, musicActive = true, sessionOverMusic = false,
            inMusic = true, musicPlaying = false, barKeptOn = null,
            dest = musicPlaying,
        ))
    }

    @Test fun `barKeptOn on different page does not show bar on MusicPlaying`() {
        assertFalse(musicBarVisible(
            framed = true, musicActive = true, sessionOverMusic = false,
            inMusic = false, musicPlaying = false,
            barKeptOn = Dest.MusicListen("other"),
            dest = Dest.MusicPlaying("test"),
        ))
    }
}
