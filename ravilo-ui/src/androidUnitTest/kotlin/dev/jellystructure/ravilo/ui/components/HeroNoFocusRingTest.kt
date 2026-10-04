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
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
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

/**
 * R374 — the Home hero draws no focus border (reverses R350 FR-R350-8's hero ring, owner 2026-10-04). It is still
 * the focus target on arrival (R53), Down still leaves it, Right still pages it. The tag is the literal the ring
 * used to carry, since its constant is gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
@OptIn(ExperimentalTestApi::class)
class HeroNoFocusRingTest {
    @get:Rule val rule = createComposeRule()

    private val oldRingTag = "hero-focus-ring"

    private fun hero(id: String, title: String) = Hero(
        item = MediaCard(id = id, kind = MediaKind.MOVIE, title = title, year = 2020, genre = null, rating = null,
            posterUrl = null, backdropUrl = null),
        taglineKicker = null, backdropUrl = "", logoUrl = null, badge = null,
    )

    private fun render(items: List<Hero>) {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        rule.setContent {
            val heroFR = remember { FocusRequester() }
            val belowFR = remember { FocusRequester() }
            Column {
                HeroCarousel(items = items, focusRequester = heroFR, heightDp = 300.dp, autoAdvanceSeconds = 0,
                    onDown = { belowFR.requestFocus() })
                Box(Modifier.size(100.dp).testTag("below").focusRequester(belowFR).focusable())
            }
            LaunchedEffect(Unit) { heroFR.requestFocus() }   // what Home does on arrival
        }
        rule.waitForIdle()
    }

    @Test fun `the hero takes focus on arrival with no ring, and Down leaves it`() {
        render(listOf(hero("h", "Stand-in Harbour")))
        rule.onNode(isFocused() and hasAnyDescendant(hasText("Stand-in Harbour"))).assertExists()
        rule.onNodeWithTag(oldRingTag, useUnmergedTree = true).assertDoesNotExist()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithTag("below").assertIsFocused()
        rule.onNodeWithTag(oldRingTag, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun `Right pages to the second slide, the hero keeps focus and still draws no ring`() {
        render(listOf(hero("a", "Stand-in Lighthouse"), hero("b", "Stand-in Ferry")))
        rule.onNode(isFocused() and hasAnyDescendant(hasText("Stand-in Lighthouse"))).assertExists()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        rule.onNode(isFocused() and hasAnyDescendant(hasText("Stand-in Ferry"))).assertExists()
        rule.onNodeWithTag(oldRingTag, useUnmergedTree = true).assertDoesNotExist()
    }
}
