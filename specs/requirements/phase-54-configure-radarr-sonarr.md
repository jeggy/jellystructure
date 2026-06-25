# Phase 54 — Configure Radarr & Sonarr in Settings (FR-AR1)

> **Scope:** this is the **connection layer** only — credentials, root-folder import, and
> rescan-after-write. *Requesting* titles we don't have and tracking their download lifecycle is
> **[Phase 56](phase-56-arr-acquisition-pipeline.md)** (the acquisition pipeline + status state
> machine), which builds on this connection. The Ravilo "Top 10" feature that surfaces requests is
> specced in [Phase 57](phase-57-chart-discover-ingestion.md) (chart ingestion) +
> [R48–R50](../ravilo/requirements/README.md).

## Goal

Let an operator connect the two *arr apps they almost certainly already run alongside
Jellyfin — **Radarr** (movies) and **Sonarr** (series) — so Jellystructure can:

1. **Read each app's root folders** and offer to turn them into `[[libraries]]` mappings
   (same longest-prefix matching as Phase 15 / the qBittorrent path mappings), and
2. **Nudge the right *arr to rescan a single title** after Jellystructure writes NFOs or
   edits tracks, so the *arr's own library view stays in sync with what we changed on disk.

> **What rescan actually refreshes (be honest about scope):** Radarr/Sonarr are TMDB-backed and
> **ignore our NFOs** for their own metadata, so a rescan does *not* pull title/overview edits into the
> \*arr. Where it earns its keep is **MediaInfo** (audio/subtitle languages, codecs) after an in-place
> `mkvpropedit` track edit and when files are added/removed. Implementation note: an in-place header
> edit changes mtime but the \*arr's change-detection may still skip it — verify `RescanMovie`/
> `RescanSeries` actually re-probes MediaInfo (fall back to a refresh command if not), else the rescan
> is a no-op except on file add/remove.

This is **strictly opt-in** and **read + rescan only**. Jellystructure never adds, grabs,
upgrades, renames, or deletes anything through Radarr/Sonarr. It is the metadata/track
manager; the *arr stack stays the acquisition manager. Both integrations are independent —
an operator can enable one, both, or neither.

## Non-goals (hard scope fence)

- **No acquisition.** Never call `POST /api/v3/movie` / `/series`, `/command {MoviesSearch}`,
  `/release`, `/queue`, or any add/grab/delete endpoint. Reads + `Rescan*` only.
- **No parallel taxonomy.** Root folders feed the existing `[[libraries]]` model; they do
  not introduce a new "quality profile"/"root folder" concept into Jellystructure's data. (Phase 56,
  when it *requests* a brand-new title, does add an explicit per-\*arr `default_root_folder` +
  `quality_profile` under `[acquisition]` — those are **add parameters the \*arr API demands**, distinct
  from this phase's read-only root-folder *import*; they don't change the `[[libraries]]` model here.)
- **No always-on coupling.** With both apps disabled (the default), nothing about scanning,
  writing, or refreshing changes — exactly as today.

## Config shape (constitution §Configuration Shape)

Two new optional top-level sections, mirroring the `[qbittorrent]` opt-in pattern. Absent or
`enabled = false` ⇒ that integration is completely inert.

```toml
# Optional — Radarr integration (Phase 54). Movies. Read root folders + rescan a title after writes.
[radarr]
enabled = false
url = "http://radarr:7878"
api_key = ""               # Radarr → Settings → General → API Key
rescan_after_write = true  # POST /api/v3/command {name:"RescanMovie", movieId} after a JS write

# Optional — Sonarr integration (Phase 54). Series. Same contract.
[sonarr]
enabled = false
url = "http://sonarr:8989"
api_key = ""               # Sonarr → Settings → General → API Key
rescan_after_write = true  # POST /api/v3/command {name:"RescanSeries", seriesId} after a JS write
```

Notes:
- New installs ship both **disabled** (matches the `[qbittorrent]` Phase 40 default).
- `api_key` is **write-only/masked** through the API exactly like `password`: never returned
  by `GET /api/config`; a blank value on `PUT` keeps the stored key via the `##KEEP##`
  sentinel (reuse the Phase 40 mechanism).

## Backend

