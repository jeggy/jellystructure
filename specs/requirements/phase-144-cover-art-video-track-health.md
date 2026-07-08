# Phase 144 — Cover-art-muxed-as-video: detect, triage & repair (FR-TK2)

> Some releases mux a `cover.png` as a **second, non-attached-picture video stream**. The file is
> technically malformed and broke Ravilo playback until the client-side fix
> ([research report](../research-reports/ravilo-cover-art-video-track-2026-07-08.md), Ravilo commit
> `482a336`). This phase gives jellystructure **library-health management** for the pattern — detect it
> at scan time, surface it in Triage like the other file-health issues, and offer a one-click **repair**
> that drops the junk cover stream — so the whole library is clean for *any* client, not just Ravilo.
> Follows the same model as Phase 128 (zero-audio diagnosis) and Phase 120 (multi-default-audio "keep
> this one"); reuses the write-through track-edit infrastructure (`TrackRoutes` + `FfmpegRunner`,
> `SeedingGuard`, background jobs). Related: R173's SurfaceView switch (the diagnostic caveat in the
> report), Phase 87 (audio-flag pagebar) is untouched.

## Problem

`Server.Farm.S01E01…TURG.mkv` (and every S01 episode of that release) carries two video streams:

```
0:v:0  h264  1920x1080  default=1   (the episode)
0:v:1  png    857x856   default=0   cover.png / MIMETYPE=image/png   ← muxed as a *video* track
```

The cover is **not** flagged as an attached picture, so it reads as a genuine, selectable video track.
Jellyfin negotiates DirectPlay (it counts only the one real video stream), jellystructure hands the raw
MKV to the player, and Media3 exposes the png as `video/x-unknown`. On Ravilo this left the player with
no working video decoder until the client fix landed (`482a336`, hardware-only video renderer). But:

- jellystructure has **no awareness** of the malformed track today. `MediaItem.tracks` already records
  the `png` VIDEO track (verified), but nothing flags it, and it does not appear in any Triage bucket.
- The file remains malformed for **other** clients (other Jellyfin apps / players can mishandle it the
  same way), so a durable fix is to repair the library, not only harden one client.

This is the same class of "the scan sees a real file problem but doesn't surface it" that Phase 128
(zero-audio) addressed.

## Current state (verified)

- `Track` (`model/Media.kt:32-41`) stores `streamIndex, specifier, kind (VIDEO/AUDIO/SUBTITLE/DATA),
  codec, language, title, default, forced` — enough to detect an image codec on a VIDEO track (no
  resolution/attached_pic stored, and none is needed).
- `TriageDetection` (`media/TriageDetection.kt`) is the O(1)-metadata predicate home
  (`hasMultiDefault`, `zeroAudioCount`, …); `TriageRoutes.kt` builds the `TriageTypeCount` list (e.g.
  `zero_audio` at line 148, `multi_default` at 130) that feeds the Dashboard breakdown + Library
  `filter=`, and `Shell.kt` renders the Triage-dock subline per type.
- Repair infra exists: `TrackRoutes.kt` + `FfmpegRunner` already do `-c copy` remuxes (the reorder op),
  gated by `SeedingGuard`, run as background `MediaJobQueue` jobs with WS progress, followed by a
  re-probe + `store.updateOne` + Jellyfin refresh. A cover-drop is the same shape.

## Design

### A. Detection (scan-time, O(1))
1. `TriageDetection.coverAsVideoCount(item)` (+ a movie/episode-aware wrapper like the others): count
   VIDEO tracks whose `codec` is an **image codec** — `png`, `mjpeg`/`mjpg`, `bmp`, `gif` — when the item
   also has a real (non-image) video track. That pairing is the cover-as-video signature. Pure read over
   stored tracks; no reprobe.

### B. Surface in Triage (mirror `zero_audio`)
2. Add `TriageTypeCount("cover_as_video", "Cover art muxed as a video track", …)` to `TriageRoutes.kt`,
   include it in the per-item triage detail, the Dashboard "items needing attention" breakdown, the
   Library `filter=cover_as_video` set (`TriageDetection` + the `library.html` filter map), and a
   `Shell.kt` triage-dock subline ("cover art muxed as video — playback-hostile, repairable").

### C. Repair (one write-through action)
3. A **"Fix cover track"** operation on the movie/episode detail (Tracks tab) and offered inline from
   the Triage item. It removes the image video stream via an `ffmpeg -map 0 -map -0:v:<n> -c copy`
   remux (fast, no re-encode) — reusing `FfmpegRunner` + `SeedingGuard` + the background-job + re-probe
   + Jellyfin-refresh path the reorder op already uses. (MKV can't drop a track via `mkvpropedit`, hence
   the ffmpeg remux; the operation is "slow (remux)" like reorder, and the command preview shows exactly
   what runs, matching the track-editor convention.)
4. After the remux + re-probe, the item's tracks no longer contain the image video stream and it drops
   out of the `cover_as_video` triage bucket automatically.

## Non-goals
- **Any client change** — Ravilo already handles these files (`482a336`, hardware-only video). This
  phase is library-health/management only.
- **Forcing a Jellyfin transcode** at `PlaybackInfo` time (heavier, gives up direct-play) — rejected in
  the research report in favour of repairing the file once.
- Touching correctly-formed cover art: a real **attached picture** (`attached_pic`) or an MKV
  **attachment** is fine and must not be flagged — only an *image codec on a selectable video track*
  qualifies.
- Re-encoding video/audio — the repair is a `-c copy` stream drop only.
- Auto-repairing during scan — repair stays an explicit operator action (like every other track edit).

## Acceptance
- A scanned item whose tracks include an image-codec VIDEO stream beside a real video track is counted
  under a new `cover_as_video` Triage type (Dashboard breakdown + `Library?filter=cover_as_video` +
  Triage-dock subline); a clean single-video file is not.
- The Tracks tab / Triage item exposes a **Fix cover track** action; running it remuxes the file
  (`-c copy`, cover stream dropped), re-probes, and the item leaves the `cover_as_video` bucket — verified
  on the live Server Farm S01 episodes.
- Detection is O(1) over stored track metadata (no reprobe); unit-tested (image-video-beside-real-video
  ⇒ flagged; single video ⇒ not; attached-picture/attachment ⇒ not).
- `SeedingGuard` blocks the repair while the file is seeding, same as other track edits.
- Verified via `compileKotlinLinuxX64` + `linuxX64Test` + admin `compileKotlinWasmJs`.
