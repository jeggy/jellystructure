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

/** DEB-5836, FR-5: counts and runtimes come through the translator, not Kotlin literals. */
class FocusDetailCountTextTest {
    private val fake: (String, Map<String, String>) -> String = { k, v -> "$k${v["n"]?.let { "($it)" } ?: ""}" }
    private fun facts(seasons: Int? = null, episodes: Int? = null, runtime: Int? = null) =
        dev.jellystructure.shared.tv.FocusDetailFacts(seasons = seasons, episodes = episodes, runtimeMinutes = runtime)

    @Test fun oneSeasonUsesTheSingularKey() = assertEquals("fd.season_one", focusDetailCountText(facts(1), fake))

    @Test fun manySeasonsAndEpisodesJoinWithADot() =
        assertEquals("fd.seasons(3) · fd.episodes(24)", focusDetailCountText(facts(3, 24), fake))

    @Test fun oneEpisodeUsesTheSingularKey() = assertEquals("fd.season_one · fd.episode_one", focusDetailCountText(facts(1, 1), fake))

    @Test fun aFilmUsesTheRuntimeKey() = assertEquals("up.runtime_min(95)", focusDetailCountText(facts(runtime = 95), fake))

    @Test fun nothingKnownIsNoLine() = assertEquals(null, focusDetailCountText(facts(), fake))
}
