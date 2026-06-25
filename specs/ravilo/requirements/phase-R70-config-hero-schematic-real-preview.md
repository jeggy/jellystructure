# Phase R70 — Real hero artwork in the Ravilo-config schematic preview (FR-RV-CP1)

## Problem
In the Jellystructure Ravilo config page, the **schematic layout preview** renders the hero carousel as a
raw **item id over a static/random gradient** — ugly and uninformative — even though the hero item's real
artwork is already on hand.

## Findings (premise correction: the data is already there; only one preview surface is broken)
- `HeroConfig` (`shared/.../tv/Models.kt:250-264`) already stores **admin display hints** alongside the
  `itemId`: `displayTitle`, `displayMeta` (e.g. "Series · HBO · 2019"), and **`displayBackdrop`** (the
  item's TMDB backdrop path) — explicitly "stored so the editor renders without extra lookups."
- These are **reliably populated**: all three add-hero paths capture `displayBackdrop = item.backdropPath`
  (`RaviloConfig.kt:780-795,1416-1417`), and `resolveHeroDisplayHints` (`:502-536`) back-fills legacy
  heroes by paging `MediaApi.list` and indexing by `jellyfinId`/`id`.
- **Three of the four** hero preview surfaces already render the real backdrop, falling back to a gradient
  only when `displayBackdrop` is blank — the hero-item list (`renderHeroes` `:538-574`), the add-hero
  picker (`:722-725`), and the page-hero editor (`:1364-1367`), all via
  `background:url('https://image.tmdb.org/t/p/w300${h.displayBackdrop}') center/cover,$bg`.
- **The one broken surface is the R28 schematic preview** (`renderPreview`, `RaviloConfig.kt:1915-1938`):
  it draws the hero block as a **fixed `linear-gradient(120deg,#7b6ef0,#3fb6f5)` with the raw `itemId` as
  the label** — ignoring `displayBackdrop` and `displayTitle` entirely:
  ```kotlin
  val heroLabel = cfg.heroes.firstOrNull { it.enabled }?.itemId?.takeIf { it.isNotBlank() } ?: "Hero"
  …background:linear-gradient(120deg,#7b6ef0,#3fb6f5)…<span>${heroLabel.htmlEsc()}</span>
  ```
- No backend/API change needed — the image URL is the standard app-wide
  `https://image.tmdb.org/t/p/<size><path>` convention, and the data is already on `HeroConfig`.

## Goal
The schematic preview's hero block shows the **actual hero backdrop** (or poster) with the **title**, not
an id + gradient — matching the other three preview surfaces.

## Requirements
1. In `renderPreview` (`RaviloConfig.kt:1915-1938`), take the first enabled hero and, when
   `displayBackdrop` is non-blank, render the hero block with
   `background:url('https://image.tmdb.org/t/p/w300${h.displayBackdrop}') center/cover, <gradient>` (use a
   larger size like `w780` for the bigger schematic if it reads sharper), overlay **`displayTitle`** (not
   `itemId`) with the existing text-shadow treatment, and fall back to `heroGradient(h.itemId)` only when
   no backdrop exists. Reuse the exact pattern already in `renderHeroes` `:538-574`.
2. (Optional) If a poster is preferred over the backdrop for the schematic, look the hero up in the cached
   `MediaApi.list` items by `jellyfinId`/`id` and use `posterPath` — but `displayBackdrop` is already
   stored, so no extra fetch is required for the default.

## Scope
- `src/wasmJsMain/.../ui/RaviloConfig.kt` — `renderPreview` hero block only. (The other three surfaces are
  already correct.)

## Non-goals
- No backend/API/model change (`displayBackdrop`/`displayTitle` already exist and are populated).
- Not reworking the channels/rows parts of the schematic here (hero is the ask); they can follow the same
  pattern later if wanted.

## Acceptance
- Open the Ravilo config with a hero configured: the schematic preview's hero block shows the real
  backdrop image with the item's title, not a raw id over a flat purple→blue gradient. A hero with no
  resolved backdrop still falls back gracefully to the per-item gradient.
