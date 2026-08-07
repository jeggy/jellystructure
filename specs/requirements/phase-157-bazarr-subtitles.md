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
Bazarr section) and `design/app/series.html` (season-scoped Bazarr card). Dev-reviewed 2026-08-07
(addendum below) — the API-surface open question is resolved against this environment's live Bazarr
instance (`localhost:7007`, real Radarr/Sonarr-backed library, 1.6.0).

## Dev-review addendum (2026-08-07 — backend-reality check before implementation starts)

Traced every backend-adjacent claim above against the actual Kotlin/Ktor backend (`src/linuxX64Main`)
and the actual admin frontend (`src/wasmJsMain` — **not** `design/app/*.html`, which is a static
mockup, not shipped code), and against a live Bazarr instance running on this host (port 7007, real
data: 161 series / hundreds of movies, 4,999 episodes + 35 movies currently wanted). Style/rigor
calibrated against the R190 addendum (`c72cf500`).

**✅ Confirmed — `[bazarr]` config shape is structurally trivial to add**, but one file-name nit.
`AppConfig` (`config/AppConfig.kt:7-28`) declares `radarr`/`sonarr`/`seerr` as nullable top-level
fields, each decoded via one `toml.decodeFromString` call (`ConfigStore.kt:19,32`,
`ignoreUnknownNames = true`) — a new `bazarr: BazarrConfig? = null` is "a fourth of the same shape,"
exactly as claimed. The `##KEEP##` secret-masking round-trip (`ConfigRoutes.kt:73-83,127-133`) already
covers exactly this quartet and extends the same way. Correction: the spec's "Current state" cites
`SettingsScreen.kt` — **that file doesn't exist in the admin app** (it's a *Ravilo player* settings
screen in `ravilo-ui`/`ravilo-tizen`). The real file is `src/wasmJsMain/.../ui/Settings.kt`.

**⚠️ Corrected — no path-matching needed at all; ID-based matching is simpler and already available.**
The spec assumes Bazarr resolution mirrors "how Radarr/Sonarr already are" matched by path. Two
independent problems with that: (1) per the R190-style check on this codebase's own Arr matching,
Radarr resolution is **tmdbId-only everywhere** (`ArrClient.kt:91-99`, `ArrRescanService.kt:62`) —
there is no Radarr-by-path lookup to imitate; only one Sonarr call site (`ArrRescanService.kt:67`) is
path-based. (2) More importantly, **querying the live Bazarr API directly shows path-matching isn't
needed for Bazarr resolution regardless of what Arr does**: `GET /api/movies` returns `imdbId` +
`path` + `radarrId` per movie; `GET /api/series` returns `tvdbId` + `path` + `sonarrSeriesId` per
series; `GET /api/episodes?seriesid[]=` returns `season`/`episode`/`sonarrEpisodeId` per episode.
`MediaItem` already carries both `imdbId: String?` (`model/Media.kt:220`) and `tvdbId: Int?`
(`Media.kt:221`) today. So the real, more robust matching plan is: **movies → equality on `imdbId`;
series → equality on `tvdbId`; episodes → season+episode number once the series is resolved** — a
clean id-join against Bazarr's own listing endpoints, no path string comparison anywhere, and no new
Jellystructure-side field needed. (Bazarr's own `path` field can still serve as a fallback/sanity
check, and IS the only usable key for movies if `imdbId` is ever null, which happens for some
titles.) Path-matching is not just avoidable, it would have been strictly worse (fragile against
Bazarr/Jellystructure library-root path-mapping mismatches) than the id-join Bazarr's API already
supports directly. This resolves the spec's own flagged open question, and does so with a simpler
answer than either the spec or the Radarr precedent assumed.

**✅ Confirmed — `BazarrClient` follows the existing `ArrClient`/`SeerrClient` template**, and the full
command surface is now confirmed against Bazarr's live `/api/swagger.json` (fetched from the running
instance), not assumed from general docs:
- Auth: `X-API-KEY` header (Bazarr's own casing; same idiom as `ArrClient`'s `X-Api-Key`).
- **Search & download (auto-pick)**: `PATCH /api/movies/subtitles?radarrid&language&forced&hi` /
  `PATCH /api/episodes/subtitles?seriesid&episodeid&language&forced&hi`.
- **Manual search + pick a specific result** (FR-BZ1-6's "manual search & download"):
  `GET /api/providers/movies?radarrid` / `GET /api/providers/episodes?...` to list candidates, then
  `POST /api/providers/movies?radarrid&hi&forced&original_format&provider&subtitle` to download the
  chosen one.
- **Sync/re-time** (ffsubsync, exactly as the spec says): `PATCH /api/subtitles?action=sync&type=
  [movie|episode]&id=&language=&path=&reference=&max_offset_seconds=` — confirms FR-BZ1-6's "sync/
  re-time to audio" maps to a real, single endpoint with a `reference` param (video track or another
  subtitle file) worth surfacing if the UI ever needs to pick a reference track.
