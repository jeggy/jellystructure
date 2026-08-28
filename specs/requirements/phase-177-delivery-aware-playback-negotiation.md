# Phase 177 — Playback negotiation must know what the device can actually decode and receive

> Reported live 2026-08-27: 4K titles stutter on stue TV over an otherwise healthy network. Investigation
> (`specs/research-reports/stue-tv-4k-playback-stutter-2026-08-28.md`) found the server negotiates every
> playback the same way regardless of what the client is physically capable of decoding or receiving.
> The TV's own decoders declare **`bitrate-range = "1-60000000"`** — a 60 Mbps ceiling on both the Dolby
> Vision and plain-HEVC paths — yet `Spindlelegs` (93 Mbps average, DTS-HD MA) direct-plays into them
> unchanged, for two hours. Meanwhile `ClientCapabilities.audioCodecs`/`maxAudioChannels` have been
> populated by the client and **never read by anything** since they were introduced, so 417 of 6798
> library files hand a lossless track to a software decoder on the TV's CPU whatever the client said.

**Status:** Planned — design-authored 2026-08-28, not yet dev-reviewed. Depends on **R216** for the new
capability fields; ships inert (identical behaviour to today) against any client that doesn't send them.

## Root cause

`deviceProfile()` (`auth/JellyfinClient.kt:42-43`) is a single hardcoded JSON string. Only two fragments
of it vary with the caller: `allowedVideoRangeTypes(capabilities)` (R183) and
`h264TargetConditions(capabilities)` (R183). Everything else — containers, video codecs, **the audio
codec list**, channel limits, transcoding profile — is fixed text identical for a Bravia, a phone, a
Tizen TV and the web player.

Three consequences, all confirmed against the live library:

1. **No decode ceiling is ever declared.** `DirectPlayProfiles` accepts any bitrate. Jellyfin therefore
   reports `supportsDirectPlay=true` for a 93 Mbps remux to a device whose decoder advertises 60 Mbps,
   and `PlaybackService.startPlayback` (`tv/PlaybackService.kt:245`) faithfully takes the direct-play
   path. The backend log shows `directPlay=true transcode=false` for every session.
2. **The client's declared audio support is discarded.** `ClientCapabilities.audioCodecs` and
   `maxAudioChannels` (`shared/…/tv/Models.kt:65-66`) are set by every client
   (`ravilo-ui/…/PlayerStore.kt:93-94` sends `aac, mp3, flac, opus, ac3, eac3` — deliberately **not**
   `truehd`/`dts`), but `deviceProfile()` hardcodes `AudioCodec` including `dts,truehd`. The client's
   honest "I would rather not be given lossless" is thrown away, and `RaviloRenderers`' FFmpeg extension
   decoder then decodes DTS-HD MA / TrueHD in software on the TV's CPU.
3. **Link quality is invisible.** A TV associated to 2.4 GHz at a 130 Mbps PHY rate (observed
   2026-08-27 22:51, ejected 34 s later with `reason=34:DISASSOC_LOW_ACK`) negotiates byte-for-byte
   identically to the same TV on 5 GHz at 585 Mbps.

There is a deliberate comment against adding a bitrate condition (`JellyfinClient.kt:92-93`): *"no
`VideoBitrate` condition: verified live that one blocks direct play of any source above it (a
high-bitrate H.264 remux would start transcoding for no reason)."* That finding is correct **for a
blanket condition with an arbitrary number**. This phase's condition is per-codec and carries the
device's own measured ceiling, which is a different thing — see FR-177-2's invariant.

## Requirements

### FR-177-1 — Carry the client's real decode ceilings

`ClientCapabilities` (`shared/…/tv/Models.kt`) gains, all defaulting to `0`/absent so existing clients
are unaffected:

- `max_video_bitrate` — the device's highest advertised video decode bitrate, in bits/s, across the
  codecs it will actually be offered. `0` = unknown.
