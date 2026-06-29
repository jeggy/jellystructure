# Phase 100 — Artwork: confirm logo on-disk + make banner work (candidates + on-disk) (FR-AM5)

## Problem
On the detail page **Artwork** tab, Poster and Background work perfectly, but:
- **Logo (clearlogo):** always reads as missing — after selecting a logo the gallery still does **not**
  show it as **ON DISK**, so the pick feels like it didn't take.
- **Banner:** the candidate options list is **always empty**, and an uploaded/URL banner never shows as
  on-disk either.

Poster/Backdrop work because they have a complete, symmetric chain: a TMDB image category → an on-disk
file (`poster.jpg`/`fanart.jpg`) → an `ArtworkStatus` field → a stored `MediaItem` path that lets the
gallery move the **ON DISK** ribbon. Logo and Banner each break a different link in that chain.

## Findings

### Asset identifiers agree (no string mismatch)
`buildArtTargets` (`MediaDetail.kt:2108–2114`) defines four asset strings sent verbatim to the backend:
`poster`, `backdrop`, `clearlogo`, `banner` (`galleryFetch` `:2210`, `gallerySave` `:2216`,
`saveArtworkCandidate`). Backend filenames (`ArtworkDownloader.assetFilename` `:227–233`):
`poster.jpg`, `fanart.jpg`, `clearlogo.png`, `banner.jpg`. Frontend and backend match on all four — the
bugs are **missing wiring on the logo/banner branches**, not a typo.

### Two on-disk surfaces
- **Left rail** dot (`buildArtTargets` `:2115–2121`) from `GET /api/media/{id}/artwork` → `ArtworkStatus`:
  ```kotlin
  t.onDisk = when (t.asset) {
      "poster"    -> status.posterExists
      "backdrop"  -> status.fanartExists
      "clearlogo" -> status.logoExists
      else        -> t.onDisk          // banner: NEVER set → false forever
  }
  ```
- **Gallery card "ON DISK" ribbon** (`renderArtGallery` `:2294–2298`) is per-candidate from `c.onDisk`.

### Logo root cause — the gallery never confirms the pick
The logo file **is** downloaded and written correctly (`clearlogo.png`); `check()` (`ArtworkDownloader.kt:62–69`)
stats the same `clearlogo.png`, so `status.logoExists` and the **left-rail dot** do work (optimistically
and on reload). What's broken is the **gallery ribbon**:
1. `POST /artwork/candidates/save` records a chosen-path only for poster/backdrop, **never clearlogo**
   (`MediaRoutes.kt:567–571`).
2. `GET /artwork/candidates` computes `onDiskSource` only for poster/backdrop; for clearlogo it's `null`
   (`MediaRoutes.kt:539–543`), so `mapCandidates` (`:127–137`) marks **every** logo candidate
   `onDisk=false` → no card is ever badged.
3. The response **does** carry a correct `onDiskExists` for clearlogo (and banner) (`MediaRoutes.kt:523–529`),
   but `ArtworkCandidatesResponse.onDiskExists` (`MediaApi.kt:56`) is **never read** in the frontend — the
   correct signal is thrown away.

