# Phase 43 — Library page: design fidelity + infinite scroll (FR-LV1)

**Status:** Planned · _brings the Library page up to the `design/app/library.html` visual target and
replaces prev/next pagination with continuous infinite scroll, kept cheap via CSS + lazy images
(a JS virtualiser only as a measured fallback)._

## Problem
The Library page (`Library.kt`) has drifted from its design and has a paging model that misleads
operators:

1. **The Movies / TV switch looks broken.** `Library.kt` renders the segmented control as
   `<div class="seg"><button id="k-all">…</button>…</div>`, but `wf.css` styles **`.seg span`** and
   **`.seg span.on`** — the `<button>` children match none of that, so they fall back to default
   browser button chrome (boxy, mismatched, no active-segment fill). The design
   (`design/app/library.html`) uses `<span class="seg"><span class="on">Movies</span><span>TV</span></span>`
   with the proper pill/segment styling.
2. **Pagination hides entries behind a misleading last row.** The grid is
   `grid-template-columns: repeat(auto-fill, minmax(150px, 1fr))` — the column count is **dynamic**
   (e.g. 8 / 8 / 4 across three rows at one width). The list is fetched in **fixed pages of 20**
   (`loadLibraryPage` calls `MediaApi.list(..., pageSize = 20)`), so the final row of a page is
   usually **partial** (e.g. only 4 of 8 columns filled). That partial row reads as "the end of the
   library," even though a **next page exists**. Operators miss content.
3. **Pagination is the wrong model for a library that can hold thousands of items.** Prev/Next forces
   manual page-stepping; combined with the partial-row problem it's easy to think there's nothing
   more. Search is the primary way to *find* a specific item, but **scrolling** is how operators
   *browse*, and the current model fights that.

## Goal
- The Library page **matches `design/app/library.html`** — most visibly, the Movies / TV (kind)
  switch renders as the proper segmented control.
- Replace prev/next pagination with **continuous infinite scroll**: more items load automatically as
  the operator scrolls toward the bottom, so a partial last row is never mistaken for the end.
- Because the library can hold **thousands** of items, keep the page cheap **primarily via CSS +
  native lazy images** (`content-visibility: auto`, `loading="lazy"`) so off-screen cards and their
  TMDB images cost almost nothing — a custom JS virtualiser is a **fallback**, only if profiling shows
  it's still needed (see **C**).
- **Search stays the primary find path** (Phase 29 multi-language search, unchanged); scrolling is the
  primary **browse** path.

## Current state (as-is)
- `Library.kt`:
  - `renderLibrary` builds a static `#poster-grid` + a `#lib-pager` row.
  - `loadLibraryPage` requests **one page of 20** (`MediaApi.list(..., libPage, 20, …)`), replaces the
    whole grid's `innerHTML`, then `renderLibraryPager` draws "Page _n_ of _m_" + prev/next buttons
    that mutate `libPage` and reload.
  - `libPage` is tracked in state and serialised to the URL as `page=` (Phase 28).
  - During a scan, `connectScanSocket` / `appendItemToGrid` **appends** poster cards live as items are
    processed (a separate, append-only path from the paged render).
  - The kind switch is `.seg` with `<button id="k-all|k-movie|k-tv">`; the filter chips, meta-filter
    dropdowns (studio/network/genre/tags), and audio-track popover already work and are **out of scope
    to change** beyond the shared visual pass.
- Backend `GET /api/media` (MediaRoutes.kt → `MediaStore.list`) is already paginated: `page`,
  `pageSize`, returns `MediaPage(items, total, page, pageSize)`; sort is deterministic
  (`recently added` default, `title`, `year`). `meta-facets` / `track-facets` power the filters.

## Requirements

### A. Visual fidelity to `design/app/library.html`
1. **Kind switch (the headline fix).** Render the Movies / TV control so it actually receives the
   `.seg` styling: use the element type the CSS targets (`.seg` with `span` segments, matching the
   design) — or equivalently extend the CSS so the rendered control gets the segmented pill look with
   a filled **active** segment (`var(--hi-soft)` / `var(--acc-ink)`), shared dividers, and hover
   states. The control must be visually identical to the design's `#kindseg`.
