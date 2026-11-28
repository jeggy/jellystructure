package dev.jellystructure.ravilo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import dev.jellystructure.ravilo.ui.components.LoadErrorKind
import dev.jellystructure.ravilo.ui.theme.LocalHandset
import dev.jellystructure.shared.tv.TvApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * DEB-5835 (a-failure-says-what-happened-and-offers-a-way-out.md FR-2) — Sign out on Home's can't-reach page asks first:
 * R340's choice overlay on the TV layout, the one-profile confirm on a handset, and nothing is signed out until confirmed.
 * The revoke runs against a dead server, so it is best effort and must still reach the callback.
 */
@OptIn(ExperimentalTestApi::class)
class HomeErrorSignOutTest {
    private val api = TvApiClient(HttpClient(OkHttp), "http://127.0.0.1:1", { "token" }, platform = "tv")
    private var all = 0
    private var one = 0

    @Composable private fun Page(handset: Boolean, desktop: Boolean = false) {
        CompositionLocalProvider(LocalHandset provides handset) {
            HomeErrorState(LoadErrorKind.UNREACHABLE, api, {}, { all++ }, onSignedOutOne = { one++ }, desktop = desktop)
        }
    }

    private fun ComposeUiTest.openSignOut() {
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
    }

    @Test fun `on the TV layout Sign out opens the choice overlay and signs nothing out`() = runComposeUiTest {
        setContent { Page(false) }
        openSignOut()
        onNodeWithText("Everyone on this TV").assertIsDisplayed()
        assertEquals(0, all + one)
        onNodeWithText("Cancel").performClick()
        waitForIdle()
        assertEquals(0, all + one)
    }

    @Test fun `on the TV layout Only signs out one profile and routes by what is left`() = runComposeUiTest {
        setContent { Page(false) }
        openSignOut()
        onNodeWithText("Yes, sign out").performClick()
        waitUntil(timeoutMillis = 5_000) { one == 1 }
        assertEquals(0, all)
    }

    @Test fun `on the TV layout Everyone on this TV unpairs all and calls onSignOut`() = runComposeUiTest {
        setContent { Page(false) }
        openSignOut()
        onNodeWithText("Everyone on this TV").performClick()
        onNodeWithText("Sign out everyone").performClick()
        waitUntil(timeoutMillis = 5_000) { all == 1 }
        assertEquals(0, one)
    }

    @Test fun `on a handset Sign out opens the one-profile confirm and signs nothing out`() = runComposeUiTest {
        setContent { Page(true) }
        openSignOut()
        onNodeWithText("Sign out of this phone?").assertIsDisplayed()
        assertEquals(0, all + one)
    }

    @Test fun `on a handset confirming signs out one profile`() = runComposeUiTest {
        setContent { Page(true) }
        openSignOut()
        onNodeWithText("Sign out of this phone?").assertIsDisplayed()
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 5_000) { one == 1 }
        assertEquals(0, all)
    }

    @Test fun `on the desktop Sign out opens its own confirm and signs nothing out`() = runComposeUiTest {
        setContent { Page(false, desktop = true) }
        openSignOut()
        onNodeWithText("Sign out of this computer?").assertIsDisplayed()
        assertEquals(0, all + one)
    }
}
