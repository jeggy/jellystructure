package dev.jellystructure.ravilo.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/** DEB-5836, FR-1: `progressPct` is 0..1, so minutes left is the film's real remainder. */
class FocusDetailFormatTest {
    @Test fun halfWatchedHundredTwentyMinuteFilmHasSixtyLeft() = assertEquals(60, minutesLeft(120, 0.5f))

    @Test fun almostFinishedReadsOneMinute() = assertEquals(1, minutesLeft(120, 0.99f))

    @Test fun neverBelowOneMinute() = assertEquals(1, minutesLeft(90, 0.999f))

    @Test fun barelyStartedKeepsMostOfTheRuntime() = assertEquals(108, minutesLeft(120, 0.1f))
}
