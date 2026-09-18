# Phase R183 — Ravilo: Dolby Vision playback + a decodable transcode fallback (bug fix, FR-RV-DV1)

> A viewer reported "Disclosure Day" (2160p DV profile 8 / HDR10+ hybrid) refusing to play in Ravilo
> while Jellyfin's own TV client plays it fine. Root-caused against the live Jellyfin server (10.11.11),
> fixed, and every negotiation outcome below re-verified live. Extends R56 (PlaybackInfo/DeviceProfile
> negotiation) and R173 (which added the `VideoRangeType` condition — and explicitly left Dolby Vision
> out as a non-goal, which is exactly what broke here).

## Bug report
2026-07-25 — playing "Disclosure Day" on Ravilo (Android) never produced a frame; Jellyfin logged
`Playback stopped reported by app "Ravilo" … Stopped at "0" ms` eleven seconds after start. The same
title plays in Jellyfin's own clients.

## Investigation (live, against jellyfin.jebster.net · Jellyfin 10.11.11)
- The file: `Disclosure Day (2026) (2160p iT WEB-DL Hybrid H265 DV HDR10+ DDP Atmos 5.1 …).mkv`, 26.6 GB.
  Jellyfin reports `hevc Main 10, 3840x1606, VideoRange=HDR, VideoRangeType=DOVIWithHDR10Plus,
  DvProfile=8, DvLevel=6, 24.4 Mbps`. With **no** DeviceProfile: `SupportsDirectPlay=true`.
- With jellystructure's DeviceProfile: `SupportsDirectPlay=false`. R173's `CodecProfiles` condition
  allowed `SDR|HDR10|HDR10Plus|HLG` only, and **no Dolby Vision range type was ever in that list** —
  `detectHdrSupport()` didn't report DV at all (R173 non-goal). So *every* DV title in the library
  (30 of 218 movies: 23× profile 8, 6× profile 7, 1× AV1 DV) was permanently forced to transcode, no
  matter how capable the client was.
- The fallback transcode was **undecodable**. Its master playlist advertised
  `CODECS="avc1.424029", RESOLUTION=3840x1606` — H.264 **Baseline level 4.1** at 4K. Level 4.1 tops out
  near 1920x1088, so ExoPlayer's per-variant capability check dropped the only variant on offer and
  preparation failed before the first frame. Cause: the profile declared **nothing** about the H.264
  target (no `VideoProfile`, no `VideoLevel`, no `Width`/`Height`), so Jellyfin fell back to advertising
  Baseline/L4.1 while still targeting the source's native 4K.
- The server side was healthy: its own `FFmpeg.Transcode-2026-07-25_21-41-26_8176323371b690b8…log` shows
  a clean NVENC run with correct DV→SDR `tonemap_cuda=…:tonemap=bt2390` at 7.5× realtime, killed by the
  idle timer because the client never consumed a segment.
- Live negotiation matrix (each row re-run for this spec):

  | Title | Range type | Before | After |
  |---|---|---|---|
  | Disclosure Day | `DOVIWithHDR10Plus` (p8) | transcode → `avc1.424029` @3840x1606 (undecodable) | **direct play** |
  | The Bad Guys 2 | `DOVIWithHDR10` (p8) | transcode | **direct play** |
  | In Your Dreams | `DOVIWithHDR10` (p8, **AV1**) | transcode | **direct play** |
  | Hypnotic / The Housemaid | `DOVIWithEL` (p7, dual-layer) | transcode, undecodable | transcode → `avc1.640033` (decodable) |
  | undertone | `HDR10Plus` | direct play (unchanged) | direct play |
  | 28 Days Later | `SDR` h264 | direct play (unchanged) | direct play |
  | Disclosure Day, SDR-only client (ravilo-web) | `DOVIWithHDR10Plus` | transcode, undecodable | transcode → `avc1.640033` @1920x803, tone-mapped |

## Root cause
Two independent defects in one negotiation, both in `deviceProfile()`:
1. **No Dolby Vision range type was ever allowed.** DV **profile 8** is single-layer: its base layer *is*
   a conformant HDR10 / HDR10+ / HLG / SDR stream and the DV metadata rides in RPU NAL units every
   decoder ignores — so an HDR10-capable device plays it correctly with no DV decoder at all. Jellyfin's
   own Android TV client encodes exactly that rule (`util/profile/deviceProfile.kt` builds the inverse,
   *unsupported*, set: `DOVIWithHDR10` is only unsupported when HDR10 is, `DOVI` needs a DV decoder,
   `DOVIWithEL*` needs dual-layer DV + multi-instance HEVC, `DOVIInvalid` always unsupported).
2. **The H.264 transcode target was undeclared**, so Jellyfin advertised Baseline/L4.1 at the source's
   full 4K — a variant no decoder accepts. This hit *every* transcode, not just DV (an HDR10+ title on
   ravilo-web had the same broken fallback).

## Fix (implemented)
- `ClientCapabilities` (`shared/src/commonMain/.../tv/Models.kt`) gains `supportsDolbyVision`,
  `supportsDolbyVisionEl`, `maxH264Width`, `maxH264Height`, `maxH264Level` (all conservative defaults —
  `false` / `0` = "unknown").
