package dev.jellystructure.ravilo.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.jellystructure.ravilo.ui.screens.SKIP_INTRO_RING_TAG
import dev.jellystructure.ravilo.ui.screens.SkipIntroPill
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R363 (FR-R363-5) — the Skip Intro pill draws its countdown ring only where it counts down to something (Auto); in
 * Prompt mode it is *Skip Intro* with its *OK* key cap. Renders the pill alone (no `PlayerScreen` harness).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class SkipIntroPillTest {
    @get:Rule val rule = createComposeRule()

    private fun render(showRing: Boolean) {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        rule.setContent {
            SkipIntroPill(colors = RaviloTheme.colors, countdown = 4, totalSecs = 6, focused = true, showRing = showRing)
        }
        rule.waitForIdle()
    }

    @Test fun `Prompt — no ring, the OK key cap is there`() {
        render(showRing = false)
        rule.onNodeWithTag(SKIP_INTRO_RING_TAG, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("OK", useUnmergedTree = true).assertExists()
    }

    @Test fun `Auto — the ring is drawn`() {
        render(showRing = true)
        rule.onNodeWithTag(SKIP_INTRO_RING_TAG, useUnmergedTree = true).assertExists()
    }
}
