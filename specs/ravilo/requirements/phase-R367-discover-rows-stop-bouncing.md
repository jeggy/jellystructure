# Phase R367 — Discover's rows stop bouncing

> Owner, 2026-10-04: *"When on Discover Request and not the first content row, then it just jumps up and down, looks
> like it's because there isn't enough space on the screen and it gets stuck in this loop."*

## Status

`Planned` — written 2026-10-04 (dev-authored), reproduced the same day on the living-room Sony BRAVIA (release
`1.49-11-g76f351b4`). Dev-reviewed 2026-10-04 (section at the end — **the cause is corrected**: the row's own request
has no resting place, and the tile is a bystander; the fix is one pure distance function plus Discover's own top inset).
Client only (`ravilo-ui`).

## What was seen

Discover → Request → Down into the second row (*Trending*) and leave the remote alone: the page keeps scrolling up
and down by itself. Twelve `uiautomator` reads of the row heading's top over ~10 s, no key pressed: **524, 508, 499,
486, 499, 508, 499, 469, 499, 521, 524, 499 px**. It never settles. The first row is still.

## Cause (code-traced, to be confirmed by the fix's test)

Two bring-into-view requests reach the Request list's `LazyColumn` for one focus move:
1. `StaticContentRow` asks for the **whole row** (heading + tiles) whenever the row gains focus
   (`ContentRow.kt:242-246`, `rowBIVR.bringIntoView()`);
2. the focused **tile** asks for itself (the default focus bring-into-view).

Both go through `rememberEdgeBringIntoViewSpec(peekDp = 150, topInsetDp = 64, centerLineFraction = 0.3)`
(`RequestScreen.kt:102`, R140): scroll so the target's top is **no higher than 64 dp** and **no lower than 30 % of the
list's height**. On Home the list is the whole screen; on Discover it starts below the page title and the tab strip,
so 30 % of it is only a little below 64 dp — a band of about 40 dp. The row's top and the tile's top are further
apart than that, so no scroll position satisfies both: fixing the tile breaks the row, fixing the row breaks the tile,
and Compose's bring-into-view keeps animating between the two. The first row is pinned at the list's start, so the
fight has nowhere to go there.

`UpcomingContent` (Coming Soon) and `TaxonomyScreen` (the walls, `peekDp = 120`) use the same spec in the same
region and must be checked for the same loop.

## Requirements

### FR-R367-1 — A focus move scrolls once and then stops
For any focus move in Discover's content (Request, Coming Soon, the walls), the list reaches one resting position and
does not move again until the next key. The framing rule must have a solution for every request it receives: the
band is computed from the list's own height with a **minimum width larger than the distance from a row's top to its
tiles' top**, or the row and its tile ask for the **same** target (one request per focus move: the row's), so they
cannot disagree.

### FR-R367-2 — The framing Discover aims for
The focused row's heading sits just under the tab strip (64 dp inset, as today), its tiles fully visible, and the next
row's heading peeking below when there is room. When there is not room for the peek, the peek is dropped — never the
heading, never the tiles' captions.

### FR-R367-3 — One rule, tested for convergence
`calculateScrollDistance` is pure; a unit test applies it repeatedly (scroll by the result, recompute) for both
requests (row rect, tile rect) at Home's and Discover's list heights (960 × 540 dp TV, plus a 600 dp-tall desktop
window) and asserts it reaches **0** within two steps for every row index. The same test covers every screen that
calls `rememberEdgeBringIntoViewSpec`.

## Acceptance

1. Discover → Request → Down to the second, third and last row: the page settles within one scroll animation; ten
   reads of the heading's position with no key pressed are identical.
2. Same on Coming Soon and on each wall (Networks, Studios, Genres).
3. Home and a collection page frame rows as before (heading under the app bar, next row peeking).
4. The convergence test (FR-R367-3) fails on today's code and passes after the fix.

## Dev review (2026-10-04, against `main` `5210045a`)

