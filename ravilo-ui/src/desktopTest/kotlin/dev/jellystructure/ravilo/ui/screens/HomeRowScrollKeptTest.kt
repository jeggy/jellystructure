package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.jellystructure.ravilo.ui.components.KeptRowScroll
import dev.jellystructure.ravilo.ui.components.StaticContentRow
import dev.jellystructure.ravilo.ui.components.resolveKeptRowScroll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** DEB-5839 (FR-2) — Home's rows keep their horizontal place on Back, and a row whose items changed opens where its item went. */
@OptIn(ExperimentalTestApi::class)
class HomeRowScrollKeptTest {
    private val keys = (0 until 30).map { "k$it" }

    @Test fun `a row left scrolled opens where it was left`() = runComposeUiTest {
        var kept: KeptRowScroll? = null
        var shown by mutableStateOf(true)
        setContent {
            if (shown) StaticContentRow(
                title = "Row", items = keys, itemKey = { it },
                keptScroll = kept, onScrollKept = { kept = it },
            ) { _, k, _ -> Box(Modifier.size(120.dp)) { Text(k) } }
        }
        waitForIdle()
        onAllNodes(hasScrollAction()).onFirst().performScrollToIndex(12)
        waitForIdle()
        val left = assertNotNull(kept)
        assertEquals(12, left.index)

        runOnIdle { shown = false }; waitForIdle()
        runOnIdle { shown = true }; waitForIdle()
        // The row came back composed at the kept place, not at its start.
        assertEquals(12, kept?.index)
    }

    @Test fun `the kept item still in its place keeps its index and offset`() {
        val r = resolveKeptRowScroll(KeptRowScroll(5, 17, "k5"), keys.size, 0) { keys[it] }
        assertEquals(KeptRowScroll(5, 17), r.copy(key = null))
    }

    @Test fun `a row whose items moved opens at the kept item's new place`() {
        val moved = listOf("new") + keys
        val r = resolveKeptRowScroll(KeptRowScroll(5, 17, "k5"), moved.size, 0) { moved[it] }
        assertEquals(6, r.index)
        assertEquals(0, r.offset)
    }

    @Test fun `a row that lost the kept item opens at its start`() {
        val r = resolveKeptRowScroll(KeptRowScroll(5, 17, "gone"), keys.size, 0) { keys[it] }
        assertEquals(0, r.index)
    }

    @Test fun `a row that shrank never opens past its end`() {
        val short = keys.take(3)
        val r = resolveKeptRowScroll(KeptRowScroll(20, 4, null), short.size, 0) { short[it] }
        assertTrue(r.index < short.size)
    }

    @Test fun `the leading tile counts toward the index`() {
        val r = resolveKeptRowScroll(KeptRowScroll(6, 0, "k5"), keys.size, 1) { keys[it] }
        assertEquals(6, r.index)
    }

    @Test fun `no kept scroll opens at the start`() {
        assertEquals(KeptRowScroll(0, 0), resolveKeptRowScroll(null, keys.size, 0) { keys[it] })
    }
}
