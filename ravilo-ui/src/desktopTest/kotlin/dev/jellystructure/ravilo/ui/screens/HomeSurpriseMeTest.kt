package dev.jellystructure.ravilo.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import dev.jellystructure.ravilo.ui.components.LocalSurpriseMe
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.shared.tv.HomeFeed
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.Row
import dev.jellystructure.shared.tv.TvApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-4273 (DEB-3984 FR-12, FR-13, tv-screen-scale-and-slim-header.md, Acceptance 10, 11, 14, 15) — Home ends with one
 * Surprise me button: drawn after the last row only while the frame provides the action, reached by Down from the last row,
 * left by Up to the tile the viewer came down from, remembered for the Back-return, and drawn on a Home with no rows.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class HomeSurpriseMeTest {
    private fun card(id: String) = MediaCard(id = id, title = "Tile $id", year = null, genre = null, rating = null, posterUrl = null, backdropUrl = null)
    private fun row(id: String, vararg cards: String) = Row(id = id, title = "Row $id", items = cards.map(::card))

    // The seeded store never needs the server (its quiet first load just fails); the client points at a closed port.
    private fun store(rows: List<Row>) = HomeStore(
        TvApiClient(HttpClient(OkHttp), "http://127.0.0.1:1", { "token" }, platform = "tv"),
        seedFeed = HomeFeed(heroes = emptyList(), channels = emptyList(), rows = rows, autoAdvanceSeconds = 0),
    )

    private fun androidx.compose.ui.test.ComposeUiTest.press(key: Key) {
        onRoot().performKeyInput { keyDown(key); keyUp(key) }
        waitForIdle()
    }

    // The text sits in the merged node under the focusable one; the innermost node that can take focus and holds the text.
    private fun androidx.compose.ui.test.ComposeUiTest.focusableWith(text: String) =
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus).and(hasAnyDescendant(hasText(text)).or(hasText(text)))).onLast()

    private fun androidx.compose.ui.test.ComposeUiTest.focus(text: String) {
        focusableWith(text).performSemanticsAction(SemanticsActions.RequestFocus)
        waitForIdle()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.home(store: HomeStore, action: (() -> Unit)?): String {
        var label = ""
        setContent {
            label = str("nav.surprise")
            CompositionLocalProvider(LocalSurpriseMe provides action) { HomeScreen(store = store, apiClient = TvApiClient(HttpClient(OkHttp), "http://127.0.0.1:1", { "token" }, platform = "tv")) }
        }
        waitForIdle()
        assertTrue(label.isNotEmpty())
        return label
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onFocusedWith(text: String) = onNode(isFocused().and(hasAnyDescendant(hasText(text))).or(isFocused().and(hasText(text))))

    @Test fun `the button is drawn after the last row when the gate is on`() = runComposeUiTest {
        val label = home(store(listOf(row("a", "1", "2"), row("b", "3", "4"))), action = {})
        val lastTile = onNodeWithText("Tile 4").fetchSemanticsNode().boundsInRoot
        val button = onNodeWithText(label).fetchSemanticsNode().boundsInRoot
        assertTrue(button.top >= lastTile.bottom, "the button sits below the last row ($button vs $lastTile)")
    }

    @Test fun `with the gate off there is no button`() = runComposeUiTest {
        val label = home(store(listOf(row("a", "1", "2"))), action = null)
        onAllNodesWithTextCount(label, 0)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesWithTextCount(text: String, n: Int) =
        assertEquals(n, onAllNodes(hasText(text)).fetchSemanticsNodes().size)

    @Test fun `Down from a tile of the last row lands on the button and Up returns to the same tile`() = runComposeUiTest {
        val label = home(store(listOf(row("a", "1", "2"), row("b", "3", "4", "5"))), action = {})
        focus("Tile 5")
        press(Key.DirectionDown)
        onFocusedWith(label).assertIsFocused()
        press(Key.DirectionUp)
        onFocusedWith("Tile 5").assertIsFocused()
        // Left, Right and Down do nothing on the button.
        focus("Tile 3")
        press(Key.DirectionDown)
        onFocusedWith(label).assertIsFocused()
        for (k in listOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionDown)) { press(k); onFocusedWith(label).assertIsFocused() }
        press(Key.DirectionUp)
        onFocusedWith("Tile 3").assertIsFocused()
    }

    @Test fun `pressing it runs the action and remembers the button for the Back-return`() = runComposeUiTest {
        var pressed = 0
        val st = store(listOf(row("a", "1", "2")))
        val label = home(st, action = { pressed++ })
        focus(label)
        press(Key.DirectionCenter)
        assertEquals(1, pressed)
        assertEquals(SURPRISE_ROW_KEY, st.focusRowKey)
        assertEquals(SURPRISE_ROW_KEY, st.focusItemKey)
    }

    @Test fun `a Home that returns from the page Surprise me opened has the button focused`() = runComposeUiTest {
        val st = store(listOf(row("a", "1", "2"), row("b", "3", "4")))
        st.rememberReturn(SURPRISE_ROW_KEY, SURPRISE_ROW_KEY, 2, 0)
        val label = home(st, action = {})
        waitUntil(timeoutMillis = 5_000) { onAllNodes(isFocused().and(hasAnyDescendant(hasText(label)).or(hasText(label)))).fetchSemanticsNodes().isNotEmpty() }
        onFocusedWith(label).assertIsFocused()
        assertNull(st.focusRowKey, "the return is spent")
    }

    @Test fun `a Home with no rows still draws it, and Down from the bar reaches it`() = runComposeUiTest {
        val label = home(store(emptyList()), action = {})
        focus("Home")
        press(Key.DirectionDown)
        onFocusedWith(label).assertIsFocused()
    }
}
