package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** DEB-4832 — which episode OK plays on a series' tile when the TV has no series page (DEB-4654 FR-9). */
class SeriesPlayTest {

    private fun ep(id: String, number: Int) =
        Episode(id = id, episodeNumber = number, title = "E$number", runtime = 40, overview = null, stillUrl = null)

    private fun detail(vararg seasons: Season, newestFirst: Boolean = false) = SeriesDetail(
        card = MediaCard(id = "series", kind = MediaKind.SERIES, title = "A Series", year = 2020, genre = null, rating = null, posterUrl = null, backdropUrl = null),
        synopsis = null, seasons = seasons.toList(), cast = emptyList(), related = emptyList(), newestFirst = newestFirst,
    )

    private fun season(index: Int, vararg eps: Episode) = Season(index = index, name = "S$index", episodes = eps.toList())

    private val show = detail(
        season(0, ep("sp", 1)),
        season(1, ep("a1", 1), ep("a2", 2)),
        season(2, ep("b1", 1), ep("b2", 2)),
    )

    @Test
    fun `a series nobody has started plays its first counted episode and never a special`() {
        val pick = assertNotNull(pickSeriesPlay(show, null))
        assertEquals("a1", pick.episode.id)
        assertEquals("S01E01", pick.kicker())
        assertEquals("a2", pick.next?.id)
    }

    @Test
    fun `the episode the series Continue Watching entry names wins with the one after it in the next season`() {
        val pick = assertNotNull(pickSeriesPlay(show, "a2"))
        assertEquals("a2", pick.episode.id)
        assertEquals(1, pick.season)
        assertEquals("b1", pick.next?.id)
        assertEquals(2, pick.nextSeason)
    }

    @Test
    fun `a name the series does not hold is ignored`() {
        assertEquals("a1", pickSeriesPlay(show, "gone")?.episode?.id)
    }

    @Test
    fun `the last episode has no next and a special has none either`() {
        assertNull(pickSeriesPlay(show, "b2")?.next)
        assertNull(pickSeriesPlay(show, "sp")?.next)
        assertEquals("S00E01", pickSeriesPlay(show, "sp")?.kicker())
    }

    @Test
    fun `a newest-first series plays its newest counted episode and offers no next`() {
        val pick = assertNotNull(pickSeriesPlay(show.copy(newestFirst = true), "a1"))
        assertEquals("b2", pick.episode.id)
        assertNull(pick.next)
    }

    @Test
    fun `a multi-episode file is one entry with its range and next is the file after it`() {
        val d = detail(season(1, ep("f", 1), ep("f", 2), ep("f", 3), ep("g", 4)))
        val pick = assertNotNull(pickSeriesPlay(d, null))
        assertEquals("f", pick.episode.id)
        assertEquals("S01E01–E03", pick.kicker())
        assertEquals("g", pick.next?.id)
    }

    @Test
    fun `catalog-only rows never play`() {
        val d = detail(season(1, ep("/tv/Show/S01E01.mkv#1", 1), ep("real", 2)))
        assertEquals("real", pickSeriesPlay(d, null)?.episode?.id)
        assertNull(pickSeriesPlay(detail(season(1, ep("/tv/Show/S01E01.mkv#1", 1))), null))
    }

    @Test
    fun `a series with only specials plays its first special`() {
        assertEquals("sp", pickSeriesPlay(detail(season(0, ep("sp", 1))), null)?.episode?.id)
    }
}
