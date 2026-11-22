package dev.jellystructure.ravilo.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DEB-4843 (FR-1, FR-9) — *Samsung TV · Experimental* sits between *Sign in a TV* and *Settings*, and is no row at all when the callback is null. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h900dp")
@OptIn(ExperimentalTestApi::class)
class ProfileSamsungTvRowTest {
    @get:Rule val rule = createComposeRule()
    private var opened = 0

    private fun render(onSamsungTv: (() -> Unit)?) {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val client = fakeTvApiClient { null }
        rule.setContent {
            ProfileScreen(
                apiClient = client, myListStore = ProfileMyListStore(client), displayName = "Olivar", isAdmin = false, avatarUrl = null, serverHost = "stand-in.invalid",
                onPhoto = {}, onMyListSeeAll = {}, onItemSelect = {}, onAppLanguage = {}, onChangePassword = {},
                onSignInTv = {}, onSamsungTv = onSamsungTv, onSettings = {}, onSignedOut = {},
            )
        }
        rule.waitForIdle()
    }

    @Test fun `the row sits after Sign in a TV and before Settings, with the Experimental badge`() {
        render { opened++ }
        val signIn = rule.onNodeWithTag(TvApproveTags.ROW).getBoundsInRoot().top
        val samsung = rule.onNodeWithTag(PROFILE_SAMSUNG_TV_TAG).getBoundsInRoot().top
        val settings = rule.onAllNodes(hasText("Settings"), useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot.top
        assertTrue(signIn < samsung, "Samsung TV is above Sign in a TV")
        assertTrue(samsung < with(rule.density) { settings.toDp() }, "Samsung TV is below Settings")
        rule.onNode(hasText("EXPERIMENTAL"), useUnmergedTree = true).assertExists()
    }

    @Test fun `tapping the row opens the guide`() {
        render { opened++ }
        rule.onNodeWithTag(PROFILE_SAMSUNG_TV_TAG).performClick()
        assertEquals(1, opened)
    }

    @Test fun `with no callback there is no row`() {
        render(null)
        assertEquals(0, rule.onAllNodes(hasTestTag(PROFILE_SAMSUNG_TV_TAG)).fetchSemanticsNodes().size)
    }
}
