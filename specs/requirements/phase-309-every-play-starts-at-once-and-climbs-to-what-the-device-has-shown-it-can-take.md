# Phase 309 — Every play starts at once, and climbs to what the device has shown it can take

> Owner, 2026-10-07, after meidam's second report (a 29 s stall casting a 4K episode from outside the house,
> `memory/project-meidam-streaming-lag-2026-10-05.md`, follow-up 2026-10-06): *"We should never have different logic
> for over the internet or not. We want the same ladder logic to always be in place. We want to provide the very best
> experience, so this 29 seconds stuck is an example of what we never want. We currently sometimes tell the user that
> before starting it may load for a while. Let's change that, so we rather just start on a lower quality and then build
> our way up to what this specific device can handle, but if we know before what the device can play, then we trust
> that and if it was not true we dynamically go down in quality. All in all, we want as little buffering/lagging as
> possible and prewarm as much as possible, so we know all the information we need and start streaming the best we can."*

## Status

`⚠ Partial` — **309a0 built and deployed 2026-10-08; 309a (backend) and most of 309b (Android + every client's detail page)
built 2026-10-08 on a worktree branch on top of 313, not merged, not deployed, not device-tested** (see *Build notes —
309a / 309b*). Written 2026-10-07 (dev-authored), dev-reviewed 2026-10-07 and re-reviewed 2026-10-08; the owner then
moved the mechanism to our own encoder (313). **309c built 2026-10-09 on a worktree branch, not merged, not deployed,
not device-tested** (see *Build notes — 309c*): the receiver bounds Shaka's ABR by the ladder rule and runs the speed
test, a direct play that stalls moves to the ladder once, the phone and the desktop send the receiver's id with the
prewarm (a no-op while casts go to Jellyfin, 313), mpv restreams a rung down/up, AVPlayer climbs under a peak bitrate
with 40 s forward buffer. **Not built:** the web player (left alone: R376 is rebuilding it). Builds on **308**,
replaces 308 FR-308-3's start rule and FR-308-4's direct-play gate, retires 185 FR-185-5…7's note and R222.

## What happened (2026-10-06 23:20–23:25, read-only from logs and the DB)

meidam cast a series episode (4K HEVC DV/HDR10+, 24.4 Mbps) from their phone to a Chromecast on the same outside
address. 308 did its part — the ladder was offered (`ladder top=24382k measured=none`), no more 80.9 Mbps — but:

- **The receiver had never played anything**, so no measurement: Shaka started on its own default, then **jumped from
  4 Mbps straight to the 24.4 Mbps top** 25 s in (Jellyfin started 4 Mbps 720p at 23:20:16, 1.5 Mbps 480p at
  23:20:27, 24.4 Mbps 1080p at 23:20:38; the 12 and 8 Mbps rungs were never asked for).
- **It never came down.** Its last report: variant 24.6 Mbps, its own estimate 15.4 Mbps, **1 up, 0 down, 1 rebuffer of
  29.4 s**. Shaka's ABR is throughput-only; with `switchInterval 20` and an estimate inflated by the first small
  segments it kept the top rung while the buffer ran dry.
- The encoder was 11–14× realtime throughout; the server was never the limit.

And the other half of the owner's point: for a file above a device's decode ceiling we still **warn** before Play
(185/R222: *"Slow to start on Stue TV. Give it a moment after you press play."*) instead of making the start fast.

## Principles

1. **One rule for every play.** No address, no inside/outside, no LAN exception, no limit — only what this device has
   measured and lived through (restates 308's owner correction; nothing here may reintroduce a location test).
2. **Start at once, then climb.** The first frame comes from a rung the device is known to carry — or, when nothing is
   known, a low one — never from the top on hope.
3. **Trust what the device has proven; lose that trust the moment it fails.** A clean play raises what we start at
   next time; a stall lowers it at once.
4. **Down is fast, up is careful.** Step down before the buffer runs dry, as many rungs as needed; step up one rung at
   a time, only with buffer in hand, only to a rung that is already encoding.
5. **Prewarm everything that can be known or started before it is needed** — the measurement, the decision, the
   encodes — so nothing is waited for on Play, on a step up, or after a seek.
6. **Nothing to decide, nothing to warn about.** No setting, no quality picker, no "this may take a while".

## Requirements

### FR-309-1 — What a device can take: its own record

Each device (a cast receiver is its own device, as in 308) keeps one **stream record**, kept by the backend and
updated by every play's QoE:

| Field | Meaning |
|---|---|
| `proven_bps` | The highest video bitrate this device **held for ≥ 2 min with no rebuffer**, in the last 30 days. |
| `measured_bps` | 308's `measuredThroughput` (median of its last 3 HLS estimates), plus FR-309-3's probe. |
| `stalled_bps` / `stalled_at` | The variant bitrate it was on at its last rebuffer, and when. |
| decode ceilings | As today (185/R216), unchanged. |

**What the device can take** = `min(decode ceiling × 0.9, proven_bps or measured × 0.7, stalled_bps × 0.8 while the
stall is < 24 h old)`. A stall wins over an older proof; a later clean play at a higher rung raises `proven_bps` again.
Direct plays count too: a direct play that ran ≥ 2 min with no rebuffer proves the file's bitrate.

### FR-309-2 — The first rung

- **A device with a record** starts on the best rung inside what it can take (the rung itself, not one below — the
  owner: *"if we know it, we trust that"*). The household's TVs, which have proven 40 Mbps, start at the top as they
  do today.
- **A device with no record** (first play, a new Chromecast, a record older than 30 days) starts on the **720p
  4 Mbps** rung and climbs (FR-309-4). Never the top first.
- The start rung is **listed first** in the master and the player is **seeded** with the matching estimate (Media3's
  meter, hls.js `abrEwmaDefaultEstimate`, Shaka `initialBandwidth`, AVPlayer's first listed variant), so every player
  starts where the backend decided, not on its own default.
- Target: **first frame within 3 s** of Play for a transcode, on any path that carries the start rung.

### FR-309-3 — Measure before Play (prewarm the knowledge)

When a title's detail page opens (or the cast sheet's target is chosen), and the device's — or the chosen receiver's —
`measured_bps` is missing or older than 24 h, the client **measures its path to the server**: a timed download from a
new `GET /api/tv/probe` (incompressible bytes, through the same public route the segments take), stopping at 4 MB or
2 s, whichever first, in the background, never blocking the page. A receiver probes itself when it is woken for the
cast (it fetches the probe before its first segment). The result is reported like a QoE estimate and joins
`measured_bps`. On a metered (mobile-data) link the probe is 1 MB.

### FR-309-4 — Climb one rung at a time

Every player climbs **only to the next rung up**, only when (a) its own estimate × 0.7 clears that rung, (b) it has
**≥ 30 s buffered**, and (c) that rung is already encoding (FR-309-6). No skipping rungs on the way up — tonight's
4 → 24.4 Mbps jump is exactly what this forbids. The climb ends at what the device can take (FR-309-1) or the top.

### FR-309-5 — Step down before the buffer runs dry, on every player

Every player steps down **as many rungs as needed** as soon as its buffer ahead falls under **20 s and is falling**,
or its estimate × 0.8 drops under the current rung — whichever comes first. This is a rule the player enforces itself
where its ABR does not (Shaka's ABR on the receiver is throughput-only and did not step down tonight; the receiver
watches its own buffer level and restricts Shaka to the lower rungs when the rule fires). A rebuffer, if one still
happens, drops at least one rung at once and writes `stalled_bps` (FR-309-1).

### FR-309-6 — Prewarm the encodes

- **On Play**, the backend starts the start rung's encode **and** warms the rung directly above it (and the one
  directly below, so a first step down never waits) at the start position, by asking Jellyfin for each one's first
  segment itself; Jellyfin's throttler pauses whatever runs ahead.
- **While playing**, the rung directly above the current one is kept warm (pinged under its own `PlaySessionId`, as
  308's variant sessions are) at the player's position, so a climb lands on segments that already exist; when the
  player climbs, the new rung above is warmed and the one two below is let go.
- **After a seek** the same three (current, above, below) are started at the new position at once; the player resumes
  on the current rung, never higher, and climbs again from there.
- **Bound:** at most **3 encodes per play**; warming never queues or delays a real play — if the backend's own count of live
  variant encodes (Jellyfin's ffmpeg runs in Jellyfin's container — our `ProcessGate` never sees it; dev review 6) is at
  its limit, warming is skipped first, and a warm encode for another viewer is stopped before a
  play is refused.

### FR-309-7 — Decide before Play (prewarm the negotiation)

When the detail page opens, the backend resolves — and caches for 5 min per (device, file, audio, subtitle) — the
play decision: direct play or ladder, the start rung, the ladder's rungs. Play then only starts the encodes; it does
not negotiate. A changed track choice re-resolves in the background.

### FR-309-8 — Direct play only when proven, and a way down from it

A file direct-plays only when its bitrate fits what the device can take (FR-309-1). Otherwise it gets the ladder — so
a device with no record plays a high-bitrate file through the ladder first, and direct-plays it once it has proven it.
A direct play that rebuffers **once** switches to the ladder at its current position (R284's restream), on the rung
under what it was carrying, and records the stall; it does not wait for a second one.

### FR-309-9 — Players that cannot switch variants themselves

- **Linux (mpv)** — no HLS adaptation. It gets one variant chosen by FR-309-2 and **the Linux app steps itself** with R284's
  restream (its capabilities' `max_video_bitrate` set to the rung) at the current position when FR-309-5's rule fires
  (mpv's buffer level from `demuxer-cache-duration`; the backend never sees it — dev review 9), or up one rung
  after 2 min clean with the rung above warm.
- **Mac (AVPlayer)** — native ABR. It is given the start rung first, `preferredForwardBufferDuration` 40 s, and
  `preferredPeakBitRate` capped one rung above where it is until it has climbed (lifted one rung per climb), so it
  climbs one rung at a time like the others.

### FR-309-10 — No more "slow to start"

The *"Slow to start on {device}"* note (185 FR-185-5…7, R222) is **removed**: the backend sends `playback_note` as
`null` always (the field stays on the wire — installed clients must keep parsing it), the clients stop drawing
`PlaybackNoteLine`, and its three strings (`slow_lead`, `slow_tail_measured`, `slow_tail_expected`) are retired.
The decode ceiling and the admin's *picture it can take* line (185 FR-185-8) stay — the ladder uses them.

### FR-309-11 — Measured, so the next report has evidence

Every player's QoE gains **time to first frame** and the **start rung**, and reports at every switch (as 308) and at
every rebuffer. The admin's *Playing now* reads e.g. *720p · 4 Mbps → 1080p · 8 Mbps · climbing* or *stepped down
once*. The admin's device row shows what the device can take (*holds 8 Mbps · stalled at 24 Mbps yesterday*).

### FR-309-12 — Nothing to decide

No setting, no toggle, no quality menu, for the viewer or the admin (as 308 FR-308-6).

### FR-309-13 — A guess is never a measurement (added 2026-10-08)

Found on 2026-10-07: a Pixel 9 Pro on home Wi-Fi (680 Mbps link) was given a **2.37 Mbps 1280×532** transcode of a 4K
HDR10+ film. 308's `measuredThroughput` read the device's only HLS sample in 30 days, **exactly 4 300 000 bps**, and
capped the stream at 70 % of it (`measured 4300k → budget 3010k (308)`). That number was never measured. It is
Media3 `DefaultBandwidthMeter`'s **initial estimate**, the guess it reports before it has transferred anything. The same
play then measured **168 Mbps**. Across `playback_qoe`, 34 rows hold exactly 4 300 000 and 11 hold exactly 3 200 000,
both meter defaults (Media3 picks its guess by network type and country, so the set of values is open-ended).

- **The player says whether its estimate is real.** Each QoE report gains `bandwidth_samples` (the number of transfers
  the estimate is built from) and `bandwidth_bytes`. Android reads them from its own meter: `SeededBandwidthMeter`
  already wraps it, so it counts the samples it passes on. hls.js and Shaka count their fragment loads. A report with
  fewer than **3 samples or under 2 MB** carries no estimate, so `bandwidth_estimate_bps` is sent as null.
- **The backend never trusts a guess.** `measuredThroughput` ignores a sample whose `bandwidth_samples` is below 3. For
  rows from apps that don't send the field yet (1.50 and older), it ignores **the known defaults**: Media3's initial
  estimates for every network type, kept as one table next to the code, including 4 300 000 and 3 200 000. It also ignores
  any sample from a play that ran under 30 s.
- **A newer real sample wins.** With FR-309-1's record in place, a real measurement newer than the samples in the
  median replaces them rather than averaging with them (the Pixel's next play would otherwise use the median of
  168 Mbps and 4.3 Mbps).
- **One-off clean-up:** the existing rows at a known default have their estimate set to null in the same migration
  (no other field changes).
- **Tests:** `measuredThroughput` with the Pixel's rows (a 4 300 000 sample from 2026-09-25 and nothing else) gives null,
  not 4.3 Mbps; a report with 2 samples is ignored; a newer real sample replaces older ones; the migration nulls only
  known-default values.

## Non-goals

- A manual quality menu, or any per-location rule.
- Prewarming encodes for a title that was only browsed (detail page open ≠ an encode; only FR-309-3/-7's cheap work
  runs before Play).
- Changing the ladder's rungs (308's table stays).

## Acceptance

1. **meidam's case:** a 24 Mbps 4K DV episode cast to a Chromecast with no record, over a path of ~15 Mbps: first frame
   ≤ 3 s at 720p 4 Mbps, climbs 4 → 8 Mbps, never asks for 12 or 24.4 Mbps, **0 rebuffers** to the end.
2. **A known device:** Stue TV's Chromecast (proven 40 Mbps) starts an 80.9 Mbps 4K DV 7 REMUX at its top rung, first frame ≤ 3 s,
   0 rebuffers.
3. **Trust lost:** the same device throttled to 5 Mbps mid-film steps down before the buffer drops under 10 s, no
   rebuffer; its next play starts at the rung it held, not at the old proof.
4. **Seek:** a 15-min jump resumes within 3 s on the current rung (no cold-encode wait above it).
5. **No warning anywhere:** no *"Slow to start"* line on any detail page, phone or TV.
6. **Direct play:** a household TV with a record still direct-plays what it has proven; a device with no record plays a
   40 Mbps REMUX through the ladder first.
7. **GPU bound:** three viewers playing at once never exceed 9 encodes; a fourth viewer's play is never refused because
   of warm encodes.

## Tests

- The stream record: proof after 2 min clean, stall lowering it for 24 h, a later clean higher play raising it, direct
  plays counting, 30-day expiry.
- What the device can take: the `min` of ceiling, proof/measurement and stall.
- The start rung: with a record (its rung), without (720p 4 Mbps), the order of the master and the seed per player.
- The climb rule (one rung, 30 s buffered, rung warm) and the down rule (20 s and falling, or estimate) as pure
  functions shared by the receiver, web and Android where the code is common.
- The warm set: current/above/below on Play, on a climb, after a seek; the 3-encode bound; warming skipped first when
  the gate is short.
- The probe: size and time caps, metered size, joins `measured_bps`.
- The play decision cache: hit on Play, re-resolved on a track change, expires at 5 min.
- `playback_note` always null; no client draws it.
- Player integration (instrumented / browser lab): Media3 and hls.js under a throttled server climb one rung at a
  time and step down before 10 s of buffer.

## Open questions (dev)

1. Where the receiver gets Shaka to obey FR-309-4/-5: CAF's `PlaybackConfig.shakaConfig` sets the ABR at load, but the
   live restriction needs the Shaka instance CAF wraps — check what CAF v3 exposes; the fallback is an own ABR manager
   (`abrFactory`) passed in the config.
2. Whether Jellyfin's throttler and 60 s ping timer let a warm rung stay ready without encoding the whole film (lean:
   yes — it pauses once `ThrottleDelaySeconds` ahead; measure GPU load with two viewers before committing to "above"
   and "below" both).
3. Whether the record should be per (device, network) after all — a phone that moves between home Wi-Fi and mobile
   data. The owner's rule forbids location, but a **network type** (Wi-Fi vs mobile) is the device's own fact, not a
   location; lean: key `measured_bps` on (device, link kind), keep `proven_bps` per device, and let the stall rule
   correct the rest.

## Dev review (2026-10-07, against `main` `23600c28`)

Read against `VideoLadder.kt`, `AudioRenditions.kt` (`ladderMaster`, `stopFor`), `PlaybackService` (`startPlayback`,
`withRenditions`, `measuredThroughputOf`, `releaseEncodes`), `PlaybackQoeStore` + `PlaybackQoe.sq`, `DetailService` +
`PlaybackNoteResolver`, `Models.kt` (`PlaybackQoeReport`, `playback_note`), the players (`RaviloPlayerAndroid.kt` with
Media3 1.8.0, `SeededBandwidthMeter.kt`, `RaviloPlayerWasm.kt`'s hls.js config, `ravilo-cast` `Receiver.kt`,
`ravilo-desktop/native/Player.swift`, `MpvPlayer.kt`), Jellyfin's live `encoding.xml` (nvenc, throttling on at 180 s,
segments kept 720 s, one CUDA device `cu:0`), 308's build notes on `GetDynamicSegment` and the 60 s ping timer, and
30 days of `playback_start_sample`. The direction holds. Fourteen items, five for the owner.

1. **Starting low makes the first minutes safe, not the first frame fast.** A transcode's first frame is Jellyfin
   starting ffmpeg (`-analyzeduration 200M -probesize 1G`, then, for Dolby Vision, CUDA tone-mapping), and that cost is
   the same for every rung: 480p at 1.5 Mbps starts no sooner than 1080p at 24 Mbps. 30 days of start samples: direct
   plays on the household TVs start in 1–2 s (≈ 800 of them), transcodes in **8–38 s**. So acceptance 1's *first frame
   ≤ 3 s* and FR-309-2's target cannot be met by a start rung alone. Only an encode that is already running at Play can
   do it, and the spec's non-goal forbids one before Play. **For the owner (Q1):** start the first encode before Play?
   Lean: yes, but only on a strong signal — the TV's Play button focused for ≥ 1 s, a phone's detail page or cast sheet
   open — stopped 60 s after the signal ends (Jellyfin's ping timer does it for free). Without it, change the target
   to "no slower than today's start for that file" and keep the stall-free goal.

2. **The decision cache (FR-309-7) saves almost nothing; drop it.** PlaybackInfo starts no encode and mints a fresh
   `PlaySessionId` per call (180's teardown keys on it). Play must still read the resume position (`getItemDetail`)
   and post `/Sessions/Playing`, so caching PlaybackInfo saves one round trip of a few hundred ms against an 8–38 s
   cold encode, at the price of a stale audio/subtitle choice and a play-session id that must be used at most once.
   Lean: drop FR-309-7; Q1's early encode is the prewarm that matters.

3. **Warm encodes: "above" is covered by the climb rule, "below" is the one that pays.** A warm job is the backend
   fetching one segment of that variant at a position (Jellyfin starts ffmpeg there), then fetching again at least
   every 60 s (the ping timer) near the player's buffer end, because throttling stops it 180 s past its last fetched
   segment and a request more than 8 segments past its encode starts a new ffmpeg anyway — so "kept warm" is a
   **continuous second encode** for the whole play, not a one-off. A climb only happens with ≥ 30 s buffered
   (FR-309-4b) and a cold start is ≤ 20 s for most files, so the climb survives a cold rung; a step down happens with a
   draining buffer and is exactly when a cold start hurt (308's Pixel run: 52 s → 1.4 s). **For the owner (Q2):** warm
   only the rung below (2 encodes per play), both (3), or none. Lean: below only, and FR-309-4(c) becomes "with
   ≥ 30 s buffered (a cold rung starts inside that)".

4. **A seek is a cold start on every rung (FR-309-6, acceptance 4).** A forward seek past the encoded range restarts
   ffmpeg with `-ss` (seen 2026-10-06: `-ss 00:18:22.101`, `-start_number 367`), paying item 1's cost again; warming
   three rungs *at the new position* starts them at the same moment as the player's own request, so it cannot make the
   seek faster. Acceptance 4's *within 3 s* holds only inside the already-encoded range. Lean: keep "the player resumes
   on the current rung, never higher", drop "the same three started at the new position", and reword acceptance 4 to
   "no slower than a cold start, and never on a higher rung".

5. **Old and non-adaptive clients must keep today's behaviour.** A client that does not declare `hls_adaptive`
   (everything before 1.50, and mpv) gets one stream: if FR-309-2's "no record ⇒ 4 Mbps" became its
   `MaxStreamingBitrate`, it would sit at 720p forever. Rule: the no-record start applies to the **ladder's first
   listed variant and seed only**; a non-adaptive client keeps 308's FR-308-4 cap (measured or nothing). 1.50 clients
   (adaptive, 308 rules) benefit from the backend half at once — the new start order and seed reach them with no app
   release, and the receiver ships with the backend (`ravilo-cast` is served by it).

6. **The encode budget is ours to count, and on one GPU.** `ProcessGate` gates this server's own ffmpeg; Jellyfin's
   runs in its container (FR-309-6 corrected). Jellyfin encodes on a single CUDA device (`cu:0`); if that is the
   RTX 2060 SUPER, the consumer NVENC session limit (8 on current drivers) is shared by every viewer and every warm job;
   the Quadro P4000 has none. Count live variant jobs from `AudioRenditions`' registry (it already knows every
   variant session it composed) and check which card `cu:0` is before fixing the per-play bound.

7. **The stream record needs a table and one more QoE moment.** `playback_qoe` is an upsert per (device, item, play
   session) — the last state only, so "held ≥ 2 min with no rebuffer" cannot be rebuilt from it. Lean: migration
   **71** (`70` is 308's), a `device_stream_record` row per device (`proven_bps`, `measured_bps`, `stalled_bps`,
   `stalled_at`, `updated_at`), updated on every QoE post: a hold is the time between two posts on the same variant
   with the rebuffer count unchanged. That works with the reports players already send **if** they also post at every
   rebuffer (FR-309-11) — the receiver and Android post only at a switch and at the end today. Additive wire fields
   only: `time_to_first_frame_ms`, `start_variant_bps`; no field removed.

8. **Media3 can do both rules, by subclassing.** `AdaptiveTrackSelection` (1.8.0) picks the best format under
   `bandwidthFraction` × estimate and can jump several rungs; FR-309-4 needs a subclass whose `canSelectFormat` allows
   at most one rung above the selected one, and FR-309-5's buffer rule an override of `updateSelectedTrack` that forces
   a lower index when `bufferedDurationUs` < 20 s and falling. Both are protected/public and non-final; plug in through
   `AdaptiveTrackSelection.Factory.createAdaptiveTrackSelection`. *"Only to a warm rung"* cannot be known by the client —
   it goes (item 3). **hls.js**: `autoLevelCapping` set to current + 1 on each `LEVEL_SWITCHED` gives one rung at a time;
   its own ABR already switches down on predicted starvation. **AVPlayer**: `preferredPeakBitRate` and
   `preferredForwardBufferDuration` are settable on the item at any time in `Player.swift` — feasible as written.

9. **Linux (mpv) steps itself (FR-309-9 corrected).** The backend never sees mpv's buffer; the app reads
   `demuxer-cache-duration` (already read in `MpvPlayer.kt`) and calls R284's restream with a lower
   `max_video_bitrate`. A restream is a visible cut (new stream at the position) — acceptable as the last resort it is.

10. **The receiver needs its own ABR manager.** CAF's `PlaybackConfig.shakaConfig` is applied at load; there is no
    public handle on the Shaka instance to change restrictions live. Shaka's config takes `abrFactory`; the receiver
    can pass a small AbrManager of its own (Kotlin/JS, the shared rule of item 13) that does one-rung-up and the buffer
    rule, reading the buffer from the media element. Verify on the Stue TV Chromecast that CAF passes a function-valued
    `abrFactory` through (fallback: `restrictions.maxBandwidth` re-applied by a new `setPlaybackConfig` at the next
    load only — not live).

11. **The probe is fine through Caddy, with two guards.** Ktor native sends no compression and the Caddy labels on
    these services set no `encode`, so random bytes reach the client as sent. The route must be **authenticated**
    (device token — otherwise it is an open bandwidth sink on the public address) and `Cache-Control: no-store`. It
    measures the same uplink the segments use (both go through the same Caddy). On the receiver, probe **in parallel**
    with the load, not before it (a 2 s probe ahead of the first segment adds 2 s to every cast); the result seeds the
    next play.

12. **Removing the note is a backend-only change for installed apps (FR-309-10 holds).** `playback_note` defaults to
    null in `Models.kt` and every client renders nothing on null, so sending null retires the line on every installed
    app the day the backend ships. Keep `PlaybackNote`, the field, the strings and the `WireBaseline` entry until the
    clients drop `PlaybackNoteLine`; `playback_start_sample` stays (it becomes FR-309-11's time-to-first-frame history).

13. **The climb and step-down rules belong in `:shared`.** `ravilo-cast` already depends on `:shared` (it posts
    `PlaybackQoeReport`), so one pure rule — `nextRung(current, rungs, estimateBps, bufferedMs, bufferTrendMs)` — can be
    the same code on the receiver, Android, web and desktop; each player only adapts its inputs and outputs. The Tests
    section's "shared where the code is common" becomes "one implementation, tested once, plus a thin adapter test per
    player".

14. **Two more for the owner.** **Q3 — a device with no record and a file it could direct-play**: FR-309-8 sends every
    such file through the ladder on its first play, so a new phone's first episode is a transcode even on a perfect
    link. Options: direct play anyway; ladder until proven; direct play only files ≤ ~8 Mbps (most 1080p web
    releases) or when FR-309-3's probe says it fits, ladder above. Lean: the last. **Q4 — a video-copy transcode**
    (only the audio or container changes — 308's `reencodesVideo` says no ladder): a 60 Mbps 4K remux with TrueHD
    goes to an unproven device as one stream at 60 Mbps, the one case "the same ladder always" doesn't cover. Options:
    keep it (fast start, no adaptation); force a picture re-encode until the device has proven the bitrate (gets the
    ladder). Lean: force it, only when unproven. **Q5 — the probe on mobile data**: 1 MB (as written), never on mobile
    data, or the same 4 MB. Lean: 1 MB.

**Build order:** **309a** (backend + receiver, ships with one deploy): the stream record (migration 71), the start rule
with item 5's exemption, `playback_note` null, the probe route, warm-below (if Q2), Q1's early encode, the receiver's
AbrManager and its rebuffer post. **309b** (app releases): the shared rule in `:shared`, Media3 subclass, hls.js
capping, AVPlayer caps, mpv self-restream, the detail-page probe, rebuffer posts on Android/web/desktop. **309c**:
admin *Playing now* and device-row lines. Each part stands on its own; 309a alone fixes the 2026-10-06 case
(receiver jump 4 → 24.4 Mbps, no step down).

**Tests to add (beyond the Tests section):** the non-adaptive exemption (no-record v1.49 client gets no 4 Mbps cap);
the record derived from a QoE timeline (hold, rebuffer, stall decay); the encode budget counted from the registry; the
shared `nextRung` table (one-up only, never past the record, down at 20 s falling, several down at once); a Media3
subclass test with a fake meter and buffered durations; a receiver AbrManager test under a Node runner; the probe
route refusing an unauthenticated request.

## Decided by the owner (2026-10-07)

1. **Start the first encode before Play — on any detail page open for more than 2 s.** TV, phone, web and desktop
   alike. The warm encode is the one Play would start (the start rung at the resume position, with the remembered
   audio/subtitle choice). **Stop it fast when the viewer leaves** the page without pressing Play: the client says so
   at once (page closed / another title opened), and the backend stops the job on that signal, with Jellyfin's 60 s
   ping timer only as the fallback. **Casting gets the same:** when a phone (or desktop) has a Google Cast session
   to a TV, the warm encode is made for **the receiver** (its capabilities, its record, its play session), so
   pressing Play on the phone lands on a stream that is already running; the receiver then plays it as its own
   device (308's rule — a receiver never borrows the phone's measurement).
2. **Warm rungs: the owner left it to us ("the fastest and best experience").** Decided: the rung **below** is kept
   warm for the whole play (a step down never waits); the rung **above** is warmed only while a climb is possible
   (the player's estimate × 0.7 clears it and FR-309-4's buffer rule is close to met) and let go otherwise. So 2
   encodes per play most of the time, 3 briefly around a climb. The encode budget (FR-309-6, counted by us — review
   item on Jellyfin's own container) still drops warming first, never a play.
3. **No record + a direct-playable file:** direct play when the file is **≤ ~8 Mbps or the speed test (FR-309-3)
   says it fits** with 0.7 headroom; otherwise the ladder until the device has proven it.
4. **A big audio-only transcode on an unproven device:** force a picture re-encode so it gets the ladder, only while
   the device has not proven the file's bitrate.
5. **Speed test size: 4 MB everywhere**, mobile data included (overrides FR-309-3's 1 MB on a metered link).

## Re-dev review (2026-10-08, against `main` `4222ac4c`)

Read again after the 2026-10-07 Pixel case and FR-309-13, against the same code as the 2026-10-07 review plus the
tag `v1.50`, `RaviloPlayerAndroid.kt`'s QoE counting, Jellyfin's `encoding.xml`, and 14 days of `playback_qoe`.
The policy holds; the mechanism and two rules need changing. Ten items, three for the owner.

1. **Correction to the 2026-10-07 review, item 5: the 1.50 apps are not adaptive.** `v1.50` predates 308 and declares
   no `hls_adaptive` (production, 2026-10-07: `adaptive=false` for a Pixel on 1.50). So 309a, the backend half,
   reaches **only the Cast receiver**, plus single-stream decisions for everyone else. Android phones and TVs get the
   start rung, the climb and the step-down only with 309b in an app release. Build order:
   - **309a0:** FR-309-13's backend half alone, as a hotfix deploy (ignore known Media3 defaults and plays under 30 s
     in `measuredThroughput`). It is the one change that helps every installed app today.
   - **309a:** as written, for the receiver.
   - **309b:** the app release.
2. **Non-adaptive clients keep exactly one decision: the cap.** For a client without `hls_adaptive` (every installed
   app, mpv), `MaxStreamingBitrate` is the whole story. Rule: a real measurement (FR-309-13) × 0.7, or no cap at all.
   Never FR-309-2's no-record 4 Mbps start, which would pin them at 720p for the whole film.
3. **Most direct plays already "stall" once, briefly, and FR-309-8 would turn them into transcodes.** Stue TV, 14
   days: 170 of 265 direct plays report a rebuffer. All are under 2 s: 121 are a single stall under 0.5 s, 49 are
   0.5–2 s. Seeks are already excluded (`qoeSuppressNextBuffering`). FR-309-8 ("a direct play that rebuffers once
   switches to the ladder") and FR-309-1's `stalled_bps` would therefore move most of Stue TV's direct plays to a
   1080p H.264 transcode: worse picture, an 8–38 s restart, and GPU load. Change both to count a stall only when it
   lasts **≥ 2 s, or two within a minute**. Separately, find the single sub-0.5 s stall: it looks like a start
   artefact (a BUFFERING after the first frame while tracks settle), not the network. A small Android phase; it is
   also the one stall most viewers actually see.
4. **FR-309-13 is feasible on every player.**
   - Android: Media3's `BandwidthMeter` exposes no sample count, but `SeededBandwidthMeter` wraps it and can count
     transfers through its own `TransferListener` (`getTransferListener()`).
   - The receiver: count the segment loads it sees.
   - hls.js: count `FRAG_LOADED`.

   Add a test that the backend ignores a sample with `bandwidth_samples` < 3 even when the value is not a known
   default.
5. **Owner decisions 1 + 2 together set the GPU load, so the budget comes first.** Jellyfin encodes on one card (the
   RTX 2060 SUPER per 2026-10-05; consumer NVENC session limit). Per viewer: a detail-page prewarm, plus the playing
   rung, plus the warm rung below, plus briefly the one above, is up to **4** encodes. Two viewers, plus someone
   browsing detail pages, can reach the card's limit. Two things must be built first, not left to tuning:
   - the encode budget, counting every prewarm, variant and warm job from `AudioRenditions`' registry and the prewarm
     table, with prewarms dropped first;
   - the "stop fast on leaving" signal.

   R291's audio rendition jobs are CPU and disk, not GPU. A prewarm must **not** warm audio renditions: a rendition job
   reads the whole interleaved file around its audio, ~1.2 GB per 2-minute run-ahead, measured in R291.
6. **A prewarm is the play, or it is a stray session.** The detail-page encode must hand its `PlaySessionId` (and its
   Jellyfin `/Sessions/Playing`, if one was sent) to the Play that follows. Otherwise Play starts a second job, and
   the abandoned one's stop is exactly 312's stray stop at 0. Tie FR-309-6 to 312's FR-312-2 (one Jellyfin session per
   play): a prewarm posts **no** `/Sessions/Playing` until Play, and Play adopts the prewarm's job if the position and
   tracks still match, else stops it before starting its own.
7. **R379's retry restarts on the start rung.** Its engine rebuild re-prepares the same `MediaItem`, so on a ladder the
   player starts again from the first listed variant (the start rung), not the rung it held. It is rare, so it is fine,
   but the ladder's first listed variant should be refreshed from the device's record at that moment, not the
   original start guess.
8. **Better mechanism: one encoder per play, owned by jellystructure.** 309's warm rungs exist to hide Jellyfin's
   per-job cold start: `-analyzeduration 200M -probesize 1G`, CUDA init and tone-mapping, paid per rung and again per
   seek. R291 already runs jellystructure's own ffmpeg per play: its rendition jobs start their first segment in
   **0.6–0.8 s** from the same files, timed to Jellyfin's video within 21 ms. The same runner, for video, should be
   **one** process per play: probe facts already known from our scan, one decode and tone-map, a split to N NVENC
   outputs with keyframes aligned at the segment length, segments made on demand, paused ahead, restarted once per
   seek. Compared with 309 on Jellyfin jobs:
   - every rung is always warm, for one decode;
   - the first segment should come in about a second;
   - a seek restarts one process, not N;
   - the GPU budget is one decode plus N encodes, not N full pipelines;
   - there is no Jellyfin play session to leak a stray stop (312).

   This is fork 2's direction, grounded in code that already ships. **For the owner (Q6):** (a) keep 309's policy and
   move its mechanism to a jellystructure encoder in a new phase (309's prewarm and warm rungs then become "start the
   encoder early") **(lean)**; (b) build 309 on Jellyfin jobs as decided, and revisit later.
9. **HEVC rungs (308's re-review, item 4) are the cheapest big win.** The same quality at roughly half the bits makes
   every rung of every ladder less likely to stall, and an HDR TV keeps HDR. **For the owner (Q7):** HEVC transcodes
   for devices that decode HEVC over HLS, H.264 kept for the rest. Lean: yes, after one measurement on a BRAVIA and the
   encoding card.
10. **The top rung by output resolution (308's re-review, item 3).** Fold into FR-309-2: the start rung and the climb's
    ceiling use a top capped per output (1080p H.264 ≤ 15 Mbps). The 2026-10-06 cast would then have topped out at
    15 Mbps, under the measured ~15 Mbps × 0.7 rule, and stayed on 8 Mbps.

**For the owner (Q8):** the direct-play stall rule (item 3). Lean: **≥ 2 s or two within a minute**, plus a small
phase to remove the single sub-0.5 s stall at start.

## Decided by the owner (2026-10-08, after the streaming re-review)

1. **Who encodes (Q6): Jellyfin.** The owner declined an encoder of our own, so 309's rules stay on Jellyfin's jobs:
   the early encode on a detail page (> 2 s), the warm rung below, the encode budget counted by us, and a stop signal the
   moment the viewer leaves. The re-review's GPU budget items apply in full (up to 4 encodes per viewer, all on one card):
   **the budget and the leave signal are built before any warming**.
2. **Which stalls lower a device's record (Q8): a stall of ≥ 2 s, or two within a minute.** Plus a small phase of its
   own to find and remove the sub-0.5 s stall at the start of direct plays (170 of Stue TV's 265 direct plays).
3. **Build order:** **309a0** first, as a backend-only hotfix that helps every installed app: FR-309-13 (a guess is
   never a measurement), no cap from a guess on a client that cannot adapt, and the receiver's own player settings
   (`useShakaForHls` so 308's Shaka settings apply at all; its start time and its estimate's sample count). Then 309a,
   309b (the app release), 309c.
4. **HEVC (Q7): yes, after measuring**, as its own phase (see 308's decisions).
5. **The order of the whole work:** See `specs/research-reports/ravilo-streaming-plan-2026-10-08.md` for the whole order.

## Owner, 2026-10-08 (later): use our own encoder

> *"Then let's change and use our own encoder if that makes the experience better."* This reverses the earlier
> "stay on Jellyfin" answer.

- **Who encodes (Q6): our own encoder** (replaces decision 1 of the earlier owner section). 309's per-device record,
  FR-309-13, the speed test, the start rung, the early step-down (the receiver enforcing it), the early encode on a
  detail page and its leave signal, the direct-play gate, the stall rule and the mpv/AVPlayer rules all **stay**.
- **Changed once the encoder exists:** the warm-rung rules (FR-309-6) go: every rung is already running in the one
  process. The climb rule relaxes to the players' stock values (a switch no longer waits for a cold encode). The
  decision cache (FR-309-7) is dropped (the start is now ~1 s). Until the encoder ships, 309a keeps only "warm the
  rung below" on Jellyfin, with the budget and the leave signal first.
- The early encode on a detail page starts **our** encoder's job, which becomes the play's own job when Play is pressed.

## Build notes — 309a0 (2026-10-08)

The backend-only hotfix (decision 3 of the owner section after the re-dev review), built on `main` and deployed to the
dev stack; the rest of 309 (record, probe, prewarm, the climb rules) is not built.

- **FR-309-13, backend half.** `VideoLadder.kt`: `KNOWN_BANDWIDTH_GUESSES` (Media3 1.8.0's `DefaultBandwidthMeter`
  initial estimates for Wi-Fi, 2G, 3G, 4G, 5G-NSA and 5G-SA, its 1 Mbps fallback (also Shaka's default) and hls.js's
  500 kbps default), `isRealMeasurement`, and `measuredThroughput`: a sample counts only if HLS, positive, not a known
  guess, and (when the player says how many transfers it rests on) at least 3. A newest sample from a player that counts
  its transfers replaces the older ones instead of being averaged with them.
- **No cap from a guess.** The cap (`throughputBudget(measuredThroughput)`) is now fed only real measurements, so a
  client without `hls_adaptive` (every installed 1.50 app) is capped only by a real one. The Pixel's 2026-10-07 case (one
  stored 4 300 000) now gives no measurement and no cap.
- **Wire (additive, optional):** `PlaybackQoeReport.bandwidth_samples`, `bandwidth_bytes`, `first_frame_ms`; stored in
  `playback_qoe` (72.sqm), which also sets `bandwidth_estimate_bps` to NULL on rows holding a known guess (nothing else).
  The Android/web/desktop players don't send the counts yet (309b); the backend falls back to the known-guess table.
  *Plays under 30 s* is not used: a row carries no play length, and the transfer count covers the same case for new apps.
- **Cast receiver** (served by the backend, so it ships with the deploy): `context.start({ useShakaForHls: true })` so
  308's Shaka settings (buffer goal 40 s, ABR targets) apply at all; its QoE now carries `first_frame_ms` (load → first
  PLAYING) and `bandwidth_samples` (segment requests counted by `PlaybackConfig.segmentRequestHandler`); it sends no
  estimate before 3 segments.
- Tests: `VideoLadderTest` (the Pixel's rows give null and no cap; every known guess ignored; < 3 transfers ignored, a
  counted sample trusted whatever its value; a newer counted sample replaces older ones).

### Live — 309a0 (2026-10-08, dev stack v1.50-51-g285663ef)

- Migration 72 ran (schema 73): the three columns exist and no stored `bandwidth_estimate_bps` holds a known guess.
- A Stue TV debug-app play after the deploy logged `measured 247094k → budget 172966k (308)` (a real HLS
  measurement) and direct-played.
- The Pixel 9 Pro's stored rows now give its real 168 Mbps HLS sample as the measurement (its guessed 4 300 000 row is
  null), so its next play is no longer capped at ~3 Mbps; not played live (the Pixel was off adb).
- The deployed `/cast/ravilo-cast.js` contains `useShakaForHls`. **Not verified on a Chromecast:** no free sender at the
  time (Stue TV was in another test, the Pixel off adb, the Mac playing the owner's music). The receiver's next QoE
  rows will show `bandwidth_samples` and `first_frame_ms`; a cast to Stue TV's Chromecast should be checked next.

### Still owed (2026-10-08 evening)

The live check planned after the v1.50-54 deploy was not made: Stue TV was in use by a viewer, and the owner then
paused all device use except the Pixel. Owed: a cast to Stue TV's built-in Chromecast confirming the receiver plays
with Shaka and reports its start time and sample count (309a0), and an audio switch on Android on R382's confirmed
film.

## Live check of 309a0 on the Cast receiver (2026-10-09, v1.50-78)

The receiver now plays with Shaka's ABR and reports what 309a0 asked for: `first_frame_ms` (15 888), `bandwidth_samples`
(5 212), variant switches (5 up / 3 down) and the variant bandwidth. **Verified.** (The play itself stalled badly; see
313's live notes.)

## Build notes — 309a / 309b (2026-10-08, worktree branch, not merged or deployed)

Built together with 313d/313e (they share `PlaybackService`). Under our own encoder (owner, 2026-10-08) FR-309-6's warm
rungs and FR-309-7's decision cache are **not built and not needed**: every rung runs in the one process, a start is
about a second. 309 had built neither, so nothing was removed.

**Backend (309a):**
- **The record (FR-309-1).** `device_stream_record` (migration **77**): `proven_bps/at`, `measured_bps/at`,
  `stalled_bps/at`, one row per device, in the master's own units (a variant's `BANDWIDTH`; a direct play's file bitrate
  ×1.05 + 256 kbps). `StreamRecord.kt`: a stream held ≥ 2 min with no new stall proves it; a stall counts when it is
  ≥ 2 s or two come within 60 s (owner Q8); a counting stall sets `stalled` (that stream) and restarts the hold; a later
  proof at or above it clears the stall. Only R381 per-item reports are read (FR-309-13). The hold between posts is kept
  in memory (a restart delays a proof, nothing else).
- **What a device can take** (`canTake`): the higher of its proof and its newest real measurement × 0.7 (the probe's or
  308's QoE measurement, whichever is newer, each ≤ 30 days), under a stall's cap (× 0.8 for 24 h). **Deviation:** the
  spec says *proof or measurement*; taking the higher keeps a device that has proved only its 720p start from being held
  there once it has measured a fast path. The stall cap still wins.
- **The negotiation's cap** (`negotiationCap`, FR-309-8 + owner decisions 3/4): an adaptive client gets *take*, else
  8 Mbps (the no-record direct-play gate; a file above it gets the ladder); a non-adaptive client (1.50 apps, mpv) gets
  *take* or no cap (re-review item 2). Used by start, prepare, restream and the prewarm.
- **The start rung (FR-309-2):** our encoder starts on the best rung whose advertised stream fits *take*; with no record
  and a player that climbs, the highest rung with ≤ 4 Mbps of video (720p); a player that can't climb gets one rung (the
  one its record fits, else the top). Jellyfin's own ladder (the fallback) gets the same budget (*take*, else 4.5 Mbps).
  The ticket carries `start_variant_bps`, and the seed (`measured_bandwidth_bps`) is the start stream ÷ 0.7 so the
  player's own × 0.7 rule lands on the start rung.
- **The speed test (FR-309-3):** `GET /api/tv/probe` — 4 MB of random bytes (made once), `Cache-Control: no-store`,
  **204** when the device was measured within 24 h (`?force=1` always sends); `POST /api/tv/probe/result {bytes, ms}`
  stores `measured` (ignored under 1 MB or 50 ms).
- **The early encode (FR-309-6):** `POST /api/tv/playback/prewarm {item_id, capabilities, audio_language?,
  audio_variant?, cast_device_id?}` → `{status: warm|direct|none, reason?}`; `POST /api/tv/playback/prewarm/cancel`.
  Our encoder only: the same negotiation and plan Play would make, registered under a prewarm key, no
  `/Sessions/Playing`, no R368 session, no tracker, no R291 rendition job. Play **adopts** it when the plan matches (file,
  codec, rungs, start rung, audio, subtitles, burn-in, mux) and the start is within one segment; otherwise the prewarm is
  stopped first. A re-post touches the running job; an abandoned one ends on the job's 60 s idle timer. When the cards
  are full, prewarms are stopped before a play falls back. With `cast_device_id` the encode is made for that Cast
  device's receiver (its last capabilities, its own record).
- **No more "slow to start" (FR-309-10):** `playback_note` is always null (the field stays on the wire); the resolver and
  its tests stay until the field can go.
- **Admin (FR-309-11):** *Playing now* reads *our encoder · 4 qualities · HEVC HDR · 4.4 Mbps → 1080p · 8 Mbps ·
  climbing* (or *stepped down once*); the device row adds *· holds 8 Mbps · stalled at 24 Mbps yesterday*
  (`stream_holds_bps`, `stream_stalled_bps`, `stream_stalled_at` on the overview device). QoE stores
  `start_variant_bps` and `encoder` (77).

**Clients (309b):**
- `LadderRules` (`:shared`): never past the next rung up; a climb only with 30 s buffered (the stock 10 s on our encoder,
  owner); down below the playing rung whenever the buffer is under 20 s and falling (again each time it keeps falling).
- **Android:** `ClimbingTrackSelection` bounds Media3's adaptive selection with it (`canSelectFormat`), on the 308 values
  (decrease while ≤ 45 s buffered, keep 50 s on a switch up, × 0.7). `PlayerLadderHints.oursEncoder` from the ticket.
  QoE now carries `bandwidth_samples`/`bandwidth_bytes` (one per `onBandwidthEstimate`, this item's), `first_frame_ms`
  (the item's first load to its first frame) and `start_variant_bps`.
- **Every client (commonMain):** QoE is posted every 2 min (was 10), at the first frame and at every new stall, so a
  2-minute hold reaches the server (one upserted row per play). The detail page (`DetailPrewarm`, movie and series — the
  episode Play would play, once the playstate says which): after 2 s the speed test, then the prewarm with the
  remembered audio, re-posted every 30 s, cancelled on leaving (1.5 s grace, skipped when a play of that item started).
  `currentClientCapabilities()` is now the one builder for a start and a prewarm. R222's line is no longer drawn.

**Tests:** `StreamRecordTest` (stall counting, proof, a stall's cap and expiry, legacy rows ignored, `canTake`,
`probeNeeded`, the negotiation's cap, the store, the QoE columns), `EmbeddedSubtitleOrderTest`, `EncoderPlanTest` (+7:
no-record start rung, record start rung, one rung for a non-climbing player, burn-in, WebVTT renditions, admin words,
prewarm adopt/cancel), `LadderRulesTest`, `QoeCounterTest` (time to first frame), `MusicEditionsStoreTest` rewind
updated for 77.

**For the deploy:** migration 77 sits after a free 76 (R379's branch uses 76): **merge R379 first**, or a database
already at 78 never runs 76. Live tests: a Stue TV debug play with no record starts on 720p and climbs one rung at a
time; `GET /api/tv/probe` answers 204 the second time; a detail page held 2 s logs `encoder: prewarm …` and Play logs
`… prewarmed`; Back logs `prewarm cancelled`; the admin's device row shows *holds …* after a 2-minute play.

## Live results after the integration deploy (2026-10-09, v1.50-105 → v1.50-106)

Deployed with R379, 313d/e, R376 and 314b/c (`22a955c8`, then `d7ecba1e` with the language fix below). Schema 78:
`device_stream_record` and QoE's `start_variant_bps`/`encoder` columns present.

- **Speed test (FR-309-3):** `GET /api/tv/probe` answers **204** for a device measured within 24 h (the Pixel, twice);
  `?force=1` sends **4 194 304 incompressible bytes** (gzip makes them bigger) through Caddy in 169 ms, and
  `POST /api/tv/probe/result` records it (`probe: … 4194 kB in 169 ms → 198546k (309)`).
- **Early encode (FR-309-6):** `POST /api/tv/playback/prewarm` for a 4K Dolby Vision 7 film answers **warm**:
  `encoder: prewarm … H264 rungs=4`, first segment **953 ms**; `…/prewarm/cancel` → `prewarm cancelled … the viewer
  left the page (309)`. The page-driven call from the app (2 s on the detail page) was not seen live: the Pixel locked
  itself before the retest and no other signed-in debug app was available (see below).
- **Found live and fixed (`d7ecba1e`):** every play of a film whose file tags a language by its 639-2/B code (`fre`,
  `ger`, `dut`, `cze`…) fell back to Jellyfin — Jellyfin reports the 639-2/T code (`fra`, `deu`…) and
  `fileAudioOrder` compared the codes. It now compares languages (`sameLanguage`). Test in `AudioRenditionsTest`.
- **Cap and record:** a start logs `takes 247881k (measured 354116k) → cap 247881k (309)`; the device's record exists.
- **Not verified live:** *no record ⇒ 720p, then one rung at a time* (every household device already has a real
  measurement, so none starts without a record — covered by `EncoderPlanTest`/`LadderRulesTest`), *holds …* on the
  admin device row after a 2-minute play (needs a real player's QoE), the client half (309b) on a device.

## Build notes — 309c (2026-10-09, worktree branch, not merged or deployed)

The rest of 309 except the web player. No migration, no new wire field (the cast prewarm uses the `cast_device_id`
309a already accepts). Nothing here is device-tested.

- **One stall rule (`StallRule`, `:shared`):** a stall counts if it lasts ≥ 2 s, or two within 60 s (owner Q8). The
  server's `StreamRecord` now uses it too, so the client's decision and the server's record cannot disagree.
- **A direct play that stalls moves to the ladder, once per item (FR-309-8):** on a counting stall the store posts
  QoE, **waits for that post**, then restreams at the current position (R284). The server's record already has the
  stall and caps *take* at the stalled stream × 0.8 (FR-309-1), so the new negotiation transcodes on the rung under
  what it carried — no new field says "ladder please". The audio index is left `null` (a non-null pick would force
  HLS-only on players that switch audio inside the file); the server takes the remembered audio, and the track
  resolver is re-armed for the new ticket (`PlayerLadderHints.rearmTracks`, read through `PlayerBookkeeping`'s
  property so PlayerScreen's body stays at 249 registers in the release dex).
- **Receiver over Shaka (FR-309-5):** `PlaybackConfig.shakaConfig.abrFactory` builds `ReceiverAbr`, a wrapper round
  Shaka's own `shaka.abr.SimpleAbrManager`: every call forwarded, every choice bounded by `LadderRules.allowedMaxBps`
  (one rung up, with 30 s buffered — 10 s on our encoder), and a 2 s timer that switches down when the buffer is under
  20 s and falling, whatever Shaka's estimate says. Logs `309 abr: …`. **Unverified:** if this CAF build drops the
  function from the config or its Shaka has another ABR interface, the factory is not called (or returns null) and
  Shaka's own manager runs as before.
- **Receiver speed test (FR-309-3):** on its idle view (never during a start), the receiver calls `/api/tv/probe`; the
  server answers 204 when it was measured within 24 h. Logs `309 probe: N kb/s`.
- **Cast prewarm (FR-309-3/-6, sender side):** Android's and the desktop's cast senders put the chosen receiver's
  device id in `CastTargetHint`; `DetailPrewarm` sends it as `cast_device_id` with the prewarm and its cancel.
  **Decision: while casts are routed to Jellyfin (313's `CAST_RECEIVER_FALLBACK`), the server answers `none` and the
  prewarm is a no-op** — warming our encoder for a play that will go to Jellyfin would waste a GPU job, and
  Jellyfin's own start cannot be warmed without starting a transcode session nobody adopts. Once 313's cast stall
  is fixed, the id is already there and nothing on the clients changes.
- **mpv (FR-309-9):** `RestreamStepper` (`:shared`) — one rung down when the buffer ahead (`bufferedMs − positionMs`
  from mpv's demuxer cache) is under 20 s and falling, not again within 30 s; one rung up after 2 min clean with
  ≥ 30 s buffered, the cap lifted above the top rung. The store restreams with `max_video_bitrate` set to the rung
  (under the device's own, restored when lifted), keeping the burned subtitle and audio. Rungs are 308's video
  bitrates (1.5 / 4 / 8 / 12 Mbps); the playing rung is read from the ticket's start variant.
- **Mac (FR-309-9):** the AVPlayer item gets `preferredForwardBufferDuration = 40` s; `MacLadderPeak` reads the master
  playlist's `BANDWIDTH`s, sets `preferredPeakBitRate` (+5 %) to the start rung, and lifts or lowers it by
  `LadderRules` every other tick of the watch loop (`309 peak: …`). New Swift symbol
  `ravilo_player_set_peak_bitrate` — **not compiled here (Linux)**; an older dylib without it is caught
  (`UnsatisfiedLinkError`) and the player runs as before.

**Tests:** `LadderSteppingTest` (`:shared`, 8 — the stall rule, the master's bandwidths, the Mac's peak, mpv down/gap/up
/lowest, a variant's video bitrate) and `PlayerLadderSwitchTest` (`:ravilo-ui`, 4 — once per item, only from a direct
play, the stepping cap). `:shared:linuxX64Test`, `:ravilo-ui:desktopTest` + `testDebugUnitTest`, the wasm and receiver
compiles, `:linuxX64Test`, the release APK (`check-player-dex` 249) and the check scripts.

**Live checks owed:** a Chromecast cast logging `309 abr: Shaka bounded by the ladder rule` (and a step down under a
throttle) and `309 probe`; a direct play throttled on Android or the web logging `309: the direct play … stalled` and
continuing on a transcode at the same position; mpv on Linux under a throttle (`309: stepping … down`, then up after
2 min); the Mac (after the dylib is rebuilt) logging `309 peak`.

## Mac live check (2026-10-09, test build `v1.50-117-g5c2fa010` built on the owner's new MacBook (M5 Pro, macOS 27.0.1) in `~/ravilo-test`, **signed ad hoc** (the Ravilo signing key isn't on the new Mac); driven with the in-app test driver)

- The rebuilt `libravilo-mac.dylib` exports `ravilo_player_set_peak_bitrate`; AVPlayer logged `309 peak 18384k (2196 ms
  buffered)` at the start (the device's record put it at the top rung, so no climb was seen).
- The Mac's speed test: `probe: … 4194 kB in 341 ms → 98400k`, cap 68.9 Mbps. Leaving a detail page logged
  `prewarm cancelled … the viewer left the page`.

## Live, 2026-10-09 (Pixel 9 Pro debug 1.50-119, Køkken hub, dev stack v1.50-130)

- **Prewarm (FR-309-11) — passed:** opening a film's page on the Pixel logged `encoder: prewarm device=… at segment 0
  H264 rungs=4 (309)` and the play that followed started on the warm job (first segment ~1.2 s).
- **A Chromecast cast logs `309 abr` — passed:** the Køkken hub's receiver sent *309 abr: Shaka bounded by the ladder
  rule*; the server's start line read *takes no record → cap 8000k (309)*. No `309 probe` note came in the first
  8 minutes of that cast.
- **A stalled direct play moves to the ladder (FR-309-8) — passed:** a direct play on the Pixel (file ≈ 6.6 Mbps)
  under a server-side `tc` cap of 1.2 Mbit/s to the phone's address (never a proxy on the phone): the server logged
  *record: device=… stalled on 6653k — holds ×0.8 for 24 h*, the phone logged *309: the direct play of … stalled
  (1 counting) — restarting it on the ladder at 110511 ms*, and the restream came back as our encoder's ladder capped
  under the stall (800p@6092k / 532p@4000k / 354p@1500k). At 1.2 Mbit/s even the bottom rung and the API starve, so
  the stop watchdog ended the play and the phone showed *Couldn't reach the server* — the switch is proven, a smooth
  continuation at that cap is not possible. The phone's stall cap (a test artifact) was cleared from
  `device_stream_record` afterwards.
- **Note:** the watchdog's stop is written as `reason=watchdog` and later progress is *ignored for already-stopped
  playback* — on a link this slow the restream plays on with no session on the server.

## Live, 2026-10-09 evening (Stue TV, v1.50-138)

- **A Cast Connect play on a TV app with no record is transcoded even when the TV could play the file directly.**
  The Stue TV debug app (fresh install, no `device_stream_record`) got `takes no record → cap 8000k (309)` for a 4K HEVC
  HDR film the BRAVIA decodes, so our encoder made an H.264 SDR ladder (first segment 1.3 s, played fine). The
  speed test (FR-309-3) runs on a detail page, which a Cast Connect start never opens on the TV. **To fix:** the TV app
  runs the probe when it starts (or on the Cast Connect load) when its measurement is missing or stale, and a TV whose
  decoders take the file without a record uses FR-309-8's small-or-speed-tested rule with that probe.
- **309's receiver ABR (FR-309-5):** logs `309 abr: Shaka bounded by the ladder rule` on every load and `309 probe`
  (4 MB in 460 ms → 72.9 Mbps); its step-down crashed Shaka after a reload — fixed in 04a5b93e (see R291).

### FR-309-14 — The Ravilo TV app with no record plays what its decoder takes (2026-10-09, from the finding above)

- **Owner decision 3's 8 Mbps no-record gate is for phones, computers and the Cast web receiver, not the TV app.** A
  start on the Ravilo app on a TV (`platform = tv`, never a Cast receiver's row) with no record is negotiated with no
  throughput cap (`negotiationCap(…, tvApp = true)` → none): its own decode ceiling (`max_video_bitrate` in
  `MaxStreamingBitrate`, and phase 177's per-codec requirement) decides whether the file plays directly. A 4K HEVC HDR
  film the BRAVIA decodes direct-plays from the first Cast Connect start. Once the TV has a record (a proof, a
  measurement, a stall), the record caps it like any device. A transcode it still needs starts on the no-record rung and
  climbs (FR-309-2 unchanged). Every start path applies it: the start, the restream, the burn-in, the prewarm and R381's
  prepare.
- **The TV app runs the speed test when another app starts a play on it** (a Cast Connect LOAD of a film, a
  `session_load` of a film or an episode) — the detail page where FR-309-3 runs it is skipped. The server answers 204
  when this TV was measured within 24 h; nothing waits on it.
- **Tests:** `StreamRecordTest` — a TV app with no record has no cap; a record still caps it; a phone keeps 8 Mbps;
  `isTvApp` is true only for `platform = tv` and never for a Cast receiver or a device that sent no platform.
