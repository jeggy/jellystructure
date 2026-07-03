# Phase R155 — Upcoming: a dedicated air/release calendar with detail, "already available" & "missing" states

> A first-class **Upcoming** tab (gated on Sonarr **or** Radarr) that shows a **TV-friendly calendar**
> of what's on the way: Sonarr's next monitored **episodes** and Radarr's monitored **movie releases**,
> grouped by day with a focusable **date rail**. Items are **clickable**: things we already hold open the
> **real detail page**; everything else opens a lightweight **upcoming detail** (styled like the Top 10
> detail). A bottom **"Missing from your library"** section lists what already released (up to 6 months
> back) but never landed. **The UI never names Sonarr/Radarr** — provenance stays server-side.

**Status:** Planned — **design built**, backend unbuilt. Supersedes three items R149 explicitly left
**out of scope**: a dedicated "Upcoming / Airing this week" screen, **movie** (theatrical/digital) release
equivalents, and a relative "in N days" countdown. R149's on-existing-surfaces signals (detail-hero
next-episode line + poster "Airing soon" badge) remain and are unchanged.

## Problem
Ravilo shows only the catalogue it already has. R149 added a next-episode line on the series-detail hero
and an "Airing soon" poster badge, but there is still no **single place** a viewer can scan *everything*
coming — across all series **and** movies — nor any signal for the two off-happy-path cases that matter
in a Sonarr/Radarr setup: (a) a title scheduled for tomorrow that we **already grabbed**, and (b) a title
that **released days ago and never arrived** (a stuck/failed grab). Both are invisible today.

## Architectural constraint (driving decision)
**Ravilo renders jellystructure's own server-pushed state — it never asks Jellyfin, Sonarr, or Radarr
directly, and never derives dates client-side.** The calendar, the per-item status, and the overdue set
are all **computed server-side** from the Sonarr calendar API (next monitored episodes) and the Radarr
calendar/queue (release dates + import state), stored/assembled by jellystructure, and delivered as one
TV feed. The client only lays it out. **No source attribution is ever shown** (mirrors R149).

## Current state (as-is)
**Design is fully built** in `design/ravilo/`:

- **`ravilo-data.js`**
  - `upcoming[]` — offset-dated schedule items via `U(offset, {…})`: `{ title, year, genre, rating,
    kind, date (local yyyy-mm-dd), offset, time, ep, epTitle, source ('sonarr'|'radarr'), network,
    release ('Digital Release'|'Physical Release'|'In Cinemas'), status, progress, monitored, qp
    (quality), syn }`. `status ∈ { monitored, announced, downloading, available }`.
  - `overdue[]` — same shape at **negative** offsets, `status: 'missing'` (released, not imported).
  - `upcomingByDay()` — groups `upcoming` by date preserving chronological order.
  - Exposes `upcoming`, `overdue`, `upcomingByDay` on `window.RAVILO`. Gated by `config.sonarr` /
    `config.radarr` (both already present from Phase 54 / R149).
- **`ravilo-app.js`**
  - Nav item `data-nav="upcoming"` (between Series and Top 10), hidden unless
    `upcomingEnabled()` (`config.sonarr || config.radarr`); `updateUpcomingNav()` toggles it.
  - `renderUpcoming()` — header + `upcoming_sub`; filter chips **All / Series / Movies**; a red
    **"Missing" jump pill** (`_upjump`, shows the overdue count, scrolls to the missing section);
    a **date rail** (`.up-rail`, one `.up-day` chip per day **that has content** — empty days are
    skipped, so the rail jumps over gaps); an **agenda** of `.up-sec` day-sections; and, at the bottom,
    a **`.up-missing`** section (overdue items, `offset >= -183` → ~6 months). Empty-safe: renders a
    `up_nothing` state when a filter leaves nothing.
  - `upcomingCard(it)` — landscape card: content-type badge (`upSrcBadge` → **Episode / Movie**, never
    a source name), `upStatusPill` (downloading → live **%** via `fetchShort`; available → green
    **"Already available"**; missing → red **"Missing"**; else nothing), time/release, title, sub
    (`S·E · title` or release type), `network · rating`. Missing cards also carry a **"Was due <date>"**
    badge (`.up-cdue`).
  - `upDayChip` scrolls to its section; filter chips re-render preserving `view.upSource`.
  - **Click routing** (`activate`, `_upitem`): if we already hold it — an `available` item **or** any
    series already in the catalogue — open the **real** `series`/`movie` detail; otherwise open
    `upcomingDetail`.
  - `renderUpcomingDetail(it)` — reuses the Top 10 `.ddt` shell (`.updt`): kicker (**New Episode /
    Premiere / Missing** + coloured dot) with a relative **airs/aired** chip (today / tomorrow /
    in N days / yesterday / N days ago); meta (cert, year, genre, kind, `S·E·title`/release chip);
    synopsis; a **Schedule** stat grid (air|release date · air time|release type · network · quality);
    a plain-language foot. **No action buttons** (no reminder, no My List).
  - Top 10 scrub: `add_list` shown only for `available` items; the discover synopsis fallback and the
    fetch-progress toast no longer name Radarr (`requested_via` reworded).
