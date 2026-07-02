# Phase R149 — Series detail + rows: surface Sonarr's next-airing episode for ongoing series

> When **Sonarr is configured** and a series has **not ended**, show that a new episode is scheduled:
> a **"Next episode · S·E · airs <date>"** line in the **series-detail hero** (visible without scrolling,
> no source attribution), and a compact **"Airing soon"** badge on the **series poster** in content rows.
> Ended series show nothing.

**Status: Planned — reopened, placement-only.** The feature shipped once (badge + banner + full backend);
the design has since moved the next-airing line into the series-detail **hero** (above the fold) and the
source attribution was already removed. **Only the line's placement remains to be built.**

## Problem
Ravilo shows the catalogue it already has — episodes, watched/resume state, cast — but gives a viewer
no signal that an **ongoing** series has a new episode on the way, *until they scroll*: the line renders
inside the episodes section today, below the season picker, so it's invisible when the detail page opens.

## Architectural constraint (unchanged)
**Ravilo requests from Jellyfin as little as possible — the UI is served from jellystructure's own
data.** Next-airing info is **Sonarr-owned**: jellystructure reads it from the Sonarr API and stores it
on the series record; the TV detail + rows feeds carry it through. No Jellyfin round-trip, no client-side
derivation.

## Current state (verified in code, 2026-07-02)
**Backend: fully built.** (The earlier "backend is the gap" note is stale.)
- `SonarrEnrichService` (`src/linuxX64Main/…/arr/SonarrEnrichService.kt`) reads series status +
  next-monitored-episode from Sonarr (`ArrClient.ArrSeriesInfo.nextAiringUtc`, `ArrClient.kt:47-55`,
  date stored as `airDateUtc.take(10)` = date-only) — runs on startup (`enrichAll()`) and broadcasts a
  feed-changed signal after enriching.
- `MediaItem` carries `sonarrStatus` / `sonarrNextAiringDate|Season|Episode|Title`
  (`model/Media.kt:126-130`).
- TV DTOs populated and gated (Sonarr enabled ∧ not ended ∧ date exists):
  `SeriesDetail.next_airing` (`NextAiring{season,episode,title?,airDate}`, `Models.kt:190-197,269`,
  built in `DetailService.kt:95-113`) and the card label `MediaCard.upcoming_episode`
  (`Models.kt:135`, built as `"SxxEyy"` in `HomeFeedService.kt:431-433`, `BrowseService.kt:163-165`,
  `DetailService.kt:178-180`).
- **Poster badge: built.** `Tile.kt:277-297` renders the top-start accent pill `"Soon • S01E05"` from
  `upcomingLabel` — short fixed-ish label, never a date.
- i18n keys exist in all three locales (`Strings.kt` — `sonarr.next_ep`, `sonarr.airs`, upcoming label);
  the UI shows **no Sonarr attribution** anywhere (already removed).

**The gap — placement.** `SeriesDetailScreen.kt:451-478` renders the next-airing pill inside the
`item("episodes")` block (between the "Episodes" header and the episode rail), i.e. **below the season
picker, below the fold**. The hero (`:258-407`) ends with the resume kicker (`:319-339`) and the action
row (`:392-405`) — all above the fold on a TV.

## Requirements

### A. Move the line into the hero (the only build item)
1. In `SeriesDetailScreen.kt`, render the next-airing line **in the hero column, beside/alongside the
   resume kicker row** (`:319-339` — the design's `.dnext-row`): when `detail.nextAiring != null`, show
   localized `Next episode` · `S{season}:E{episode}` · optional `"{title}"` · `airs {formatted date}`.
   Keep the existing pill styling (accent dot + text) and i18n keys. Date formatting stays locale-aware
   and **UTC-pinned** (parse y-m-d parts; the calendar day must not shift by timezone).
2. **Remove** the episodes-section banner (`:451-478`) — the line lives in the hero only.
3. When `nextAiring == null`, render nothing and reserve no space (the hero column is bottom-anchored, so
   this cannot reflow content that is already visible — no-flicker rule holds).
4. The hero column is width-constrained (`0.6f` on TV); if both the resume kicker and the next-airing
   line are present they stack (kicker first) — verify neither wraps into the action row.

### B. Poster badge + DTOs + ingest (already built — regression-guard only)
5. No change to `Tile.kt` badge, DTO fields, gating, or Sonarr ingest. Acceptance re-verifies them.

## Invariants
- **Jellyfin is not consulted** — Sonarr-sourced, jellystructure-stored, server-pushed. The TV never
  derives or guesses the date.
- **Gated on `config.sonarr`** ∧ series not ended ∧ a next-airing date exists — otherwise neither line
  nor badge appears.
- Date stored/transported as date-only ISO; all display formatting UTC-pinned.
- Poster badge stays a short label (`Soon • SxxEyy`), never a date; the hero line carries the full date.
- **The UI never names the data source** (no "Sonarr"/"Radarr" attribution anywhere).

## Out of scope
- Countdown timers, calendar reminders, notifications, an "Upcoming" browse row, auto-requesting the
  episode, movie release countdowns — unchanged from the original spec.

## Acceptance
- Opening an ongoing series with a scheduled episode shows the "Next episode …" line **without any
  scrolling**, in the hero, beside the resume/up-next kicker; the old below-the-picker banner is gone.
- Ended series and Sonarr-disabled setups show neither line nor badge (unchanged).
- Row posters keep the "Soon • SxxEyy" badge (unchanged).

## Source references
- Design: `design/ravilo/ravilo-app.js` (series-detail hero `.dnext.air` line in `.dnext-row`),
  `ravilo-data.js` (`nextAiringFor`, `SERIES_STATUS`), `ravilo.css` (`.dnext.air`, `.dnext-row`,
  `.tile-air`), `ravilo-i18n.js`.
- Code: `ravilo-ui/…/screens/SeriesDetailScreen.kt` (`:319-339` hero kicker row — target;
  `:451-478` episodes-section banner — remove); backend + DTOs as listed in Current state.
