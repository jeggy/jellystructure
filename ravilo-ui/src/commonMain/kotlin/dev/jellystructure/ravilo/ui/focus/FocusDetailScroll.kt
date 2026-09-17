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
 * R257 (FR-R257-5) — the one case where the page DOES move back: the opened row's heading has ended up
 * under the app bar. Seen on the stue TV 2026-09-17 moving Down from an OPEN row into the next one:
 * bring-into-view parks the new row first, THEN the row above collapses (giving back ~60 dp of held
 * height) and drags the new row's heading up under the bar — and [focusDetailRowOpenScrollDelta], by
 * design, never corrects upward. Returns a NEGATIVE distance that brings the row's top down to
 * [minTopPx], limited so the grown row's foot (+ [footMarginPx]) is not pushed off screen — the foot wins
 * (FR-R240-9 unchanged). 0 when the heading is already clear or there is no room to give.
 */
fun focusDetailRowOpenHeadingDelta(
    rowTopPx: Float,
    rowFootPx: Float,
    screenHeightPx: Float,
    minTopPx: Float,
    footMarginPx: Float,
): Float {
    if (rowTopPx >= minTopPx) return 0f
    val wanted = rowTopPx - minTopPx                                   // < 0
    val footLimit = rowFootPx + footMarginPx - screenHeightPx          // most-negative distance the foot allows
    return kotlin.math.min(0f, max(wanted, footLimit))
}

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

/**
 * The same horizontal target, computed from KNOWN geometry instead of a measurement — the only form
 * that works before the panel has ever been placed (see
 * [dev.jellystructure.ravilo.ui.components.focusDetailPanelWidthFor]'s doc for why measuring it
 * first is circular).
 *
 * [openTileOffsetPx] is the opening tile's current left edge in the row's own scroll space and
 * [openTileWidthPx] its width *before* it grows — the grown width is derived here via
 * [widthScale] (FR-R240-7's `ROW_OPEN_WIDTH_SCALE`) rather than measured, since at the moment this
 * runs the growth animation has only just started. [viewportEndPx] is the row's own viewport end
 * (already net of the row's end content-padding, so that inset comes for free).
 *
 * **The result is clamped so the opening tile can never be scrolled past the row's own content start.**
 * That clamp is not a detail — it is the fix for a real reported bug: without it, a row whose panel
 * needs more room than exists to the tile's right scrolls far enough to push the tile itself off the
 * left edge, visibly clipping the poster. The amount of room needed depends on the tile's own width,
 * which differs per [dev.jellystructure.ravilo.ui.components.TileVariant] (Continue Watching's
 * LANDSCAPE tiles vs. a standard POSTER row), which is exactly why the clipping was reported as
 * happening on some rows and not others. Clamping here means the panel may stay partly off-screen on
 * a very narrow viewport — deliberately preferred over clipping the focused tile, since the tile is
 * the thing the viewer is actually pointing at.
 */
fun focusDetailRowOpenTargetScrollDelta(
    openTileOffsetPx: Float,
    openTileWidthPx: Float,
    widthScale: Float,
    itemSpacingPx: Float,
    panelWidthPx: Float,
    viewportEndPx: Float,
): Float {
    val grownTileEnd = openTileOffsetPx + openTileWidthPx * widthScale
    val panelEnd = grownTileEnd + itemSpacingPx + panelWidthPx
    val needed = panelEnd - viewportEndPx
    // Never scroll the opening tile past the row's own content start (offset 0 in this space).
    val maxWithoutClipping = openTileOffsetPx.coerceAtLeast(0f)
    return needed.coerceIn(0f, maxWithoutClipping)
}

/**
 * R250 (FR-R250-4) — how wide the panel may actually be once the opening tile has *landed*: the room
 * between the grown tile's right edge (plus the item gap) and the row's end gutter. Computed from the
 * tile's measured position after the scroll settled, not from where the row intended to put it — the
 * stue TV showed a tile landing 66 px right of the content start with the panel sized for a tile at
 * the content start, so the synopsis ran to the screen's last pixel column. A result below the
 * declared width narrows the panel; it is never widened past what was declared. [endGutterPx] is the
 * row's end gutter in the same (content) space as [landedTileOffsetPx] — i.e. `viewportEndOffset −
 * afterContentPadding`, **not** `viewportEndOffset`, which reaches the screen edge (the root cause of
 * FR-R250-5's 66 px: the target let the panel end at the screen edge, so the tile stopped short of the
 * content start by exactly the gutter it had been allowed to spend).
 */
fun focusDetailPanelAvailableWidthPx(
    landedTileOffsetPx: Float,
    grownTileWidthPx: Float,
    itemSpacingPx: Float,
    endGutterPx: Float,
): Float = endGutterPx - (landedTileOffsetPx + grownTileWidthPx + itemSpacingPx)

/** R250 (FR-R250-4) — the width the panel renders at: the declared width unless the room measured by
 *  [focusDetailPanelAvailableWidthPx] is smaller (beyond a 2 px rounding tolerance), never below
 *  [minPx], never above the declared width. */
fun focusDetailPanelClampedWidthPx(declaredPx: Float, availablePx: Float, minPx: Float): Float =
    if (availablePx < declaredPx - 2f) availablePx.coerceIn(minPx.coerceAtMost(declaredPx), declaredPx) else declaredPx
