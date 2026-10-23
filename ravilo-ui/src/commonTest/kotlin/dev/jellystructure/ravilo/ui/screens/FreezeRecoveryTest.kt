package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.QoeStall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-2205 (FR-1, FR-5) — the stop measure and the recovery decision table, as pure rules. */
class FreezeRecoveryTest {
    private val hevc2160 = 31_000_000L   // the BANDWIDTH of a 2160p@20000k HEVC variant
    private val h264_1080 = 18_500_000L

    // ── the decision table ──────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `an empty buffer is capped in place every time and never restreams`() {
        val r = FreezeRecovery()
        repeat(4) { assertEquals(FreezeAction.CapInPlace, r.decide(FreezeKind.BUFFER_EMPTY)) }
        assertEquals(0, r.freezes)
        assertEquals(4, r.emptyBuffers)
    }

    @Test fun `the first freeze goes to our encoder under the frozen rung and leaves hevc`() {
        val a = FreezeRecovery().decide(FreezeKind.FROZEN_WITH_BUFFER, hevc2160, "hevc")
        assertEquals(FreezeAction.RestreamOurs(belowBandwidthBps = hevc2160, leaveHevc = true), a)
    }

    @Test fun `a frozen h264 rung does not ask to leave hevc`() {
        val a = FreezeRecovery().decide(FreezeKind.FROZEN_WITH_BUFFER, h264_1080, "h264") as FreezeAction.RestreamOurs
        assertFalse(a.leaveHevc)
        assertEquals(h264_1080, a.belowBandwidthBps)
    }

    @Test fun `the second freeze goes to Jellyfin capped under the first frozen rung`() {
        val r = FreezeRecovery()
        r.decide(FreezeKind.FROZEN_WITH_BUFFER, hevc2160, "hevc")
        val b = r.decide(FreezeKind.FROZEN_WITH_BUFFER, h264_1080, "h264") as FreezeAction.RestreamJellyfin
        assertEquals(FreezeRecovery.maxVideoBpsUnder(h264_1080), b.maxVideoBps)
        assertTrue(b.leaveHevc, "the codec that froze first stays left")
    }

    @Test fun `the third freeze ends on the error`() {
        val r = FreezeRecovery()
        r.decide(FreezeKind.FROZEN_WITH_BUFFER, hevc2160, "hevc")
        r.decide(FreezeKind.FROZEN_WITH_BUFFER, h264_1080, "h264")
        assertEquals(FreezeAction.Fail, r.decide(FreezeKind.FROZEN_WITH_BUFFER, 6_000_000L, "h264"))
        assertEquals(3, r.freezes)
    }

    @Test fun `a bound never rises above the lowest rung that froze`() {
        val r = FreezeRecovery()
        r.decide(FreezeKind.FROZEN_WITH_BUFFER, h264_1080, "h264")
        // The second freeze is reported on a HIGHER rung than the first (a stale reading): the bound stays under the first.
        val b = r.decide(FreezeKind.FROZEN_WITH_BUFFER, hevc2160, "hevc") as FreezeAction.RestreamJellyfin
        assertEquals(h264_1080, r.lowestFrozenBps)
        assertEquals(FreezeRecovery.maxVideoBpsUnder(h264_1080), b.maxVideoBps)
    }

    @Test fun `an empty buffer in between changes nothing about the freezes`() {
        val r = FreezeRecovery()
        r.decide(FreezeKind.FROZEN_WITH_BUFFER, hevc2160, "hevc")
        r.decide(FreezeKind.BUFFER_EMPTY)
        assertTrue(r.decide(FreezeKind.FROZEN_WITH_BUFFER, h264_1080, "h264") is FreezeAction.RestreamJellyfin)
    }

    @Test fun `a freeze on an unknown rung still recovers but bounds nothing`() {
        val r = FreezeRecovery()
        assertEquals(FreezeAction.RestreamOurs(null, leaveHevc = false), r.decide(FreezeKind.FROZEN_WITH_BUFFER, null, null))
        assertEquals(FreezeAction.RestreamJellyfin(null, leaveHevc = false), r.decide(FreezeKind.FROZEN_WITH_BUFFER, null, null))
        assertNull(r.lowestFrozenBps)
    }

    @Test fun `the Jellyfin cap is under the frozen rungs video whatever audio it carried`() {
        for (audio in listOf(0L, 128_000L, 192_000L, 384_000L, 640_000L)) {
            for (video in listOf(500_000L, 1_500_000L, 4_000_000L, 8_000_000L, 20_000_000L)) {
                val bandwidth = (video * 1.5).toLong() + audio
                val cap = FreezeRecovery.maxVideoBpsUnder(bandwidth) ?: continue
                assertTrue(cap < video, "cap $cap vs frozen video $video with audio $audio")
            }
        }
    }

    // ── one rung below, in place ───────────────────────────────────────────────────────────────────────────────────

    @Test fun `the rung below is the largest strictly under the current one`() {
        val rungs = listOf(30_000_000L, 12_500_000L, 6_200_000L, 2_000_000L)
        assertEquals(12_500_000L, rungBelow(rungs, 30_000_000L))
        assertEquals(2_000_000L, rungBelow(rungs, 6_200_000L))
        assertEquals(6_200_000L, rungBelow(rungs, 7_000_000L), "between rungs it is the one under")
    }

    @Test fun `the lowest rung has nothing below it`() {
        assertNull(rungBelow(listOf(30_000_000L, 2_000_000L), 2_000_000L))
        assertNull(rungBelow(emptyList(), 2_000_000L))
        assertNull(rungBelow(listOf(0L, -1L), 2_000_000L))
    }

    // ── the log line and the codec ─────────────────────────────────────────────────────────────────────────────────

    @Test fun `the recovery line names the rung that froze and the one it landed on`() {
        assertEquals(
            "recovery: froze on 2160p@31000k (hevc) with 9 s buffered → ours 1080p@18500k h264 (DEB-2205 FR-5)",
            recoveryLine(2160, hevc2160, "hevc", 9_400, "ours 1080p@18500k h264"),
        )
        assertEquals("recovery: froze on an unknown rung with 0 s buffered → x (DEB-2205 FR-5)", recoveryLine(null, null, null, 0, "x"))
    }

    @Test fun `a mime type names its video family`() {
        assertEquals("hevc", videoFamilyOfMime("video/hevc"))
        assertEquals("h264", videoFamilyOfMime("video/avc"))
        assertEquals("av1", videoFamilyOfMime("video/av01"))
        assertEquals("vp9", videoFamilyOfMime("video/x-vnd.on2.vp9"))
        assertNull(videoFamilyOfMime("audio/mp4a-latm"))
        assertNull(videoFamilyOfMime(null))
    }

    // ── the stop measure ───────────────────────────────────────────────────────────────────────────────────────────

    private fun watch(pos: Long, ahead: Long = 9_000, play: Boolean = true, waiting: Boolean = false, first: Boolean = true, recovering: Boolean = false) =
        PlayerWatch(pos, play, waiting, first, ahead, recovering)

    @Test fun `a moving position is no stop`() {
        val m = FrozenMeter()
        var t = 0L
        for (i in 0..20) { val r = m.observe(t, watch(i * 500L)); assertEquals(0, r.heldMs); assertNull(r.ended); t += 500 }
    }

    @Test fun `a position stuck for a second with buffer in hand is a stop reported when it ends`() {
        val m = FrozenMeter()
        m.observe(0, watch(10_000))
        assertEquals(0, m.observe(500, watch(10_000)).heldMs, "under a second is not yet a stop")
        assertEquals(1_000, m.observe(1_000, watch(10_000)).heldMs)
        assertEquals(2_500, m.observe(2_500, watch(10_000)).heldMs)
        val end = m.observe(3_000, watch(11_000))
        assertEquals(FrozenStop(startedAtMs = 0, durationMs = 3_000, positionMs = 10_000, bufferAheadMs = 9_000), end.ended)
        assertEquals(0, end.heldMs)
    }

    @Test fun `five seconds frozen is what the recovery waits for`() {
        val m = FrozenMeter()
        var held = 0L
        var t = 0L
        while (held < FROZEN_RECOVER_MS) { held = m.observe(t, watch(10_000)).heldMs; t += 500 }
        assertEquals(5_000L, held)
    }

    @Test fun `a pause or a buffering player or a seek or no first frame is no stop`() {
        for (w in listOf(watch(5_000, play = false), watch(5_000, waiting = true), watch(5_000, first = false))) {
            val m = FrozenMeter()
            for (t in 0..10_000L step 500) { val r = m.observe(t, w); assertEquals(0, r.heldMs); assertNull(r.ended) }
        }
    }

    @Test fun `a stuck position with the buffer gone is the buffer empty kind and not this one`() {
        val m = FrozenMeter()
        for (t in 0..10_000L step 500) assertEquals(0, m.observe(t, watch(5_000, ahead = 2_900)).heldMs)
        val unknown = FrozenMeter()
        for (t in 0..10_000L step 500) assertEquals(0, unknown.observe(t, watch(5_000, ahead = -1)).heldMs)
    }

    @Test fun `the video output ladders own seek and wait does not restart the five seconds`() {
        val m = FrozenMeter()
        m.observe(0, watch(10_000))
        // R220's rung 2 seeks by 1 ms and the player waits: with the ladder running the freeze goes on.
        assertEquals(2_000, m.observe(2_000, watch(9_999, waiting = true, recovering = true)).heldMs)
        assertEquals(5_000, m.observe(5_000, watch(9_999, waiting = true, recovering = true)).heldMs)
        // The same wait with no ladder running is the player's own (or the viewer's) and ends it.
        assertEquals(0, m.observe(5_500, watch(9_999, waiting = true)).heldMs)
    }

    @Test fun `a stop closes when the buffer runs out under it`() {
        val m = FrozenMeter()
        m.observe(0, watch(5_000))
        m.observe(1_500, watch(5_000))
        val r = m.observe(2_000, watch(5_000, ahead = 1_000))
        assertEquals(2_000L, r.ended?.durationMs)
        assertEquals(0, r.heldMs)
    }

    @Test fun `a pause ends the stop in progress`() {
        val m = FrozenMeter()
        m.observe(0, watch(5_000)); m.observe(2_000, watch(5_000))
        assertEquals(2_500L, m.observe(2_500, watch(5_000, play = false)).ended?.durationMs)
    }

    @Test fun `flush hands over the stop in progress and starts clean`() {
        val m = FrozenMeter()
        m.observe(0, watch(5_000)); m.observe(5_000, watch(5_000))
        assertEquals(5_500L, m.flush(5_500)?.durationMs)
        assertNull(m.flush(6_000))
        assertEquals(0, m.observe(6_000, watch(5_000)).heldMs, "the clock starts again")
    }

    // ── the entries the report carries ─────────────────────────────────────────────────────────────────────────────

    private fun stall(after: Long, cause: String?) = QoeStall(afterFirstFrameMs = after, positionMs = after, bufferedMs = 0, durationMs = 100, phase = "mid", cause = cause)

    @Test fun `stops are merged in time order and the delivery stalls keep theirs`() {
        val player = listOf(stall(10_000, "buffer_empty"), stall(90_000, "buffer_empty"))
        val store = listOf(stall(50_000, "frozen_with_buffer"), stall(60_000, "recovery"))
        assertEquals(listOf(10_000L, 50_000L, 60_000L, 90_000L), mergeStops(player, store).map { it.afterFirstFrameMs })
        assertEquals(player, mergeStops(player, emptyList()))
    }
}
