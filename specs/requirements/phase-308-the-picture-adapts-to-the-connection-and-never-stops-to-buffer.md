# Phase 308 — The picture adapts to the connection, and never stops to buffer

> Owner, 2026-10-05: *"The user should never decide. Ravilo and Jellystructure should talk together and if needed then
> automatically pick a lower bitrate. Ravilo should automatically pick a lower rate if needed (even mid-stream) and if it
> thinks it can bump the bitrate again then it should do that. It should always automatically pick the best quality it
> thinks it can play where the video streaming will never stop for buffering."* — after meidam's lag report
> (`memory/project-meidam-streaming-lag-2026-10-05.md`).

## Status

`Planned` — written 2026-10-05 (dev-authored) from the owner's direction above, checked against `main` `b0fce38a`.
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

The first variant is the best one the device's **own recent measurements** (R216 `bandwidth_estimate_bps`, rebuffers,
for that device) say it sustains with headroom (≈ 0.7× the median of the last plays); with no history, a viewer outside
the house (public address ≠ the household's) starts at 1080p 8 Mbps and climbs, one inside starts at the top. A cast
receiver starts from what its enrolling app measured.

### FR-308-4 — Direct play only when it is safe

A file is direct-played only when its own bitrate fits what the path is known to carry (FR-308-3's estimate); otherwise
it gets the ladder. On the household LAN this changes nothing; for meidam it would have meant 8–12 Mbps 1080p from the
first second.

### FR-308-5 — Stalls are measured

Each player reports to R216's QoE the variant switches (down, up, when), rebuffers, and the variant it settled on —
the Cast receiver included (it reports nothing today). The admin's *Playing now* (304) shows the current variant
(*1080p · 8 Mbps · stepped down twice*).

### FR-308-6 — Nothing to decide

No setting, no toggle, no quality picker for the viewer or the admin. (Direct play vs transcode stays automatic as
today.)

## Non-goals

- A manual quality menu.
- Changing what plays on the household LAN when it already plays without stalls.

## Acceptance

1. *The Housemaid* on a Chromecast outside the house (or a phone throttled to ~19 Mbps) starts within a few seconds,
   plays to the end without a single rebuffer, and settles on a 1080p variant ≤ 12 Mbps.
2. Throttling a phone mid-film from 50 → 5 Mbps steps down within a segment or two with no stall; lifting the throttle
   steps back up within a minute.
3. On the household LAN, a direct-playable file still direct-plays.
4. QoE rows show the switches; the admin shows the current variant.

## Tests

- The ladder builder: variants per device decode ceiling and source; bandwidth/resolution/codec lines; audio shared.
- The first-guess rule: history median × 0.7, remote/no-history start, inside/no-history start.
- The direct-play gate.
- Player integration: ExoPlayer selects a lower variant under a throttled `MockWebServer`; hls.js likewise in the web lab.

## Open questions

1. **How many variants run at once?** Lean: only the one being read (plus at most one starting after a switch);
   Jellyfin's throttling pauses the rest. Measure GPU/CPU with the P4000 + RTX 2060 before committing to five rungs.
2. **Jellyfin's own `EnableAdaptiveBitrateStreaming`** — check what Jellyfin 12.1 does with it before composing the
   ladder ourselves; use it if it already lists variants we can trust.
3. Shaka on older Chromecasts and multi-variant H.264 — verify on the bedroom TV's Chromecast and a stick.
