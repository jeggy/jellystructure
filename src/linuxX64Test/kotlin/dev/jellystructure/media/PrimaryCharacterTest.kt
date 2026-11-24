package dev.jellystructure.media

import dev.jellystructure.tmdb.TmdbAggregateRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** DEB-5479 (FR-5): a person with two credit strings is stored with the same one whatever order TMDB lists them in. */
class PrimaryCharacterTest {
    private val a = TmdbAggregateRole(character = "Rex", episodeCount = 4)
    private val b = TmdbAggregateRole(character = "Rex (voice)", episodeCount = 4)
    private val c = TmdbAggregateRole(character = "Pip", episodeCount = 9)

    @Test fun `a tie on episodes takes the string that sorts first in either order`() {
        assertEquals("Rex", primaryCharacter(listOf(a, b)))
        assertEquals("Rex", primaryCharacter(listOf(b, a)))
    }

    @Test fun `the role with the most episodes wins whatever its place`() {
        assertEquals("Pip", primaryCharacter(listOf(a, b, c)))
        assertEquals("Pip", primaryCharacter(listOf(c, b, a)))
    }

    @Test fun `blank roles are skipped and no role is null`() {
        assertEquals("Rex", primaryCharacter(listOf(TmdbAggregateRole(character = "", episodeCount = 99), a)))
        assertNull(primaryCharacter(emptyList()))
    }
}
