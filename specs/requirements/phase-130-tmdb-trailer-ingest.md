# Phase 130 — TMDB trailer ingest + trailer on the detail page (YouTube / Vimeo)

> When pulling metadata from TMDB, also read the **videos** section and keep one **official trailer**
> (YouTube or Vimeo) per title. Store it on the item, show it on the **admin detail page** (Overview), and
> expose it on the **Ravilo TV detail DTO** so the companion app can offer a **▷ Trailer** button
> ([R163](../ravilo/requirements/phase-R163-trailer-playback.md) renders + plays it). Titles TMDB has no
> video for simply carry no trailer — every surface hides the affordance rather than showing a dead one.

**Status:** Planned — **design built** (admin `#trailer-card`), backend + DTO unbuilt.

## Problem
The scanner pulls title/plot/genre/cast/certifications/keywords from TMDB but **ignores `/videos`**. So
there is no trailer anywhere: the admin can't see or verify it, nothing is written for Jellyfin, and Ravilo's
detail hero already has a **Trailer** button that is a **dead stub** (it just flashes the title — no data
backs it). TMDB almost always has an official trailer, hosted on **YouTube** (usually) or **Vimeo**.

## Goal
At every point we fetch TMDB metadata, also fetch the **videos** list, pick **one** official trailer, and
persist a small reference on the item. Surface it read-only on the admin detail page and carry it on the TV
detail DTO. One trailer per title — not an extras gallery.

## Requirements

### FR-130-1 — Ingest one trailer from TMDB `/videos`
1. Add `TmdbClient.getMovieVideos(id)` / `getTvVideos(id)` (`/movie/{id}/videos`, `/tv/{id}/videos`) and call
   them at **every** Scanner build site that already fetches TMDB metadata (full scan · re-pull from TMDB ·
   the targeted single-item sync) — the same sites Phase 106 (certifications) / Phase 108 (timestamps) hook.
2. **Selection** (deterministic): keep results whose `site ∈ {YouTube, Vimeo}`; prefer `type == "Trailer"`,
   else `type == "Teaser"`; among those prefer `official == true`, then the item's **original language** then
   **English** (`iso_639_1`), then the **most recently published** (`published_at`). Take the first.
3. Store `MediaItem.trailer = { site: "youtube"|"vimeo", key, name }` — **additive, nullable**, same
   JSON-blob pattern as Phase 108 (no DB migration). **Re-pull from TMDB refreshes** it; when TMDB returns no
   usable video, store **null** (clears a stale one). A manual **Clear** sets null; **Re-fetch** re-runs
   selection against TMDB.

### FR-130-2 — Show it on the admin detail page (Overview)
4. A **Trailer** card on the Overview tab of **Movie** (`media.html`) and **Series** (`series.html`) detail:
   a 16:9 **thumbnail** (YouTube `img.youtube.com/vi/{key}/hqdefault.jpg`; Vimeo via the thumbnail proxy) with
   a play glyph linking to the video, the trailer **name**, a **provider badge** (YouTube / Vimeo), a
   **▷ Preview ↗** external link, and **Re-fetch from TMDB** / **Clear** actions. Show the raw `site · key`.
   When the item has no trailer, the card shows an **empty state** ("No trailer — TMDB had no usable video")
   with only **Re-fetch**.

### FR-130-3 — Expose it on the Ravilo TV detail DTO
5. Add `trailer` to `MovieDetail` / `SeriesDetail` (`{ site, key, name? }`, null when absent), populated by
   `DetailService` from the in-memory `MediaItem.trailer` — **catalog-only, zero Jellyfin round-trips** (the
   item is already loaded; same discipline as R81 cast / R83 catalog-only detail). R163 consumes it.

### FR-130-4 — NFO `<trailer>` (optional, decision)
6. Optionally write the resolved trailer as an NFO **`<trailer>`** URL on **Save → NFO** so Jellyfin also
   picks it up. Marked optional this phase — the primary consumer is Ravilo via the DTO, not Jellyfin.

## Invariants
- **One trailer per title** — official Trailer preferred, Teaser fallback; YouTube or Vimeo only.
- **TMDB-owned + refreshable** — re-pull overwrites; no usable video ⇒ null; never a broken affordance.
- **Additive/nullable field, no migration** (Phase 108 blob pattern).
- **DTO population is catalog-only** — no extra Jellyfin/TMDB call at detail-read time.
- Every consumer (admin card, Ravilo button) is **conditional on the trailer existing**.

## Out of scope
- **Hosting / downloading** the trailer, or proxying it through jellystructure — we store a **reference**
  (site + key), playback happens on the provider (Ravilo R163 embeds it).
- A **multi-video / extras** gallery (featurettes, clips, bloopers) — one trailer only.
- In-admin **inline playback** beyond opening the provider link.
- Trailer on Top 10 / Discover / Upcoming detail (those are not library items).

## Source references
- Design: `design/app/media.html` + `design/app/series.html` — `#trailer-card` (thumbnail · name · provider
  badge · Preview / Re-fetch / Clear) + `.tr-thumb` / `.tr-play` / `.tr-src` CSS.
- Backend: `TmdbClient.getMovieVideos`/`getTvVideos`; trailer selection helper; `MediaItem.trailer` (additive
  nullable); the Scanner build sites (full · re-pull · single-item sync); `DetailService` DTO population;
  optional NFO `<trailer>` writer.
- Related: **[R163](../ravilo/requirements/phase-R163-trailer-playback.md)** (Ravilo Play-Trailer button +
  fullscreen embed — the primary consumer), **Phase 106 / R153** (the ingest→DTO→render pattern this mirrors),
  **Phase 108** (additive JSON-blob fields, no migration).
