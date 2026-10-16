package dev.jellystructure.ravilo.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.jellystructure.ravilo.ui.i18n.WithLocale
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R386 (FR-R386-6/-7/-8) — what the Software update block and the ready notice say and offer in each state, drawn in
 * both families. The states are set as values on [SoftwareUpdate]: the screens draw nothing of their own, so a state that
 * reads right here reads right on the Mac.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class SoftwareUpdateScreensTest {
    @get:Rule val rule = createComposeRule()

    private val at = CheckedAt(today = true, date = "16 Oct 2026", time = "09:14")
    private val ready = UpdateBlockState.Ready("1.53", "https://example.test/notes")
    private val calls = mutableListOf<String>()

    @Before fun arm() {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        SoftwareUpdate.resetForTest()
        SoftwareUpdate.actions = SoftwareUpdateActions(
            checkNow = { calls += "check" }, restart = { calls += "restart" }, download = { calls += "download" },
            tryAgain = { calls += "again" }, setAutoDownload = { calls += "auto=$it" }, quit = { calls += "quit" },
        )
    }

    @After fun disarm() { SoftwareUpdate.resetForTest(); SoftwareUpdate.actions = SoftwareUpdateActions() }

    private fun show(block: UpdateBlockState, auto: Boolean = true, desk: Boolean = true, lang: String = "en", content: @Composable () -> Unit = { SoftwareUpdateBlock(desk) }) {
        SoftwareUpdate.set(SoftwareUpdateModel(block, "1.52", auto))
        rule.setContent { WithLocale(lang, content) }
        rule.waitForIdle()
    }

    private fun text(t: String) = rule.onNodeWithText(t, useUnmergedTree = true)

    @Test fun `state 1 — up to date, with the version, when it was checked, and Check now`() {
        show(UpdateBlockState.UpToDate(at))
        text("Software update").assertExists()
        text("Ravilo 1.52 · Up to date").assertExists()
        text("Checked today at 09:14").assertExists()
        text("Check now").performClick()
        assertEquals(listOf("check"), calls)
    }

    @Test fun `state 1 — a check from an earlier day says which day`() {
        show(UpdateBlockState.UpToDate(CheckedAt(today = false, date = "15 Oct 2026", time = "21:40")))
        text("Checked 15 Oct 2026 at 21:40").assertExists()
    }

    @Test fun `state 2 — checking has no button`() {
        show(UpdateBlockState.Checking)
        text("Checking for updates…").assertExists()
        text("Check now").assertDoesNotExist()
    }

    @Test fun `state 3 — downloading says so without figures when Sparkle gives none`() {
        show(UpdateBlockState.Downloading("1.53", -1, -1))
        text("Ravilo 1.53 is downloading").assertExists()
        text("It downloads in the background. Ravilo asks before it restarts.").assertExists()
        assertEquals(0, rule.onAllNodesWithText("MB", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun `state 3 — with figures from Sparkle, they are shown`() {
        show(UpdateBlockState.Downloading("1.53", 41_000_000, 94_000_000))
        text("41 of 94 MB").assertExists()
    }

    @Test fun `state 3 — the offer form, toggle off, hands over to Sparkle through Download`() {
        show(UpdateBlockState.Offer("1.53"), auto = false)
        text("Ravilo 1.53 for Mac is available").assertExists()
        text("Download").performClick()
        assertEquals(listOf("download"), calls)
    }

    @Test fun `state 4 — ready offers Restart to update and What's new, and the restart is asked for`() {
        show(ready)
        text("Ravilo 1.53 is ready").assertExists()
        text("Restart Ravilo to finish. It takes a few seconds.").assertExists()
        text("What’s new ›").assertExists()
        text("Restart to update").performClick()
        assertEquals(listOf("restart"), calls)
    }

    @Test fun `state 5 — while something plays the button is gone and nothing can restart`() {
        SoftwareUpdate.setPlaying(true)
        show(ready)
        text("Ravilo 1.53 is ready").assertExists()
        text("Something is playing, so the update waits. Restart to update appears here when it stops.").assertExists()
        rule.onNodeWithTag(UPDATE_BUTTON_TAG_PREFIX + "Restart to update", useUnmergedTree = true).assertDoesNotExist()
        SoftwareUpdate.restart()
        assertTrue(calls.isEmpty(), "Restart is withheld while anything plays: $calls")
    }

    @Test fun `state 5 ends when playback does — the button comes back`() {
        SoftwareUpdate.setPlaying(true)
        show(ready)
        rule.runOnIdle { SoftwareUpdate.setPlaying(false) }
        rule.waitForIdle()
        text("Restart to update").assertExists()
    }

    @Test fun `state 6 — held back names both releases and offers nothing`() {
        show(UpdateBlockState.HeldBack("1.53", "1.52"))
        text("Ravilo 1.53 is out").assertExists()
        text("Your server runs 1.52, so this Mac waits for the server. It updates once the server has 1.53.").assertExists()
        text("Check now").assertDoesNotExist()
    }

    @Test fun `state 7 — a download that failed and an update that could not be confirmed, each with Try again`() {
        show(UpdateBlockState.Failed(signature = false))
        text("The update didn’t download").assertExists()
        text("Try again").performClick()
        assertEquals(listOf("again"), calls)
    }

    @Test fun `state 7 — the signature one says why in its own words`() {
        show(UpdateBlockState.Failed(signature = true))
        text("The update couldn’t be confirmed").assertExists()
        text("Ravilo installs only an update it can confirm is genuine. Try again to download it fresh.").assertExists()
        text("Try again").assertExists()
    }

    @Test fun `state 8 — from a temporary copy, R385's strings, Quit only and no toggle`() {
        show(UpdateBlockState.CannotUpdate(fromDiskImage = false))
        text("Move Ravilo to your Applications folder").assertExists()
        text("Ravilo is running from a temporary copy, which can't reach your home network. Quit Ravilo, drag it to your Applications folder, and open it from there.").assertExists()
        text("Download updates automatically").assertDoesNotExist()
        text("Check now").assertDoesNotExist()
        text("Quit Ravilo").performClick()
        assertEquals(listOf("quit"), calls)
    }

    @Test fun `state 8 — from the disk image has its own sentence`() {
        show(UpdateBlockState.CannotUpdate(fromDiskImage = true))
        text("Ravilo is running from the disk image, which can't reach your home network. Quit Ravilo, drag it to your Applications folder, and open it from there.").assertExists()
    }

    @Test fun `state 9 — the toggle flips through the engine and says what each position means`() {
        show(UpdateBlockState.UpToDate(at), auto = true)
        text("Download updates automatically").assertExists()
        text("Ravilo still asks before it restarts.").assertExists()
        rule.onNodeWithTag(UPDATE_TOGGLE_TAG, useUnmergedTree = true).performClick()
        assertEquals(listOf("auto=false"), calls)
    }

    @Test fun `state 9 — off says Ravilo downloads when asked`() {
        show(UpdateBlockState.UpToDate(at), auto = false)
        text("Ravilo tells you when an update is out and downloads it when you say so.").assertExists()
    }

    @Test fun `state 12 — offline keeps the last check's time and has no Check now`() {
        show(UpdateBlockState.Offline(at))
        text("Can’t check while offline").assertExists()
        text("Last checked today at 09:14. Ravilo checks again when this Mac is back online.").assertExists()
        text("Check now").assertDoesNotExist()
    }

    @Test fun `state 12 — offline before any check reached the server leaves the time out`() {
        show(UpdateBlockState.Offline(null))
        text("Ravilo checks again when this Mac is back online.").assertExists()
    }

    @Test fun `the handover line is Ravilo's last and has no toggle`() {
        show(UpdateBlockState.Handover("1.53"))
        text("Closing Ravilo to install 1.53…").assertExists()
        text("macOS takes it from here and opens Ravilo again.").assertExists()
        text("Download updates automatically").assertDoesNotExist()
    }

    @Test fun `state 10 — a development build has no block at all, and no notice or dot`() {
        SoftwareUpdate.set(null)
        rule.setContent { WithLocale("en") { SoftwareUpdateBlock(true); UpdateNoticeRow(true); if (updateDotShows()) UpdateDot() } }
        rule.waitForIdle()
        rule.onNodeWithTag(UPDATE_BLOCK_TAG, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(UPDATE_NOTICE_TAG, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(UPDATE_DOT_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun `the same states draw in the phone family`() {
        show(ready, desk = false)
        text("Ravilo 1.53 is ready").assertExists()
        text("Restart to update").performClick()
        assertEquals(listOf("restart"), calls)
    }

    @Test fun `no state leaves a key on screen in any language, in either family`() {
        val states = listOf(
            UpdateBlockState.UpToDate(at), UpdateBlockState.UpToDate(null), UpdateBlockState.Checking, UpdateBlockState.Downloading("1.53", -1, -1),
            UpdateBlockState.Downloading("1.53", 1_000_000, 2_000_000), UpdateBlockState.Offer("1.53"), ready, UpdateBlockState.HeldBack("1.53", "1.52"),
            UpdateBlockState.Failed(false), UpdateBlockState.Failed(true), UpdateBlockState.CannotUpdate(false), UpdateBlockState.CannotUpdate(true),
            UpdateBlockState.Offline(at), UpdateBlockState.Offline(null), UpdateBlockState.Offline(CheckedAt(false, "15 Oct", "21:40")), UpdateBlockState.Handover("1.53"),
        )
        // One composition for the whole sweep (a test rule takes one setContent); the language and the family are state.
        var lang by androidx.compose.runtime.mutableStateOf("en")
        var desk by androidx.compose.runtime.mutableStateOf(true)
        rule.setContent { WithLocale(lang) { SoftwareUpdateBlock(desk); UpdateNoticeRow(desk) } }
        for (l in listOf("en", "da", "fo")) for (d in listOf(true, false)) for (playing in listOf(false, true)) {
            for (block in states) {
                rule.runOnIdle { lang = l; desk = d; SoftwareUpdate.setPlaying(playing); SoftwareUpdate.set(SoftwareUpdateModel(block, "1.52", true)) }
                rule.waitForIdle()
                assertEquals(0, rule.onAllNodesWithText("mac.", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size, "$l/$d/$block")
                assertEquals(0, rule.onAllNodesWithText("{", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size, "$l/$d/$block left a {placeholder}")
            }
        }
    }

    // ── the notice (FR-R386-8) ──

    @Test fun `the notice row is Update ready, Restart and Hide, and Restart does what the block's button does`() {
        show(ready, content = { UpdateNoticeRow(desk = true) })
        text("Update ready").assertExists()
        rule.onNodeWithTag(UPDATE_NOTICE_RESTART_TAG, useUnmergedTree = true).performClick()
        assertEquals(listOf("restart"), calls)
    }

    @Test fun `the notice while something plays — Restart is the inert After playback`() {
        SoftwareUpdate.setPlaying(true)
        show(ready, content = { UpdateNoticeRow(desk = true) })
        text("After playback").assertExists()
        rule.onNodeWithTag(UPDATE_NOTICE_RESTART_TAG, useUnmergedTree = true).assertDoesNotExist()
        text("After playback").performClick()
        assertTrue(calls.isEmpty(), "After playback does nothing: $calls")
    }

    @Test fun `Hide removes the row, and the dot stays until the update is installed`() {
        show(ready, content = { UpdateNoticeRow(desk = true); if (updateDotShows()) UpdateDot() })
        rule.onNodeWithTag(UPDATE_NOTICE_HIDE_TAG, useUnmergedTree = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(UPDATE_NOTICE_TAG, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(UPDATE_DOT_TAG, useUnmergedTree = true).assertExists()
    }

    @Test fun `no dot and no row in any state but Ready`() {
        SoftwareUpdate.set(SoftwareUpdateModel(UpdateBlockState.Checking, "1.52", true))
        rule.setContent { WithLocale("en") { UpdateNoticeRow(true); if (updateDotShows()) UpdateDot() } }
        for (block in listOf(UpdateBlockState.UpToDate(at), UpdateBlockState.Checking, UpdateBlockState.Downloading("1.53", -1, -1), UpdateBlockState.Offer("1.53"),
            UpdateBlockState.HeldBack("1.53", "1.52"), UpdateBlockState.Failed(false), UpdateBlockState.CannotUpdate(false), UpdateBlockState.Offline(at), UpdateBlockState.Handover("1.53"))) {
            rule.runOnIdle { SoftwareUpdate.set(SoftwareUpdateModel(block, "1.52", true)) }
            rule.waitForIdle()
            rule.onNodeWithTag(UPDATE_NOTICE_TAG, useUnmergedTree = true).assertDoesNotExist()
            rule.onNodeWithTag(UPDATE_DOT_TAG, useUnmergedTree = true).assertDoesNotExist()
        }
    }
}
