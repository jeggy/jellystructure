package dev.jellystructure.ui

/**
 * DEB-3907 FR-9 — where a `.menu` goes when it has to leave its `.mu-scroll` wrapper (`overflow-x:auto` makes
 * `overflow-y` auto too, so an absolute menu is cropped by the table). **Pure — no DOM**, so it sits in
 * `commonMain` where `linuxX64Test` can run it; `muWireMenus` reads the rectangles and applies the result as
 * `position:fixed`.
 *
 * The menu opens under the trigger with the left edges aligned, kept 8 px inside the window; above the trigger
 * when it does not fit below. Returns `(left, top)`.
 */
fun fixedMenuPlacement(
    trigLeft: Double, trigTop: Double, trigBottom: Double,
    menuW: Double, menuH: Double, winW: Double, winH: Double,
): Pair<Double, Double> {
    val below = trigBottom + 6
    val top = if (below + menuH > winH - 8) trigTop - 6 - menuH else below
    val left = minOf(trigLeft, winW - menuW - 8)
    return maxOf(left, 8.0) to maxOf(top, 8.0)
}
