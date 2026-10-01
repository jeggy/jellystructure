package dev.jellystructure.tv

import dev.jellystructure.server.routes.playstateAnswer
import dev.jellystructure.shared.tv.CardPlayState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R343 (FR-R343-13) — after a Start over's stop, the page read *Resume · S01E02* for about three seconds: Jellyfin's
 * own session had written S01E01 back as watched and its NextUp named S01E02, and the caches said so until the
 * write-back's refresh. A hold makes every read and push say what the server is about to write.
 */
class StartOverHoldsTest {
    private val user = "u1"
    private val t0 = 1_000_000L

    @BeforeTest fun reset() = StartOverHolds.clearForTest()
    @AfterTest fun clean() = StartOverHolds.clearForTest()

    /** What the caches held in the window: S01E01 watched (Jellyfin's stop), the Continue pointer on S01E02. */
    private val stale = mapOf(
        "s01e01" to CardPlayState(played = true, resumeMs = 0, playedPct = 1f),
        "s01e02" to CardPlayState(),
        "series" to CardPlayState(favorite = true),
    )
    private val asked = listOf("s01e01", "s01e02", "series")

    @Test fun without_a_hold_the_answer_is_the_caches() {
        val out = playstateAnswer(user, stale, asked, mapOf("series" to "s01e02"), t0)
        assertEquals("s01e02", out["series"]?.continueEpisodeId)
        assertTrue(out["s01e01"]!!.played)
    }

    @Test fun a_held_episode_reads_unwatched_at_its_position_and_the_series_points_at_it() {
        StartOverHolds.hold(user, "series", "s01e01", positionMs = 120_000, durationMs = 1_200_000, nowMs = t0)
        val out = playstateAnswer(user, stale, asked, mapOf("series" to "s01e02"), t0 + 1_000)
        val ep = out["s01e01"]!!
        assertFalse(ep.played); assertEquals(120_000L, ep.resumeMs); assertEquals(0.1f, ep.playedPct)
        assertEquals("s01e01", out["series"]?.continueEpisodeId)
        assertTrue(out["series"]!!.favorite)          // the series' own state is kept
        assertEquals(CardPlayState(), out["s01e02"])  // other episodes untouched
    }

    @Test fun a_held_episode_missing_from_the_cache_is_answered_when_asked() {
        StartOverHolds.hold(user, "series", "s01e01", 60_000, 0, t0)
        val out = playstateAnswer(user, emptyMap(), asked, emptyMap(), t0)
        assertEquals(CardPlayState(played = false, resumeMs = 60_000, playedPct = 0f), out["s01e01"]!!.copy(continueEpisodeId = null))
        // A refresh's map (no ids asked) only rewrites what it carries.
        assertEquals(emptyMap<String, CardPlayState>(), StartOverHolds.overlay(user, emptyMap(), nowMs = t0))
    }

    @Test fun progress_moves_the_hold_and_a_release_ends_it() {
        StartOverHolds.move(user, "s01e01", 5_000, t0)               // no hold yet: nothing
        assertTrue(StartOverHolds.active(user, t0).isEmpty())
        StartOverHolds.hold(user, "series", "s01e01", 60_000, 600_000, t0)
        StartOverHolds.move(user, "s01e01", 90_000, t0 + 10_000)
        assertEquals(90_000L, StartOverHolds.active(user, t0 + 10_000).single().positionMs)
        StartOverHolds.release(user, "s01e01")
        assertTrue(playstateAnswer(user, stale, asked, mapOf("series" to "s01e02"), t0)["s01e01"]!!.played)
    }

    @Test fun a_hold_expires_on_its_own_and_is_per_viewer() {
        StartOverHolds.hold(user, "series", "s01e01", 60_000, 600_000, t0)
        assertTrue(StartOverHolds.continueEpisodes("someone-else", t0).isEmpty())
        assertEquals(mapOf("series" to "s01e01"), StartOverHolds.continueEpisodes(user, t0 + StartOverHolds.TTL_MS - 1))
        assertNull(StartOverHolds.continueEpisodes(user, t0 + StartOverHolds.TTL_MS)["series"])
    }
}
