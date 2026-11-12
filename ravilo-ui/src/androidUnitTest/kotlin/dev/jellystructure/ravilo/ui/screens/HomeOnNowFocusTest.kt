package dev.jellystructure.ravilo.ui.screens

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dev.jellystructure.shared.tv.Hero
import dev.jellystructure.shared.tv.HomeFeed
import dev.jellystructure.shared.tv.LiveTvChannel
import dev.jellystructure.shared.tv.LiveTvHomePlacement
import dev.jellystructure.shared.tv.LiveTvProgramInfo
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.RaviloWireJson
import dev.jellystructure.shared.tv.Row
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DEB-4271 — D-pad Down through Home's *On Now* row. The row opens with the "Open TV Guide" tile, which is shorter
 * than the channel tiles beside it (they carry a title and a time line under the square), and Compose's 2-D focus
 * search read the taller second tile as "below" the guide tile, so Down moved right; the Down after it chose the
 * tile nearest the source's centre in the next row — the second — and every row after kept that column.
 * Down from a row's first tile must land on the next row's first tile, and Down from the guide tile must leave
 * the row. Stand-in titles and channels only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
@OptIn(ExperimentalTestApi::class)
class HomeOnNowFocusTest {
    @get:Rule val rule = createComposeRule()

    private fun card(r: Int, i: Int) = MediaCard(id = "r${r}i$i", kind = MediaKind.MOVIE, title = "Row$r Tile$i", year = 2001,
        genre = null, rating = null, posterUrl = null, backdropUrl = null)

    private fun row(r: Int) = Row(id = "row$r", title = "Shelf $r", items = (1..8).map { card(r, it) })

    private val hero = Hero(item = MediaCard(id = "hero", kind = MediaKind.MOVIE, title = "Stand-in Hero", year = 2020,
        genre = null, rating = null, posterUrl = null, backdropUrl = null), taglineKicker = null, backdropUrl = "", logoUrl = null, badge = null)

    private fun channel(i: Int) = LiveTvChannel(channelId = "ch$i", name = "Stand-in Channel $i", number = i, order = i, shown = true,
        logoUrl = null, category = "General", hasGuide = true,
        currentProgram = LiveTvProgramInfo(name = "Stand-in Programme $i", startMs = 0, endMs = Long.MAX_VALUE / 2))

    private fun render(onNowAfter: Int, rows: Int) {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val feed = HomeFeed(heroes = listOf(hero), channels = emptyList(), rows = (1..rows).map(::row), autoAdvanceSeconds = 0,
            liveTvHome = LiveTvHomePlacement(showOnNowRow = true, onNowRowPosition = onNowAfter))
        val api = fakeTvApiClient { path ->
            when (path) {
                "/api/tv/home" -> RaviloWireJson.encodeToString(HomeFeed.serializer(), feed)
                "/api/tv/livetv/channels" -> RaviloWireJson.encodeToString(ListSerializer(LiveTvChannel.serializer()), (1..6).map(::channel))
                else -> null
            }
        }
        val store = HomeStore(api)
        rule.setContent { HomeScreen(store = store, apiClient = api, displayName = "Olivar") }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Row1 Tile1"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(5_000) { rule.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun focusedOn(text: String) =
        rule.onNode(isFocused() and (hasText(text) or hasAnyDescendant(hasText(text))), useUnmergedTree = true)

    @Test fun `Down through On Now leaves the guide tile, lands on the first tile below, and keeps the first column`() {
        render(onNowAfter = 2, rows = 6)
        press(Key.DirectionDown)                       // hero → Shelf 1
        press(Key.DirectionDown)                       // → Shelf 2
        focusedOn("Row2 Tile1").assertExists()
        press(Key.DirectionDown)                       // → On Now: the guide tile, not a channel
        rule.onAllNodes(isFocused() and hasAnyDescendant(hasText("Stand-in Channel", substring = true)), useUnmergedTree = true)
            .assertCountEquals(0)
        press(Key.DirectionDown)                       // the guide tile → the row below, not one tile right
        focusedOn("Row3 Tile1").assertExists()
        press(Key.DirectionDown)
        focusedOn("Row4 Tile1").assertExists()
        press(Key.DirectionDown)
        focusedOn("Row5 Tile1").assertExists()
    }
}
