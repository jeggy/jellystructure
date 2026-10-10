# Phase 308 — The picture adapts to the connection, and never stops to buffer

> **Phase 309 (2026-10-07) replaces FR-308-3 (the start rule) and FR-308-4 (the direct-play gate)** and adds the climb/step-down rules and prewarming.

> Owner, 2026-10-05: *"The user should never decide. Ravilo and Jellystructure should talk together and if needed then
> automatically pick a lower bitrate. Ravilo should automatically pick a lower rate if needed (even mid-stream) and if it
> thinks it can bump the bitrate again then it should do that. It should always automatically pick the best quality it
> thinks it can play where the video streaming will never stop for buffering."* — after meidam's lag report
> (`memory/project-meidam-streaming-lag-2026-10-05.md`).
>
> Owner, 2026-10-05 (correction, the same day): *"I don't want a limit for users outside our network. Ravilo and
> Jellystructure should talk together while streaming and pick the best quality possible; if it starts lagging they
> should talk together and find a lower bitrate. This should be done automatically — whatever is between the server and
> the client doesn't matter; different countries or the same room as the server should have no impact, only the real
> live test on the client. We always want to provide the best possible quality, but without lagging or multiple
> buffering pauses mid-stream."*

## Status

`⚠ Partial` — **built 2026-10-05 on branch `r308-adaptive`** (backend, Android, web, Mac, Cast receiver, admin); tests
and compiles green; **not device-tested** (see *Build notes*). Before that: `Planned` — written 2026-10-05 (dev-authored) from the owner's direction above, checked against `main` `b0fce38a`.
Supersedes this phase's first draft (a remote limit in Settings — **withdrawn: nobody picks a number**). Not
dev-reviewed. Backend (the composed master, the ladder, the start choice), every player (Android/ExoPlayer, desktop/mpv,
web/hls.js, Cast/Shaka), the admin's session view.

## What happens today

- One rendition per play. The bitrate asked for is `min(120 Mbps, the device's decode ceiling, the client's LOCAL link
  × 0.5/0.9)` (Phase 177 FR-177-4); nothing knows the real path to the viewer. A Chromecast reports no ceiling and no
  link at all.
- Jellyfin sets a transcode's target to the source's bitrate: meidam's 4K DV7 REMUX (80.9 Mbps) became a 1080p H.264
  stream at `-b:v 80889815` — ~20 Mbps average, 40 Mbps peaks — over a path his phone had measured at ~19 Mbps. It
  advanced at ~0.29× realtime; the encoder sat 10× ahead, idle.
- When a stream stalls nothing changes: R218 shows the stall and the player waits. There is no step down and no step up.
- What exists to build on: R291's **composed HLS master** (jellystructure writes the master playlist itself, today to add
  audio renditions), R216's per-device QoE (`bandwidth_estimate_bps`, rebuffers), R284's restream at the current position.

## Requirements

### FR-308-1 — A ladder in the master playlist

When a stream is transcoded, the composed master lists **a ladder of video variants**, each with its true `BANDWIDTH`,
`RESOLUTION` and codec, from the best the device can decode down to a floor, e.g.:

| Variant | Codec / size | Target |
|---|---|---|
| top | as today's choice (e.g. 2160p HEVC where decodable) | ≤ 40 Mbps |
| 1080p high | H.264 1080p | 12 Mbps |
| 1080p | H.264 1080p | 8 Mbps |
| 720p | H.264 720p | 4 Mbps |
| 480p | H.264 480p | 1.5 Mbps |

One table in one place; a variant above what the device decodes is left out. Each variant is its own Jellyfin
transcode, **started only when a player asks for that variant's playlist or segments** (Jellyfin starts a job on
request; the throttler pauses one nobody reads). Audio renditions (R291) are shared by every variant.

### FR-308-2 — The player chooses, continuously

Every player plays the master with its own adaptive logic — ExoPlayer's track selection, hls.js's ABR, Shaka on the
Cast receiver, mpv's HLS — so it **steps down before the buffer runs dry and steps back up when the measured
throughput holds**, at segment boundaries, without a restart and without a visible pause. Ravilo tunes each player only
where needed (start variant, buffer targets, how fast it steps up), never exposes a choice.

### FR-308-3 — A good first guess

The first variant is the best one the device's **own measurements** (R216 `bandwidth_estimate_bps` from its recent
HLS plays) say it sustains with headroom (≈ 0.7× the median of the last plays); from the first segments on, the
player's own live measurement decides. With no measurement the player starts as its own default does (the top listed
first). **Nothing depends on where the device is** — no address, no inside/outside, no remote rung, no limit (owner's
correction above). A cast receiver measures for itself like any other player (it reports QoE from this phase on).

