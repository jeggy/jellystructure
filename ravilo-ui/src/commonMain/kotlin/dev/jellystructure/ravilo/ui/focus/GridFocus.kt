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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import dev.jellystructure.ravilo.ui.screens.gridVerticalTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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

    /** R361 (FR-R361-7) — a Back-return restore that has not landed yet. A refresh that arrives while it is set is
     *  the restore's to resolve (it starts again on the new list), not [followRefresh]'s. */
    internal var restorePending = false

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
        // R361 (FR-R361-7.2) — a cancelled attempt (the list changed under it) leaves no requester armed.
        try {
            withFrameNanos { }
            bringIndexIn(index)
            return targetFR.requestFocusAwaiting()
        } finally {
            if (targetKey == keys[index]) targetKey = null
        }
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
    internal suspend fun followRefresh(
        prev: List<Any>, now: List<Any>, hadFocus: Boolean, focusedAtSwap: Any?, onEmpty: () -> Unit,
    ) {
        if (restorePending) return
        val key = focusedAtSwap ?: return
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
 * R362 (FR-R362-5) — Down and Up in a browse grid keep the column: the grid's own key handler moves to
 * `gridVerticalTarget`'s tile (scrolled in first, then focused), instead of Compose's 2-D search, which lands on
 * column 1 whenever the next row is not composed. Up from the first row goes to [onFirstRowUp] when given (R350's
 * facet-bar bridge); no row in that direction leaves the key to the native search. A target that cannot be focused
 * falls back to the native move, so the key is never dead (FR-R362-3).
 */
fun Modifier.gridColumnKeys(
    gridFocus: GridFocus,
    keys: () -> List<Any>,
    cols: Int,
    scope: CoroutineScope,
    focusManager: FocusManager,
    onFirstRowUp: (() -> Unit)? = null,
): Modifier = onPreviewKeyEvent { ev ->
    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val down = when (ev.key) {
        Key.DirectionDown -> true
        Key.DirectionUp -> false
        else -> return@onPreviewKeyEvent false
    }
    val idx = gridFocus.focusedIndex
    if (idx < 0) return@onPreviewKeyEvent false
    if (!down && idx < cols && onFirstRowUp != null) { onFirstRowUp(); return@onPreviewKeyEvent true }
    val ks = keys()
    val t = gridVerticalTarget(idx, cols, ks.size, down) ?: return@onPreviewKeyEvent false
    scope.launch {
        if (!gridFocus.focusIndex(ks, t)) focusManager.moveFocus(if (down) FocusDirection.Down else FocusDirection.Up)
    }
    true
}

/**
 * R361 (FR-R361-7) — a Back-return to a grid: focus the opened tile ([restoreKey]) or the tile now at
 * [restoreIndex], and spend the restore only when it lands (or the empty target has been asked for focus), never
 * when it starts. The kept store shows its old list and then refetches, so [keys] can change while the restore is
 * in flight; the restore then starts again on the new list, with the key and index captured at arrival (the
 * store's own are cleared by [onResolved]). The bounded retry is counted from the last change of [keys].
 *
 * [emptyIsHandled]: the grid has something else to show or focus when [keys] is empty, so [onEmpty] is not asked.
 * Declare this before [FollowGridRefresh], which leaves a pending restore to this effect.
 */
@Composable
fun RestoreOnReturn(
    gridFocus: GridFocus,
    keys: List<Any>,
    restoreKey: String?,
    restoreIndex: Int,
    onResolved: () -> Unit,
    onEmpty: () -> Unit,
    emptyIsHandled: Boolean = false,
) {
    val arrival = remember(gridFocus) {
        restoreKey?.let { it to restoreIndex }.also { gridFocus.restorePending = it != null }
    }
    LaunchedEffect(keys) {
        val (key, index) = arrival ?: return@LaunchedEffect
        if (!gridFocus.restorePending) return@LaunchedEffect
        val landed = gridFocus.restore(keys, key, index)
        gridFocus.restorePending = false
        onResolved()
        if (!landed && !emptyIsHandled) onEmpty()
    }
}

/**
 * R361 — call from the grid composable with its current keys: when a refresh drops the focused tile while the grid
 * holds focus, focus moves to the tile now at its position ([onEmpty] when nothing is left). The "had focus" flag is
 * read during the composition that applies the new keys, before the removed tile's detach clears focus.
 */
@Composable
fun FollowGridRefresh(gridFocus: GridFocus, keys: List<Any>, onEmpty: () -> Unit) {
    val prevKeys = remember(gridFocus) { mutableStateOf(keys) }
    val hadFocusAtSwap = gridFocus.gridFocused
    // Read with the flag: once the removed tile detaches, Compose's own recovery can focus another tile at once, and
    // that overwrites `focusedKey` before this effect runs.
    val focusedAtSwap = gridFocus.focusedKey
    LaunchedEffect(keys) {
        val prev = prevKeys.value
        prevKeys.value = keys
        gridFocus.followRefresh(prev, keys, hadFocusAtSwap, focusedAtSwap, onEmpty)
    }
}
