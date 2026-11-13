package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DEB-4288 (restores R136) — the page-context label (a channel or collection name) shows in the bar without moving the
 * tabs: it sits in the flexible slot after them, not in the row ahead of them.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class AppBarTitleSlotTest {
    private val tabs = listOf("Home", "Movies", "Series", "Discover")

    /** The left edge of each tab, and of the label when there is one, with [title] on a 1280 dp wide bar. */
    private fun edges(title: String?): Pair<List<Float>, Float?> {
        var tabX = emptyList<Float>()
        var labelX: Float? = null
        runComposeUiTest {
            setContent { Box(Modifier.size(1280.dp, 200.dp)) { AppBar(navItems = tabs, title = title) } }
            waitForIdle()
            tabX = tabs.map { onNodeWithText(it).fetchSemanticsNode().boundsInRoot.left }
            if (title != null) labelX = onNodeWithText("· $title").fetchSemanticsNode().boundsInRoot.left
        }
        return tabX to labelX
    }

    @Test
    fun `a title does not move the tabs`() {
        val (without, _) = edges(null)
        val (with, label) = edges("A Collection Name")
        assertEquals(without, with, "every tab keeps its x with a title in the bar")
        assertTrue(label != null && label > with.last(), "the label sits after the last tab")
    }
}
