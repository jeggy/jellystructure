# Phase 308 — A viewer outside the house gets a stream their connection can carry

> Owner, 2026-10-05: meidam (outside the house) said *The Housemaid* was lagging; the investigation is
> `memory/project-meidam-streaming-lag-2026-10-05.md` (read-only, nothing changed).

## Status

`Planned` — written 2026-10-05 (dev-authored) from that investigation, checked against `main` `4fec8034`. Not
dev-reviewed. Backend (`JellyfinClient.maxStreamingBitrate`, the play paths) and one Settings field. **Open question 1
(the remote limit's default) is the owner's.**

## What happens today

- The bitrate Ravilo asks Jellyfin for is `min(120 Mbps, the device's decode ceiling, the client's LOCAL link × 0.5 wifi
  / 0.9 ethernet)` (Phase 177 FR-177-4). **Nothing knows whether the viewer is in the house.** meidam's phone reported
  wifi 1080 Mbps ⇒ 540 Mbps, i.e. no cap; his Chromecast reported no ceiling and no link at all.
- Jellyfin then sets the encoder's target to **the source's bitrate**: a 4K Dolby Vision 7 REMUX (80.9 Mbps) became a
  1080p H.264 transcode with `-b:v 80889815` — about 20 Mbps average and 40 Mbps peaks in practice — over a path the
  phone's own player had measured at **~19 Mbps**. The film advanced at ~0.29× realtime while the encoder sat 10× ahead.
- Jellyfin's own `RemoteClientBitrateLimit` cannot help: Jellyfin sees the backend and Caddy, never the viewer.
- What we already know about a remote viewer: `ravilo_device.last_public_address` (meidam: 81.25.179.243; the
  household: its own), and R216's `playback_qoe.bandwidth_estimate_bps` (meidam's phone: 19.0 Mbps).

## Requirements

### FR-308-1 — A transcode's target fits its output

When the stream will be transcoded, the bitrate asked for is never above what the output needs: **1080p H.264 ≤ 12 Mbps,
720p ≤ 6 Mbps, 2160p HEVC ≤ 40 Mbps** (one table in one place). A direct play or a remux is untouched — the file's own
bitrate is what it is. This alone would have halved meidam's stream with no visible loss.

### FR-308-2 — Who is outside the house

A play request is **remote** when the requesting device's public address differs from the household's (the address the
household's own devices report, the server's public IP, or a configured list — the build picks one rule and states it).
A cast receiver is remote when the app that enrolled it is (meidam's Chromecast shared his phone's address).

### FR-308-3 — A remote limit

Settings gains *Streaming outside the house: up to N Mbps* (`remote_streaming_limit_mbps`). A remote play asks for at
most that. **Default: open question 1** (lean 15 Mbps — 1080p comfortably, under meidam's measured 19).

### FR-308-4 — What the viewer's own player measured

For a remote device with recent QoE rows, the ask is also capped at **0.8 × the median of its last 5
`bandwidth_estimate_bps`** (never below 3 Mbps), so a slower connection than the setting still plays.

### FR-308-5 — Say why

The admin's *Playing now* (304) and the device's row show the cap that applied and why (*remote · 15 Mbps*, *measured
19 Mbps*), so the next report has evidence. A cast receiver reports QoE like any app (it reports none today).

## Non-goals

- Adaptive bitrate ladders inside one transcode (Jellyfin produces one rendition).
- Changing direct-play decisions for viewers inside the house.

## Acceptance

1. *The Housemaid* to a Chromecast outside the house is transcoded at ≤ the remote limit (and ≤ 12 Mbps for 1080p
   H.264), and plays without rebuffering on a ~19 Mbps path.
2. The same title on the household LAN is unchanged (direct play where it direct-played).
3. The admin shows the cap and its reason on the session.

## Tests

- The output table (FR-308-1) for each resolution/codec; direct play untouched.
- Remote detection: household address vs other; a cast receiver inherits its enroller's.
- The measured-bandwidth cap: median, 0.8×, floor 3 Mbps; no rows ⇒ the setting alone.

## Open questions

1. **The remote limit's default** — the owner's. Lean: **15 Mbps**.
2. Remote detection rule: compare with the server's public IP (simple) or with the addresses the household's own
   devices report (robust to a changing IP)? Lean: the server's public IP, falling back to the household's reported
   addresses.
