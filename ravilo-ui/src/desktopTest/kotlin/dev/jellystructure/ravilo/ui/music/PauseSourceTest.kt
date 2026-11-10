package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.ravilo.ui.desktop.MacNowPlaying
import dev.jellystructure.ravilo.ui.desktop.pauseSourceOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * DEB-3034 (a-song-that-waits-says-so.md FR-7) — what sent a pause, as the Mac's engine names it. The "click right after
 * the space bar" window went with DEB-4133: Space no longer reaches a control, and the key sends `keyboard` itself
 * (SpaceKeyTest).
 */
class PauseSourceTest {
    @Test fun `the three Now Playing commands each have their own name and the Dock outranks them`() {
        assertEquals("now_playing_pause", pauseSourceOf(MacNowPlaying.Command.PAUSE, viaDock = false))
        assertEquals("now_playing_toggle", pauseSourceOf(MacNowPlaying.Command.TOGGLE, viaDock = false))
        assertEquals("now_playing_stop", pauseSourceOf(MacNowPlaying.Command.STOP, viaDock = false))
        assertEquals("dock", pauseSourceOf(MacNowPlaying.Command.TOGGLE, viaDock = true))
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
