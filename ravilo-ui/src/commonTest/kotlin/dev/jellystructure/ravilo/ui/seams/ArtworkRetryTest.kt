package dev.jellystructure.ravilo.ui.seams

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-4396 (DEB-4390, FR-1, FR-3) — the retry schedule, the "retry now" nudge, and the 60 s foreground rule. */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtworkRetryTest {
    @Test fun `the wait is 1 2 4 8 16 seconds, then 30 for as long as the tile stays`() {
        assertEquals(listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000, 30_000), (1..8).map(ArtworkRetry::delayMs))
    }

    @Test fun `a retry waits out its backoff when nothing nudges`() = runTest {
        val signal = MutableStateFlow(0)
        ArtworkRetry.waitForRetry(3, signal)
        assertEquals(4_000L, currentTime)
    }

    @Test fun `a nudge ends the wait at once`() = runTest {
        val signal = MutableStateFlow(0)
        var done = false
        launch { ArtworkRetry.waitForRetry(6, signal); done = true }
        testScheduler.advanceTimeBy(5_000)
        assertFalse(done)
        signal.value += 1
        testScheduler.runCurrent()
        assertTrue(done)
        assertEquals(5_000L, currentTime)
    }

    @Test fun `waking after 60 seconds or more evicts, a shorter nap does not`() {
        val gate = ForegroundEvictGate()
        gate.onBackground(1_000)
        assertFalse(gate.shouldEvict(60_999))
        gate.onBackground(100_000)
        assertTrue(gate.shouldEvict(160_000))
    }

    @Test fun `a foreground with no background before it never evicts, and a second one does not either`() {
        val gate = ForegroundEvictGate()
        assertFalse(gate.shouldEvict(500_000))
        gate.onBackground(0)
        gate.onBackground(50_000)   // the screen-off after the stop is the same stay
        assertTrue(gate.shouldEvict(120_000))
        assertFalse(gate.shouldEvict(300_000))
    }
}
