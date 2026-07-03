# Phase 121 — Triage: "missing episode overview" → "missing episode still image" (FR-TR2)

## Goal
A missing TMDB plot summary is **not** a real problem: the screen-grabber (R131) generates an episode
still, so Ravilo always has an image to show. Reframe the triage type from "episode has no plot text"
to **"episode has no still image available at all"** (neither a downloaded TMDB still nor a screen-grab
on disk) — the genuine "Ravilo shows a blank episode card" case. The current "Episodes with no plot
summary from TMDB: 456 (38 titles)" is noise and should stop being flagged.

## Current state (verified in code)
- `TriageDetection.missingOverviewCount` (`TriageDetection.kt:36-37`) counts
  `item.episodes.count { it.overview.isNullOrBlank() }` (TMDB plot text, TV only). Mirrored in
  `TriageRoutes.toTriageItem` (`:284`) and the `MediaStore.list` filter branch
  (`"missing_overview"`, `:278`).
- Model (`src/commonMain/kotlin/dev/jellystructure/model/Media.kt`): `Episode.overview: String?` (plot)
  and `Episode.stillPath: String?` (`:64` — TMDB metadata **URL** path, **not** a disk-existence flag).
  **No persisted `hasStill`.** Episodes are JSON blobs — additive fields need no migration.
- Still pipeline: TMDB still and screen-grab **both write the same disk path**
  `<episodeDir>/<basename>-thumb.jpg` (`ArtworkDownloader.episodeStillPath:211`); a `.src` sidecar
  records provenance (`tmdb`|`screengrab`|`manual`). `fetchEpisodeStill:166` prefers the TMDB download
  (`ep.stillPath`), else `Screengrabber.grabEpisodeStill` (ffmpeg frame @ ~20% runtime); called for all
  episodes by `fetch(item)` (R125). "Does a still exist on disk" = `checkEpisodeStill(ep).stillExists`
  = `SystemFileSystem.exists(episodeStillPath(ep))` (`:160`) — a **filesystem stat, not persisted**.
- Ravilo reads `GET /api/tv/image/{itemId}/still/{epFilename}` (R133, `TvRoutes.kt:577`) → `serveStill`
  → **404 when no file on disk**. So "no image to show" ≡ `!checkEpisodeStill(ep).stillExists`; a
  missing overview never blocks the image.
- Pre-existing bug: `MediaDetail.kt:1206,1211` shows a "missing still" badge computed as
  `ep.stillPath.isNullOrBlank()` (TMDB-metadata only) — it falsely flags episodes that HAVE a
  screen-grab.

## Requirements

### A. Persist a still-existence flag (performance — the backend consideration the reframe requires)
1. Add `hasStill: Boolean = false` to `Episode` (additive JSON field, no migration). Set it wherever a
   still is written (TMDB download / manual save / screen-grab success → `true`) and refresh it on scan
   when building episodes (via `checkEpisodeStill`, mirroring the existing per-episode check at
   `MediaRoutes.kt:640`).
2. Rationale: current triage predicates are O(1) in-memory. A filesystem stat per episode on the **hot**
   `MediaStore.list ?filter=` path (flagged by the backend-performance investigation) is a regression —
   a persisted flag keeps both `/triage/count` (memoized by `libraryVersion`) and Library filtering
   O(1). A re-pull swapping screen-grab→TMDB keeps it `true` (fine); an out-of-band deletion goes stale
   until the next scan (acceptable — triage is advisory).

### B. Replace the triage type
1. `TriageDetection`: `missingOverviewCount` → `missingStillCount(item)` = TV episodes with `!hasStill`.
2. `TriageRoutes`: rename the type key `missing_overview` → `missing_still`, label **"Missing episode
   image"**, description e.g. "Episode has no still image — no TMDB still and no screen-grab — so Ravilo
   shows a blank episode card." Update `toTriageItem` and the `EpisodeTriageItem` field.
3. `MediaStore.list`: filter value `missing_still` → `hasStill == false` predicate. Update
   `Library.kt ISSUE_FILTER_LABELS`, `Shell.kt` subline, `MediaApi.EpisodeTriageItem`. The Dashboard is
   server-driven (renders `count.types` generically) → no hardcoded label to change.
4. **Drop "no plot text" as an issue entirely** (the user's explicit intent). A low-key detail-only
   badge on the episode row is out of scope unless trivially free.

### C. Fix the misleading detail badge
1. `MediaDetail.kt:1206,1211` "missing still" badge → use `!ep.hasStill` (on-disk truth) instead of
   `ep.stillPath.isNullOrBlank()`, so screen-grabbed episodes stop being falsely flagged.

## Scope
- Model: `Episode.hasStill` (commonMain, additive).
- Backend: scanner/artwork sets `hasStill`; `TriageDetection`, `TriageRoutes`, `MediaStore.list`.
- FE: `Library.kt`, `Shell.kt`, `MediaApi.kt`, `MediaDetail.kt`. No migration.

## Non-goals
- No change to the screen-grab pipeline itself.
- No new "missing plot text" issue surface.
- No stills for movies (episode-only, as today).

## Acceptance
- The dashboard/triage no longer reports the 456 "missing plot summary" episodes as issues; episodes
  with a TMDB still OR a screen-grab are clean.
- Only episodes with genuinely no image on disk count as "Missing episode image";
  `#/library?filter=missing_still` lists exactly those titles.
- The media-detail episode "missing still" badge matches on-disk truth (screen-grabbed episodes not
  flagged).
- Counting and Library filtering stay O(1) (no per-request filesystem stat).
