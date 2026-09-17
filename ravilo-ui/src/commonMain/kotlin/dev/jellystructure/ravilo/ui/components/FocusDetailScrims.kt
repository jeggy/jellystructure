package dev.jellystructure.ravilo.ui.components

/**
 * R255 (FR-R255-2/-7) — THE stop table for J's backdrop, in the page's own colour (`colors.background`
 * / `--bg`, never black). One definition, two renderers: `FocusDetailBackdrop.kt` + `FocusDetailPanel.kt`
 * here, and `design/ravilo/ravilo.css` `.jbg-scrim` + `.jpanel::before` in the mockup. Change one,
 * change the other — that they had drifted (the mockup was black, at pre-R250 stops, with no panel scrim
 * at all) is the defect R255 found.
 *
 * Every value is an alpha of the page colour. Starting values (FR-R255-2), to be corrected by the
 * stue-TV measurement (FR-R255-6) — the reading part's 0.94 is R250's contrast arithmetic and holds.
 */
object FocusDetailScrims {
    /** Noir goes deeper by the same margin as before (FR-R255-4): less colour, never less picture. */
    const val NOIR_EXTRA = 0.10f

    /** Full-screen vertical gradient — (fraction of screen height, alpha). Head → mid (≤ 0.25, the
     *  picture is SEEN; R250's wash was 0.50–0.54 here) → floor, dissolving into the page. */
    val VERTICAL: List<Pair<Float, Float>> = listOf(
        0.00f to 0.70f,   // head: the app bar's ink, the heading of the row above
        0.18f to 0.22f,   // mid begins
        0.45f to 0.22f,   // mid ends
        0.75f to 0.55f,   // floor: the next row's heading
        1.00f to 1.00f,   // the picture ends in the page, not at the screen edge
    )

    /** Panel-local reading gradient, horizontal — (fraction of panel width, alpha). Feathered over 40 %
     *  of the width (R250: 10 %, which read as a box). */
    const val READING_ALPHA = 0.94f
    const val READING_ALPHA_NOIR = 0.96f
    const val READING_FEATHER_FRACTION = 0.40f

    /** …and feathered 0 → full over this many dp at the panel's top and bottom, so no straight scrim
     *  edge sits on the picture on any side. */
    const val READING_EDGE_FEATHER_DP = 16

    fun vertical(noir: Boolean): List<Pair<Float, Float>> =
        if (!noir) VERTICAL else VERTICAL.map { (at, a) -> at to (a + NOIR_EXTRA).coerceAtMost(1f) }

    fun readingAlpha(noir: Boolean): Float = if (noir) READING_ALPHA_NOIR else READING_ALPHA
}