- `max_hevc_bitrate`, `max_h264_bitrate` — per-codec, when they differ (they do not on MT5895; both
  report 60 Mbps). A codec with no entry falls back to `max_video_bitrate`.
- `link_kind` (`"ethernet"` / `"wifi"` / `"unknown"`) and `link_mbps` — the client's own view of its
  network link. See R216 §FR-R216-2 for how these are sourced.

R216 populates these. This phase only consumes them.

### FR-177-2 — Declare a per-codec bitrate ceiling, derived from the device

`deviceProfile()` gains a `CodecProfiles` entry per video codec for which a ceiling is known:

```
{"Type":"Video","Codec":"hevc","Conditions":[
  {"Condition":"LessThanEqual","Property":"VideoBitrate","Value":"<ceiling>","IsRequired":true}]}
```

- **`IsRequired: true` is deliberate and is the whole point** — unlike `h264TargetConditions`' declarative
  `IsRequired:false` target, this is a genuine direct-play *requirement*: above it, the device cannot be
  trusted to decode, so Jellyfin must transcode.
- **The ceiling is only ever emitted when the client actually reported one.** A `0`/absent value emits no
  condition at all, reproducing today's behaviour exactly. This is what keeps
  `JellyfinClient.kt:92-93`'s live finding respected: we never invent a number, we only repeat the
  device's own.
- **A safety margin applies.** The declared ceiling is the device's reported value scaled by a constant
  (proposed **0.9**) to leave headroom for the container/audio overhead Jellyfin's `VideoBitrate` check
  does not account for, and for the short peaks a rolling average hides — Offboarding averages 24 Mbps but
  peaks at 88.9 Mbps over 1 s. The constant lives in one named place, not scattered at the call site.

### FR-177-3 — Honour the client's audio codec list

`DirectPlayProfiles`' `AudioCodec` becomes `capabilities.audioCodecs`, joined, when the client sent a
non-empty list; the current hardcoded string remains the fallback for a client that sent none.
`maxAudioChannels` becomes a `CodecProfiles` audio condition when non-default.

The intended and verified-available Jellyfin behaviour is **audio-only transcode**: when the video
stream satisfies every direct-play condition but the audio codec is not in the client's list, Jellyfin
copies the video and transcodes only the audio (cheap on the server, and it removes software TrueHD /
DTS-HD MA decode from the TV entirely). This must be confirmed live against Jellyfin 10.11.11 before
build — see Open questions.

### FR-177-4 — Cap bitrate when the link cannot carry the file

`MaxStreamingBitrate` (currently the fixed `120000000` in `deviceProfile()`) becomes the **minimum** of:

- the fixed 120 Mbps ceiling as today,
- the device's decode ceiling from FR-177-2 (so the two mechanisms cannot disagree),
- a link-derived allowance when `link_kind`/`link_mbps` indicate the link cannot sustain the file:
  proposed **50 % of the reported link rate** for Wi-Fi (PHY rate is roughly double achievable TCP
  throughput under good conditions, and worse under load), and **90 %** for Ethernet, which is
  deterministic.

A client that reports no link information gets today's behaviour. **This is deliberately conservative:
capping `MaxStreamingBitrate` makes Jellyfin choose a transcode rather than fail, which is strictly
better than the current outcome of a direct play the link cannot feed.**

### FR-177-5 — Ingest and surface playback quality telemetry

New authenticated endpoint `POST /api/tv/playback/qoe`, accepting the report R216 §FR-R216-4 emits
(device id, Jellyfin item id, play session id, wall-clock window, dropped frame count, rebuffer count
and total rebuffer ms, the player's own bandwidth estimate, the negotiated `directPlay` flag, and the
link state at the time).

- Persisted to a new `playback_qoe` table, keyed by `(device_id, jellyfin_id, play_session_id)`, with a
  retention window (proposed 90 days) so it cannot grow unbounded — this table is diagnostic, not a
  ledger.
- Surfaced on **Activity** (`activity.html`) as a per-playback row, and on the Users & devices settings
  tab as a per-device recent-quality summary. A session with rebuffers or dropped frames is badged
  `--warn`; a clean one is not badged at all.
