package dev.jellystructure.ravilo.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.jellystructure.ravilo.ui.LocalReauthRequired
import kotlin.test.Test
import kotlin.test.assertEquals

/** DEB-5835 (FR-1) — a REAUTH failure always has a Sign in button where the app can sign the profile out. */
@OptIn(ExperimentalTestApi::class)
class LoadErrorSignInTest {
    @Test fun `REAUTH with only the local reauth route draws Sign in and pressing it calls the route`() = runComposeUiTest {
        var called = 0
        setContent {
            CompositionLocalProvider(LocalReauthRequired provides { called++ }) {
                LoadErrorState(LoadErrorKind.REAUTH, onBack = {})
            }
        }
        onNodeWithText("Sign in").assertIsDisplayed().performClick()
        assertEquals(1, called)
        onAllNodesWithText("Back").assertCountEquals(0)
    }

    @Test fun `REAUTH with no local reauth route keeps Back`() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalReauthRequired provides null) {
                LoadErrorState(LoadErrorKind.REAUTH, onBack = {})
            }
        }
        onNodeWithText("Back").assertIsDisplayed()
        onAllNodesWithText("Sign in").assertCountEquals(0)
    }
}
