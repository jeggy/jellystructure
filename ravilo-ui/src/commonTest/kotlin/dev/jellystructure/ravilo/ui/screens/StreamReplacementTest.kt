package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.QoeStallCause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-3103 (DEB-3093 FR-6, FR-8) — a stream is replaced without being seen, and every replacement leaves a record. */
class StreamReplacementTest {
    // ── FR-6: the retry schedule ──
    @Test fun `a failed call is retried at 1 2 4 and 8 s and then every 8 s while the buffer holds`() {
        val holding = 20_000L
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L, 8_000L), (1..6).map { replacementRetryDelayMs(it, holding) })
    }

    @Test fun `with the buffer empty every retry runs first and then it gives up`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), (1..4).map { replacementRetryDelayMs(it, 0L) })
        assertNull(replacementRetryDelayMs(5, 0L))
        assertNull(replacementRetryDelayMs(5, -1L))   // a buffer the player cannot read is not picture in hand
    }

    @Test fun `with picture in the buffer it never gives up`() {
        assertEquals(8_000L, replacementRetryDelayMs(50, 400L))
    }

    // ── FR-6: where the new stream starts ──
    @Test fun `the new stream starts where the old buffer can carry the playhead to and at most 6 s ahead`() {
        assertEquals(10_000L + 6_000L, replacementAtMs(10_000L, 30_000L, oldStreamDead = false))
        assertEquals(10_000L + 3_000L, replacementAtMs(10_000L, 5_000L, oldStreamDead = false))
    }

    @Test fun `a dead or nearly empty old stream is replaced at the playhead`() {
        assertEquals(10_000L, replacementAtMs(10_000L, 30_000L, oldStreamDead = true))
        assertEquals(10_000L, replacementAtMs(10_000L, 1_500L, oldStreamDead = false))
        assertEquals(10_000L, replacementAtMs(10_000L, -1L, oldStreamDead = false))
    }

    // ── FR-8: the entry ──
    private fun recovery(reason: String = ReplacementReason.STALL_TO_LADDER, awaiting: Boolean = false) = PendingRecovery(
        startedAtMs = 100_000L, positionMs = 60_000L, bufferAheadMs = 12_000L, frozenHeight = 2160, frozenBps = 40_000_000L, frozenCodec = "hevc",
        target = "ours", reason = reason, awaitingSwitch = awaiting,
    )

    private fun playing(positionMs: Long) = PlayerWatch(positionMs, wantsToPlay = true, waiting = false, firstFrame = true, bufferedAheadMs = 5_000L)
    private fun waiting(positionMs: Long) = PlayerWatch(positionMs, wantsToPlay = true, waiting = true, firstFrame = true, bufferedAheadMs = 0L)

    private fun end(r: PendingRecovery, w: PlayerWatch, now: Long) = finishRecovery(r, w, now, 1080, 12_000_000L, "h264") { 30_000L }

    @Test fun `nothing is read while the old stream is the one playing`() {
        val r = recovery(awaiting = true)
        assertNull(end(r, playing(61_000L), 105_000L))
        assertNull(end(r, playing(61_000L), 100_000L + RECOVERY_WATCH_MS + 1))
    }

    @Test fun `the clock starts at the switch not at the decision`() {
        val r = recovery(awaiting = true)
        r.switched(nowMs = 108_000L, positionMs = 64_000L)
        assertNull(end(r, waiting(64_000L), 108_500L))                       // still waiting for the new stream
        val e = assertNotNull(end(r, playing(64_900L), 110_000L))            // moved >500 ms past the switch, 2 s after it
        assertEquals(2_000L, e.stall!!.durationMs)
        assertEquals(64_000L, e.stall!!.positionMs)
    }

    @Test fun `a stop of a second or more writes a recovery entry with its reason and the rung it left`() {
        val r = recovery(ReplacementReason.ENCODER_FAILED)
        val e = assertNotNull(end(r, playing(61_000L), 101_500L))
        val s = assertNotNull(e.stall)
        assertEquals(QoeStallCause.RECOVERY, s.cause)
        assertEquals(ReplacementReason.ENCODER_FAILED, s.reason)
        assertEquals(1_500L, s.durationMs)
        assertEquals(2160, s.rungHeight)
        assertEquals(40_000_000L, s.rungBps)
        assertEquals(12_000L, s.bufferAheadMs)
        assertEquals("mid", s.phase)
    }

    @Test fun `a replacement under a second is not a stop - it writes its line and no entry`() {
        val e = assertNotNull(end(recovery(), playing(61_000L), 100_400L))
        assertNull(e.stall)
        assertTrue("[stall_to_ladder]" in e.line, e.line)
    }

    @Test fun `a new stream that never moved is a stop too`() {
        val e = assertNotNull(end(recovery(ReplacementReason.FROZEN), waiting(60_000L), 100_000L + RECOVERY_WATCH_MS + 1))
        assertEquals(ReplacementReason.FROZEN, e.stall!!.reason)
        assertTrue("no picture within" in e.line, e.line)
    }

    @Test fun `every road has its own reason code`() {
        val all = listOf(
            ReplacementReason.ENCODER_FAILED, ReplacementReason.ENCODER_STUCK, ReplacementReason.FROZEN, ReplacementReason.DECODE_FAILED,
            ReplacementReason.HANDOVER_NOW, ReplacementReason.HANDOVER_AHEAD, ReplacementReason.STALL_TO_LADDER, ReplacementReason.STEP_CAP,
        )
        assertEquals(all.size, all.toSet().size)
    }

    // ── FR-6 (DEB-3171): no moment inside the hold ──
    @Test fun `a replacement draws no buffering moment for its first 2 s whatever the player says`() {
        assertTrue(replacementDrawsNothing(heldSinceMs = 10_000L, nowMs = 10_000L))
        assertTrue(replacementDrawsNothing(10_000L, 10_400L))   // the 400 ms of a seek or cold start: nothing is drawn
        assertTrue(replacementDrawsNothing(10_000L, 10_000L + REPLACEMENT_HOLD_MS))
    }

    @Test fun `after the hold R218 treatment applies and a stream that is not a replacement has none`() {
        assertEquals(false, replacementDrawsNothing(10_000L, 10_000L + REPLACEMENT_HOLD_MS + 1))
        assertEquals(false, replacementDrawsNothing(null, 10_000L))
    }
}
