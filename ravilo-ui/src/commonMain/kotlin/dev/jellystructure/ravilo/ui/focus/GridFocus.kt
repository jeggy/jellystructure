package dev.jellystructure.ravilo.ui.focus

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

/**
 * R361 (FR-R361-3/4) — the focus bookkeeping a browse grid (Movies / Series / a seeded page, My List) shares:
 * which tile holds focus, one requester that is attached to whichever tile a restore or a refresh aims at (never a
 * requester fixed to lazy item 0, which is not composed once the grid has scrolled — the R200/R236 pattern), and
 * the scroll-then-focus that gets that tile composed first.
 *
 * Every grid item carries [itemModifier]; the grid reports refreshes through [FollowGridRefresh].
 */
@Stable
class GridFocus internal constructor(val gridState: LazyGridState) {
    private val targetFR = FocusRequester()
    private var targetKey by mutableStateOf<Any?>(null)

    /** The key and index of the tile that last held focus in this grid (-1: none since it last left). */
    internal var focusedKey: Any? = null
        private set
    var focusedIndex by mutableIntStateOf(-1)
        private set

    /** Whether focus is anywhere in the grid right now. */
    var gridFocused by mutableStateOf(false)
        internal set

    /** Attach to each grid item's outermost node: tracks focus, and carries the target requester while the item is
     *  the one a restore or a refresh aims at. */
    fun itemModifier(key: Any, index: Int): Modifier = Modifier
        .onFocusChanged {
            if (it.hasFocus) { focusedKey = key; focusedIndex = index }
            else if (focusedIndex == index && focusedKey == key) focusedIndex = -1
        }
        .then(if (key == targetKey) Modifier.focusRequester(targetFR) else Modifier)

    /** Scroll [index] into composition if it is not (a few small steps first, so the page moves only as far as
     *  needed; a jump for a far one), then focus it with the bounded retry. Says whether focus moved. */
    suspend fun focusIndex(keys: List<Any>, index: Int): Boolean {
        if (index !in keys.indices) return false
        targetKey = keys[index]
        withFrameNanos { }
        bringIndexIn(index)
        val ok = targetFR.requestFocusAwaiting()
        targetKey = null
        return ok
    }

    private suspend fun bringIndexIn(index: Int) {
        repeat(6) {
            val info = gridState.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.any { it.index == index }) return
            val first = visible.firstOrNull()?.index
            val last = visible.lastOrNull()?.index
            if (first == null || last == null) { runCatching { gridState.scrollToItem(index) }; return }
            val perStep = (info.viewportSize.height / 3f).coerceAtLeast(1f)
            val far = index < first - 2 * (last - first + 1) || index > last + 2 * (last - first + 1)
            if (far) { runCatching { gridState.scrollToItem(index) }; return }
            runCatching { gridState.scrollBy(if (index < first) -perStep else perStep) }
            withFrameNanos { }
        }
        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == index }) runCatching { gridState.scrollToItem(index) }
    }

    /**
     * R361 (FR-R361-4) — a Back-return: the tile with [restoreKey] if it is still in [keys], else the tile now at
     * [restoreIndex] (`fallbackIndex`), else nothing (`false`: the grid is empty).
     */
    suspend fun restore(keys: List<Any>, restoreKey: Any?, restoreIndex: Int): Boolean {
        val found = keys.indexOf(restoreKey)
        val idx = if (found >= 0) found else fallbackIndex(restoreIndex, keys.size) ?: return false
        return focusIndex(keys, idx)
    }

    /** R361 (FR-R361-3 for grids) — the focused tile left the list in a refresh: the tile now at its position. */
    internal suspend fun followRefresh(prev: List<Any>, now: List<Any>, hadFocus: Boolean, onEmpty: () -> Unit) {
        val key = focusedKey ?: return
        if (!hadFocus || prev == now || key in now) return
        val prevIdx = prev.indexOf(key).coerceAtLeast(0)
        focusedKey = null
        val to = fallbackIndex(prevIdx, now.size)
        if (to == null) onEmpty() else focusIndex(now, to)
    }
}

@Composable
fun rememberGridFocus(gridState: LazyGridState): GridFocus = remember(gridState) { GridFocus(gridState) }

/**
 * R361 — call from the grid composable with its current keys: when a refresh drops the focused tile while the grid
 * holds focus, focus moves to the tile now at its position ([onEmpty] when nothing is left). The "had focus" flag is
 * read during the composition that applies the new keys, before the removed tile's detach clears focus.
 */
@Composable
fun FollowGridRefresh(gridFocus: GridFocus, keys: List<Any>, onEmpty: () -> Unit) {
    val prevKeys = remember(gridFocus) { mutableStateOf(keys) }
    val hadFocusAtSwap = gridFocus.gridFocused
    LaunchedEffect(keys) {
        val prev = prevKeys.value
        prevKeys.value = keys
        gridFocus.followRefresh(prev, keys, hadFocusAtSwap, onEmpty)
    }
}
