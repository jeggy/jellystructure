package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.components.castQueueFor
import dev.jellystructure.shared.tv.Episode
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.Season
import dev.jellystructure.shared.tv.SeriesDetail
import dev.jellystructure.shared.tv.SkipMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2939 (DEB-2916, FR-13, FR-14) — nothing follows an episode of a newest-first series, and a cast of one sends it
 * alone. The stand-in programme is the one in [NewestFirstTest]: season 2026, 12 episodes, E291..E302.
 */
class NewestFirstPlayerTest {
    private fun ep(n: Int) =
        Episode(id = "e$n", episodeNumber = 290 + n, title = "Dagbók $n", runtime = 28, overview = null, stillUrl = null,
            airDate = "2026-10-${n + 17}", file = "/f/e$n.mkv")

    private fun dagbok(newestFirst: Boolean) = SeriesDetail(
        card = MediaCard(id = "series", kind = MediaKind.SERIES, title = "Dagbók", year = null, genre = null, rating = null, posterUrl = null, backdropUrl = null),
        synopsis = null, seasons = listOf(Season(2026, "Season 2026", (1..12).map { ep(it) })),
        cast = emptyList(), related = emptyList(), newestFirst = newestFirst,
    )

    /** What the page hands the player: the display order, which a newest-first series reverses. */
    private fun context(newestFirst: Boolean, epId: String): EpisodePlayContext {
        val shown = dagbok(newestFirst).forDisplay()
        return buildEpisodeContext(shown, 0, shown.seasons[0].episodes, epId, emptyMap(), "en")
    }

    @Test fun `FR-13 a newest-first episode has no next episode even one never seen`() {
        // Newest first, e10 sits at index 2 of 12: the episode after it in the rail is e9, which nobody has watched.
        val ctx = context(newestFirst = true, epId = "e10")
        assertNull(ctx.nextEpId); assertNull(ctx.nextEpLabel); assertNull(ctx.nextEpTitle)
        assertTrue(ctx.nothingFollows)
    }

    @Test fun `FR-13 the newest episode and the oldest both have nothing after them`() {
        assertNull(context(true, "e12").nextEpId)
        assertNull(context(true, "e1").nextEpId)
    }

    @Test fun `FR-13 the player's rail lists the episodes newest first and still holds all of them`() {
        val ctx = context(newestFirst = true, epId = "e10")
        assertEquals((12 downTo 1).map { "e$it" }, ctx.episodes.map { it.id })
        assertEquals(2, ctx.currentEpIndex)
    }

    @Test fun `FR-13 a series that is not marked still has its next episode`() {
        val ctx = context(newestFirst = false, epId = "e5")
        assertEquals("e6", ctx.nextEpId)
        assertFalse(ctx.nothingFollows)
    }

    @Test fun `FR-13 no credits card rises before the end of a newest-first episode`() {
        for (mode in SkipMode.entries) assertFalse(creditsCardEarly(mode, nothingFollows = true), "$mode")
    }

    @Test fun `FR-13 the early card is unchanged for every other episode`() {
        assertTrue(creditsCardEarly(SkipMode.AUTO, nothingFollows = false))
        assertTrue(creditsCardEarly(SkipMode.PROMPT, nothingFollows = false))
        assertFalse(creditsCardEarly(SkipMode.OFF, nothingFollows = false), "R230 — Skip Credits: Off is no mid-playback interruption")
    }

    @Test fun `FR-13 the end of a newest-first episode closes the player with no card for any setting`() {
        for (mode in SkipMode.entries) for (hasNext in listOf(true, false)) for (autoplay in listOf(true, false))
            assertFalse(endOfFileShowsCard(mode, nothingFollows = true, hasNextEpisode = hasNext, autoplayNext = autoplay), "$mode next=$hasNext autoplay=$autoplay")
    }

    @Test fun `FR-13 the end of any other episode is as before`() {
        assertTrue(endOfFileShowsCard(SkipMode.AUTO, nothingFollows = false, hasNextEpisode = false, autoplayNext = false), "a last episode still gets Skip credits")
        assertTrue(endOfFileShowsCard(SkipMode.OFF, nothingFollows = false, hasNextEpisode = true, autoplayNext = true))
        assertFalse(endOfFileShowsCard(SkipMode.OFF, nothingFollows = false, hasNextEpisode = true, autoplayNext = false))
        assertFalse(endOfFileShowsCard(SkipMode.OFF, nothingFollows = false, hasNextEpisode = false, autoplayNext = true))
    }

    @Test fun `FR-14 casting a newest-first episode sends that episode alone`() {
        val ctx = context(newestFirst = true, epId = "e10")
        val (queue, at) = castQueueFor(ctx.episodes, ctx.currentEpIndex, ctx.nothingFollows)
        assertEquals(listOf("e10"), queue.map { it.id })
        assertEquals(0, at)
    }

    @Test fun `FR-14 casting any other episode still sends its whole season`() {
        val ctx = context(newestFirst = false, epId = "e5")
        val (queue, at) = castQueueFor(ctx.episodes, ctx.currentEpIndex, ctx.nothingFollows)
        assertEquals((1..12).map { "e$it" }, queue.map { it.id })
        assertEquals(4, at)
    }

    @Test fun `FR-14 an episode missing from the rail leaves the queue as it was`() {
        val (queue, at) = castQueueFor(emptyList(), 0, nothingFollows = true)
        assertTrue(queue.isEmpty()); assertEquals(0, at)
    }
}
