# Phase 30 — Library Multi-Axis Filters (studio / network / genre / tags) (FR-LMF1)

## Problem
Phase 19 added the **Metadata** page (Studios / Networks / Genres / Tags) and deep-links from there
into the Library (`/library?studio=…`). Phase 20 added the **audio-track** filter. But the Library
itself grew a full **multi-axis filter bar** — in-place dropdowns for studio, network, genre **and
tags**, each multi-select — that was never written up. `plan.md` only documented singular
`studio`/`network`/`genre` query params and no `tags` filter at all. This phase captures what the
Library filter system actually does.

## As-built behaviour

### Backend — `GET /api/media` query params (`MediaRoutes.kt` → `MediaStore.list`)
All filters are **combinable (AND across categories)**; the multi-value ones are comma-separated and
OR **within** a category. Matching is case-insensitive.

| Param | Type | Semantics |
|-------|------|-----------|
| `kind` | enum | `MOVIE` / `TV_SHOW` |
| `filter` | enum | `attention` (issueCount > 0 **or** `languageMix` **or** multiple-default audio) or `missing_artwork` |
| `search` | string | matches `title` + `originalTitle` + **all `titlesByLang` values** (Phase 29), in-memory |
| `sort` | enum | `title` / `year` / default `scannedAt` desc |
| `page`, `pageSize` | int | `pageSize` clamped 1–100 (default 20) |
| `studios` | csv | item's `studio` ∈ list |
| `networks` | csv | item's `network` ∈ list |
| `genres` | csv | any of item's `genres` ∈ list |
| `tags` | csv | any of item's `tags` ∈ list — **was previously undocumented** |
| `audioLang` | csv | item has an AUDIO track in one of these languages (Phase 20) |
| `trackTitle` | string | item has a track whose title contains this substring (Phase 20) |
| `audioCodec` | string | item has an AUDIO track with this codec (Phase 20) |
| `untaggedAudio` | bool | item has an AUDIO track with no language (Phase 20) |

The `attention` filter is evaluated **in-memory** (not via SQL) so multiple-default-audio items
(which have `issueCount == 0`) are included. For TV, the audio-track filters consider **all episodes'**
tracks, not just `item.tracks`.

### Backend — facet discovery endpoints
Both iterate the media store at query time (no aggregation table) and return distinct values with
**item counts** (not track counts), so the UI populates dropdowns instead of free-text:

- `GET /api/media/meta-facets` → `{ studios, networks, genres, tags }`, each `[{value, count}]`.
  **This endpoint powers the Library studio/network/genre/tags dropdowns and was undocumented.**
- `GET /api/media/track-facets` → `{ audioLanguages, audioCodecs, trackTitles }` (Phase 20).

> Note the overlap with Phase 19's `GET /api/metadata/{studios,networks,genres,tags}`: those return
> richer entries (tmdbId/logoPath, JS-tag color/description split) for the **Metadata page**, while
> `meta-facets` is the lightweight `{value,count}` shape for the **Library filter dropdowns**. Both
> exist; keep them distinct.

### Frontend — `Library.kt` filter bar
- Quick chips: **All**, **Needs attention**, **Missing artwork**.
- Dropdown popovers, each multi-select with counts from `meta-facets`: **Studio ▾**, **Network ▾**,
  **Genre ▾**, **Tags ▾**.
- **Audio track ▾** popover (language / track-title / codec / untagged-audio) from `track-facets`
  (Phase 20).
- Active selections render as removable chips; clearing one updates the grid.
- **Every** filter axis, plus `search`, `sort`, and `page`, is reflected in the URL query string and
  restored on load (Phase 28) — e.g. `#/library?genres=Crime,Drama&tags=nordic%20noir&kind=TV_SHOW`.
  Live search typing uses `replace=true` (no history spam); discrete changes push history.

## Requirements (as-built — must hold)
1. `tags` is a first-class Library filter param and dropdown, equal to studio/network/genre.
2. Facet dropdowns are populated from `meta-facets` / `track-facets`, never free-text, and show counts.
3. Empty facet categories hide their dropdown; an unscanned library shows a "scan to populate" hint.
4. All filter state is URL-addressable and round-trips (Phase 28); refreshing or sharing a link
   reproduces the exact grid.
5. Filters combine (AND across categories) and never block on a mixed-language series.

## Invariants
- **Frontend renders server-fetched state only** — the grid is whatever `GET /api/media` returns for
  the current query; the URL drives *which* query, not a second copy of the data.

## Out of scope
- Saved/named filter presets.
- Server-side aggregation tables (facets are computed per request; fine at this scale).
- Acting on filtered results in bulk (retag, set-default) — those remain per-item on media detail.
