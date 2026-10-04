# Phase R361 — Back to a title that is gone lands on its neighbour

> Owner, 2026-10-04: *"When in the recommended content row and opening a movie and marking it as watched and then
> going back, the movie gets removed from the list … then the focus is going bananas because this item doesn't
> exist anymore. Let's just make sure that even though the item is not there anymore, we should go back in a nice
> way."*

## Status

`✓ Built` 2026-10-04 (build notes at the end). Was `Planned` — written 2026-10-04 (dev-authored) from a D-pad sweep on the living-room Sony BRAVIA (release build
`1.49-11-g76f351b4`, the same code as `main` for these screens; first seen on `1.47-34`). Dev-reviewed 2026-10-04 against `main` `5210045a` (see the end; still `Planned`). Client
only (`:ravilo-ui`). The Recommended row's own rule (a watched title stays until the weekly build) is the server's,
written as a 2026-10-04 amendment to **phase 269**; this phase is what the client does whenever a title it returns
to is no longer there, whatever the reason.

**Supersedes, in part:**
- **R248 FR-R248-3**'s fallback (*"falls to the row's first tile when it does not [survive]"*) — see FR-R361-3.
- **R200 FR-RV-NAV1-1** stays (the restore always resolves); this phase says where focus then goes, which R200 left
  open, and closes the case R200 cannot reach (the whole row gone).
- **R187 FR-RV-BROWSE1-10** gains a rule for a tile that is no longer in the grid (FR-R361-4).

## What was seen

1. **Home, a mid-page row (Recommended, 5th row down).** Open the 4th tile → *Mark Watched* → Back. The tile is
   gone, nothing in the row is focused, the page stays scrolled where it was, and the focus ring is on the app bar's
   **Home** tab (top-left, off the part of the page being looked at). The next Down goes to the hero at the top of
   Home: the viewer has lost their place five rows down. One OK instead would have reloaded Home.
2. **Movies with *Watched: Unwatched*.** Open a film → *Mark Watched* → Back. The film has left the filtered grid
   (count 183 → 182), no tile is focused, and the ring is on the app bar's **Home** tab. Same on *Watched: Watched* →
   *un*mark.

**Cause (code-traced on `main` 76f351b4).**
- Home: the push `home_changed` (sent by `PUT /tv/played` before Back is pressed) has already refreshed the row.
  `StaticContentRow`'s restore (`ContentRow.kt:205-222`) finds `idx < 0`, requests nothing, and clears the keys;
  `HomeScreen.kt:245` skipped Home's own entry focus because a key was set. Nothing on the page asks for focus, so
  Compose's focus-loss recovery parks it on the first focusable node it finds: the app bar's first tab.
- If the **whole row** went away (a row whose last title left), no row with that id composes, `onRestored` never
  runs, and `focusRowKey`/`focusItemKey` stay set — so `HomeScreen.kt:245` skips entry focus on **every** later
  Home entry until another tile is opened (R200's leak, through a door R200 does not cover).
- The browse grid (`SeededBrowseScreen.kt:1025-1030`) restores only `if (items.any { it.id == restoreItemKey })`;
  otherwise nothing is focused, the entry focus is skipped (`freshEntry` false, `:478-480` skipped), and
  `store.focusItemKey` is never cleared (it dangles on the kept store until a sort change or the next select).
- When a refresh lands while the viewer is **in** the row (R248 FR-3), focus goes to index 0 with a one-shot
  `requestFocus()` and no scroll: if the row is scrolled right, tile 0 is not composed and focus is lost the same way.

## Requirements

### FR-R361-1 — One rule: the title now in its place
A Back-return (or a refresh) that aims focus at a title which is no longer in its row or grid focuses, in order:
1. the tile now at the **same position** in that row or grid (the one that slid into its place);
2. the **last** tile, if the title was at the end and the list got shorter;
3. if the row or grid is now **empty or gone**: on Home, the row now at that row's place in the page (the next row
   below, else the one above), its tile at the same position clamped to its length; in a browse grid, the facet
   bar's chip that the viewer used last (the grid's own empty state otherwise);
