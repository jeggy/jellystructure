package dev.jellystructure.ravilo.ui.screens

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.requestFocus
import dev.jellystructure.shared.tv.TvApiClient
import io.ktor.client.HttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R348 — what each sign-in and change-password field tells the keyboard, read from the `EditorInfo` Compose hands the
 * input method (what Gboard reads). A password field must carry `TYPE_TEXT_VARIATION_PASSWORD` and no autocorrect, or
 * the keyboard shows the typed password in its suggestion strip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class PasswordKeyboardTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var view: View

    private fun loginStore() = LoginStore(TvApiClient(HttpClient(), "https://server.invalid", { null }))

    private fun editorInfoOf(tag: String): EditorInfo {
        rule.onNodeWithTag(tag).requestFocus()
        rule.waitForIdle()
        rule.onNodeWithTag(tag).assertIsFocused()
        val info = EditorInfo()
        val connection = rule.runOnIdle { view.onCreateInputConnection(info) }
        assertNotNull("the focused field opened no input connection", connection)
        return info
    }

    private fun assertPassword(info: EditorInfo) {
        assertEquals(InputType.TYPE_CLASS_TEXT, info.inputType and InputType.TYPE_MASK_CLASS)
        assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, info.inputType and InputType.TYPE_MASK_VARIATION)
        assertEquals(0, info.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        assertEquals(0, info.inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
    }

    @Test fun `the sign-in password field is a password to the keyboard`() {
        rule.setContent { view = LocalView.current; LoginScreen(store = loginStore(), onSignedIn = {}) }
        assertPassword(editorInfoOf(LoginTags.PASSWORD))
    }

    @Test fun `the username field is plain text with no autocorrect and no capitals`() {
        rule.setContent { view = LocalView.current; LoginScreen(store = loginStore(), onSignedIn = {}) }
        val info = editorInfoOf(LoginTags.USERNAME)
        assertEquals(InputType.TYPE_TEXT_VARIATION_NORMAL, info.inputType and InputType.TYPE_MASK_VARIATION)
        assertEquals(0, info.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        assertEquals(0, info.inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        assertEquals(EditorInfo.IME_ACTION_NEXT, info.imeOptions and EditorInfo.IME_MASK_ACTION)
    }

    @Test fun `Next on the username moves to the password and Done on the password signs in`() {
        val store = loginStore()
        rule.setContent { view = LocalView.current; LoginScreen(store = store, onSignedIn = {}) }
        rule.onNodeWithTag(LoginTags.USERNAME).requestFocus()
        rule.onNodeWithTag(LoginTags.USERNAME).performImeAction()
        rule.waitForIdle()
        rule.onNodeWithTag(LoginTags.PASSWORD).assertIsFocused()
        rule.onNodeWithTag(LoginTags.PASSWORD).performImeAction()
        rule.waitForIdle()
        // Both fields empty: the store answers "required" without touching the network — proof Done submitted.
        assertEquals(LoginState.Errored(LoginErrorKind.REQUIRED), store.state.value)
    }

    @Test fun `every change-password field is a password to the keyboard`() {
        rule.setContent {
            view = LocalView.current
            ChangePasswordScreen(apiClient = TvApiClient(HttpClient(), "https://server.invalid", { null }), onBack = {}, onForceSignOut = {})
        }
        for (tag in listOf(ChangePasswordTags.CURRENT, ChangePasswordTags.NEW, ChangePasswordTags.REPEAT)) {
            val info = editorInfoOf(tag)
            assertPassword(info)
            assertTrue("$tag has an IME action", (info.imeOptions and EditorInfo.IME_MASK_ACTION) != EditorInfo.IME_ACTION_UNSPECIFIED)
        }
    }
}
