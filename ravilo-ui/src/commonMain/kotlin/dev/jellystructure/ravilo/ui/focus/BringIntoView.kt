package dev.jellystructure.ravilo.ui.focus

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Edge-based bring-into-view for **vertical** scroll containers (R45): scroll only enough to reveal
 * content that is genuinely clipped, and return 0 for content already fully in view. Compose's focus
 * machinery requests bring-into-view whenever a focusable gains focus; the default behaviour can pull
 * an already-visible target (e.g. the detail Play button sitting in the lower third of a full-bleed
 * hero) toward the viewport, scrolling the hero out of frame on entry. This makes that a no-op while
 * still revealing off-screen targets (used together with the explicit snap-to-top on the actions row).
 *
 * Use [rememberEdgeBringIntoViewSpec] in screens where a [peekDp] peek-ahead on the bottom is needed
 * (e.g. the home screen showing a glimpse of the next row when a row gains focus).
 */
@OptIn(ExperimentalFoundationApi::class)
val EdgeBringIntoViewSpec: BringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
        offset < 0f -> offset                                   // clipped at the top → reveal
        offset + size > containerSize -> offset + size - containerSize  // clipped at the bottom → reveal
        else -> 0f                                              // fully visible → don't move
    }
}

/**
 * R367 (FR-R367-1/3) — the one framing rule every vertical page uses, as a pure distance (beside R250's
 * [gutterBringIntoViewDistance]): `offset`/`size` are the target's top and height in the list, `containerSize` the
 * list's height; [topPx] is the inset a target's top is never pushed above, [centerPx] the focus band's lower edge
 * (`> 0` = the band is on, R140), [peekPx] how much of the next row a bottom reveal shows when the band is off.
 *
 * Every target, whatever its size, has a non-empty set of offsets where the distance is 0, and one step reaches it.
 * The body it replaces had none for a target taller than `container − top` with the band on: its bottom branch was not
 * gated on the band, so it pushed the target down by the overflow **plus the peek**, the top branch pulled it back,
 * and Compose's bring-into-view re-aimed every frame — Discover's rows bouncing (a 357 dp row in a 338 dp list).
 */
fun edgeBandScrollDistance(offset: Float, size: Float, containerSize: Float, topPx: Float, centerPx: Float, peekPx: Float): Float {
        if (centerPx > 0f) {
            // Band on: a target that fits rests with its top in [topPx, centerPx] and its bottom on screen; one that
            // does not fit rests with its top exactly at the inset, and the peek is what gives way (FR-R367-2).
            val maxTop = maxOf(topPx, minOf(centerPx, containerSize - size))
            return when {
                offset < topPx -> offset - topPx
                offset > maxTop -> offset - maxTop
                else -> 0f
            }
        }
        // Band off (the detail pages): reveal a clipped bottom with the peek, but never push the top above the inset.
        return when {
            offset < topPx -> offset - topPx
            offset + size > containerSize -> minOf(offset + size - containerSize + peekPx, offset - topPx)
            else -> 0f
        }
}

/**
 * R367 (FR-R367-3) — every caller's framing numbers in one place (dp, and the band as a fraction of the list's
 * height), so the convergence test covers each of them: **a new caller adds its band to [EDGE_BANDS]**.
 */
data class EdgeBand(val topInsetDp: Float, val centerLineFraction: Float, val peekDp: Float) {
    companion object {
        /** Home (R140): the heading clears the overlay app bar (60) + its own 64 dp band; next row peeking. */
        val HOME = EdgeBand(124f, 0.3f, 150f)
        /** A channel / collection page: as Home. */
        val CHANNEL = EdgeBand(124f, 0.3f, 150f)
        /** Discover → Request (R367 Fix 2): the list starts below the tab strip and nothing overlays it — inset 0. */
        val DISCOVER_REQUEST = EdgeBand(0f, 0.3f, 150f)
        /** Discover → the walls (R367 Fix 2): inset 0, as Request. */
        val DISCOVER_WALLS = EdgeBand(0f, 0.3f, 120f)
        /** Movie and series detail: app bar + 24, no band, a small peek. */
        val DETAIL = EdgeBand(84f, 0f, 60f)
    }
}

/** R367 — every caller's band (see [EdgeBand]). */
val EDGE_BANDS: List<EdgeBand> = listOf(EdgeBand.HOME, EdgeBand.CHANNEL, EdgeBand.DISCOVER_REQUEST, EdgeBand.DISCOVER_WALLS, EdgeBand.DETAIL)

/**
 * The density-aware spec for a vertical list (R45/R65/R140): see [edgeBandScrollDistance] for the rule.
 * - [peekDp] > 0: scroll [peekDp] further past a bottom clip so the next row is partly visible (band off).
 * - [topInsetDp] > 0 (R65): a top-clipped target is revealed at the inset (an overlay app bar), not at y = 0.
 * - [centerLineFraction] > 0 (R140): a focus band for downward navigation, as a fraction of the list's height.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberEdgeBringIntoViewSpec(peekDp: Dp = 0.dp, topInsetDp: Dp = 0.dp, centerLineFraction: Float = 0f): BringIntoViewSpec {
    val density = LocalDensity.current
    return remember(density, peekDp, topInsetDp, centerLineFraction) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                edgeBandScrollDistance(
                    offset, size, containerSize,
                    topPx = with(density) { topInsetDp.toPx() },
                    centerPx = containerSize * centerLineFraction,   // fraction of viewport → density-independent
                    peekPx = with(density) { peekDp.toPx() },
                )
        }
    }
}

/** R367 — [rememberEdgeBringIntoViewSpec] for one of [EDGE_BANDS]. */
@Composable
fun rememberEdgeBringIntoViewSpec(band: EdgeBand): BringIntoViewSpec =
    rememberEdgeBringIntoViewSpec(peekDp = band.peekDp.dp, topInsetDp = band.topInsetDp.dp, centerLineFraction = band.centerLineFraction)

/**
 * R250 (FR-R250-6) — pure distance for [rememberGutterBringIntoViewSpec]: a focused item is never
 * closer than [gutterPx] to either edge of the container. A merely *focused* season pill used to be
 * brought to the viewport edge (the lazy row's default), inside the content padding — Season 17 sat
 * 25 px from the screen edge on the stue TV. Selection-driven `scrollToItem` (R201) is unaffected.
 */
fun gutterBringIntoViewDistance(offset: Float, size: Float, containerSize: Float, gutterPx: Float): Float = when {
    offset < gutterPx -> offset - gutterPx                                            // clipped / inside the start gutter → reveal at the gutter
    offset + size > containerSize - gutterPx -> offset + size - (containerSize - gutterPx)  // inside the end gutter → reveal at the gutter
    else -> 0f
}

/** R250 (FR-R250-6) — a horizontal bring-into-view spec whose margin on both sides is [gutter]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberGutterBringIntoViewSpec(gutter: Dp): BringIntoViewSpec {
    val density = LocalDensity.current
    return remember(density, gutter) {
        val gutterPx = with(density) { gutter.toPx() }
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                gutterBringIntoViewDistance(offset, size, containerSize, gutterPx)
        }
    }
}
