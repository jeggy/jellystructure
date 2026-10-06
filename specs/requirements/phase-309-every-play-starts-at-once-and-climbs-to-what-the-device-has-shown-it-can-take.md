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

`Planned` — written 2026-10-07 (dev-authored) from the owner's direction above, checked against `main` `00429b14`
(31 commits ahead of `origin/main`; 309 is free on both). Not dev-reviewed, not built (owner: spec only).
**Builds on 308** (the ladder, the composed master, the players' own ABR, the receiver's QoE) and **replaces 308
FR-308-3's start rule and FR-308-4's direct-play gate**. **Retires 185 FR-185-5…7's note and all of R222** (the
*"Slow to start on …"* line). Backend, every player (Android/Media3, web/hls.js, Mac/AVPlayer, Linux/mpv, Cast/Shaka),
the admin's *Playing now*.

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
- **Bound:** at most **3 encodes per play**; warming never queues or delays a real play — if the GPU encoder slots
  (`ProcessGate` / NVENC) are short, warming is skipped first, and a warm encode for another viewer is stopped before a
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

- **Linux (mpv)** — no HLS adaptation. It gets one variant chosen by FR-309-2 and the backend steps it by restream at
  the current position when FR-309-5's rule fires (mpv's buffer level from `demuxer-cache-duration`), or up one rung
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
