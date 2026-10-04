# Phase R361 — Back to a title that is gone lands on its neighbour

> Owner, 2026-10-04: *"When in the recommended content row and opening a movie and marking it as watched and then
> going back, the movie gets removed from the list … then the focus is going bananas because this item doesn't
> exist anymore. Let's just make sure that even though the item is not there anymore, we should go back in a nice
> way."*

## Status

`Planned` — written 2026-10-04 (dev-authored) from a D-pad sweep on the living-room Sony BRAVIA (release build
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
6. Robolectric walks for 1–5 on the TV path (`isTvPlatform`), with a fake feed that drops the title between select
   and return, and one that drops it while focused.

## Out of scope

- Whether a watched title leaves Recommended at all (phase 269's amendment, 2026-10-04: only at the weekly build).
- The phone (no focus is drawn on a handset; R267/R298).

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