- **This is the requirement that pays for the other four.** Three separate stutter investigations
  (2026-08-19, 2026-08-20/21, 2026-08-27) have each been an after-the-fact forensic reconstruction from
  Wi-Fi and Jellyfin logs, because the one process that knows how playback actually went throws that
  knowledge away. Everything else in this phase is a hypothesis until this exists to confirm it.

## Invariants

- **A client that sends none of the new fields negotiates exactly as it does today.** Every new
  condition is emitted only when its input is present. Web, Tizen and any un-updated Ravilo build are
  bit-for-bit unaffected — this phase cannot regress them.
- **We never invent a ceiling.** Every number in a `VideoBitrate` condition traces to something the
  device itself reported. The one tunable we add is the FR-177-2 safety margin, which only ever lowers a
  device-supplied number.
- **Transcoding is still a last resort, not a preference.** These conditions exist to catch the cases
  where direct play is *known to be beyond the device*, not to second-guess a working direct play. If a
  file plays fine today on a device that reports no ceiling, it must still direct-play after this phase.
- **The frontend renders server-pushed state only** (constitution) — QoE rows are read back from the
  server, never accumulated client-side in the admin UI.

## Out of scope

- **Refresh-rate / 24p matching.** Proven inert on the reported hardware: the panel exposes a single
  60 Hz mode with `mRefreshRateChangeable: false`, and ExoPlayer already calls `Surface.setFrameRate()`
  under its default seamless-only strategy. There is nothing for an app to switch to. Recorded here so
  the idea is not re-proposed.
- **Adaptive mid-playback bitrate switching.** Everything here is negotiated once, at
  `startPlayback`. Re-negotiating on a mid-stream link change is a much larger design (it needs a
  seamless restream and a position handoff) and should follow the telemetry from FR-177-5, not precede it.
- **Anything about the disk or the network themselves** — Phase 178 covers the I/O side; the Ethernet
  move and the SSID split are infrastructure, not code.
- **Dolby Vision handling.** R183's `allowedVideoRangeTypes` is correct and confirmed working; the
  supplied briefing's DV-demux theory was checked and rejected (research report §3, claim 1).

## Source references

- `auth/JellyfinClient.kt:42-43` — `deviceProfile()`, the hardcoded string this phase parameterises;
  `:61-79` `allowedVideoRangeTypes` (R183, the precedent for varying it per capability); `:92-102`
  `h264TargetConditions` **and the comment explaining why a blanket `VideoBitrate` condition was
  rejected** — read it before implementing FR-177-2.
- `shared/src/commonMain/kotlin/dev/jellystructure/shared/tv/Models.kt:62-98` — `ClientCapabilities`,
  extended by FR-177-1.
- `tv/PlaybackService.kt:243-270` — the direct-play/transcode decision and stream URL construction; the
  consumer of everything above.
- `specs/research-reports/stue-tv-4k-playback-stutter-2026-08-28.md` — measurements behind every number
  in this spec.
- Related: **R183** (DV + the `maxH264*` capability precedent this extends), **R216** (the client half),
  **Phase 178** (the I/O half), **Phase 161** (the last time a capability flag had to be added to keep
  direct play honest).

## Open questions (dev review)

1. **Does Jellyfin 10.11.11 actually produce a video-copy/audio-transcode stream** when only the audio
   codec fails the profile, or does it fall back to a full transcode? FR-177-3's entire value rests on
   this. Confirm live against a real TrueHD title before building.
2. **Is `VideoBitrate` evaluated against the source's average or peak bitrate?** Offboarding averages
   24 Mbps and peaks at 88.9 Mbps over 1 s; if Jellyfin compares against a nominal average, the FR-177-2
   margin is doing more work than it appears and may want to be larger.
3. **Is 0.9 the right margin, and 50 % the right Wi-Fi link fraction?** Both are proposed, not measured.
   FR-177-5's telemetry is what should eventually set them.
