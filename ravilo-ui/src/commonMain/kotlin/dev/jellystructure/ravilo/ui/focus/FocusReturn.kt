package dev.jellystructure.ravilo.ui.focus

/**
 * R361 (FR-R361-1) — where focus goes when it is aimed at a title that is no longer in its row or grid: the tile now
 * at the **same position** (the one that slid into its place), else the **last** one if the list got shorter than
 * that, else nothing (`null`: the list is empty). The one helper every screen that restores focus on Back uses
 * (Home rows, a collection's rows, the browse grids, My List, Search, the Discover walls).
 */
fun fallbackIndex(oldIndex: Int, newSize: Int): Int? =
    if (newSize <= 0) null else oldIndex.coerceIn(0, newSize - 1)

/** R361 — a resolved focus target on a page of rows: the row's key and the tile's key inside it. */
data class ReturnTarget(val rowKey: String, val itemKey: String)

/**
 * R361 (FR-R361-1/5, dev review item 4) — resolves a Back-return (or a refresh) on a page of rows, once, against the
 * rows about to be laid out. [rows] are the page's rows in order, each as its key and its tiles' keys; [rowKey] /
 * [itemKey] are what was selected, and [rowIndex] / [itemIndex] where they were at select time.
 *
 * 1. the title survives anywhere in its row (also at a new index) → that row and title;
 * 2. the row is still there with tiles → its tile at [fallbackIndex];
 * 3. the row is gone, or empty (an empty row counts as gone) → the row now in its place: the first non-empty row
 *    from its old position down (the row below slid up into it), else the nearest non-empty row above; its tile at
 *    [itemIndex], clamped to its length;
 * 4. every row empty → `null` (the caller's own entry focus applies).
 */
fun resolveReturn(
    rows: List<Pair<String, List<String>>>,
    rowKey: String,
    itemKey: String,
    rowIndex: Int,
    itemIndex: Int,
): ReturnTarget? {
    val own = rows.firstOrNull { it.first == rowKey }
    if (own != null && own.second.isNotEmpty()) {
        if (itemKey in own.second) return ReturnTarget(rowKey, itemKey)
        val i = fallbackIndex(itemIndex, own.second.size) ?: return null
        return ReturnTarget(rowKey, own.second[i])
    }
    val anchor = rows.indexOfFirst { it.first == rowKey }.takeIf { it >= 0 } ?: rowIndex.coerceIn(0, rows.size)
    val below = rows.drop(anchor).firstOrNull { it.first != rowKey && it.second.isNotEmpty() }
    val above = rows.take(anchor).lastOrNull { it.second.isNotEmpty() }
    val target = below ?: above ?: return null
    val i = fallbackIndex(itemIndex, target.second.size) ?: return null
    return ReturnTarget(target.first, target.second[i])
}
