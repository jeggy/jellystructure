# Phase 43 — Library page: design fidelity + infinite scroll (FR-LV1)

> **As built:** the kind switch renders as `<span class="seg">` segments (All/Movies/TV) so it picks
> up the `.seg span.on` styling. The pager (`#lib-pager`, `renderLibraryPager`, `page=` URL param,
> `libPage`) is gone; `loadMore(scope, reset)` fetches fixed slices into `#poster-grid`, driven by an
> `IntersectionObserver` on a `#lib-sentinel` (700px rootMargin) **plus** a scroll/resize fill-loop
> (`elemNearViewportBottom`) so short slices keep loading until the viewport is satisfied. Auto-fetch
> resets on filter/search/sort/kind change. Cards use `loading="lazy"` + `.poster { content-visibility:
> auto; contain-intrinsic-size: 150px 300px }`; no JS windowing was needed. No backend change.

## Problem
The Library page has a paging model that misleads operators:

1. **The Movies / TV switch looks broken** when rendered as `<button>` children of `.seg` — `wf.css`
   styles `.seg span` / `.seg span.on`, so buttons fall back to default chrome. The design uses
   `<span class="seg"><span class="on">Movies</span>…</span>`.
2. **Pagination hides entries behind a misleading partial last row.** The grid column count is dynamic
   (`repeat(auto-fill, minmax(150px,1fr))`) but pages are fixed-size, so the final row of a page is
   usually partial and reads as "the end of the library" even though a next page exists.
3. **Pagination is the wrong model for a library of thousands.** Prev/Next forces manual stepping;
   combined with the partial-row problem it's easy to think there's nothing more. Search is how you
   *find*; **scrolling** is how you *browse* — and the current model fights that.

## Goal
- The Library page **matches `design/app/library.html`** — most visibly the Movies / TV switch as a
  proper segmented control.
- Replace prev/next pagination with **continuous infinite scroll**: more items load automatically as
  the operator scrolls toward the bottom, so a partial last row is never mistaken for the end.
- Keep the page cheap **primarily via CSS + native lazy images** (`content-visibility: auto`,
  `loading="lazy"`); a custom JS virtualiser is a **measured fallback** only.
- **Search stays the primary find path** (Phase 29); scrolling is the primary browse path.

## Requirements

### A. Visual fidelity to `design/app/library.html`
1. **Kind switch** renders so it actually receives `.seg` styling (segmented pill, filled active
   segment `var(--hi-soft)`/`var(--acc-ink)`, shared dividers, hover).
2. Keep the three-way **All / Movies / TV** behaviour; **All** is the default and shows both kinds.
   The design includes the **All** segment so design and app agree.
3. Visual-diff the rest of the page (search box + "matches every title" note, filter chips with
   counts, sort control, active-filter chip row, page-sub copy) and fix token/spacing/structure drift.
   No behavioural change to the filters.

### B. Infinite scroll replaces the pager
1. **Delete the prev/next pager** (`#lib-pager`, `renderLibraryPager`, `page=` URL param, `libPage`
   stepping). The grid becomes a single continuously-growing list for the current filter/search/sort.
2. **Fetch on scroll** via an `IntersectionObserver` **sentinel after the last card** that fires
   *before* the last row is reached, backed by a scroll/resize **fill-loop** so short slices keep
   loading until the viewport is satisfied. Show a **loading row** while a slice is in flight and an
   **"end of results"** affordance once the last item is loaded.
3. **Fixed slice size** (~48–60 items in the app; the design mock uses a smaller demo slice), large
   enough to fill several rows at any width — not computed from the dynamic column count.
4. **Stable, gap-free ordering across slices** (deterministic backend sort; cursor only if offset
   paging proves unstable — see F).
5. **Reset on filter/search/sort/kind change** — clears the item array + DOM and re-fetches from the
   first slice; the total-count label updates.
6. **Preserve empty + error states** in the grid area ("No items found." / "Failed to load library.").
7. **Total count stays visible** for the current filter set, independent of how many have scrolled in.

### C. Keeping the page cheap (render layer)
1. **CSS-first baseline:** poster `<img>` use `loading="lazy"`; `.poster` cards use
   `content-visibility: auto` + `contain-intrinsic-size` (matching card height) so off-screen cards
   cost ~nothing and find-in-page still works.
2. **JS windowing is a fallback only** — add it only if profiling a thousands-item library shows the
   CSS approach is still janky. Do not build pre-emptively.
3. The scroll container is the **main content column** (`.app-main2`).
4. Clicking a card still navigates to `/media/{jellyfinId|id}`.

### D. Coexist with live scan
1. Live-scanned items append to the same item array + grid (respecting sort/filter), not a separate
   path. 2. On scan finish the list refreshes to the canonical first slice. 3. Scan banner / button
   behaviour unchanged.

### E. URL & navigation state
1. URL keeps all filter/search/sort params (Phase 28) but **drops `page=`**. Returning via URL
   restores filters and starts from the top; scroll-position persistence is out of scope.

### F. Backend (only if needed)
1. Default needs **no backend change** — reuse `GET /api/media?page&pageSize` with a larger
   `pageSize`. If offset paging shifts under live-scan inserts, add a stable keyset/cursor over the
   deterministic sort key. Prefer the no-backend-change path.

## Invariants
- **Frontend renders server-pushed state only** — no client-derived ordering or counts.
- **Search is the primary find path**; scrolling is the primary browse path; both work for the full
  library.
- The Library page is a **visual match** to `design/app/library.html`.
- Smooth at thousands of items via **CSS + lazy images**; JS virtualiser is a measured fallback.

## Out of scope
- Changing the filter set, facets, or search semantics (Phases 20/29/30 stand).
- Saved/scroll-position restoration across navigations.
- Any change to Media Detail / Series detail.

## Design reference
`design/app/library.html` — the **All** segment is present in `#kindseg`, and the prev/next footer is
replaced with the infinite-scroll affordance (`#lib-sentinel` + `#lib-loadrow` "Loading more…" +
`#lib-end` "You've reached the end", with the `#lib-total` count retained). Cards carry
`content-visibility: auto`; slices append with a skeleton-shimmer placeholder per fetch.
