package dev.jellystructure.ravilo.ui.focus

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.jellystructure.ravilo.ui.screens.ConfirmOverlay
import dev.jellystructure.ravilo.ui.screens.SignOutChoiceOverlay
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DialogKeepsFocusTest {

    @Composable
    private fun Behind(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().arrowKeysMoveFocus(true)) {
            Row(Modifier.align(Alignment.TopEnd)) { Box(Modifier.size(40.dp).testTag("behind").focusable()) }
            content()
        }
    }

    private fun ComposeUiTest.press(keys: List<Key>) {
        for (k in keys) {
            onRoot().performKeyInput { pressKey(k) }
            waitForIdle()
            onNodeWithTag("behind").assertIsNotFocused()
            onNode(isFocused()).assertExists()
        }
    }

    private fun signOut(onCancel: () -> Unit = {}): @Composable () -> Unit =
        { Behind { SignOutChoiceOverlay(displayName = "A", onCancel = onCancel, onOnly = {}, onEveryone = {}) } }

    @Test
    fun `sign out dialog Down then Right stays inside`() = runComposeUiTest {
        setContent(signOut()); waitForIdle()
        press(listOf(Key.DirectionDown, Key.DirectionRight))
    }

    @Test
    fun `sign out dialog every dead end stays inside`() = runComposeUiTest {
        setContent(signOut()); waitForIdle()
        press(listOf(Key.DirectionUp, Key.DirectionLeft, Key.DirectionRight))
        press(listOf(Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight))
        press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionRight, Key.DirectionLeft, Key.DirectionLeft, Key.DirectionDown))
    }

    @Test
    fun `sign out dialog from the Everyone row stays inside`() = runComposeUiTest {
        setContent(signOut()); waitForIdle()
        press(listOf(Key.DirectionDown, Key.DirectionCenter, Key.DirectionDown, Key.DirectionRight, Key.DirectionDown))
    }

    @Test
    fun `confirm dialog dead ends stay inside`() = runComposeUiTest {
        setContent { Behind { ConfirmOverlay(title = "t", description = "d", confirmLabel = "c", onCancel = {}, onConfirm = {}) } }
        waitForIdle()
        press(listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown, Key.DirectionRight))
    }

    @Test
    fun `the trap does not swallow the dialog own moves`() = runComposeUiTest {
        var cancels = 0
        setContent(signOut { cancels++ }); waitForIdle()
        press(listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionLeft, Key.Enter))
        assertEquals(1, cancels)
    }
}
