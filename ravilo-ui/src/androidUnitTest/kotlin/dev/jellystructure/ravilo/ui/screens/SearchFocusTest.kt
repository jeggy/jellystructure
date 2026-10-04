package dev.jellystructure.ravilo.ui.screens

import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.pressKey
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.SearchResults
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R350 (FR-R350-4, and the 2026-10-02 re-test's FR-R350-13) — Search on a TV, key by key: the field takes focus without
 * raising the keyboard, OK raises it, Back from the results leaves in one press, and Up from the first row returns to
 * the field without the keyboard. A television configuration, so `isTvPlatform` is true.
 *
 * The keyboard is read where Android shows it — the `InputMethodManager` (Robolectric's shadow records the show and the
 * hide) — and the view's input session, not a stand-in `SoftwareKeyboardController`: on the Sony the keyboard on
 * arrival came from the text field starting an input session when it took focus, which never goes through that
 * controller. The first version of this test recorded the controller and passed while the TV showed the keyboard.
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

    private var backs = 0
    private lateinit var view: View

    /** The system keyboard is up (what `showSoftInput` / `hideSoftInputFromWindow` last said). */
    private fun keyboardUp(): Boolean = rule.runOnIdle {
        shadowOf(view.context.getSystemService(InputMethodManager::class.java)).isSoftInputVisible
    }

    /** A text field holds an input session (the keyboard could type into it). */
    private fun editing(): Boolean = rule.runOnIdle { view.onCheckIsTextEditor() }

    private fun init() =
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())

    private fun store() = SearchStore { q -> SearchResults(q, cards) }

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        view = LocalView.current
        // What RaviloApp's root does with a Back nobody consumed: leave the page.
        Box(Modifier.onKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Back) { backs++; true } else false }) {
            content()
        }
    }

    private fun waitForResults() {
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasText("Stand-in 1"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
    }

    private fun render() {
        init()
        val store = store()
        rule.setContent { Host { SearchScreen(store = store, onBack = {}, onItemSelect = {}) } }
        waitForResults()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private val field get() = rule.onNodeWithTag(SearchTags.FIELD)
    private fun tileFocused(title: String) =
        rule.onNode(isFocused() and hasAnyDescendant(hasText(title)), useUnmergedTree = true).assertExists()

    @Test fun `arriving focuses the field and leaves the keyboard down, and OK raises it`() {
        render()
        field.assertIsFocused()
        assertFalse(keyboardUp(), "no keyboard on arrival")
        assertFalse(editing(), "no input session on arrival — a session is what raises the keyboard")
        press(Key.DirectionCenter)
        field.assertIsFocused()
        assertTrue(editing(), "OK makes the field editable")
        assertTrue(keyboardUp(), "OK on the field raises the keyboard")
    }

    @Test fun `opening Search with OK on the top bar's search icon does not raise the keyboard`() {
        // The Sony's path: OK on the app bar's magnifier pushes Search; the press's release then lands on the new page.
        init()
        val store = store()
        rule.setContent {
            Host {
                var open by remember { mutableStateOf(false) }
                val icon = remember { FocusRequester() }
                if (!open) {
                    Text("Search icon", Modifier.dpadFocusable(focusRequester = icon, onSelect = { open = true }))
                    LaunchedEffect(Unit) { icon.requestFocus() }
                } else {
                    SearchScreen(store = store, onBack = {}, onItemSelect = {})
                }
            }
        }
        rule.waitForIdle()
        press(Key.DirectionCenter)
        waitForResults()
        field.assertIsFocused()
        assertFalse(keyboardUp(), "no keyboard on arrival from the top bar")
        assertFalse(editing())
        press(Key.DirectionCenter)
        assertTrue(keyboardUp(), "OK on the field raises it")
    }

    @Test fun `Back from the results leaves Search in one press`() {
        render()
        press(Key.DirectionDown)
        tileFocused("Stand-in 1")
        press(Key.DirectionRight)
        tileFocused("Stand-in 2")
        press(Key.Back)
        assertEquals(1, backs, "one Back leaves")
        assertFalse(keyboardUp(), "and never raises the keyboard on the way")
    }

    @Test fun `Up from the first row returns to the field, without the keyboard, and Back from the field leaves`() {
        render()
        press(Key.DirectionDown)
        tileFocused("Stand-in 1")
        press(Key.DirectionUp)
        field.assertIsFocused()
        assertFalse(keyboardUp())
        assertFalse(editing())
        press(Key.Back)
        assertEquals(1, backs)
    }

    @Test fun `after typing, Down to the results and Up again comes back without the keyboard`() {
        render()
        press(Key.DirectionCenter)
        assertTrue(keyboardUp())
        press(Key.DirectionDown)            // the field's own Down: into the results, keyboard hidden
        tileFocused("Stand-in 1")
        assertFalse(keyboardUp())
        press(Key.DirectionUp)
        field.assertIsFocused()
        assertFalse(keyboardUp(), "D-pad focus coming back never opens the keyboard")
        assertFalse(editing())
        press(Key.DirectionCenter)
        assertTrue(keyboardUp(), "OK opens it again")
    }

    // ── R365 (FR-R365-1) — Clear is reachable on a TV ──

    private val clear get() = rule.onNodeWithTag(SearchTags.CLEAR, useUnmergedTree = true)

    private fun typedThenBackOnTheField() {
        render()
        press(Key.DirectionCenter)
        field.performTextInput("Stand")
        rule.waitForIdle()
        waitForResults()
        press(Key.DirectionDown)
        press(Key.DirectionUp)
        field.assertIsFocused()
        assertFalse(keyboardUp())
    }

    @Test fun `Right from the field reaches Clear, and OK clears and returns to the field without the keyboard`() {
        typedThenBackOnTheField()
        press(Key.DirectionRight)
        clear.assertIsFocused()
        press(Key.DirectionCenter)
        field.assertIsFocused()
        field.assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        assertFalse(keyboardUp(), "clearing never raises the keyboard")
        clear.assertDoesNotExist()
    }

    @Test fun `Left or Down from Clear go back to the field, and Up stays`() {
        typedThenBackOnTheField()
        press(Key.DirectionRight)
        clear.assertIsFocused()
        press(Key.DirectionUp)
        clear.assertIsFocused()
        press(Key.DirectionLeft)
        field.assertIsFocused()
        press(Key.DirectionRight)
        clear.assertIsFocused()
        press(Key.DirectionDown)
        field.assertIsFocused()
    }

    @Test fun `with no query there is no Clear and Right stays on the field`() {
        init()
        val store = SearchStore { q -> SearchResults(q, if (q.isBlank()) emptyList() else cards) }
        rule.setContent { Host { SearchScreen(store = store, onBack = {}, onItemSelect = {}) } }
        rule.waitForIdle()
        field.assertIsFocused()
        clear.assertDoesNotExist()
        press(Key.DirectionRight)
        field.assertIsFocused()
    }
}
