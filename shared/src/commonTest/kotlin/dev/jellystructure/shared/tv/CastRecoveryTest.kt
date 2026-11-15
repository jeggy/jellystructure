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

    private object Math { fun pow2(n: Int, base: Double): Double { var r = 1.0; repeat(n) { r *= base }; return r } }
}
