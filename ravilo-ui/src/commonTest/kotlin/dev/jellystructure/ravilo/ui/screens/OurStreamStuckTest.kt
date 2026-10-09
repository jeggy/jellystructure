package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.AudioTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 313 (found live 2026-10-09, Mac) — a refused stream never spins forever, and a burn-in keeps the picked audio. */
class OurStreamStuckTest {

    @Test fun `a position that has not moved for 20 s while playing is stuck`() {
        val w = StuckWatch(stuckMs = 20_000)
        assertFalse(w.observe(60_000, paused = false, nowMs = 0))
        assertFalse(w.observe(60_000, paused = false, nowMs = 19_999))
        assertTrue(w.observe(60_100, paused = false, nowMs = 20_000))   // within the tolerance: still not moving
    }

    @Test fun `moving or pausing starts the clock again`() {
        val w = StuckWatch(stuckMs = 20_000)
        w.observe(0, paused = false, nowMs = 0)
        assertFalse(w.observe(5_000, paused = false, nowMs = 15_000))   // it moved
        assertFalse(w.observe(5_000, paused = false, nowMs = 30_000))   // 15 s since the move
        assertFalse(w.observe(5_000, paused = true, nowMs = 40_000))    // a pause is not a stall
        assertFalse(w.observe(5_000, paused = false, nowMs = 55_000))
        assertTrue(w.observe(5_000, paused = false, nowMs = 60_000))
    }

    /** Found live 2026-10-09 (evening, Mac): the player rejected our stream and the restream gave it back again. */
    @Test fun `our stream's first failure restreams from Jellyfin, the second ends on the error`() {
        assertEquals(StreamRecovery.RESTREAM_FROM_JELLYFIN, streamRecovery(ours = true, restreamedAlready = false, failed = true))
        assertEquals(StreamRecovery.RESTREAM_FROM_JELLYFIN, streamRecovery(ours = true, restreamedAlready = false, failed = false))
        assertEquals(StreamRecovery.FAILED, streamRecovery(ours = true, restreamedAlready = true, failed = true))
        assertEquals(StreamRecovery.NOT_STARTED, streamRecovery(ours = true, restreamedAlready = true, failed = false))
        assertEquals(StreamRecovery.FAILED, streamRecovery(ours = false, restreamedAlready = false, failed = true))
    }

    @Test fun `a burn-in keeps the audio the viewer picked inside the stream`() {
        val audio = listOf(AudioTrack(1, "eng", "TrueHD"), AudioTrack(2, "eng", "DTS-HD MA"), AudioTrack(4, "hin", "AC-3"))
        // R291 renditions: no carried track — the picked one (position 1, Jellyfin index 2) is sent, not the default.
        assertEquals(2, restreamAudioIndex(sessionAudioIndex = null, ticketAudio = audio, selectedAudio = 1))
        // A single-audio session carries its own track, which wins.
        assertEquals(4, restreamAudioIndex(sessionAudioIndex = 4, ticketAudio = audio, selectedAudio = 1))
        assertNull(restreamAudioIndex(sessionAudioIndex = null, ticketAudio = emptyList(), selectedAudio = 0))
    }
}