Read against `BringIntoView.kt`, `ContentRow.kt`, `RequestScreen.kt`, `UpcomingScreen.kt`, `TaxonomyScreen.kt`,
`DiscoverScreen.kt`, `HomeScreen.kt`, `ChannelScreen.kt`, `Tile.kt`, `Dimens.kt`, and the bytecode of Compose
Foundation 1.9.3's `ContentInViewNode` (the version we ship). The symptom and the place are right. **The mechanism is
not what "Cause" says**, and the FR-R367-1 fix it suggests ("one request per focus move: the row's") would not stop
the loop. Ten items, one for the owner.

1. **Two requests that disagree do not loop in Compose 1.9.** `ContentInViewNode` keeps concurrent requests in one
   queue, nested ones ordered largest first. Each frame it aims at the largest request that fits the viewport (or the
   smallest one, if none fits). It completes a request only when that request's `calculateScrollDistance` is 0, and it
   ends the animation as soon as the current target's distance is 0. Two satisfiable requests with no common position
   therefore end in at most two moves. `ChannelScreen` does exactly this today: the same band, row and tile requests
   both on, and it does not bounce.

2. **The real cause: the row's request has no position at which its distance is 0.** Look at
   `rememberEdgeBringIntoViewSpec` (`BringIntoView.kt:46-62`). The third branch,
   `offset + size > containerSize -> offset + size - containerSize + peekPx`, is commented "(band off)" but is **not
   gated on the band**. So with the band on, a target whose top already rests in the band
   (`topPx ≤ offset ≤ centerPx`) but whose bottom is clipped is pushed down by the overflow **plus the 150 dp peek**.
   That throws its top above `topPx`, and the first branch then pulls it back. A target with
   `size > containerSize − topPx` has no resting offset at all:
   - above the inset, the distance is negative;
   - from the inset down, it is positive (at least the peek).

   The animation re-aims every frame and never finishes. It stops only when a scroll cannot be consumed. That is why
   the **first** row is still: its up-move hits the list's start, and Compose cancels the animation.

3. **Discover's numbers (TV, 960 × 540 dp, estimated from the code).**
   - The frame above the list is 84 (bar + 24) + 45 (title, subtitle) + 12 + 45 (strip) + 16 ≈ **202 dp**, so the
     Request list is ≈ **338 dp** tall. The band is [64, 101], about 37 dp, so the spec's "about 40" is right.
   - A Request row's rect (`StaticContentRow`'s `Column`; the spacer sits outside it) is: heading ≈ 24, + 10
     (`rowHeadPadB`), + 20 (`trackPadV`), + tile 232 + 8 + title ≈ 24 + year line ≈ 19, + 20 ≈ **357 dp**.
   - `357 > 338 − 64 = 274`, so the row has no fixed point. The tile alone (≈ 283 dp) is within a few dp of its own
     limit, and it is not what loops.
   - The device trace fits a sawtooth just under the row's inset boundary. The heading creeps down toward it, then the
     peek branch throws it up: no reading above 524 px, dips to 469.
   - On Home the list is 540 dp, and R108 (`HomeScreen.kt:339/583/684`) sends only the tile request. On channel pages
     the row (357) is below 540 − 162. Neither can reach the branch.

4. **Coming Soon does not use this spec.** `UpcomingContent` (`UpcomingScreen.kt:171`) provides no
   `LocalBringIntoViewSpec`, so it gets the platform default: `PivotBringIntoViewSpec` on Android TV,
   `DefaultBringIntoViewSpec` elsewhere. Both give every target a resting point, so Coming Soon should not loop. It
   frames rows its own way, though, and after R366 it is the page Discover opens on, so acceptance 2 stays.
   `TaxonomyContent` uses the edge spec but sends only tile requests (≈ 130 dp, far under 274), so the walls have a
   fixed point. Keep both in the convergence test.

