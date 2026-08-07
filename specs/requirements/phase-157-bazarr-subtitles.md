# Phase 157 — Bazarr subtitle integration (connection · per-title · overview)

> Jellystructure already talks to the *arr stack (Radarr/Sonarr) and Seerr. It has no relationship
> with **Bazarr**, so subtitles are the one media dimension an operator still has to leave the app
> for. This phase adds Bazarr as a first-class optional integration: a connection in Settings, a
> subtitle surface on each title, and a global **Subtitles** overview — so you can search, download,
> sync and upgrade subtitles without ever opening Bazarr.

## Design principle — Bazarr stays the source of truth
Jellystructure **stores nothing about subtitles**. It reads Bazarr live over the API and issues
commands to it. Bazarr continues to own providers, scoring, and **language profiles**; we mirror
those read-only and assign a profile per title *in Bazarr's model*, never persisting a parallel copy.
This keeps the same "server-pushed state only, no derived state" rule the rest of the app follows and
means there is nothing to migrate, reconcile, or drift.

## Goal
- Configure a Bazarr connection in **Settings → Download tools**, matched to titles by path the same
  way Radarr/Sonarr already are.
- On a title, act on subtitles from the **Tracks & subtitles** tab (movie) / **Seasons & episodes**
  tab (series): see present sidecar subs + wanted languages, and Search / Download / Sync / Upgrade /
  Delete each — plus per-title history.
- See the whole picture on a new **Subtitles** page: live queue, wanted list, recent history,
  provider health, and the (read-only) language profiles.

## Current state
- `ArrConfig` / `SeerrConfig` (`config/AppConfig.kt`) + their `SettingsScreen.kt` cards + test-connection
  are the established pattern for an optional external service. Bazarr is a fourth of the same shape.
- The admin shell (`app/app-shell.js`) builds the sidebar from a `NAV` array and injects it on every page.
- Movie detail has a **Tracks & order** tab (embedded audio/subtitle *track* editor); series edits
  tracks per-episode in a modal. Neither surfaces **external/sidecar** subtitle files.
- Bazarr's REST API exposes providers, wanted, history, episodes/movies subtitle state, and command
  endpoints (search/download/sync/upgrade/delete, plus a full scan) — enough to drive all of the above.

## Requirements

