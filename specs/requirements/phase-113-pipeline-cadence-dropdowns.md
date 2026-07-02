# Phase 113 — Scan-pipeline freshness cadences: real dropdowns + a Daily option (FR-SP2)

## Problem
In **Settings ▸ Libraries ▸ Scanning**, the *Scan media files* trigger block's three release-age
freshness controls (re-check cadence for this-year / 1–5 y / older titles) are **cycle buttons**: each
click silently advances to the next value (`Settings.kt:1630-1638`, cycling
`CAD_VALS = ["weekly","monthly","6months","yearly","never"]`, `:1439`). That is undiscoverable (nothing
signals there are 5 options), easy to overshoot, and slow to reach a distant value. It also lacks a
**daily** cadence — for this-year titles a daily TMDB re-check is a perfectly reasonable ask.

(For clarity: the *master schedule* control `#pipe-freq` is a separate segmented Daily/Weekly/Every-6h +
time-of-day control, `Settings.kt:137-143` → `computePipeCron()` — it already has "daily" and is fine;
this phase only touches the freshness-tier controls.)

## Requirements

### A. Dropdowns
1. Replace the three freshness cycle buttons (`cadY`/`cadM`/`cadO` in `pipeScanCfgEl()`,
   `Settings.kt:1589-1645`) with real **`<select>` dropdowns** (styled by the shared `wf.css` select
   rules), one per tier, showing all options at once. Same ids/round-trip
   (`refresh_this_year` / `refresh_1_5y` / `refresh_older` on `PipelineStep`, `AppConfig.kt:36-38`).
2. Keep the tier rows' labels/explainer text unchanged.

### B. Daily option
1. Add `daily` to the cadence values (FE option list + labels).
2. Backend: `cadenceMs()` (`Main.kt:310-317`) maps `"daily"` → 24 h. Unknown strings keep their current
   safe fallback behaviour.
3. Defaults are unchanged (`weekly` / `monthly` / `6months`) — daily is opt-in.

### C. Design mirror
`design/app/settings.html` shows the same three dropdowns incl. Daily, so the sync round-trips.

## Non-goals
- No change to the master schedule (`scan_schedule` cron derivation), pipeline step set, or freshness
  bucketing logic (`executePipeline`'s `freshnessFilter`, `Main.kt:321-364`) beyond the new value.
- No per-library cadences.

## Acceptance
- Each tier shows a dropdown listing Daily · Weekly · Monthly · Every 6 months · Yearly · Never; picking
  a value persists to `[[scan.pipeline]]` and survives reload.
- With this-year = Daily, an unchanged this-year title whose `last_checked` is >24 h old is re-checked
  by the next pipeline run (and skipped when <24 h).
- No cycle-on-click behaviour remains.
