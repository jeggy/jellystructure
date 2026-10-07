# Phase 313 — Our own encoder makes every quality at once

> Owner, 2026-10-08, after the streaming re-review: *"Then let's change and use our own encoder if that makes the
> experience better."* (This reverses the same day's "stay on Jellyfin". Plan:
> `specs/research-reports/ravilo-streaming-plan-2026-10-08.md`, step 4.)

## Status

`Planned` — written 2026-10-08 (dev-authored), against `main` `c9883354`. Not dev-reviewed, not built. Backend (a new
encoder beside R291's rendition jobs, the composed master, `PlaybackService`), the Docker image and compose files (GPU
and ffmpeg), the admin's *Playing now*. No app release is needed for the backend half: every adaptive player already
reads a composed master (308). Builds on **308** (the ladder table, the composed master, the players' ABR), **309**
(the per-device record, the start rung, the prewarm and its leave signal), **R291** (our own ffmpeg runner and audio
renditions), **180** (teardown), **310/312** (the stop path).

## What happens today

From `specs/research-reports/ravilo-streaming-evidence-2026-10-08.md` and `…-approach-2026-10-08.md`:

- **Most plays never transcode** (94 % of Android TV plays, 86 % of phone plays, 1–2 s to the first frame). The rest
  are where viewers feel it.
- **A transcode is a Jellyfin job, one per ladder rung.** Its ffmpeg needs a **median 8.5 s (p90 16 s)** to its first
  frame and then runs 4–11× realtime. Phone transcodes start in a median 13 s. Each job probes with
  `-analyzeduration 200M -probesize 1G` (4.6 s on a cold disk for a 4K REMUX with image subtitles, 78 ms with small
  limits), and each decodes the 4K source on its own.
- **Every rung switch and every seek is a new cold job.** 25 of the 57 jobs in the logs were restarts at a seek or
  resume position. A step up stalled 2.7 s on Stue TV's Chromecast; a remote receiver stalled 29 s.
- **Every transcode is H.264 SDR** (`AllowHevcEncoding=false`): HDR TVs see tone-mapped SDR.
- **The backend container has no GPU** (`DeviceRequests: null`, 64 MB `/dev/shm`). Its ffmpeg is Debian's **5.1**:
  `h264_nvenc`, `hevc_nvenc`, `scale_cuda`, `overlay_cuda` and `libplacebo` are there, `tonemap_cuda` is not. Jellyfin's
  container has both cards and jellyfin-ffmpeg **8.1.2**.
- The cards: **Quadro P4000** (no NVENC session cap) and **RTX 2060 SUPER** (consumer cap, 8 sessions on current
  drivers). At most 4 concurrent video encodes have been seen.
- **R291's runner already works:** our own ffmpeg per audio rendition, segments made on request, paused (SIGSTOP)
  when ahead, restarted on a seek, first segment in 0.6–0.8 s, served at `/api/tv/stream/{id}`.

## Principles

1. **One play, one encoder process.** The source is read and decoded once; every rung comes from that decode.
2. **Every rung exists at every segment boundary**, keyframe-aligned, so a player switches up or down without waiting.
3. **We already know the file.** Our own scan's tracks replace Jellyfin's 1 GB probe.
4. **Jellyfin's transcode stays as the fallback**, chosen per play by rule, never by guessing after a failure.
5. **Nothing changes for the viewer except speed and picture.** No setting, no quality menu (308 FR-308-6).

## Requirements

### FR-313-1 — The job

- One **encoder job per play** (device × item × chosen audio/subtitle), owned by the backend.
- **Started** by Play, or earlier by 309's detail-page prewarm (> 2 s on a detail page, or a cast sheet with a
  receiver chosen). The prewarm's job is **adopted** by Play when the play matches it (same device, item, start
  position within one segment, same audio/subtitle). Otherwise it is stopped and a new one started.
- **Stopped** by 180's teardown (the play's stop, a superseding start, the watchdog), by 309's leave signal (the viewer
  left the detail page without pressing Play), or after **60 s without a segment request** (idle kill, as R291's
  `IDLE_MS`).
- **Paused** (SIGSTOP) once it is **40 s** ahead of the furthest segment any rung has been asked for; resumed when
  requests come within 20 s. A two-hour film is never encoded ahead for nobody.
- One job serves **all** its rungs. A rung no player reads costs only its encoder slice, never a second decode or disk
  read.

