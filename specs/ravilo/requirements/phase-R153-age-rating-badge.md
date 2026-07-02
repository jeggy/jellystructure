# Phase R153 — Age-rating certification badge on the series/movie detail hero

> Consumes the resolved age rating from **[Phase 106](../../requirements/phase-106-age-rating-region-cascade.md)**
> (region-cascade config + TMDB ingest, jellystructure-side). Ravilo only **displays** the value the
> server already resolved — no client-side cascade.

## Goal
Show a title's **content certification** (age rating) as a nice badge on the Ravilo **detail hero**, so a
viewer sees at a glance whether something is `A` / `PG` / `15` / `18`, etc.

## Requirements

### FR-R153-1 — Certification badge
In the detail hero meta row (beside HD · year · genre), render a **certification badge** for the resolved
rating: a small two-part chip — the **region code** (e.g. `DK`) next to the **certification code**
(e.g. `15`) — colour-coded by a 0–4 **maturity tier** (all-ages → adult: green · blue · amber · orange ·
red). The region code makes clear *which* country's system the rating comes from (the cascade winner).
Tooltip: region name · rating system (and "(fallback)" when no cascade region had a rating).

### FR-R153-2 — Source of the value
The badge reads the server-resolved rating (Phase 106): the raw per-country map + the resolved
`{ region, code, tier }`. Ravilo never re-runs the cascade or guesses. When the item has no certification
at all, render nothing.

## Implementation

| Layer | File | Change |
|---|---|---|
| Data | `ravilo-data.js` | `config.ageRating.cascade`; `CERT_SYS` (region → name/system/scale); `itemCerts(item)` (raw per-country map, deterministic in the mock; server-supplied in prod); `ratingFor(item)` (walks the cascade → `{ region, regionName, system, code, tier, fallback? }`). Exposed on `window.RAVILO`. |
| UI | `ravilo-app.js` | Detail hero builds `certHTML` from `R.ratingFor(item)` and renders it in `.hero-meta`. |
| Style | `ravilo.css` | `.cert` badge (`.cert-rg` region tag + `.cert-code` colour by `.lvl-0…4`). |

### Design reference (already built)
`design/ravilo/Ravilo TV.html`: Nordvest → `DK 15` (Denmark present), Havets Hjarta → `US PG-13` (Denmark
absent → cascade falls to the US), Big Buck Bunny → `DE 0` (no cascade region → last-resort fallback).

## Non-goals
- No certification badge on content-row poster tiles this phase (detail hero only).
- No cascade logic in the client — it consumes Phase 106's resolved value.
- No official national rating-system logos — a colour-coded code badge.
