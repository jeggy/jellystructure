package dev.jellystructure.ravilo.ui.screens

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performClick
import dev.jellystructure.shared.tv.TvApiClient
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DEB-2233 (FR-5) — the TV's sign-in screen on a 960 × 540 dp television: it opens on the code and QR with focus on
 * *Sign in with a password instead*, that button opens today's form and Back returns to the code, a server that cannot
 * sign a TV in opens the form directly, and without a link there is a code and no QR. Answers come from an interceptor,
 * so nothing touches a network; the poll answers 404 (a dropped poll), which the screen shrugs off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-television")
@OptIn(ExperimentalTestApi::class)
class TvSignInScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun codeBody(link: String?) =
        """{"available":true,"code":"K7P4QX","expiresAt":600,"secret":"s","linkUrl":${if (link == null) "null" else "\"$link\""}}"""

    private fun client(codeJson: String): TvApiClient = fakeTvApiClient { path -> if (path == "/api/tv/signin/code") codeJson else null }

    private var backed = 0
    private var passwordFormShown = false

    private fun render(api: TvApiClient, onBack: (() -> Unit)? = null) {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        rule.setContent {
            TvSignInScreen(apiClient = api, onSignedIn = {}, onBack = onBack) {
                passwordFormShown = true
                LoginScreen(store = LoginStore(api), onSignedIn = {})
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithTagCount(TvSignInTags.CODE) > 0 || rule.onAllNodesWithTagCount(TvSignInTags.RETRY) > 0 || passwordFormShown }
        rule.waitForIdle()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test
    fun `it opens on the code and the QR with focus on the password button`() {
        render(client(codeBody("https://ravilo.example.net/tv?c=K7P4QX")))
        rule.onNodeWithTag(TvSignInTags.CODE).assertIsDisplayed().assertTextContains("K7P 4QX")
        rule.onNodeWithTag(TvSignInTags.QR).assertIsDisplayed()
        rule.onNodeWithTag(TvSignInTags.PASSWORD).assertIsDisplayed().assertIsFocused()
        rule.onNodeWithText("Sign in with a password instead").assertIsDisplayed()
    }

    @Test
    fun `the password button opens today's form and Back returns to the code`() {
        render(client(codeBody("https://ravilo.example.net/tv?c=K7P4QX")))
        press(Key.DirectionCenter)
        rule.onNodeWithTag(LoginTags.USERNAME).assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTagCount(TvSignInTags.CODE))
        press(Key.Back)
        rule.waitUntil(5_000) { rule.onAllNodesWithTagCount(TvSignInTags.CODE) > 0 }
        rule.onNodeWithTag(TvSignInTags.PASSWORD).assertIsFocused()
    }

    @Test
    fun `a click on the password button opens the form too`() {
        render(client(codeBody("https://ravilo.example.net/tv?c=K7P4QX")))
        rule.onNodeWithTag(TvSignInTags.PASSWORD).performClick()
        rule.onNodeWithTag(LoginTags.USERNAME).assertIsDisplayed()
    }

    @Test
    fun `without a link there is the code and no QR`() {
        render(client(codeBody(null)))
        rule.onNodeWithTag(TvSignInTags.CODE).assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTagCount(TvSignInTags.QR))
        rule.onNodeWithText("Open Ravilo on a phone or computer and choose Sign in a TV.").assertIsDisplayed()
        rule.onNodeWithTag(TvSignInTags.PASSWORD).assertIsFocused()
    }

    @Test
    fun `a server that cannot sign a TV in opens the password form directly`() {
        render(client("""{"available":false}"""))
        rule.waitUntil(5_000) { rule.onAllNodesWithTagCount(LoginTags.USERNAME) > 0 }
        assertEquals(0, rule.onAllNodesWithTagCount(TvSignInTags.CODE))
        assertEquals(0, rule.onAllNodesWithTagCount(TvSignInTags.QR))
    }

    @Test
    fun `an unreachable server shows the ordinary message with retry focused and the password button after it`() {
        render(fakeTvApiClient { null })
        rule.waitUntil(5_000) { rule.onAllNodesWithTagCount(TvSignInTags.RETRY) > 0 }
        rule.onNodeWithText("Couldn't reach the server — try again").assertIsDisplayed()
        rule.onNodeWithTag(TvSignInTags.RETRY).assertIsFocused()
        rule.onNodeWithTag(TvSignInTags.PASSWORD).assertIsDisplayed()
    }

    @Test
    fun `add user says Back returns to the profiles and Back goes there`() {
        render(client(codeBody("https://ravilo.example.net/tv?c=K7P4QX")), onBack = { backed++ })
        rule.onNodeWithText("Back returns to the profiles").assertIsDisplayed()
        press(Key.Back)
        assertEquals(1, backed)
    }

    @Test
    fun `no single sign-on button anywhere on the screen`() {
        render(client(codeBody("https://ravilo.example.net/tv?c=K7P4QX")))
        // The one "Sign in with ..." on the screen is the password button.
        assertEquals(1, rule.onAllNodesWithText("Sign in with ", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
        for (word in listOf("single sign-on", "SSO")) {
            assertEquals("'$word' on the TV screen", 0, rule.onAllNodesWithText(word, substring = true, ignoreCase = true).fetchSemanticsNodes().size)
        }
    }
}
