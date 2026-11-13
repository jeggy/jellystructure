package dev.jellystructure.ravilo.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** DEB-4287 — a top-bar page's content composes one frame after its shell. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class PageShellFirstTest {
    /** The names of the contents on screen right now, kept by the contents' own enter and leave. */
    private val live = mutableListOf<String>()

    @Composable
    private fun Content(name: String) {
        DisposableEffect(name) { live += name; onDispose { live -= name } }
    }

    @Test
    fun `the first composition holds no content and a later one does`() = runComposeUiTest {
        val passes = mutableListOf<Boolean>()
        setContent {
            val past = rememberPastFirstFrame()
            passes += past
            if (past) Content("page")
        }
        waitForIdle()
        assertEquals(listOf(false, true), passes, "one pass for the shell, then one with the content")
        assertEquals(listOf("page"), live)
    }

    @Test
    fun `a return skips the wait`() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent { if (rememberPastFirstFrame(skip = true)) Content("page") }
        assertEquals(listOf("page"), live)
    }

    @Test
    fun `a changed key takes the old content down in one frame and builds the new one in the next`() = runComposeUiTest {
        var which by mutableStateOf("a")
        mainClock.autoAdvance = false
        val seen = mutableListOf<List<String>>()
        setContent {
            if (rememberPastFirstFrame(key = which)) Content(which)
            SideEffect { seen += live.toList() }
        }
        mainClock.advanceTimeByFrame(); mainClock.advanceTimeByFrame()
        assertEquals(listOf("a"), live)
        which = "b"
        mainClock.advanceTimeByFrame()
        val sawNeither = seen.any { it.isEmpty() }
        mainClock.advanceTimeByFrame(); mainClock.advanceTimeByFrame()
        assertEquals(listOf("b"), live)
        assertEquals(true, sawNeither, "a pass held neither the old content nor the new")
    }
}
