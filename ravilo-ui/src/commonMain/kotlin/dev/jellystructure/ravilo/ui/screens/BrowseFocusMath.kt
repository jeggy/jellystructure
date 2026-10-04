package dev.jellystructure.ravilo.ui.screens

/**
 * R362 (FR-R362-5) — Down/Up in a browse grid keeps the column: `index ± cols`, or the last tile of a shorter last
 * row on the way down; `null` when there is no row in that direction (Up from row 0 stays R350's facet-bar bridge,
 * Down from the last row stays native, which reaches R190's Seerr overflow row on a person page).
 */
fun gridVerticalTarget(index: Int, cols: Int, count: Int, down: Boolean): Int? {
    if (cols <= 0 || index !in 0 until count) return null
    if (!down) return (index - cols).takeIf { it >= 0 }
    val t = index + cols
    if (t < count) return t
    val lastRow = (count - 1) / cols
    return if (index / cols < lastRow) count - 1 else null
}

/**
 * R362 (FR-R362-1/2) — where Down from the app bar (and Up from the grid's first row) lands in the facet bar: the chip
 * last focused there if it is still fully on screen, else `null` = scroll the bar to its start and focus Genre.
 */
fun facetEntryTarget(lastChipKey: String?, fullyVisibleKeys: Set<String>): String? =
    lastChipKey?.takeIf { it in fullyVisibleKeys }

/**
 * R362 (FR-R362-6) — a facet (or Sort) popover's left edge: under the chip that opened it, clamped inside the
 * screen's horizontal padding on both sides.
 */
fun popoverOffsetX(chipX: Float, popoverWidth: Float, screenWidth: Float, hPad: Float): Float {
    val max = (screenWidth - hPad - popoverWidth).coerceAtLeast(hPad)
    return chipX.coerceIn(hPad, max)
}
