# Ravilo streaming: the plan to remove buffering and lag (2026-10-08)

> Owner, 2026-10-08: *"Let's do a review of all the not yet implemented or not fully implemented specs and do a
> re-devreview on all those that are related to the ladder and auto encode etc. And try to make sure we are suggesting
> the best approach … for all users to not feel any buffering or lagging when streaming on Android, TV or Chromecast."*

Sources: `ravilo-streaming-evidence-2026-10-08.md` (numbers from the household's own plays),
`ravilo-streaming-approach-2026-10-08.md` (the options compared), and the re-dev reviews of 308, 309, 312, R266, R291,
R376 and R379 (each spec's last sections hold the details and the owner's decisions).

## What the evidence says

- **Most plays never transcode.** Android TVs direct-play 94 % (733 of 776), phones 86 %, starting in 1–2 s.
- **A transcode is where viewers feel it.** Jellyfin's ffmpeg needs a median 8.5 s (p90 16 s) to its first frame, then
  runs 4–11× realtime. Every rung switch and every seek is a new cold job. Every job probes with
  `-analyzeduration 200M -probesize 1G` (4.6 s on a cold disk for a 4K REMUX with image subtitles in one measurement).
- **The Chromecast is the transcode-heavy device, mostly because of audio.** 80 % of films and 57 % of episodes
  transcode when cast; 75 % of films have no AAC/MP3/Opus/FLAC track at all.
- **On the TVs**, only 12 % of films and 1.7 % of episodes force a picture re-encode: Dolby Vision 7 with its enhancement
  layer, VC-1/MPEG-2, or a bitrate above the decoder's ceiling.
- **Bugs make it worse:** player guesses stored as measurements (34 rows at 4 300 000, 11 at 3 200 000) capped a phone at
  2.37 Mbps; the progress writer died for 7 hours (310); a stray stop at 0 wiped 24 places in four days (312); the
  receiver probably never used Shaka, so 308's Shaka settings never applied.
- **Every transcode is H.264 SDR** (`AllowHevcEncoding=false`), so HDR TVs see tone-mapped SDR.

## The order (owner, 2026-10-08)

| Step | What | Where it is spec'd | Status |
|---|---|---|---|
| 1 | **Hotfixes, backend only, every installed app gains:** FR-309-13 (a guess is never a measurement) and no cap from a guess on a client that cannot adapt; the receiver's own settings (`useShakaForHls`, start time, sample count) | 309a0 | spec'd |
| 1 | **310** — the progress writer survives; Jellyfin is told the start position | 310 | spec'd, reviewed |
| 1 | **312** — find the stray stop (log the caller + one test play), repair the 24 places | 312 | spec'd, reviewed |
| 2 | **R266** — casts to a TV running Ravilo play in the Ravilo app (direct play, 1.7 s starts on Stue TV); rebase the branch; the phone's remote complete before release; **a TV music mode first** | R266 + a new TV music-mode spec | branch exists; music mode to spec |
| 3 | **Fix files at the source, opt-in per kind:** a compatible audio track (E-AC3/AAC) beside TrueHD/DTS-only audio; Dolby Vision 7 → 8.1. Dry run first; originals stay in the file | new spec | to spec |
| 4 | **Cut the cold start of Jellyfin's own transcodes** (no encoder of our own — owner): 309's early encode on a detail page and the warm rung below, with the encode budget and the leave signal first; find out whether Jellyfin's per-job probe can be smaller | 309a–c + a probe investigation | spec'd (309); probe to spec |
| 5 | **HEVC transcodes** for devices that decode HEVC over HLS, H.264 as the fallback, after measuring | new spec | to spec |
| later | Pre-encoding likely titles (≈ 1.5–2.5 TB for the 4K titles); only if steps 1–5 leave a gap | — | not planned |

Alongside, small and independent:
- **The sub-0.5 s stall at the start of direct plays** (170 of Stue TV's 265 direct plays): its own small phase. Only
  stalls of ≥ 2 s, or two within a minute, lower a device's record (309).
- **R379:** phones without a platform AC3 decoder get an audio-only server transcode for AC3/E-AC3 sources.
- **R376:** direct play first on the web, HLS only on an audio switch; merge after a rebase keeping 308's hls.js
  settings and a Safari check on the Mac.

## Declined

- **A jellystructure-owned video encoder** (one ffmpeg per play, every rung from one decode). The approach report and
  the 309 re-review recommended it as the end state; the owner chose to stay on Jellyfin's transcoder. If steps 1–5 leave
  transcode starts or rung switches noticeably slow, this is the option to revisit.

## Side findings to file

- The Mac app transcodes every play (12 of 12); the web start timer reports 1 s for everything.
- A paused Chromecast session from 2026-10-05 never ends: each repeated "offline" event resets its 24 h timer.
- R266's branch migration 69 collides with 307's; renumber with 309's and 310's.

## Open measurements

- How many files are Dolby Vision 7 (and with an enhancement layer), per library.
- The household's upload capacity (remote viewers).
- NVENC HEVC throughput on the card, and Jellyfin's HEVC HLS on a BRAVIA.
- AV1 decode on the BRAVIAs and the Chromecasts.
