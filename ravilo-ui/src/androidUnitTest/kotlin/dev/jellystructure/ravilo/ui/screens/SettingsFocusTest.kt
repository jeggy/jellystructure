package dev.jellystructure.ravilo.ui.screens

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
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.RaviloThemes
import dev.jellystructure.shared.tv.RaviloWireJson
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R350 re-test (FR-R350-16) — Settings on a TV, key by key: it opens at the top with focus on its first control
 * (‹ Back), a stray OK there changes no setting, and the D-pad walks down through the sections and back up to Back.
 * Before the fix the page put focus on *Show progress on Continue Watching* once the settings loaded, scrolled to it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-television")
@OptIn(ExperimentalTestApi::class)
class SettingsFocusTest {
    @get:Rule val rule = createComposeRule()

    /** Every request path the screen sent; a write to the viewer's settings is `/api/tv/settings`. */
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var backs = 0

    private fun writes() = requests.count { it == "/api/tv/settings" }

    private fun render() {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        // A TV viewer on Noir: the TV lists the dark themes only, so the first pill (Aurora) is NOT the one in use —
        // an OK on it would change the theme.
        val config = RaviloConfig(themeFollow = false, theme = RaviloThemes.NOIR, themeDark = RaviloThemes.NOIR)
        val body = RaviloWireJson.encodeToString(RaviloConfig.serializer(), config)
        val api = fakeTvApiClient { path ->
            requests += path
            when (path) {
                "/api/tv/config" -> body
                else -> null
            }
        }
        val store = SettingsStore(api)
        rule.setContent {
            SettingsScreen(store = store, displayName = "Olivar", onSignOut = {}, onBack = { backs++ })
        }
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasText("Show progress on Continue Watching"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun focusedWith(text: String) =
        rule.onNode(isFocused() and (hasText(text, substring = true) or hasAnyDescendant(hasText(text, substring = true))), useUnmergedTree = true)
            .assertExists()

    private fun back() = focusedWith("Back")

    @Test fun `opens at the top with focus on Back, not on a Playback toggle`() {
        render()
        back()
        rule.onNode(hasText("Settings"), useUnmergedTree = true).assertIsDisplayed()   // not scrolled away from the top
        rule.onNode(isFocused() and hasAnyDescendant(hasText("Show progress on Continue Watching")), useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test fun `a stray OK on arrival changes no setting`() {
        render()
        press(Key.DirectionCenter)
        assertEquals(1, backs, "OK on Back leaves Settings")
        assertEquals(0, writes(), "and writes nothing")
    }

    @Test fun `the D-pad walks down the sections and back up to Back`() {
        render()
        back()
        press(Key.DirectionDown); focusedWith("Aurora")                                   // Appearance: the first theme
        press(Key.DirectionUp); back()
        press(Key.DirectionDown); focusedWith("Aurora")
        press(Key.DirectionDown); focusedWith("English")                                  // Language
        press(Key.DirectionDown); focusedWith("Show progress on Continue Watching")       // Playback
        press(Key.DirectionDown); focusedWith("Autoplay next episode")
        press(Key.DirectionDown); focusedWith("Change password")                          // Account
        press(Key.DirectionUp); focusedWith("Autoplay next episode")
        press(Key.DirectionUp); focusedWith("Show progress on Continue Watching")
        press(Key.DirectionUp); focusedWith("English")
        press(Key.DirectionUp); focusedWith("Aurora")
        press(Key.DirectionUp); back()
        rule.onNode(hasText("Settings"), useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0, writes(), "walking writes nothing")
        assertTrue(requests.contains("/api/tv/config"))
    }
}
