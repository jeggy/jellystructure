# Phase R361 — Back to a title that is gone lands on its neighbour

> Owner, 2026-10-04: *"When in the recommended content row and opening a movie and marking it as watched and then
> going back, the movie gets removed from the list … then the focus is going bananas because this item doesn't
> exist anymore. Let's just make sure that even though the item is not there anymore, we should go back in a nice
> way."*

## Status

`Planned` — written 2026-10-04 (dev-authored) from a D-pad sweep on the living-room Sony BRAVIA (release build
`1.49-11-g76f351b4`, the same code as `main` for these screens; first seen on `1.47-34`). Not dev-reviewed. Client
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
