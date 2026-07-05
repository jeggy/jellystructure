# Phase R173 — Ravilo: HDR/HLG tone-map negotiation (bug fix, FR-RV-HDR1)

> A viewer reported "Overcast" (HEVC 10-bit HDR10+, confirmed via `ffprobe`) played very dark on Ravilo
> but perfectly through Jellyfin's own web/native client. Investigated same day, root-caused against the
> live Jellyfin server, fixed, and verified — no design draft, no build queued beforehand. Extends R56
> (which introduced the PlaybackInfo/DeviceProfile negotiation this fix corrects).

## Bug report
2026-07-05 — streaming "Overcast" in Ravilo looked very dark; the same title played perfectly through
Jellyfin's own client.

## Investigation
- Confirmed the file is genuine HDR: `ffprobe` on the stored path
  (`/mnt/media/jellyfin/movies/undertone (2026)/overcast.2025.2160p.iT.WEB-DL.DDP5.1.Atmos.HDR.H.265-SCOPE.mkv`)
  shows `codec_name: hevc`, `pix_fmt: yuv420p10le`, `color_transfer: smpte2084` (PQ), `color_primaries:
  bt2020`.
- jellystructure doesn't transcode itself — `PlaybackService.startPlayback()` (`src/linuxX64Main/.../tv/
  PlaybackService.kt`) brokers a `StreamTicket` pointing at Jellyfin's own direct-play or transcode URL,
  decided by POSTing a `DeviceProfile` to Jellyfin's `POST /Items/{id}/PlaybackInfo`. The profile
  (`JellyfinClient.kt`'s `DEVICE_PROFILE` constant) had **no `CodecProfiles` entry at all** — the section
  Jellyfin's real clients use to say "reject this as direct-play if it's outside what I can show" (e.g.
  a `VideoRangeType` condition).
- `PlaybackService.startPlayback(device, jellyfinId, capabilities: ClientCapabilities)` already received
  a `capabilities` parameter — populated by the Ravilo player's own `startSession()` — but it was marked
  `@Suppress("UNUSED_PARAMETER")` and never reached `getPlaybackInfo()`. `ClientCapabilities` itself
  (shared DTO) had no HDR/color field to carry such a signal in the first place — confirmed via a
  repo-wide grep for hdr/tonemap/colorTransfer/bitdepth (zero hits anywhere in the codebase).
- **Verified live against the real Jellyfin server** (jellyfin.example.net), item
  `d4896721f606d45fe493b26a22fae5a2` ("Overcast"):
  - With jellystructure's exact current `DeviceProfile` (no `CodecProfiles`): `PlaybackInfo` →
    `SupportsDirectPlay: true`, `VideoRangeType: HDR10Plus` — reproduces the bug: Jellyfin has no reason
    to disqualify this stream from direct play, so it ships the raw PQ curve as-is.
  - Adding `CodecProfiles: [{Type: "Video", Codec: "hevc,h264,vp9,av1", Conditions: [{Condition:
    "EqualsAny", Property: "VideoRangeType", Value: "SDR", IsRequired: true}]}]` → `PlaybackInfo` flips to
    `SupportsDirectPlay: false`, `TranscodeReasons=VideoRangeTypeNotSupported`, and the resulting
    `TranscodingUrl` targets `h264-rangetype=SDR` — Jellyfin correctly tone-maps HDR→SDR.
  - Widening `Value` to the pipe-separated `"SDR|HDR10|HDR10Plus"` flips `SupportsDirectPlay` back to
    `true` — confirms the same mechanism lets an HDR-capable client keep direct play (no regression for
    a TV that genuinely supports HDR10).
- Ravilo's Android player (ExoPlayer via a plain `TextureView`, no HDR-aware `VideoFrameProcessor`/tone-
  map effect) has no client-side fallback either — a raw HDR10 stream that reaches the device has nothing
  else that would correct it.

## Root cause
jellystructure's `DeviceProfile` never declared any HDR/color constraint, so Jellyfin's own transcode
decision — which is otherwise perfectly capable of tone-mapping — never had a reason to trigger. This is
not a Ravilo decode bug; it's a missing capability declaration in the server-to-Jellyfin negotiation.

## Fix (implemented, commit `3cb4966`)
- `ClientCapabilities` (`shared/src/commonMain/.../tv/Models.kt`) gains `supportsHdr10` / `supportsHlg`
  (default `false` — conservative: assume SDR-only unless the device proves otherwise, so an unknown or
  new client forces a safe tone-mapped transcode rather than risking a dark picture).
- `JellyfinClient.kt`'s `DEVICE_PROFILE` constant becomes `deviceProfile(capabilities)`, built per
  request with the `CodecProfiles`/`VideoRangeType` condition verified above, widened by whatever the
  caller's capabilities actually claim.
- `PlaybackService.startPlayback()` no longer discards `capabilities` — passes it to
  `getPlaybackInfo()`. The unrelated burn-in `restream()` call site (which used to pass
  `subtitleStreamIndex` positionally into what is now the `capabilities` slot — a latent break this fix
  also caught) is corrected with named arguments; it keeps the conservative default since it doesn't
  have the original session's capabilities on hand, which is fine — that path already forces a
  transcode for the subtitle burn-in regardless of HDR.
- Ravilo Android gains a `detectHdrSupport()` seam (`ravilo-ui/.../seams/HdrCapabilities.kt`, `expect`/
  `actual`) that queries the real `Display.HdrCapabilities` (API 24+ guarded, `HDR_TYPE_HDR10_PLUS`
  API 29+ guarded, for a minSdk-21 app) and feeds `PlayerStore.startSession()`'s `ClientCapabilities`.
  wasmJs has no reliable browser HDR-passthrough detection today and reports `HdrSupport.NONE`
  (conservative — correct for a plain `<video>` element anyway).

## Non-goals
- No Dolby Vision negotiation (no DOVI decode/passthrough pipeline exists anywhere in the codebase today
  — out of scope; only HDR10/HDR10+/HLG via `VideoRangeType` are covered).
- No client-side tone-mapping fallback in the Android player (Media3 `VideoFrameProcessor`/effects) — the
  server-side negotiation fix is sufficient and lower-risk; a client-side safety net is future work only
  if a device is ever found to misreport its own HDR capability.
- `restream()` (subtitle burn-in) doesn't thread the original session's real capabilities through —
  it always negotiates conservatively. Acceptable: burn-in already forces a transcode regardless of HDR.

## Acceptance (verified)
- `ffprobe` confirms "Overcast" is genuine HDR10+ (PQ/BT.2020/10-bit).
- Live `PlaybackInfo` test against the real server confirms: current profile → direct-play (bug
  reproduced); `CodecProfiles` + `VideoRangeType=SDR` → transcode with
  `TranscodeReasons=VideoRangeTypeNotSupported` and SDR-range transcode output (fix confirmed); widened
  `VideoRangeType` list → direct-play restored (HDR-capable-client path confirmed not to regress).
- `compileKotlinLinuxX64`, admin `compileKotlinWasmJs`, `:ravilo-ui:compileDebugKotlinAndroid`,
  `:ravilo-web:compileKotlinWasmJs`, and `linuxX64Test` (14/14) all pass.
- **Not yet on-device verified** — needs a backend relaunch + Ravilo APK rebuild/reinstall, deliberately
  not done this session (deploy is user-initiated, see project conventions).
