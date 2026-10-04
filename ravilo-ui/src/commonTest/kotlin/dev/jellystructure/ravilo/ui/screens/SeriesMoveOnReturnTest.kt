package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.components.heroStartIndex
import dev.jellystructure.shared.tv.Episode
import dev.jellystructure.shared.tv.Season
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** R365 (FR-R365-9/10) — where Back lands after the player moved on, and which hero slide Home starts on. */
class SeriesMoveOnReturnTest {
    private fun ep(s: Int, e: Int, file: String = "/tv/show/S${s}E$e.mkv") =
        Episode(id = "s${s}e$e", episodeNumber = e, title = "Episode $e", runtime = 11, overview = null, stillUrl = null, file = file)

    private val seasons = listOf(
        Season(index = 1, name = "Season 1", episodes = (1..4).map { ep(1, it) }),
        // E01–E03 in one file: one card, keyed by its first episode
        Season(index = 2, name = "Season 2", episodes = (1..3).map { ep(2, it, "/tv/show/S2E1-3.mkv") } + (4..6).map { ep(2, it) }),
    )

    @Test fun returnFocusForFindsTheSeasonAndTheCard() {
        assertEquals(SeriesReturnFocus.Episode(1, "s2e5"), returnFocusFor(seasons, "s2e5"))
        assertEquals(SeriesReturnFocus.Episode(0, "s1e2"), returnFocusFor(seasons, "s1e2"))
    }

    @Test fun anEpisodeInsideAMultiEpisodeFileIsThatFilesCard() {
        assertEquals(SeriesReturnFocus.Episode(1, "s2e1"), returnFocusFor(seasons, "s2e2"))
    }

    @Test fun anUnknownIdIsNothing() {
        assertNull(returnFocusFor(seasons, "nope"))
    }

    @Test fun theWriteRule() {
        val shuffle = SeriesReturnFocus.Shuffle(0)
        val card = SeriesReturnFocus.Episode(0, "s1e1")
        assertEquals(card, moveOnReturnFocus(card, "s1e2", shuffleActive = false, seriesPageBelow = false), "no series page below: no write")
        assertEquals(shuffle, moveOnReturnFocus(shuffle, "s2e4", shuffleActive = true, seriesPageBelow = true), "a running shuffle keeps Shuffle (owner decision 2)")
        assertEquals(SeriesReturnFocus.EpisodeId("s2e4"), moveOnReturnFocus(shuffle, "s2e4", shuffleActive = false, seriesPageBelow = true), "a rail pick left the shuffle")
        assertEquals(SeriesReturnFocus.EpisodeId("s1e2"), moveOnReturnFocus(card, "s1e2", shuffleActive = false, seriesPageBelow = true))
    }

    @Test fun heroStartIndexIsTheSavedSlideElseTheFirst() {
        val ids = listOf("a", "b", "c", "d", "e")
        assertEquals(4, heroStartIndex(ids, "e"))
        assertEquals(0, heroStartIndex(ids, "gone"), "gone after a home_changed")
        assertEquals(0, heroStartIndex(ids, null))
        assertEquals(0, heroStartIndex(emptyList(), "a"))
    }
}