4. never the app bar, unless the page has nothing else focusable.
The target is **scrolled into view first, then focused** (R350 FR-2's order). A `requestFocus()` on an item that
may not be composed is never fire-and-forget: scroll, await the layout, then request (with the bounded retry the
codebase already uses, R257).

### FR-R361-2 — The page does not move under the viewer
The row keeps its vertical place on the page; the row's horizontal scroll moves only as far as needed to show the
focused tile. The Home page is never scrolled back to the hero by this path.

### FR-R361-3 — A refresh while the viewer is in the row (amends R248 FR-R248-3)
The focused title stays focused when it survives the refresh (unchanged). When it does not, FR-R361-1 applies
(same position, not index 0), with the scroll-then-focus of FR-R361-1.

### FR-R361-4 — The Movies / Series / seeded browse grid
Back to a grid whose opened title is no longer in the filtered list applies FR-R361-1 by **index in the filtered,
sorted list**. `focusItemKey` is cleared once the restore has resolved, found or not.

### FR-R361-5 — A pending restore always resolves, even when its row is gone
Home clears `focusRowKey`/`focusItemKey` once the first feed after a return has been laid out, whether or not a row
with that id composed (closes R200's leak for a vanished row). The fallback in FR-R361-1.3 needs the row's
**index** on the page, so the store remembers `focusRowIndex` and `focusItemIndex` beside the keys at select time.

### FR-R361-6 — Every surface that restores focus on Back follows the same rule
Home rows (incl. Continue Watching and Recommended), a collection's (channel's) rows, the browse grids (Movies,
Series, seeded browse, My List), Search results (R295 FR-1's *"R277's entry applies"* becomes FR-R361-1), the
Discover walls, and the series page's episode rail (R350 FR-2's *"if the card is gone, focus goes to Play"* stays —
it is a page with one obvious home). One helper in common code, not one per screen.

## Acceptance

1. Home → a mid-page row, 4th tile → *Mark Watched* → Back: focus on the tile now 4th in that row, page not
   scrolled; Down goes to the next row, not the hero.
2. Same with the row's **last** tile: focus on the new last tile.
3. A row with one title, marked watched (the row disappears): focus on the row now in its place, same column clamped;
   then open any tile and Back — the normal restore works (no leaked key).
4. Movies, *Watched: Unwatched*, 3rd tile → *Mark Watched* → Back: focus on the tile now 3rd; the count reads one
   less; the next Back-return with a surviving title restores as before.
5. Focus a tile scrolled far right in a row, then cause a refresh that removes it: focus lands on its neighbour, which
   is on screen.
6. 1–5 are walked key by key in Robolectric and their decisions are unit-tested; the retry helpers retry. See
   *Tests*.

## Out of scope

- Whether a watched title leaves Recommended at all (phase 269's amendment, 2026-10-04: only at the weekly build).
- The phone (no focus is drawn on a handset; R267/R298).

## Tests

Conventions as in `BrowseFocusTest`: Robolectric `@Config(sdk = [34], qualifiers = "w960dp-h540dp")`, plus
`-television` on a new class whose path reads `isTvPlatform` (as `SearchFocusTest` does), `fakeTvApiClient`, keys
through `rule.onRoot().performKeyInput { pressKey(…) }`, the focused node asserted with `isFocused()` + its text or
tag.
"Leave and return" is the test toggling the screen out of and back into composition over the **same** kept store
(`SeriesDetailFocusTest.renderLeavingForThePlayer`'s shape). Stand-in titles only.

**Prerequisite — the retry helpers retry (review item 3; also R362, R365 FR-6).**
- `FocusRetryTest` (new, Robolectric, `androidUnitTest/…/ui/focus/`), `rule.mainClock.autoAdvance = false`:
  - `requestFocusRetrying` on a requester whose box composes only after 3 frames → after `advanceTimeByFrame()` ×3
    the box is focused. **Red on today's code** (`isSuccess` stops after the first, failed try).
  - `requestFocusRetryingOrMoveNative` on a requester that never attaches, from a focused box above a box "below"
    → after 30 frames "below" is focused (the native `moveFocus(Down)` fallback runs).
  - A requester already attached → focused on the call, with no frame advanced.
- Source check (checklist): `grep -rn -A1 'requestFocus() }' ravilo-ui/src | grep -E '\.(isSuccess|isFailure|onFailure)'`
  finds nothing (7 hits on `5210045a`: the two helpers, the `repeat(10)` loops in `SeededBrowseScreen` and
  `TaxonomyScreen`, and `ChannelScreen`'s two-line `.onFailure`, R365 FR-6).

**Decisions (`commonTest`, `FocusReturnTest`, new, `…/ui/focus/`).**
- `fallbackIndex`: `(3, 10) → 3`; the end of a shorter list `(9, 9) → 8`; `(5, 2) → 1`; `(0, 0) → null`.
- `resolveReturn`: the title survives (also at a new index) → the same row and title; gone → the same row's tile at
  the same index; gone from the end → the new last; the row gone → the row now at `rowIndex` (the one below that
  slid up), column clamped to its length; the row gone and it was the last row → the row above; a row present but
  empty counts as gone; every row empty → `null`.

**Robolectric walks.**
- `HomeFocusTest` (new, `…/screens/`; the fake answers `/api/tv/home` with six stand-in rows, a second fetch drops one
  title; a `home_changed` refresh is the store reloading):
  - FR-1/2/5, acceptance 1: Down ×5 → row 5, Right ×3 → tile 4, OK; feed drops tile 4; return → the focused node is
    the tile now 4th; the hero is not displayed; Down → a tile in row 6.
  - Acceptance 2: the row's last tile, same walk → the new last tile.
  - Acceptance 3: a one-title row, the feed drops it → focus on the row now in its place, same column clamped; then
    OK on another tile, return with an unchanged feed → that tile (the keys were cleared: `focusRowKey == null`
    after the first resolve).
  - FR-3, acceptance 5: Right ×12 in a long row, refresh without that tile while it is focused → the tile now at that
    index is focused **and** `assertIsDisplayed()`.
  - Review item 2's late timing: return on the old feed (restore succeeds), then the refresh drops the title → as 5.
- `BrowseFocusTest` gains (FR-4, acceptance 4): Right ×2 → "Stand-in 3", OK, the fake drops it, return → "Stand-in
  4" focused, the count reads one less, `store.focusItemKey == null`; OK on it, return unchanged → "Stand-in 4"
  again. The fake answers an **empty** grid on return → focus on the facet bar's last-used chip (inside
  `SEEDED_FACET_BAR_TAG`), never the app bar.
- My List's grid twin (FR-3 for grids) is walked in R364's `MyListFocusTest`; Search's `fallbackIndex` in
  `SearchReturnTargetTest` (a result gone → its neighbour); the Discover wall's in `DiscoverFocusTest` (a tile gone
  → its neighbour).

**TV only (manual, D-pad on the TV).** Home, 5th row, 4th tile → *Mark Watched* → Back: the row stays at its height
on screen and the next Down goes to row 6. Then the same with Back pressed at once and with Back after 5 s (the order
of `home_changed` against Back on a real network). Continue Watching: finish an episode's last minutes → Back.

## Dev review (2026-10-04, against `main` `5210045a`)

Read against `StaticContentRow` (`ContentRow.kt`), `HomeScreen`/`HomeStore`, `ChannelScreen`, `SeededBrowseScreen`
(`BrowseCardGrid`), `BrowseScreen` (`BrowseGrid`), `FocusModifiers.kt` and the Compose UI 1.9.4 `FocusRequester`
bytecode. No `:ravilo-ui` code changed between `76f351b4` and `5210045a`, so the spec's line numbers hold. The
design holds. Eleven items, none for the owner.

1. **The causes are confirmed.**
   - Home: `ContentRow.kt:205-222` requests nothing when `idx < 0` and calls `onRestored()`, which clears the keys.
     `HomeScreen.kt:245` had already skipped Home's entry focus because `store.focusItemKey != null`.
   - The whole row gone: no row with that id composes, so no `onRestored()` runs and the keys stay set. They are
     cleared only by a later tile select (`HomeScreen.kt:354/625/722`) or the hero's `onOpenDetail` (`:317`).
   - Browse grid: `SeededBrowseScreen.kt:1025-1030` restores only when the id is still there; `:478-480` and
     `freshEntry` (`:486`) both skip entry focus; `focusItemKey` is cleared only by a sort change (`:571`).
     `BrowseScreen.kt:421-425` (My List) has the same shape.
   - In-row refresh: `ContentRow.kt:189-196` sends focus to `firstFR`, a requester on lazy item 0 (`:408`). With the
     row scrolled right, item 0 is not composed and the request does nothing.
   Where focus lands after the focused tile leaves is Compose's own recovery (seen: the app bar's first tab). The fix
   must not depend on it. The next Down goes to the hero because the app bar's `onDown` scrolls Home to 0 first
   (`HomeScreen.kt:402-412`).

2. **Two timings, both real.** `home_changed` usually lands before Back (the spec's case: the row is already short
   when Home composes). It can also land after Back. Then the R139 restore succeeds on the old list and the title
   leaves while it is focused, which is FR-R361-3's path. Both paths must use the same rule. Acceptance 5 covers the
   second.

3. **The "bounded retry the codebase already uses" does not retry (shipped bug, fix first).** In Compose 1.9,
   `FocusRequester.requestFocus()` on a requester with no attached node does not throw. It prints
   `FocusRelatedWarning: FocusRequester is not initialized` and returns `false`. The no-argument `Unit` overload is
   synthetic (hidden), so Kotlin calls the `Boolean` one. `requestFocusRetrying` and
   `requestFocusRetryingOrMoveNative` (`FocusModifiers.kt:56/60/81/85`) test `runCatching { … }.isSuccess`, which is
   `true` whether focus moved or not. So they stop after the first try. The 30-frame retry (R200 FR-RV-NAV1-2) and
   the native fallback (R236 FR-R236-2) never run. Change the test to `runCatching { fr.requestFocus() }.getOrDefault(false)`
   in both helpers (and in the two `repeat(10)` loops at `SeededBrowseScreen.kt:1036` and `TaxonomyScreen.kt:146`).
   FR-R361-1's retry is then that helper. (The spec cites R257 for it; it is R200/R236's.)

