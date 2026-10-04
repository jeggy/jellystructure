package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.CardPlayState
import dev.jellystructure.shared.tv.Episode
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.Season
import dev.jellystructure.shared.tv.SeriesDetail
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R346 (the episodes that count, the opening season, the Play fallback) and R343 (finished, the shuffle plan). */
class SeriesEpisodesTest {
    private fun ep(s: Int, e: Int, id: String = "s${s}e$e", file: String = "/f/s${s}e$e.mkv") =
        Episode(id = id, episodeNumber = e, title = "Episode $e", runtime = 11, overview = null, stillUrl = null, file = file)

    // Specials last, as R346's server sends them; one catalog-only row in season 2.
    private val detail = SeriesDetail(
        card = MediaCard(id = "series", kind = MediaKind.SERIES, title = "Stand-in", year = null, genre = null, rating = null, posterUrl = null, backdropUrl = null),
        synopsis = null,
        seasons = listOf(
            Season(1, "Season 1", (1..4).map { ep(1, it) }),
            Season(2, "Season 2", (1..4).map { ep(2, it) } + ep(2, 5, id = "/f/s2e5.mkv#5")),
            Season(0, "Specials", listOf(ep(0, 1))),
        ),
        cast = emptyList(), related = emptyList(),
    )
    private fun played(vararg ids: String) = ids.associateWith { CardPlayState(played = true, playedPct = 1f) } + ("series" to CardPlayState())
    private val season1 = (1..4).map { "s1e$it" }.toTypedArray()
    private val season2 = (1..4).map { "s2e$it" }.toTypedArray()

    @Test fun `specials and unplayable rows do not count`() {
        assertEquals(8, countedEpisodes(detail.seasons).size)
        assertTrue(countedEpisodes(detail.seasons).none { it.isCatalogOnly() || it.id == "s0e1" })
    }

    @Test fun `every counted episode watched is finished — whatever the specials say`() {
        assertTrue(seriesFinished(countedEpisodes(detail.seasons), played(*season1, *season2)))
        assertFalse(seriesFinished(countedEpisodes(detail.seasons), played(*season1)))
    }

    @Test fun `the page opens on the first season with something unwatched — never on Specials`() {
        assertEquals(1, openingSeasonIndex(detail.seasons, played(*season1, "s2e1")))
        assertEquals(0, openingSeasonIndex(detail.seasons, played()))
    }

    @Test fun `a finished series opens on Season 1 and its button plays S01E01`() {
        val all = played(*season1, *season2)
        assertEquals(0, openingSeasonIndex(detail.seasons, all))
        assertEquals("s1e1", primaryEpisodeId(detail, all))
    }

    @Test fun `the Play fallback is never a special — but a started special still resumes`() {
        assertEquals("s2e1", primaryEpisodeId(detail, played(*season1)))
        val startedSpecial = played(*season1) + ("s0e1" to CardPlayState(resumeMs = 60_000))
        assertEquals("s0e1", primaryEpisodeId(detail, startedSpecial))
    }

    @Test fun `a shuffle plays every counted episode once — a multi-episode file as one entry — and ends`() {
        val withFile = detail.copy(seasons = listOf(
            Season(1, "Season 1", listOf(ep(1, 1, "jf-a", "/f/x.mkv").copy(partCount = 3), ep(1, 2, "jf-a", "/f/x.mkv").copy(partCount = 3, partIndex = 1),
                ep(1, 3, "jf-a", "/f/x.mkv").copy(partCount = 3, partIndex = 2), ep(1, 4))),
            detail.seasons[1], detail.seasons[2],
        ))
        val plan = buildShufflePlan(withFile, emptyMap(), "en", Random(7))
        assertEquals(listOf("jf-a", "s1e4", "s2e1", "s2e2", "s2e3", "s2e4").sorted(), plan.map { it.episodeId }.sorted())
        plan.zipWithNext().forEach { (a, b) -> assertEquals(b.episodeId, a.nextEpId) }
        assertNull(plan.last().nextEpId, "the last entry ends playback")
        assertTrue(plan.all { it.kicker!!.startsWith("Shuffle · S0") }, plan.map { it.kicker }.toString())
    }

    // ── R350 (FR-R350-3) ───────────────────────────────────────────────────────────────────────────

    @Test fun `R350-3 — the page opens on the season holding the primary button's episode`() {
        // Continue Watching names S02E03 while S01E04 is still unwatched: the button resumes S02E03, so the page
        // opens on Season 2 (it opened on Season 1 before).
        val overlay = played("s1e1", "s1e2", "s1e3", "s2e1", "s2e2") + ("series" to CardPlayState(continueEpisodeId = "s2e3"))
        val primary = primaryEpisodeId(detail, overlay)
        assertEquals("s2e3", primary)
        assertEquals(1, openingSeasonIndex(detail.seasons, overlay, primary))
        // A started special is what the button plays, so the page opens on Specials.
        val startedSpecial = played(*season1) + ("s0e1" to CardPlayState(resumeMs = 60_000))
        assertEquals(2, openingSeasonIndex(detail.seasons, startedSpecial, primaryEpisodeId(detail, startedSpecial)))
        // Finished: Season 1 (the primary is S01E01).
        val all = played(*season1, *season2)
        assertEquals(0, openingSeasonIndex(detail.seasons, all, primaryEpisodeId(detail, all)))
        // No primary, or one no season holds: R346's rule.
        assertEquals(1, openingSeasonIndex(detail.seasons, played(*season1, "s2e1"), null))
        assertEquals(1, openingSeasonIndex(detail.seasons, played(*season1, "s2e1"), "gone"))
    }

    // ── R375 (FR-R375-5) ───────────────────────────────────────────────────────────────────────────

    @Test fun `R375 — a finished series the server names an episode for resumes it and opens on its season`() {
        val overlay = played(*season1, *season2) + ("series" to CardPlayState(continueEpisodeId = "s2e3"))
        assertEquals("s2e3", rewatchEpisodeId(detail, overlay))
        assertEquals("s2e3", primaryEpisodeId(detail, overlay))
        assertEquals(1, openingSeasonIndex(detail.seasons, overlay, primaryEpisodeId(detail, overlay)))
    }

    @Test fun `R375 — a finished series with nothing named — or a special named — starts over`() {
        val nothing = played(*season1, *season2)
        assertNull(rewatchEpisodeId(detail, nothing))
        assertEquals("s1e1", primaryEpisodeId(detail, nothing))
        val special = played(*season1, *season2) + ("series" to CardPlayState(continueEpisodeId = "s0e1"))
        assertNull(rewatchEpisodeId(detail, special), "a special is not a counted episode")
        assertEquals("s1e1", primaryEpisodeId(detail, special))
    }

    @Test fun `R375 — an unfinished series is unchanged and has no rewatch episode`() {
        val overlay = played(*season1) + ("series" to CardPlayState(continueEpisodeId = "s2e3"))
        assertNull(rewatchEpisodeId(detail, overlay))
        assertEquals("s2e3", primaryEpisodeId(detail, overlay))
    }
}
