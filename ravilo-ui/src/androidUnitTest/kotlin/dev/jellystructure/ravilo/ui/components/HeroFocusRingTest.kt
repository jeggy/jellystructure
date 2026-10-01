package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import dev.jellystructure.shared.tv.Hero
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MediaKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** R350 (FR-R350-8) — Home's hero shows that it is focused (it had no focused state at all), and stops when it isn't. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
@OptIn(ExperimentalTestApi::class)
class HeroFocusRingTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `the focused hero draws its ring, and Down to the row below takes it away`() {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val hero = Hero(
            item = MediaCard(id = "h", kind = MediaKind.MOVIE, title = "Stand-in", year = 2020, genre = null, rating = null,
                posterUrl = null, backdropUrl = null),
            taglineKicker = null, backdropUrl = "", logoUrl = null, badge = null,
        )
        rule.setContent {
            val heroFR = remember { FocusRequester() }
            val belowFR = remember { FocusRequester() }
            Column {
                HeroCarousel(items = listOf(hero), focusRequester = heroFR, heightDp = 300.dp, autoAdvanceSeconds = 0,
                    onDown = { belowFR.requestFocus() })
                Box(Modifier.size(100.dp).testTag("below").focusRequester(belowFR).focusable())
            }
            LaunchedEffect(Unit) { heroFR.requestFocus() }   // what Home does on arrival
        }
        rule.waitForIdle()
        rule.onNodeWithTag(HERO_FOCUS_RING_TAG, useUnmergedTree = true).assertExists()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithTag("below").assertIsFocused()
        rule.onNodeWithTag(HERO_FOCUS_RING_TAG, useUnmergedTree = true).assertDoesNotExist()
    }
}
