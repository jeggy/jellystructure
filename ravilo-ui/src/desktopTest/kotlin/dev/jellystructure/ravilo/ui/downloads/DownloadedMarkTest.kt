package dev.jellystructure.ravilo.ui.downloads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.jellystructure.ravilo.ui.components.Tile
import dev.jellystructure.ravilo.ui.i18n.WithLocale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * DEB-4071 (DEB-3995 FR-8, amended; acceptance 10) — the white chip shows **only** on a title fully on this phone, and a poster for a title
 * that is not downloaded draws exactly what it drew before: no node, no space.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class DownloadedMarkTest {
    @Test fun `a downloaded poster carries the mark and a poster that is not carries nothing new`() = runComposeUiTest {
        setContent {
            WithLocale("en") {
                Row {
                    Tile(title = "Has", posterUrl = null, downloaded = true)
                    Tile(title = "Not", posterUrl = null)
                }
            }
        }
        onAllNodesWithContentDescription("Downloaded").assertCountEquals(1)
    }

    @Test fun `isDownloaded asks the downloaded set, which is empty wherever there is no download surface`() = runComposeUiTest {
        val seen = mutableListOf<Boolean>()
        setContent {
            seen += isDownloaded("a")          // the default: no download surface
            CompositionLocalProvider(LocalDownloadedIds provides setOf("a")) { seen += isDownloaded("a"); seen += isDownloaded("b"); seen += isDownloaded(null) }
        }
        assertEquals(listOf(false, true, false, false), seen)
    }

    @Test fun `the chip's size and inset are the round's on a poster and on an episode thumb`() = runComposeUiTest {
        setContent {
            WithLocale("en") {
                Box(Modifier.size(200.dp)) { DownloadedMark(MarkSize.POSTER) }
            }
        }
        val node = onNodeWithContentDescription("Downloaded").fetchSemanticsNode()
        assertEquals(with(density) { 20.dp.roundToPx() }, node.size.width)
        assertEquals(20.dp, MarkSize.POSTER.box); assertEquals(7.dp, MarkSize.POSTER.radius); assertEquals(7.dp, MarkSize.POSTER.inset)
        assertEquals(18.dp, MarkSize.EPISODE.box); assertEquals(6.dp, MarkSize.EPISODE.radius); assertEquals(5.dp, MarkSize.EPISODE.inset)
    }
}
