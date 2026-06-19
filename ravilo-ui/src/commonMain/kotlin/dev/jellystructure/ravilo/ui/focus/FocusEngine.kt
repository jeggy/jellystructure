package dev.jellystructure.ravilo.ui.focus

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester

/**
 * Tracks focus within a 2-D grid: rows × columns.
 * Up/down switch rows, left/right switch columns within a row.
 * Remembered focus restores to the last (row, col) pair when re-entering a screen.
 */
@Stable
class FocusGrid(rowCount: Int, colCounts: (row: Int) -> Int = { 1 }) {
    var focusedRow by mutableIntStateOf(0)
    var focusedCol by mutableIntStateOf(0)

    private val grid: List<List<FocusRequester>> =
        List(rowCount) { r -> List(colCounts(r)) { FocusRequester() } }

    fun requester(row: Int, col: Int): FocusRequester = grid[row][col]
    fun rowSize(row: Int): Int = grid[row].size
    val rowCount: Int get() = grid.size

    fun requestFocus() {
        runCatching { grid[focusedRow][focusedCol].requestFocus() }
    }

    fun move(dRow: Int, dCol: Int) {
        val newRow = (focusedRow + dRow).coerceIn(0, rowCount - 1)
        val newCol = (focusedCol + dCol).coerceIn(0, grid[newRow].size - 1)
        focusedRow = newRow
        focusedCol = newCol
        requestFocus()
    }

    fun moveTo(row: Int, col: Int) {
        focusedRow = row.coerceIn(0, rowCount - 1)
        focusedCol = col.coerceIn(0, grid[focusedRow].size - 1)
        requestFocus()
    }
}

/**
 * Linear single-row focus list. Left/right only. Used inside a ContentRow.
 */
@Stable
class FocusRow(count: Int) {
    var focused by mutableIntStateOf(0)
    val requesters: List<FocusRequester> = List(count) { FocusRequester() }
    val size: Int get() = requesters.size

    fun requestFocus() {
        runCatching { requesters[focused].requestFocus() }
    }

    fun moveLeft()  { if (focused > 0) { focused--; requestFocus() } }
    fun moveRight() { if (focused < size - 1) { focused++; requestFocus() } }
}

/** Screen-level remembered focus: one saved (row, col) per screen identity key. */
val globalFocusMemory = mutableMapOf<String, Pair<Int, Int>>()

fun rememberFocusAt(key: String): Pair<Int, Int> =
    globalFocusMemory[key] ?: (0 to 0)

fun saveFocusAt(key: String, row: Int, col: Int) {
    globalFocusMemory[key] = row to col
}
