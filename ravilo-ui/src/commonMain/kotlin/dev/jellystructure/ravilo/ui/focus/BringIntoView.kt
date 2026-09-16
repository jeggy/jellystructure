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
 * Density-aware variant of [EdgeBringIntoViewSpec].
 * - [peekDp] > 0: scroll [peekDp] further past the bottom clip so the next row is partially visible.
 * - [topInsetDp] > 0 (R65): treat the top [topInsetDp] of the viewport as occupied (e.g. an overlay
 *   app bar). Top-clipped targets are revealed at `topInsetDp` instead of `y=0`, keeping them from
 *   sliding under the bar.
 * - [centerLineFraction] > 0 (R140): a **focus band** for downward navigation, as a fraction of the viewport
 *   height (e.g. 0.3 = 30% down) so it's density-independent. A focused target whose top sits *below* the
 *   band is pulled up so its top rests on it — the focused row lands at a consistent mid-screen height (next
 *   row peeking below) instead of hugging the bottom edge. Unlike the edge-only peek, this fires even when
 *   the target is already fully visible, which is what makes the position consistent. Upward reveals still
 *   snap to [topInsetDp] (unchanged); 0 disables the band so detail screens keep pure edge behaviour.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberEdgeBringIntoViewSpec(peekDp: Dp = 0.dp, topInsetDp: Dp = 0.dp, centerLineFraction: Float = 0f): BringIntoViewSpec {
    val density = LocalDensity.current
    return remember(density, peekDp, topInsetDp, centerLineFraction) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val peekPx   = with(density) { peekDp.toPx() }
                val topPx    = with(density) { topInsetDp.toPx() }
                val centerPx = containerSize * centerLineFraction   // fraction of viewport → density-independent
                return when {
                    offset < topPx -> offset - topPx                                  // up / clipped-top → reveal at inset
                    centerLineFraction > 0f && offset > centerPx -> offset - centerPx   // down → pull to the focus band
                    offset + size > containerSize -> offset + size - containerSize + peekPx  // (band off) bottom clip
                    else -> 0f
                }
            }
        }
    }
}

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
