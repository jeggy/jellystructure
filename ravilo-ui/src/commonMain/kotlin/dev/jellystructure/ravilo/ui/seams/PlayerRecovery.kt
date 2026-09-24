package dev.jellystructure.ravilo.ui.seams

/**
 * R292 (FR-R292-11, rung 2) — where the recovery ladder's seek goes. ExoPlayer ignores a seek to the
 * position it is already at, so the original `seekTo(positionMs)` never flushed anything; a step
 * back would clamp to 0:00 at the start (where the tracks-at-end stall sits), so below one second
 * the step is forward. Measured 2026-09-24: a seek that moves recovered a stalled picture in 1.5 s
 * where the same-position seek never did.
 */
fun recoverySeekTargetMs(positionMs: Long): Long =
    if (positionMs < 1_000L) positionMs + 1L else positionMs - 1L
