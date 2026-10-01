package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.SearchResults
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * R350 (FR-R350-4) — Search on a TV, key by key: the field takes focus without raising the keyboard, OK raises it,
 * Back from the results leaves in one press, and Up from the first row returns to the field without the keyboard.
 * A television configuration, so `isTvPlatform` is true (the app's own runtime check reads the UI mode).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-television")
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class SearchFocusTest {
    @get:Rule val rule = createComposeRule()

    private val cards = (1..12).map { i ->
        MediaCard(id = "t$i", kind = MediaKind.MOVIE, title = "Stand-in $i", year = 2020, genre = null, rating = null,
            posterUrl = null, backdropUrl = null)
    }

    /** Records the screen's keyboard requests (the text field's own requests go through the same local). */
    private class Keyboard : SoftwareKeyboardController {
        var shows = 0; var hides = 0
        override fun show() { shows++ }
        override fun hide() { hides++ }
    }

    private var backs = 0
    private val keyboard = Keyboard()

    private fun render() {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val store = SearchStore { q -> SearchResults(q, cards) }
        rule.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                // What RaviloApp's root does with a Back nobody consumed: leave the page.
                Box(Modifier.onKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Back) { backs++; true } else false }) {
                    SearchScreen(store = store, onBack = {}, onItemSelect = {})
                }
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("tile-t1"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodes(androidx.compose.ui.test.hasText("Stand-in 1"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private val field get() = rule.onNode(hasSetTextAction())
    private fun tileFocused(title: String) =
        rule.onNode(isFocused() and androidx.compose.ui.test.hasAnyDescendant(androidx.compose.ui.test.hasText(title)), useUnmergedTree = true).assertExists()

    @Test fun `arriving focuses the field and leaves the keyboard down, and OK raises it`() {
        render()
        field.assertIsFocused()
        assertEquals(0, keyboard.shows, "no keyboard on arrival")
        press(Key.DirectionCenter)
        assertEquals(1, keyboard.shows, "OK on the field raises the keyboard")
        field.assertIsFocused()
    }

    @Test fun `Back from the results leaves Search in one press`() {
        render()
        press(Key.DirectionDown)
        tileFocused("Stand-in 1")
        press(Key.DirectionRight)
        tileFocused("Stand-in 2")
        press(Key.Back)
        assertEquals(1, backs, "one Back leaves")
        assertEquals(0, keyboard.shows, "and never raises the keyboard on the way")
    }

    @Test fun `Up from the first row returns to the field, without the keyboard, and Back from the field leaves`() {
        render()
        press(Key.DirectionDown)
        tileFocused("Stand-in 1")
        press(Key.DirectionUp)
        field.assertIsFocused()
        assertEquals(0, keyboard.shows)
        press(Key.Back)
        assertEquals(1, backs)
    }
}
