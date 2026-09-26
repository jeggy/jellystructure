package dev.jellystructure.server

import dev.jellystructure.server.routes.withContinueEpisodes
import dev.jellystructure.shared.tv.CardPlayState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** R306 (FR-R306-5) — the series page's Continue Watching episode rides the playstate it already fetches. */
class ContinueEpisodeMergeTest {
    @Test
    fun `a series that was asked for gets its continue episode on its own entry`() {
        val out = withContinueEpisodes(mapOf("series" to CardPlayState(favorite = true), "ep1" to CardPlayState(resumeMs = 5)), listOf("series", "ep1"), mapOf("series" to "s22e4"))
        assertEquals("s22e4", out["series"]?.continueEpisodeId)
        assertEquals(true, out["series"]?.favorite, "the series' own state is kept")
        assertNull(out["ep1"]?.continueEpisodeId)
    }

    @Test
    fun `a series with no playstate of its own still gets an entry`() {
        val out = withContinueEpisodes(emptyMap(), listOf("series"), mapOf("series" to "s22e4"))
        assertEquals(CardPlayState(continueEpisodeId = "s22e4"), out["series"])
    }

    @Test
    fun `a series nobody asked for is not added and no hit changes nothing`() {
        val ps = mapOf("ep1" to CardPlayState(resumeMs = 5))
        assertSame(ps, withContinueEpisodes(ps, listOf("ep1"), mapOf("other" to "x")))
    }
}
