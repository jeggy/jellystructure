package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-3105 (DEB-3093 FR-9) — what the Chromecast receiver does with a stream that failed. */
class CastRecoveryTest {
    @Test fun `our encoder is asked again first and Jellyfin second`() {
        assertEquals(CastRecoveryStep.OURS_AGAIN, castRecoveryStep(ours = true, askedOursAgain = false, askedJellyfin = false))
        assertEquals(CastRecoveryStep.JELLYFIN, castRecoveryStep(ours = true, askedOursAgain = true, askedJellyfin = false))
        assertEquals(CastRecoveryStep.GIVE_UP, castRecoveryStep(ours = true, askedOursAgain = true, askedJellyfin = true))
    }

    @Test fun `a stream from Jellyfin that failed ends on the error`() {
        assertEquals(CastRecoveryStep.GIVE_UP, castRecoveryStep(ours = false, askedOursAgain = false, askedJellyfin = false))
    }

    @Test fun `only a film that had its first picture is recovered`() {
        assertTrue(castShouldRecover(music = false, radio = false, reachedEnd = false, played = true, hasItem = true))
        assertFalse(castShouldRecover(music = false, radio = false, reachedEnd = false, played = false, hasItem = true))   // never played
        assertFalse(castShouldRecover(music = false, radio = false, reachedEnd = true, played = true, hasItem = true))     // an end
        assertFalse(castShouldRecover(music = true, radio = false, reachedEnd = false, played = true, hasItem = true))
        assertFalse(castShouldRecover(music = false, radio = true, reachedEnd = false, played = true, hasItem = true))
        assertFalse(castShouldRecover(music = false, radio = false, reachedEnd = false, played = true, hasItem = false))
    }

