package dev.jellystructure.ravilo.ui.downloads

/**
 * DEB-4070 (FR-8, amended) — the action row is Play · Download · My List, and **Play must not move**. The Download control has one
 * width per language, taken from the widest of its short labels, so it is the same in all five states; My List is a fixed icon
 * button; Play takes the rest.
 */
object DownloadControlLayout {
    /** My List, icon-only (44 dp). */
    const val MY_LIST_DP = 44f
    /** The gap between the three controls. */
    const val GAP_DP = 10f
    /** Horizontal padding inside the control, both sides together, and the glyph that leads a label. */
    const val CHROME_DP = 40f

    /**
     * The short labels of the five states in one language, for measuring: the verb, "Queued", the widest percentage ("100 %"), "Downloaded"
     * and the failure's "Retry". [str] resolves a key in the current language.
     */
    fun shortLabels(str: (String) -> String): List<String> = listOf(
        str("download.action"),
        str("download.queued"),
        "100 %",
        str("download.downloaded"),
        str("download.retry"),
    )

    /** The one width this language's control has, in dp: the widest label plus the chrome. [measureDp] measures a label at the control's text style. */
    fun controlWidthDp(labels: List<String>, measureDp: (String) -> Float): Float =
        (labels.maxOfOrNull(measureDp) ?: 0f) + CHROME_DP

    /** Play's width in a row of [rowDp]: everything the other two controls and the two gaps leave. */
    fun playWidthDp(rowDp: Float, controlDp: Float): Float = rowDp - controlDp - MY_LIST_DP - 2 * GAP_DP
}
