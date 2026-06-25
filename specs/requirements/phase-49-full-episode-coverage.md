# Phase 49 — Full episode coverage for large series (FR-EP1)

so long shows (e.g. "Two and a Half Men", ~260 episodes) show scattered gaps in the Seasons & Episodes
and Artwork tabs — and no UI action recovers them. Make the cap configurable (default unlimited) and wire
an on-demand uncapped "Re-scan all episodes"._

> Follow-up to Phase 6 (per-episode metadata) + Phase 13 (sync single item). No NFO/format change — this
> is about which episodes get probed and stored.

## Problem
`Scanner.scanSeries()` caps episode probing for big series (`Scanner.kt` ~L207–212):
```kotlin
val filesToProbe = if (episodeFiles.size > 100) selectSamples(episodeFiles, 100) else episodeFiles
```
`selectSamples` takes an **evenly-spread** subset (indices `0, step, 2·step, …`), so for a ~260-episode
series only ~100 episodes are stored in `MediaItem.episodes`, scattered across every season. Both detail
tabs render straight from that list — Seasons & Episodes (`item.episodes.groupBy { seasonNumber }`) and
the Artwork tab's per-episode stills (`for (ep in item.episodes)`) — so the same episodes are missing in
both, throughout all seasons.

Worse, **no UI action currently recovers them**:
- **Re-pull from Jellyfin** (`rescanFromJellyfin` → `scanItem` → `scanSeries`) re-applies the same cap.
- **Per-season ↻ Sync** (`syncSeason`) only re-probes episodes *already stored* — it doesn't discover
  missing files from disk.
- The uncapped path **`syncSeriesEpisodes()`** (re-walks the folder, probes every file) exists and is
  reachable at `POST /api/media/{id}/sync` (scope=`episodes`) — but its modal (`showSyncModal`) is **not
  wired to any button** (it appears to be the old "Sync ↻" that Phase 25 replaced with the now-capped
  "Re-pull from Jellyfin").

## Goal
Large series are fully populated, and the operator can force a complete, uncapped episode re-probe on
demand.

## Requirements
### A. Make the cap configurable — default unlimited
1. Add `[behavior] scan_episode_cap` (`Int`, default **0 = unlimited / probe all**) to `AppConfig`
   (`BehaviorConfig`). The library scan probes every episode unless a positive cap is set.
2. `Scanner.scanSeries()` reads the cap from config: cap only when `scan_episode_cap > 0 &&
   episodeFiles.size > scan_episode_cap`, otherwise probe all. Keep `selectSamples` for when a cap is set.
3. Surface it in **Settings → Scanning** (a number field, `0 = unlimited`), alongside workers/threads.

### B. On-demand uncapped re-scan (always full)
1. Wire a visible **"Re-scan all episodes"** action on the Series detail **Seasons & Episodes** tab
   (and offer it in the empty "no episode data" state) that opens the existing `showSyncModal` → **Full
   sync** → `syncSeriesEpisodes()` (`/api/media/{id}/sync` scope=`episodes`). This re-walks the folder
   and probes **every** file regardless of `scan_episode_cap`.
2. `syncSeriesEpisodes` stays uncapped (it already is) — a targeted, user-initiated action always
   prioritises completeness over scan speed.

### C. Consistency
With the default cap unlimited, the full scan and **Re-pull from Jellyfin** also become complete for big
series (both go through the config cap). The on-demand re-scan (B) remains the explicit "fix it now"
button independent of the config.

## Invariants
- A targeted per-item episode sync probes **all** episodes on disk — never sampled.
- Default behaviour is full coverage (`scan_episode_cap = 0`); sampling happens only when an operator
  sets a positive cap for very large libraries.
- Episode discovery stays a filesystem walk of the series directory (constitution) — the cap only limits
  how many of the found files get `ffprobe`d.

## Out of scope
- Changing `selectSamples` (retained for the opt-in cap path).
- Per-season *discovery* of missing files (the series-level re-scan covers all seasons; `syncSeason`
  keeps re-probing already-known episodes for a fast single-season refresh).

## Design reference
`Scanner.kt` `scanSeries` (cap ~L207–212), `selectSamples`, `syncSeriesEpisodes` (~L400);
`AppConfig.kt` `BehaviorConfig`; `MediaDetail.kt` `buildEpisodesTab` + `showSyncModal` (wire a button);
`Settings.kt` Scanning section.
