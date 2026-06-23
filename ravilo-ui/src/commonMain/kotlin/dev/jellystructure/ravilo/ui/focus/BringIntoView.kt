package dev.jellystructure.ravilo.ui.focus

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec

/**
 * Edge-based bring-into-view for **vertical** scroll containers (R45): scroll only enough to reveal
 * content that is genuinely clipped, and return 0 for content already fully in view. Compose's focus
 * machinery requests bring-into-view whenever a focusable gains focus; the default behaviour can pull
 * an already-visible target (e.g. the detail Play button sitting in the lower third of a full-bleed
 * hero) toward the viewport, scrolling the hero out of frame on entry. This makes that a no-op while
 * still revealing off-screen targets (used together with the explicit snap-to-top on the actions row).
 */
@OptIn(ExperimentalFoundationApi::class)
val EdgeBringIntoViewSpec: BringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
        offset < 0f -> offset                                   // clipped at the top → reveal
        offset + size > containerSize -> offset + size - containerSize  // clipped at the bottom → reveal
        else -> 0f                                              // fully visible → don't move
    }
}
