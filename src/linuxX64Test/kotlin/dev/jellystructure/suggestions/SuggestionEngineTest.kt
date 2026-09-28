package dev.jellystructure.suggestions

import dev.jellystructure.suggestions.SuggestionEngine.Cand
import dev.jellystructure.suggestions.SuggestionEngine.Source
import dev.jellystructure.suggestions.SuggestionEngine.Viewer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 274 — the suggestion list's rules, on made-up films. */
class SuggestionEngineTest {
    private val year = 2026
    private fun cand(id: Int, rating: Double = 7.2, votes: Int = 5000, y: Int = 2010, genres: List<Int> = listOf(18)) = Cand(id, "Film $id", y, rating, votes, genres)
    private fun src(id: Int, w: Double = 1.0, verb: String = SuggestionEngine.VERB_FINISHED, at: Long = id.toLong()) = Source(id, "Seen $id", w, verb, at)

    @Test fun a_source_weighs_by_finished_and_by_how_recent() {
        assertEquals(1.5, SuggestionEngine.sourceWeight(true, 10.0))
        assertEquals(1.0, SuggestionEngine.sourceWeight(true, 100.0))
        assertEquals(0.7, SuggestionEngine.sourceWeight(true, 400.0), 1e-9)
        assertEquals(0.75, SuggestionEngine.sourceWeight(false, 10.0))
    }

    @Test fun the_floor_is_a_rating_and_enough_votes_or_a_new_film_with_a_hundred() {
        assertTrue(SuggestionEngine.passesFloor(cand(1, 6.3, 400), year))
        assertFalse(SuggestionEngine.passesFloor(cand(1, 6.2, 90_000), year))
        assertFalse(SuggestionEngine.passesFloor(cand(1, 7.0, 300, y = 2020), year))
        assertTrue(SuggestionEngine.passesFloor(cand(1, 7.0, 150, y = 2025), year))
        assertTrue(SuggestionEngine.fresh(cand(1, 7.0, 150, y = 2025), year))
        assertFalse(SuggestionEngine.fresh(cand(1, 7.0, 5000, y = 2025), year))
    }

    @Test fun two_viewers_reaching_a_film_lift_it_and_the_because_line_names_both() {
        val a = Viewer("a", "Ann", listOf(src(10), src(11)))
        val b = Viewer("b", "Bo", listOf(src(20, verb = SuggestionEngine.VERB_WATCHING, w = 0.5)))
        val graph = mapOf(10 to listOf(cand(100), cand(101)), 11 to listOf(cand(100)), 20 to listOf(cand(100), cand(102)))
        val s = SuggestionEngine.score(listOf(a, b), graph, excluded = setOf(102), leanings = SuggestionEngine.Leanings(), thisYear = year)
        assertEquals(listOf(100, 101), s.map { it.cand.tmdbId }, "102 is excluded")
        val top = s.first()
        assertEquals(2, top.viewers)
        assertEquals((1.0 + 1.0 + 0.5) * 1.25 * (1 + 0.9 * 0.08), top.score, 1e-9)
        assertEquals(listOf("Ann" to "finished", "Bo" to "watching"), top.because.map { it.viewer to it.verb })
        assertEquals(listOf("Seen 11", "Seen 10"), top.because.first().titles, "newest first")
    }

    @Test fun not_interested_weighs_its_sources_down_and_too_old_leans_newer() {
        val v = Viewer("a", "Ann", listOf(src(10), src(11)))
        val graph = mapOf(10 to listOf(cand(100, y = 1970)), 11 to listOf(cand(101, y = 2024)))
        val plain = SuggestionEngine.score(listOf(v), graph, emptySet(), SuggestionEngine.Leanings(), year).associate { it.cand.tmdbId to it.score }
        val leaned = SuggestionEngine.score(listOf(v), graph, emptySet(),
            SuggestionEngine.Leanings(mapOf(11 to SuggestionEngine.notInterestedMultiplier(1)), tooOldSteps = 1), year).associate { it.cand.tmdbId to it.score }
        assertEquals(plain.getValue(101) * 0.8, leaned.getValue(101), 1e-9)
        assertTrue(leaned.getValue(100) < plain.getValue(100))
        assertEquals(0.4, SuggestionEngine.notInterestedMultiplier(9))
    }

    @Test fun one_film_per_franchise_and_the_first_when_the_library_has_none_of_it() {
        val scored = SuggestionEngine.score(listOf(Viewer("a", "Ann", listOf(src(10)))),
            mapOf(10 to listOf(cand(203, 8.0), cand(202, 7.5), cand(301, 7.0), cand(400, 6.9))), emptySet(), SuggestionEngine.Leanings(), year)
        val coll = mapOf(203 to 2, 202 to 2, 301 to 3, 400 to null)
        val first = mapOf(2 to cand(201, y = 1999), 3 to cand(300, y = 1990))
        val picks = SuggestionEngine.onePerFranchise(scored, coll, ownedCollections = setOf(3), firstOf = first, excluded = emptySet())
        assertEquals(listOf(201, 301, 400), picks.map { it.scored.cand.tmdbId })
        assertEquals("Film 203", picks[0].franchiseOf, "the sequel the graph pointed at")
        assertNull(picks[1].franchiseOf, "the library owns part of collection 3: its best member")
        val blocked = SuggestionEngine.onePerFranchise(scored, coll, setOf(3), first, excluded = setOf(201))
        assertEquals(203, blocked[0].scored.cand.tmdbId, "the first film is excluded: the best member instead")
    }

    @Test fun shares_split_a_title_between_its_clusters_and_count_series_lightly() {
        val defs = SuggestionEngine.FALLBACK
        val shares = SuggestionEngine.shares(defs, listOf(
            SuggestionEngine.Taste(setOf(27, 53), emptySet(), 1.0, film = true),
            SuggestionEngine.Taste(setOf(35), emptySet(), SuggestionEngine.seriesWeight(80), film = false),
        ))
        assertEquals(0.5, shares[0].weight); assertEquals(0.5, shares[2].weight)
        assertEquals(3.0, shares[4].weight, "80 episodes cap at three films' worth")
        assertEquals(0, shares[4].finishedFilms, "a series is not a finished film")
        assertEquals(1, shares[0].finishedFilms)
    }

    @Test fun a_household_a_third_horror_gets_about_a_third_of_the_slots_however_dense_the_family_graph() {
        // 32 % horror, 40 % family; family has four times the candidates.
        val alloc = SuggestionEngine.slots(listOf(32.0, 40.0, 11.0, 9.0, 5.0, 3.0), listOf(12, 50, 9, 8, 7, 6))
        assertEquals(20, alloc.sum())
        assertTrue(alloc[0] in 6..7, "horror got ${alloc[0]}")
        assertTrue(alloc.all { it >= 1 }, "every cluster with a candidate gets one")
        assertEquals(listOf(0, 20), SuggestionEngine.slots(listOf(5.0, 1.0), listOf(0, 40)), "no candidates, no slot")
        assertEquals(listOf(2, 18), SuggestionEngine.slots(listOf(9.0, 1.0), listOf(2, 40)), "never more than a cluster has")
    }

    @Test fun a_candidate_falls_in_the_first_fallback_cluster_its_genres_touch() {
        assertEquals("Horror", SuggestionEngine.fallbackCluster(listOf(53, 27)))
        assertEquals("Family & animation", SuggestionEngine.fallbackCluster(listOf(16, 35)))
        assertEquals("Drama", SuggestionEngine.fallbackCluster(listOf(99)))
        assertEquals("Drama", SuggestionEngine.fallbackCluster(emptyList()))
    }
}
