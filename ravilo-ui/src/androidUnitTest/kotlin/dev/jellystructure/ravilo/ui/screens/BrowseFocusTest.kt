package dev.jellystructure.ravilo.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dev.jellystructure.shared.tv.BrowseCard
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.RaviloWireJson
import dev.jellystructure.shared.tv.SeededBrowseResponse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * R350 (FR-R350-9) — the Movies / Series browse page, key by key: Up from ANY tile of the first row lands on the
 * facet bar, never past it on the app bar's search or avatar above the right-hand tiles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
@OptIn(ExperimentalTestApi::class)
class BrowseFocusTest {
    @get:Rule val rule = createComposeRule()

    // R362 — sixty cards (ten rows) with channels, a quality and people, so the facet bar has every chip and
    // scrolls at 960 dp, and the grid scrolls.
    private fun cardsOf(n: Int) = (1..n).map { i ->
        BrowseCard(
            card = MediaCard(id = "m$i", kind = MediaKind.MOVIE, title = "Stand-in $i", year = 2000 + i, genre = null,
                rating = null, posterUrl = null, backdropUrl = null),
            genres = listOf(if (i % 2 == 0) "Drama" else "Comedy"),
            channels = listOf("ch1"), quality = if (i % 3 == 0) "4K" else "HD", people = listOf(i % 4 + 1),
        )
    }
    private val cards = cardsOf(60)

    @Volatile private var served: List<BrowseCard> = cards
    private var shown by mutableStateOf(true)
    private var opened: MediaCard? = null
    private lateinit var store: SeededBrowseStore

