package dev.jellystructure.ravilo.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dev.jellystructure.shared.tv.Hero
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
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * R361 — Home, key by key: a Back-return (or a refresh) aimed at a title that is gone lands on its neighbour in the
 * same row, or on the row now in its place, never on the app bar, and never scrolls Home back to the hero.
 * "Leave and return" is the screen leaving composition and coming back over the same kept [HomeStore].
 * Stand-in titles only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
@OptIn(ExperimentalTestApi::class)
class HomeFocusTest {
    @get:Rule val rule = createComposeRule()

    private fun card(r: Int, i: Int) = MediaCard(id = "r${r}i$i", kind = MediaKind.MOVIE, title = "Row$r Tile$i", year = 2001,
        genre = null, rating = null, posterUrl = null, backdropUrl = null)

    private fun row(r: Int, tiles: List<Int>) = Row(id = "row$r", title = "Shelf $r", items = tiles.map { card(r, it) })

    private val hero = Hero(item = MediaCard(id = "hero", kind = MediaKind.MOVIE, title = "Stand-in Hero", year = 2020,
        genre = null, rating = null, posterUrl = null, backdropUrl = null), taglineKicker = null, backdropUrl = "", logoUrl = null, badge = null)

    private fun feed(rows: List<Row>) = HomeFeed(heroes = listOf(hero), channels = emptyList(), rows = rows, autoAdvanceSeconds = 0)

    private val six = (1..6).map { r -> row(r, (1..8).toList()) }

    @Volatile private var served: HomeFeed = feed(six)
    private lateinit var store: HomeStore
    private var shown by mutableStateOf(true)
    private var opened: MediaCard? = null

    private fun render(initial: HomeFeed) {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        served = initial
        val api = fakeTvApiClient { path ->
            when (path) {
                "/api/tv/home" -> RaviloWireJson.encodeToString(HomeFeed.serializer(), served)
                else -> null
            }
        }
        store = HomeStore(api)
        rule.setContent {
            if (shown) HomeScreen(store = store, apiClient = api, displayName = "Olivar",
                onItemSelect = { opened = it; shown = false })
        }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Row1 Tile1"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun focused(text: String) =
        rule.onNode(isFocused() and (hasText(text) or hasAnyDescendant(hasText(text))), useUnmergedTree = true)

    /** The server's feed changes and Home's store re-pulls it (a `home_changed` push, R248). */
    private fun serverNow(rows: List<Row>) {
        served = feed(rows)
        store.refresh(silent = true)
        rule.waitUntil(5_000) { (store.state.value as? HomeState.Loaded)?.feed?.rows == rows }
        rule.waitForIdle()
    }

    private fun back() { shown = true; rule.waitForIdle(); rule.mainClock.advanceTimeBy(1_200); rule.waitForIdle() }

    private fun walkTo(rowNumber: Int, column: Int) {
        press(Key.DirectionDown)              // the hero → row 1
        repeat(rowNumber - 1) { press(Key.DirectionDown) }
        repeat(column - 1) { press(Key.DirectionRight) }
        focused("Row$rowNumber Tile$column").assertExists()
    }

    @Test fun `acceptance 1 — a gone tile mid-page lands on the tile now in its place, the page stays, Down goes on`() {
        render(feed(six))
        walkTo(5, 4)
        press(Key.Enter)
        assertEquals("r5i4", opened?.id)
        serverNow(six.map { if (it.id == "row5") row(5, listOf(1, 2, 3, 5, 6, 7, 8)) else it })
        back()
        focused("Row5 Tile5").assertExists().assertIsDisplayed()   // the tile now 4th
        // Home did not scroll back to the hero (it is either disposed or off screen).
        val heroNodes = rule.onAllNodes(hasText("Stand-in Hero"), useUnmergedTree = true)
        if (heroNodes.fetchSemanticsNodes().isNotEmpty()) heroNodes.onFirst().assertIsNotDisplayed()
        press(Key.DirectionDown)
        rule.onNode(isFocused() and hasAnyDescendant(hasText("Row6", substring = true)), useUnmergedTree = true).assertExists()
    }

    @Test fun `acceptance 2 — the row's last tile gone lands on the new last`() {
        render(feed(six))
        walkTo(5, 8)
        press(Key.Enter)
        serverNow(six.map { if (it.id == "row5") row(5, (1..7).toList()) else it })
        back()
        focused("Row5 Tile7").assertExists()
    }

    @Test fun `acceptance 3 — a one-title row that goes lands on the row now in its place, and the keys do not leak`() {
        val withSingle = listOf(row(1, (1..8).toList()), row(2, (1..8).toList()), row(3, listOf(1)), row(4, (1..8).toList()), row(5, (1..8).toList()))
        render(feed(withSingle))
        walkTo(3, 1)
        press(Key.Enter)
        serverNow(withSingle.filter { it.id != "row3" })
        back()
        focused("Row4 Tile1").assertExists()
        assertNull(store.focusRowKey, "the return spent the keys")
        // an ordinary return afterwards still restores
        press(Key.DirectionRight)
        press(Key.Enter)
        assertEquals("r4i2", opened?.id)
        back()
        focused("Row4 Tile2").assertExists()
    }

    @Test fun `acceptance 5 — a refresh that drops the focused tile far right lands on its neighbour, on screen`() {
        val long = listOf(row(1, (1..20).toList()), row(2, (1..8).toList()))
        render(feed(long))
        walkTo(1, 13)
        serverNow(listOf(row(1, (1..20).filter { it != 13 }), row(2, (1..8).toList())))
        focused("Row1 Tile14").assertExists().assertIsDisplayed()
    }

    @Test fun `review item 2 — return on the old feed, then the refresh drops the title`() {
        render(feed(six))
        walkTo(2, 3)
        press(Key.Enter)
        back()
        focused("Row2 Tile3").assertExists()
        serverNow(six.map { if (it.id == "row2") row(2, listOf(1, 2, 4, 5, 6, 7, 8)) else it })
        focused("Row2 Tile4").assertExists().assertIsDisplayed()
    }

    // ── R365 (FR-R365-10) — Back to Home's hero shows the slide that was opened ──

    @Test fun `R365 FR-10 — the 5th slide opened, Back shows the 5th slide with the hero focused`() {
        val heroes = (1..5).map { i -> hero.copy(item = hero.item.copy(id = "hero$i", title = "Stand-in Slide $i")) }
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        served = HomeFeed(heroes = heroes, channels = emptyList(), rows = six.take(2), autoAdvanceSeconds = 0)
        val api = fakeTvApiClient { path ->
            if (path == "/api/tv/home") RaviloWireJson.encodeToString(HomeFeed.serializer(), served) else null
        }
        store = HomeStore(api)
        rule.setContent {
            if (shown) HomeScreen(store = store, apiClient = api, displayName = "Olivar", onItemSelect = { opened = it; shown = false })
        }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Stand-in Slide 1"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        repeat(4) { press(Key.DirectionRight) }
        rule.onNode(isFocused() and hasAnyDescendant(hasText("Stand-in Slide 5")), useUnmergedTree = true).assertExists()
        press(Key.Enter)
        assertEquals("hero5", opened?.id)
        back()
        rule.onNode(isFocused() and hasAnyDescendant(hasText("Stand-in Slide 5")), useUnmergedTree = true).assertExists()
    }
}
