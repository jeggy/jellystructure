# Phase R194 — Ravilo TV: media-session artwork uses the season poster, not an episode still (FR-RV-SESS3)

> Direct follow-up to **R193** (rich media-session metadata). R193 shipped artwork resolution as
> "current episode's still, falling back to the series/movie poster." The user's actual ask, given
> right after R193 landed: for a series, always prefer the **season's own poster** over an episode
> still, and fall back to the **series' own poster** (not an episode still at all) when the season has
> none. Series playback never uses an episode still for session artwork after this phase.

**Status:** Implemented.

## Requirements

### FR-RV-SESS3-1 — Backend: serve a season's own poster
New route `GET /api/tv/image/{itemId}/season/{season}/poster` (`TvRoutes.kt`, alongside the existing
`still`/`poster`/`backdrop`/`logo` routes — covered by the same `/api/tv/image/` `OPEN_API_PATHS`
prefix, no auth-plugin change needed). Backed by `RaviloArtworkService.serveSeasonPoster(itemId,
season, width)`, sourcing from `ArtworkDownloader.seasonPosterPath` (widened from `private` to
`internal` — same module, `dev.jellystructure.tv` needs it) — the same on-disk `seasonNN-poster.jpg`
asset the admin's season-poster picker (R126/Phase 151's manual-lock) already manages. Reuses
`resizeServe`'s existing `"poster"` sizing/caching path (320px default, cached by source-file-size
signal like every other image route here).

### FR-RV-SESS3-2 — `Season.posterUrl` is null (not a URL that would 404), when there's no season poster
`Season` (shared `Models.kt`) gains `posterUrl: String?`. `DetailService` sets it only when
`artwork.checkSeasonPoster(item, seasonNum)` is true — deliberately **not** always emitting a URL and
letting the client discover a 404, since every other image field in this DTO is "non-null ⇒ valid,"
and a native `MediaMetadata.setArtworkUri` has no fallback mechanism of its own if the URI 404s (this
isn't a `RemoteImage`/Coil load with an error painter — it's handed straight to the OS). Requires
`DetailService` to hold an `ArtworkDownloader` (new constructor param; one call site, `Main.kt`).

### FR-RV-SESS3-3 — Client: thread season + series poster URLs down to the player, in priority order
- `PlayerEpisodeEntry` (client model) gains `seasonPosterUrl: String?`, populated in
  `SeriesDetailScreen.buildEpisodeContext` from `detail.seasons[seasonIdx].posterUrl` — every entry in
  one `buildEpisodeContext` call belongs to the same season (it's already called per-season), so a
  single value applies to the whole `episodes` list built there.
- `EpisodePlayContext` gains `seriesPosterUrl: String?` (from `detail.card.posterUrl`), threaded into
  the existing `Dest.Player.posterUrl` field (R193) at its one call site (`RaviloApp.kt`'s
  `SeriesDetail.onPlay`) and carried forward through the auto-advance `replaceTop(Dest.Player(...))` —
  same series, same fallback poster, for the whole binge.
- `PlayerScreen`'s artwork resolution becomes: `episodes[currentEpIndex].seasonPosterUrl ?: posterUrl`
  — **no `stillUrls` in this chain at all** (R193's design is superseded here for the series case;
  `stillUrls` remains used elsewhere, e.g. the in-player episode rail thumbnail — unaffected).
  `posterUrl` alone already covers both the series-poster fallback (episodes, via the above threading)
  and the movie-poster case (R193, unchanged) — one fallback field serves both.
- Both `PlayerScreen` and `LiveTvPlayerScreen` gain a local `resolveImageUrl()` (reads
  `LocalServerBaseUrl.current`, prefixes a relative `/api/tv/image/...` path) — needed because the OS
  media session's artwork URI bypasses Coil/`RemoteImage` (which already does this resolution for
  on-screen art) entirely.

## Invariants
- **A missing season poster never shows a broken/blank artwork slot** — the fallback chain always
  lands on a real, already-known-valid URL (the series poster) or `null` (no artwork at all,
  cosmetic), never a URL that might itself 404.
- **This phase changes only OS media-session artwork resolution.** No on-screen Compose UI (season
  picker, episode rail, detail hero) is touched — the season poster surfaces nowhere else in Ravilo
  yet, deliberately scoped to this one consumer.

## Out of scope
- Showing the season poster anywhere in Ravilo's own on-screen UI (season picker thumbnails, etc.) —
  not asked for; this phase only threads it to the OS media session.
- Backfilling season posters that don't exist on TMDB/disk — `ArtworkDownloader`'s existing R126
  gap-fill (during `fetch`) is unchanged; this phase only reads whatever's already there.

## Dev-review addendum (2026-08-10 — implementation notes)
1. Verified via `compileKotlinLinuxX64`, `linuxX64Test` (backend), `:ravilo-ui:compileDebugKotlinAndroid`,
   `:ravilo-ui:compileKotlinWasmJs`, `:ravilo-web:compileKotlinWasmJs`, `:ravilo-android:compileDebugKotlin`,
   `:ravilo-phone:compileDebugKotlin` — all pass. Not on-device/live-scan verified this session (backend
   restart + on-device deploy are both user-initiated, per standing preference).

## Source references
- `src/linuxX64Main/kotlin/dev/jellystructure/media/ArtworkDownloader.kt` — `seasonPosterPath`
  visibility widened to `internal`.
- `src/linuxX64Main/kotlin/dev/jellystructure/tv/RaviloArtworkService.kt` — new `serveSeasonPoster`.
- `src/linuxX64Main/kotlin/dev/jellystructure/tv/RaviloImageUrl.kt` — new `seasonPoster(seriesId, season)`.
- `src/linuxX64Main/kotlin/dev/jellystructure/server/routes/TvRoutes.kt` — new
  `/tv/image/{itemId}/season/{season}/poster` route.
- `src/linuxX64Main/kotlin/dev/jellystructure/tv/DetailService.kt` — `Season(...)` construction now
  sets `posterUrl`; new `artwork: ArtworkDownloader` constructor param.
- `src/linuxX64Main/kotlin/dev/jellystructure/Main.kt` — `DetailService(...)` call site.
- `shared/src/commonMain/kotlin/dev/jellystructure/shared/tv/Models.kt` — `Season.posterUrl`.
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/PlayerEpisodeEntry.kt` —
  `PlayerEpisodeEntry.seasonPosterUrl`, `EpisodePlayContext.seriesPosterUrl`.
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/SeriesDetailScreen.kt` —
  `buildEpisodeContext` populates both new fields.
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/RaviloApp.kt` — threads
  `ctx.seriesPosterUrl` into `Dest.Player.posterUrl` (initial play + auto-advance).
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/PlayerScreen.kt` — artwork
  priority now `seasonPosterUrl ?: posterUrl`; new `resolveImageUrl()`.
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/LiveTvPlayerScreen.kt` — same
  `resolveImageUrl()` applied to the channel logo (was passed unresolved in R193).
- Related: **phase-R193-mediasession-rich-metadata-tv-only.md** (the artwork-resolution design this
  phase supersedes for the series case), **phase-R192-mediasession-background-cleanup.md**.
