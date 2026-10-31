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

    @Test fun `shaka retries follow FR-4 and its timeout FR-3`() {
        // 0.5, 1, 2, 4 s between five attempts; a request gives up at the player's own 20 s.
        val delays = (0 until CastRecovery.RETRY_ATTEMPTS - 1).map { (CastRecovery.RETRY_BASE_DELAY_MS * Math.pow2(it)).toLong() }
        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L), delays)
        assertEquals(2.0, CastRecovery.RETRY_BACKOFF)
        assertEquals(20, CastRecovery.REQUEST_TIMEOUT_SEC)
    }

    private object Math { fun pow2(n: Int): Double { var r = 1.0; repeat(n) { r *= CastRecovery.RETRY_BACKOFF }; return r } }
}
