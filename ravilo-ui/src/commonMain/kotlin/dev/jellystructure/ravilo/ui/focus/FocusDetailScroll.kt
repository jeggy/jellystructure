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

/**
 * J's row's own HORIZONTAL scroll target — the bug this fixes: native per-tile bring-into-view only
 * ever accounts for the focused TILE's own bounds, never the panel item spliced in right after it
 * (they're two separate `LazyRow` children), so a tile focused near the right edge of the screen
 * brought itself fully on screen while its panel — everything the whole feature exists to show —
 * rendered off the right edge of the viewport, invisible. Computed ONCE, after the panel's open tween
 * has settled (same R232-hazard discipline as [focusDetailRowOpenScrollDelta]: a mid-tween width
 * races the animation and either undershoots or fights it frame to frame).
 * [panelRightPx] is the open panel's own right edge in window space; [screenWidthPx] is the viewport
 * width; [marginPx] is the breathing room kept between the panel and the screen edge. A result `<= 0`
 * means the panel already fits without moving — the caller must not act on it (only ever scrolls
 * forward to reveal the panel, the same restraint [focusDetailRowOpenScrollDelta] applies vertically).
 */
fun focusDetailRowOpenHorizontalScrollDelta(
    panelRightPx: Float,
    screenWidthPx: Float,
    marginPx: Float,
): Float = panelRightPx + marginPx - screenWidthPx