- **`ravilo-i18n.js`** — `nav_upcoming`, `upcoming_sub`, `up_today/tomorrow`, `up_all/series/movies`,
  `up_release(s)`, `up_nothing`, `up_airs_today/tomorrow/in`, `up_aired_yest/ago`, `up_released_yest/ago`,
  `up_schedule`, `up_airdate`, `up_reldate`, `up_airtime`, `up_release_type`, `up_network`, `up_quality`,
  `up_arriving`, `up_episode`, `up_movie`, `up_new_episode`, `up_premiere`, `up_available`, `up_missing`,
  `up_missing_title`, `up_missing_sub`, `up_due` — en/da/fo (missing keys fall back to en).
- **`ravilo.css`** — `.upscreen`/`.uphead`, `.upfilter`/`.upchip`, `.up-missjump*`, `.up-rail`/`.up-day`,
  `.up-sec`/`.up-daybadge`/`.up-seccount`, `.up-card`/`.up-art`/`.up-src`/`.up-stat.{dl,avail-lib,missing}`,
  `.up-cdue*`, `.up-missing`/`.up-mbadge`, and `.updt`/`.up-kicker`/`.up-kdot`/`.up-airchip` detail styles.

**Backend is the gap:** no TV "upcoming/calendar" feed, no combined Sonarr-calendar + Radarr-calendar
ingest, no overdue query.

## Requirements

### A. Assemble the calendar server-side (ingest)
1. When `[sonarr]` is configured, read the **Sonarr calendar** (next monitored, unaired episodes: series,
   S/E, episode title, air date **and air time**, network) plus each episode's **grab/download state**
   (queued / downloading-with-% / imported). When `[radarr]` is configured, read the **Radarr calendar**
   (monitored movies: release date, **release type** — digital / physical / in-cinemas — and
   monitored / grabbed / imported state). Best-effort: an outage yields an empty calendar, never blocks.

### B. TV upcoming feed DTO
2. Expose a TV **upcoming feed**: a list of items `{ id, kind, title, year, genre, rating, date
   (yyyy-MM-dd), time?, ep?, epTitle?, release?, network?, quality?, status ∈
   {monitored, announced, downloading, available, missing}, progress?, synopsis?, artwork/grad }`,
   **without** any `source`/provider field exposed to the client. The server groups nothing — the client
   groups by `date`.
3. **Available** = already imported into the library (client will link it to the real detail item, so
   include enough identity to resolve the catalogue item). **Missing** = released in the past, monitored,
   **not** imported.

### C. Nav tab + gating
4. Add an **Upcoming** tab (between Series and Top 10), visible iff **Sonarr or Radarr is configured**
   (`config.sonarr || config.radarr`) — same gating spirit as `discoverEnabled()`. Localize the label.

### D. Calendar screen (layout)
5. Header + subtitle; a **content-type filter** (All / Series / Movies); a **date rail** with one chip
   **per day that has releases** — **empty days are omitted**, nothing is assumed to arrive daily, and the
   rail visibly jumps over gaps; each chip shows weekday / day-number / month / count and scrolls to its
   day. Below, an **agenda** with one section per day (day badge, relative/weekday title, release count,
   a horizontal rail of cards). D-pad friendly (row-based focus). If a filter empties the screen, show a
   "nothing scheduled" state.

