# Phase 19 — Studios, Networks, Genres & Tags (FR-M1)


## Problem
There's no way to browse media by studio, network, genre, or tag. The Media Detail tags section needs
polish (dropdown of known tags, colored dots). There's no structured tag system.

## Prerequisites discovered during investigation
See [`_investigation-findings.md`](_investigation-findings.md). **Two must be solved before the Studios/
Networks tabs have any data:**

- **P1 — populate `studio`/`network` from TMDB.** The scanner only maps `genres`; `studio`/`network`
  are always null unless manually edited. `TmdbClient` doesn't model `production_companies` /
  `networks`. Extend:
  - `TmdbMovieDetails` += `production_companies: [{ id, name, logo_path }]`.
  - `TmdbTvDetails` += `networks: [{ id, name, logo_path }]` (and optionally `production_companies`).
  - `Scanner.scanMovie` sets `studio` from the first/primary production company; `scanSeries` sets
    `network` from the first network (and `studio` if desired). Apply the same in `syncMovie`/
    `syncSeriesEpisodes`/`rescanMetadata`.
- **P2 — capture studio/network identity + logo for artwork.** TMDB has **no `/search/network`**
  endpoint; networks only appear inside TV details. So capture `{ id, name, logoPath }` at scan time
  rather than resolving by name later. Production companies are also searchable via `/search/company`
  if a backfill is needed.
  - **Decision for the plan:** either (a) store only the name on `MediaItem` (as today) and resolve
    id/logo on demand for studios via `/search/company` (works for studios, **not** networks), or
    (b) store structured `{name, tmdbId, logoPath}` for studio/network on `MediaItem`. **Recommended:
    (b)** — it's the only reliable way to get network logos. Add nullable
    `studioTmdbId/studioLogoPath/networkTmdbId/networkLogoPath` (or small structured fields) to
    `MediaItem`, populated by the scanner.

## New route and nav entry
1. New route `/metadata` → `wasmJsMain/.../ui/Metadata.kt`. Sidebar nav **"Metadata"** with a
   tag/label icon. Position it where "Language" used to be (Language is removed in
   [`phase-23`](phase-23-remove-language-page.md)), before "Settings".
2. Top tab bar: **Studios · Networks · Genres · Tags**, driven by `?tab=` (`/metadata?tab=networks`).
   Default: Studios. **Router work required** — `App.handleRoute` currently exact-matches routes;
   add `startsWith("/metadata")` + query parsing, and likewise make `/library?studio=…&network=…&genre=…`
   navigable (see Library filters below). See [`_investigation-findings.md`](_investigation-findings.md).

## Backend aggregation
3. New `/api/metadata` group (aggregated by iterating the media store at query time — no aggregation
   table):
   - `GET /api/metadata/studios?sort=name|count` → `[{ name, count, tmdbId?, logoPath? }]` from
     `MediaItem.studio` (+ captured id/logo). Excludes null studios.
   - `GET /api/metadata/networks?sort=name|count` → same shape from `MediaItem.network`. TV only.
   - `GET /api/metadata/genres?sort=name|count` → `[{ name, count }]` from `MediaItem.genres`.
   - `GET /api/metadata/tags?sort=name|count` → `{ jsTags: [{ name, color, description, count }],
     otherTags: [{ name, count }] }`. `jsTags` from the JS-tag store; `otherTags` = all `MediaItem.tags`
     not in the JS-tag set.
4. Library filters: `GET /api/media?studio=…`, `?network=…`, `?genre=…`. Extend `MediaStore.list`.
   The Library page must accept these via the URL (`/library?studio=Warner`).

