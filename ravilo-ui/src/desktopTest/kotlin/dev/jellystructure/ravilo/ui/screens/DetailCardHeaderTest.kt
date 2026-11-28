package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.jellystructure.ravilo.ui.components.DETAIL_SKELETON_HERO
import dev.jellystructure.ravilo.ui.components.DetailLoadingShell
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.shared.tv.MediaCard
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * DEB-5839 (FR-4) — a title page opened from a tile draws the tile's title and year on its first frame, in the loaded hero's
 * box, and nothing the detail answer owns (no Play, no progress, no watched mark). With no card it is the plain skeleton.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class DetailCardHeaderTest {
    private val card = MediaCard(
        id = "m1", title = "Invented Film", year = 2001, genre = "Drama", rating = "PG", posterUrl = null, backdropUrl = null,
        progressPct = 0.4f, watched = true,
    )

    @Test fun `the first frame shows the card's title and year`() = runComposeUiTest {
        setContent { RaviloTheme { Box(Modifier.fillMaxSize()) { DetailLoadingShell(card) } } }
        onNodeWithText("Invented Film").assertExists()
        onNodeWithText("2001").assertExists()
    }

    @Test fun `no Play, progress, watched mark or badge is drawn from the card`() = runComposeUiTest {
        setContent { RaviloTheme { Box(Modifier.fillMaxSize()) { DetailLoadingShell(card) } } }
        assertEquals(0, onAllNodesWithTextCount("Play"))
        assertEquals(0, onAllNodesWithTextCount("PG"))
        assertEquals(0, onAllNodesWithTextCount("Drama"))
        assertEquals(0, onAllNodesWithTextCount("Watched"))
    }

    @Test fun `the hero is the same box with a card as without`() = runComposeUiTest {
        var withCard by androidx.compose.runtime.mutableStateOf(false)
        setContent { RaviloTheme { Box(Modifier.fillMaxSize()) { DetailLoadingShell(if (withCard) card else null) } } }
        val before = onNodeWithTag(DETAIL_SKELETON_HERO).fetchSemanticsNode().boundsInRoot
        runOnIdle { withCard = true }
        waitForIdle()
        assertEquals(before, onNodeWithTag(DETAIL_SKELETON_HERO).fetchSemanticsNode().boundsInRoot)
    }

    @Test fun `without a card there is no title text`() = runComposeUiTest {
        setContent { RaviloTheme { Box(Modifier.fillMaxSize()) { DetailLoadingShell(null) } } }
        assertEquals(0, onAllNodesWithTextCount("Invented Film"))
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesWithTextCount(text: String) =
        onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size
}
