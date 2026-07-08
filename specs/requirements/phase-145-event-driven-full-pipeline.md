# Phase 145 — Event-driven full pipeline: run the *configured* pipeline per triggered item (FR-ING1)

> When a file lands via **Radarr/Sonarr** or **Jellyfin** (manual copy), the item should get the **exact
> same pipeline the schedule runs — whatever `[[scan.pipeline]]` is configured** (Scan → TMDB → Artwork
> → IMDb → *arr → NFO → Jellyfin) — instantly, scoped to just that item. Today the event path
> (`RealtimeIngestService`, Phase 114) runs a **hardcoded subset** (scan + TMDB + Sonarr-enrich + NFO +
> artwork push) that **omits IMDb ratings** and **ignores the configured steps/scopes**, so event-ingested
> items silently get lesser, drift-prone treatment. This unifies the two paths onto one code path and
> makes the Radarr/Sonarr trigger reliable. Builds on Phase 114 (webhooks + listener), Phase 91
> (pipeline), Phase 135 (per-step worker pools). Related: the 2026-07-08 vacuous-series-rollup finding —
> the pipeline's closing `/Library/Refresh` fixes that rollup instantly for the triggered item too.

## Problem

Two ingest paths have drifted apart:

- **Scheduled** — `executePipeline` (`Main.kt:344`) runs **every configured step** over a working set,
  ending with `triggerLibraryRefresh` (`/Library/Refresh`).
- **Event-driven** — `RealtimeIngestService.ingestByJellyfinId` (`media/RealtimeIngestService.kt`) does a
  **fixed** flow: `scanner.scanItem` (ffprobe+TMDB) → Sonarr-enrich → `stampHasStill` → `store.addOrUpdate`
  → `pushToJellyfin` (NFO + `artwork.fetch` + per-item refresh). It **never runs `sync_imdb_ratings`**,
  and it is **not driven by the configured pipeline** — change the config and the event path won't follow.

Both triggers converge on `realtimeIngest.enqueue(jellyfinId)`: the **Jellyfin `LibraryChanged` listener**
(`JellyfinLibraryListener.kt:156`, correct ids) and the **Radarr/Sonarr webhooks** (`WebhookRoutes.kt`).
The *arr path resolves the item via `getItemByPath` (`WebhookRoutes.kt:132`) after nudging Jellyfin —
but this Jellyfin ignores `?Path=`, so `getItemByPath` can return the **wrong** item, so the *arr trigger
can ingest the wrong id (or nothing).

## Current state (verified)

- `executePipeline` builds `workingSet` from `runScan(...)` (whole library, freshness-filtered), then runs
  `for (step in pipeline) { … runPipelineStepPool(…) }` over it; `signalPipelineComplete` fires the closing
  `/Library/Refresh`. It already has a `fullRun` flag that bypasses the freshness filter.
- `RealtimeIngestService` runs each ingest as its own `runTagged` activity on a dedicated 2-worker
  dispatcher, with one 60s retry. It re-scans a series parent when an episode event arrives.
- The *arr webhook already **nudges** Jellyfin via `notifyLibraryMediaUpdated` (`POST /Library/Media/Updated`,
  `JellyfinClient.kt:171`); the fragile part is only the subsequent `getItemByPath` poll+enqueue.

## Design

### A. One pipeline, two entry points
1. Give `executePipeline` a **targeted mode** — `seedItems: List<MediaItem>? = null`. When non-null: skip the
   whole-library `runScan`, set `workingSet = seedItems`, and run **every configured downstream step** over
   it exactly as the scheduled run does (treat the seed like `fullRun` — no freshness filter for the seed),
   ending with the same `signalPipelineComplete` (`/Library/Refresh`). Everything else — step order,
   `runPipelineStepPool`, per-item gates — is reused verbatim, so it does **whatever the config says**.
2. **Concurrency isolation:** a targeted run must not collide with the scheduled scan's shared
   `ScanTracker`/"already running" guard. Use a **separate `ScanTracker` instance + distinct `jobId`** for
   targeted runs (they already run under their own `runTagged` activity), so a realtime ingest and a
   scheduled scan can run at once without either tripping the other's state.

### B. RealtimeIngestService becomes a thin dispatcher
3. On a trigger: resolve to the Movie/Series `MediaItem` (the existing `scanItem` path, incl. the
   episode→parent-series rescan), then hand that item to the **targeted `executePipeline`** with the
   **current configured pipeline** — instead of the hardcoded `pushToJellyfin` flow. Keep the 2-worker
   dispatcher + one retry.
4. **Debounce bursts:** a season import fires many episode events for one series. Coalesce by series/movie
   id within a short window (e.g. a few seconds) so a 10-episode import runs the full pipeline **once** for
   the series, not ten times. (Today each event is an independent full re-scan of the parent.)
5. Wire the extra `executePipeline` dependencies into `RealtimeIngestService` (imdbClient, a targeted
   ScanTracker, the scan dispatcher, arrRescan) — most are already constructed at startup.

### C. Reliable Radarr/Sonarr trigger
6. Drop the unreliable `getItemByPath` poll+enqueue. The *arr webhook keeps nudging Jellyfin
   (`notifyLibraryMediaUpdated`); the **`LibraryChanged` listener** (which already yields the **correct**
   ids) drives the ingest. This removes the `?Path=` dependency entirely and makes *arr imports reliably
   kick off the full flow. Fallback when the listener socket is down: resolve the item by matching the
   mapped path **client-side** against a Jellyfin folder/recently-added query (our match, not Jellyfin's
   broken `?Path=`), then enqueue.

## Non-goals
- Changing which steps the pipeline runs — this makes the **event** path obey the **existing** config; the
  configured steps themselves are unchanged.
- Changing scheduled-scan behaviour, freshness cadences, or the pipeline step implementations.
- A new UI — targeted runs surface in Activity via the existing `runTagged`/pipeline events.
- Fixing Jellyfin's `?Path=` server-side (out of our control) — we route around it.

## Acceptance
- Importing a title via Radarr/Sonarr (or dropping a file into a Jellyfin library) runs the **full
  configured pipeline** for that item within seconds: TMDB metadata, **IMDb rating**, artwork (poster/
  backdrop/logo/stills downloaded, not just `stampHasStill`), NFO written, Jellyfin refreshed — verified by
  inspecting the item right after import (IMDb badge present, artwork on disk, NFO on disk).
- Changing `[[scan.pipeline]]` (e.g. removing a step) changes the event-path behaviour identically — no
  separate hardcoded flow remains.
- A Radarr/Sonarr import reliably ingests the **correct** item (no `getItemByPath` mis-resolution).
- A multi-episode import triggers **one** pipeline run for the series, not one per episode.
- A newly-added series is correct in Ravilo immediately (the closing `/Library/Refresh` recomputes its
  child rollup — no transient false "watched" ✓; complements the `8a89507` guard).
- A targeted run and a scheduled scan can run concurrently without either tripping the other's "already
  running" guard.
- Verified via `compileKotlinLinuxX64` (+ a live import test on the running backend).
