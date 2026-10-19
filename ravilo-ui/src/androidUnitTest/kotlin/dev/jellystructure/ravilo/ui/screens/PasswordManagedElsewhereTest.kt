package dev.jellystructure.ravilo.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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
 * DEB-1854 (FR-6, FR-7, FR-8, FR-11) — Settings' Account block, the phone's Profile row and Change password, rendered
 * from the config's two fields: a phone (and the web, which draws the same page) gets the link, the sentence or the
 * form; a TV gets no row and no line, and its D-pad steps straight over the gap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@OptIn(ExperimentalTestApi::class)
class PasswordManagedElsewhereTest {
    @get:Rule val rule = createComposeRule()

    private val page = "https://accounts.example.invalid/password"
    private val sub = "Opens your account page in the browser."
    private val none = "Your password is managed outside Ravilo."
    private val opened: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val recorder = object : UriHandler { override fun openUri(uri: String) { opened += uri } }

    private fun configOf(elsewhere: Boolean, url: String? = null) = RaviloConfig(
        themeFollow = false, theme = RaviloThemes.NOIR, themeDark = RaviloThemes.NOIR,
        passwordManagedElsewhere = elsewhere, passwordChangeUrl = url,
    )

    private fun api(config: RaviloConfig, passwordAnswer: String? = null): dev.jellystructure.shared.tv.TvApiClient {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val body = RaviloWireJson.encodeToString(RaviloConfig.serializer(), config)
        return fakeTvApiClient { path ->
            requests += path
            when (path) {
                "/api/tv/config" -> body
                "/api/tv/account/password" -> passwordAnswer
                else -> null
            }
        }
    }

