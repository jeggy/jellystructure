package dev.jellystructure.ravilo.ui.desktop

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.jellystructure.ravilo.ui.desktop.SpaceKey.Verdict
import dev.jellystructure.ravilo.ui.seams.DesktopTextInputWatch
import dev.jellystructure.ravilo.ui.seams.reportTextFieldFocus
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DEB-4133 (R337 FR-R337-10, amendment 2026-11-09) — on the desktop a bare Space plays or pauses whatever has focus and
 * never presses the focused control; a text field keeps it; a modifier makes it another key; a held Space toggles once;
 * Return is never Space. The decision is pure; the window's preview handler acts on its verdict.
 */
@OptIn(ExperimentalTestApi::class)
class SpaceKeyTest {
    @AfterTest fun reset() { SpaceKey.reset() }

    private fun space(down: Boolean, modified: Boolean = false, field: Boolean = false, held: Boolean = false) =
        SpaceKey.decide(space = true, down = down, modified = modified, textFieldFocused = field, held = held)

    @Test fun `a bare Space going down toggles and is consumed, and the key is held until it comes up`() {
        val d = space(down = true)
        assertEquals(Verdict.TOGGLE, d.verdict)
        assertTrue(d.heldAfter)
        val up = space(down = false, held = true)
        assertEquals(Verdict.SWALLOW, up.verdict)   // consumed too: the focused control's click-on-release never fires
        assertFalse(up.heldAfter)
    }

    @Test fun `a held Space toggles once - every repeat is swallowed and the next press after the release toggles again`() {
        var held = false
        var toggles = 0
        for (i in 0 until 20) {   // the first KeyDown, then nineteen repeats
            val d = space(down = true, held = held)
            if (d.verdict == Verdict.TOGGLE) toggles++ else assertEquals(Verdict.SWALLOW, d.verdict)
            held = d.heldAfter
        }
        assertEquals(1, toggles)
        held = space(down = false, held = held).heldAfter
        assertFalse(held)
        assertEquals(Verdict.TOGGLE, space(down = true, held = held).verdict)
    }

    @Test fun `a text field with focus keeps Space, down and up, and nothing is held`() {
        val d = space(down = true, field = true)
        assertEquals(Verdict.PASS, d.verdict)
        assertFalse(d.heldAfter)
        assertEquals(Verdict.PASS, space(down = false, field = true).verdict)
        // Focus moved into a field between the key going down and coming up: the up still ends the hold.
        val up = space(down = false, field = true, held = true)
        assertEquals(Verdict.PASS, up.verdict)
        assertFalse(up.heldAfter)
    }

    @Test fun `Space with a modifier is another key and passes - the system's Cmd-Space, Shift-Space in a list`() {
        val d = space(down = true, modified = true)
        assertEquals(Verdict.PASS, d.verdict)
        assertFalse(d.heldAfter)
        assertEquals(Verdict.PASS, space(down = false, modified = true).verdict)
    }

    @Test fun `any other key passes whatever the state - Return and keypad Enter keep pressing what has focus`() {
        for (down in listOf(true, false)) for (field in listOf(true, false)) for (held in listOf(true, false)) {
            val d = SpaceKey.decide(space = false, down = down, modified = false, textFieldFocused = field, held = held)
            assertEquals(Verdict.PASS, d.verdict)
            assertEquals(held, d.heldAfter)   // untouched: another key says nothing about Space
        }
    }

    @Test fun `a Space coming up with none held passes - its down went to another window or to a field`() {
        val d = space(down = false, held = false)
        assertEquals(Verdict.PASS, d.verdict)
        assertFalse(d.heldAfter)
    }

    /** The stateful edge the window's handler calls: it remembers the hold across events, and a lost window focus clears it. */
    @Test fun `the edge holds Space across events and losing the window's focus forgets a held Space`() {
        DesktopWindow.report(shown = true, minimised = false, focused = true, fullScreen = false)
        fun down() = SpaceKey.onKey(space = true, down = true, modified = false, textFieldFocused = false)
        fun up() = SpaceKey.onKey(space = true, down = false, modified = false, textFieldFocused = false)
        assertEquals(Verdict.TOGGLE, down())
        assertEquals(Verdict.SWALLOW, down())   // the repeat
        assertEquals(Verdict.PASS, SpaceKey.onKey(space = false, down = true, modified = false, textFieldFocused = false))   // Return meanwhile
        assertEquals(Verdict.SWALLOW, down())   // still the same hold
        assertEquals(Verdict.SWALLOW, up())
        assertEquals(Verdict.TOGGLE, down())    // a new press
        // A KeyDown with no KeyUp seen, then the window loses focus, then focus returns: a fresh press, not a stuck repeat.
        DesktopWindow.report(shown = true, minimised = false, focused = false, fullScreen = false)
        DesktopWindow.report(shown = true, minimised = false, focused = true, fullScreen = false)
        assertEquals(Verdict.TOGGLE, down())
    }

    /** The window's "a text field has focus" signal — the explicit report on the field, and the generic text-input session. */
    @Test fun `a focused text field is reported and a focused button is not`() = runComposeUiTest {
        val fieldFR = FocusRequester()
        setContent {
            DesktopTextInputWatch {
                var text by remember { mutableStateOf("") }
                Column {
                    BasicTextField(text, { text = it }, Modifier.focusRequester(fieldFR).reportTextFieldFocus().testTag("field"))
                    Box(Modifier.size(40.dp).clickable {}.testTag("button"))
                }
            }
        }
        waitForIdle()
        assertFalse(DesktopWindow.textFieldFocused, "nothing focused yet")
        onNodeWithTag("field").requestFocus()
        waitForIdle()
        assertTrue(DesktopWindow.textFieldFocused, "the field has focus")
        onNodeWithTag("button").requestFocus()
        waitForIdle()
        assertFalse(DesktopWindow.textFieldFocused, "focus moved to a button")
    }

    /** The generic half alone: a field that does not call reportTextFieldFocus() still counts while it takes input. */
    @Test fun `a text field that was not told to report still counts through its text-input session`() = runComposeUiTest {
        val fieldFR = FocusRequester()
        setContent {
            DesktopTextInputWatch {
                var text by remember { mutableStateOf("") }
                Column {
                    BasicTextField(text, { text = it }, Modifier.focusRequester(fieldFR).testTag("field"))
                    Box(Modifier.size(40.dp).clickable {}.testTag("button"))
                }
            }
        }
        waitForIdle()
        assertFalse(DesktopWindow.textFieldFocused)
        onNodeWithTag("field").requestFocus()
        waitForIdle()
        assertTrue(DesktopWindow.textFieldFocused, "the field opened a text-input session")
        onNodeWithTag("button").performClick()
        onNodeWithTag("button").requestFocus()
        waitForIdle()
        assertFalse(DesktopWindow.textFieldFocused, "the session closed with the focus")
    }
}