- `JellyfinClient.kt` splits the profile's two moving parts into documented, unit-tested functions:
  - `allowedVideoRangeTypes(capabilities)` — an allow-list: always `SDR` + `DOVIWithSDR` (SDR base
    layer, DV 8.2); `HDR10`/`HDR10Plus` + `DOVIWithHDR10`/`DOVIWithHDR10Plus` when `supportsHdr10`;
    `HLG` + `DOVIWithHLG` (DV 8.4) when `supportsHlg`; `DOVI` (profile 5, IPT-PQ-C2, no HDR10 base
    layer) only with a real DV decoder; `DOVIWithEL`/`DOVIWithELHDR10Plus` (profile 7) only with
    dual-layer DV. `DOVIInvalid` is excluded by construction.
  - `h264TargetConditions(capabilities)` — declares `VideoProfile` `high|main|baseline|constrained
    baseline`, `VideoLevel ≤ maxH264Level`, `Width ≤ maxH264Width`, `Height ≤ maxH264Height`, all
    `IsRequired=false` (a declaration of the encoder target, not a direct-play requirement).
    Unreported limits fall back to **1080p High/L5.1**, which the whole fleet decodes.
- `detectHdrSupport()` (`ravilo-ui/.../seams/HdrCapabilities.kt`, androidMain) additionally reports
  `dolbyVision` (a `video/dolby-vision` decoder, API 24+) and `dolbyVisionEl` (that **plus** the
  `dvhe.07`/`DolbyVisionProfileDvheDtb` profile **and** an HEVC decoder with
  `maxSupportedInstances ≥ 2`) — the same two checks as `HevcCodecCapabilities` in Jellyfin's client.
- New `detectAvcDecoderLimits()` seam reports the device's real H.264 ceiling (widest-area AVC decoder's
  `VideoCapabilities` bounds + the highest `AVCLevel*` any profile entry advertises, mapped to Jellyfin's
  level×10 encoding). wasmJs reports `UNKNOWN` (a browser exposes no such limit query), so ravilo-web
  gets the safe 1080p target.
- `PlayerStore.startSession()` and `LiveTvPlayerStore.capabilities()` thread both new signals through.

## Non-goals / accepted trade-offs
- **No `VideoBitrate` condition.** Verified live that one doesn't merely cap the transcode — it
  disqualifies direct play of any source above it, which would needlessly transcode high-bitrate remuxes.
  `MaxStreamingBitrate` stays at R56's 120 Mbps.
- **Profile 7 (dual-layer) DV still transcodes** unless the device reports dual-layer DV decode, matching
  Jellyfin's own client. Its fallback is now decodable, which is the part that was broken. Direct-playing
  a profile 7 base layer while discarding the EL is not attempted.
- No client-side DV tone-mapping/rendering pipeline (Media3 effects) — the negotiation is the fix.
- `restream()` (PGS burn-in) still negotiates with the conservative default capabilities, so its
  transcode is capped at 1080p High/L5.1 — which is now a *benefit* (it used to inherit the broken 4K
  Baseline declaration).
- **Text-subtitle sideloading is untouched** (see "Known adjacent issue").

## Known adjacent issue (not fixed here)
`PlaybackService.buildSubtracks()` sideloads embedded text subtitles as
`/Videos/{id}/{id}/Subtitles/{index}/0/Stream.vtt`, and the Android player attaches them as
`MediaItem.SubtitleConfiguration`s with `SELECTION_FLAG_DEFAULT`, so ExoPlayer fetches the default one
during `prepare()`. On this 26 GB file that made Jellyfin extract all four `subrip` tracks with ffmpeg —
`21:41:26 → 21:46:04`, **4m37s** — which delays playback start on the *first* play of any large
multi-subtitle title (the extraction is cached afterwards). Fixing it properly means either declaring
embedded text subs `Embed` on the direct-play path (letting `MatroskaExtractor` render them in-container)
or pre-extracting them in the scan pipeline; both are follow-up work with their own risk to the R180
picker / R181 track resolution, so they are deliberately out of this bug fix's scope.

## Acceptance (verified)
- Live PlaybackInfo matrix above re-run against Jellyfin 10.11.11 with the **exact** JSON
  `deviceProfile()` now emits: all 23 profile-8 DV titles direct-play on an HDR10 client (spot-checked
  hevc + AV1), profile 7 transcodes to a decodable `avc1.640033`, HDR10+ and SDR titles are unchanged,
  and an SDR-only client still gets a correctly tone-mapped — and now decodable — 1080p transcode.
- New `DeviceProfileTest` (8 tests, `src/linuxX64Test/.../auth/`) pins the range allow-list per
  capability combination and the emitted H.264 target conditions, including "no `VideoBitrate` condition"
  and "`DOVIInvalid` never allowed".
- `compileKotlinLinuxX64`, `linuxX64Test`, admin `compileKotlinWasmJs`,
  `:ravilo-ui:compileDebugKotlinAndroid`, `:ravilo-web:compileKotlinWasmJs`,
  `:ravilo-android:compileDebugKotlin`, `:ravilo-phone:compileDebugKotlin` all pass.
- **Not yet on-device verified** — needs a backend relaunch + Ravilo APK rebuild/reinstall (deploy is
  user-initiated, per project conventions). Note the DV direct-play half already works with the
  *currently installed* APK once the backend restarts, since profile 8 only needs the `supportsHdr10`
  signal the app already sends; the new decoder-limit reporting needs the rebuilt APK (until then the
  server uses its safe 1080p fallback target).
