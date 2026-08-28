# Phase R216 — The player must report what it can decode and receive, and record how playback actually went

> Companion to **Phase 177** (server negotiation) and **Phase 178** (server I/O scheduling). Investigation
> `specs/research-reports/stue-tv-4k-playback-stutter-2026-08-28.md` established that stue TV's decoders
> declare a **60 Mbps** ceiling (`bitrate-range = "1-60000000"` on both `OMX.dolby.vision.dvhe.st.decoder`
> and `OMX.MTK.VIDEO.DECODER.HEVC`) which the server never learns, that the TV can silently associate to
> **2.4 GHz at a 130 Mbps PHY rate** mid-evening, and that after three separate stutter investigations
> nothing in the app has ever recorded a dropped frame or a rebuffer.

**Status:** Planned — design-authored 2026-08-28, not yet dev-reviewed. Phase 177 consumes what this
phase reports; each ships independently (177 is inert without R216, R216 is harmless without 177).

## Why this phase exists

Ravilo's capability reporting has grown one honest field at a time, each added because a real bug proved
the server was guessing: `supportsHdr10`/`supportsHlg` (tone-mapping), `supportsDolbyVision*` +
`maxH264*` (R183), `supportsEmbeddedTextSubs` (Phase 161). Each of those was a case of the server
assuming something the device could have simply been asked.

Three gaps of the same shape remain, and they are the ones behind the reported 4K stutter:

1. **Decode ceiling.** `detectAvcDecoderLimits()` (`androidMain/…/HdrCapabilities.kt:77-93`) already
   walks `MediaCodecList` and reads `VideoCapabilities` — width, height, level — but never
   `getBitrateRange()`, and only for H.264. The device's actual 60 Mbps limit is sitting in an API we
   already call and is thrown away.
2. **Link state.** Nothing anywhere reports the network link. A 2.4 GHz association at 130 Mbps and a
   5 GHz one at 585 Mbps are indistinguishable to the server.
3. **Outcome.** `ExoPlayer` tracks dropped frames and rebuffers natively. Ravilo attaches no
   `AnalyticsListener` and ships nothing, so every stutter report becomes a forensic reconstruction from
   router and Jellyfin logs days later.

Additionally, `ExoPlayer.Builder` is used bare (`androidMain/…/RaviloPlayerAndroid.kt:42-53` — only a
`RenderersFactory` is set), so `DefaultLoadControl`'s defaults apply, including
`bufferForPlaybackAfterRebufferMs = 2000`. On a link that dips, resuming on 2 s of buffer turns one
stall into a train of them.

## Requirements

### FR-R216-1 — Report the real decode ceiling

Extend the existing decoder-capability probe rather than adding a parallel one:

- `detectAvcDecoderLimits()` generalises to cover **HEVC and Dolby Vision** alongside H.264, reading
  `VideoCapabilities.getBitrateRange().getUpper()` for each. Rename accordingly (it is no longer
  AVC-specific); keep the existing `maxH264*` fields exactly as they are so R183's transcode-target
  logic is untouched.
- Populate Phase 177's new `max_video_bitrate` / `max_hevc_bitrate` / `max_h264_bitrate` fields at the
  one existing call site (`commonMain/…/PlayerStore.kt:88-103`).
- **Take the ceiling from the decoder that would actually be selected**, not the maximum across all
  decoders. A software fallback decoder advertising a higher bitrate must not mask the hardware
  decoder's real limit — that would invert the entire point.
- Unknown / unreported → `0`, and Phase 177 emits no condition. Never guess a number.

Non-Android targets (`wasmJs`, Tizen) keep their existing `expect`/`actual` shape and report `0`; the
web player has no equivalent API and must not pretend otherwise.

### FR-R216-2 — Report the network link

New `expect fun detectLinkState(): LinkState` alongside the existing capability seams, returning
`kind` (`ethernet` / `wifi` / `unknown`) and `mbps`.

- **Android:** `ConnectivityManager.getNetworkCapabilities()` gives transport type and
  `getLinkDownstreamBandwidthKbps()`; on Wi-Fi, `WifiManager`'s `WifiInfo` gives the real `linkSpeed` and
  `frequency` — the latter is what distinguishes a 2.4 GHz association from a 5 GHz one, and is the
  signal that would have caught 2026-08-27's `f=2462` case.
- **Other targets:** `unknown`, reported honestly.
- Sampled at `startPlayback` only. Continuous monitoring is out of scope (see below).
- **No new runtime permission may be required.** If the needed signal turns out to be permission-gated
  on Android 12+, report `unknown` rather than prompting — a permission dialog on a TV to fix stutter is
  a worse product than the stutter. Verify before building.

### FR-R216-3 — Harden the player against delivery dips

Supply an explicit `DefaultLoadControl` to `ExoPlayer.Builder` instead of inheriting the defaults:

- Raise `bufferForPlaybackAfterRebufferMs` substantially (proposed **10 s**) so a recovered stall
  resumes with a real cushion rather than re-stalling seconds later. This is the single highest-value
  change in this requirement.
- Keep `bufferForPlaybackMs` low (start latency is a product property; R211/R212 exist because startup
  time matters).
- **Do not blindly raise the buffer size.** `dalvik.vm.heapgrowthlimit` on the reported TV is **192 MB**
  while `DEFAULT_VIDEO_BUFFER_SIZE` is already **128 MB** of Java-heap `byte[]` (`2000 × 64 KB`). There
  is far less headroom here than the time-based defaults suggest, and GC pressure is itself an untested
  stutter hypothesis (research report §2.3). Any increase must set `targetBufferBytes` explicitly, be
  measured against `Runtime.maxMemory()`, and be validated by FR-R216-4's own telemetry before shipping
  wider.