### `ArrClient` (one client, two instances)
A small Ktor client shared by both apps (the v3 API is identical between Radarr and Sonarr
for the calls we make):
- `ping()` → `GET /api/v3/system/status` with `X-Api-Key`; returns `{ok, version, ms}`.
- `rootFolders()` → `GET /api/v3/rootfolder` → `List<String>` of paths.
- `lookupId(path|tmdbId)` → resolve the affected title to its Radarr `movieId` / Sonarr
  `seriesId` (movies: `GET /movie?tmdbId=`; series: `GET /series` matched by path/tvdb).
- `rescan(id)` → `POST /api/v3/command` body `{name:"RescanMovie"|"RescanSeries", <id>}`.

### Routes
- `POST /api/config/test-radarr` and `POST /api/config/test-sonarr` — receive
  `{url, apiKey}`, run `ping()` + `rootFolders()`, return `{ok, detail, version?, rootFolders?}`.
  Never persists (temporary config object, same shape as `test-qbittorrent`).
- `GET /api/config/{radarr|sonarr}/root-folders` — for the "Import root folders → libraries"
  action; returns the discovered paths so the UI can pre-fill `[[libraries]]` `local_path`s.
- `PUT /api/config` — handles the `api_key == "##KEEP##"` sentinel for both sections.

### Post-write hook
After a successful NFO/track write in `pushToJellyfin` / the track-write path, if the item is
a movie and `radarr.enabled && radarr.rescan_after_write` (resp. series + sonarr), fire a
**best-effort, non-blocking** `rescan(id)`. Failures are logged (Phase 17 activity log) and
**never** block or fail the Jellystructure write — the *arr rescan is a courtesy, not a
dependency. This is independent of the Jellyfin refresh (`tell_jellyfin`), which still runs.

### Health (Phase 35)
`GET /health` gains `radarr` and `sonarr` probes (only when each is enabled), surfaced by the
existing **Test connections** flow. An unreachable *arr is a **non-fatal** health warning — it
does **not** fail-close anything (contrast with the qBittorrent guard, which does).

## Frontend

### `ConfigApi.kt`
- New `ArrConfig` DTO: `enabled`, `url`, `apiKey` (`@SerialName("api_key")`),
  `rescanAfterWrite` (`@SerialName("rescan_after_write")`).
- `AppConfig` gains `radarr: ArrConfig? = null` and `sonarr: ArrConfig? = null`.
- `ArrTestResult(ok, detail, version?, rootFolders?)` DTO.
- `ConfigApi.testRadarr(url, apiKey)` / `testSonarr(url, apiKey)` and
  `importRootFolders(kind)` methods.

### Settings.kt — "Download tools" tab (see Phase 55)
The Radarr/Sonarr section lives on the new **Download tools** tab alongside Cross-seed safety
(`data-tab="downloads"`, section id `sect-arr`). Each app is its own `.box` with:
- header (app name + `movies`/`tvshows` badge) and a master **enable** toggle that
  shows/hides the body (`#radarr-on` / `#sonarr-on`);
- **URL** + **API key** (masked) fields, the key carrying an inline test chip
  (`#chk-radarr` / `#chk-sonarr`) wired into `runHealthCheck()`;
- a **root-folders** readout + **"Import root folders → libraries"** action (`.arr-import`);
- a **"Rescan in {app} after writes"** toggle bound to `rescan_after_write`;
- a note that root-folder paths reconcile with **Library mapping** by longest prefix.

`populateForm()` / `readForm()` round-trip both sections (api-key `##KEEP##` sentinel when
blank); `buildToml()` renders `[radarr]` / `[sonarr]` blocks (omitted when disabled).
`runHealthCheck()` sets `chk-radarr`/`chk-sonarr` only when the app is enabled and folds any
failure into the **Download tools** tab badge (Phase 55 aggregation).

## Invariants preserved / added

- **Opt-in & inert by default** — both `enabled = false`; disabled ⇒ zero behavioural change.
- **Read + rescan only** — no acquisition/mutation endpoints are ever called (scope fence).
- **Never blocks a write** — an *arr rescan is best-effort and non-fatal; only the qBittorrent
  guard is fail-closed.
- **API key never leaves the server** — masked in `GET /api/config`; `##KEEP##` on `PUT`
  (same contract as qBittorrent `password`).
- **One taxonomy** — root folders feed `[[libraries]]`, not a parallel model.

## Mockup

`design/app/settings.html` — `sect-arr` section on the Download tools tab (Radarr + Sonarr
boxes, enable toggles, masked key + test chip, import-root-folders action, rescan toggle) and
the matching `[radarr]`/`[sonarr]` blocks in the live `config.toml` preview.
