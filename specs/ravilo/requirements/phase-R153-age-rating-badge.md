# Phase R153 — Age-rating certification badge on the series/movie detail hero

> Consumes the resolved age rating from **[Phase 106](../../requirements/phase-106-age-rating-region-cascade.md)**
> (region-cascade config + TMDB ingest, jellystructure-side). Ravilo only **displays** the value the
> server already resolved — no client-side cascade.

## Goal
Show a title's **content certification** (age rating) as a nice badge on the Ravilo **detail hero**, so a
viewer sees at a glance whether something is `A` / `PG` / `15` / `18`, etc.

## Current state (code)
- The TV DTOs already reserve the slot: `MediaCard.rating` (`shared/…/tv/Models.kt:123`) is sent as
  `null` by every service today; `MovieDetailScreen.kt:197-220` even renders it as plain meta text when
  non-null. `SeriesDetailScreen.kt:285-291` (meta row `year · genre`) has no rating slot yet.
- Phase 106 adds the backend resolution and serves: `MediaCard.rating` = resolved code, plus a
  `rating_badge { region, code, tier, fallback }` object on `MovieDetail` + `SeriesDetail`.

## Requirements

### FR-R153-1 — Certification badge
In the detail hero meta row (beside year · genre — `MovieDetailScreen.kt:197-220`,
`SeriesDetailScreen.kt:285-291`), render a **certification badge** for the resolved rating: a small
two-part chip — the **region code** (e.g. `DK`) next to the **certification code** (e.g. `15`) —
colour-coded by the server-supplied 0–4 **maturity tier** (all-ages → adult: green · blue · amber ·
orange · red). The region code makes clear *which* country's system the rating comes from (the cascade
winner). The movie meta row's current plain-text `rating` string is replaced by the badge.

### FR-R153-2 — Source of the value
The badge reads the server-resolved `rating_badge` on the detail DTO (Phase 106). Ravilo never re-runs
the cascade, never derives the tier, and never guesses. When `rating_badge` is null, render nothing (no
reserved gap — the meta row simply has one fewer chip; this is layout-static per the no-flicker rule
because detail data arrives in the single catalog payload, not late-hydrated).

## Implementation

| Layer | File | Change |
|---|---|---|
| DTO | `shared/…/tv/Models.kt` | `RatingBadge(region, code, tier, fallback)`; `rating_badge` on `MovieDetail` + `SeriesDetail` (nullable, defaulted). Backend populates in `DetailService` (Phase 106 D). |
| UI | `ravilo-ui/…/components/CertBadge.kt` (new) | Two-part chip composable; tier → colour map shared across skins. |
| Movie | `ravilo-ui/…/screens/MovieDetailScreen.kt` (meta row `:197-220`) | Drop `rating` from the joined text; render `CertBadge` beside the meta text. |
| Series | `ravilo-ui/…/screens/SeriesDetailScreen.kt` (meta row `:285-291`) | Render `CertBadge` beside `year · genre`. |

### Design reference (already built)
`design/ravilo/Ravilo TV.html`: Nordvest → `DK 15` (Denmark present), Havets Hjarta → `US PG-13` (Denmark
absent → cascade falls to the US), Big Buck Bunny → `DE 0` (no cascade region → last-resort fallback).
`ravilo-data.js` `ratingFor`/`CERT_SYS`/`itemCerts`; `ravilo-app.js` `certHTML`; `ravilo.css` `.cert`
(`.cert-rg` region tag + `.cert-code` colour by `.lvl-0…4`).

## Non-goals
- No certification badge on content-row poster tiles this phase (detail hero only — `MediaCard.rating`
  arrives with Phase 106 regardless, so a tile badge is a later one-liner).
- No cascade or tier logic in the client — it consumes Phase 106's resolved values.
- No official national rating-system logos — a colour-coded code badge.
- No tooltip on TV (no hover); the region tag itself carries the "which system" context.
