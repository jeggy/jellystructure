# Ravilo streaming: the best way to never buffer (approach review)

> Research report, 2026-10-08 (dev). Owner's goal: *"all users never feel any buffering or lagging when streaming on
> Android, TV or Chromecast etc."*, and *"make sure we are suggesting the best approach, and if not, suggest a better
> approach"*. It weighs the built 308 and the planned 309 against alternatives, using evidence from the code, the
> live logs, the library and the libraries' own sources. Read-only: nothing was changed or restarted. Titles are left out.

## 1. What the evidence says first

**Most plays never encode at all.** Since the backend restart (2026-10-07 00:28) it negotiated **65 video plays: 62
direct plays, 3 transcodes**. Jellyfin's transcode logs since 2026-10-05 hold 57 video transcodes, and 53 of them are
the 308 device tests on two titles. In normal use the household direct-plays almost everything.

**The library is mostly easy.** It has 9 732 video files (from jellystructure's own scanned tracks):

| Trait | Files | Share | Effect |
|---|---|---|---|
| H.264 / HEVC | 9 273 | 95 % | plays directly on every Ravilo device |
| SDR | 9 511 | 97.7 % | no tone mapping |
| HDR (incl. Dolby Vision) | 221 | 2.3 % | a transcode on SDR-only screens and on DV 7 sources |
| 4K (height ≥ 1500) | 303 | 3.1 % | heavy for remote viewers and the BRAVIA's decoder |
| Video above 20 / 40 / 60 Mbps | 169 / 59 / 16 | 1.7 / 0.6 / 0.2 % | above remote links / above the BRAVIA's 60 Mbps |
| AV1 | 282 | 2.9 % | a transcode on devices without an AV1 decoder |
| Audio **only** TrueHD / DTS | 444 | 4.6 % | an audio transcode on phones, Chromecast, the web |
| Image subtitles (PGS / VobSub) | 1 177 | 12.1 % | a burn-in when one is chosen; slow probing (below) |

So the buffering the household feels comes from **a small set of files** (4K / HDR / DV 7 / AV1 / lossless-only audio)
and from **viewers on a thin link** (meidam, ~15–19 Mbps). A play is slow in one of three ways:

1. **Cold start.** A Jellyfin transcode measured 8–38 s from Play to picture (30 days of `playback_start_sample`).
   Direct plays take 1–2 s.
2. **A step to a new rung** in 308 is a new cold Jellyfin job. Stue TV's Chromecast stalled 2.7 s on one, and meidam's
   receiver jumped 4 → 24 Mbps and stalled 29 s.
3. **A seek forward** past the encoded range restarts the encode cold.

**Where the cold start goes (measured today).** Jellyfin starts every transcode with
`-analyzeduration 200M -probesize 1G` (its defaults for `FFmpeg:analyzeduration` / `FFmpeg:probesize`). On a 4K REMUX
with three PGS tracks, `ffprobe` with those values took **4.6 s on a cold disk** (574 ms warm), against **78 ms** with
`10M / 50M`. ffmpeg keeps reading until it has seen a packet of every stream, and sparse image-subtitle streams make it
read far. Jellyfin pays this **for every job**: each rung in 308, each seek. The low limits lose the PGS track's frame
size, which Jellyfin wants when it burns subtitles in. Jellyfin also extracts subtitles at the start
(`SubtitleEncoder`, seen at 2026-10-07 17:32:03).

## 2. The options

### A. Keep 308/309: one on-demand Jellyfin job per rung

- **For:** built and device-tested. It uses Jellyfin's mature HDR/DV/subtitle handling and plays on every device.
- **Against:**
  - Every rung is its own job: its own probe, its own **decode of the source** (a 4K 80 Mbps HEVC decode per rung) and
    its own disk read.
  - Warming the rungs (309 FR-309-6) multiplies the heaviest part, NVDEC plus the disk. Four rungs of an 80 Mbps file
    read 320 Mbps, which is the disk pressure behind the 2026-09-15 stall incident.
  - A climb onto a rung nobody warmed is a cold start. Each seek restarts each live rung, each with the 1 GB probe.
  - Jellyfin's own ABR stays unusable (308 build notes).
- **Verdict:** right for now, because it's built. As the end state it caps how smooth a switch or a seek can be, and
  309's warming makes it costly.

### B. One encode, every rung: our own ffmpeg producing the whole ladder

One ffmpeg per play: decode once on the GPU, `split` into each rung (`scale_cuda`, tone-map once), encode each with
NVENC under the **same forced keyframes** (`-force_key_frames "expr:gte(t,n_forced*2)"`, `-no-scenecut 1`, fixed GOP).
It writes **2 s CMAF/fMP4** segments per rung with `-var_stream_map` and a master it composes itself. Audio stays R291's
shared renditions.

- **For:**
  - Every rung exists at every segment, so **a switch never waits**, up or down.
  - One decode and one disk read per play.
  - A seek restarts **one** job, and probing is ours: we already know every stream from our own scan (`Track` rows), so
    we pass `-map` and a tiny probe (78 ms instead of 0.6–4.6 s).
  - We control segment length, keyframes and pacing.
  - **The pieces exist:** R291's `AudioRenditionJobs` already runs our own ffmpeg per stream. It makes segments on
    request, pauses a job that runs ahead (SIGSTOP), restarts on a seek, and serves segments through
    `/api/tv/stream/{id}`. 308's master composition and every player's ABR stay as they are.
- **Against / cost:**
  - **The backend container has no GPU today** (`DeviceRequests: null`; Jellyfin's has both cards). Its ffmpeg is
    Debian's 5.1 with `h264_nvenc` but no `tonemap_cuda`. We'd need the GPU in the compose file and jellyfin-ffmpeg (or
    `libplacebo`) for HDR → SDR.
  - We take on Jellyfin's edge cases for these plays: Dolby Vision (decode the base layer), HDR10+ metadata, PGS
    burn-in (`overlay_cuda`), odd sources (VC-1, MPEG-2, interlaced).
  - NVENC sessions: one per rung. The **Quadro P4000 has no session cap**; the consumer RTX 2060 SUPER does (8 on
    current drivers), so ladder jobs belong on the P4000.
  - Jellyfin's dashboard no longer shows these as transcodes. The watch state still reaches Jellyfin through our
    `/Sessions/Playing*` reports.
- **Risk:** medium. Mitigated by keeping Jellyfin's path as the fallback for anything our builder doesn't handle
  (declared per source, not guessed).

### C. Prepare ahead of time ("optimized versions")

Pre-encode, on idle GPUs, a compatible version (one 1080p 8 Mbps H.264 SDR file, or the whole ladder) for titles
likely to be watched next.

- **For:** zero encode at Play, so the start is as fast as a direct play, even remotely.
- **Against:**
  - **Disk:** ~3.6 GB per hour at 8 Mbps, ~6 GB per hour for a 3-rung ladder. Covering only the 303 4K files is about
    1.5–2.5 TB.
  - It only helps titles chosen in advance; a spontaneous pick still needs A or B.
  - The owner's rule "the file is the record" means these belong in a cache directory, never in the library.
- **Verdict:** a later, targeted add-on with a disk budget. For example: new 4K/HDR/AV1 arrivals that some household
  device can't play directly, Continue Watching's next episodes, and the titles a remote viewer has open. Not the
  foundation.

### D. Avoid transcodes at the source

At ingest (jellystructure already remuxes, re-flags and re-tags files):

| Fix | Files | Effect |
|---|---|---|
| Add an E-AC3 5.1 (or AC3) track beside TrueHD / DTS, original kept | 444 | phones, Chromecast and the web direct-play the picture with a compatible track, so no audio transcode |
| Dolby Vision 7 (+EL) → **8.1** remux (`dovi_tool`: base layer + RPU, EL dropped) | a subset of the 221 HDR | direct play as DV on the BRAVIA and the 4K Chromecast, as HDR10 elsewhere; no tone-map transcode |
| PGS: nothing (OCR is unreliable); text tracks are preferred when chosen (R180/R195) | — | — |
| AV1: nothing at the source (that would be a re-encode; C or B covers it) | — | — |

- **For:** instant start on every device for these files, no GPU at play time, and a one-time cost (an audio encode is
  fast; a DV remux is a copy).
- **Against:**
  - It rewrites files: owner approval per kind of change, the copy-write-verify-swap discipline (284), and 311's
    rule that work files stay invisible to Radarr/Sonarr.
  - A DV 7 → 8.1 conversion drops the enhancement layer: a small, real quality loss on a 12-bit FEL disc.
  - It doesn't help remote viewers with big files.
- **Verdict:** a high-value, low-risk win for the household's own devices. Do it opt-in, kind by kind.

### E. Player-side

- **Segment length:** **2 s** fMP4 instead of 3 s TS. A smaller first download, faster switches and seeks. The
  per-request overhead is negligible over HTTP/2.
- **Start:** Media3's `bufferForPlaybackMs` is 1 s by default (we keep it; R216 raised only the after-stall cushion).
  Start on a rung the device has shown it can carry (309 FR-309-1/-2). Download is rarely the slow part; the encode
  start is.
- **Climb:** with B, a climb never waits for an encode, so 309's "≥ 30 s buffered **and** the rung already warm" can
  relax to Media3's stock 10 s and Shaka's stock `switchInterval` 8 s. Better pictures sooner, still safe.
- **Down:** keep 309 FR-309-5. It matters most on the **Chromecast**: Shaka's ABR is throughput-only and did not step
  down at 24.6 Mbps on a 15.4 Mbps estimate.
- **Next episode:** start the next episode's job when the credits marker fires (R182/R347 already know it), and
  preload it in Media3, so the next episode starts instantly.
- **Chromecast limits:** the HD model has no Dolby Vision and is 1080p, so every 4K HDR title transcodes for it. That
  device class benefits most from B, C and D. Shaka lowers its buffer goal itself on a quota error; keep
  `bufferingGoal` 30–40 s.
- **BRAVIA:** 60 Mbps decode ceiling, native DV 8. D removes most of its transcodes.

### F. Other

- **Upload:** for remote viewers the household's upstream is the real ceiling, and it's unmeasured. The ladder and
  309's per-device record handle it, but measure it once, so the top rung for remote plays isn't a guess.
- **Delivery:** segments from tmpfs (`/dev/shm`, as Jellyfin's are), no compression on media (Caddy), HTTP/2.
  Low-latency HLS isn't needed (VOD).
- **Jellyfin's probe:** if A stays for some plays, `JELLYFIN_FFmpeg__probesize` / `__analyzeduration` can lower the
  4.6 s cold probe. It trades off PGS size detection for burn-in, so measure before changing it on prod.

## 3. Recommendation

**Do D for the household's own devices, B as the engine for every play that must re-encode, and C later for the few
titles where even B's start is too slow.** Keep 309's per-device policy (record, probe, start where the device is
proven, step down early, no slow-start note) on top of B.

| Part of 308/309 | Keep / change / drop |
|---|---|
| 308 composed master, players' own ABR, QoE with variant switches | **Keep** |
| 308 one Jellyfin job per rung | **Replace** with B; Jellyfin stays as the fallback encoder |
| 309 FR-309-1/-2 per-device record and start rung; FR-309-13 a guess is never a measurement | **Keep** (do first) |
| 309 FR-309-3 speed test before Play | **Keep** |
| 309 FR-309-4 climb rule (≥ 30 s and a warm rung) | **Change** under B: stock 10 s, one rung at a time |
| 309 FR-309-5 step down early, the receiver enforcing it over Shaka | **Keep** (do first) |
| 309 FR-309-6 warm the rung above/below | **Drop** under B (every rung is always there); until B, warm only the rung below |
| 309 prewarm on a detail page open > 2 s, including the cast receiver (owner) | **Keep**: under B it starts our one job at the resume point |
| 309 FR-309-7 decision cache | **Drop** (already the review's lean) |
| 309 FR-309-8 direct play only when proven (≤ 8 Mbps or tested) | **Keep** |
| 309 FR-309-9 mpv / AVPlayer rules | **Keep** |
| 309 FR-309-10 retire the slow-start note | **Keep** |

## 4. Phased plan

1. **Now (no new subsystem):**
   - FR-309-13 and the measurement record (the Pixel's 2.37 Mbps case).
   - The receiver's step-down rule (meidam's 29 s).
   - Prewarm the start rung on a detail page open > 2 s.
   - The slow-start note retired.
   - 310 and 312 (correctness of watch state).
   - Expect: a known device starts in seconds, and no stall from a stuck top rung.
2. **D, opt-in per kind:** E-AC3 beside lossless-only audio (444 files), then DV 7 → 8.1 (count the DV 7 files first).
   Measure the household's transcode rate before and after.
3. **B, the ladder engine:**
   - GPU into the backend container (the P4000), jellyfin-ffmpeg in its image.
   - Build on `AudioRenditionJobs`: one job per play, all rungs, 2 s CMAF, keyframes aligned, a probe from our own
     scan, tone mapping and PGS burn-in on the GPU.
   - Jellyfin's per-rung path stays as the fallback, declared per source kind.
   - Then relax 309's climb rule and drop the warm rungs.
4. **C, targeted pre-encodes with a disk budget:** new 4K/HDR/AV1 arrivals no household device can play directly,
   next episodes, and a remote viewer's open title. Plus the next-episode job started at the credits.

## 5. Open points

- Count DV profile 7 files (our scan stores `videoRange`, not the DV profile) before sizing D's second half.
- Measure the household's upload once, for the remote top rung.
- B's NVENC throughput on the P4000 with 3–4 rungs of a 4K HDR source: measure before deciding how many rungs a
  remote play gets.
- Whether the BRAVIA and the Chromecast models in the house decode AV1 (it decides how much C is worth for the
  282 AV1 files).
