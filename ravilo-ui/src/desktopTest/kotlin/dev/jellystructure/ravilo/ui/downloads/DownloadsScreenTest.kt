package dev.jellystructure.ravilo.ui.downloads

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeLeft
import dev.jellystructure.ravilo.ui.i18n.WithLocale
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * DEB-4071 (DEB-3995 FR-9, FR-10; acceptance 11, 12) — the page as a viewer meets it: a running download at the top, every delete asking first,
 * and Cancel leaving the entry where it was.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class DownloadsScreenTest {
    private val done = DownloadPhase.Downloaded

    private fun rig() = PageRig().apply {
        add("alice", "f1", done, 6_200_000_000L, title = "Alpha Film", watched = true)
        add("alice", "f2", done, 7_900_000_000L, title = "Bravo Film")
        add("alice", "run", DownloadPhase.Running(42), 650_000_000L, title = "Foss", kind = "episode", seriesId = "s", seriesTitle = "Nordvest", season = 1, episode = 5)
    }

    @Test fun `a running download shows in the Downloading block above Films`() = runComposeUiTest {
        val r = rig()
        setContent { WithLocale("en") { DownloadsScreen(r.controller, onBack = {}) } }
        onNodeWithTag(DownloadsTags.DOWNLOADING).assertIsDisplayed()
        onNodeWithText("Downloading").assertIsDisplayed()
        onNodeWithTag(DownloadsTags.row("run")).assertIsDisplayed()
        val blockY = onNodeWithTag(DownloadsTags.DOWNLOADING).fetchSemanticsNode().boundsInRoot.top
        val filmY = onNodeWithTag(DownloadsTags.row("f1")).fetchSemanticsNode().boundsInRoot.top
        assertTrue(blockY < filmY, "the Downloading block ($blockY) sits above the first film ($filmY)")
    }

    @Test fun `the block is gone when nothing is on its way`() = runComposeUiTest {
        val r = PageRig().apply { add("alice", "f1", done, 1_000_000_000L, title = "Alpha Film") }
        setContent { WithLocale("en") { DownloadsScreen(r.controller, onBack = {}) } }
        onNodeWithTag(DownloadsTags.DOWNLOADING).assertDoesNotExist()
    }

    @Test fun `the empty page says how to download`() = runComposeUiTest {
        val r = PageRig()
        setContent { WithLocale("en") { DownloadsScreen(r.controller, onBack = {}) } }
        onNodeWithText("Nothing on this phone yet").assertIsDisplayed()
        onNodeWithTag(DownloadsTags.OVERFLOW).assertDoesNotExist()   // nothing to select or delete
    }

    @Test fun `a swipe asks before deleting, naming the title and what it frees, and Cancel restores the entry`() = runComposeUiTest {
        val r = rig()
        setContent { WithLocale("en") { DownloadsScreen(r.controller, onBack = {}) } }
        onNodeWithTag(DownloadsTags.row("f2")).performTouchInput { swipeLeft() }
        waitForIdle()
        onNodeWithText("Delete Bravo Film? This frees 7.9 GB.").assertIsDisplayed()
        assertTrue(r.engine.removed.isEmpty(), "a swipe deletes nothing until it is confirmed")
        onNodeWithText("Cancel").performClick()
        waitForIdle()
        onNodeWithText("Delete Bravo Film? This frees 7.9 GB.").assertDoesNotExist()
        onNodeWithTag(DownloadsTags.row("f2")).assertIsDisplayed()
        assertTrue(r.engine.removed.isEmpty() && r.controller.records.value.any { it.itemId == "f2" })
    }

    @Test fun `confirming a swipe's delete removes the entry`() = runComposeUiTest {
        val r = rig()
        setContent { WithLocale("en") { DownloadsScreen(r.controller, onBack = {}) } }
        onNodeWithTag(DownloadsTags.row("f2")).performTouchInput { swipeLeft() }
        waitForIdle()
        onAllNodesWithText("Delete").onLast().performClick()   // the sheet is drawn last: its Delete, not the swipe tile's
        waitForIdle()
        assertTrue(downloadId("alice", "f2") in r.engine.removed)
        onNodeWithTag(DownloadsTags.row("f2")).assertDoesNotExist()
    }

    @Test fun `Delete all in the overflow asks with the space it frees and Cancel keeps everything`() = runComposeUiTest {
        val r = rig()
        setContent { WithLocale("en") { DownloadsScreen(r.controller, onBack = {}) } }
        onNodeWithTag(DownloadsTags.OVERFLOW).performClick()
        onNodeWithText("Delete all").performClick()
        waitForIdle()
        onNodeWithText("Delete all downloads?").assertIsDisplayed()
        onNodeWithText("This frees 14.4 GB.").assertIsDisplayed()   // 6.2 + 7.9 + 42 % of 0.65
        onNodeWithText("Cancel").performClick()
        waitForIdle()
        assertTrue(r.engine.removed.isEmpty() && r.controller.records.value.size == 3)
    }
}