    @Test fun `a failed call is asked again at 1 2 4 and 8 s and then gives up`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), (1..4).map { castRecoveryRetryMs(it) })
        assertNull(castRecoveryRetryMs(5))
    }

    @Test fun `the receiver draws a buffering view only after 2 s`() {
        assertFalse(castDrawsBuffering(0))
        assertFalse(castDrawsBuffering(1_999))
        assertTrue(castDrawsBuffering(2_000))
    }

    @Test fun `a frozen picture counts at one second and recovers at five`() {
        fun action(ms: Long, ahead: Long = 3_000L) = castFrozenAction(12_000, 12_000, 1_000, 1_000 + ms, ahead, true, false, false, true)
        assertEquals(CastFrozenAction.NONE, action(999))
        assertEquals(CastFrozenAction.COUNT_FROZEN, action(1_000))
        assertEquals(CastFrozenAction.COUNT_FROZEN, action(4_999))
        assertEquals(CastFrozenAction.RECOVER_FROZEN, action(5_000))
        assertEquals(CastFrozenAction.NONE, action(5_000, 2_999))
        assertEquals(CastFrozenAction.RECOVER_STUCK, action(30_000, 0))
    }

    @Test fun `a stop with nothing to play is a delivery wait whatever buffered reads and a playable one is frozen`() {
        fun action(readyState: Int, ahead: Long, ms: Long = 1_000L) =
            castFrozenAction(12_000, 12_000, 1_000, 1_000 + ms, ahead, true, false, false, true, readyState)
        assertEquals(CastFrozenAction.COUNT_FROZEN, action(4, 6_000))
        assertEquals(CastFrozenAction.COUNT_DELIVERY, action(1, 6_000))
        assertEquals(CastFrozenAction.COUNT_DELIVERY, action(2, 6_000))
        assertEquals(CastFrozenAction.NONE, action(4, 1_000), "playable with little buffered is neither")
        assertEquals(CastFrozenAction.NONE, action(2, 6_000, 999), "a delivery wait counts from one second")
        assertEquals(CastFrozenAction.COUNT_DELIVERY, action(2, 6_000, 20_000), "no frozen recovery at five seconds for a delivery wait")
        assertEquals(CastFrozenAction.RECOVER_STUCK, action(2, 6_000, 30_000), "the 30 s backstop is unchanged")
        assertEquals(CastFrozenAction.COUNT_FROZEN, action(-1, 6_000), "an unreadable element is classed as before")
    }

    @Test fun `a freeze takes our encoder then Jellyfin then the error for any stream`() {
        assertEquals(CastRecoveryStep.OURS_AGAIN, castFrozenRecoveryStep(askedOursAgain = false, askedJellyfin = false))
        assertEquals(CastRecoveryStep.JELLYFIN, castFrozenRecoveryStep(askedOursAgain = true, askedJellyfin = false))
        assertEquals(CastRecoveryStep.GIVE_UP, castFrozenRecoveryStep(askedOursAgain = true, askedJellyfin = true))
        // An error after the first picture keeps its rule: a stream from Jellyfin that failed ends on the error.
        assertEquals(CastRecoveryStep.GIVE_UP, castRecoveryStep(ours = false, askedOursAgain = false, askedJellyfin = false))
    }

    @Test fun `a delivery wait still open at four seconds asks on a rung with no lower choice`() {
        fun open(ms: Long, rungs: List<Long> = listOf(2_000_000)) =
            castShouldAskForLadder(rungs, 2_000_000, listOf(stall(20_000, ms)), false)
        assertTrue(open(4_000))
        assertFalse(open(3_900))
        assertFalse(open(4_000, listOf(1_000_000, 2_000_000)), "a lower rung is listed")
        assertFalse(castShouldAskForLadder(listOf(2_000_000), 2_000_000, listOf(stall(20_000, 4_000)), true), "once per item")
    }

    @Test fun `a pause seek recovery first frame or moving clock suppresses the watch`() {
        val base = castFrozenAction(12_000, 12_000, 1_000, 31_000, 40_000, true, false, false, true)
        assertEquals(CastFrozenAction.RECOVER_STUCK, base)
        assertEquals(CastFrozenAction.NONE, castFrozenAction(12_000, 12_000, 1_000, 31_000, 40_000, false, false, false, true))
        assertEquals(CastFrozenAction.NONE, castFrozenAction(12_000, 12_000, 1_000, 31_000, 40_000, true, true, false, true))
        assertEquals(CastFrozenAction.NONE, castFrozenAction(12_000, 12_000, 1_000, 31_000, 40_000, true, false, true, true))
        assertEquals(CastFrozenAction.NONE, castFrozenAction(12_000, 12_000, 1_000, 31_000, 40_000, true, false, false, false))
        assertEquals(CastFrozenAction.NONE, castFrozenAction(12_001, 12_000, 1_000, 31_000, 40_000, true, false, false, true))
    }

    @Test fun `frozen restream cap uses the next lower master rung in video units`() {
        val rungs = listOf(12_256_000L, 6_256_000L, 2_506_000L)
        assertEquals(4_000_000, castFrozenVideoCap(rungs, 12_256_000L, 0))
        assertEquals(1_500_000, castFrozenVideoCap(rungs, 2_506_000L, 0))
        assertEquals(3_000_000, castFrozenVideoCap(rungs, 12_256_000L, 3_000_000))
        assertEquals(20_000_000, castFrozenVideoCap(emptyList(), null, 20_000_000))
    }

    @Test fun `shaka retries follow FR-4 and its timeout FR-3`() {
        // 0.5, 1, 2, 4 s between five attempts; a request gives up at the player's own 20 s.
        val retry = CastRecovery.shakaRetryParameters
        val delays = (0 until retry.maxAttempts - 1).map { (retry.baseDelayMs * Math.pow2(it, retry.backoffFactor)).toLong() }
        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L), delays)
        assertEquals(5, retry.maxAttempts)
        assertEquals(500, retry.baseDelayMs)
        assertEquals(2.0, retry.backoffFactor)
        assertEquals(20_000, retry.timeoutMs)
    }

    private fun stall(at: Long, duration: Long = 1_000L, cause: String = QoeStallCause.BUFFER_EMPTY) = QoeStall(
        afterFirstFrameMs = at, positionMs = at, bufferedMs = 0, durationMs = duration,
        phase = "mid", cause = cause,
    )

    @Test fun `two delivery stalls inside two minutes ask once for a missing lower rung`() {
        val first = stall(20_000)
        val second = stall(139_000)
        assertFalse(castShouldAskForLadder(listOf(2_000_000), 2_000_000, listOf(first), false))
        assertTrue(castShouldAskForLadder(listOf(2_000_000), 2_000_000, listOf(first, second), false))
        assertFalse(castShouldAskForLadder(listOf(2_000_000), 2_000_000, listOf(first, second), true), "same-stream answer does not trigger another ask")
        assertFalse(castShouldAskForLadder(listOf(1_000_000, 2_000_000), 2_000_000, listOf(first, second), false), "Shaka has a lower rung")
    }

    @Test fun `stalls three minutes apart do not ask and one four second stall does`() {
        assertFalse(castShouldAskForLadder(listOf(2_000_000), 2_000_000, listOf(stall(20_000), stall(200_000)), false))
        assertTrue(castShouldAskForLadder(listOf(2_000_000), 2_000_000, listOf(stall(20_000, 4_000)), false))
    }

    @Test fun `a frozen stop with buffer does not count toward a delivery recovery`() {
        val frozen = stall(10_000, 5_000, QoeStallCause.FROZEN_WITH_BUFFER)
        val delivery = stall(20_000)
        assertFalse(castShouldAskForLadder(listOf(2_000_000), 2_000_000, listOf(frozen, delivery), false))
        assertTrue(castShouldAskForLadder(listOf(2_000_000), 2_000_000, listOf(frozen, delivery, stall(30_000)), false))
    }

    @Test fun `each LOAD starts fresh QoE counters and stalls`() {
        val counts = CastQoeCounters().apply {
            rebuffers = 2; rebufferMs = 5_000; down = 1; up = 3
            frozenCount = 1; frozenMs = 4_000; stalls += stall(20_000)
        }
        counts.reset()
        assertEquals(0, counts.rebuffers)
        assertEquals(0L, counts.rebufferMs)
        assertEquals(0, counts.down)
        assertEquals(0, counts.up)
        assertEquals(0, counts.frozenCount)
        assertEquals(0L, counts.frozenMs)
        assertTrue(counts.stalls.isEmpty())
    }

    @Test fun `a failed start reports an HTTP status or minus one only before first frame`() {
        assertEquals(503, castStartFailureStatus(false, 503))
        assertEquals(-1, castStartFailureStatus(false, null))
        assertEquals(-1, castStartFailureStatus(false, 0))
        assertNull(castStartFailureStatus(true, 503))
    }

    private object Math { fun pow2(n: Int, base: Double): Double { var r = 1.0; repeat(n) { r *= base }; return r } }
}
