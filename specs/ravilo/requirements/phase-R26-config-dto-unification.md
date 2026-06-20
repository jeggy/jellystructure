# Phase R26 — Config DTO unification (FR-RV26)

**Status:** ✓ Done (2026-06-20)

## Problem
The admin frontend (`ravilo-web` / `wasmJsMain`) maintained parallel `Admin*` DTO classes that
duplicated `:shared`'s `RaviloConfig` hierarchy. Any schema change had to be made in two places.

## What was built
- Parallel `AdminRaviloConfig`, `AdminChannel`, `AdminRow` etc. classes deleted from `wasmJsMain`.
- `ravilo-web` now imports `RaviloConfig` and its nested types directly from `:shared`.
- `PUT /api/tv/admin/config` backend handler now validates incoming `RaviloConfig` JSON:
  - Returns `400 Bad Request` (was: `500`) when required `id` fields are missing or `order` values
    are out of range.
  - `RaviloConfigService.normalize()` clamps `heroHeightPct` to `[0.3, 1.0]` and
    `autoAdvanceSeconds` to `[0, 120]`.
- Backend generates stable `id` values on `Channel` and `Row` entries that lack one (UUID-v4),
  so existing configs saved without IDs round-trip cleanly.
