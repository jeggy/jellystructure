# Phase R10 — Home screen (FR-RV10)

## Problem
Build the Ravilo **Home** — hero carousel, channel/collections rail, and the stack of content rows —
wired to `GET /api/tv/home`, rendering the **server-composed** `HomeFeed` with full focus navigation,
on both Android TV and web.

## Current state (as-is)
- R09 provides theme + components + focus engine. R05 provides `GET /api/tv/home`. No Home screen yet.

## Requirements
1. A `HomeStore` (coroutine `StateFlow`) loads `HomeFeed` via `TvApiClient`; the screen renders store
   state — **no client-invented rows**, no derived catalog state.
2. **Hero carousel** at the top (configurable height feel ~50–60%): backdrop + optional title logo +
   kicker + meta + synopsis + **Play / More Info / + My List**; auto-advances with manual
   left/right; dots indicator. Selecting Play → playback (R14); More Info → detail (R13).
3. **Channel rail** (Disney+-style): `ChannelCard`s (logo or styled text + brand color); focusing
   scales them; selecting opens the **channel view** (R11).
4. **Content rows** in server order: Continue Watching (landscape tiles with progress + next-up
   label), Newly Added (split or merged per feed), genre rows (poster tiles). Each tile focuses/zooms;
   selecting opens detail (R13); a "see all" affordance opens browse (R11).
5. **Focus/scroll**: down from the hero enters the rail/rows; rows lazy-load horizontally; vertical
   movement scrolls the focused row into a comfortable position; entering a row remembers the last
   column.
6. App bar with brand + top nav (Home/Movies/Series/My List) + search; selecting nav routes to the
   right screen.
7. Loading/empty/error states (skeleton rows while the feed loads; friendly empty copy).

## Invariants
- Renders the **server-composed feed verbatim**; Continue Watching and Newly-Added behavior come from
  the feed (R05), not the client.
- Identical behavior on Android TV and web; one shared implementation.
- Server-pushed state only.

## Out of scope
- Channel view + grids (R11), search (R12), detail (R13), player (R14).
