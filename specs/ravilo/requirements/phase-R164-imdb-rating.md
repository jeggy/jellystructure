# Phase R164 — Ravilo TV: show the IMDb rating + vote count on the detail page

> When a title has an IMDb rating (the `imdbRating` field on the detail DTO from
> [Phase 131](../../requirements/phase-131-imdb-ratings-ingest.md)), show it **nicely on the Movie/Series
> detail hero** — a compact **IMDb ★ <rating> · <votes>** chip in the meta row. Titles without a rating
> simply don't show the chip. The value is **server-pushed** (stored + synced by Phase 131); Ravilo never
> calls imdbapi.dev.

**Status:** Planned — **design built**, app integration unbuilt.

## Problem
Ravilo's detail hero shows a certification badge, year and genre, but no **quality** rating. Viewers expect
the familiar **IMDb** number. The data now exists (Phase 131 stores `{ aggregateRating, voteCount }` per
title and carries it on the detail DTO); the TV just needs to render it.

## Architectural constraint (driving decision)
Ravilo renders **server-pushed state**: the rating arrives on the detail DTO (Phase 131), already stored and
periodically synced server-side. The client **never** queries imdbapi.dev and never derives the number — it
only formats and lays out what the feed provides (same class as the certification badge / audio flags).

## Current state (as-is)
**Design is built** in `design/ravilo/`:

- **`ravilo-data.js`** — an `IMDB` map (`{ id, rating, votes }` per title; notable titles have real-ish
  values, others derive deterministically so the demo reads as populated, ~30% have none) and `imdbFor(item)`
  on `window.RAVILO`. Stands in for Phase 131's `detail.imdbRating` DTO field.
- **`ravilo-app.js`** — `imdbHTML(item)` renders the chip into the detail hero **meta row** (after the
  certification badge), only when `R.imdbFor(item)` exists; `fmtVotes()` abbreviates the count
  (`24800 → 24.8K`, `1_200_000 → 1.2M`). The chip: an **IMDb** wordmark, a gold **★**, the rating to one
  decimal, and the abbreviated vote count; the full `rating/10 · N votes` is in the `title` tooltip.
- **`ravilo.css`** — `.imdb` / `.imdb-wm` (gold `#f5c518` mark) / `.imdb-star` / `.imdb-val` / `.imdb-votes`.

**The gap:** the Compose detail screens don't read an `imdbRating` DTO field or render a rating chip.

## Requirements

### A. Show the rating chip when present
1. On **Movie and Series** detail, when `detail.imdbRating` is present, render a compact chip in the hero
   meta row: an **IMDb** mark, a **★**, the **aggregateRating** (one decimal, e.g. `8.1`), and the
   **voteCount** (abbreviated: `24.8K`, `1.2M`). No rating ⇒ no chip.

### B. Formatting
2. Rating shows one decimal on the **0–10** scale (`7` → `7.0`). Vote count is abbreviated for legibility
   (`<1000` as-is; thousands `K`; millions `M`); the exact `rating/10 · N votes` is available on
   focus/long-press or as accessible text. For a **series** this is the **show-level** rating.

### C. Source
3. The chip renders **only** from the server-pushed `detail.imdbRating`; the client never calls imdbapi.dev
   and never computes the value.

## Invariants
- **Chip appears iff `imdbRating` is present** on the detail DTO.
- **Render-only** — no client-side fetch or derivation; the value is stored + synced by Phase 131.
- IMDb is shown as a plain **"IMDb" text mark + gold star**, not IMDb's logo artwork (data attribution).
- Series shows the **show** rating (no per-episode ratings).

## Out of scope
- IMDb rating on **poster tiles / rows / grids** (detail hero only this phase — a tile badge is a possible
  follow-up).
- **Other** rating sources (Rotten Tomatoes, Metacritic, TMDB) — IMDb only.
- Sorting/filtering Ravilo rows by rating.
- Any on-device fetch, refresh, or "rate this" affordance.

## Source references
- Design: `design/ravilo/ravilo-data.js` (`IMDB`, `imdbFor`); `design/ravilo/ravilo-app.js`
  (`imdbHTML`, `fmtVotes`, chip in the `.hero-meta` of `renderDetail`); `design/ravilo/ravilo.css`
  (`.imdb` / `.imdb-wm` / `.imdb-star` / `.imdb-val` / `.imdb-votes`).
- Backend / app: **[Phase 131](../../requirements/phase-131-imdb-ratings-ingest.md)** `detail.imdbRating`
  DTO field; a rating chip in the Compose detail-hero meta row (gated on the field).
- Related: **Phase 131** (imdbapi.dev ingest + store + sync + DTO — the data source), **R153** (the
  certification badge this sits beside), **constitution** (renders server state; no client-side derivation).