5. **Fix 1: one pure distance function with a fixed point for every size.** Lift the body into
   `fun edgeBandScrollDistance(offset, size, containerSize, topPx, centerPx, peekPx): Float`, next to R250's
   `gutterBringIntoViewDistance`, and have `rememberEdgeBringIntoViewSpec` call it.
   - **Band on:** `val maxTop = max(topPx, min(centerPx, containerSize - size))`; return `offset - topPx` if
     `offset < topPx`, `offset - maxTop` if `offset > maxTop`, else 0. A target that fits rests with its top in the band
     and its bottom on screen. One that doesn't fit rests with its top exactly at the inset, with no peek (FR-R367-2
     allows dropping the peek).
   - **Band off** (the detail screens): keep the top branch. The bottom branch becomes
     `min(offset + size - containerSize + peekPx, offset - topPx)`, so a reveal never pushes the top above the inset.
     The same no-fixed-point flaw is latent there today for any focusable taller than `container − top − peek`
     (≈ 396 dp on a 540 dp detail page). Nothing that tall is focusable, so it has not been seen.

   Every target that has a fixed point today gets the same result as before: Home, channel and detail at their real
   sizes. Acceptance 3 therefore holds by construction. The function is pure (`commonMain`, no Compose), so
   FR-R367-3's test needs no UI.

6. **Fix 2: Discover's own inset, or the framing still clips.** With Fix 1 alone, the row rests with its top at 64 dp.
   On a 338 dp list that clips the posters' bottoms and both caption lines, which FR-R367-2 forbids. The 64 dp in
   `RequestScreen.kt:102` and `TaxonomyScreen.kt:154` was copied from Home, where it clears the **overlay** app bar. On
   Discover the list starts below the strip, and nothing overlays it. Set `topInsetDp = 0.dp` in both; the strip's
   16 dp bottom padding is the gap. Then:
   - the row (357, including the 20 dp of track padding under the captions) rests at 0, and its year line ends at
     ≈ 337 of 338;
   - the tile request (top at 54, `maxTop = min(101, 338 − 283 = 55)`) is satisfied at the same position.

   So the two requests agree. That is FR-R367-1's second option, reached through geometry rather than by dropping a
   request. Keep `bringRowHeaderIntoView = true` on Discover: the row request is what brings the heading in when you
   move up into a row.

7. **For the owner — the TV has no room to spare under Discover's header.** The header (Discover, its subtitle, the
   tabs) takes ≈ 202 of 540 dp. A Request row with heading, posters, title and year line needs ≈ 337 dp, and the list
   under the header is ≈ 338 dp. So the focused row fills the whole area under the tabs, and none of the next row
   shows. **Lean: accept it** (FR-R367-2 already says the peek goes first). The alternative is to let the header slide
   away once you move down into the rows, as Home's hero does. That changes R262 FR-R262-1's fixed frame and is a
   bigger change than this bug.

8. **Rewrite FR-R367-1 to match.** Replace the "minimum band width / one request" wording with: *every target,
   whatever its size, has a non-empty set of offsets at which the distance is 0, and one step reaches it*. Keep "one
   resting position per key".

9. **Tests.**
   - **(a) Pure** (`commonTest`, FR-R367-3's test). Cover each caller's parameters (Home and channel `124/0.3/150`,
     Request and the walls `0/0.3/150` and `0/0.3/120` after Fix 2, detail `84/0/60`), list heights of 540, 338 and a
     600 dp desktop window's list, and target sizes from 40 dp to 1.5 × the container. For each, iterate
     `offset -= f(offset)` from 50 starting offsets and assert `f == 0` after **one** step. Today's function fails the
     357-in-338 case (its sign alternates forever), which is acceptance 4.
   - **(b) Layout**, Robolectric `w960dp-h540dp`, like `DiscoverFocusTest`. Give `DiscoverStore` an `internal`
     fetch-lambda constructor, as R350 did for `TaxonomyStore`. Render Request with three rows, press Down into row
     2, set `mainClock.autoAdvance = false`, advance 2 s, read the heading's bounds, advance 2 s more, and assert:
     - the two readings are identical;
     - the year line's bottom is at or above the list's bottom.

     On today's code the readings differ, and `waitForIdle` never idles. This test also measures the real heights
     that item 3 estimates.
   - **Device only:** acceptance 1–2 on the TV (ten reads, all identical), and that real font metrics do not push the
     year line past the edge.

10. **Nothing else is involved.** Discover has no focus detail: `RequestContent` passes no `openPanel`, and
    R240/R242/R254 are Home rows. It does no polling: `DiscoverStore` refreshes only on live-config and acquisition
    pushes. The app bar is an overlay, so `appBarScrolled` changes no layout. No string, DTO or config change.
