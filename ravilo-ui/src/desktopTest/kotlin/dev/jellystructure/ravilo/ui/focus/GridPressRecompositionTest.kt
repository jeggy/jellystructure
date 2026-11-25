package dev.jellystructure.ravilo.ui.focus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.jellystructure.ravilo.ui.components.Tile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DEB-2448 (FR-R362-5, amended; acceptance 9) — a Down or Up in a browse grid recomposes the tile that loses focus and
 * the one that gains it, and no other tile; focus lands on a composed target at once; and when the move needs a scroll
 * the grid glides (several distinct offsets), it never jumps.
 *
 * The probe is DEB-2204 FR-3's: a `composed` modifier on each `Tile`, whose `SideEffect` runs whenever that tile's own
 * composition scope recomposes.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class GridPressRecompositionTest {
    private val cols = 6
    private val n = 60
    private val keys = List(n) { "k$it" }

    private fun Modifier.probe(counts: IntArray, i: Int): Modifier = composed {
        SideEffect { counts[i]++ }
        Modifier
    }.let { this.then(it) }

    private class Rig {
        val counts = IntArray(60)
        lateinit var focus: GridFocus
        lateinit var state: LazyGridState
        lateinit var scope: CoroutineScope
    }

    private fun ComposeUiTest.render(rig: Rig) {
        mainClock.autoAdvance = false
        setContent {
            val state = browseLazyGridState()
            val gridFocus = rememberGridFocus(state)
            rig.focus = gridFocus; rig.state = state
            val scope = rememberCoroutineScope()
            rig.scope = scope
            val focusManager = LocalFocusManager.current
            LazyVerticalGrid(
                columns = GridCells.Fixed(cols), state = state,
                modifier = Modifier.width(600.dp).height(300.dp).focusRestorer()
                    .onFocusChanged { gridFocus.gridFocused = it.hasFocus }
                    .gridColumnKeys(gridFocus, { keys }, cols, scope, focusManager),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(n, key = { keys[it] }) { i ->
                    Box(gridFocus.itemModifier(keys[i], i)) {
                        Tile(title = "T$i", posterUrl = null, modifier = Modifier.probe(rig.counts, i))
                    }
                }
            }
        }
        mainClock.advanceTimeBy(300)
    }

    private fun ComposeUiTest.focusAt(rig: Rig, index: Int) {
        runOnIdle { rig.scope.launch { rig.focus.focusIndex(keys, index) } }
        mainClock.advanceTimeBy(1_500)
        assertEquals(index, rig.focus.focusedIndex)
    }

    private fun ComposeUiTest.press(key: Key) {
        onRoot().performKeyInput { pressKey(key) }
    }

    @Test fun `one Down from column 4 recomposes the tile losing focus and the tile gaining it`() = runComposeUiTest {
        val rig = Rig()
        render(rig)
        focusAt(rig, 3)
        val composed = rig.counts.indices.filter { rig.counts[it] > 0 }
        assertTrue(9 in composed, "the next row is composed before the key arrives")
        val before = rig.counts.copyOf()

        press(Key.DirectionDown)
        mainClock.advanceTimeByFrame()
        assertEquals(9, rig.focus.focusedIndex, "the target is focused at once, the column kept")
        mainClock.advanceTimeBy(1_500)
        for (i in composed) {
            val d = rig.counts[i] - before[i]
            if (i == 3 || i == 9) assertTrue(d in 0..2, "tile $i (lost/gained focus) recomposed $d times")
            else assertEquals(0, d, "tile $i did not change focus but recomposed")
        }
    }

    @Test fun `Down and Up glide, the grid never jumps and the column is kept`() = runComposeUiTest {
        val rig = Rig()
        render(rig)
        focusAt(rig, 3)
        var maxStep = 0
        var distinct = HashSet<Int>()
        var last = rig.state.firstVisibleItemScrollOffset + rig.state.firstVisibleItemIndex * 10_000
        var idx = 3
        repeat(6) {
            press(Key.DirectionDown)
            idx += cols
            repeat(40) {
                mainClock.advanceTimeByFrame()
                val now = rig.state.firstVisibleItemScrollOffset + rig.state.firstVisibleItemIndex * 10_000
                if (now != last) distinct.add(now)
                last = now
            }
            assertEquals(idx, rig.focus.focusedIndex)
        }
        assertTrue(distinct.size > 6, "the scroll animates over several frames (saw ${distinct.size} distinct offsets)")
        repeat(6) {
            press(Key.DirectionUp)
            idx -= cols
            mainClock.advanceTimeBy(800)
            assertEquals(idx, rig.focus.focusedIndex)
        }
        assertEquals(3, rig.focus.focusedIndex)
    }
}