- **Delete**: `DELETE /api/movies/subtitles?radarrid&language&forced&hi&path` / episode equivalent.
- **Upload**: `POST /api/movies/subtitles` / `POST /api/episodes/subtitles` (both already in the API,
  matching FR-BZ1-4's "Upload a file…").
- **Per-item "search all wanted" sweep** (movie/series card level, FR-BZ1-4/5): `PATCH /api/movies?
  radarrid=&action=search-wanted` / `PATCH /api/series?radarrid=&action=search-wanted` (also exposes
  `scan-disk` and a plain `sync` action per item).
- **⚠️ Corrected — per-title "Upgrade" has no matching single-item Bazarr endpoint.** `GET /api/system/
  tasks` on the live instance lists Bazarr's actual task ids: `upgrade_subtitles` ("Upgrade Previously
  Downloaded Subtitles") is a **global, library-wide** scheduled task (`POST /api/system/tasks?
  taskid=upgrade_subtitles`), not scoped to one movie/episode. FR-BZ1-4/5's per-title **Upgrade**
  button therefore has no 1:1 backend call to proxy — it needs to be *composed* client-side/
  server-side as "search this item's providers again, then download whichever result now scores
  higher than what's on disk" (the same `GET`+`POST /api/providers/{movies,episodes}` pair as manual
  search, just auto-picking the top-scoring result instead of prompting). Worth stating explicitly in
  the requirements before implementation so it isn't scoped as a single passthrough call.
- **Full Bazarr scan** (FR-BZ1-6/overview page): also task-based, and **split in two** —
  `movies_full_scan_subtitles` and `series_full_scan_subtitles` are separate task ids; "Run full
  Bazarr scan" needs to fire both (`POST /api/system/tasks` twice) to match its own description.
- **Auto-search-on-add sweep** (the Settings toggle, FR-BZ1-1) maps to `wanted_search_missing_
  subtitles_movies` / `_series` task ids, or the per-item `search-wanted` action above if scoped to
  just the newly-added title (cheaper, and avoids re-sweeping the whole wanted queue on every import).

**✅ Confirmed and validated — the "thousands of wanted items" scale claim is not hypothetical.** The
live instance currently has **4,999 wanted episodes and 35 wanted movies** (`GET /api/episodes/wanted`
`"total": 4999`) — right at the spec's own "5,000+ is normal" framing. This directly validates FR-BZ1-2's
insistence on server-side paging + counts rather than flat rendering; a flat render on this exact,
real dataset would already be rendering ~5,000 DOM rows on first paint. No change needed here — flagging
as a claim that checked out, not just ones that didn't.

**✅ Confirmed — providers/language-profiles read-only mirror is straightforward.** `GET /api/providers`
returns live per-provider status/retry-after (including real throttle state observed on this
instance: `opensubtitlescom` mid-`DownloadLimitExceeded`, `tvsubtitles` mid-retry-backoff — exactly
the "provider health" signal FR-BZ1-2's side rail wants). `GET /api/system/languages/profiles` returns
full profile definitions (id, name, per-language hi/forced/audio-only flags) — enough to render the
read-only mirror with zero interpretation needed.

**⚠️ Corrected — folding Bazarr history into the title History tab, as literally written, would
break the spec's own "stores nothing" principle.** `MediaHistory` (`media/MediaHistory.kt:7-38`) is a
**jellystructure-owned, SQLite-persisted, revertable** audit log (`HistoryEntry.beforeSnapshot`,
`MediaApi.revertHistoryEntry`), not a passive display surface — `record()` is called from real
mutation paths (e.g. `MediaRoutes.kt:709`). FR-BZ1-1's "fold Bazarr's per-title log into the title
History tab" has two readings: (1) merge Bazarr's `GET /api/movies/history` /`/episodes/history` at
render time only (keeps "stores nothing about subtitles" intact, zero backend writes) vs. (2) write
Bazarr events through `mediaHistory.record()` (persists subtitle state jellystructure said it
wouldn't, and makes no sense against `revertable`/`beforeSnapshot` — there's nothing to revert a
Bazarr download to). The spec's own design principle demands (1); the prose as written reads like
(2). **Needs one sentence added before implementation:** "merged at render time only, never written
to `mediaHistoryQueries`."

**⚠️ Corrected — "Subtitles page reached only via dashboard card, no left-nav" has no precedent in
the real app** (only in the design mockup). Real nav is `Shell.kt:94-107`'s `NAV` array; every real
top-level route has an entry except two Library drill-down/leaf pages, and the closest "featureless
in the nav" precedent (Triage) is explicitly a floating dock, not a page (`Shell.kt:192-193`). This is
fine to build, but it's **novel real-app plumbing** (a new `Main.kt` route deliberately absent from
`NAV`), not "the established pattern."

**⚠️ Corrected — no existing Radarr/Sonarr/Seerr status card exists on the Dashboard today.**
`Dashboard.kt:26-77` has exactly three cards (attention breakdown, recently processed, quick actions);
grep for `radarr|sonarr|seerr` in that file is empty. FR-BZ1-3 would be the **first** external-service
status card on the Dashboard, not a fourth of an established kind — the `.dash-sidecol` card *layout*
is reusable scaffolding, but the mini-stat content pattern has nothing to confirm against.

**Net effect on scope:** §A (config) is cheap, confirmed reuse. §D (the action set) is now fully
mapped to real, live-verified endpoints — cheaper than expected for search/download/sync/delete/
upload, but "Upgrade" and "full scan" both need small composition/fan-out logic the spec's 1-button-
1-call framing glosses over. The matching layer (needed by every other requirement) is simpler and
more robust than either the spec or the Arr precedent suggested: a straight `imdbId`/`tvdbId` join,
no path-matching code to write at all. §B/§C's own scope is otherwise as described. Two things need a
one-line spec fix before implementation: the History-tab persistence question (§6) and stating the
full-scan/upgrade composition explicitly (§D) — everything else is either confirmed reuse or novel-
but-modest frontend plumbing (§7/§8).
