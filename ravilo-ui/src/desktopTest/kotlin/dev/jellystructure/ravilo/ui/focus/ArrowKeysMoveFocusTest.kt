package dev.jellystructure.ravilo.ui.focus

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * R350 (FR-R350-11, FR-R350-19) — the root's arrow handling: an arrow nobody consumed moves focus, an arrow a focused
 * element consumed stays that element's, and a modified arrow is never a focus move.
 */
@OptIn(ExperimentalTestApi::class)
class ArrowKeysMoveFocusTest {
    private val a = FocusRequester()
    private val field = FocusRequester()
    private val own = FocusRequester()

    private fun androidx.compose.ui.test.ComposeUiTest.content(onRight: () -> Unit = {}, text: (TextFieldValue) -> Unit = {}) {
        setContent {
            var value by remember { mutableStateOf(TextFieldValue("abc", TextRange(3))) }
            Box(Modifier.arrowKeysMoveFocus(true)) {
                Column {
                    Box(Modifier.testTag("a").size(40.dp).focusRequester(a).focusable())
                    BasicTextField(
                        value = value,
                        onValueChange = { value = it; text(it) },
                        modifier = Modifier.testTag("field").focusRequester(field),
                    )
                    Box(Modifier.testTag("own").size(40.dp).focusRequester(own).dpadFocusable(onRight = onRight))
                }
            }
        }
        waitForIdle()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.press(key: Key, modifier: Key? = null) {
        onRoot().performKeyInput {
            if (modifier != null) keyDown(modifier)
            keyDown(key); keyUp(key)
            if (modifier != null) keyUp(modifier)
        }
        waitForIdle()
    }

    @Test fun downFromAButtonMovesFocusToTheNextFocusableBelow() = runComposeUiTest {
        content()
        runOnIdle { a.requestFocus() }
        press(Key.DirectionDown)
        onNodeWithTag("field").assertIsFocused()
    }

    @Test fun leftInAFocusedTextFieldMovesTheCaretAndKeepsFocus() = runComposeUiTest {
        var last = TextFieldValue("")
        content(text = { last = it })
        runOnIdle { field.requestFocus() }
        press(Key.DirectionLeft)
        onNodeWithTag("field").assertIsFocused()
        assertEquals(TextRange(2), last.selection)
    }

    @Test fun anElementWithItsOwnDirectionHandlerKeepsItsArrow() = runComposeUiTest {
        var hits = 0
        content(onRight = { hits++ })
        runOnIdle { own.requestFocus() }
        press(Key.DirectionRight)
        onNodeWithTag("own").assertIsFocused()
        assertEquals(1, hits)
    }

    @Test fun aModifiedArrowIsNotAFocusMove() = runComposeUiTest {
        content()
        for (m in listOf(Key.AltLeft, Key.ShiftLeft, Key.CtrlLeft, Key.MetaLeft)) {
            runOnIdle { a.requestFocus() }
            press(Key.DirectionDown, m)
            onNodeWithTag("a").assertIsFocused()
        }
    }
}