### FR-R216-4 — Capture and report playback quality

Attach an `AnalyticsListener` to the player and accumulate, per playback session:

- dropped frame count (`onDroppedVideoFrames`),
- rebuffer count and total rebuffer duration (derived from `onPlaybackStateChanged` / `isPlaying`
  transitions, excluding the initial buffering before first frame and excluding user-initiated seeks),
- the player's own bandwidth estimate (`onBandwidthEstimate`),
- the selected video decoder name and the negotiated `directPlay` flag from the ticket,
- the `LinkState` from FR-R216-2.

Posted once at session end (and on a long-session interval, proposed every 10 minutes, so an
abandoned/crashed session is not lost) to Phase 177's `POST /api/tv/playback/qoe`.

- **Failure to report must never affect playback.** Fire-and-forget, no retry storm, no user-visible
  error — this is diagnostics, and a diagnostics endpoint being down is not a playback problem.
- Nothing is surfaced in the Ravilo UI. Per the product principle, viewers never see bitrates, buffers or
  quality settings — this is for the admin side only.

## Invariants

- **No user-visible setting, ever.** Ravilo does not expose player choice, bitrate, buffer or quality
  controls. Everything here is negotiated or measured automatically; the viewer's experience is that
  playback simply works. This is a product constraint, not an implementation preference.
- **We report, we never guess.** A capability we cannot determine is reported as unknown/`0` and the
  server falls back to today's behaviour. An invented ceiling is worse than none.
- **A client that can't answer still plays.** Every field here is additive with a safe default; web and
  Tizen builds continue to work unchanged.
- **Telemetry is never a playback dependency.** No QoE call may block, delay or fail a session.
- **Ravilo screens paint as one atomic frame** (constitution / no-flicker rule) — nothing here adds a
  late-hydrating UI element, because nothing here has a UI at all.

## Out of scope

- **Refresh-rate / 24p matching.** The reported panel exposes a single 60 Hz mode with
  `mRefreshRateChangeable: false` and `alternativeRefreshRates=[]`, and ExoPlayer already calls
  `Surface.setFrameRate()` under its default seamless-only strategy. There is nothing to switch to.
  Recorded explicitly so it is not re-proposed — see research report §3, claim 3.
- **Replacing ExoPlayer with libmpv / a custom extractor.** The supplied third-party briefing recommended
  this on a Dolby Vision demux theory that was checked and disproved (research report §3, claim 1). The
  DV path on this hardware is correct and hardware-accelerated. The `:ravilo-player` fork decision
  remains deferred for its own reasons and this phase gives no new argument for it.
- **Mid-playback re-negotiation** on a link change. Requires a seamless restream and position handoff;
  should follow the telemetry, not precede it. Phase 177 shares this boundary.
- **Continuous link monitoring.** One sample at session start. Polling the radio throughout playback is a
  battery/wake concern on phone and buys nothing until re-negotiation exists.
- **Audio decoder changes.** `RaviloRenderers`' FFmpeg-for-audio / hardware-for-video split is correct
  and load-bearing (it is what fixed the cover-art-as-video-track bug). Phase 177 §FR-177-3 removes
  lossless *at the source* by honouring the audio codec list; the client decode path stays as it is.

## Source references

- `ravilo-ui/src/androidMain/…/seams/HdrCapabilities.kt:77-93` — `detectAvcDecoderLimits()`, generalised
  by FR-R216-1; `:28-69` `detectHdrSupport()` — the established shape for a capability probe.
- `ravilo-ui/src/commonMain/…/screens/PlayerStore.kt:88-103` — the single `ClientCapabilities`
  construction site; every new field is populated here.
- `ravilo-ui/src/androidMain/…/seams/RaviloPlayerAndroid.kt:42-53` — bare `ExoPlayer.Builder`, extended
  by FR-R216-3/FR-R216-4.
- `ravilo-player/…/RaviloRenderers.kt` — audio/video renderer split (context for what is deliberately
  *not* changing).
- `shared/…/tv/Models.kt:62-98` — `ClientCapabilities`, extended by Phase 177 §FR-177-1.
- Related: **Phase 177** (server half — consumes all of this), **Phase 178** (server I/O), **R183** (the
  `maxH264*` precedent), **Phase 161** (`supportsEmbeddedTextSubs` — the most recent "just ask the
  client" fix), **R211/R212** (startup latency, which FR-R216-3 must not regress).
- `specs/research-reports/stue-tv-4k-playback-stutter-2026-08-28.md` — every measurement cited here.

## Open questions (dev review)

1. **Is `getBitrateRange()` trustworthy across the fleet?** It is authoritative on MT5895; OEM decoders
   are known to report optimistic or placeholder values. A wrong-but-plausible ceiling is worse than
   none, since Phase 177 will act on it. Consider sanity bounds (ignore implausibly low values) and
   validate on the Tizen/phone targets before enabling FR-177-2 fleet-wide.
2. **Is the link signal available without a runtime permission** on Android 12 TV builds? FR-R216-2's
   fallback is `unknown`, but confirm before building.
3. **Should QoE reporting be opt-in?** It is first-party telemetry to the household's own server, which
   argues no. Noted for the owner's call rather than assumed.