4. **One pure resolver, called once, before the rows compose.** Add to common code:
   - `fallbackIndex(oldIndex: Int, newSize: Int): Int?`: same index, else the last one, else `null` (empty).
   - `resolveReturn(rows: List<Pair<String, List<String>>>, rowKey, itemKey, rowIndex, itemIndex): Pair<String, String>?`:
     the same tile if it survives; else that row's `fallbackIndex`; else the row now at `rowIndex` (below, else
     above) with the column clamped; else `null`.
   Home calls it once on entry (`remember`, from the ordered list of rows it is about to lay out: channels,
   content rows, On Now at its index). It clears the store's keys at once (FR-R361-5), then passes the resolved item
   as `restoreItemKey` to the resolved row only. The rows' own R139 effect then does the scroll and the focus. This
   replaces "clear when `onRestored` fires" as the thing that ends a restore, so a vanished row can no longer leak
   the keys.

5. **Indexes at select time.** `HomeStore` and `ChannelStore` gain `focusRowIndex` and `focusItemIndex`, set
   where `focusRowKey` is set today (`HomeScreen.kt:354/625/722`, `ChannelScreen.kt:279`). The grids store the
   index in the list as laid out (`filtered` in `SeededBrowseScreen`, `items` in `BrowseScreen`) beside
   `focusItemKey`.

