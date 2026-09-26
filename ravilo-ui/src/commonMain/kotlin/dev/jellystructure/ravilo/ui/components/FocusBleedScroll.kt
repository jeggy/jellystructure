package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * R306 (FR-R306-1) — how far a focused [RaviloButton] reaches past its own bounds: the 1.06 focus scale
 * on a ~220 dp button (~7 dp a side) plus its 16 dp glow, whose fade was measured on the stue TV to run
 * ~30 dp beyond the button (24 dp still showed a faint step). Stays inside the pages' 48 dp gutter.
 */
val FOCUS_BLEED: Dp = 40.dp

/**
 * R306 (FR-R306-1) — [horizontalScroll] for a row of focusable buttons, without cutting the focused one.
 *
 * A scrolling container clips its children along its scroll axis at its own edge, so a focused button
 * standing on the row's first pixel lost its left corners and half its glow (seen on the stue TV's
 * series page, *Resume*). This widens the scroll viewport by [bleed] into the gutter on both sides and
 * pads the content back by the same amount: every child is exactly where it was, and the clip lands
 * [bleed] further out — past the growth. The row reports its original width to its parent.
 */
fun Modifier.focusBleedScroll(state: ScrollState, bleed: Dp = FOCUS_BLEED): Modifier = this
    .layout { measurable, constraints ->
        val px = bleed.roundToPx()
        val widened = constraints.copy(
            minWidth = constraints.minWidth + 2 * px,
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + 2 * px else Constraints.Infinity,
        )
        val placeable = measurable.measure(widened)
        layout((placeable.width - 2 * px).coerceAtLeast(0), placeable.height) { placeable.placeRelative(-px, 0) }
    }
    .horizontalScroll(state)
    .padding(horizontal = bleed)
