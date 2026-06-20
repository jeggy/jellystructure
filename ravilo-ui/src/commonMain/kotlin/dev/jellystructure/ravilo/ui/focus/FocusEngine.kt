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
        if (rowCount == 0) return
        val newRow = (focusedRow + dRow).coerceIn(0, rowCount - 1)
        focusedRow = newRow
        focusedCol = clampCol(newRow, focusedCol + dCol)
        requestFocus()
    }

    fun moveTo(row: Int, col: Int) {
        if (rowCount == 0) return
        val newRow = row.coerceIn(0, rowCount - 1)
        focusedRow = newRow
        focusedCol = clampCol(newRow, col)
        requestFocus()
    }

    // coerceIn(0, size - 1) throws on an empty row (size - 1 == -1); pin an empty row's column to 0.
    private fun clampCol(row: Int, col: Int): Int {
        val size = grid[row].size
        return if (size == 0) 0 else col.coerceIn(0, size - 1)
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
