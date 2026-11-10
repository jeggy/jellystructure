package dev.jellystructure.ravilo.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import dev.jellystructure.ravilo.ui.remote.VolumeSliderRow
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/**
 * DEB-2185 (FR-10, FR-11; `one-remote-a-vol-pending-film.png`, `-a-vol-reverted-film.png`) — the one slider row as
 * drawn: a dash and the reason for a place that reports nothing, the reported level and its percent, the viewer's own
 * level held after release while the place still says the old one, and *{place} kept {n} %* once the 3 s ran out.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h900dp-mdpi")
class VolumeSliderRowTest {
    @get:Rule val rule = createComposeRule()

    private var reported by mutableStateOf<Int?>(null)
    private var now = 5_000_000L
    private val sent = mutableListOf<Int>()

    private fun draw() {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        rule.mainClock.autoAdvance = false
        rule.setContent {
            RaviloTheme {
                VolumeSliderRow(label = "Volume", placeName = "Stue TV", reported = reported, enabled = true, send = { sent += it }, tag = "vol", clock = { now })
            }
        }
        settle(16)
    }

    /** A write from the test thread reaches the recomposer only after apply notifications; then the frames run. */
    private fun settle(ms: Long) { rule.waitForIdle(); rule.mainClock.advanceTimeBy(ms); rule.waitForIdle() }

    @Test fun `a place that reports nothing shows a dash and says why - never 0`() {
        draw()
        rule.onNodeWithTag("vol-none").assertIsDisplayed()
        rule.onNodeWithText("Stue TV doesn't report its volume").assertIsDisplayed()
        rule.onNodeWithTag("vol").assertDoesNotExist()
    }

    @Test fun `it opens at the reported level`() {
        reported = 37
        draw()
        rule.onNodeWithTag("vol").assertIsDisplayed()
        rule.onNodeWithTag("vol-level").assertTextEquals("37 %")
        rule.onNodeWithTag("vol-no-report").assertDoesNotExist()
    }

    @Test fun `a request is held against the old level and returns with the kept line after 3 s`() {
        reported = 2
        draw()
        // The viewer sets 15: one send, and the thumb holds 15 while the place still reports 2.
        rule.onNodeWithTag("vol").performSemanticsAction(SemanticsActions.SetProgress) { it(15f) }
        settle(16)
        assertEquals(listOf(15, 15), sent, "the drag's own send and the release's")
        reported = 2
        settle(500)
        rule.onNodeWithTag("vol-level").assertTextEquals("15 %")
        rule.onNodeWithTag("vol-kept").assertDoesNotExist()
        // 3 s with no matching report: back at 2 %, and the line names what the place kept.
        now += 3_100
        settle(300)
        rule.onNodeWithTag("vol-level").assertTextEquals("2 %")
        rule.onNodeWithText("Stue TV kept 2 %").assertIsDisplayed()
        // 3 s later the line is gone.
        now += 3_100
        settle(300)
        rule.onNodeWithTag("vol-kept").assertDoesNotExist()
        rule.onNodeWithTag("vol-level").assertTextEquals("2 %")
    }

    @Test fun `a matching report settles the request and a later change from elsewhere is live`() {
        reported = 2
        draw()
        rule.onNodeWithTag("vol").performSemanticsAction(SemanticsActions.SetProgress) { it(15f) }
        settle(16)
        rule.onNodeWithTag("vol-level").assertTextEquals("15 %")
        reported = 14
        settle(200)
        rule.onNodeWithTag("vol-level").assertTextEquals("14 %")
        reported = 60
        settle(200)
        rule.onNodeWithTag("vol-level").assertTextEquals("60 %")   // the TV's own remote moved it: drawn live, not held against the old request
        rule.onNodeWithTag("vol-kept").assertDoesNotExist()
    }

    @Test fun `a disabled row cannot be dragged`() {
        reported = 40
        rule.setContent { RaviloTheme { VolumeSliderRow(label = "Volume", placeName = "Stue TV", reported = reported, enabled = false, send = { sent += it }, tag = "vol") } }
        rule.onNodeWithTag("vol").assertIsNotEnabled()
    }
}
