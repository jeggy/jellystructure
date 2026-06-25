# Phase R56 — Image-based subtitle parity (PGS/VobSub/DVDSub) (FR-RV-S2)

## Problem
Image-based subtitles — **PGS** (Blu-ray), **VobSub/DVDSub** (DVD) — are common in movie
rips and **never display** on Ravilo. They can't be converted to WebVTT (they're bitmaps),
so R55's sideload path doesn't reach them, and Ravilo streams every item as a raw
direct-play container with no codec/subtitle negotiation, so the server is never asked to
make them renderable. This is the remaining gap to subtitle parity with the official
Jellyfin Android TV client.

## Background — how the official Jellyfin Android TV client does it
- It sends a **`DeviceProfile`** to Jellyfin `PlaybackInfo` declaring which subtitle formats
  it can render directly (`SubtitleProfiles` with method `Embed`/`External`/`Hls`) vs. not.
- Jellyfin returns, per subtitle stream, a **`DeliveryMethod`** (`Embed` | `External` |
  `Hls` | `Encode`) and a `DeliveryUrl`, plus a `TranscodingUrl` for the video.
- Native path: as of **v0.19.0 (Oct 2025)** it renders **VobSub/DVDSub without transcoding**;
  PGS is supported by Media3 but flaky (lag/crashes).
- Fallback path: a sub the client can't render is **burned into the video** (`Encode`),
  which forces a **full video transcode** (CPU-heavy — PGS burn-in spikes the server). The
  byte stream is still Jellyfin's (a Jellyfin `TranscodingUrl`).

## Current state (as-is)
- `PlaybackService.startPlayback()` builds a hardcoded **direct-play** URL
  (`…/Videos/{id}/stream?Static=true…`) with an explicit **`TODO(R14)`: call PlaybackInfo
  with a device profile for proper codec negotiation** (`PlaybackService.kt:32,62`).
- `JellyfinClient` has **no** `PlaybackInfo` call; `JellyfinMediaStream` (auth/Models.kt:112)
  has no `DeliveryMethod`/`DeliveryUrl` fields.
- Stock Media3/ExoPlayer 1.8.0 cannot reliably render embedded PGS; nothing configures the
  extractor to parse image subs during extraction.
- R55 delivers the **cue render surface** (`SubtitleView`) this phase depends on for the
  *native* path; **burned-in** subs need no surface (they're part of the video).

## Requirements

### A. Native in-container image subs (no transcode)
1. Configure the Android ExoPlayer media-source/extractors factory to **parse subtitles
   during extraction** so MatroskaExtractor surfaces VobSub/DVDSub (and PGS where stable) as
   TEXT tracks that render through the R55 `SubtitleView` (`RaviloPlayerAndroid.kt` builder).
2. These formats are declared **`Embed`-capable** in the device profile (B) so the server
   leaves them in the direct-play container instead of transcoding.

### B. PlaybackInfo + DeviceProfile negotiation (backend)
3. `JellyfinClient.getPlaybackInfo(baseUrl, token, userId, itemId, subtitleStreamIndex?)` →
   `POST /Items/{id}/PlaybackInfo?UserId=…` with a `DeviceProfile` JSON declaring
   `SubtitleProfiles` (external `vtt`/`srt`/`ass`; embed `vobsub`/`dvdsub`/`pgs`) and
   direct-play/transcode codec support. Add DTOs (`PlaybackInfoResponse`, `MediaSourceInfo`
   with `TranscodingUrl`, per-`MediaStream` `DeliveryMethod`/`DeliveryUrl`) to
   `auth/Models.kt` and extend `JellyfinMediaStream`.
4. `PlaybackService.startPlayback()` drives the stream URL off PlaybackInfo (direct-play vs
   `TranscodingUrl`) instead of the hardcoded `Static=true` URL, replacing the `TODO(R14)`.
   For an image sub the client can't render, request **burn-in** (`SubtitleMethod=Encode`,
   `SubtitleStreamIndex=…`) → HLS `TranscodingUrl`.

### C. Shared model + client re-stream on selection
5. Extend shared DTOs (`shared/.../tv/Models.kt`): `SubTrack.deliveryMethod`
   (`embed`|`external`|`encode`|`hls`) + a transcode/stream-index carrier on `StreamTicket`,
   so the client knows which subtitle needs a fresh stream.
6. Add an endpoint + `PlayerStore` method so selecting an **`encode`** sub re-requests a
   ticket with that `SubtitleStreamIndex` and re-`load()`s the player **at the current
   position** (burned-in subs can't be toggled client-side). `embed`/`external` subs keep
   using `player.selectSubtitleTrack()` (R55 path). Touch `PlayerStore.kt`,
   `PlayerScreen.kt` `choosePick()` (line 210), `TvRoutes.kt`, `TvApiClient.kt`.
7. Chrome: show a brief "burning in subtitle… (transcoding)" state on an `encode` switch,
   since it restarts the stream and is server-CPU-heavy.

## Invariants
- **Data plane stays Jellyfin-direct.** Even a burn-in transcode streams from a Jellyfin
  `TranscodingUrl`; jellystructure stays out of the byte path (control/PlaybackInfo only).
- **Prefer no-transcode.** Use native `Embed` rendering wherever the player can; reserve
  `Encode` burn-in for image subs that won't render — it forces a full video transcode.
- **Renders server-pushed state only**; the server (PlaybackInfo) decides delivery method,
  not the client.

## Out of scope
- Quality/bitrate ladder selection beyond direct-play vs the PlaybackInfo-chosen transcode.
- Web image-sub rendering (no JASSUB/libass image path yet) — Android-first.
- Trickplay, offline, PiP.

## Acceptance
- A PGS or VobSub movie shows the subtitle in the picker; selecting a VobSub/DVDSub renders
  natively with no transcode; selecting PGS (where native fails) burns in via transcode and
  renders; switching back to a text sub or Off restores direct-play. PlaybackInfo response is
  logged once while iterating the device profile against the live server. Server CPU confirms
  the burn-in path is exercised only for the `Encode` case.
