package dev.jellystructure.tv

import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase 211 — pins the regression: [PlaystateCache.idsToRefresh] must include every episode's own
 * jellyfinId, not just each series' top-level one. Before this phase, a series' episodes never appeared
 * in the id list `refreshOne` fetches, so `DetailService.getPlaystate` (called with episode ids on every
 * season open) missed unconditionally — reported live as "all progress and next-up gone on every series."
 */
class PlaystateCacheTest {
    private fun movie(id: String) = MediaItem(
        id = id, title = id, year = 2020, kind = MediaKind.MOVIE, path = "/movies/$id.mkv", tmdbId = null,
        originalLanguage = null, posterPath = null, overview = null, tracks = emptyList(), issueCount = 0,
        scannedAt = 0L, episodes = emptyList(), jellyfinId = id,
    )

    private fun episode(jellyfinId: String?, season: Int, ep: Int) = Episode(
        filename = "S${season}E$ep.mkv", path = "/series/s/S${season}E$ep.mkv", seasonNumber = season,
        episodeNumber = ep, tracks = emptyList(), issueCount = 0, jellyfinId = jellyfinId,
    )

    private fun series(id: String, episodes: List<Episode>) = MediaItem(
        id = id, title = id, year = 2020, kind = MediaKind.TV_SHOW, path = "/series/$id", tmdbId = null,
        originalLanguage = null, posterPath = null, overview = null, tracks = emptyList(), issueCount = 0,
        scannedAt = 0L, episodes = episodes, jellyfinId = "jf-$id",
    )

    @Test
    fun `idsToRefresh includes every episode id alongside the series' own top-level id`() {
        val show = series("show1", listOf(episode("jf-e1", 1, 1), episode("jf-e2", 1, 2)))

        val ids = PlaystateCache.idsToRefresh(listOf(show))

        assertEquals(setOf("jf-show1", "jf-e1", "jf-e2"), ids.toSet())
    }

    @Test
    fun `idsToRefresh skips episodes never yet assigned a jellyfinId`() {
        val show = series("show1", listOf(episode("jf-e1", 1, 1), episode(null, 1, 2)))

        val ids = PlaystateCache.idsToRefresh(listOf(show))

        assertEquals(setOf("jf-show1", "jf-e1"), ids.toSet())
    }

    @Test
    fun `idsToRefresh still covers plain movies with no episodes`() {
        val ids = PlaystateCache.idsToRefresh(listOf(movie("m1")))

        assertEquals(listOf("m1"), ids)
    }
}
