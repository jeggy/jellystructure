package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R365 (FR-R365-6) — a collection's Back-to-top focuses the hero if the page has one, else the app bar. The shipped
 * `runCatching { heroFR.requestFocus() }.onFailure { … }` never reached the bar: in Compose 1.9 an unattached requester
 * returns false rather than throwing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class ChannelBackToTopTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `no hero — the bar is focused`() {
        val hero = FocusRequester()
        val bar = FocusRequester()
        rule.setContent {
            Column {
                Box(Modifier.size(40.dp).testTag("bar").focusRequester(bar).focusable())
                Box(Modifier.size(40.dp).testTag("tile").focusable())
            }
            LaunchedEffect(Unit) { focusHeroElseBar(hero, bar) }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("bar").assertIsFocused()
    }

    @Test fun `a hero composed after two frames — the hero is focused`() {
        val hero = FocusRequester()
        val bar = FocusRequester()
        var frames by mutableIntStateOf(0)
        rule.setContent {
            Column {
                Box(Modifier.size(40.dp).testTag("bar").focusRequester(bar).focusable())
                if (frames >= 2) Box(Modifier.size(40.dp).testTag("hero").focusRequester(hero).focusable())
            }
            LaunchedEffect(Unit) { repeat(2) { withFrameNanos { }; frames++ } }
            LaunchedEffect(Unit) { focusHeroElseBar(hero, bar) }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("hero").assertIsFocused()
    }
}
