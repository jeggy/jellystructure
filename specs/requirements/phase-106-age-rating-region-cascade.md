# Phase 106 — Age ratings: region-cascade config + TMDB ingest + detail display + workbench facet (FR-AR1)

## Goal
Fetch each title's **content certification (age rating)** from TMDB and surface it in Ravilo and the admin
detail page. Because TMDB carries a **different certification per country**, add a global
**region cascade** (an ordered list of countries) in **Settings → Metadata**: Jellystructure resolves the
rating to store & show by walking the cascade top-to-bottom and using the **first region that has a
certification** for that title. Make the resolved rating **filterable** in the workbench.

The name "region cascade" is deliberate — it mirrors the existing **language cascade** (walk an ordered
list, take the first that resolves), so admins meet one consistent mental model.

## Background
- TMDB exposes certifications via **`/movie/{id}/release_dates`** and **`/tv/{id}/content_ratings`**,
  keyed by ISO-3166-1 country (e.g. `DK` → Medierådet `15`, `US` → MPA `R`). A title often has ratings
  for only a handful of countries.
- Jellystructure already resolves **metadata language** by an ordered cascade; this phase adds the analogous
  **age-rating region cascade** as a sibling global metadata setting.
- **Renders server state only** — the client never derives the rating; Jellystructure resolves it at scan
  time and stores the resolved value (plus the raw per-country map) on the item.

## Requirements

### A. Global config — Settings → Metadata
1. Add an **"Age ratings — region cascade"** card: an **ordered, editable list of countries**. Each row
   shows the ISO code, country name, its rating system (Medierådet/MPA/BBFC/FSK/…), and the system's scale.
   Support **reorder** (▲▼ / drag), **remove**, and **add region** (from a catalog of supported countries).
   The **first** region is visually marked as the default winner ("shown by default").
2. Persist as an ordered list of ISO country codes on the global config, e.g.
   `metadata.ageRatingCascade = ["DK","US","GB"]`. Empty list ⇒ feature off (show the title's own primary
   certification only).
3. It is a **global** setting (like the language fallback). Per-library override is **out of scope** here.

### B. Ingest from TMDB (scan/refresh)
1. During TMDB pull, fetch the per-country certification map for each title (movies: `release_dates`;
   series: `content_ratings`). Store the **raw map** (`{ "DK":"15", "US":"R", … }`) on the item record.
2. Best-effort: a missing/failed certifications call leaves the map empty and never blocks the scan.
3. Re-resolve (C) whenever the map or the cascade changes; the freshness/refresh pipeline already re-pulls
   TMDB for newer titles (ratings included).

### C. Resolve the shown rating (region cascade)
1. Resolve `shownCertification = { region, code, system }` by walking `ageRatingCascade` and taking the
   first region present in the item's raw map. If none of the cascade regions is present, fall back to the
   item's own primary certification (any available region) and flag it as a fallback.
2. Store the resolved value on the item so the TV feed and workbench read a single field (no client-side
   cascade). Expose a **0–4 maturity tier** derived from the code for badge colour + range filtering.

### D. Admin detail page (`media.html` / `series.html`)
1. Show a compact **certification badge** (region tag + code, colour-coded by maturity tier) in the pagebar.
2. Show an **"Age rating — region cascade"** trace card: each cascade region in order, marked
   used / skipped (no certification) / not-reached, so the admin can see *why* a given rating was chosen.

### E. Filter workbench facet
1. Add an **"Age rating"** facet to the shared workbench (`RaviloBuilders.FACETS`), filtering on the
   **resolved certification code**. It appears in "Add filter" everywhere the workbench is used (Library,
   Ravilo config channels/rows), with the same active-chip / save-as-channel/row round-trip as other facets.

## Scope
- `design/app/settings.html` — Metadata tab: the region-cascade card + reorder/add/remove UI (**built**).
- `design/app/media.html` (+ `series.html`) — pagebar cert badge + cascade-trace card (**built** on media).
- `design/app/ravilo-builders.js` — `ageRating` facet + `certCode` resolver (**built**).
- `design/ravilo/ravilo-data.js` — `CERT_SYS`, `config.ageRating.cascade`, `itemCerts`, `ratingFor`
  (**built**); `design/ravilo/ravilo-app.js` + `ravilo.css` — the `.cert` badge (**built**, see R153).
- Backend: TMDB client (certifications endpoints), item record (raw map + resolved), config
  (`ageRatingCascade`), TV feed + workbench condition wiring.

## Non-goals
- Per-library or per-user cascades (global only this phase).
- Parental-control / content-locking behaviour — this is **display + filtering** only, no gating.
- Icon artwork per national system — a colour-coded code badge, not official rating logos.
- Editing a title's certification by hand (it's TMDB-sourced; manual override is a later phase).

## Acceptance
- Settings → Metadata has a reorderable region cascade; the top region is marked as the default winner.
- After a scan, each item carries its raw per-country certifications and a resolved `{region, code}`.
- Ravilo detail and the admin detail page show the resolved certification badge; the admin page shows the
  cascade trace (used / skipped / not-reached).
- Moving Denmark below the United States in the cascade changes the shown rating for titles that have both.
- "Age rating" is selectable in the workbench and filters the grid, with removable active chips.