### FR-308-4 — Direct play only when it is safe

A file is direct-played only when its own bitrate fits what the device has **measured** its path to carry (FR-308-3's
estimate × 0.7); otherwise it gets the ladder. Where the device's measurements are high (the household's TVs measure
200+ Mbps on their HLS plays) this changes nothing; for meidam's phone (19 Mbps measured) it would have meant a 12 Mbps
1080p variant from the first second.

### FR-308-5 — Stalls are measured

Each player reports to R216's QoE the variant switches (down, up, when), rebuffers, and the variant it settled on —
the Cast receiver included (it reports nothing today). The admin's *Playing now* (304) shows the current variant
(*1080p · 8 Mbps · stepped down twice*).

### FR-308-6 — Nothing to decide

No setting, no toggle, no quality picker for the viewer or the admin. (Direct play vs transcode stays automatic as
today.)

## Build notes (2026-10-05, branch `r308-adaptive`)

### Research

1. **Jellyfin's own adaptive streaming cannot be used** (Jellyfin `master`, `Jellyfin.Api/Helpers/DynamicHlsHelper.cs`).
   `EnableAdaptiveBitrateStreaming(...)` returns false for any request from the local network (*"Within the local
   network this will likely do more harm than good"*) — and every master this server fetches is such a request — and for
   a copied video or audio codec. Where it is on, the extra variants are the same URL with `VideoBitrate` lowered by
   `GetBitrateVariation` (2 Mbps at ≥ 10 Mbps, then doubled: 80.9 → 78.9 → 76.9 Mbps) **under the same
   `PlaySessionId`**, and `StreamingHelpers.GetOutputFilePath` names a job's output by
   `MD5(MediaPath-UserAgent-DeviceId-PlaySessionId)`: those variants would share one job directory and serve each
   other's segments (R291's 2026-09-25 measurement saw exactly that for an audio job). Not a ladder we can trust.
2. **A variant per Jellyfin job works, on demand, at the right segment** (`DynamicHlsController.GetDynamicSegment`):
   fetching a master or `main.m3u8` starts nothing (R291, measured); a segment request with no job, or one behind the
   job, or more than `24 / SegmentLength` (8) segments ahead of it, starts ffmpeg **at that segment** (the path a seek
   takes), and `KillTranscodingJobs(deviceId, playSessionId)` there only kills that play session's jobs — so variants
   under their own `PlaySessionId`s coexist. `TranscodeManager.PingTimer`: an HLS job nobody pings for **60 s** is
   killed; segment requests ping it, and so does a `/Sessions/Playing/Progress` carrying its `PlaySessionId`. So each
   variant gets `{PlaySessionId}v{n}` (the top too: progress reports carry the plain id, so they keep no variant alive),
   a variant the player left dies a minute after its last read, and phase 180's teardown stops each by its own id. The
   composed master points every variant at its Jellyfin URL directly (absolute, as R291 already does).
3. **The players.** Media3 (Android): `AdaptiveTrackSelection` steps down when `0.7 ×` the meter's estimate falls under
   the variant's bitrate (`maxDurationForQualityDecreaseMs` 25 s of buffer) and up after `minDurationForQualityIncreaseMs`
   10 s; its first pick comes from `DefaultBandwidthMeter`'s initial estimate, a network-type × country table — so it is
   seeded. hls.js: ABR on by default, starts on the first listed variant, `abrEwmaDefaultEstimate` seedable. Safari /
   the Mac's AVPlayer: native ABR, start on the first listed variant. CAF (Shaka): ABR on by default;
   `PlaybackConfig.initialBandwidth` seeds it. **mpv does not adapt** (`hls-bitrate` picks one variant at open) — it is
   not offered the ladder and keeps one stream, sized by FR-308-4.
