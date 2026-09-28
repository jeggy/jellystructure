package dev.jellystructure.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 274 (FR-274-5) — the suggestion groups are used only when the answer is complete and honest. */
class AiClustersTest {
    private val sources = setOf("g27", "g35", "g18", "k10")

    private fun answer(vararg groups: String) = AiRequests.Result("c", "succeeded", "end_turn", """{"clusters":[${groups.joinToString(",")}]}""", null)
    private fun g(name: String, cands: List<Int>, src: List<String> = listOf("g27")) =
        """{"name":"$name","candidates":[${cands.joinToString(",") { "\"c$it\"" }}],"sources":[${src.joinToString(",") { "\"$it\"" }}]}"""

    @Test fun a_complete_answer_is_used_with_its_candidates_by_index() {
        val j = AiRequests.judgeClusters(answer(g("Coastal horror", listOf(1, 2)), g("Family nights", listOf(3), listOf("g35")), g("Quiet dramas", listOf(4), listOf("g18", "k10"))), 4, sources)
        val groups = j.value!!
        assertEquals(listOf("Coastal horror", "Family nights", "Quiet dramas"), groups.map { it.name })
        assertEquals(listOf(0, 1), groups[0].candidates)
        assertEquals("used · 3 groups", j.why)
    }

    @Test fun an_answer_that_leaves_a_candidate_out_is_refused() {
        val j = AiRequests.judgeClusters(answer(g("Coastal horror", listOf(1, 2)), g("Family nights", listOf(3)), g("Quiet dramas", emptyList())), 4, sources)
        assertNull(j.value)
        assertEquals("a candidate left out (c4)", j.why)
    }

    @Test fun every_other_dishonest_shape_is_refused_with_its_reason() {
        fun why(vararg gs: String, n: Int = 3) = AiRequests.judgeClusters(answer(*gs), n, sources).why
        assertEquals("2 groups (3–8 allowed)", why(g("One group", listOf(1, 2)), g("Two group", listOf(3))))
        assertEquals("a candidate in two groups (c1)", why(g("Aaa", listOf(1)), g("Bbb", listOf(1, 2)), g("Ccc", listOf(3))))
        assertEquals("two groups named \"aaa\"", why(g("Aaa", listOf(1)), g("Bbb", listOf(2)), g("aaa", listOf(3))))
        assertEquals("an id it wasn't given (c9)", why(g("Aaa", listOf(1)), g("Bbb", listOf(2)), g("Ccc", listOf(3, 9))))
        assertEquals("an id it wasn't given (g99)", why(g("Aaa", listOf(1)), g("Bbb", listOf(2)), g("Ccc", listOf(3), listOf("g99"))))
        assertTrue(why(g("Ab", listOf(1)), g("Bbb", listOf(2)), g("Ccc", listOf(3))).startsWith("a group name of 2 characters"))
        assertEquals("stopped at the output limit", AiRequests.judgeClusters(AiRequests.Result("c", "succeeded", "max_tokens", "{}", null), 3, sources).why)
    }

    @Test fun the_request_names_ids_and_never_titles_or_viewers() {
        val input = AiRequests.ClustersInput(1L, mapOf(27 to "Horror", 35 to "Comedy"), mapOf(10 to "island life"),
            listOf(AiRequests.TasteLine("film", 1.5, listOf(27), listOf(10))), listOf(AiRequests.CandLine(123, listOf(27), emptyList())))
        val sent = AiRequests.sentOf(AiRequests.clustersRequest("c1", "claude-haiku-4-5", "", input))
        assertTrue("g27 Horror" in sent && "k10 island life" in sent && "c1 · g27" in sent, sent)
        assertTrue("123" !in sent, "the candidate's TMDB id never leaves the house")
        assertEquals(setOf("g27", "k10"), AiRequests.clusterSourceIds(input))
    }
}
