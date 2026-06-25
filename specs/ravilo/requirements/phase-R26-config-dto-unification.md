# Phase R26 — Config DTO unification (FR-RV26)

## Problem
The admin frontend (`ravilo-web` / `wasmJsMain`) maintained parallel `Admin*` DTO classes that
duplicated `:shared`'s `RaviloConfig` hierarchy. Any schema change had to be made in two places.

## What was built
- Parallel `AdminRaviloConfig`, `AdminChannel`, `AdminRow` etc. classes deleted from `wasmJsMain`.
- `ravilo-web` now imports `RaviloConfig` and its nested types directly from `:shared`.
- `PUT /api/tv/admin/config` backend handler now validates incoming `RaviloConfig` JSON via
  `RaviloConfigService.validate()`:
  - Returns `400 Bad Request` (was: `500`) when any `Channel`/`Row` `id` is **blank or duplicate**.
    Ids are **required and not backfilled** — the editor always supplies them; an explicit 400 is
    safer than silently minting ids that won't match the stored layout on the next edit.
  - `RaviloConfigService.normalize()` clamps `heroHeightPct` to `[30, 70]` and `autoAdvanceSeconds`
    to `[0, 120]`, and reassigns contiguous `order` on heroes/channels/rows.

> **Reconciliation note (review fixes, R29):** `heroHeightPct` is an **Int percent** (`30..70`, default
> 56), not a fractional `[0.3, 1.0]`, matching the `RaviloConfig` model. The earlier draft also claimed
> UUID-v4 id-backfill; that was never implemented — blank ids are rejected with 400 instead (above).
> `autoAdvanceSeconds`'s clamp was widened to `[0, 120]` in R29 to match this spec (it had been capping
> at 30s).
