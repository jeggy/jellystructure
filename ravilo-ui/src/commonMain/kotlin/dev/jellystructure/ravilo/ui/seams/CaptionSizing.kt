package dev.jellystructure.ravilo.ui.seams

/**
 * R300 (FR-R300-1) — the height captions are sized against: the picture's, not the window's. In fit
 * mode the picture is the display-aspect box that fits the window; in fill mode, or before the first
 * frame tells us the aspect, it covers the window's height. All in the same unit as the inputs.
 */
fun captionBaseHeight(windowWidth: Float, windowHeight: Float, dar: Float, fill: Boolean): Float =
    if (dar <= 0f || fill) windowHeight else minOf(windowHeight, windowWidth / dar)
