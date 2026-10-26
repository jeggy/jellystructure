package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.shared.tv.ChannelConfig
import dev.jellystructure.shared.tv.HeroConfig
import dev.jellystructure.shared.tv.PageHeroConfig
import dev.jellystructure.shared.tv.RaviloConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/** DEB-2518 (FR-5) — `GET /tv/config` carries only the heroes the device can see, display fields included. */
class ConfigHeroesTagFilterTest {
    private fun film(id: String, tags: List<String>) = MediaItem(
        id = id, title = id, year = 2020, kind = MediaKind.MOVIE, path = "/lib/$id", tmdbId = null,
        originalLanguage = null, posterPath = null, overview = null, tracks = emptyList(), issueCount = 0,
        scannedAt = 0L, episodes = emptyList(), tags = tags, jellyfinId = "jf-$id",
    )

    private val catalogue = listOf(film("household", listOf("t")), film("other", listOf("u")))

    private fun hero(item: String) = HeroConfig(itemId = item, displayTitle = "Title of $item", displayMeta = "meta", displayBackdrop = "/b/$item")

    private val config = RaviloConfig(
        heroes = listOf(hero("jf-household"), hero("jf-other"), hero("household"), hero("other"), hero("not-in-catalogue")),
        channels = listOf(ChannelConfig(id = "c", pageHero = PageHeroConfig(enabled = true, items = listOf(hero("jf-household"), hero("jf-other"))))),
    )

    private fun device(allowed: Set<String> = emptySet(), blocked: Set<String> = emptySet()) =
        DeviceData("d", "tok", "u1", "viewer", "", isAdmin = false, allowedTags = allowed, blockedTags = blocked, kind = "phone")

    @Test fun `a device with allowed tags t loses the hero of a title without t on Home and on a channel page`() {
        val out = config.withoutHeroesHiddenFrom(device(allowed = setOf("t")), catalogue)
        assertEquals(listOf("jf-household", "household", "not-in-catalogue"), out.heroes.map { it.itemId })
        assertEquals(listOf("jf-household"), out.channels.single().pageHero!!.items.map { it.itemId })
        assertEquals(emptyList(), out.heroes.filter { it.displayTitle == "Title of jf-other" }, "its display fields leave with it")
    }

    @Test fun `a blocked tag removes the hero of a title that carries it`() {
        val out = config.withoutHeroesHiddenFrom(device(blocked = setOf("u")), catalogue)
        assertEquals(listOf("jf-household", "household", "not-in-catalogue"), out.heroes.map { it.itemId })
    }

    @Test fun `a device with no tag lists gets the config unchanged`() {
        assertEquals(config, config.withoutHeroesHiddenFrom(device(), catalogue))
    }
}
