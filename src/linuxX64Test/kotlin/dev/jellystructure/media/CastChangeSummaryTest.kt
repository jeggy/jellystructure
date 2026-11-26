package dev.jellystructure.media

import dev.jellystructure.model.Person
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CastChangeSummaryTest {
    private fun p(id: Int, character: String = "A", order: Int = 0, thumb: String? = "/t") =
        Person(tmdbId = id, name = "P$id", profilePath = thumb, character = character, order = order)

    @Test
    fun `an equal cast reports nothing`() {
        assertNull(castChangeSummary(listOf(p(1), p(2)), listOf(p(1), p(2))))
    }

    @Test
    fun `a role and order swap on one person and a moved thumb on another are counted`() {
        val old = listOf(p(1, "Jocasta", 5), p(2, thumb = "/a"))
        val fresh = listOf(p(1, "Butcherbird", 8), p(2, thumb = "/b"))
        assertEquals("3 person(s) (1 role, 1 order, 1 thumb, 0 added, 0 removed)", castChangeSummary(old, fresh))
    }

    @Test
    fun `added and removed people are counted`() {
        assertEquals("2 person(s) (0 role, 0 order, 0 thumb, 1 added, 1 removed)", castChangeSummary(listOf(p(1)), listOf(p(2))))
    }
}
