package dev.jellystructure.ravilo.ui.music

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.shared.tv.MusicAlbumDetail
import dev.jellystructure.shared.tv.MusicArtistDetail
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals

/** DEB-4091 FR-3 — a failed album or artist load says so and offers Retry; the empty-library line is for an answer of nothing. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class MusicDetailFailedTest {
    @Test fun `a failed album load shows the error state with Retry, not the no-music line`() = runComposeUiTest {
        val gate = CompletableDeferred<MusicAlbumDetail>()
        var calls = 0
        val loader = MusicLoader<MusicAlbumDetail> { if (++calls == 1) error("down") else gate.await() }
        var empty = ""; var retry = ""
        setContent {
            empty = str("mhome.empty"); retry = str("action.retry")
            MusicAlbumScreen(loader, onBack = {}, onOpenArtist = {}, onOpenAlbum = {}, onTrackMore = {})
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("music-load-failed").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText(empty).assertCountEquals(0)
        onNodeWithText(retry).performClick()
        waitUntil(timeoutMillis = 5_000) { calls == 2 }
        // Retry puts the page back to its skeleton while it loads again.
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("music-load-failed").fetchSemanticsNodes().isEmpty() }
        assertEquals(2, calls)
    }

    @Test fun `a failed artist load shows the error state with Retry, not the no-music line`() = runComposeUiTest {
        val loader = MusicLoader<MusicArtistDetail> { error("down") }
        var empty = ""
        setContent {
            empty = str("mhome.empty")
            MusicArtistScreen(loader, onBack = {}, onOpenAlbum = {}, onPlayVideo = {}, onTrackMore = {}, playAll = PlayAll(fetch = { _, _ -> error("not called") }))
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("music-load-failed").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText(empty).assertCountEquals(0)
    }
}
