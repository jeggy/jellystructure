package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.HomeFeed
import dev.jellystructure.shared.tv.Hero
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.Row
import dev.jellystructure.shared.tv.RowKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** DEB-4396 (DEB-4390, FR-4.3) — what a Home seeded from the R212 snapshot asks the image loader for at once. */
class HomeSeedWarmTest {
    private fun card(id: String) = MediaCard(
        id = id, title = id, year = null, genre = null, rating = null,
        posterUrl = "/api/tv/image/$id/poster?v=1", backdropUrl = "/api/tv/image/$id/backdrop?v=1",
    )

    private fun row(id: String, kind: RowKind, n: Int) = Row(id = id, title = id, kind = kind, items = (1..n).map { card("$id$it") })

    private val hero = Hero(card("h"), null, "/api/tv/image/h/backdrop?v=1", "/api/tv/image/h/logo?v=1", null, null)

    @Test fun `the hero's first backdrop and logo and the first two rows' tiles, at the sizes the tiles ask for`() {
        val feed = HomeFeed(
            heroes = listOf(hero, hero.copy(backdropUrl = "/api/tv/image/h2/backdrop?v=1", logoUrl = null)),
            channels = emptyList(),
            rows = listOf(row("c", RowKind.CONTINUE, 2), row("a", RowKind.CUSTOM, 20), row("b", RowKind.CUSTOM, 3)),
        )
        val urls = homeSeedWarmUrls(feed)
        assertEquals(listOf("/api/tv/image/h/backdrop?v=1", "/api/tv/image/h/logo?v=1"), urls.take(2))
        // Continue Watching is landscape: the backdrop at ?w=640, exactly as its tiles request it.
        assertEquals(listOf("/api/tv/image/c1/backdrop?v=1&w=640", "/api/tv/image/c2/backdrop?v=1&w=640"), urls.slice(2..3))
        // The second row is posters, no ?w=, capped at one row-ahead window of tiles; the third row is not touched.
        assertEquals((1..8).map { "/api/tv/image/a$it/poster?v=1" }, urls.drop(4))
    }

    @Test fun `a feed with no hero, no logo or no rows asks only for what it has`() {
        assertEquals(emptyList(), homeSeedWarmUrls(HomeFeed(heroes = emptyList(), channels = emptyList(), rows = emptyList())))
        assertEquals(listOf("/api/tv/image/h/backdrop?v=1"), homeSeedWarmUrls(HomeFeed(listOf(hero.copy(logoUrl = null)), emptyList(), emptyList())))
    }
}
