package dev.jellystructure.ravilo.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.jellystructure.ravilo.ui.components.CLIENT_UPDATE_LINE_TAG
import dev.jellystructure.ravilo.ui.components.ClientUpdate
import dev.jellystructure.ravilo.ui.components.ClientUpdateLine
import dev.jellystructure.ravilo.ui.components.InstallSource
import dev.jellystructure.ravilo.ui.components.UpdateSurface
import dev.jellystructure.ravilo.ui.components.updateDotFor
import dev.jellystructure.shared.tv.RaviloVersion
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-5851 (FR-4, FR-5) — the phone's quiet update line on Profile, and what a tap on it opens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h900dp")
@OptIn(ExperimentalTestApi::class)
class ProfileUpdateLineTest {
    @get:Rule val rule = createComposeRule()
    private val opened = mutableListOf<String>()
    private val handler = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
    private val v173 = RaviloVersion(1, 73)

    @Before fun init() {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        ClientUpdate.resetForTest(null)
    }

    private fun renderProfile() {
        val client = fakeTvApiClient { null }
        rule.setContent {
            CompositionLocalProvider(LocalUriHandler provides handler) {
                ProfileScreen(
                    apiClient = client, myListStore = ProfileMyListStore(client), displayName = "Olivar", isAdmin = false, avatarUrl = null, serverHost = "stand-in.invalid",
                    onPhoto = {}, onMyListSeeAll = {}, onItemSelect = {}, onAppLanguage = {}, onChangePassword = {},
                    onSignInTv = {}, onSamsungTv = null, onSettings = {}, onSignedOut = {},
                )
            }
        }
        rule.waitForIdle()
    }

    @Test fun `no notice, no line`() {
        renderProfile()
        assertEquals(0, rule.onAllNodes(hasTestTag(CLIENT_UPDATE_LINE_TAG)).fetchSemanticsNodes().size)
    }

    @Test fun `the line sits directly above the version line, with the download words off Play`() {
        ClientUpdate.resetForTest(v173)
        renderProfile()
        rule.onNodeWithTag(CLIENT_UPDATE_LINE_TAG).assertExists()
        rule.onNode(hasText("Ravilo 1.73 is available"), useUnmergedTree = true).assertExists()
        rule.onNode(hasText("Download it from the Ravilo releases page."), useUnmergedTree = true).assertExists()
        val line = rule.onNodeWithTag(CLIENT_UPDATE_LINE_TAG).getBoundsInRoot()
        val version = rule.onNode(hasText("Ravilo ", substring = true) and hasText("signed in to stand-in.invalid", substring = true), useUnmergedTree = true).getBoundsInRoot()
        assertTrue(line.bottom <= version.top, "the line is above the version line")
    }

    @Test fun `opening Profile once for the release takes the dot away and keeps the line`() {
        ClientUpdate.resetForTest(v173)
        renderProfile()
        assertFalse(updateDotFor(ClientUpdate.notice.value, ClientUpdate.seenFlow.value[UpdateSurface.Profile]))
        rule.onNodeWithTag(CLIENT_UPDATE_LINE_TAG).assertExists()
    }

    @Test fun `a download opens the releases page and Play opens its own page`() {
        rule.setContent {
            CompositionLocalProvider(LocalUriHandler provides handler) {
                ClientUpdateLine(v173, InstallSource.Download, openable = true)
            }
        }
        rule.onNodeWithTag(CLIENT_UPDATE_LINE_TAG).performClick()
        assertEquals(listOf("https://github.com/jeggy/jellystructure/releases/latest"), opened)
    }

    @Test fun `Google Play says its own words and opens the Play page`() {
        rule.setContent {
            CompositionLocalProvider(LocalUriHandler provides handler) {
                ClientUpdateLine(v173, InstallSource.GooglePlay, openable = true)
            }
        }
        rule.onNode(hasText("Update it in the Google Play Store."), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(CLIENT_UPDATE_LINE_TAG).performClick()
        assertEquals(1, opened.size)
        assertTrue(opened[0].startsWith("https://play.google.com/store/apps/details?id="), opened[0])
    }

    @Test fun `the dot shows until that release has been seen, and a larger one brings it back`() {
        assertTrue(updateDotFor(v173, null))
        assertFalse(updateDotFor(v173, "1.73"))
        assertTrue(updateDotFor(RaviloVersion(1, 74), "1.73"))
        assertFalse(updateDotFor(null, null))
    }
}
