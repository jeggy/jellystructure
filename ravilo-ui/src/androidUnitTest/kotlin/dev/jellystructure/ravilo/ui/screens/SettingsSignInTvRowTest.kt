package dev.jellystructure.ravilo.ui.screens

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
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
import kotlin.test.assertEquals

/**
 * DEB-2233 (FR-6, amendment 2026-10-29) — Settings › Account with `onSignInTv`: *Sign in a TV* sits directly after the
 * password place, and the D-pad order is password → Sign in a TV → Sign out (Install Ravilo is web-only and not drawn
 * here). With the callback null nothing is drawn and the chain is the one it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-television")
@OptIn(ExperimentalTestApi::class)
class SettingsSignInTvRowTest {
    @get:Rule val rule = createComposeRule()

    private var opened = 0

    private fun render(passwordManagedElsewhere: Boolean = false, onSignInTv: (() -> Unit)?) {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val config = RaviloConfig(themeFollow = false, theme = RaviloThemes.NOIR, themeDark = RaviloThemes.NOIR, passwordManagedElsewhere = passwordManagedElsewhere)
        val body = RaviloWireJson.encodeToString(RaviloConfig.serializer(), config)
        val store = SettingsStore(fakeTvApiClient { path -> if (path == "/api/tv/config") body else null })
        rule.setContent { SettingsScreen(store = store, displayName = "Olivar", onSignOut = {}, onBack = {}, onSignInTv = onSignInTv) }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Autoplay next episode"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun press(key: Key) { rule.onRoot().performKeyInput { pressKey(key) }; rule.waitForIdle() }

    private fun focusedWith(text: String) =
        rule.onNode(isFocused() and (hasText(text, substring = true) or hasAnyDescendant(hasText(text, substring = true))), useUnmergedTree = true).assertExists()

    private fun rows() = rule.onAllNodes(hasTestTag(TvApproveTags.SETTINGS_ROW), useUnmergedTree = true).fetchSemanticsNodes().size

    /** From Back down to Autoplay, as SettingsFocusTest walks it. */
    private fun toAutoplay() {
        repeat(4) { press(Key.DirectionDown) }
        focusedWith("Autoplay next episode")
    }

    @Test fun `with the callback the row follows Change password and precedes Sign out, both ways`() {
        render(onSignInTv = { opened++ })
        assertEquals(1, rows())
        toAutoplay()
        press(Key.DirectionDown); focusedWith("Change password")
        press(Key.DirectionDown); focusedWith("Sign in a TV")
        press(Key.DirectionDown); focusedWith("Sign out")
        press(Key.DirectionUp); focusedWith("Sign in a TV")
        press(Key.DirectionUp); focusedWith("Change password")
    }

    @Test fun `OK on the row calls the callback`() {
        render(onSignInTv = { opened++ })
        toAutoplay()
        press(Key.DirectionDown); press(Key.DirectionDown); focusedWith("Sign in a TV")
        press(Key.DirectionCenter)
        assertEquals(1, opened)
    }

    @Test fun `an account managed elsewhere with no page has the row straight after Autoplay`() {
        render(passwordManagedElsewhere = true, onSignInTv = { opened++ })
        toAutoplay()
        press(Key.DirectionDown); focusedWith("Sign in a TV")
        press(Key.DirectionDown); focusedWith("Sign out")
        press(Key.DirectionUp); focusedWith("Sign in a TV")
        press(Key.DirectionUp); focusedWith("Autoplay next episode")
    }

    @Test fun `without the callback nothing is drawn and the chain is today's`() {
        render(onSignInTv = null)
        assertEquals(0, rows())
        assertEquals(0, rule.onAllNodes(hasText("Sign in a TV"), useUnmergedTree = true).fetchSemanticsNodes().size)
        toAutoplay()
        press(Key.DirectionDown); focusedWith("Change password")
        press(Key.DirectionDown); focusedWith("Sign out")
        press(Key.DirectionUp); focusedWith("Change password")
    }
}
