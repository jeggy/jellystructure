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
import dev.jellystructure.shared.tv.DownloadOption
import dev.jellystructure.shared.tv.TvApiError
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test fun `Delete stays hidden at rest for films and episodes including select mode and a resting tap plays`() = runComposeUiTest {
        val r = rig().apply {
            add("alice", "ep", done, 1_000_000_000L, title = "The Crossing", kind = "episode", seriesId = "s", seriesTitle = "Nordvest", season = 1, episode = 4)
        }
        var played: String? = null
        setContent { WithLocale("en") { DownloadsScreen(r.controller, onBack = {}, onPlay = { played = it.itemId }) } }
        onNodeWithTag(DownloadsTags.row("f1")).assertIsDisplayed()
        onNodeWithTag(DownloadsTags.row("ep")).assertIsDisplayed()
        onNodeWithText("Delete").assertDoesNotExist()
        onNodeWithTag(DownloadsTags.row("f1")).performClick()
        assertEquals("f1", played)
        onNodeWithTag(DownloadsTags.OVERFLOW).performClick()
        onNodeWithText("Select").performClick()
        onNodeWithText("Delete").assertDoesNotExist()
        onNodeWithTag(DownloadsTags.row("ep")).assertIsDisplayed()
    }

    @Test fun `after a delete the storage bar reads the engine's freed bytes on the open page`() = runComposeUiTest {
        val r = rig()
        r.engine.freedPerRemove = 7_900_000_000L
        setContent { WithLocale("en") { DownloadsScreen(r.controller, onBack = {}) } }
        onNodeWithText("41.2 GB").assertIsDisplayed()
        onNodeWithText("72.4 GB").assertIsDisplayed()
        onNodeWithTag(DownloadsTags.row("f2")).performTouchInput { swipeLeft() }
        waitForIdle()
        onAllNodesWithText("Delete").onLast().performClick()
        waitForIdle()
        onNodeWithText("49.1 GB").assertIsDisplayed()
        onNodeWithText("72.4 GB").assertIsDisplayed()
    }

    @Test fun `saved wide copy shows its encoder rung`() = runComposeUiTest {
        val r = PageRig().apply { add("alice", "f1", done, 1_000_000_000L, title = "Alpha Film", quality = "high", height = 1036) }
        setContent { WithLocale("en") { DownloadsScreen(r.controller, onBack = {}) } }
        onNodeWithText("1080p").assertIsDisplayed()
        onNodeWithText("1036p").assertDoesNotExist()
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

    // ─── DEB-4258 (DEB-4256 FR-7; acceptance 20, 21, 23): the waiting line and the original offer ───

    /** A copy of 720p that the server ended for room: one record, the engine's `WaitingServer`, and an original of 4 GB listed for this phone. */
    private fun waitingRig(body: String? = null, retryMs: Long = 100L, listed: Boolean = true) = PageRig(waitingRetryMs = retryMs).apply {
        if (listed) api.original = DownloadOption("original", 2160, 4_000_000_000L, exact = true)
        body?.let { api.startError = TvApiError.Http(503, it, retryAfterSeconds = null) }
        add("alice", "w", DownloadPhase.WaitingServer, 1_000_000_000L, title = "Waiting Film", quality = "medium", height = 720)
    }

    @Test fun `a waiting copy reads its reason on its row and offers the original under it`() = runComposeUiTest {
        val r = waitingRig("""{"reason":"encoder_full","streams":2}""")
        setContent { WithLocale("en") { DownloadsScreen(r.controller, onBack = {}) } }
        waitUntil(timeoutMillis = 3_000) { r.controller.waits.value["w"]?.reason is dev.jellystructure.shared.tv.DownloadWaitReason.EncoderFull }
        onNodeWithText("The server is busy with 2 other titles — this starts on its own when one is done").assertIsDisplayed()
        onNodeWithTag(DownloadsTags.getOriginal("w")).assertIsDisplayed()
        onNodeWithText("Download the original instead · 4.0 GB").assertIsDisplayed()
    }

    @Test fun `one tap on the offer makes the row an original`() = runComposeUiTest {
        val r = waitingRig("""{"reason":"unavailable"}""")
        setContent { WithLocale("en") { DownloadsScreen(r.controller, onBack = {}) } }
        waitUntil(timeoutMillis = 3_000) { r.controller.originalOffers.value.containsKey("w") }
        onNodeWithTag(DownloadsTags.getOriginal("w")).performClick()
        waitForIdle()
        assertEquals("original", r.library.get("alice", "w")?.quality)
        assertEquals(listOf(downloadId("alice", "w")), r.engine.removed)
        onNodeWithTag(DownloadsTags.getOriginal("w")).assertDoesNotExist()
    }

    @Test fun `a copy with no listed original offers nothing`() = runComposeUiTest {
        val r = waitingRig("""{"reason":"unavailable"}""", listed = false)
        setContent { WithLocale("en") { DownloadsScreen(r.controller, onBack = {}) } }
        waitUntil(timeoutMillis = 3_000) { r.controller.waits.value["w"]?.reason == dev.jellystructure.shared.tv.DownloadWaitReason.Unavailable }
        onNodeWithTag(DownloadsTags.getOriginal("w")).assertDoesNotExist()
    }

    @Test fun `a paused copy offers nothing`() = runComposeUiTest {
        val r = waitingRig("""{"reason":"unavailable"}""")
        setContent { WithLocale("en") { DownloadsScreen(r.controller, onBack = {}) } }
        waitUntil(timeoutMillis = 3_000) { r.controller.originalOffers.value.containsKey("w") }
        r.engine.set(downloadId("alice", "w"), DownloadPhase.Paused(0))
        waitForIdle()
        onNodeWithTag(DownloadsTags.getOriginal("w")).assertDoesNotExist()
    }

    @Test fun `in Danish and Faroese at 360 dp each waiting line shows whole under the title's row, inside the width`() {
        val reasons = listOf(
            dev.jellystructure.shared.tv.DownloadWaitReason.EncoderFull(1), dev.jellystructure.shared.tv.DownloadWaitReason.EncoderFull(12),
            dev.jellystructure.shared.tv.DownloadWaitReason.EncoderFull(null), dev.jellystructure.shared.tv.DownloadWaitReason.Unavailable,
        )
        for (lang in listOf("da", "fo")) for (reason in reasons) runComposeUiTest {
            var expected = ""
            setContent {
                WithLocale(lang) {
                    val line = waitLine(WaitInfo(reason)); expected = dev.jellystructure.ravilo.ui.i18n.str(line.key, line.args)
                    DownloadStatusLine(DownloadPhase.WaitingServer, Modifier.width(360.dp).testTag("line"), WaitInfo(reason))
                }
            }
            assertTrue(expected.isNotBlank() && !expected.contains("{"), "$lang $reason resolved to '$expected'")
            onNodeWithText(expected).assertIsDisplayed()   // the whole sentence is one node: nothing cut, nothing ellipsised
            val b = onNodeWithTag("line").fetchSemanticsNode().boundsInRoot
            assertTrue(b.width <= density.run { 360.dp.toPx() } + 1f, "$lang $reason is ${b.width} px wide")
        }
    }
}
