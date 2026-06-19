# Phase R11 — Channel view + browse grids (FR-RV11)

**Status:** Planned · _scoped channels and the everything-grids._

## Problem
From Home, selecting a channel (HBO, Dansk TV, …) should open a view with the **same rows scoped** to
that channel; selecting a top-nav item (Movies / Series / My List) should open a **browse grid** with
filters. Both fully focus-navigable on TV + web.

## Current state (as-is)
- R10 (Home) routes to these screens. R06 provides `GET /api/tv/channel/{id}`, `GET /api/tv/browse`,
  `GET /api/tv/facets`.

## Requirements

### Channel view
1. A channel header (logo/name + brand-color treatment + "‹ Home" affordance) over a feed from
   `GET /api/tv/channel/{id}` — the **same row lineup as Home**, scoped to the channel; empty rows
   omitted; friendly empty state if the channel has nothing.
2. Back returns to Home with focus restored to the originating channel card.

### Browse grids (Movies / Series / My List)
3. A paged **poster grid** (≈6 columns, even flex; never overflowing) from `GET /api/tv/browse`, with
   a title + count.
4. A focusable **filter chip row** (All + genres/facets from `GET /api/tv/facets`) for Movies/Series;
   selecting a chip refilters in place and updates the count. (My List omits filters.)
5. Grid focus: left/right within a row, up/down between rows, the focused tile scrolls into view;
   paging loads more as focus nears the end.
6. Tiles open detail (R13). Back returns to Home/previous with focus restored.

## Invariants
- Channel views reuse the **same row set**, scoped — consistent with Home.
- Grid is a real responsive grid (equal columns, no overflow), focus-navigable.
- Server-pushed state; filters reuse Phase 30 facets via R06.

## Out of scope
- Search (R12), detail (R13), player (R14).
