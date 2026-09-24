package dev.jellystructure.ravilo.ui.screens

/**
 * The −10 s / +30 s arrow: a three-quarter arc opening at the top, arrowhead at the open end.
 * R257 (FR-R257-6) — back turns COUNTER-clockwise with its head at the upper left pointing left;
 * forward turns clockwise with its head at the upper right pointing right. R301 — one geometry,
 * drawn by the phone player and the cast remote alike, so they cannot disagree again.
 *
 * @property startAngle degrees, Compose's arc convention (0 = 3 o'clock, clockwise positive).
 * @property sweepAngle degrees; negative sweeps counter-clockwise.
 * @property tipSide −1 for a head on the left of the circle, +1 for the right; the head points that way.
 */
data class SkipArc(val startAngle: Float, val sweepAngle: Float, val tipSide: Int)

fun skipArc(back: Boolean): SkipArc =
    if (back) SkipArc(startAngle = 240f, sweepAngle = 270f, tipSide = -1)
    else SkipArc(startAngle = -60f, sweepAngle = -270f, tipSide = +1)
