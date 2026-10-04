package dev.jellystructure.ravilo.ui.screens

import kotlin.test.assertEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dev.jellystructure.shared.tv.BrowseFacets
import dev.jellystructure.shared.tv.FacetItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R350 (FR-R350-5) — Discover's vertical path, key by key: the top bar → the selected tab → the wall, and back up
 * the same way. Down from the bar used to skip the tabs and land in the wall's first tile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
@OptIn(ExperimentalTestApi::class)
class DiscoverFocusTest {
    @get:Rule val rule = createComposeRule()

    private val facets = BrowseFacets(
        networks = (1..14).map { FacetItem(name = "Network $it", count = 20 - it) },
        studios = (1..8).map { FacetItem(name = "Studio $it", count = 10 - it) },
        genres = (1..6).map { FacetItem(name = "Genre $it", count = 7 - it) },
    )

    private fun render() {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val taxonomy = TaxonomyStore { facets }
        rule.setContent {
            DiscoverScreen(
                segment = DiscoverSegment.NETWORKS,
                segments = listOf(DiscoverSegment.NETWORKS, DiscoverSegment.STUDIOS, DiscoverSegment.GENRES),
                onSegment = {}, focusSegmentOnEntry = false, onFocusSegmentConsumed = {},
                displayName = "Olivar", onNavSelect = {}, onProfile = {}, onSearch = {},
                upcomingStore = null, onUpcomingItemSelect = {}, requestStore = null, onEntrySelect = { _, _ -> },
                onSearchSeerr = {}, taxonomyStore = taxonomy, onTileSelect = { _, _, _ -> },
            )
        }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Network 1"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun focusedWithText(text: String) =
        rule.onNode(isFocused() and (hasText(text) or hasAnyDescendant(hasText(text))), useUnmergedTree = true).assertExists()

    @Test fun `the bar, the selected tab, the wall — and back up`() {
        render()
        focusedWithText("Discover")                  // arrival: the app bar, on Discover
        press(Key.DirectionDown)
        focusedWithText("Networks")                  // the selected tab, not the wall
        press(Key.DirectionDown)
        focusedWithText("Network 1")                 // the wall's first tile
        press(Key.DirectionRight)
        focusedWithText("Network 2")
        press(Key.DirectionUp)
        focusedWithText("Networks")                  // the selected tab, whichever chip sits above the tile
        press(Key.DirectionUp)
        focusedWithText("Discover")
    }

    @Test fun `R361 — Back to a wall value that is gone lands on the value now in its place`() {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        var served = facets
        val taxonomy = TaxonomyStore { served }
        var shown by mutableStateOf(true)
        rule.setContent {
            if (shown) DiscoverScreen(
                segment = DiscoverSegment.NETWORKS,
                segments = listOf(DiscoverSegment.NETWORKS, DiscoverSegment.STUDIOS, DiscoverSegment.GENRES),
                onSegment = {}, focusSegmentOnEntry = false, onFocusSegmentConsumed = {},
                displayName = "Olivar", onNavSelect = {}, onProfile = {}, onSearch = {},
                upcomingStore = null, onUpcomingItemSelect = {}, requestStore = null, onEntrySelect = { _, _ -> },
                onSearchSeerr = {}, taxonomyStore = taxonomy, onTileSelect = { _, _, _ -> shown = false },
            )
        }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Network 1"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        press(Key.DirectionDown); press(Key.DirectionDown)
        press(Key.DirectionRight); press(Key.DirectionRight)
        focusedWithText("Network 3")
        press(Key.Enter)
        served = facets.copy(networks = facets.networks.filter { it.name != "Network 3" })
        taxonomy.refresh(silent = true)
        rule.waitUntil(5_000) { (taxonomy.state.value as? TaxonomyState.Loaded)?.facets?.networks?.size == 13 }
        shown = true
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        focusedWithText("Network 4")
    }

    // ── R365 ──

    private fun renderComingSoon(avatarReturn: (() -> Unit)? = null) {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val feed = dev.jellystructure.shared.tv.UpcomingFeed(enabled = true, items = (1..6).map { i ->
            dev.jellystructure.shared.tv.UpcomingItem(id = "u$i", title = "Stand-in Arrival $i", date = "2026-10-1$i")
        })
        val api = fakeTvApiClient { path ->
            if (path == "/api/tv/upcoming") dev.jellystructure.shared.tv.RaviloWireJson.encodeToString(dev.jellystructure.shared.tv.UpcomingFeed.serializer(), feed) else null
        }
        val upcoming = UpcomingStore(api)
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(dev.jellystructure.ravilo.ui.components.LocalAvatarReturn provides avatarReturn) {
                DiscoverScreen(
                    segment = DiscoverSegment.COMING_SOON,
                    segments = listOf(DiscoverSegment.COMING_SOON, DiscoverSegment.NETWORKS),
                    onSegment = {}, focusSegmentOnEntry = false, onFocusSegmentConsumed = {},
                    displayName = "Olivar", onNavSelect = {}, onProfile = {}, onSearch = {},
                    upcomingStore = upcoming, onUpcomingItemSelect = {}, requestStore = null, onEntrySelect = { _, _ -> },
                    onSearchSeerr = {}, taxonomyStore = TaxonomyStore { facets }, onTileSelect = { _, _, _ -> },
                )
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Stand-in Arrival 1"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    @Test fun `R365 FR-4 — Down from the tab strip lands on the selected chip, All and then Series`() {
        renderComingSoon()
        focusedWithText("Discover")
        press(Key.DirectionDown)
        focusedWithText("Coming Soon")
        press(Key.DirectionDown)
        focusedWithText("All")                       // not Movies, the chip nearest the tab
        press(Key.DirectionRight)
        press(Key.Enter)                             // select Series
        focusedWithText("Series")
        press(Key.DirectionUp)
        focusedWithText("Coming Soon")
        press(Key.DirectionDown)
        focusedWithText("Series")
    }

    @Test fun `R365 FR-5 — arriving with the return-to-avatar flag focuses the avatar and consumes the flag once`() {
        var consumed = 0
        renderComingSoon(avatarReturn = { consumed++ })
        rule.mainClock.advanceTimeBy(200)
        rule.waitForIdle()
        rule.onNodeWithTag(dev.jellystructure.ravilo.ui.components.APP_BAR_AVATAR_TAG, useUnmergedTree = true).assertIsFocused()
        assertEquals(1, consumed)
    }

    @Test fun `R365 FR-5 — without the flag the active tab is focused`() {
        renderComingSoon()
        focusedWithText("Discover")
    }
}