### FR-313-2 — The input

- The source is the file on **this server's disk** (R291's `localFileOf`). A file the backend cannot read falls back to
  Jellyfin (FR-313-12).
- **No 1 GB probe.** The job maps streams by index from our scan's `Track` rows (`-map 0:v:0`, the chosen audio, the
  chosen subtitle) and opens with small probe limits (`-probesize 5M -analyzeduration 5M`; measured at 78 ms against
  4.6 s). A burned-in image subtitle needs its frame size: it comes from the scan, never from probing.
- A source our scan has not described (no tracks yet) falls back to Jellyfin.

### FR-313-3 — The ladder

- 308's table stays the one table (`VideoLadder.kt`): top, 1080p 12, 1080p 8, 720p 4, 480p 1.5 Mbps; rungs above the
  device's decode ceiling or not clearly below the one above are left out.
- **The top rung is capped by its own output resolution**, not the source's bitrate: about 20 Mbps for 2160p HEVC,
  12 Mbps for 1080p H.264, 8 Mbps for 1080p HEVC (one table next to the ladder). The 2026-10-06 cast's 24.4 Mbps 1080p
  H.264 top rung is the case this removes.
- **309 decides which rungs a play gets and where it starts**: the per-device record, FR-309-13's real measurements,
  the start rung. The encoder makes exactly the rungs listed in the master, at least the start rung, the one below and
  the one above, and up to **4 rungs** per play (FR-313-8's budget may lower that).

### FR-313-4 — Codec per device

- **HEVC rungs (10-bit, HDR kept)** for a device that decodes HEVC over HLS and declares the source's HDR form
  (`supports_hdr10`, `supports_hlg`; Dolby Vision is sent as its HDR10 base layer). The BRAVIAs and the Pixel qualify;
  the Chromecast HD and the web in most browsers do not.
- **H.264 SDR rungs** for every other device, tone-mapped on the GPU when the source is HDR (`libplacebo`, or
  jellyfin-ffmpeg's `tonemap_cuda`, FR-313-11). The tone-map runs **once**, before the split into rungs.
- A device gets **one codec family** per play: the master lists either HEVC rungs or H.264 rungs, never a mix, so a
  player can't switch codec mid-play.
- **Dolby Vision 7 with an enhancement layer** plays as its HDR10 base layer (the enhancement layer is dropped).
  Profile 5 (no HDR10 base) is tone-mapped by `libplacebo`'s Dolby Vision support, or falls back to Jellyfin when the
  image's ffmpeg can't.
- HDR10+ dynamic metadata is not carried into the rungs (static HDR10 only); stated, not hidden.

### FR-313-5 — Audio

- Audio comes from the **same process** as R291's renditions, not from a second job. Every audio track the master
  lists is one `-map` in the encoder job (AAC stereo/5.1, or AC-3/E-AC-3 where the device decodes it), on the same
  segment timeline as the video.
- Switching audio stays instant (R291): the player picks another rendition in the master; nothing restarts.
- R291's separate audio jobs remain for direct-play files that only need a rendition, and for the Jellyfin fallback.

### FR-313-6 — Subtitles

- **Text subtitles** (SRT, ASS, WebVTT, `mov_text`) are served as **WebVTT** renditions in the master (converted once
  per file and cached), never burned in.
- **Image subtitles** (PGS, VobSub) are burned in only when the viewer picks one (`overlay_cuda` on the GPU, the
  subtitle's frame size from the scan).
- **A burn-in switch** (turning an image subtitle on, off or to another track) **replaces** the job: a new job starts at
  the current segment with the new overlay, the player is handed the new master by R284's restream at the same
  position, and the old job is stopped once the new one has served its first segment. No other switch restarts the
  job.

### FR-313-7 — Segments, seek and the master

- **Segments are 2 s fMP4 (CMAF)**, one init segment per rung, keyframes forced at every segment boundary
  (`-force_key_frames "expr:gte(t,n_forced*2)"`, scene-cut off, fixed GOP), the same boundaries for every rung and for
  audio.
- **Seek:** a request for a segment the job cannot reach within **20 s** of what it has made (forward) or before what it
  has kept (backward) **restarts the job at that segment's keyframe-aligned start**. Segments already made are kept for
  as long as they fit FR-313-9's cap, so seeking back into them needs no encode.
- **The master is ours** (308's `composeLadderMaster`, extended): every rung's `BANDWIDTH`, `RESOLUTION` and `CODECS`
  from the encoder's own settings, the audio and WebVTT groups, the start rung first (309).
- Segments and playlists are served from `/api/tv/stream/{id}/…` as R291's are. The URL carries no credential.

### FR-313-8 — The encode budget, across viewers

- The backend counts its own encoder sessions per card. The **P4000 carries ladder jobs** (no session cap). The
  2060 SUPER is used only when the P4000 is full, and only up to **6 of its 8 sessions** (Jellyfin's own jobs and its
  trickplay also use that card).
- A job needs one NVENC session per rung and one NVDEC session. When a new play doesn't fit, it gets **fewer rungs**
  (the start rung, then the one below). Only when even one rung won't fit does the play fall back to Jellyfin.
- A prewarm (309) never takes a slot a real play needs: a prewarm job is the first stopped when slots run out.
- `/api/health` gains an `encoder` block: sessions per card, jobs, rungs, paused, fallbacks in the last hour.

### FR-313-9 — Disk

- Segments go to a tmpfs: the backend container gets `shm_size` raised (2 GB) or a dedicated tmpfs mount at
  `/transcode/js` (≈ 1 GB per 4K play at 3–4 rungs over FR-313-1's 40 s ahead plus what's kept behind).
- A total cap (default 4 GB, in config). Over the cap, the oldest kept-behind segments of the least recently read rung
  go first. Never the segments ahead of a playhead.
- Everything a job wrote is deleted when it stops; a sweep at start deletes leftovers from a crash.

### FR-313-10 — The Jellyfin side

- **No Jellyfin transcode job and no Jellyfin stream URL** for a play our encoder serves. So no `PlaySessionId`
  variants, no Jellyfin ping timer, and no second session that could send 312's stray stop.
- Watch state still goes through `/Sessions/Playing`, `/Progress` and `/Stopped` (310's writer), with the same
  identity and `PlayMethod: Transcode`. Jellyfin's dashboard then shows the play as playing, but not as one of its own
  transcodes.
- Jellyfin's `PlaybackInfo` is still asked once per play for what it is good at: whether the device can direct-play
  (183/306/309's gate). Our encoder is used only where the answer is "transcode".

### FR-313-11 — The image and the container

- The backend image ships **jellyfin-ffmpeg** (the same build Jellyfin uses, for `tonemap_cuda`, `overlay_cuda`,
  current NVENC/NVDEC and Dolby Vision handling) in place of Debian's ffmpeg 5.1, or beside it for the encoder only.
- The compose files pass the GPU to the backend (`deploy.resources.reservations.devices: nvidia, capabilities: [gpu,
  video, compute]`), as Jellyfin's already is.
- The backend runs without a GPU too: then every transcode falls back to Jellyfin, and `/api/health` says so.

### FR-313-12 — When Jellyfin does it instead

Decided per play, before the job starts, and logged with the reason (`encoder: fallback to Jellyfin — <reason>`):
no GPU or no slot (FR-313-8), the file isn't on our disk or not scanned, a video codec the image can't decode on the GPU
(VC-1, MPEG-2 interlaced — measured in 313a), Dolby Vision profile 5 when `libplacebo` can't tone-map it, a live TV
channel, and a music or audiobook stream (never this encoder). A job that fails at start (no first segment within
5 s) falls back once, and records why.

### FR-313-13 — Measured, and visible

- QoE gains `encoder` (`ours` / `jellyfin` / `direct`), the time from Play to the first segment served, and the rung
  count. The admin's *Playing now* shows *our encoder · 4 qualities · HEVC HDR* or *Jellyfin* beside 308's current
  variant.
- The backend logs one line per job: start (source, rungs, codec, card, start segment, why), the first segment's time,
  every restart (seek / burn-in switch) and the stop.

### FR-313-14 — What changes in 309

- **Dropped once 313e makes this the default:** FR-309-6's warm rungs (every rung is already running in the one job)
  and FR-309-7's decision cache (the start is ~1 s).
- **Relaxed:** FR-309-4's climb rule falls back to the players' stock values (Media3's 10 s, Shaka's stock switch
  interval), since a climb never waits for an encode.
- **Unchanged:** the per-device record, FR-309-13, the speed test, the start rung, the early step-down (the receiver
  enforcing it over Shaka), the direct-play gate, the stall rule, the leave signal (it now stops our job), mpv/AVPlayer.

## Non-goals

- Pre-encoding titles ahead of time (the plan's "later").
- Music and audiobooks (direct play / R291 as today).
- Live TV (Jellyfin's path).
- HDR10+ dynamic metadata and Dolby Vision RPU in the rungs.
- Replacing direct play: a file the device can play untouched is still played untouched.

## Acceptance

1. A transcoded 4K HDR film on Stue TV (HEVC HDR rungs) and on the Pixel: **first frame ≤ 1.5 s after Play**, or
   ≤ 0.5 s when 309's prewarm ran.
2. The same film on a Chromecast (H.264 SDR rungs, tone-mapped): first frame ≤ 2 s; a throttle from 50 to 5 Mbps
   mid-film steps down with **0 stalls**, and back up when lifted, with **0 stalls**.
3. A 15-minute forward seek resumes in **≤ 2 s**; seeking back into already-made segments resumes in ≤ 0.5 s.
4. Turning a PGS subtitle on mid-film: picture with the subtitle within 2 s, no stall after.
5. Three viewers transcoding at once: no fallback to Jellyfin while the P4000 has sessions; `/api/health` shows the
   sessions and rungs.
6. Jellyfin's log shows one start and one stop per play, and no transcode job, for every play our encoder serves.
7. With the GPU removed from the backend's compose file, every transcode still plays, through Jellyfin.

## Tests

1. The command builder: rungs, codec, keyframe forcing, segment length, maps, probe limits, tone-map once before the
   split, burn-in overlay, per device kind (BRAVIA HEVC HDR, Pixel HEVC, Chromecast H.264 SDR, web H.264).
2. The top-rung cap by output resolution; the rung list from 309's record and FR-313-8's budget.
3. The master: one codec family, the start rung first, `CODECS`/`RESOLUTION`/`BANDWIDTH` per rung, audio and WebVTT
   groups.
4. The job lifecycle with a fake process: start, adopt a prewarm, pause ahead, resume, seek restart, keep-behind,
   idle kill, stop on teardown, stop on the leave signal, the crash sweep.
5. The budget: sessions per card, fewer rungs before fallback, a prewarm stopped first.
6. The fallback rules, each with its logged reason.
7. The disk cap: eviction order, never a segment ahead of a playhead.
8. Jellyfin side: one `/Sessions/Playing` start, progress and stop per play, no Jellyfin stream URL handed out.
9. An integration test in the e2e stack (a short HEVC HDR fixture, CPU encode in CI): segments of every rung align at
   every boundary (same PTS), a switch mid-play decodes without a gap.

## Open questions (dev)

1. **Measure first (313a):** NVENC throughput on the P4000 for one 4K HDR source → 3–4 rungs (HEVC and H.264),
   `realtime ×` and first-segment time. This decides FR-313-3's rung count per play and whether the 2060 SUPER is
   needed at all.
2. The 2060 SUPER's session cap on the installed driver (8 assumed), and whether Jellyfin's `cu:0` is the 2060 SUPER as
   the evidence report suggests.
3. **Chromecast:** does the household's Chromecast (CAF, Shaka or MPL — see the R266 review's `useShakaForHls`) play
   fMP4/CMAF HLS and HEVC rungs? If not, it gets TS segments and H.264 only; measure on the device.
4. Licensing and size of shipping jellyfin-ffmpeg in our image (GPL build; the image is public on GHCR).
5. Whether `libplacebo` in the image tone-maps Dolby Vision profile 5 correctly, or profile 5 always falls back.
6. 2 s segments vs 3 s: Media3 and Shaka handle 2 s fine; confirm the request rate through Caddy is no issue.

## Build order

- **313a — measure and plumb:** the GPU in the backend container, jellyfin-ffmpeg in the image, the P4000 measurements
  (open question 1), the health block. No player sees anything yet.
- **313b — H.264 ladder:** the job, the input, the ladder, segments, seek, the master, the budget, disk, the Jellyfin
  side, the fallback; behind a config switch, on for the dev stack.
- **313c — HEVC and HDR:** HEVC rungs for HEVC devices, the tone-map once for H.264 rungs, Dolby Vision base layers.
- **313d — subtitles:** WebVTT renditions and the burn-in switch.
- **313e — make it the default:** on for every transcode; 309's FR-309-6/-7 removed; the Jellyfin path stays as the
  fallback.
