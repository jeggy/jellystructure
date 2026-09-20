# Phase R283 — Declare the audio the player can really decode

> The root cause behind the *Honeyman* report ([[phase-252-a-burn-in-ticket-says-what-it-burned]],
> [[phase-R282-a-burned-in-subtitle-is-the-only-subtitle]]). Those two make a transcoding session
> behave; this one stops the session transcoding in the first place. Owner, same report: *"In
> Wholphin there are 2 audio tracks while in Ravilo there are not"* and *"Ravilo should at least
> support everything the same as Wholphin."*

**Status:** ✓ Built 2026-09-20 (same day as written), not dev-reviewed, **not verified on a device.** Compiles on Android + wasmJs. Checked in the local release APK: `libffmpegJNI.so` ships for all four ABIs and contains `ff_truehd_decoder` and `DCA (DTS Coherent Acoustics)` — the declaration is true of the binary. Needs a client release to take effect; **no backend change.**

## Investigation (measured, 2026-09-20)
`PlayerStore.startSession()` sends a hard-coded list that has not changed since R14:
```kotlin
audioCodecs = listOf("aac", "mp3", "flac", "opus", "ac3", "eac3")
```
- **The Android player decodes more than that.** `:ravilo-player` bundles
  `org.jellyfin.media3:media3-ffmpeg-decoder` — per its own `NOTICE`, *DTS / TrueHD / AC3 / E-AC3* —
  the same decoder Jellyfin's own Android clients ship. `RaviloRenderers` sets
  `EXTENSION_RENDERER_MODE_PREFER` for audio, and **both** activities (`android/MainActivity`,
  `phone/MainActivity`) install it via `RaviloPlayerEngine.renderersFactoryProvider`.
- **The list was harmless until phase 177.** `deviceProfile()`'s own comment: `audioCodecs` was
  *"previously always discarded"*. 177 (2026-08-28) started folding it into the DirectPlayProfile —
  correct — and the stale list became a silent regression: every file whose default audio is TrueHD
  or DTS flipped from direct play to a transcode.
- **Proof, against the live Jellyfin 12.1.0**, *Honeyman (2021)*, same DeviceProfile, one variable:

  | declared audio codecs | `SupportsDirectPlay` |
  |---|---|
  | `…,ac3,eac3` (today) | **false** — `AudioCodecNotSupported` (+`VideoCodecNotSupported`, since the only TranscodingProfile is h264) |
  | `…,ac3,eac3,truehd,dts` | **true** |

- **What the needless transcode costs**, all observed on this one title: a 52 Mbps 4K HDR10 HEVC film
  re-encoded to h264; one audio track instead of two (HLS carries the one Jellyfin picked); PGS
  subtitles forced through burn-in, i.e. a reload per pick; R222's *slow to start*.
- **Scope, from the production catalog** (9 439 files with audio): **573** have a TrueHD/DTS default
  track — 129 with a "supported" track beside it (Honeyman's shape), 444 with nothing else.

## Requirements

### FR-R283-1 — A platform seam answers "which audio codecs can this player decode"
New `seams/AudioCapabilities.kt`: `expect fun supportedAudioCodecs(): List<String>`, reported by
`PlayerStore.startSession()` in place of the literal.
- **Android:** the base list, **plus `truehd`, `dts`** when — and only when —
  `RaviloPlayerEngine.renderersFactoryProvider` is installed (that provider *is* the FFmpeg module;
  a build without it must not claim what it cannot decode).
- **wasmJs:** exactly today's list. No behaviour change on the web.

### FR-R283-2 — Codec names are Jellyfin's
`truehd` and `dts` as Jellyfin reports them in `MediaStreams[].Codec` (DTS-HD MA is `dts` with a
profile; FFmpeg's `dca` decodes it). No aliases invented client-side.

### FR-R283-3 — Nothing else in negotiation changes
177's video-bitrate ceiling, R183's HDR/DV ranges, 161/R209's subtitle rules and
`maxAudioChannels = 8` all stand. A title that still transcodes for one of *those* reasons is
correct, and is exactly the case 252/R282 exist for.

## Invariants
- The capability list is a statement of fact about the running build, derived in one place per
  platform — never a literal at the call site.
- This phase only ever turns a transcode into a direct play; it cannot turn a direct play into
  anything else.

## Considered and not done
- **Backend retry with a playable `AudioStreamIndex`** (measured: `AudioStreamIndex=5` also flips
  Honeyman to direct play). Moot on Android once the list is honest; on the web the same titles are
  HEVC/MKV and transcode regardless. Revisit if a client without FFmpeg gains HEVC direct play.
- **A video-copy TranscodingProfile** (remux instead of re-encode when only audio is unsupported) —
  the next parity step for clients that genuinely lack a decoder. Its own phase.

## Verification
- Compile: `:ravilo-ui` Android + wasmJs, `:ravilo-android`.
- ⚠ **Not verified on a device.** The decoder is bundled and was the *only* path for these files
  before 2026-08-28, so this restores prior behaviour rather than betting on new — but the on-device
  check is still owed: Honeyman on the Pixel 9 → log shows `directPlay=true`, two audio tracks in the
  picker, English (PGS) selectable **with no reload**, sound on both tracks.