2. **Keep the three-way All / Movies / TV behaviour** (the current functional default; the design's
   two-segment Movies/TV is a mockup simplification). "All" is the default selection and shows both
   kinds in one grid. Update `design/app/library.html` to include the **All** segment so design and
   app agree. _(If the product owner prefers the design's two-way Movies/TV with no combined view,
   that's a one-line change — but the default for this phase is to preserve All.)_
3. Run a **visual diff pass** over the rest of the page against the design: the search box + its
   "matches every title" note (Phase 29), the filter chips with counts, the sort control, the active
   audio/meta filter chips row, and the page-sub copy. Fix any token/spacing/structure drift found so
   the page reads as the design. (No behavioural change to the filters themselves.)

### B. Infinite scroll replaces the pager

> **Two layers, specified separately.** The **fetch layer** decides how many *items* are pulled from
> the API; the **render layer** (section C) decides how many *DOM cards* exist at once. They are
> independent — the fetch layer always appends to an in-memory item array; the render layer decides
> what of that array is in the DOM.

1. **Delete the prev/next pager** (`#lib-pager`, `renderLibraryPager`, the `page=` URL param, and the
   `libPage` stepping). The grid becomes a single continuously-growing list for the current
   filter/search/sort.
2. **Fetch on scroll.** As the operator scrolls toward the bottom, the next slice of items is fetched
   and appended to the item array, via an `IntersectionObserver` **sentinel placed after the last
   card** that fires **before** the last row is reached — so loading feels seamless and no partial row
   ever looks like the end. (The sentinel is the *fetch* trigger and lives at the true list end; it is
   independent of the render window in C.) Show a small **loading row** while a slice is in flight and
   an **"end of results"** affordance (or simply nothing) once the last item is loaded.
3. **Fixed slice size.** Each fetch pulls a **fixed slice** (~48–60 items) — large enough to fill
   several rows at any width — rather than trying to compute a slice from the dynamic column count. The
   sentinel keeps fetching until the viewport is satisfied and as the operator scrolls.
4. **Stable, gap-free ordering across slices.** Successive slices must not duplicate or skip items.
   The backend sort is deterministic; if `page`/`pageSize` offset paging proves unstable under live
   scan inserts, switch to a stable cursor — see **F**. The default plan reuses the existing
   `page`/`pageSize` contract with the fixed slice as `pageSize`.
5. **Reset on filter/search/sort change.** Any change to kind, filter chips, meta/audio filters,
   search text, or sort **resets the scroller to the top** and starts a fresh sequence (clears the
   item array + DOM, re-fetches from the first slice). The total-count label updates to the new count.
6. **Preserve empty + error states.** Zero results still shows "No items found."; a failed fetch still
   shows "Failed to load library." — both in the grid area, as today, not a blank page.
7. **Total count stays visible.** Keep the `… items` count (`#lib-total`) for the *current* filter
   set, independent of how many have scrolled into view.

### C. Keeping the page cheap (render layer)
1. **CSS-first is the baseline requirement.** Even with the whole result set fetched, the page must
   stay smooth because:
   - Poster `<img>` use **`loading="lazy"`**, so off-screen TMDB images are never requested.
   - `.poster` cards use **`content-visibility: auto`** + **`contain-intrinsic-size`** (matching the
     card's natural height), so the browser skips layout/paint for off-screen cards at near-zero code
     cost — and, unlike JS windowing, **find-in-page (Ctrl+F) still works**.
   These two together address the dominant costs (hundreds of image requests + layout) without a
   custom virtualiser.
2. **JS windowing is a fallback only.** If profiling a large real library (thousands of items) shows
   the CSS approach is still janky, *then* add a windowing layer that keeps only the cards near the
   viewport in the DOM (top/bottom spacers reserve scroll height; column count computed from container
   width ÷ `minmax(150px,1fr)` + gap, recomputed on resize/drawer changes). Do **not** build this
   pre-emptively — measure first.
3. The scroll container is the **main content column** (`.app-main2`) — sentinel and any windowing
   math are relative to it, not the window.
4. Clicking a card still navigates to `/media/{jellyfinId|id}` (unchanged).

### D. Coexist with live scan
1. During a scan, items currently **append live** via the WebSocket (`ItemScanned`). With infinite
   scroll this must still work: live-scanned items append to the same item array + grid (respecting
   the current sort/filter) rather than via a now-removed separate append path.
2. On **scan finish**, the list refreshes to the canonical first slice for the current filters (as it
   does today via `loadLibraryPage`).
3. The scan banner / "Scan library" button behaviour is unchanged.

### E. URL & navigation state
1. The URL keeps all filter/search/sort params (Phase 28) but **drops `page=`**. Returning to the
   Library via the URL restores the filters and starts scrolling from the top (scroll position itself
   is **not** required to persist across navigations — out of scope).

### F. Backend (only if needed)
1. The default plan needs **no backend change** — it reuses `GET /api/media?page&pageSize` with a
   larger `pageSize`. Note for the implementer: offset paging can shift if items are inserted/removed
   mid-scroll (e.g. during a live scan). If that causes visible duplicate/skipped cards, add a
   **stable keyset/cursor** parameter to `GET /api/media` (cursor over the deterministic sort key)
   and have the scroller pass it instead of `page`. Decide during implementation; prefer the
   no-backend-change path if scan-time reconciliation (D) is enough.

## Invariants
- **Frontend renders server-pushed state only** — the scroller displays whatever the list endpoint
  (and the scan WS) returns; no client-derived ordering or counts. (See `fe-reflects-be-no-derived-state`.)
- **Search is the primary find path**; scrolling is the primary browse path. Both must work for the
  full library, not just the first page.
- The Library page is a **visual match** to `design/app/library.html`.
- The page stays smooth at thousands of items via **CSS + lazy images** (`content-visibility` /
  `loading="lazy"`); a JS virtualiser is a measured fallback, not a default.

## Out of scope
- Changing the filter set, facets, or search semantics (Phases 20/29/30 stand).
- Saved/scroll-position restoration across navigations.
- Any change to Media Detail / Series detail.

## Design reference
`design/app/library.html` — update it to (a) include the **All** segment in `#kindseg`, and (b)
replace the prev/next footer with an infinite-scroll affordance (sentinel + "loading…" row, total
count retained), so the mockup matches this phase.
