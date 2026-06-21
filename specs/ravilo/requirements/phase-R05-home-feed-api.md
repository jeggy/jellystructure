# Phase R05 — Home feed composition (FR-RV5)

**Status:** ✓ Done · _the server composes what the TV renders._

## Problem
The Ravilo home screen (hero + channel rail + content rows) must be **composed server-side** from the
user's `RaviloConfig` + the library, and handed to the client as one ready-to-render feed. The client
must not invent rows or decide what "Continue Watching" means.

## Current state (as-is)
- After R04 the user's `RaviloConfig` is available. jellystructure already has the library, watched
  data (via Jellyfin user-data), meta-facets (Phase 30), and multi-language titles (Phase 29).
- No feed endpoint exists.

## Requirements

### `GET /api/tv/home`
1. Read the caller's `RaviloConfig`; return a **`HomeFeed`** = `{ heroes[], channels[], rows[] }` fully
   resolved to `MediaCard`s with Jellyfin **image URLs** (data plane) already filled in.
2. **Heroes:** the configured featured items (or an "auto" pick), each with backdrop + optional title
   **logo** + badge + kicker.
3. **Channels:** the configured channel buttons, each carrying its display name, style (LOGO/TEXT),
   brand color, optional logo URL, and the filter it represents (studio/network/genre/tag).
4. **Rows**, in the user's configured order/visibility:
   - **Continue Watching** = **merge of Continue + Next Up** — in-progress movies/episodes (with
     `progressPct`) **and** the next unwatched episode of started series (with `nextUpLabel`), sorted
     by recency. Computed server-side from Jellyfin user-data.
   - **Newly Added Movies** / **Newly Added Series**, or a single merged **Newly Added** row when
     `mergeNewlyAdded == true`.
   - **Genre rows** (5–20) per config, each a filtered slice.
5. Each row returns a bounded page of items (e.g. ≤30) with a flag/▶ for "see all" → browse (R06).
6. Performance: compose from existing stores/indices; no per-request filesystem walks. Cap work so the
   feed returns promptly on a large library.

## Invariants
- **Composition is server-side**; the client renders the feed verbatim — no client-invented rows.
- **Continue Watching is Continue + Next Up merged**; Newly-Added merge honored per config.
- Image/stream URLs in the payload point at **Jellyfin** (data plane).
- Server-pushed state only — `progressPct`/watched come from the feed, not client accumulation.

## Out of scope
- Channel-scoped feeds and browse/search (R06); detail (R07); playback (R08).
- The Home UI (R10).
