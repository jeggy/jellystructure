package dev.jellystructure.ravilo.ui.components

/**
 * R255 — J's backdrop is dimmed by ONE flat layer of the page's own colour (`colors.background` /
 * `--bg`, never black) over the WHOLE picture: top to bottom, left to right. **No gradient, and no
 * darker region behind the text** — owner decision 2026-09-17, after seeing both R250's box and this
 * phase's first (gradient) build on the stue TV.
 *
 * One definition, two renderers: `FocusDetailBackdrop.kt` here and `design/ravilo/ravilo.css`
 * `.jbg-scrim` in the mockup. Change one, change the other.
 *
 * The number is arithmetic, not taste: the panel's body ink (`textSecondary`, L ≈ 0.46) needs a ground
 * of L ≤ 0.063 for 4.5:1. Blended in sRGB, 0.75 of the page colour over a PURE WHITE picture leaves
 * L ≈ 0.051 → 5.0:1 (0.70 would leave 4.1:1). So a flat 0.75 clears the floor on any picture with no
 * help from a local scrim. Noir goes deeper by the usual margin.
 */
object FocusDetailScrims {
    const val WASH_ALPHA = 0.75f
    const val WASH_ALPHA_NOIR = 0.85f
    fun washAlpha(noir: Boolean): Float = if (noir) WASH_ALPHA_NOIR else WASH_ALPHA
}