So a logo pick writes the file but gives no gallery confirmation — exactly the reported symptom.
(Phase 81 deliberately left clearlogo/banner without a stored `MediaItem` field — "their on-disk state
stays file-existence based" — which is the gap this phase closes.)

### Banner root cause — two independent defects
- **B1 — candidate list always empty (by construction):** `GET /artwork/candidates` maps banner → `null`
  (`MediaRoutes.kt:533–538`). TMDB has **no banner category** (`getImages` returns posters/backdrops/logos
  only — verified live: `/movie/603/images` → `logos: 58`, `banners: 0`), and **no fanart.tv/TVDB provider
  is wired**. So the gallery permanently shows empty; only Upload/Paste-URL can set a banner.
- **B2 — on-disk never reflected:** `ArtworkStatus` has **no `bannerExists`** (`ArtworkDownloader.kt:27–32`,
  `MediaApi.kt:36–40`); `check()` never stats `banner.jpg` (`:62–69`); `buildArtTargets` has no banner
  branch (`MediaDetail.kt:2120`, `else -> t.onDisk`). The save path writes `banner.jpg` but **there is no
  read path** — so even a successful banner upload/URL shows "missing" forever.

## Goal
Selecting a logo confirms **ON DISK** in the gallery just like poster/backdrop; a banner can be set via
**upload/paste-URL** and reliably shows as on-disk; and the banner gallery shows honest
upload/URL-only empty-state copy instead of a silent blank.

> **Decision (locked):** banner is **upload/URL-only** — no external candidate provider is wired in this
> phase (option **C2** below). Wiring fanart.tv/TVDB (**C1**) is recorded as a possible future phase, not
> built here.

## Requirements

### A. Logo — confirm the pick in the gallery
Pick **one** of these (A1 preferred — mirrors the poster/backdrop pattern most closely):
1. **A1 — record + read clearlogo provenance.** In `POST /artwork/candidates/save`, when asset is
   `clearlogo`, write a provenance sidecar recording the chosen TMDB `file_path` (mirror
   `ArtworkDownloader.writeStillSrc`/`readStillSrc` `:201–211`). In `GET /artwork/candidates`, read it back
   as `onDiskSource` for clearlogo (`MediaRoutes.kt:539–543`), so `mapCandidates` badges the matching
   candidate `onDisk=true`. No new `MediaItem` field needed.
2. **A2 — consume `onDiskExists`.** Have the gallery read the already-correct response-level `onDiskExists`
   (`MediaApi.kt:56`) and show a header/rail "on disk" indicator for clearlogo (and banner), decoupled
   from per-candidate matching. (Lighter, but doesn't highlight *which* candidate is current.)
   Recommended: **A1** (full parity — the current logo is badged in the gallery), optionally plus A2 as a
   belt-and-braces header indicator.

### B. Banner — on-disk read path (B2; small, high value)
1. Add `bannerExists` to `ArtworkStatus` in **both** `ArtworkDownloader.kt:27–32` and `MediaApi.kt:36–40`.
2. Set it in `check()` (`:62–69`) via `SystemFileSystem.exists(Path("$dir/banner.jpg"))`.
3. Add `"banner" -> status.bannerExists` to the `buildArtTargets` `when` (`MediaDetail.kt:2120`).
   After this, an uploaded/URL banner correctly shows the rail dot and survives reload.

### C. Banner — candidate source: upload/URL-only (B1; **chosen = C2**)
- **C2 (chosen): formally accept banner as upload/URL-only.** Keep the empty candidate list but replace
  the silent emptiness with explicit empty-state copy ("No banner providers configured — upload an image
  or paste a URL"), and rely on **B** so a manually-set banner reads as on-disk. The Upload / Paste-URL
  controls must clearly be the way to set a banner. No external provider, no new API key.
- **C1 (future, not in this phase): wire a real banner provider.** Should banners ever need an auto-
  populated gallery, add fanart.tv (`moviebanner` / `tvbanner`; the standard banner source used by
  Jellyfin/Kodi) — and/or TVDB — into `GET /artwork/candidates` for the `banner` branch
  (`MediaRoutes.kt:533–538`), shaped like the TMDB candidates. Requires a provider API key in config and
  the existing `ArtworkDownloader.Semaphore(8)` FD ceiling. **Deferred — recorded for a future phase, not
  built here.**

## Scope
- `src/linuxX64Main/.../media/ArtworkDownloader.kt` — `ArtworkStatus.bannerExists` + `check()` stat;
  clearlogo provenance sidecar (A1).
- `src/linuxX64Main/.../server/routes/MediaRoutes.kt` — clearlogo `onDiskSource` read (A1); banner
  `onDiskExists`; banner upload/URL-only empty-state copy (C2).
- `src/wasmJsMain/.../api/MediaApi.kt` — `ArtworkStatus.bannerExists`; consume `onDiskExists` if A2.
- `src/wasmJsMain/.../ui/MediaDetail.kt` — `buildArtTargets` banner on-disk branch; gallery ribbon for
  clearlogo (A1/A2); banner empty-state copy (C2).

## Non-goals
- No change to poster/backdrop behaviour (the working reference path).
- No change to the episode-still pipeline (Phase R131) or the textless filter (Phase 48).
- **No external banner provider** — banner stays upload/URL-only (C2). fanart.tv/TVDB (C1) is a deferred
  future phase.

## Acceptance
- **Logo:** selecting a clearlogo candidate badges it **ON DISK** in the gallery (and the rail dot stays
  lit) immediately and after reload — parity with poster/backdrop.
- **Banner on-disk:** after an upload/URL banner save, the Banner rail row shows on-disk and survives a
  reload (no longer "missing forever").
- **Banner candidates:** the gallery shows explicit "upload/URL-only" empty-state copy instead of a silent
  blank, and Upload / Paste-URL set the banner.
- No regression to poster/backdrop on-disk status or selection.