### E. Cards (per item)
6. Landscape card: a **content-type tag** (Episode / Movie — **never** "Sonarr"/"Radarr"), the air
   **time** (series) or **release type** (movie), title, `S·E · title` / release sub, `network · rating`,
   and a status pill: **downloading → live %** (spinner), **available → "Already available"** (green),
   **missing → "Missing"** (red). Non-special items carry **no** pill (the whole screen is already
   "upcoming"). Missing cards additionally show a **"Was due <date>"** badge.

### F. Upcoming detail page (for items we don't hold)
7. Reuse the Top 10 detail shell. Kicker = **New Episode / Premiere / Missing** with a **relative** chip
   (airs today/tomorrow/in N days · aired yesterday/N days ago). Meta row, synopsis, and a **Schedule**
   grid: air|release **date**, air **time**|release **type**, **network** (series), **quality**. Foot is
   plain language ("added to your library automatically when it airs/releases"; for missing: "released,
   but not in your library yet"). **No action buttons** — see Invariants (My List / reminders excluded).

### G. "Already available" routing
8. An item whose `status == available`, **or** any **series already in the catalogue**, opens the
   **real** `series`/`movie` detail page (not the lightweight one) — the viewer lands on the normal
   watch/resume surface. It still appears in the calendar with the **"Already available"** badge.

### H. Missing / overdue overview
9. A bottom **"Missing from your library"** section lists items that **released in the past but never
   imported**, most-recent first, **capped at ~6 months** (older is written off). Each card shows the
   red "Missing" pill + "Was due <date>". A **jump pill** at the top (with the count) scrolls to it.
   Clicking a missing item opens the upcoming detail in its **"aired/released N days ago"** framing.

## Invariants
- **Gated on `config.sonarr || config.radarr`.** With both disabled the tab never appears.
- **The UI never names the data source** — no "Sonarr"/"Radarr" text anywhere in Ravilo (this phase also
  removed the remaining Radarr mentions from the Top 10 surfaces).
- **Renders server state only.** All dates/times/status come from the feed; the client never derives or
  guesses. Dates are date-only ISO; display formatting is locale-aware.
- **No "My List" or reminder affordance for non-library items** — My List is Jellyfin-owned and cannot
  hold titles that don't exist yet, so the upcoming detail has **no actions**; the Top 10 "＋ My List"
  button is likewise shown only for `available` items.
- **The calendar makes no daily-content assumption** — empty future days are skipped in the rail and
  agenda; a fully-empty (filtered) screen shows an explicit empty state.
- **Overdue is bounded to ~6 months**; anything older is not surfaced.
- **Already-available items link to the real detail page**; only not-yet-held items use the lightweight
  upcoming detail.

## Out of scope
- Any **fetch/request/retry** affordance on the calendar (Sonarr/Radarr own acquisition; a stuck grab is
  surfaced as "Missing" but not actionable from here) — display only.
- **Reminders / notifications / calendar export** when an item airs.
- A month **grid** calendar view (agenda + date-rail only this phase).
- Re-deriving the R149 detail-hero line / poster badge — those stay as-is.

## Source references
- Design: `design/ravilo/Ravilo TV.html`; `design/ravilo/ravilo-data.js` (`upcoming`, `overdue`,
  `upcomingByDay`, `U()`); `design/ravilo/ravilo-app.js` (`renderUpcoming`, `renderUpcomingDetail`,
  `upcomingCard`, `upStatusPill`, `upSrcBadge`, `upDayChip`, `updateUpcomingNav`, `_upitem`/`_upjump`
  routing); `design/ravilo/ravilo-i18n.js` (`nav_upcoming`, `up_*`); `design/ravilo/ravilo.css`
  (`.upscreen`/`.up-rail`/`.up-sec`/`.up-card`/`.up-stat`/`.up-missing`/`.updt`).
- Backend: Sonarr + Radarr clients + config (`[sonarr]`, `[radarr]`); a new TV upcoming/calendar feed
  builder + DTO (shared `Models.kt`); overdue query (released ≤6mo, not imported);
  `ravilo-ui/.../screens/UpcomingScreen.kt` (+ card / date-rail / detail composables).
- Related: **R149** (on-surface next-airing; this phase delivers its deferred dedicated screen + movie
  equivalents + countdown), **R148** (episode air dates), **Phase 54 / Discover + R48–R50** (Radarr-backed
  request/fetch + `config.radarr` gating this mirrors), **R153** (age-rating badge reused in the detail meta).
