# Phase R149 — Series detail + rows: surface Sonarr's next-airing episode for ongoing series

> When **Sonarr is configured** and a series has **not ended**, show that a new episode is scheduled:
> a full "Next episode · S·E · airs <date>" banner on the **series detail** page, and a compact
> **"Airing soon"** badge on the **series poster** in content rows. Ended series show nothing.

## Problem
Ravilo shows the catalogue it already has — episodes, watched/resume state, cast — but gives a viewer
no signal that an **ongoing** series has a new episode on the way. Sonarr already tracks each series'
status (continuing vs ended) and the air date of the next monitored episode; that information was never
surfaced in the TV UI. A viewer browsing a continuing series can't tell it isn't complete, and a poster
in a row looks identical whether the show is finished or actively airing.

## Architectural constraint (driving decision)
**Ravilo requests from Jellyfin as little as possible — the UI is served from jellystructure's own
data.** Next-airing info is **Sonarr-owned**, not Jellyfin-owned: jellystructure reads it from the
Sonarr API (series status + next-airing date) at scan/refresh time and stores it on the series record.
The TV detail + rows feeds then carry it through. No Jellyfin round-trip, no client-side derivation.

## Current state (as-is)
- **Design is built** in `design/ravilo/`:
  - `ravilo-data.js`: `config.sonarr` flag; `SERIES_STATUS` map (`{ ended, season, ep, title }` per
    series); `nextAiringFor(item)` → `null` unless `config.sonarr && item.kind === 'series' &&
    !status.ended`, else `{ season, ep, title, date (ISO yyyy-mm-dd), days }` (date computed relative to
    now in the mock so it always reads as upcoming). Exported on `window.RAVILO`.
  - `ravilo-app.js`: series **detail** episodes section renders a `.dnext.air` banner —
    `Next episode · S{n}:E{n} "{title}" · airs {full date} · Sonarr` — via `epAirLabel()` (UTC-pinned,
    locale month/day/year) + a `.dnext-src` Sonarr pill. Poster `tile()` renders a compact `.tile-air`
    badge (`upcomingLabel()` → `t('upcoming')`, accent dot) top-right when `nextAiringFor(item)` is set.
  - `ravilo-i18n.js`: `upcoming` ("Airing soon" / "Kommer snart" / "Kemur skjótt"), `next_ep`, `airs`,
    `via_sonarr` in en/da/fo.
  - `ravilo.css`: `.dnext.air`, `.dnext-src` pill, `.tile-air` + `.tile-air-dot`.
- **Sonarr is already a configured integration** in jellystructure (`[sonarr]`, read-only root-folder
  import + best-effort rescan, alongside Radarr). Its series **status / next-airing** fields are not yet
  read into the catalog record or the TV feeds.
- **Backend is the gap**: the Sonarr client, the series catalog record, and the TV detail/rows DTOs do
  not yet carry next-airing data.

## Requirements

### A. Read next-airing from Sonarr (ingest)
1. When `[sonarr]` is configured, read each series' **status** (`continuing`/`ended`) and **next-airing
   date** (next monitored, unaired episode incl. its season/episode number + title when available) from
   the Sonarr API during scan/refresh. Store on the series catalog record. Best-effort: a Sonarr outage
   leaves the field null, never blocks the scan.

### B. Carry it on the TV DTOs
2. Add an optional `nextAiring` to the TV **series detail** model: `{ season: Int, episode: Int, title:
   String?, airDate: String /* yyyy-MM-dd */ }`, nullable + defaulted. Add the same (or a `boolean
   hasUpcoming` + `airDate`) to the **row/poster** series tile DTO so rows can render the badge without
   the full detail payload.
3. Populate both only when **Sonarr is enabled AND the series has not ended AND a next-airing date
   exists**. Otherwise null.

### C. Render — detail page
4. In the series detail episodes section, when `nextAiring != null`, show the banner: localized
   `Next episode` · `S{season}:E{episode}` · optional `"{title}"` · `airs {formatted date}` · a `Sonarr`
   source pill. Date formatting is locale-aware and **UTC-pinned** (calendar day must not shift by
   timezone). When null, render nothing.

### D. Render — content-row poster
5. On series poster tiles in content rows, when the series has an upcoming episode, show a compact
   **"Airing soon"** badge (top-right, accent dot). The badge is a **short fixed label** — NOT the date
   — so it never overflows the poster; the full date lives on the detail page only. When null, render
   nothing.

## Invariants
- **Jellyfin is not consulted** for status or next-airing — Sonarr-sourced, jellystructure-stored,
  server-pushed. Renders server state only; the TV never derives or guesses the date.
- **Gated on `config.sonarr`.** With Sonarr disabled, neither the banner nor the badge ever appears
  (mirrors the Radarr/Discover gating in `discoverEnabled()`).
- **Ended series show neither** banner nor badge — the feature is strictly for continuing series.
- Date stored/transported as date-only ISO; all display formatting UTC-pinned.
- Poster badge copy is a short fixed label (`upcoming` i18n key), never a date — overflow-safe. The
  banner carries the precise date.

## Out of scope
- A countdown / "in N days" timer, calendar reminders, or notifications when the episode airs.
- A dedicated "Upcoming" / "Airing this week" browse row or screen (display-on-existing-surfaces only).
- Auto-requesting or grabbing the upcoming episode (Sonarr already handles acquisition) — this is
  display only, no fetch affordance.
- Movie equivalents (theatrical/digital release countdowns) — series only this phase.

## Source references
- Design: `design/ravilo/Ravilo TV.html`; `design/ravilo/ravilo-data.js`
  (`config.sonarr`, `SERIES_STATUS`, `nextAiringFor`); `design/ravilo/ravilo-app.js`
  (series detail `.dnext.air` banner, `tile()` `.tile-air` badge, `upcomingLabel`, `epAirLabel`);
  `design/ravilo/ravilo-i18n.js` (`upcoming`/`next_ep`/`airs`/`via_sonarr`); `design/ravilo/ravilo.css`
  (`.dnext.air`, `.dnext-src`, `.tile-air`, `.tile-air-dot`).
- Backend: Sonarr client + config (`[sonarr]`); series catalog record; TV `DetailService` + row/tile
  feed builders; shared TV `Models.kt` (series detail + tile DTOs);
  `ravilo-ui/.../screens/SeriesDetailScreen.kt` + the row/poster tile composable.
- Related: **R148** (episode air dates — same Sonarr/TMDB date-display spirit, episode picker),
  **Phase 54 / Discover** (`config.radarr` gating pattern this mirrors for `config.sonarr`),
  **R48–R50** (Radarr-backed request/fetch surfaces — sibling integration UX).
