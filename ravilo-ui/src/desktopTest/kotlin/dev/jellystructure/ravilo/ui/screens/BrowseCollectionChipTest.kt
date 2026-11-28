package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.BrowseCard
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.TvApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-4077 (DEB-4032, FR-8) — the *Collection* chip lists Ravilo collections and Jellyfin collections together. Stand-in names only. */
class BrowseCollectionChipTest {
    private fun store() = SeededBrowseStore(TvApiClient(HttpClient(OkHttp), "http://127.0.0.1:1", { "token" }, platform = "tv"),
        seedQuery = null, seedMediaKind = "MOVIE", continueWatching = false)

    private fun card(i: Int, channels: List<String> = emptyList(), boxSets: List<String> = emptyList()) = BrowseCard(
        card = MediaCard(id = "m$i", kind = MediaKind.MOVIE, title = "Stand-in $i", year = 2000 + i, genre = null,
            rating = null, posterUrl = null, backdropUrl = null),
        channels = channels, boxSets = boxSets,
    )

    private val cards = listOf(
        card(1, channels = listOf("c-a"), boxSets = listOf("b-1")),
        card(2, channels = listOf("c-a")),
        card(3, boxSets = listOf("b-1")),
        card(4, boxSets = listOf("b-2")),
        card(5),
    )

    @Test fun `both kinds are counted in one list ordered by count, the Jellyfin ids kept apart by a prefix`() {
        val values = store().valuesFor(cards, BrowseFacetKey.CHANNEL)
        assertEquals(listOf("box:b-1" to 2, "c-a" to 2, "box:b-2" to 1), values.map { it.value to it.count })
    }

    @Test fun `a Ravilo and a Jellyfin collection selected together show the union`() {
        val s = store()
        s.channel = setOf("c-a", "box:b-2")
        assertEquals(listOf("m1", "m2", "m4"), cards.filter { s.matches(it, null) }.map { it.card.id })
    }

    @Test fun `a collection selection is ANDed with another facet`() {
        val s = store()
        s.channel = setOf("box:b-1")
        s.year = setOf("2000s")
        assertEquals(listOf("m1", "m3"), cards.filter { s.matches(it, null) }.map { it.card.id })
        s.year = setOf("1990s")
        assertTrue(cards.none { s.matches(it, null) })
    }

    @Test fun `a response without box_sets lists Ravilo collections only`() {
        val values = store().valuesFor(cards.map { it.copy(boxSets = emptyList()) }, BrowseFacetKey.CHANNEL)
        assertEquals(listOf("c-a"), values.map { it.value })
        assertFalse(values.any { isBoxSetValue(it.value) })
    }

    @Test fun `neither kind present leaves nothing to list`() {
        assertTrue(store().valuesFor(listOf(card(1), card(2)), BrowseFacetKey.CHANNEL).isEmpty())
    }
}