4. **The measurements** (`playback_qoe`, read-only): direct-play rows report 4–8 Mbps on the household LAN (the meter
   sees a progressive reader's pace, not the path) while HLS rows on the same TVs report 204–265 Mbps. Only HLS rows
   count; meidam's phone's one HLS row says 19.0 Mbps.

### What was built

- **`VideoLadder.kt`** — the one table (`LADDER`: 1080p 12 · 1080p 8 · 720p 4 · 480p 1.5 Mbps; top capped at 40 Mbps,
  the source's own bitrate and the decode ceiling × 0.9), rungs above the ceiling or not clearly below the one above
  (× 0.8) left out; `reencodesVideo` (a ladder only when `TranscodeReasons` re-encodes the picture or a subtitle is
  burned in — a video-copy transcode's segments follow the source's keyframes and keep one stream);
  `measuredThroughput` (median of the device's last 3 HLS estimates, ≤ 30 days); `throughputBudget` (× 0.7);
  `startOrder` (the first guess listed first, the rest from the top down).
- **`AudioRenditions`** composes the ladder master: each variant is Jellyfin's negotiated URL with its own
  `VideoBitrate`, `MaxWidth`/`MaxHeight` and `PlaySessionId`; its `#EXT-X-STREAM-INF` (BANDWIDTH, RESOLUTION, CODECS) is
  the line Jellyfin itself writes for that request (one master GET per variant, once per stream, cached); R291's audio
  renditions, when present, are shared by every variant. `stopFor` returns every variant session; `releaseEncodes`
  stops each.
- **`PlaybackService`** — the measured throughput narrows `MaxStreamingBitrate` (FR-308-4) on start and both restreams;
  `withRenditions` adds the ladder for a client that declares `hls_adaptive`; the ticket carries `adaptive` and
  `measured_bandwidth_bps`. `recordQoe` keeps each (device, item)'s current variant for *Playing now*.
- **Wire (additive):** `ClientCapabilities.hls_adaptive`; `StreamTicket.adaptive`, `measured_bandwidth_bps`;
  `PlaybackQoeReport.variant_switches_down/_up`, `variant_bandwidth_bps`, `variant_height` (migration **70**).
- **Players:** Android — `SeededBandwidthMeter` (the app-wide Media3 meter, answering with the ticket's measurement
  until two fresh samples), variant switches from `onDownstreamFormatChanged`; web — hls.js `abrEwmaDefaultEstimate`,
  `LEVEL_SWITCHED` counted; Mac (AVPlayer) — declares it, starts on the first listed variant; Linux (mpv) — not
  declared; **Cast receiver** — declares it, seeds `PlaybackConfig.initialBandwidth`, and reports QoE for the first
  time (rebuffers after the first frame, not a seek's; `BITRATE_CHANGED` switches; Shaka's `estimatedBandwidth`).
  QoE is posted at once when the variant changes (otherwise every 10 min and at the end, as before).
- **Admin:** *Playing now*'s State cell reads *1080p · 8 Mbps · stepped down twice* under the state.
- **Tests:** `VideoLadderTest` (9: the ladder per source and ceiling, Jellyfin's lines and own sessions, shared audio,
  the first-guess order, the measurement median and its exclusions, the direct-play cap, the re-encode rule, teardown,
  the variant URL). Not built: FR-308's player-integration tests (a throttled `MockWebServer` under Media3, hls.js in a
  browser) — they need an instrumented device or a browser with H.264.

### Deviations and open points

- **No location rule anywhere** (owner's correction): no remote start rung, no public-address comparison. A device
  with no HLS measurement starts as its player's default does (top listed first; Media3/Shaka from their own estimate).
- A receiver does not borrow its enrolling app's measurement (that would measure the phone's path, not the TV's); it
  measures its own from now on.
- **GPU load:** one encode per viewer while playing; two for up to a minute after a switch (the old variant is
  throttled, then killed). The RTX 2060's consumer NVENC session cap applies across all viewers; measure before
  relying on several remote viewers switching at once.
- **Chromecast:** the receiver gets a plain muxed ladder (no audio group — it does not declare renditions), the most
  common HLS shape, but R291's composed master failed on a built-in Chromecast; device-test first.
- Migration number 70 is the next free on `main` at `c081e82d`; re-check at merge.

### Device testing (2026-10-06)

- **Pixel 9 Pro, through a 5 Mbps throttle:** the first build kept Media3's default, which steps down only once less
  than 25 s is buffered. A new rung is a new encode that took ~17 s to deliver its first segment, so a 52 s buffer
  fell to 5 s, then 1.4 s, before the lower rung arrived. Now it steps down while up to 45 s is buffered, steps up
  only with 25 s in hand, and uses 70 % of the estimate. Retest: down at the throttle, back up when freed, the buffer
  never under 35 s. hls.js and the receiver's Shaka got the same rule (step up only with real headroom; Shaka switches
  at most every 20 s; hls.js keeps a minute ahead).
- **Chromecast (Stue TV), Dolby Vision films:** `TranscodeReasons` does not always say the picture is re-encoded.
  *Saltwater Hall* (DV 7 with an enhancement layer, `DOVIWithEL`) read only `AudioCodecNotSupported,DirectPlayError`
  under `VideoCodec=hevc,h264`, yet Jellyfin tone-mapped it to H.264 at the source's 80.9 Mbps with no ladder. The rule
  is now: a re-encoding reason, a burned-in subtitle, a target codec list without the source's codec, **or a source
  `VideoRangeType` missing from that codec's `{codec}-rangetype` list**.

## Non-goals

- A manual quality menu.
- Changing what plays on the household LAN when it already plays without stalls.

## Acceptance

1. *Saltwater Hall* on a Chromecast outside the house (or a phone throttled to ~19 Mbps) starts within a few seconds,
   plays to the end without a single rebuffer, and settles on a 1080p variant ≤ 12 Mbps.
2. Throttling a phone mid-film from 50 → 5 Mbps steps down within a segment or two with no stall; lifting the throttle
   steps back up within a minute.
3. On the household LAN, a direct-playable file still direct-plays.
4. QoE rows show the switches; the admin shows the current variant.

## Tests

- The ladder builder: variants per device decode ceiling and source; bandwidth/resolution/codec lines; audio shared.
- The first-guess rule: the median of the device's own HLS measurements × 0.7; no measurement ⇒ the player's own start.
- The direct-play gate.
- Player integration: ExoPlayer selects a lower variant under a throttled `MockWebServer`; hls.js likewise in the web lab.

## Open questions

1. **How many variants run at once?** Lean: only the one being read (plus at most one starting after a switch);
   Jellyfin's throttling pauses the rest. Measure GPU/CPU with the P4000 + RTX 2060 before committing to five rungs.
2. **Jellyfin's own `EnableAdaptiveBitrateStreaming`** — check what Jellyfin 12.1 does with it before composing the
   ladder ourselves; use it if it already lists variants we can trust.
3. Shaka on older Chromecasts and multi-variant H.264 — verify on the bedroom TV's Chromecast and a stick.

## Re-dev review (2026-10-08, against `main` `fde62635`)

Read again against `VideoLadder.kt`, `AudioRenditions.kt` (`ladderMaster`, `composeLadderMaster`, `stopFor`),
`PlaybackService` (`startPlayback`, `withRenditions`, `measuredThroughputOf`), `JellyfinClient.deviceProfile` /
`maxStreamingBitrate`, `RaviloPlayerAndroid.kt`, the receiver's `Receiver.kt`, Jellyfin's live `encoding.xml`, the tag
`v1.50`, and 14 days of `playback_qoe` / `playback_start_sample`. Eight items, one for the owner.

1. **No released app has the ladder.** 308 (`7e35f624`) is not an ancestor of `v1.50`, and `v1.50`'s `Models.kt` has
   no `hls_adaptive`: every Android phone and TV in the household and outside it declares nothing and gets one stream.
   Confirmed in production on 2026-10-07 (`no ladder: adaptive=false` for a Pixel 9 Pro on 1.50). Today only the Cast
   receiver (served by the backend, so it ships with a deploy) and debug/dev builds get variants. Acceptance 2 was met
   on a debug build only. The Status line should say so; the next app release is on the critical path for phones and
   TVs.
2. **FR-308-4's cap is applied to every client, adaptive or not, and it has done harm.** `throughputBudget(measured)`
   goes into `MaxStreamingBitrate` for every start (`PlaybackService` ~l.661). On 2026-10-07 the Pixel's only "HLS
   measurement" in 30 days was 4 300 000 bps, Media3's initial guess, so a film was transcoded to **1280×532 at
   2.37 Mbps** on a 680 Mbps Wi-Fi link (the same play measured 168 Mbps). 34 QoE rows hold exactly 4 300 000 and
   11 hold 3 200 000. Until 309's FR-309-13 ships, this is 308's most harmful behaviour in production. Lean: ship
   FR-309-13's backend half (ignore known Media3 defaults and plays under 30 s) as its own small deploy first: it is
   backend-only and helps every installed app.
3. **The top rung ignores the output resolution.** `topVideoBps` takes the source's bitrate, capped at 40 Mbps, then
   the decode ceiling. A 1080p H.264 top therefore asks for up to 40 Mbps (the 2026-10-06 cast: 24.4 Mbps 1080p over a
   ~15 Mbps path), about twice what 1080p needs to look the same. It also makes the step from the 12 Mbps rung to the
   top the big jump Shaka took. Lean: cap the top by its output, e.g. 1080p H.264 ≤ 15 Mbps, 2160p ≤ 40 Mbps
   (H.264) / 25 Mbps (HEVC, item 4). One table next to `LADDER`.
4. **Every rung is H.264.** Jellyfin's `AllowHevcEncoding` is `false`, and Android sets `hlsHevc` only for HLS-only
   clients (`PlayerStore`: `hlsHevc = hlsOnly && …`), so Android always gets H.264 in TS. Two costs:
   - every rung needs roughly 1.6–2× the bits of HEVC for the same picture, which is exactly what makes a slow link
     stall;
   - an HDR source is always tone-mapped to SDR, even on a 4K HDR TV.

   It also means R284's video-copy path (an audio-only transcode of an HEVC file as a remux, starting in well under a
   second) never applies on Android. **For the owner:** (a) HEVC transcodes (fMP4 HLS, Main10, HDR kept) for devices
   that decode HEVC over HLS (Android TVs, the Pixel, receivers that declare it), with H.264 rungs kept for the rest
   **(lean)**; (b) keep H.264 only. Needs one measurement first: NVENC HEVC on the encoding card, and Media3 on the
   BRAVIAs playing Jellyfin's fMP4 HEVC HLS.
5. **Every rung and every seek is a Jellyfin cold start.** Each variant is its own Jellyfin job under
   `{PlaySessionId}v{n}`: the measured first segment after a switch was ~17 s on the Pixel (308's own notes), and
   30 days of transcode starts took 8–38 s. A seek restarts the job being read. The ladder hides switches behind the
   buffer, but starts and seeks stay slow. This points the same way as fork 2's research: R291's own
   `AudioRenditionJobs` start their first segment in 0.6–0.8 s from the same files, against Jellyfin's 2–5 s for the
   same audio, so a jellystructure-owned encoder is the proven faster mechanism here (see 309's re-review, item 8).
6. **The encoding card.** Jellyfin encodes on `cu:0`. The host has a Quadro P4000 (no session limit) and an RTX 2060
   SUPER (consumer NVENC limit); 2026-10-05's investigation saw the job on the 2060. Every warm or variant job counts
   against that limit; 309 must count before adding warm encodes.
7. **Where the waiting actually is.** Of the last 14 days' QoE rows, about 95 % are direct plays. Transcodes are rare,
   but they carry the long waits: on Stue TV 4 of 7 transcodes stalled, 4.5 s on average, and starts took 8–38 s. The
   ladder addresses the stalls of that minority. Start time is 309's prewarm, and direct play's own short stalls are
   309's re-review, item 3.
8. **Still owed:** acceptance 1–2 on a release build once one declares `hls_adaptive`, and the receiver re-test after
   `5f382716` (40 s buffer goal).

## Decided by the owner (2026-10-08, after the streaming re-review)

1. **The order of the no-buffering work** (owner): (1) backend hotfixes, 310, 312 and the Chromecast's own settings;
   (2) R266, casts play in the Ravilo app on a TV; (3) fix files at the source, opt-in per kind; (4) cut the cold start
   of Jellyfin's own transcodes; (5) HEVC transcodes after measuring. See `specs/research-reports/ravilo-streaming-plan-2026-10-08.md` for the whole order.
2. **No encoder of our own: the ladder stays on Jellyfin's transcoder** (owner declined the one-process,
   every-rung encoder). The re-review's items about cold starts are therefore answered inside Jellyfin: 309's early
   encodes, a warm rung below, and finding out whether Jellyfin's per-job probe (`-analyzeduration 200M
   -probesize 1G`) can be made smaller.
3. **HEVC transcodes for devices that decode HEVC over HLS, H.264 kept as the fallback** (re-review 308-4): yes, after
   measuring NVENC HEVC on the card and Jellyfin's HEVC HLS on a BRAVIA. Its own phase.
4. **The measured-speed cap no longer narrows `MaxStreamingBitrate` for a client that cannot change quality** unless
   the measurement is real (FR-309-13): shipped first, in 309a0.

## Owner, 2026-10-08 (later): use our own encoder

> *"Then let's change and use our own encoder if that makes the experience better."* This reverses the earlier
> "stay on Jellyfin" answer.

- **Step 4 of the order is now a jellystructure-owned encoder** (new phase): one ffmpeg per play decodes the source
  once and writes every rung with aligned keyframes and short CMAF/fMP4 segments, so a switch never waits for a cold
  encode, a seek restarts one job, and the probe comes from our own scan. Jellyfin's per-rung transcode stays as the
  fallback (encoder unavailable, GPU busy, a source our encoder refuses).
- **HEVC folds into it:** our encoder writes HEVC rungs for devices that decode HEVC over HLS (HDR kept), H.264 rungs
  for the rest; measured first (NVENC HEVC throughput with 3–4 rungs on the P4000).
- 308's ladder table, the composed master and the players' ABR stay; only who produces the rungs changes.
