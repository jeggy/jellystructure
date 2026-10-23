package dev.jellystructure.ravilo.ui.focus

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.jellystructure.ravilo.ui.components.ButtonStyle
import dev.jellystructure.ravilo.ui.components.GenreChip
import dev.jellystructure.ravilo.ui.components.RaviloButton
import dev.jellystructure.ravilo.ui.components.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DEB-2204 (FR-3) — a focus move recomposes the component that lost focus and the one that gained it, once each, and
 * nothing else; and no frame of the fade or the spring recomposes anything.
 *
 * The probe is a `composed` modifier handed to each item: its `SideEffect` runs every time the item's own composition
 * scope recomposes, because the item applies the modifier to a layout it emits directly in its body.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class FocusRecompositionTest {
    private class Counts(n: Int) { val c = IntArray(n); fun snapshot() = c.copyOf() }

    private fun Modifier.probe(counts: Counts, i: Int): Modifier = composed {
        SideEffect { counts.c[i]++ }
        Modifier
    }.let { this.then(it) }

    /** Builds N items twice (two rows), focuses #1, then moves focus to #2 and checks the counts. */
    private fun check(n: Int = 6, item: @Composable (i: Int, fr: FocusRequester, probe: Modifier) -> Unit) = runComposeUiTest {
        val rowA = Counts(n)
        val rowB = Counts(n)
        val frs = List(n) { FocusRequester() }
        mainClock.autoAdvance = false
        setContent {
            Column {
                Row { repeat(n) { i -> item(i, frs[i], Modifier.probe(rowA, i)) } }
                Row { repeat(n) { i -> item(i, remember { FocusRequester() }, Modifier.probe(rowB, i)) } }
            }
        }
        mainClock.advanceTimeBy(100)
        runOnIdle { frs[1].requestFocus() }
        mainClock.advanceTimeBy(1_000)
        val before = rowA.snapshot(); val otherRow = rowB.snapshot()

        runOnIdle { frs[2].requestFocus() }
        mainClock.advanceTimeByFrame()
        val applied = rowA.snapshot()
        for (i in 0 until n) {
            val d = applied[i] - before[i]
            if (i == 1 || i == 2) assertTrue(d in 1..1, "item $i (lost/gained focus) recomposes once, was $d")
            else assertEquals(0, d, "item $i did not change focus")
        }
        assertTrue(rowB.c.contentEquals(otherRow), "nothing in the other row recomposes")

        // the frames of the 200 ms fade and of the spring: draw only
        mainClock.advanceTimeBy(60)
        assertTrue(rowA.c.contentEquals(applied), "no recomposition while the fade runs: ${applied.toList()} → ${rowA.c.toList()}")
        // the end: a component whose draw-only animation ends may recompose once more (a boolean it shows flips), never per frame
        mainClock.advanceTimeBy(2_000)
        for (i in 0 until n) {
            val d = rowA.c[i] - applied[i]
            if (i == 1 || i == 2) assertTrue(d in 0..1, "item $i recomposes at most once more, at the end, was $d")
            else assertEquals(0, d, "item $i did not change focus")
        }
        assertTrue(rowB.c.contentEquals(otherRow))
    }

    @Test fun `a tile row recomposes the two tiles whose focus changed and nothing per frame`() =
        check { i, fr, probe -> Tile(title = "T$i", posterUrl = null, focusRequester = fr, modifier = probe) }

    @Test fun `a button row recomposes the two buttons whose focus changed and nothing per frame`() =
        check { i, fr, probe -> RaviloButton(label = "B$i", focusRequester = fr, style = if (i % 2 == 0) ButtonStyle.PRIMARY else ButtonStyle.GHOST, modifier = probe) }

    @Test fun `a chip row recomposes the two chips whose focus changed and nothing per frame`() =
        check { i, fr, probe -> GenreChip(text = "C$i", lead = i == 0, focusRequester = fr, onSelect = {}, modifier = probe) }
}