    private fun renderSettings(config: RaviloConfig) {
        val store = SettingsStore(api(config))
        rule.setContent {
            CompositionLocalProvider(LocalUriHandler provides recorder) {
                SettingsScreen(store = store, displayName = "Olivar", onSignOut = {}, onBack = {})
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Autoplay next episode"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun textCount(text: String, substring: Boolean = true) =
        rule.onAllNodes(hasText(text, substring = substring), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun press(key: Key) { rule.onRoot().performKeyInput { pressKey(key) }; rule.waitForIdle() }

    private fun focusedWith(text: String) =
        rule.onNode(isFocused() and (hasText(text, substring = true) or hasAnyDescendant(hasText(text, substring = true))), useUnmergedTree = true).assertExists()

    // ── FR-6, phone / web / desktop (the phone family draws one Settings page) ───────────────────────────────

    @Test fun `a phone sees Change password with the link glyph and the subtitle, and a press opens the page`() {
        renderSettings(configOf(true, page))
        assertEquals(1, textCount("Change password ↗"))
        assertEquals(1, textCount(sub))
        assertEquals(0, textCount(none))
        rule.onNode(hasText("Change password", substring = true), useUnmergedTree = true).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf(page), opened.toList(), "the page opened through the platform's URI handler")
    }

    @Test fun `a phone with no page set sees the one sentence and no row`() {
        renderSettings(configOf(true, null))
        assertEquals(0, textCount("Change password"))
        assertEquals(1, textCount(none))
        assertEquals(0, textCount(sub))
    }

    @Test fun `a local account keeps today's row and no glyph`() {
        renderSettings(configOf(false))
        assertEquals(1, textCount("Change password", substring = false))
        assertEquals(0, textCount("↗"))
        assertEquals(0, textCount(none))
    }

    // ── FR-6, the phone's Profile row ─────────────────────────────────────────────────────────────────────

    private fun renderProfile(row: PasswordRow) {
        val client = api(configOf(false))
        rule.setContent {
            CompositionLocalProvider(LocalUriHandler provides recorder) {
                ProfileScreen(
                    apiClient = client, myListStore = ProfileMyListStore(client), displayName = "Olivar", isAdmin = false, avatarUrl = null,
                    serverHost = "stand-in.invalid", onPhoto = {}, onMyListSeeAll = {}, onItemSelect = {}, onAppLanguage = {},
                    onChangePassword = {}, passwordRow = row, onSettings = {}, onSignedOut = {},
                )
            }
        }
        rule.waitForIdle()
    }

    @Test fun `the Profile row is the link with the subtitle, and opens the page`() {
        renderProfile(PasswordRow.Link(page))
        rule.onNode(hasText("Change password", substring = true), useUnmergedTree = true).performScrollTo().performClick()
        assertEquals(1, textCount(sub))
        assertEquals(listOf(page), opened.toList())
    }

    @Test fun `the Profile row is the sentence when no page is set, and no row`() {
        renderProfile(PasswordRow.Line)
        assertEquals(0, textCount("Change password"))
        assertEquals(1, textCount(none))
    }

    @Test fun `the Profile row is today's row for a local account`() {
        renderProfile(PasswordRow.Form)
        assertEquals(1, textCount("Change password", substring = false))
        assertEquals(0, textCount(none))
    }

    // ── FR-8, client half ─────────────────────────────────────────────────────────────────────────────────

    @Test fun `Change password given managed_elsewhere shows the link in place of the generic error`() {
        val client = api(configOf(true, page), passwordAnswer = """{"ok":false,"managed_elsewhere":true}""")
        rule.setContent {
            CompositionLocalProvider(LocalUriHandler provides recorder) { ChangePasswordScreen(apiClient = client, onBack = {}, onForceSignOut = {}) }
        }
        fill()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(sub, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, textCount("Something went wrong"))
        rule.onNodeWithTag(ChangePasswordTags.LINK).performClick()
        assertEquals(listOf(page), opened.toList())
    }

    @Test fun `Change password given managed_elsewhere and no page shows the sentence`() {
        val client = api(configOf(true, null), passwordAnswer = """{"ok":false,"managed_elsewhere":true}""")
        rule.setContent { ChangePasswordScreen(apiClient = client, onBack = {}, onForceSignOut = {}) }
        fill()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(none, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, textCount("Something went wrong"))
    }

    private fun fill() {
        for (tag in listOf(ChangePasswordTags.CURRENT, ChangePasswordTags.NEW, ChangePasswordTags.REPEAT)) {
            rule.onNodeWithTag(tag).performTextInput(if (tag == ChangePasswordTags.CURRENT) "old-secret" else "new-secret")
        }
        rule.onNodeWithTag(ChangePasswordTags.SAVE).performClick()
        rule.waitForIdle()
    }

    // ── FR-7, the TV ──────────────────────────────────────────────────────────────────────────────────────

    @Test @Config(qualifiers = "w960dp-h540dp-television")
    fun `a TV draws no row and no line for an account managed elsewhere`() {
        renderSettings(configOf(true, page))
        assertEquals(0, textCount("Change password"))
        assertEquals(0, textCount(none))
        assertEquals(0, textCount(sub))
    }

    @Test @Config(qualifiers = "w960dp-h540dp-television")
    fun `a TV's D-pad steps from Autoplay straight to Sign out and back, no gap`() {
        renderSettings(configOf(true, null))
        // Walk down from Back to Autoplay, as SettingsFocusTest does for a local account.
        press(Key.DirectionDown); focusedWith("Aurora")
        press(Key.DirectionDown); focusedWith("English")
        press(Key.DirectionDown); focusedWith("Show progress on Continue Watching")
        press(Key.DirectionDown); focusedWith("Autoplay next episode")
        press(Key.DirectionDown); focusedWith("Sign out")
        press(Key.DirectionUp); focusedWith("Autoplay next episode")
    }

    @Test @Config(qualifiers = "w960dp-h540dp-television")
    fun `a TV with a local account still has the row between Autoplay and Sign out`() {
        renderSettings(configOf(false))
        press(Key.DirectionDown); press(Key.DirectionDown); press(Key.DirectionDown); press(Key.DirectionDown)
        focusedWith("Autoplay next episode")
        press(Key.DirectionDown); focusedWith("Change password")
        press(Key.DirectionDown); focusedWith("Sign out")
        press(Key.DirectionUp); focusedWith("Change password")
        assertTrue(requests.contains("/api/tv/config"))
    }
}
