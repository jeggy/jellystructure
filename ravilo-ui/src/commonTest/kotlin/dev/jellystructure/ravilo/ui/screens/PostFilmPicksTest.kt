package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.MediaCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DEB-5841 (FR-3) — the after-film page's picks rule. */
class PostFilmPicksTest {
    private fun card(id: String, watched: Boolean = false) =
        MediaCard(id = id, title = "Title $id", year = null, genre = null, rating = null,
            posterUrl = null, backdropUrl = null, watched = watched)

    @Test fun `FR-3 unwatched titles keep the server's order`() {
        val picks = postFilmPicks(listOf(card("a"), card("b"), card("c")), playedId = "x")
        assertEquals(listOf("a", "b", "c"), picks.map { it.id })
    }

    @Test fun `FR-3 watched titles are dropped`() {
        val picks = postFilmPicks(listOf(card("a", watched = true), card("b"), card("c", watched = true)), "x")
        assertEquals(listOf("b"), picks.map { it.id })
    }

    @Test fun `FR-3 the film just played is never a pick`() {
        val picks = postFilmPicks(listOf(card("a"), card("played"), card("b")), "played")
        assertEquals(listOf("a", "b"), picks.map { it.id })
    }

    @Test fun `FR-3 at most six picks, taken after the watched ones are dropped`() {
        val related = listOf(card("w1", true), card("w2", true)) + (1..10).map { card("u$it") }
        val picks = postFilmPicks(related, "x")
        assertEquals((1..6).map { "u$it" }, picks.map { it.id })
    }

    @Test fun `FR-3 nothing related or all watched is empty, which means no page`() {
        assertTrue(postFilmPicks(emptyList(), "x").isEmpty())
        assertTrue(postFilmPicks(listOf(card("a", true), card("b", true)), "x").isEmpty())
    }
}
