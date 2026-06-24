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
 * Density-aware variant of [EdgeBringIntoViewSpec]. When [peekDp] > 0, scrolls [peekDp] further
 * past the bottom clip point so the next row below is partially visible — gives the user a preview
 * that more content exists without a full scroll.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberEdgeBringIntoViewSpec(peekDp: Dp = 0.dp): BringIntoViewSpec {
    val density = LocalDensity.current
    return remember(density, peekDp) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val peekPx = with(density) { peekDp.toPx() }
                return when {
                    offset < 0f -> offset
                    offset + size > containerSize -> offset + size - containerSize + peekPx
                    else -> 0f
                }
            }
        }
    }
}
