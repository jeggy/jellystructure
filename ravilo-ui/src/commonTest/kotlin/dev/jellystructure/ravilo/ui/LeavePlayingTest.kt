package dev.jellystructure.ravilo.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** DEB-2482 (FR-R337-6) — [leavePlayingTarget]: pop when a page sits under Playing, else the mode's first page. */
class LeavePlayingTest {
    private val name = "Viewer"

    @Test fun aPageUnderPlayingInMusicModeIsPoppedTo() {
        assertNull(leavePlayingTarget(listOf(Dest.MusicListen(name), Dest.MusicPlaying(name)), inMusic = true, displayName = name))
    }

    @Test fun playingAloneInMusicModeGoesToListen() {
        assertEquals(Dest.MusicListen(name), leavePlayingTarget(listOf(Dest.MusicPlaying(name)), inMusic = true, displayName = name))
    }

    @Test fun playingAloneInFilmsModeGoesToHome() {
        assertEquals(Dest.Home(name), leavePlayingTarget(listOf(Dest.MusicPlaying(name)), inMusic = false, displayName = name))
    }

    @Test fun pagesUnderPlayingInFilmsModeArePoppedTo() {
        val stack = listOf(Dest.Home(name), Dest.AlbumDetail("a1", name), Dest.MusicPlaying(name))
        assertNull(leavePlayingTarget(stack, inMusic = false, displayName = name))
    }
}