    private fun render(initial: List<BrowseCard> = cards) {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        served = initial
        val api = fakeTvApiClient { path ->
            when (path) {
                "/api/tv/browse/seeded" -> RaviloWireJson.encodeToString(SeededBrowseResponse.serializer(), SeededBrowseResponse(items = served, total = served.size))
                "/api/tv/channels" -> "[]"
                else -> null
            }
        }
        store = SeededBrowseStore(api, seedQuery = null, seedMediaKind = "MOVIE", continueWatching = false)
        rule.setContent {
            if (shown) SeededBrowseScreen(
                store = store, title = "Movies", breadcrumb = null, subtitle = null, showTypeFacet = false,
                showFacetBar = true, displayName = "Olivar", activeNav = 1, onBack = {}, onItemSelect = { opened = it; shown = false },
            )
        }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Stand-in 1"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    /** Leave for a title page and come back over the same kept store; the page re-fetches on arrival (R187). */
    private fun back(expectTitles: Int) {
        shown = true
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasText(if (expectTitles == 1) "1 title" else "$expectTitles titles", substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun inFacetBar() =
        rule.onNode(isFocused() and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(SEEDED_FACET_BAR_TAG)), useUnmergedTree = true).assertExists()

    private fun focusedExactly(text: String) =
        rule.onNode(isFocused() and (hasText(text) or hasAnyDescendant(hasText(text))), useUnmergedTree = true).assertExists()

    private fun focusedWith(text: String) =
        rule.onNode(isFocused() and (hasText(text, substring = true) or hasAnyDescendant(hasText(text, substring = true))), useUnmergedTree = true).assertExists()

    @Test fun `Up from the last tile of the first row lands on the facet bar`() {
        render()
        focusedWith("Stand-in")                      // R257: the first cell takes focus on arrival
        repeat(5) { press(Key.DirectionRight) }       // the sixth (last) tile of the first row — LocalGridColumns' 6
        press(Key.DirectionUp)
        inFacetBar()                                 // a facet chip — not the app bar's search or avatar
        focusedWith("Genre")                         // its first chip (nothing focused there before)
    }

    @Test fun `Up from the first tile lands on the facet bar, and Down returns to the grid`() {
        render()
        press(Key.DirectionUp)
        inFacetBar()
        focusedWith("Genre")
        press(Key.DirectionDown)
        focusedWith("Stand-in")
    }

    // ── R361 (FR-R361-4) — Back to a grid whose opened title has left the filtered list ──

    @Test fun `a title gone from the grid lands on the tile now at its index, and the key is spent`() {
        render()
        press(Key.DirectionRight); press(Key.DirectionRight)
        focusedExactly("Stand-in 3")
        press(Key.Enter)
        assertEquals("m3", opened?.id)
        served = cards.filter { it.card.id != "m3" }
        back(expectTitles = 59)
        focusedExactly("Stand-in 4")
        assertNull(store.focusItemKey, "the restore spent the key")
        // an ordinary return afterwards still restores
        press(Key.Enter)
        assertEquals("m4", opened?.id)
        back(expectTitles = 59)
        focusedExactly("Stand-in 4")
    }

    @Test fun `an empty grid on return lands on the facet bar, never the app bar`() {
        render()
        press(Key.Enter)
        served = emptyList()
        shown = true
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("0 titles", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        inFacetBar()
    }

    // ── R362 — Movies and Series never strand the D-pad ──

    private fun focusedNode() = rule.onNode(isFocused(), useUnmergedTree = true)

    @Test fun `acceptance 1 — Sort, Up to the app bar, Down lands on a visible chip`() {
        render()
        press(Key.DirectionUp)
        repeat(10) { press(Key.DirectionRight) }
        focusedWith("Sort")
        press(Key.DirectionUp)
        rule.onNode(isFocused() and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(SEEDED_FACET_BAR_TAG)), useUnmergedTree = true).assertDoesNotExist()
        press(Key.DirectionDown)
        inFacetBar()
        focusedNode().assertIsDisplayed()
    }

    @Test fun `FR-1 — the chip last focused is where Down from the app bar lands while it is on screen`() {
        render()
        press(Key.DirectionUp)
        press(Key.DirectionRight)
        focusedWith("Maturity")
        press(Key.DirectionUp)
        press(Key.DirectionDown)
        focusedWith("Maturity")
    }

    @Test fun `FR-1 — a last chip scrolled away (a return resets the bar) sends Down to Genre at the start`() {
        render()
        press(Key.DirectionUp)
        repeat(10) { press(Key.DirectionRight) }
        focusedWith("Sort")
        press(Key.DirectionDown)                      // the grid
        press(Key.Enter)                              // a title page…
        back(expectTitles = 60)                       // …and back: the bar is shown from its start (FR-4)
        press(Key.DirectionUp); press(Key.DirectionUp) // the tile → the facet bar's entry; Up again → the app bar
        press(Key.DirectionDown)
        inFacetBar()
        focusedNode().assertIsDisplayed()
    }

    @Test fun `acceptance 3 — Up from the grid's first row with the bar scrolled lands on a visible chip`() {
        render()
        press(Key.DirectionUp)
        repeat(10) { press(Key.DirectionRight) }
        press(Key.DirectionDown)
        repeat(3) { press(Key.DirectionRight) }
        press(Key.DirectionUp)
        inFacetBar()
        focusedNode().assertIsDisplayed()
    }

    @Test fun `review item 2 — the first tile opened and restored, Up, Down reaches a grid tile`() {
        render()
        press(Key.Enter)
        assertEquals("m1", opened?.id)
        back(expectTitles = 60)
        focusedExactly("Stand-in 1")
        press(Key.DirectionUp)
        inFacetBar()
        press(Key.DirectionDown)
        focusedWith("Stand-in")
    }

    @Test fun `acceptance 4 — the applied filter chip is in view after a return`() {
        render()
        press(Key.DirectionUp)                        // Genre
        press(Key.Enter)                              // its popover, first row (Comedy)
        press(Key.DirectionDown)                      // Drama
        press(Key.Enter)                              // tick Drama
        press(Key.Back)                               // close: focus back on the chip
        focusedWith("Genre (Drama)")
        repeat(10) { press(Key.DirectionRight) }
        press(Key.DirectionDown)
        press(Key.Enter)
        back(expectTitles = 30)
        rule.onNode(hasText("Genre (Drama)", substring = true), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun `acceptance 5 — Down and Up keep column 4 all the way`() {
        render()
        repeat(3) { press(Key.DirectionRight) }
        focusedExactly("Stand-in 4")
        for (k in 1..6) { press(Key.DirectionDown); focusedExactly("Stand-in ${4 + 6 * k}") }
        for (k in 5 downTo 0) { press(Key.DirectionUp); focusedExactly("Stand-in ${4 + 6 * k}") }
    }

    @Test fun `FR-5 — Down from a row above a shorter last row lands on its last tile`() {
        render(cardsOf(16))
        repeat(3) { press(Key.DirectionRight) }
        press(Key.DirectionDown)
        focusedExactly("Stand-in 10")
        press(Key.DirectionDown)
        focusedExactly("Stand-in 16")
    }
}
