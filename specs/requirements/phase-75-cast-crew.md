# Phase 75 — Cast & crew: TMDB people, detail-page editor, NFO sync + person images (FR-CC1)

> Authored from the design project (`design/app/media.html`, `design/app/series.html`).
> Builds on the write-through model of
> **[Phase 74](archive/phase-74-metadata-write-through.md)** and the artwork/logo image-cache
> pattern of **[Phase 31](archive/phase-31-studio-network-artwork.md)**.

## Goal

Jellystructure never tracked **people**. Add full cast & crew support: fetch people from TMDB,
edit them on the detail page, and sync them to Jellyfin via the NFO — **including person
photos** — for movies, series, and episodes.

## TMDB ingest

- Pull credits from TMDB `/{movie|tv}/{id}/credits` (and `/tv/{id}/season/{n}/episode/{m}/credits`
  for episode guest stars). Each person carries `id`, `name`, `profile_path`, plus `character`
  + `order` (cast) or `department` + `job` (crew).
- Ingest at **scan** and on an explicit **"↻ Fetch from TMDB"** action on the Cast & crew tab.
- Library-match is not needed — people are stored on the item.

## Data model

- New `Person { tmdbId, name, role?, job?, department?, order, type, profilePath }`.
- `MediaItem` (and episode records) gain `cast: Person[]` (ordered) and `crew: Person[]`.
- `type` ∈ `Actor` (cast) or the crew department's canonical type; `order` is the cast display
  order written to the NFO.

## Detail-page editor (movies + series)

A new **"Cast & crew"** tab on **Movie detail** (`media.html`) and **Series detail**
(`series.html`):

- **Cast** — a poster-style grid of person cards (photo, name, **editable character/role**),
  **drag-to-reorder** (order is written to `<actor><order>`), remove, and **＋ Add cast** via a
  **TMDB person search** popup.
- **Crew** — grouped by **department** (Directing, Writing, Production, Sound, Art, Camera, …),
  each row showing job + name with remove; **＋ Add crew** opens the same search popup with a
  department + job field.
- **Write-through (Phase 74):** every edit — add/remove a person, edit a role, reorder — writes
  to the library **DB immediately** (toast). The top **Save → NFO** writes the people into the
  NFO; **Sync** asks Jellyfin to re-read it. No staging.
- Series cast is **series-level** (`tvshow.nfo`); **per-episode guest stars** are written to
  each `episodedetails.nfo` (episode-level cast editing surfaces on the per-episode editor —
  the series tab covers the show-level set).

## NFO sync

`NfoWriter` emits, in cast `<order>`:

```xml
<actor>
  <name>Halina Reijn</name>
  <role>Sintel (voice)</role>
  <order>0</order>
  <type>Actor</type>
  <thumb>https://<jellystructure>/api/people/<id>/image</thumb>
</actor>
<director>Colin Levy</director>
<writer>Esther Wouda</writer>
```

- Cast → `<actor>` (name/role/order/type/thumb). Crew → `<director>`, `<writer>` (Jellyfin also
  reads `<credits>` for writers); other departments map to the elements Jellyfin recognises.

## Person images (the "across the board" part)

How Jellyfin shows a person photo: it reads the **`<thumb>`** URL inside each `<actor>` element
and **downloads the image into its own people metadata store** (`…/metadata/People/<initial>/<name>/folder.jpg`),
then serves it across its UI. So Jellystructure only has to write a **reachable `<thumb>` URL**.

- **Cache + serve** each TMDB profile like Phase 31 logos: download `profile_path`
  (`https://image.tmdb.org/t/p/w185…`), cache on disk keyed by TMDB person id, and serve at a
  stable `GET /api/people/{tmdbId}/image` route. The NFO `<thumb>` points at that route (stable,
  reachable by Jellyfin even if TMDB is rate-limited).
- The **same cached image** backs the person photos shown in the Jellystructure UI (cast grid,
  crew avatars) — one cache, used by our UI, the NFO, and therefore Jellyfin.
- Applies **across the board**: movies, series (`tvshow.nfo`), and episode guest stars
  (`episodedetails.nfo`) all write `<thumb>` from the same person-image cache.

## Scope / invariants

- Comparison/write forms follow Phase 74: DB on edit; NFO on Save; Jellyfin on Sync.
- Reuses the Phase 31 image-cache + serve pattern; no new image infra.
- Field locks (Phase 22) / drift (Phase 33) still apply to people written into the NFO.

## Mockup

`design/app/media.html` + `design/app/series.html` (Cast & crew tab — cast grid with
reorder/role-edit, crew by department, TMDB person-search popup, person-image explainer).
Person photos render as initial-avatars in the mockup; production loads the cached TMDB profile.
