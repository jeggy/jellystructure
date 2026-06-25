# Phase 76 — Scope-aware season/episode cast & crew (Series · Season · Episode) (FR-CC2)

**Status:** ✓ Done · _design_

> Authored from the design project. Extends **[Phase 75](phase-75-cast-crew.md)** (cast & crew
> fetch + NFO + person images) with the season/episode model. Companion exploration:
> `design/app/Season Episode Cast.html`.

## Problem

Phase 75 added cast & crew but only at the **series level**. A series' people actually live at
three scopes — the recurring **main cast** (whole show), **guest stars** per episode, and
**episode crew** (director/writer) — and re-entering the main cast on every episode is
untenable. We need a model that mirrors how TMDB exposes credits and how Jellyfin reads NFOs.

## Model: define main cast once, inherit downward

The Series-detail **Cast & crew** tab gains a **Series · Season · Episode** scope switcher.

- **Series scope (default)** — the recurring **main cast**, sourced from TMDB
  `aggregate_credits` in TMDB's billing `order`; each card shows a `▸ N eps` badge
  (`total_episode_count`). Reorder / edit-role / add / remove; manual edits preserved across
  re-fetches (like JS tags). Plus series **crew** grouped by department. → `tvshow.nfo`.
- **Episode scope** — pick an episode; shows the **main cast inherited** from the series
  (read-only, faded with an "⤓ inherited" tag), then **guest stars** (editable, this episode
  only) and **episode crew** (director/writer). → that episode's `episodedetails.nfo`.
- **Season scope** — a **presence matrix** (see below), not a separate cast list. Jellyfin has
  no season-level cast file, so season edits cascade to that season's episodes.

### "What is main cast?" — answered in the UI

It is **not** a Jellystructure heuristic: main cast is **TMDB `aggregate_credits`**, used
verbatim and in TMDB's order, written to `tvshow.nfo` and inherited by every episode. The tab
states this inline so operators trust the source; manual curation is layered on top and
survives re-pulls.

## Presence matrix (Season scope)

A grid of **actors × columns** with a sub-picker:

- **All seasons** — columns are **S1, S2, …** and each dot carries a **number = how many
  episodes that person appears in that season** (purple = recurring, green = guest), plus a
  **Total** column. Surfaces a lead who thins out in a season, or a "guest" who actually
  recurs.
- **A single season** — columns become **E1…En** with on/off dots; **tapping a cell toggles**
  that actor on the episode's `episodedetails.nfo` (recurring → toggles series-cast presence
  for that episode; guest → adds/removes the guest). The fast fix for "TMDB missed a guest in
  E3".

## TMDB ingest

- Main cast ← `/tv/{id}/aggregate_credits` (cast `roles[]` with `episode_count`; crew `jobs[]`).
- Guest stars + episode crew ← `/tv/{id}/season/{n}/episode/{m}/credits` (`guest_stars[]`,
  `crew[]`). A per-episode **↻ Fetch this episode** and a tab-level **↻ Fetch from TMDB**.

## NFO sync

- `tvshow.nfo` ← main cast (`<actor>` with `<order>`, `<thumb>`).
- `episodedetails.nfo` ← inherited main cast (resolved for that episode) + guest stars +
  `<director>`/`<writer>`. Person `<thumb>` from the Phase 75 cached profile.
- Write-through (Phase 74): edits persist to the DB immediately; **Save → NFO** writes,
  **Sync Jellyfin** refreshes (the series pagebar's Save/Sync buttons, now relabelled to match
  Phase 74).

## "ⓘ" data-flow help popup

A circled **?** in the tab header reveals a hover popup describing the 4-step pipeline
**TMDB → Jellystructure → NFO → Jellyfin** (the same diagram from the exploration), so the
source-of-truth flow is discoverable in-context.

## Data model

- `Person` (Phase 75) gains episode scoping: main-cast members carry per-season episode lists
  (which episodes they appear in); episodes hold their own `guestStars: Person[]` and
  `crew: Person[]`.
- `EpisodeRecord` gains `guestStars` + `crew`; series keeps `cast` (main) + `crew`.

## Scope / invariants

- Main cast is **TMDB-sourced** and inherited; never re-entered per episode. Episode scope is
  inherited (read-only) cast + editable guests/crew.
- Season scope is a **view/cascade**, not a Jellyfin file.
- Reuses the shared person-search + Phase 75 image cache; write-through per Phase 74.

## Mockup

`design/app/series.html` + `design/app/series-cast.js` (scope-aware tab: Series cast,
presence matrix with All-seasons numbered view + per-season toggling, Episode inherited cast +
guests + crew, ⓘ help popup). Exploration: `design/app/Season Episode Cast.html`.
