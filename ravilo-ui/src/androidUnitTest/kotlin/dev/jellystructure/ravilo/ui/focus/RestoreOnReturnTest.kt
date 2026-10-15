package dev.jellystructure.ravilo.ui.focus

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * R361 (FR-R361-7) — a Back-return restore is spent when it lands, not when it starts. The kept store renders its
 * old list and refetches, so the list can change in any frame of the restore. The test owns the frame clock
 * (`autoAdvance = false`), so "the refetch lands N frames after the return" is a number, not a thread race: for
 * every N in the window the opened tile's neighbour is focused (or the empty target asked) within the bound, the key is
 * resolved exactly once, and no attempt is left pending. (A change after focus has landed is `FollowGridRefresh`'s
 * and is walked on the real screens: `BrowseFocusTest`'s *settled* rows and `MyListFocusTest`. This harness has no
 * tile of the real grid, so Compose's own recovery decides where focus goes there.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
class RestoreOnReturnTest {
    @get:Rule val rule = createComposeRule()

    private val all = (1..12).map { "k$it" }
    private var keys by mutableStateOf(all)
    private var resolved = 0
    private var empties = 0
    private lateinit var focus: GridFocus

    private fun render(restoreKey: String?, restoreIndex: Int) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val state = rememberLazyGridState()
            focus = rememberGridFocus(state)
            RestoreOnReturn(focus, keys, restoreKey, restoreIndex, onResolved = { resolved++ }, onEmpty = { empties++ })
            FollowGridRefresh(focus, keys, onEmpty = { empties++ })
            LazyVerticalGrid(
                columns = GridCells.Fixed(3), state = state,
                modifier = Modifier.focusRestorer().onFocusChanged { focus.gridFocused = it.hasFocus },
            ) {
                itemsIndexed(keys, key = { _, k -> k }) { i, k ->
                    // The wrapper tracks focus and carries the target requester, the tile inside is the focus target.
                    Box(focus.itemModifier(k, i)) { Box(Modifier.size(40.dp).testTag(k).focusable()) }
                }
            }
        }
    }

    private fun frames(n: Int) = repeat(n) { rule.mainClock.advanceTimeByFrame() }

    private fun settle() = rule.mainClock.advanceTimeBy(500)

    @Test fun `no refetch — the opened tile is focused and the key spent once`() {
        render("k3", 2)
        settle()
        rule.onNodeWithTag("k3").assertIsFocused()
        assertEquals(1, resolved)
        assertEquals(0, empties)
    }

    private fun refetchDropsOpened(afterFrames: Int) {
        render("k3", 2)
        frames(afterFrames)
        keys = all - "k3"
        settle()
        rule.onNodeWithTag("k4").assertIsFocused()      // the tile now at index 2
        assertEquals(1, resolved)
        assertEquals(0, empties)
    }

    @Test fun `the opened tile leaves in the first frame`() = refetchDropsOpened(0)
    @Test fun `the opened tile leaves one frame in`() = refetchDropsOpened(1)

    private fun refetchEmpties(afterFrames: Int) {
        render("k3", 2)
        frames(afterFrames)
        keys = emptyList()
        settle()
        assertEquals(1, resolved)
        assertEquals(1, empties)                        // the empty target was asked for focus, once
    }

    @Test fun `an empty refetch in the first frame asks the empty target`() = refetchEmpties(0)
    @Test fun `an empty refetch one frame in asks the empty target`() = refetchEmpties(1)

    @Test fun `two refetches during one restore — the last list wins, counted from the last change`() {
        render("k3", 2)
        frames(1)
        keys = all - "k3"
        frames(1)
        keys = all - "k3" - "k4"
        settle()
        rule.onNodeWithTag("k5").assertIsFocused()
        assertEquals(1, resolved)
    }

    @Test fun `a refetch that keeps the opened tile at a new index still lands on it`() {
        render("k3", 2)
        frames(1)
        keys = listOf("k0") + all
        settle()
        rule.onNodeWithTag("k3").assertIsFocused()
        assertEquals(1, resolved)
    }
}
