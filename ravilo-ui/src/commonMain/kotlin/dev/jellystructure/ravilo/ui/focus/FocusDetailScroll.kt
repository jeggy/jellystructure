package dev.jellystructure.ravilo.ui.focus

import kotlin.math.max

/**
 * Phase R240 (FR-R240-9) — the row's own vertical scroll target, computed ONCE, after J's row-open
 * growth has settled (FR-R240-8's held-height ratchet has already stopped moving by then) — never
 * raced against the growth itself, which is exactly R232's hazard (a scroll computed against a
 * mid-tween size races the tween and either undershoots or fights it frame to frame).
 * [rowTopPx]/[rowFootPx] are the grown row's own top/bottom edges in window space; [screenHeightPx]
 * is the viewport height. Mirrors the spec's literal formula: `max(rowTop − peek, rowFoot +
 * footMargin − screenHeight)`. A result `<= 0` means the row already fits without moving — the
 * caller must not act on it (FR-R240-9's "the page never scrolls back up": this mechanism only ever
 * scrolls forward to reveal the grown row, it never corrects a row that already fits).
 */
fun focusDetailRowOpenScrollDelta(
    rowTopPx: Float,
    rowFootPx: Float,
    screenHeightPx: Float,
    peekPx: Float,
    footMarginPx: Float,
): Float = max(rowTopPx - peekPx, rowFootPx + footMarginPx - screenHeightPx)
