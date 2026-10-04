package dev.jellystructure.ravilo.ui.focus

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R361 (dev review item 3) — the shipped retry helpers actually retry. In Compose 1.9 `requestFocus()` on an
 * unattached requester returns `false` instead of throwing, so the old `runCatching { … }.isSuccess` test stopped
 * after the first, failed try and the R200 retry / R236 native fallback never ran.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class FocusRetryTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `requestFocusRetrying focuses a box that composes only after three frames`() {
        rule.mainClock.autoAdvance = false
        val fr = FocusRequester()
        lateinit var scope: CoroutineScope
        var frames by mutableIntStateOf(0)
        rule.setContent {
            scope = rememberCoroutineScope()
            LaunchedEffect(Unit) { repeat(3) { withFrameNanos { }; frames++ } }
            if (frames >= 3) Box(Modifier.size(40.dp).testTag("late").focusRequester(fr).focusable())
        }
        rule.runOnIdle { requestFocusRetrying(scope, fr) }
        repeat(8) { rule.mainClock.advanceTimeByFrame() }
        rule.onNodeWithTag("late").assertIsFocused()
    }

    @Test fun `requestFocusRetryingOrMoveNative falls back to the native move when the target never attaches`() {
        rule.mainClock.autoAdvance = false
        val never = FocusRequester()
        val aboveFR = FocusRequester()
        lateinit var scope: CoroutineScope
        lateinit var move: () -> Unit
        rule.setContent {
            scope = rememberCoroutineScope()
            val fm = LocalFocusManager.current
            move = { requestFocusRetryingOrMoveNative(scope, never, fm, FocusDirection.Down) }
            Column {
                Box(Modifier.size(40.dp).testTag("above").focusRequester(aboveFR).focusable())
                Box(Modifier.size(40.dp).testTag("below").focusable())
            }
        }
        rule.runOnIdle { aboveFR.requestFocus() }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("above").assertIsFocused()
        rule.runOnIdle { move() }
        repeat(5) { rule.mainClock.advanceTimeByFrame() }
        rule.onNodeWithTag("below").assertIsNotFocused()   // still retrying
        repeat(30) { rule.mainClock.advanceTimeByFrame() }
        rule.onNodeWithTag("below").assertIsFocused()
    }

    @Test fun `an attached requester is focused on the call with no frame advanced`() {
        rule.mainClock.autoAdvance = false
        val fr = FocusRequester()
        lateinit var scope: CoroutineScope
        rule.setContent {
            scope = rememberCoroutineScope()
            Box(Modifier.size(40.dp).testTag("now").focusRequester(fr).focusable())
        }
        rule.mainClock.advanceTimeByFrame()
        rule.runOnIdle { requestFocusRetrying(scope, fr) }
        rule.onNodeWithTag("now").assertIsFocused()
    }

    @Test fun `tryRequestFocus says false for an unattached requester and true for an attached one`() {
        val attached = FocusRequester()
        rule.setContent { Box(Modifier.size(40.dp).focusRequester(attached).focusable()) }
        rule.runOnIdle {
            assert(!FocusRequester().tryRequestFocus()) { "an unattached requester must report a miss" }
            assert(attached.tryRequestFocus()) { "an attached requester must report focus" }
        }
    }

    @Test fun `R362 — scrollThenFocus brings an off-screen item in and focuses it`() {
        val fr = FocusRequester()
        rule.setContent {
            val state = rememberLazyListState()
            LazyRow(state = state, modifier = Modifier.size(300.dp, 60.dp)) {
                items(30) { i ->
                    Box(Modifier.size(80.dp).testTag("item$i").then(if (i == 25) Modifier.focusRequester(fr) else Modifier).focusable())
                }
            }
            LaunchedEffect(Unit) { scrollThenFocus(state, 25, fr) {} }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("item25").assertIsFocused().assertIsDisplayed()
    }

    @Test fun `R362 — scrollThenFocus on a target that never composes runs the fallback, so the key is never dead`() {
        val never = FocusRequester()
        val otherFR = FocusRequester()
        var fellBack = false
        rule.setContent {
            val state = rememberLazyListState()
            Column {
                LazyRow(state = state, modifier = Modifier.size(300.dp, 60.dp)) {
                    items(5) { i -> Box(Modifier.size(80.dp).focusable()) }
                }
                Box(Modifier.size(40.dp).testTag("other").focusRequester(otherFR).focusable())
            }
            LaunchedEffect(Unit) { scrollThenFocus(state, 3, never) { fellBack = true; otherFR.requestFocus() } }
        }
        rule.waitForIdle()
        assert(fellBack) { "the fallback ran" }
        rule.onNodeWithTag("other").assertIsFocused()
    }

    @Test fun `requestFocusAwaiting reaches an item scrolled in from off screen`() {
        val fr = FocusRequester()
        var done = false
        rule.setContent {
            val state = rememberLazyListState()
            LazyRow(state = state, modifier = Modifier.size(300.dp, 60.dp)) {
                items(30) { i ->
                    Box(Modifier.size(80.dp).testTag("item$i").then(if (i == 25) Modifier.focusRequester(fr) else Modifier).focusable())
                }
            }
            LaunchedEffect(Unit) {
                state.scrollToItem(25)
                done = fr.requestFocusAwaiting()
            }
        }
        rule.waitForIdle()
        assert(done) { "the scrolled-in item takes focus" }
        rule.onNodeWithTag("item25").assertIsFocused()
    }
}
