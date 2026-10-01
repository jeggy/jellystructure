package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jellystructure.shared.tv.TvApiClient
import io.ktor.client.HttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R349 — the sign-in, change-password and server-setup forms on a TV, in the full 540 dp and in the height the system
 * keyboard leaves (`RaviloApp` pads every non-playing screen by the keyboard, so a shorter box is exactly what the form
 * gets with the keyboard up). The focused field and its label must be displayed, and the D-pad must walk the form at
 * both heights. Before R349 the form could not scroll and the keyboard-height box squeezed the password field to nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-television")
@OptIn(ExperimentalTestApi::class)
class KeyboardFormTest {
    @get:Rule val rule = createComposeRule()

    /** The Gboard TV keyboard covers a little over half of a 540 dp screen. */
    private val keyboardUp = 240.dp
    private val keyboardDown = 540.dp

    private var height by mutableStateOf(keyboardDown)
    private lateinit var view: android.view.View

    /** R350 re-test — the system keyboard is up (Robolectric's `InputMethodManager` shadow records show and hide). */
    private fun keyboardUp(): Boolean = rule.runOnIdle {
        org.robolectric.Shadows.shadowOf(view.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)).isSoftInputVisible
    }

    /** R350 re-test — a text field holds an input session (what raises the keyboard). */
    private fun session(): Boolean = rule.runOnIdle { view.onCheckIsTextEditor() }

    private fun noKeyboard(why: String) {
        org.junit.Assert.assertFalse("keyboard up: $why", keyboardUp())
        org.junit.Assert.assertFalse("input session: $why", session())
    }

    private fun client() = TvApiClient(HttpClient(), "https://server.invalid", { null })

    private fun render(start: Dp, screen: @Composable () -> Unit) {
        height = start
        // What the app's Application.onCreate does; `isTvPlatform` reads the UI mode through it.
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        rule.setContent { view = androidx.compose.ui.platform.LocalView.current; Box(Modifier.fillMaxWidth().height(height)) { screen() } }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun focusedAndShown(tag: String, label: Boolean = false) {
        rule.onNodeWithTag(tag).assertIsFocused()
        rule.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
        if (label) rule.onNodeWithTag("$tag-label", useUnmergedTree = true).assertIsDisplayed()
    }

    // ── sign-in ────────────────────────────────────────────────────────────────────────────────────

    @Test fun `keyboard up — Next shows the password field and its label`() {
        render(keyboardUp) { LoginScreen(store = LoginStore(client()), onSignedIn = {}) }
        focusedAndShown(LoginTags.USERNAME, label = true)
        rule.onNodeWithTag(LoginTags.USERNAME).performImeAction()
        rule.waitForIdle()
        focusedAndShown(LoginTags.PASSWORD, label = true)
    }

    @Test fun `the keyboard opening under a focused password keeps it in view`() {
        render(keyboardDown) { LoginScreen(store = LoginStore(client()), onSignedIn = {}) }
        press(Key.DirectionDown)
        focusedAndShown(LoginTags.PASSWORD, label = true)
        height = keyboardUp
        rule.waitForIdle()
        focusedAndShown(LoginTags.PASSWORD, label = true)
        height = keyboardDown
        rule.waitForIdle()
        focusedAndShown(LoginTags.PASSWORD, label = true)
    }

    @Test fun `D-pad walks the sign-in form at both heights`() {
        render(keyboardDown) { LoginScreen(store = LoginStore(client()), onSignedIn = {}) }
        for (h in listOf(keyboardDown, keyboardUp)) {
            height = h
            rule.onNodeWithTag(LoginTags.USERNAME).requestFocus(); rule.waitForIdle()
            focusedAndShown(LoginTags.USERNAME)
            press(Key.DirectionDown); focusedAndShown(LoginTags.PASSWORD, label = true)
            press(Key.DirectionDown); focusedAndShown(LoginTags.SIGN_IN)
            press(Key.DirectionDown); focusedAndShown(LoginTags.CHANGE_SERVER)
            press(Key.DirectionDown); focusedAndShown(LoginTags.CHANGE_SERVER)   // the end of the form: stays put
            press(Key.DirectionUp); focusedAndShown(LoginTags.SIGN_IN)
            press(Key.DirectionUp); focusedAndShown(LoginTags.PASSWORD, label = true)
            press(Key.DirectionUp); focusedAndShown(LoginTags.USERNAME, label = true)
        }
    }

    // ── change your password ───────────────────────────────────────────────────────────────────────

    @Test fun `D-pad and Next walk the change-password form with the keyboard up`() {
        render(keyboardUp) { ChangePasswordScreen(apiClient = client(), onBack = {}, onForceSignOut = {}) }
        // The screen opens on its Back link (the header's own first focus, as before R349); Down enters the form.
        press(Key.DirectionDown)
        focusedAndShown(ChangePasswordTags.CURRENT, label = true)
        rule.onNodeWithTag(ChangePasswordTags.CURRENT).performImeAction(); rule.waitForIdle()
        focusedAndShown(ChangePasswordTags.NEW, label = true)
        rule.onNodeWithTag(ChangePasswordTags.NEW).performImeAction(); rule.waitForIdle()
        focusedAndShown(ChangePasswordTags.REPEAT, label = true)
        press(Key.DirectionDown); focusedAndShown(ChangePasswordTags.SAVE)
        press(Key.DirectionUp); focusedAndShown(ChangePasswordTags.REPEAT, label = true)
        press(Key.DirectionUp); focusedAndShown(ChangePasswordTags.NEW, label = true)
        press(Key.DirectionUp); focusedAndShown(ChangePasswordTags.CURRENT, label = true)
    }

    // ── R350 re-test: D-pad focus on a field never opens the keyboard on a TV; OK does ────────────────────

    @Test fun `sign-in — arrival, Down and Up focus the fields without the keyboard, OK opens it`() {
        render(keyboardDown) { LoginScreen(store = LoginStore(client()), onSignedIn = {}) }
        focusedAndShown(LoginTags.USERNAME, label = true)
        noKeyboard("arriving on the username")
        press(Key.DirectionDown); focusedAndShown(LoginTags.PASSWORD, label = true)
        noKeyboard("Down onto the password")
        press(Key.DirectionDown); focusedAndShown(LoginTags.SIGN_IN)
        press(Key.DirectionUp); focusedAndShown(LoginTags.PASSWORD, label = true)
        noKeyboard("Up from Sign in onto the password")
        press(Key.DirectionCenter)
        focusedAndShown(LoginTags.PASSWORD)
        org.junit.Assert.assertTrue("OK on the password opens the keyboard", keyboardUp())
        press(Key.DirectionUp); focusedAndShown(LoginTags.USERNAME, label = true)
        noKeyboard("Up onto the username after typing")
    }

    @Test fun `sign-in — OK, then the keyboard's Next carries the typing on to the password`() {
        render(keyboardDown) { LoginScreen(store = LoginStore(client()), onSignedIn = {}) }
        press(Key.DirectionCenter)
        org.junit.Assert.assertTrue(keyboardUp())
        rule.onNodeWithTag(LoginTags.USERNAME).performImeAction(); rule.waitForIdle()
        focusedAndShown(LoginTags.PASSWORD, label = true)
        org.junit.Assert.assertTrue("Next keeps the keyboard for the password", keyboardUp() && session())
    }

    @Test fun `change password — Down from Back and between the fields never opens the keyboard, OK does`() {
        render(keyboardDown) { ChangePasswordScreen(apiClient = client(), onBack = {}, onForceSignOut = {}) }
        noKeyboard("arriving (focus on Back)")
        press(Key.DirectionDown); focusedAndShown(ChangePasswordTags.CURRENT, label = true)
        noKeyboard("Down from Back onto Current password")
        press(Key.DirectionDown); focusedAndShown(ChangePasswordTags.NEW, label = true)
        noKeyboard("Down onto New password")
        press(Key.DirectionDown); focusedAndShown(ChangePasswordTags.REPEAT, label = true)
        noKeyboard("Down onto Repeat")
        press(Key.DirectionDown); focusedAndShown(ChangePasswordTags.SAVE)
        press(Key.DirectionUp); focusedAndShown(ChangePasswordTags.REPEAT, label = true)
        press(Key.DirectionUp); focusedAndShown(ChangePasswordTags.NEW, label = true)
        press(Key.DirectionUp); focusedAndShown(ChangePasswordTags.CURRENT, label = true)
        noKeyboard("walking back up")
        press(Key.DirectionCenter)
        focusedAndShown(ChangePasswordTags.CURRENT)
        org.junit.Assert.assertTrue("OK on Current password opens the keyboard", keyboardUp())
        rule.onNodeWithTag(ChangePasswordTags.CURRENT).performImeAction(); rule.waitForIdle()
        focusedAndShown(ChangePasswordTags.NEW, label = true)
        org.junit.Assert.assertTrue("the keyboard's Next keeps typing in New password", keyboardUp() && session())
    }

    // ── server setup ───────────────────────────────────────────────────────────────────────────────

    @Test fun `server setup — arrives focused on the address without typing, OK edits, Down and Up at both heights`() {
        render(keyboardDown) { ServerSetupScreen(onUrlSaved = {}) }
        focusedAndShown(ServerSetupTags.ADDRESS)   // a visible focus on arrival, and not the text field (no keyboard)
        rule.onNodeWithTag(ServerSetupTags.ADDRESS_FIELD).assertIsNotFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithTag(ServerSetupTags.ADDRESS_FIELD).assertIsFocused()
        for (h in listOf(keyboardDown, keyboardUp)) {
            height = h
            rule.waitForIdle()
            press(Key.DirectionDown); focusedAndShown(ServerSetupTags.CONNECT)
            press(Key.DirectionUp); focusedAndShown(ServerSetupTags.ADDRESS)
            rule.onNodeWithTag(ServerSetupTags.ADDRESS_FIELD).assertIsNotFocused()
            press(Key.DirectionCenter)
            rule.onNodeWithTag(ServerSetupTags.ADDRESS_FIELD).assertIsFocused()
            rule.onNodeWithTag(ServerSetupTags.ADDRESS_FIELD, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test fun `server setup after Wrong server — the last address is filled in and Connect has focus`() {
        render(keyboardDown) { ServerSetupScreen(onUrlSaved = {}, initialAddress = "https://media.example.org/") }
        focusedAndShown(ServerSetupTags.CONNECT)
        rule.onNodeWithTag(ServerSetupTags.ADDRESS_FIELD).assertTextContains("media.example.org")
    }

    @Test @Config(qualifiers = "w412dp-h915dp")
    fun `on a phone the address field takes focus directly, as before`() {
        render(915.dp) { ServerSetupScreen(onUrlSaved = {}) }
        rule.onNodeWithTag(ServerSetupTags.ADDRESS_FIELD).requestFocus(); rule.waitForIdle()
        rule.onNodeWithTag(ServerSetupTags.ADDRESS_FIELD).assertIsFocused()
    }

    @Test fun `a saved address reads back without the inferred https but keeps a typed http`() {
        org.junit.Assert.assertEquals("media.example.org:8097", addressForEditing("https://media.example.org:8097/"))
        org.junit.Assert.assertEquals("http://10.0.0.5:8097", addressForEditing("http://10.0.0.5:8097"))
        org.junit.Assert.assertEquals("", addressForEditing(""))
    }
}
