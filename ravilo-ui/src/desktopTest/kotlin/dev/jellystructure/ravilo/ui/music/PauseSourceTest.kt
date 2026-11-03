package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.ravilo.ui.desktop.MacNowPlaying
import dev.jellystructure.ravilo.ui.desktop.pauseSourceOf
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** DEB-3034 (a-song-that-waits-says-so.md FR-7) — what sent a pause, as the Mac's engine names it. */
class PauseSourceTest {
    private var clock = 10_000L

    @AfterTest fun reset() { PauseSource.nowMs = { 0L }; clock = 10_000L }

    @Test fun `the three Now Playing commands each have their own name and the Dock outranks them`() {
        assertEquals("now_playing_pause", pauseSourceOf(MacNowPlaying.Command.PAUSE, viaDock = false))
        assertEquals("now_playing_toggle", pauseSourceOf(MacNowPlaying.Command.TOGGLE, viaDock = false))
        assertEquals("now_playing_stop", pauseSourceOf(MacNowPlaying.Command.STOP, viaDock = false))
        assertEquals("dock", pauseSourceOf(MacNowPlaying.Command.TOGGLE, viaDock = true))
    }

    @Test fun `a click right after the space bar is the keyboard and any other click is the screen`() {
        PauseSource.nowMs = { clock }
        assertEquals("screen", PauseSource.fromControl())   // no key yet
        PauseSource.noteSpaceBar()
        clock += 100
        assertEquals("keyboard", PauseSource.fromControl())
        clock += 400
        assertEquals("screen", PauseSource.fromControl())   // 500 ms after the key: a click of its own
    }

    @Test fun `only the documented values reach the wire`() {
        for (v in listOf("screen", "keyboard", "dock", "now_playing_pause", "now_playing_toggle", "now_playing_stop", "remote", "engine", "media_session", "audio_focus"))
            assertEquals(v, PauseSource.wire(v))
        assertNull(PauseSource.wire("somebody"))
        assertNull(PauseSource.wire(null))
    }

    @Test fun `the Mac's reason for waiting is read from the debug line`() {
        assertEquals("AVPlayerWaitingToMinimizeStallsReason",
            whyOf("item=ready player=waiting(AVPlayerWaitingToMinimizeStallsReason)\nreadySeen=true"))
        assertNull(whyOf("item=ready player=playing readySeen=true"))
        assertNull(whyOf("item=unknown player=waiting(?) readySeen=false"))
        assertNull(whyOf("mpv: no player"))
    }
}
