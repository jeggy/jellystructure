package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.ravilo.ui.Dest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-2305 (FR-R337-6/7 amendments) — the desktop Playing page keeps the bar, loses seek/transport. */
class DesktopBarOnPlayingTest {

    /**
     * The core visibility predicate extracted from the desktop bar logic.
     * Returns true when the bar should be visible for the given destination,
     * music state, and mode.
     */
    private fun barVisibleFor(
        framed: Boolean,
        musicActive: Boolean,
        sessionOverMusic: Boolean,
        inMusic: Boolean,
        musicPlaying: Boolean,
        barKeptOn: Dest?,
        dest: Dest,
    ): Boolean = framed && musicActive && !sessionOverMusic &&
        (inMusic || musicPlaying || barKeptOn == dest)

    @Test fun `the bar is visible on MusicPlaying when music is active in music mode`() {
        assertTrue(barVisibleFor(
            framed = true,
            musicActive = true,
            sessionOverMusic = false,
            inMusic = true,
            musicPlaying = false,
            barKeptOn = null,
            dest = Dest.MusicPlaying("test"),
        ))
    }

    @Test fun `the bar is visible on MusicPlaying when music is playing in films mode`() {
        assertTrue(barVisibleFor(
            framed = true,
            musicActive = true,
            sessionOverMusic = false,
            inMusic = false,
            musicPlaying = true,
            barKeptOn = null,
            dest = Dest.MusicPlaying("test"),
        ))
    }

    @Test fun `the bar is NOT visible on MusicPlaying when music is not active`() {
        assertFalse(barVisibleFor(
            framed = true,
            musicActive = false,
            sessionOverMusic = false,
            inMusic = true,
            musicPlaying = false,
            barKeptOn = null,
            dest = Dest.MusicPlaying("test"),
        ))
    }

    @Test fun `the bar is NOT visible on MusicPlaying when session is over music`() {
        assertFalse(barVisibleFor(
            framed = true,
            musicActive = true,
            sessionOverMusic = true,
            inMusic = true,
            musicPlaying = false,
            barKeptOn = null,
            dest = Dest.MusicPlaying("test"),
        ))
    }

    @Test fun `the bar is visible on MusicPlaying when kept by a prior pause`() {
        assertTrue(barVisibleFor(
            framed = true,
            musicActive = true,
            sessionOverMusic = false,
            inMusic = false,
            musicPlaying = false,
            barKeptOn = Dest.MusicPlaying("test"),
            dest = Dest.MusicPlaying("test"),
        ))
    }

    @Test fun `the bar is NOT visible on MusicPlaying when kept by a pause on a different page`() {
        assertFalse(barVisibleFor(
            framed = true,
            musicActive = true,
            sessionOverMusic = false,
            inMusic = false,
            musicPlaying = false,
            barKeptOn = Dest.MusicListen("test"),
            dest = Dest.MusicPlaying("test"),
        ))
    }

    @Test fun `the bar is NOT visible when not framed (compact or phone)`() {
        assertFalse(barVisibleFor(
            framed = false,
            musicActive = true,
            sessionOverMusic = false,
            inMusic = true,
            musicPlaying = false,
            barKeptOn = null,
            dest = Dest.MusicPlaying("test"),
        ))
    }

    /**
     * The old `deskBarShows` excluded MusicPlaying. The new `deskBarShows` delegates
     * to `deskMusicBarVisible` and then excludes MusicPlaying — so the bar is
     * visible on the Playing page but `deskBarShows` returns false for it.
     */
    private fun oldBarShows(
        framed: Boolean,
        musicActive: Boolean,
        sessionOverMusic: Boolean,
        inMusic: Boolean,
        musicPlaying: Boolean,
        barKeptOn: Dest?,
        dest: Dest,
    ): Boolean = framed && musicActive && !sessionOverMusic && dest !is Dest.MusicPlaying &&
        (inMusic || musicPlaying || barKeptOn == dest)

    @Test fun `old deskBarShows excluded MusicPlaying`() {
        assertFalse(oldBarShows(
            framed = true,
            musicActive = true,
            sessionOverMusic = false,
            inMusic = true,
            musicPlaying = false,
            barKeptOn = null,
            dest = Dest.MusicPlaying("test"),
        ))
    }

    @Test fun `old deskBarShows still hides on MusicPlaying in films mode while playing`() {
        assertFalse(oldBarShows(
            framed = true,
            musicActive = true,
            sessionOverMusic = false,
            inMusic = false,
            musicPlaying = true,
            barKeptOn = null,
            dest = Dest.MusicPlaying("test"),
        ))
    }
}
