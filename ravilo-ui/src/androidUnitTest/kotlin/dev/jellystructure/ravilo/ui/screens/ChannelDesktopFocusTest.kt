package dev.jellystructure.ravilo.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dev.jellystructure.ravilo.ui.theme.LayoutFamily
import dev.jellystructure.ravilo.ui.theme.LocalLayoutFamily
import dev.jellystructure.shared.tv.Channel
import dev.jellystructure.shared.tv.HomeFeed
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.RaviloWireJson
import dev.jellystructure.shared.tv.Row
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DEB-1745 (FR-R328-5, R337) — a collection opened on a computer takes focus on arrival. The desktop AppBar is the
 * toolbar and never attaches the bar's requester, so a collection without a hero used to open with nothing focused:
 * no ring, and the arrows and Esc reached nobody. Stand-in titles only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
@OptIn(ExperimentalTestApi::class)
class ChannelDesktopFocusTest {
    @get:Rule val rule = createComposeRule()

    private val channel = Channel(id = "c1", name = "Stand-in Collection", logoUrl = null, brandColor = null)

    private fun card(i: Int) = MediaCard(id = "t$i", kind = MediaKind.MOVIE, title = "Tile$i", year = 2001,
        genre = null, rating = null, posterUrl = null, backdropUrl = null)

    private fun render() {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val feed = HomeFeed(heroes = emptyList(), channels = emptyList(), rows = listOf(Row(id = "r1", title = "Shelf", items = (1..4).map(::card))), autoAdvanceSeconds = 0)
        val api = fakeTvApiClient { path ->
            if (path == "/api/tv/channel/c1") RaviloWireJson.encodeToString(HomeFeed.serializer(), feed) else null
        }
        val store = ChannelStore(api)
        rule.setContent {
            CompositionLocalProvider(LocalLayoutFamily provides LayoutFamily.DESKTOP) {
                ChannelScreen(channel = channel, store = store, displayName = "Olivar", onBack = {}, onNavSelect = {},
                    onProfile = {}, onSearch = {}, onItemSelect = {})
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Tile1"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(5_000) { rule.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun focused(text: String) =
        rule.onNode(isFocused() and (hasText(text) or hasAnyDescendant(hasText(text))), useUnmergedTree = true)

    @Test fun `a hero-less collection on a computer opens with focus on its first tile and the arrows walk on`() {
        render()
        focused("Tile1").assertExists()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        focused("Tile2").assertExists()
    }
}
