package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.jellystructure.ravilo.ui.theme.LocalReducedMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DEB-2204 (FR-6) — a long title in a TV grid tile scrolls once per focus arrival. The clock is the compose test
 * clock: `mainClock` drives both the effect's `delay`s and the animation frames.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class ScrollOnceTitleTest {
    private val long = "The Quick Brown Fox Jumps Over The Lazy Dog Again And Again"
    private val short = "Short"
    private val other = "Another Tile"

    private var tileComposes = 0

    private fun Modifier.probe(): Modifier = this.then(composed { SideEffect { tileComposes++ }; Modifier })

    private class Stage(val first: FocusRequester, val second: FocusRequester)

    private fun ComposeUiTest.stage(firstTitle: String, reduced: Boolean = false, scroll: Boolean = true): Stage {
        val s = Stage(FocusRequester(), FocusRequester())
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalReducedMotion provides reduced) {
                Row {
                    Tile(title = firstTitle, posterUrl = null, focusRequester = s.first, scrollLongTitle = scroll, modifier = Modifier.probe())
                    Tile(title = other, posterUrl = null, focusRequester = s.second, scrollLongTitle = scroll)
                }
            }
        }
        mainClock.advanceTimeBy(100)
        return s
    }

    private fun ComposeUiTest.x(title: String): Float = onNodeWithText(title).fetchSemanticsNode().layoutInfo.coordinates.positionInRoot().x
    private fun ComposeUiTest.titleWidth(title: String): Int = onNodeWithText(title).fetchSemanticsNode().size.width
    private fun ComposeUiTest.tileWidth(): Int = titleWidth(other)

    @Test fun `nothing moves or changes before the focus has rested for 1000 ms`() = runComposeUiTest {
        val s = stage(long)
        val x0 = x(long)
        runOnIdle { s.first.requestFocus() }
        mainClock.advanceTimeBy(950)
        assertEquals(tileWidth(), titleWidth(long), "still the clipped ellipsis at 950 ms")
        assertEquals(x0, x(long), 0.01f)
        mainClock.advanceTimeBy(250)
        assertTrue(titleWidth(long) > tileWidth(), "the full title replaced the ellipsis once focus had rested 1000 ms")
    }

    @Test fun `it moves left until the last character shows, holds, returns and stops within 5 s`() = runComposeUiTest {
        val s = stage(long)
        val x0 = x(long)
        runOnIdle { s.first.requestFocus() }
        mainClock.advanceTimeBy(1_050)
        val full = titleWidth(long)
        val travel = (full - tileWidth()).toFloat()
        assertTrue(travel > 0f)
        mainClock.advanceTimeBy(1_000)                        // 2.05 s after focus, 1.05 s into the travel
        val mid = x(long)
        assertTrue(mid < x0 - 1f && mid > x0 - travel, "mid-travel, was ${x0 - mid} of $travel")
        mainClock.advanceTimeBy(2_550)                        // 4.6 s after focus: the travel (3.5 s from 1.0 s) is over
        assertEquals(x0 - travel, x(long), 1.5f, "the last character is visible at the end of the travel")
        mainClock.advanceTimeBy(800)                          // 5.4 s: inside the 1 s hold
        assertEquals(x0 - travel, x(long), 1.5f)
        mainClock.advanceTimeBy(500)                          // 5.9 s: 4.9 s after the start, the 300 ms return is done
        assertEquals(x0, x(long), 0.01f, "back at offset 0, inside 5 s of the start")
        assertEquals(tileWidth(), titleWidth(long), "the ellipsis is back")
        mainClock.advanceTimeBy(10_000)
        assertEquals(x0, x(long), 0.01f, "resting longer never repeats the run")
        assertEquals(tileWidth(), titleWidth(long))
    }

    @Test fun `a title that fits never moves`() = runComposeUiTest {
        val s = stage(short)
        val x0 = x(short)
        runOnIdle { s.first.requestFocus() }
        for (i in 0 until 12) {
            mainClock.advanceTimeBy(500)
            assertEquals(x0, x(short), 0.01f)
            assertEquals(tileWidth(), titleWidth(short), "a title that fits stays in its ellipsis-width box")
        }
    }

    @Test fun `a focus change mid-run resets the title in the same frame`() = runComposeUiTest {
        val s = stage(long)
        val x0 = x(long)
        runOnIdle { s.first.requestFocus() }
        mainClock.advanceTimeBy(2_000)
        assertTrue(x(long) < x0 - 1f, "mid-run")
        runOnIdle { s.second.requestFocus() }
        mainClock.advanceTimeByFrame()
        assertEquals(x0, x(long), 0.01f)
        assertEquals(tileWidth(), titleWidth(long), "the ellipsis is back in the frame focus left")
        mainClock.advanceTimeBy(10_000)
        assertEquals(tileWidth(), titleWidth(long), "the cancelled run does not resume")
    }

    @Test fun `a pending start is cancelled when focus leaves before 1000 ms`() = runComposeUiTest {
        val s = stage(long)
        runOnIdle { s.first.requestFocus() }
        mainClock.advanceTimeBy(600)
        runOnIdle { s.second.requestFocus() }
        mainClock.advanceTimeBy(3_000)
        assertEquals(tileWidth(), titleWidth(long))
    }

    @Test fun `reduced motion keeps the title still`() = runComposeUiTest {
        val s = stage(long, reduced = true)
        val x0 = x(long)
        runOnIdle { s.first.requestFocus() }
        for (i in 0 until 12) {
            mainClock.advanceTimeBy(500)
            assertEquals(x0, x(long), 0.01f)
            assertEquals(tileWidth(), titleWidth(long))
        }
    }

    @Test fun `a tile that does not opt in never scrolls`() = runComposeUiTest {
        val s = stage(long, scroll = false)
        runOnIdle { s.first.requestFocus() }
        mainClock.advanceTimeBy(2_000)
        assertEquals(tileWidth(), titleWidth(long))
    }

    @Test fun `the tile recomposes at most once at the start of a run and once at its end and never per frame`() = runComposeUiTest {
        val s = stage(long)
        runOnIdle { s.first.requestFocus() }
        mainClock.advanceTimeBy(900)                          // focus landed and settled
        val beforeStart = tileComposes
        mainClock.advanceTimeBy(300)                          // the start at 1000 ms
        assertTrue(tileComposes - beforeStart <= 1, "start: ${tileComposes - beforeStart}")
        val afterStart = tileComposes
        mainClock.advanceTimeBy(3_000)                        // frames of the travel and the hold
        assertEquals(afterStart, tileComposes, "no recomposition while the title moves")
        mainClock.advanceTimeBy(2_500)                        // the return and the end
        assertTrue(tileComposes - afterStart <= 1, "end: ${tileComposes - afterStart}")
        assertEquals(tileWidth(), titleWidth(long))
    }
}
