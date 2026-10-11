package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** R351 (FR-R351-13) — the receiver's next-up trigger; R384 — with no trusted marker, 1 s before the end. */
class NextUpTriggerTest {
    private val duration = 2_128_071L   // a 35:28 episode

    @Test fun aMarkerInTheLastSecondsLeavesRoomForTheWholeCountdown() {
        // The re-test's episode: the heuristic marker 1.8 s before the end. The card now starts 8 s before the end.
        assertEquals(duration - 6_000 - 2_000, nextUpStartMs(duration, 2_126_237L, countdownSecs = 6))
        // A longer countdown starts earlier still.
        assertEquals(duration - 8_000 - 2_000, nextUpStartMs(duration, 2_126_237L, countdownSecs = 8))
    }

    @Test fun aMarkerWithRoomIsKeptAsItIs() {
        val credits = duration - 60_000
        assertEquals(credits, nextUpStartMs(duration, credits, countdownSecs = 6))
        // Exactly at the cap is still the marker.
        assertEquals(duration - 8_000, nextUpStartMs(duration, duration - 8_000, countdownSecs = 6))
    }

    @Test fun noTrustedMarkerWaitsUntilOneSecondIsLeft() {
        // R384 (FR-R384-1) — whatever the countdown: the episode plays to its end and the countdown runs on after it.
        for (secs in listOf(4, 6, 8, 20)) assertEquals(duration - 1_000, nextUpStartMs(duration, null, countdownSecs = secs))
        // A marker in the first half belongs to another stream (the auto-advance fix) and is ignored.
        assertEquals(duration - 1_000, nextUpStartMs(duration, 60_000L, countdownSecs = 6))
        // So is one past the end, and one at 0.
        assertEquals(duration - 1_000, nextUpStartMs(duration, duration + 5_000, countdownSecs = 6))
        assertEquals(duration - 1_000, nextUpStartMs(duration, 0L, countdownSecs = 6))
    }

    @Test fun aOneMinuteEpisodeWithNoMarkerPlaysToItsLastSecond() {
        assertEquals(59_000L, nextUpStartMs(60_000L, null, countdownSecs = 6))
    }

    @Test fun anUnknownDurationShowsNothing() {
        assertNull(nextUpStartMs(0L, 2_126_237L, countdownSecs = 6))
        assertNull(nextUpStartMs(-1L, null, countdownSecs = 6))
    }

    @Test fun aClipShorterThanOneSecondStartsAtZero() {
        assertEquals(0L, nextUpStartMs(500L, null, countdownSecs = 6))
        // A 5 s clip with no marker is no longer pulled back to the countdown: 1 s before its end.
        assertEquals(4_000L, nextUpStartMs(5_000L, null, countdownSecs = 6))
    }
}
