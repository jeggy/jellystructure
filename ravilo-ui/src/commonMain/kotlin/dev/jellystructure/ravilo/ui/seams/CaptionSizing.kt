package dev.jellystructure.ravilo.ui.seams

/**
 * R300 (FR-R300-1, amended 2026-10-25) — the height captions are sized against: that of a 16:9 frame
 * fitted inside the window. The screen decides, never the film's shape: neither the display aspect
 * ratio nor fit/fill enters the calculation, so a 2.40:1 film on a TV sizes its captions like a 16:9
 * episode. All in the same unit as the inputs.
 */
fun captionBaseHeight(windowWidth: Float, windowHeight: Float): Float =
    minOf(windowHeight, windowWidth * 9f / 16f)