## Studio / Network artwork (TMDB)
5. New endpoints to fetch + serve studio/network logos:
   - `POST /api/metadata/studios/{name}/artwork` (and `…/networks/{name}/artwork`) → resolves the
     TMDB logo (from captured `logoPath`, or `/search/company` for studios) and downloads it to a
     dedicated cache dir, e.g. `<data>/artwork/studios/<tmdbId or slug>.png`. Logos are transparent
     PNGs; base `https://image.tmdb.org/t/p/original`.
   - `GET /api/metadata/studios/{name}/artwork` → serve the cached logo (404 if absent).
   - A **batch** "fetch all missing studio/network logos" action (like `/api/media/batch/artwork`).
   - Networks: since there's no network search, only networks whose `logoPath` was captured at scan
     time can get artwork. Surface "no logo available" gracefully.

## Studios tab
6. Grid of cards (2–4 per row), each: studio **logo** (from the artwork endpoint; fallback to name
   text when no logo), studio name, item-count badge. Sort control: "A–Z" / "Most items". Click →
   `/library?studio={name}` (encoded). A "Fetch logos" action triggers the batch fetch.

## Networks tab
7. Same layout as Studios, for networks. Click → `/library?network={name}`.

## Genres tab
8. Compact chips (no artwork — user confirmed): genre name + count badge. Sort A–Z / Most items.
   Click → `/library?genre={name}`.

## Tags tab
9. Explanatory paragraph between the tab bar and content (always visible):
   > "Jellystructure tags are structured labels you define here with a color and description. They
   > survive metadata re-syncs — when pulling fresh data from TMDB, Jellystructure tags on an item are
   > always preserved. All other tags (below) come from TMDB or were added manually and may be
   > overwritten on resync."
10. **Section 1 — Jellystructure Tags**: header + "+ New tag". Each tag is a card: colored dot, name,
    description (1-line truncate), usage count. "+ New tag" modal: name, color (8 preset swatches),
    description → `POST /api/tags`. Click a card → edit modal (same fields + "Delete tag" ghost-red,
    `confirm()` → `DELETE /api/tags/{name}`); save → `PATCH /api/tags/{name}`.
11. **Section 2 — "And the rest"**: compact read-only chip list of all non-JS tags with counts. May be
    large (TMDB tags).

## Tags backend
12. **JS-tag store** — follows the JSON-file + atomic-write pattern (like `MediaStore`), e.g.
    `<data>/js-tags.json`. **Not SQLite** (none exists). Shape: `[{ name, color, description }]`,
    `color` default `#6b7280`, `description` default `""`, `name` unique (primary key semantics).
13. Routes:
    - `GET /api/tags` → all JS tags (no counts; counts come from `/api/metadata/tags`).
    - `POST /api/tags` `{ name, color, description }` — create; 409 if name exists.
    - `PATCH /api/tags/{name}` `{ color?, description? }` — update.
    - `DELETE /api/tags/{name}` — delete the **definition** only (does NOT strip the tag string from
      any `MediaItem.tags`).

## Media Detail — tags fix
14. Tags add/remove already works (chips, dirty tracking, `PATCH …/metadata` with `tags`). Enhance:
    - On Media Detail load, fetch `GET /api/tags` once (alongside the existing `ConfigApi.get()`); cache locally.
    - When the tag input is focused, show a dropdown of matching JS-tag names (substring filter).
      Free text (non-matching) is still accepted.
    - JS tags in the chip list render with a colored dot:
      `<span class="tag-dot" style="background:{color}"></span>` inside the chip; non-JS tags are plain.

## Sync invariant — tags
15. On `POST /api/media/{id}/sync`, `…/repull`, and `…/repull-jellyfin` (Phase 25):
    - Load the JS-tag name set from the JS-tag store.
    - Merge: `newTags = item.tags.filter { it in jsTagNames } + tmdbSourcedTags`.
    - Result: JS-defined tags always survive; non-JS tags follow TMDB (or clear if TMDB returns none).
    - TMDB v3 details don't currently return tags, so today this preserves only JS tags — the merge is
      future-proof.

## Suggested split (optional)
This phase is large. The plan may split it into **18a** (Studios/Networks/Genres incl. P1/P2 + artwork)
and **18b** (Tags: store, page, Media Detail dropdown, sync invariant). Track as sub-phases if helpful.