### A. Settings — Bazarr connection (`design/app/settings.html`)
#### FR-BZ1-1 — A Bazarr card in Download tools
A new **Bazarr** card in the Download-tools tab, same shape as Radarr/Sonarr/Seerr: master enable
toggle, `url` + masked `api_key` (with `##KEEP##` sentinel like the others), a **Test connection**
chip (version + latency), and a connected status line (provider count · profile count · "matched by
path to Radarr/Sonarr"). New optional `[bazarr]` config block: `enabled`, `url`, `api_key`,
`auto_search_on_add`. Off ⇒ no subtitle surfaces appear anywhere. Two service options:
**Auto-search when new media is added** (on scan / *arr import, ask Bazarr to search wanted languages)
and **Show subtitle history on title pages** (fold Bazarr's per-title log into the title History tab).

### B. Global Subtitles overview (`design/app/subtitles.html`, new page)
#### FR-BZ1-2 — A Subtitles page reached from the Dashboard
A full **Subtitles** page (`subtitles.html`), **not** given a left-nav item — it is reached from the
dashboard summary card (FR-BZ1-3). Page sections, all read live from Bazarr:
- **Stat strip** — Wanted · No-subs-at-all · Downloaded today · In queue · Providers healthy.
- **Queue & tasks** — live list of in-flight searches / syncs / scans with progress, + **Run full
  Bazarr scan**.
- **Wanted subtitles** — the wanted list is expected to run to **thousands** of items (5,000+ is
  normal), so it is never rendered flat: a prominent total, a debounced title search, kind
  (movie/series) + language facets, and **paged** loading ("Load N more" / range readout), all backed
  by Bazarr's own server-side wanted query and counts. Per-row **Search**; a page-level **Search all
  wanted (N)** sweep that **confirms first** given the scale and runs in the background respecting
  provider rate limits.
- **Recent history** — download / sync / upgrade / remove feed with provider, language, match score,
  time; filterable by action.
- Side rail — **Language profiles** (read-only mirror, "edit in Bazarr" link), **Providers** health,
  **This week** counters.

#### FR-BZ1-3 — Dashboard summary card
A **Subtitles** card in the dashboard side column: wanted / in-queue / got-today / providers mini
stats + latest action, linking to the Subtitles page.

### C. Per-title subtitle surface
#### FR-BZ1-4 — Movie: a Bazarr section on the Tracks & subtitles tab
The movie **Tracks & order** tab is renamed **Tracks & subtitles** and gains a **Subtitles — Bazarr**
card below the embedded-track editor (clearly labelled *sidecar files*, distinct from embedded tracks).
It lists the title's profile-wanted languages; for each: if present, the file / provider / HI / **score**
and **Sync** · **Upgrade** · **Delete**; if wanted-but-missing, **Search**. Card-level **Search all
wanted** and **Upload a file…**. A latest-action line links to the History tab.

#### FR-BZ1-5 — Series: a season-scoped Bazarr card on Seasons & episodes
The series **Seasons & episodes** tab gains a **Subtitles — Bazarr** card scoped to the selected
season: a per-episode row (have languages · wanted languages · Search/Sync action) and season-level
**Search all wanted** / **Sync all to audio**. Consistent with the movie card; no per-episode modal
needed for the common case.

### D. Actions Jellystructure drives (all proxied to Bazarr)
#### FR-BZ1-6 — Search / Download / Auto-search / Sync / Upgrade / Delete / Scan
The action set exposed on the item surfaces and the overview: **manual search & download** (pick a
result), **auto-search** for wanted, **sync/re-time** to audio (ffsubsync), **upgrade** to a
better-scoring subtitle, **delete** a subtitle (language reverts to wanted), and **trigger a full
Bazarr scan**. Each maps to a Bazarr command endpoint; long-running ones surface in the overview queue
and the Activity log. *(Not in scope: editing providers, profiles, or scoring — done in Bazarr.)*

## Config shape
```toml
[bazarr]
enabled = true
url = "http://bazarr:6767"
api_key = "•••7c14"
auto_search_on_add = true
```
Path-matched to the same library roots Radarr/Sonarr import. No subtitle state persisted.

## Non-goals
- **No editing of Bazarr's providers, language profiles, or scoring** — read-only mirror + deep links.
- **No Jellystructure-owned wanted-language store** — the profile lives in Bazarr; we assign, not copy.
- **No Ravilo-side change** — this is admin-only (the player already lists whatever subtitle tracks
  exist on the file).
- **No offline/degraded mode beyond "integration disabled"** — Bazarr unreachable ⇒ surfaces show a
  connection error, no local fallback.

## Acceptance
- With `[bazarr]` enabled, Settings shows a connected Bazarr card; disabled ⇒ every subtitle surface
  disappears. *(Verified in the design build.)*
- The Subtitles page shows live queue, wanted (filterable), history (filterable), providers and
  read-only profiles; the dashboard card summarizes and links to it. *(Verified.)*
- A movie's Tracks & subtitles tab and a series' Seasons & episodes tab each expose present + wanted
  subtitles with Search/Sync/Upgrade/Delete driven through Bazarr. *(Verified.)*

## Status
Design-complete, **`Planned`** (2026-08-07). Design lives in `design/app/settings.html` (Bazarr card
+ `[bazarr]` TOML), `design/app/subtitles.html` (overview page), `design/app/app-shell.js` (Subtitles
nav + icon), `design/app/index.html` (dashboard card), `design/app/media.html` (Tracks & subtitles
Bazarr section) and `design/app/series.html` (season-scoped Bazarr card). Not yet dev-reviewed; the
one open backend question is the exact Bazarr API surface for the command endpoints (search/sync/
upgrade) and how path-matching resolves a Bazarr `radarrId`/`sonarrId` from a Jellystructure item —
to confirm against a live Bazarr instance before implementation, as was done for Seerr in R190.
