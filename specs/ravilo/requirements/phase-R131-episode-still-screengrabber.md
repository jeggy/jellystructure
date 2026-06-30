# R131 — Episode-still screen-grabber (lowest-priority placeholder, auto-upgraded by TMDB) (FR-AM4)

> Builds on **Phase 47** (artwork manager), **R124**
> (on-disk artwork truth), **R125** (episode stills are part of `fetch()`), and the existing
> `FfmpegRunner`/`FfprobeRunner`. Our version of Jellyfin's thumbnail extractor.

---

## Problem

Episodes frequently have **no TMDB still** (less-popular shows, anime, foreign content), so their
thumbnails are blank in Ravilo's Continue / episode rows. There was no fallback — if `ep.stillPath` was
null, `fetchEpisodeStill` wrote nothing.

## Priority model

`manual` (highest, never auto-touched) > `tmdb` (auto) > `screengrab` (lowest, a placeholder) > none.
Recorded as a one-word `.src` sidecar next to each still (`<basename>-thumb.jpg.src`, mirroring the
image-proxy `.ct` sidecar). Scope: **episode stills only** — movies and series-level artwork are untouched.

## Requirements

1. **Generate a placeholder still from the video.** When an episode has no still on disk and no TMDB
   `stillPath`, extract a frame from the episode's own file (~20% in, to skip intros/black) → write
   `<basename>-thumb.jpg`, mark `.src = screengrab`. `Screengrabber.grabEpisodeStill` (ffprobe duration →
   `ffmpeg -ss T -i file -frames:v 1 -q:v 3 out.jpg`), bounded `Semaphore(2)`.
2. **Lowest priority — auto-upgrade.** A screen-grab is a placeholder: when a real TMDB still becomes
   available (`ep.stillPath` resolves on a later `pull_tmdb`), the **next scheduled `download_artwork`
   (missing scope)** replaces the file with the TMDB still and flips `.src → tmdb`. Driven by
   `fetchEpisodeStill` (upgrade branch) + `isArtworkIncomplete` flagging *"screengrab AND TMDB now
   available"* so the series is re-processed.
3. **Manual is permanent.** A manual pick (TMDB candidate / upload / URL via `saveEpisodeStill`) marks
   `.src = manual` and is never auto-touched.
4. **Picker preview + regenerate.** The artwork tab's episode-still gallery shows the **current on-disk
   still** (`GET /episodes/{ep}/still/file`) with a provenance badge (Screen grab placeholder / Manual /
   From TMDB), and a **"Generate frame"** button (`POST /episodes/{ep}/still/screengrab`) to grab/regenerate
   on demand (always marks `screengrab` — lowest priority; upload a frame to lock a custom one).

## Data / transport

- No new persisted field. Provenance is the `.src` sidecar (co-located, portable). `EpisodeStillStatus`
  (backend + `MediaApi` mirror) gains `source: String?`, surfaced in `/episodes/stills` + the screengrab
  response. New routes `GET …/still/file` (serve on-disk JPEG) + `POST …/still/screengrab`.
- The grabbed `-thumb.jpg` is the Jellyfin episode-thumb convention, so a `sync_jellyfin` makes Jellyfin
  read it → the R85 image proxy serves it to Ravilo (no app change).

## Scope / invariants

- Episode stills only; movies/series posters/backdrops untouched (series fall back to their poster as
  today). `ArtworkDownloader` gains a `Screengrabber` dependency (built in `Main.kt`).
- One source-of-record `.src` per still drives the filter (`isArtworkIncomplete`), the write
  (`fetchEpisodeStill`), the manual lock (`saveEpisodeStill`) and the picker label — they can't disagree.

## Out of scope

- Movie/series-level screen grabs; "best frame" selection (single ~20% frame is enough for a placeholder);
  locking a manually-generated screen-grab against upgrade (upload to keep a custom frame).

## Files

- Backend: `media/FfmpegRunner.kt` (`extractFrame`), `media/FfprobeRunner.kt` (`duration`),
  `media/Screengrabber.kt` (new), `media/ArtworkDownloader.kt` (`.src` sidecar, `fetchEpisodeStill` upgrade,
  `screengrabEpisodeStill`, `isArtworkIncomplete`, `saveEpisodeStill` → manual), `Main.kt`,
  `server/routes/MediaRoutes.kt` (`still/file`, `still/screengrab`, `EpisodeStillStatusDto.source`).
- Frontend: `ui/MediaDetail.kt` (still preview + badge + Generate button), `api/MediaApi.kt`
  (`EpisodeStillStatus.source`, `screengrabStill`).
