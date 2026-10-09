package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.QoeStall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Phase 309 (FR-309-8/-9) — the restreams the player store starts on its own. */
class PlayerLadderSwitchTest {
    private fun stall(at: Long, ms: Long) = QoeStall(afterFirstFrameMs = at, positionMs = at, bufferedMs = 0, durationMs = ms, phase = "mid")

    @Test fun `a direct play that stalls for two seconds moves to the ladder`() {
        assertTrue(shouldMoveToLadder(directPlay = true, alreadyMoved = false, stalls = listOf(stall(90_000, 2_400))))
    }

    @Test fun `two short stalls within a minute move it too one alone does not`() {
        assertTrue(shouldMoveToLadder(true, false, listOf(stall(30_000, 500), stall(70_000, 600))))
        assertFalse(shouldMoveToLadder(true, false, listOf(stall(30_000, 500))))
    }

    @Test fun `never twice for one item and never from a stream that is already a transcode`() {
        val counting = listOf(stall(90_000, 3_000))
        assertFalse(shouldMoveToLadder(directPlay = true, alreadyMoved = true, stalls = counting))
        assertFalse(shouldMoveToLadder(directPlay = false, alreadyMoved = false, stalls = counting))
    }

    @Test fun `a stepping cap stays under the devices own and is lifted back to it`() {
        assertEquals(8_000_000, steppedMaxVideo(deviceMaxVideo = 0, capVideoBps = 8_000_000))
        assertEquals(6_000_000, steppedMaxVideo(deviceMaxVideo = 6_000_000, capVideoBps = 8_000_000))
        assertEquals(6_000_000, steppedMaxVideo(deviceMaxVideo = 6_000_000, capVideoBps = 0))
        assertEquals(0, steppedMaxVideo(deviceMaxVideo = 0, capVideoBps = 0))
    }
}
