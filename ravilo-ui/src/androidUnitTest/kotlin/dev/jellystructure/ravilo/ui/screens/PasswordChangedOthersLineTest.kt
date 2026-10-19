package dev.jellystructure.ravilo.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * DEB-1933 (DEB-1926, FR-R234-7) — Change your password, rendered from the route's answer: *"Password changed."* always,
 * and one more line only when the server says another device could not be kept signed in. An older server omits the field.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@OptIn(ExperimentalTestApi::class)
class PasswordChangedOthersLineTest {
    @get:Rule val rule = createComposeRule()

    private val line = "Some of your other devices will ask you to sign in again."

    private fun changePassword(answer: String) {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val client = fakeTvApiClient { path -> if (path == "/api/tv/account/password") answer else null }
        rule.setContent { ChangePasswordScreen(apiClient = client, onBack = {}, onForceSignOut = {}) }
        rule.onNodeWithTag(ChangePasswordTags.CURRENT).performTextInput("old-secret")
        rule.onNodeWithTag(ChangePasswordTags.NEW).performTextInput("new-secret")
        rule.onNodeWithTag(ChangePasswordTags.REPEAT).performTextInput("new-secret")
        rule.onNodeWithTag(ChangePasswordTags.SAVE).performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Password changed."), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun lineCount() = rule.onAllNodes(hasText(line), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test fun `the result with other_devices_signed_out true adds the one line`() {
        changePassword("""{"ok":true,"token_survived":true,"other_devices_signed_out":true}""")
        assertEquals(1, lineCount())
        assertEquals(1, rule.onAllNodes(hasTestTag(ChangePasswordTags.OTHERS), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun `the result with other_devices_signed_out false adds no line`() {
        changePassword("""{"ok":true,"token_survived":true,"other_devices_signed_out":false}""")
        assertEquals(0, lineCount())
    }

    @Test fun `the result from an older server with no field adds no line`() {
        changePassword("""{"ok":true,"token_survived":true}""")
        assertEquals(0, lineCount())
    }

    @Test fun `the confirmation still returns to Settings after the line is shown`() {
        var backs = 0
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val client = fakeTvApiClient { path -> if (path == "/api/tv/account/password") """{"ok":true,"other_devices_signed_out":true}""" else null }
        rule.setContent { ChangePasswordScreen(apiClient = client, onBack = { backs++ }, onForceSignOut = {}) }
        rule.onNodeWithTag(ChangePasswordTags.CURRENT).performTextInput("old-secret")
        rule.onNodeWithTag(ChangePasswordTags.NEW).performTextInput("new-secret")
        rule.onNodeWithTag(ChangePasswordTags.REPEAT).performTextInput("new-secret")
        rule.onNodeWithTag(ChangePasswordTags.SAVE).performClick()
        rule.waitUntil(10_000) { backs == 1 }
        assertEquals(1, backs)
    }
}
