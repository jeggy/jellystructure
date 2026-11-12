package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.components.castQueueFor
import dev.jellystructure.shared.tv.Episode
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.Season
import dev.jellystructure.shared.tv.SeriesDetail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** DEB-4425 (DEB-4424, FR-R375-8/9): series order and the current-season rail have different scopes. */
class NextSeasonPlayerTest {
    private fun ep(season: Int, number: Int, id: String = "s${season}e$number", file: String = "$id.mkv") =
        Episode(id, number, "Episode $number", 28, null, null, file = file)

    private fun detail(newestFirst: Boolean = false) = SeriesDetail(
        card = MediaCard("series", MediaKind.SERIES, "Harbor Stories", null, null, null, null, null),
        synopsis = null,
        seasons = listOf(
            Season(0, "Specials", listOf(ep(0, 1), ep(0, 2))),
            Season(1, "Season 1", listOf(ep(1, 1), ep(1, 2))),
            Season(2, "Season 2", listOf(
                ep(2, 1, id = "/catalog/season2/episode1", file = ""),
                ep(2, 2, id = "s2e2", file = "s2e2-3.mkv").copy(partCount = 2),
                ep(2, 3, id = "s2e2", file = "s2e2-3.mkv").copy(partIndex = 1, partCount = 2),
                ep(2, 4),
            )),
            Season(3, "Season 3", listOf(ep(3, 1))),
        ),
        cast = emptyList(), related = emptyList(), newestFirst = newestFirst,
    )

    private fun context(detail: SeriesDetail, season: Int, id: String): EpisodePlayContext {
        val index = detail.seasons.indexOfFirst { it.index == season }
        return buildEpisodeContext(detail, index, detail.seasons[index].episodes, id, emptyMap(), "en")
    }

    @Test fun `FR-R375-8 a season finale offers the next playable file with its own kicker`() {
        val ctx = context(detail(), 1, "s1e2")
        assertEquals("s2e2", ctx.nextEpId)
        assertEquals("S02E02–E03", ctx.nextEpLabel)
        assertEquals(listOf("s1e1", "s1e2"), ctx.episodes.map { it.id })
        assertEquals(listOf("s1e1", "s1e2", "s2e2", "s2e4", "s3e1"), ctx.seriesEpisodes.map { it.id })
    }

    @Test fun `FR-R375-9 advancing switches the rail and keeps the next episode`() {
        val ctx = context(detail(), 1, "s1e2")
        val first = advanceSeriesEpisode(ctx.seriesEpisodes, ctx.nextEpId!!, ctx.nothingFollows)!!
        assertEquals(listOf("s2e2", "s2e4"), first.seasonEpisodes.map { it.id })
        assertEquals(0, first.seasonIndex)
        assertEquals(2, first.episode.seasonNumber)
        assertEquals("s2e4", first.next?.id)
        val second = advanceSeriesEpisode(ctx.seriesEpisodes, first.next!!.id, ctx.nothingFollows)!!
        assertEquals("s3e1", second.next?.id)
        assertNull(advanceSeriesEpisode(ctx.seriesEpisodes, "s3e1", ctx.nothingFollows)!!.next)
    }

    @Test fun `FR-R375-8 cast receiver receives the same order across seasons`() {
        val ctx = context(detail(), 1, "s1e2")
        val at = ctx.seriesEpisodes.indexOfFirst { it.id == ctx.episodeId }
        val (queue, current) = castQueueFor(ctx.seriesEpisodes, at, ctx.nothingFollows)
        assertEquals(1, current)
        assertEquals(listOf("s1e1", "s1e2", "s2e2", "s2e4", "s3e1"), queue.map { it.id })
    }

    @Test fun `FR-R375-8 a season with no playable episode is skipped`() {
        val base = detail()
        val emptySecondSeason = base.copy(seasons = base.seasons.map { season ->
            if (season.index == 2) season.copy(episodes = listOf(ep(2, 1, id = "/catalog/only", file = ""))) else season
        })
        assertEquals("s3e1", context(emptySecondSeason, 1, "s1e2").nextEpId)
    }

    @Test fun `FR-R375-8 the last episode specials and newest-first episodes have no next`() {
        assertNull(context(detail(), 3, "s3e1").nextEpId)
        assertNull(context(detail(), 0, "s0e1").nextEpId)
        assertNull(context(detail(newestFirst = true), 1, "s1e1").nextEpId)
    }
}
