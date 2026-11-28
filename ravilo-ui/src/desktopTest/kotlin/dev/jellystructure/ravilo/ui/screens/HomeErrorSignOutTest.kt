package dev.jellystructure.ravilo.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.runComposeUiTest
import dev.jellystructure.ravilo.ui.components.LoadErrorKind
import androidx.compose.ui.test.assertIsDisplayed
import dev.jellystructure.ravilo.ui.theme.LocalHandset
import dev.jellystructure.shared.tv.TvApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * DEB-5835 (a-failure-says-what-happened-and-offers-a-way-out.md FR-2) — Sign out on Home's can't-reach page asks first:
 * R340's choice overlay on the TV layout, the one-profile confirm on a handset, and nothing is signed out until confirmed.
 */
@OptIn(ExperimentalTestApi::class)
class HomeErrorSignOutTest {
    private val api = TvApiClient(HttpClient(OkHttp), "http://127.0.0.1:1", { "token" }, platform = "tv")

    @androidx.compose.runtime.Composable private fun Page(handset: Boolean, desktop: Boolean = false, onOut: () -> Unit) {
        CompositionLocalProvider(LocalHandset provides handset) { HomeErrorState(LoadErrorKind.UNREACHABLE, api, {}, onOut, desktop = desktop) }
    }

    @Test fun `on the TV layout Sign out opens the choice overlay and signs nothing out`() = runComposeUiTest {
        var signedOut = 0
        setContent { Page(false) { signedOut++ } }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        onNodeWithText("Everyone on this TV").assertIsDisplayed()
        assertEquals(0, signedOut)
        onNodeWithText("Cancel").performClick()
        waitForIdle()
        assertEquals(0, signedOut)
    }

    @Test fun `on the desktop Sign out opens its own confirm and signs nothing out`() = runComposeUiTest {
        var signedOut = 0
        setContent { Page(false, desktop = true) { signedOut++ } }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        onNodeWithText("Sign out of this computer?").assertIsDisplayed()
        assertEquals(0, signedOut)
    }

    @Test fun `on a handset Sign out opens the one-profile confirm and signs nothing out`() = runComposeUiTest {
        var signedOut = 0
        setContent { Page(true) { signedOut++ } }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        onNodeWithText("Sign out of this phone?").assertIsDisplayed()
        assertEquals(0, signedOut)
    }
}
