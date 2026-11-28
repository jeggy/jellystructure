package dev.jellystructure.ravilo.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.jellystructure.ravilo.ui.components.CLIENT_UPDATE_LINE_TAG
import dev.jellystructure.ravilo.ui.components.InstallSource
import dev.jellystructure.shared.tv.RaviloVersion
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** DEB-5851 (FR-6) — the TV's Settings ▸ About: the version, the quiet line when one is due, one focus stop that opens nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-television")
@OptIn(ExperimentalTestApi::class)
class TvAboutSectionTest {
    @get:Rule val rule = createComposeRule()
    private val opened = mutableListOf<String>()
    private val handler = object : UriHandler { override fun openUri(uri: String) { opened += uri } }

    private fun render(notice: RaviloVersion?) {
        rule.setContent {
            CompositionLocalProvider(LocalUriHandler provides handler) {
                TvAboutSection(notice, InstallSource.Download, FocusRequester(), onUp = {})
            }
        }
        rule.waitForIdle()
    }

    @Test fun `the About heading and the version are there with nothing to say`() {
        render(null)
        rule.onNodeWithTag(TV_ABOUT_TAG).assertExists()
        rule.onNode(hasText("About"), useUnmergedTree = true).assertExists()
        assertEquals(0, rule.onAllNodes(hasTestTag(CLIENT_UPDATE_LINE_TAG)).fetchSemanticsNodes().size)
    }

    @Test fun `a newer release adds the line under the version`() {
        render(RaviloVersion(1, 73))
        rule.onNode(hasText("Ravilo 1.73 is available"), useUnmergedTree = true).assertExists()
        rule.onNode(hasText("Download it from the Ravilo releases page."), useUnmergedTree = true).assertExists()
    }

    @Test fun `the row is not actionable - a click opens nothing`() {
        render(RaviloVersion(1, 73))
        rule.onNodeWithTag(TV_ABOUT_TAG).performClick()
        assertEquals(emptyList(), opened)
    }
}