6. **The R139 restore needs to await the scroll and move the row only as far as needed (FR-R361-2).** Today it calls
   `scrollToItem(idx)` and then `restoreFR.requestFocus()` straight away (`ContentRow.kt:210-211`). The item is
   composed on the next measure, so the request can arrive first. `scrollToItem(idx)` also puts the tile at the row's
   left edge even when it was already on screen. Do it like the R248 effect: scroll only if the key is not in
   `visibleItemsInfo`, then use the fixed `requestFocusRetrying`. On Home, if the fallback row is not composed in the
   `LazyColumn` (rare: the row below usually slid up into view), scroll the page just enough to show it first.

7. **FR-R361-3 replaces `firstFR` with a key-targeted requester.** Target `items[fallbackIndex(prevIdx, size)]`,
   where `prevIdx` comes from `prevItems` (already held, `ContentRow.kt:182`). Attach a `targetFR` to the item with
   that key, the same way `restoreFR` is attached. Scroll it in if it is not visible, then request it with the fixed
   helper. Remove the item-0 `focusRequester(firstFR)` (`:408`): it is a lazy-item requester, the R200/R236 pattern.
   Empty row: hand off to the Home-level resolver's "row now in its place".

8. **The grids (FR-R361-4).** `BrowseCardGrid` and `BrowseGrid` compute their restore id from `fallbackIndex`
   when the stored id is gone, and clear `focusItemKey` once resolved, found or not. Clearing it also fixes a shipped
   bug (see R362's review, item 2): while the key stays set on the first tile, `firstCellFR` is attached to nothing.
   An empty grid sends focus to the facet bar through R362's FR-1 resolver (the last chip used). My List has no
   facet bar; there it goes to the avatar (R364 FR-3).

9. **FR-R361-6, where each surface gets it.** Channel rows use `StaticContentRow`, so they get items 4–7 for free.
   Search uses its own return index (`SearchReturnTarget`, R295); give it `fallbackIndex`. The Discover walls restore
   in `TaxonomyScreen.kt:146`; give that `fallbackIndex` too. The series rail stays on R350 FR-2 (Play), as the spec
   says. "One helper" is the resolver plus `fallbackIndex`. Each screen keeps its own plumbing.

10. **Tests.**
    - `commonTest`: the resolver and `fallbackIndex` cover acceptance 1–5's decisions: same index, last, row gone
      (below, above, clamped column), empty.
    - Robolectric: `BrowseFocusTest` gains acceptance 4, with `fakeTvApiClient` answering without the title on the
      second fetch.
    - Home has no Robolectric focus test today. Add `HomeFocusTest` for 1–3 and 5 on the same fake, with a
      `home_changed` refresh between select and return and one while focused.
    - Device only: that the page does not move, and the order of `home_changed` against Back on a slow network.

11. **No conflict with the constitution.** This is its "never strand focus or trap the user" rule (§ Remote-first
    interaction). The handset is untouched: its grids pass `restoreItemKey = null` (`SeededBrowseScreen.kt:588`).
    With phase 269's 2026-10-04 amendment, Recommended no longer drops a watched title. This phase still covers
    Continue Watching, filtered grids and My List. ⚠ That amendment is not in `5210045a`: the design export in that
    commit deleted it from the phase-269 file. It needs restoring from `9d1dd502`.

## Build notes (2026-10-04)

Built as specified and as the dev review lays it out. Client only (`:ravilo-ui` commonMain).
- **The retry helpers retry (review item 3, first).** `FocusModifiers.kt`: new `FocusRequester.tryRequestFocus()`
  reads the `Boolean` (`runCatching { requestFocus() }.getOrDefault(false)`); `requestFocusRetrying` and
  `requestFocusRetryingOrMoveNative` use it, so the 30-frame retry (R200) and the native fallback (R236) run for the
  first time. The two `repeat(10)` loops (`SeededBrowseScreen`, `TaxonomyScreen`) use it too. New suspend
  `requestFocusAwaiting()` for code already in a coroutine. `ChannelScreen`'s `.onFailure` is R365 FR-6's.
- **One resolver** (`focus/FocusReturn.kt`): `fallbackIndex(oldIndex, newSize)` and `resolveReturn(rows, rowKey,
  itemKey, rowIndex, itemIndex)` (same title → same row's `fallbackIndex` → the row now in its place, below else
  above, column clamped; an empty row counts as gone).
- **Home** (`HomeScreen.kt`, `HomeStore.kt`): `HomeStore.rememberReturn(rowKey, itemKey, rowIndex, itemIndex)` keeps
  the indexes (FR-5). `HomeLoaded` resolves once on entry against the ordered rows (channels, content rows, On Now at
  its place), spends the store's keys at once (no more leak through a vanished row), and hands the resolved tile to
  its row only (`RowReturn`); a resolved row the column has not composed is scrolled in first, and a restore that
  never fires is dropped after 1 s rather than left armed. A row that vanishes (or empties) while it holds focus
  resolves to the row now in its place.
- **`StaticContentRow`** (`ContentRow.kt`): the R139 restore is keyed on the key, scrolls the row only when the tile
  is not visible (and now counts On Now's leading guide tile), then awaits the focus with the bounded retry
  (review item 6); `restoreItemIndex` falls back by index. The R248 in-row refresh targets
  `fallbackIndex(prevIdx, size)` through a requester on that item, scrolled in first; the lazy item-0 `firstFR` is
  gone (review item 7); an emptied row calls `onEmptiedWhileFocused`.
- **Collections** (`ChannelScreen.kt`): the same resolve-once on entry (`ChannelStore.rememberReturn`).
- **Grids** (`focus/GridFocus.kt`, used by `SeededBrowseScreen.BrowseCardGrid` and `BrowseScreen.BrowseGrid`): a
  restore lands on the opened tile, else the tile now at its index in the filtered, sorted list; the key is cleared
  once resolved, found or not (FR-4); a refresh that drops the focused tile lands on its neighbour (FR-3 for grids,
  R364 review item 4); an empty grid goes to the facet bar (Movies/Series/seeded) or the app bar (My List until
  R364 moves it to the avatar). The first cell's requester is now always on tile 0 (R362 review item 2).
- **Search** (`SearchReturnTarget.takeIndex`) and **the Discover walls** (`TaxonomyStore.lastSelectedIndex`) use
  `fallbackIndex`. The series rail stays on R350 FR-2.
- Tests (green): `FocusRetryTest` (Robolectric: a box composing after 3 frames is focused; the native fallback moves
  focus after 30 frames; an attached requester focuses at once; `tryRequestFocus`'s two answers; a scrolled-in
  item), `FocusReturnTest` (commonTest: `fallbackIndex` and every `resolveReturn` case in *Tests*), `HomeFocusTest`
  (new, Robolectric, six stand-in rows + a hero: acceptance 1, 2, 3 incl. the keys not leaking, 5, and review item
  2's late timing — all five were checked **red on the old `HomeScreen`/`ContentRow`** before the fix),
  `BrowseFocusTest` (a gone tile → the tile now 3rd, count one less, key spent, a later return restores; an empty
  grid → the facet bar), `DiscoverFocusTest` (a gone wall value → its neighbour), `SearchReturnTargetTest` (a gone
  result → its neighbour). My List's grid twin is walked in R364's `MyListFocusTest`.
- Source check: `grep -rn -A1 'requestFocus() }' ravilo-ui/src | grep -E '\.(isSuccess|isFailure|onFailure)'` now
  finds only `ChannelScreen`'s `.onFailure`, which R365 FR-6 removes.
- Device only: the page not moving on the TV, and the order of `home_changed` against Back on a slow network
  (Back at once / after 5 s), Continue Watching after finishing an episode.
