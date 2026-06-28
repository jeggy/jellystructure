# R133 — Serve Ravilo artwork from jellystructure on-disk files (retire the Jellyfin image proxy)

> Supersedes **R85** (the Jellyfin image proxy) for media. Realises WS2 of the decoupling investigation
> (`specs/research-reports/ravilo-jellyfin-decoupling-investigation.md` §9), sourced from on-disk files.

---

## Problem

Ravilo images flowed **Ravilo → `/api/tv/image/{jellyfinId}/{type}` → Jellyfin** (which resized them);
jellystructure cached the bytes. Everything runs on one box, so the round-trip bought little and caused a
class of bugs — R132 (Jellyfin returns a JSON "does not have an image" body that got cached as a broken
poster) and a write→sync→re-read race (jellystructure writes `poster.jpg`, but Jellyfin must re-read it
before the proxy can serve it). jellystructure **already has every poster/backdrop/logo/still on disk**.

## Decisions

- **ffmpeg resize + cache** (parity with the proxy: poster ~320w, backdrop ~1920w, still ~640w, logo ~300h).
- Image URLs keyed by **`MediaItem.id`** (full decouple — items not synced to Jellyfin still get art).
- **Avatars**: jellystructure fetches the Jellyfin user image once, caches it durably, serves it — the one
  remaining (cached) Jellyfin fetch — reusable by the admin pair-a-device UI.

## Design

- **`RaviloArtworkService`** (was `ImageProxyService`): `serve(id, type, w)` → `store.resolve(id)` →
  on-disk file (poster→`assetPath("poster")`, backdrop→`fanart.jpg`, logo→`clearlogo.png`) → ffmpeg resize
  → cache → bytes. `serveStill(seriesId, epFilename, w)` → `episodeStillPath`. `serveAvatar(userId)` →
  cached Jellyfin user image. Missing source file → `null` → 404 → app placeholder (no Jellyfin fallback).
- **Cache + auto-invalidation**: `$dataDir/artwork/tv/{key}` + `{key}.ct` = `"<source-byte-size>|<ct>"`.
  The source file's byte size is the change signal — any artwork rewrite (pipeline, manual pick, R131
  screengrab→TMDB upgrade) changes the compressed size, so the entry re-resizes on the next request with
  **no explicit invalidation hooks to miss**. Bounded by `behavior.tv_image_cache_mb` (R129/R130).
  Avatars cache durably under `$dataDir/artwork/avatars/` (not size-capped).
- **URLs** (`RaviloImageUrl`, was `JellyfinImageUrl`): `poster|backdrop|logo(id)`,
  `still(seriesId, epFilename)` → `/api/tv/image/{id}/still/{enc(file)}`, `avatar(userId)` →
  `/api/tv/image/user/{id}/avatar`. Builders (`DetailService`/`HomeFeedService`/`BrowseService`) repointed
  off `jellyfinId` to `item.id`; the TMDB-CDN still fallback dropped. Card **navigation** id is unchanged
  (`jellyfinId ?: id`), only the image keys changed.
- **App-transparent**: every image URL is a server-built relative string, so no Ravilo app rebuild is
  needed — only a backend relaunch.

## Coverage caveat

Today Jellyfin may serve artwork that jellystructure lacks on disk (embedded/Jellyfin-fetched images).
After R133 those items show the placeholder (hero/detail → R130 title-text; tiles → placeholder) until a
full artwork pass. **Run "Download artwork (all)" + the R131 screengrabs alongside the deploy** to
maximise on-disk coverage. There is intentionally no Jellyfin fallback.

## Out of scope / follow-ups

- Admin pair-a-device avatar thumbnails (`app/ravilo-config`) — the public `/api/tv/image/user/{id}/avatar`
  route is ready; the UI can adopt it any time.
- Season-poster serving (no Ravilo caller today).
- A faster in-process resizer (libvips/stb cinterop) if ffmpeg cold-start ever bites.

## Files

- Backend: `media/FfmpegRunner.kt` (`resizeImage`), `media/ArtworkDownloader.kt` (`episodeStillPath`
  public), `tv/RaviloArtworkService.kt` (was `ImageProxyService.kt`), `tv/RaviloImageUrl.kt` (was
  `JellyfinImageUrl.kt`), `tv/DetailService.kt`, `tv/HomeFeedService.kt`, `tv/BrowseService.kt`,
  `server/routes/TvRoutes.kt` (still + avatar routes), `server/Server.kt`, `Main.kt`.
