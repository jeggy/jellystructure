# Phase 313 — Our own encoder makes every quality at once

> Owner, 2026-10-08, after the streaming re-review: *"Then let's change and use our own encoder if that makes the
> experience better."* (This reverses the same day's "stay on Jellyfin". Plan:
> `specs/research-reports/ravilo-streaming-plan-2026-10-08.md`, step 4.)

## Status

`⚠ Partial` — **built 2026-10-08 on worktree branches (313a measured, 313b–313e built; the encoder is now on by default), not deployed, no device has played through it yet** (see Build notes and *Build notes — 313d / 313e*). Written 2026-10-08 (dev-authored), against `main` `c9883354`. **Dev-reviewed 2026-10-08** (see the end). Backend (a new
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

## Dev review (2026-10-08, against `main` `fdd48f10`)

Read against `AudioRenditionJobs.kt`, `AudioRenditions.kt`, `VideoLadder.kt`, `PlaybackService` (`withRenditions`,
`releaseEncodes`, `TICKET_TTL_MS`), `TvRoutes.kt` (`/tv/stream/{id}/…`), `AuthPlugin.kt`, the `Dockerfile`, both
compose files in the deployment and Jellyfin's, and on the host: `nvidia-smi`, the NVIDIA container toolkit, the
`/dev/nvidia*` nodes, `/dev/shm`, and the jellyfin-ffmpeg build in Jellyfin's container (filters, encoders, build
configuration). **Not measured:** NVENC throughput (open question 1). A household play started two minutes before the
measurement would have run, so it stays 313a's first step. The design holds; fifteen items, one for the owner.

1. **The GPU can reach the backend container, as Jellyfin's does.** Driver 550.163.01, NVIDIA container toolkit 1.20,
   default runtime `runc`; Jellyfin gets both cards through a `DeviceRequests` entry (`driver: nvidia`, all devices,
   `capabilities: [gpu]`) plus `NVIDIA_VISIBLE_DEVICES=all` / `NVIDIA_DRIVER_CAPABILITIES=all`. The device nodes are
   `0666`, so the backend's uid 1000 needs no extra group. FR-313-11: mirror that stanza, with
   `NVIDIA_DRIVER_CAPABILITIES=compute,video,utility`. **Put it in a separate override file** (e.g.
   `docker-compose.gpu.yml`): the public compose must still start on a machine without an NVIDIA card (the repo is
   public), and FR-313-11's "runs without a GPU" then holds by construction.

2. **Which card is which.** `nvidia-smi` index 0 is the P4000 (bus 08:00.0), index 1 the RTX 2060 SUPER (42:00.0). CUDA
   numbers devices fastest first by default, so Jellyfin's `-init_hw_device cuda=cu:0` is almost certainly the 2060
   SUPER (consistent with 2026-10-05, when `nvidia-smi` showed Jellyfin's ffmpeg on GPU 1). Our encoder must set
   `CUDA_DEVICE_ORDER=PCI_BUS_ID` (or address the card by UUID) and choose the P4000 deliberately.

3. **Count sessions from the card, not only our own.** FR-313-8 counts "its own encoder sessions per card", but
   Jellyfin's jobs and trickplay share the 2060 SUPER. Read the live counts from NVML at each start
   (`nvidia-smi --query-gpu=encoder.stats.sessionCount`, or the library). A paused (SIGSTOP) job **keeps** its NVENC
   sessions, so paused jobs count as holding them until they are stopped.

4. **The P4000 is Pascal.** It has one NVENC engine, HEVC Main 10 encode but no HEVC B-frames, and lower quality per
   bit than the Turing 2060 SUPER. A four-rung 4K HEVC job may not reach the ≥ 1.5× realtime the targets need. 313a
   decides: H.264 ladders on the P4000; a 4K HEVC top rung on the 2060 SUPER (within the 6-session limit) if the P4000
   can't keep up. One job stays on one card (no frame copies between cards).

5. **ffmpeg in the image.** The backend image is Debian 12 (bookworm) with Debian's ffmpeg **5.1** (no `tonemap_cuda`).
   The jellyfin-ffmpeg in Jellyfin's container is the **trixie** package `jellyfin-ffmpeg8 8.1.2`, 218 MB, GPL v3, built
   with `libfdk-aac`. Our image is public on GHCR, so avoid redistributing fdk-aac. Lean: **jellyfin-ffmpeg's portable
   GPL build**, pinned by version and SHA-256, in `/usr/lib/jellyfin-ffmpeg`, used by the encoder only. Its built-in
   `aac` encoder replaces fdk (R291 already uses `aac`). Moving the whole image to trixie is the alternative. Debian's
   ffmpeg + `libplacebo` would need Vulkan and the `graphics` capability in the container, so no.

6. **R291's runner is the base, with three differences.** (a) It writes to `/tmp/js-renditions`, the container's
   overlay disk, not a tmpfs. Mount a dedicated tmpfs with a `size=` limit for the encoder (the host's `/dev/shm` is
   63 GB), so FR-313-9's cap is enforced by the kernel too. (b) A segment is read whole into a `ByteArray` and sent with
   `respondBytes`. A 2 s 4K rung is ~5 MB per request, and that churn hits K/N's GC (phase 228). Stream video segments
   from the file in chunks instead. (c) `IDLE_MS` is **90 s**, not 60 (FR-313-1's text). Its segments are 3 s MPEG-TS
   aligned to Jellyfin's; inside a 313 job, audio follows the video's 2 s fMP4 timeline. R291's own jobs (direct-play
   renditions, the Jellyfin fallback) keep theirs. One master never mixes the two timelines.

7. **The stream URL is a bearer, so its id must be unguessable.** `/api/tv/stream/` is a public prefix in
   `AuthPlugin`, and `AudioRenditions.randomId()` uses `kotlin.random.Random`, not a cryptographic generator. Today it
   guards audio segments; with 313 it guards whole films. Take 128 bits from `getrandom`/`/dev/urandom` (313b, which
   fixes R291 at the same time). An id expires with its ticket (4 h); resuming after that re-issues it through the
   normal restream.

8. **"No app release is needed for the backend half" is true only for the Cast receiver and post-308 builds.** Ravilo
   1.50 does not declare `hls_adaptive` (308's re-review), so installed phones and TVs never get a ladder. **But they
   can still get our encoder:** a **one-rung** master (the rung 309's rules pick) gives a non-adaptive client a ~1 s
   start instead of Jellyfin's 8.5 s, with no app change. Media3 1.50 plays a single-variant fMP4 HLS. Add this as an FR
   in 313b: the quickest felt win for every installed app.

9. **What each player needs in the master and the segments.** HEVC in fMP4 must use the `hvc1` sample entry
   (`-tag:v hvc1`) or AVPlayer/Safari refuse it. `CODECS` must be exact (`avc1.6400xx`, `hvc1.2.4.L1xx.B0`, `mp4a.40.2`,
   `ec-3`), computed from each rung's profile and level. Media3 drops a variant whose codec string its decoders don't
   support, so a wrong string silently hides rungs. HDR10 rungs carry `VIDEO-RANGE=PQ` (HLG: `HLG`); every rung carries
   `FRAME-RATE` and `CLOSED-CAPTIONS=NONE`. Tests for each string.

10. **The Chromecast.** CAF's default HLS player assumes MPEG-TS unless the load request sets
    `hlsSegmentFormat`/`hlsVideoSegmentFormat` to FMP4; with 309a0's `useShakaForHls`, Shaka handles fMP4. The encoder
    supports both muxings, one per job: **TS for the receiver** until 309a0 is verified on the device, fMP4 elsewhere.
    HEVC rungs on a receiver only when its own capabilities say HEVC. The BRAVIA's built-in Cast may; a Chromecast HD
    does not.

11. **A restart must line up with what was already made.** After a seek or burn-in switch the new job must produce the
    same init segment and continuous `tfdt`/PTS for its `start_number`: identical encoder settings, `-copyts` and the
    segment's start offset, as R291 does for TS. Kept-behind segments from an earlier job then sit in the same rung, so
    a test decodes segment k from the restarted job right after segment k−1 from the first.

12. **Jobs have a fixed set of rungs.** A running ffmpeg can't gain an output, so a prewarm (309) starts with the full
    rung set the play will use, and adoption (FR-313-1) requires the same set. Starting the full set is cheap: it
    pauses 40 s ahead.

13. **Teardown.** `releaseEncodes` today calls Jellyfin's `stopActiveEncoding` and `audioRenditions.stopFor`. With 313,
    a play has no Jellyfin job, so it stops **our** job by the play's key and skips the Jellyfin call. 312's "release
    the encodes only" path is the same call. PlaybackInfo still mints a `PlaySessionId` (it starts nothing), and the
    Jellyfin session keeps `PlayMethod: Transcode` (FR-313-10).

14. **Tests are feasible as listed.** `AudioRenditionsTest`/`VideoLadderTest` show the pattern for pure builders.
    Lifecycle tests use a fake process like R291's. The e2e alignment test (Test 9) needs libx264/libx265 in the CI image
    (CPU encode, no GPU).

15. **Build order:** 313a also does items 1, 2, 5 and the tmpfs mount; 313b adds item 7 and item 8's one-rung mode
    first, then the ladder.

**For the owner:**

- **Q1 — Installed apps (1.50) and our encoder:** they can't use a ladder until the app update, but a **single fixed
  quality from our encoder** starts in ~1 s instead of ~8.5 s, with the quality chosen from the device's record. Options:
  (a) **yes, as soon as 313b works (lean)**; (b) only after the app update brings the ladder, so installed apps keep
  Jellyfin's single stream until then.

## Decided by the owner (2026-10-08, after the dev review)

1. **Installed 1.50 apps get our encoder as one fixed quality as soon as 313b works** (Q1, the lean): ~1 s starts for
   every installed app before any app release; the ladder follows with the app update.


## Build notes (2026-10-08, worktree branch, not merged or deployed)

### 313a — measured on the household host (nobody watching; Jellyfin's container and its jellyfin-ffmpeg 8.1.2)

Source: a 4K HDR10 HEVC REMUX (3840×2160, ~50 Mbps), 60 s of film from 10:00. Cards: `nvidia-smi` index 0 = Quadro
P4000 (bus 08:00.0), index 1 = RTX 2060 SUPER (42:00.0); with `CUDA_DEVICE_ORDER=PCI_BUS_ID` CUDA's numbering matches.

| Run (one ffmpeg, one decode) | First segment | Speed | Notes |
|---|---|---|---|
| H.264 SDR, 4 rungs (1080p 12 · 1080p 8 · 720p 4 · 480p 1.5 Mbps), tone-mapped once, P4000, **no kernel cache** | 17.7 s | 2.1× | see below |
| same, **CUDA kernel cache warm** | **2.0 s** | **4.7×** | |
| HEVC Main 10 HDR, 4 rungs (2160p 20 · 1080p 8 · 720p 4 · 480p 1.5 Mbps), P4000 | **2.3 s** | **2.6×** | 2160p top: 132 MB/min |
| two HEVC 4-rung jobs at once on the P4000 | 2.9 / 3.2 s | **1.3× each** | the P4000 carries two 4K HEVC ladders |
| decode only, 4 s of film | — | — | 2.4 s wall incl. start-up |

**The 15 s "cold start" is the GPU kernels being compiled, not the encoder.** `tonemap_cuda`/`scale_cuda` kernels are
JIT-compiled on first use; Jellyfin's container has `HOME=/` and no `CUDA_CACHE_PATH`, so the compiled kernels are never
kept and **every Jellyfin transcode with a tone-map pays ~7–10 s** (on the 2060 SUPER too: 12.5 s cold, 2.3 s cached).
This is very likely a large part of the 8.5 s median transcode start in the evidence report. Our encoder sets
`CUDA_CACHE_PATH` to a persistent folder (`[encoder] cuda_cache_dir`, under `/config`). **Side finding for Jellyfin itself:**
giving Jellyfin's container a persistent `CUDA_CACHE_PATH` (e.g. `/cache/cuda`) should cut its own tone-mapped starts by
~7 s — a one-line compose change, the owner's call.

Decisions from the numbers: up to **4 rungs** per play; the **P4000 first** (no session cap), a 4K HEVC ladder counts as
half the card (two fit at ≥ 1.3×), an H.264 1080p ladder a quarter; the 2060 SUPER only when the P4000 is full, within
6 of its 8 sessions. The FR-313-3 caps stand (2160p HEVC 20 Mbps, 1080p H.264 12 Mbps, 1080p HEVC 8 Mbps).

### What was built

- `tv/EncoderPlan.kt` (pure): rungs (top capped by its own output size, the source and the decode ceiling; lower rungs
  capped per codec family), `trimRungs` (start, below, above), `startRung` (309's budget), codec family per device
  (HEVC only with `hls_hevc` + the source's HDR form), exact `CODECS` strings (`avc1.6400xx`, `hvc1.2.4.L150.B0`…),
  `VIDEO-RANGE`, the master (start rung first, audio renditions `a{pos} {label}` as R291), the VOD playlists (2 s, init
  segment for fMP4), the ffmpeg command (5 MB probe, one GPU decode, scale-then-tone-map once, `split`, NVENC per rung,
  `-tag:v hvc1`, colour tags, every audio rendition from the same process, `-copyts`, `temp_file`), the budget
  (`placeJob`, `nvidia-smi` parsing) and the fallback rules with their reasons.
- `tv/EncoderJobs.kt`: one ffmpeg per stream; pause 40 s ahead / resume within 20 s; a seek replaces the job and keeps
  the two previous jobs' segments (served without an encode); idle kill after 60 s; stop with the play; a sweep of
  leftovers at start.
- `tv/Encoder.kt`: the per-play decision and plan (`planFor`), the stream registry under 128-bit ids, the routes'
  playlists/segments/init, `stopFor` (phase 180), `/api/health`'s `encoder` block, and `ensureFfmpeg`: jellyfin-ffmpeg's
  **portable GPL build 8.1.2-5, pinned by SHA-256**, downloaded into `/config/encoder` on first start when the encoder is
  on (not shipped in the public image — it contains libfdk_aac; we never use that encoder, ffmpeg's own `aac` is used).
- `tv/SecureIds.kt`: stream ids from `/dev/urandom` (dev review item 7) — R291's rendition ids now use it too.
- `PlaybackService.withRenditions`: for a transcode that re-encodes the picture, our encoder is asked first; when it
  serves, the ticket's URL is our master and **no Jellyfin stream URL is handed out** (so Jellyfin starts no transcode
  job); otherwise the 308/R291 path as before, with `encoder: fallback to Jellyfin — <reason>` logged.
  `releaseEncodes` stops our job first (phase 180 / 312's encodes-only path).
- Routes `GET /api/tv/stream/{id}/{v|a}/{i}/main.m3u8 | init.mp4 | <k>.m4s | <k>.ts`, segments sent in 256 KB pieces
  (`server/RespondFileChunked.kt`, dev review item 6b).
- Config `[encoder]`: `enabled` (default **false**), `ffmpeg_dir`, `download_ffmpeg`, `work_dir` (tmpfs), `cuda_cache_dir`,
  `max_disk_mb`. `docker-compose.gpu.yml` (new): the GPU + a 4 GB tmpfs at `/transcode/js`, used on top of the base
  compose; without it the backend starts as before and every transcode stays Jellyfin's. `Dockerfile`: `xz-utils`.
- **Found while building (EncoderAlignmentTest):** `-start_number K` makes ffmpeg's HLS muxer count its cut targets from
  K (a restarted job's first segment came out 2(K+1) s long), and `force_key_frames`' `t` counts from the job's own first
  frame even with `-copyts`. Every job therefore numbers its files from 0 and forces keyframes on its own 2 s grid, which
  is the file's grid because a job always starts on a 2 s boundary; `EncoderJobs` maps segment k to file `s<k − start>`.

### Sub-phases

- **313a** ✓ measured; GPU override + ffmpeg fetch + health block built.
- **313b** ✓ built (H.264 and the one-rung mode for 1.50 apps — owner Q1 — via `hls_adaptive`), behind `[encoder] enabled`.
- **313c** ✓ built in the same code: HEVC Main 10 HDR rungs for HEVC/HDR devices, tone-map once for H.264. Dolby Vision 5
  falls back to Jellyfin; DV 7/8 play as their HDR10 base (the decoder drops the RPU/EL).
- **313d** partly: text subtitles are left to the player as today (the ticket's own subtitle list); the burn-in overlay
  is in the command builder (`overlay_cuda`, composited once before the split, H.264 only) but **a burn-in play still
  goes to Jellyfin** until it is verified on a device. WebVTT renditions in the master: not built.
- **313e** not done: the encoder is **off by default**; turning it on for the dev stack is the live test.

### Tests

`EncoderPlanTest` (16: caps, cropped 4K, ceilings, trim order, start rung, codec family, codec strings, master, playlists,
commands incl. one tone-map and the keyframe grid, burn-in before the split, budget across cards, every fallback reason,
the planner for a BRAVIA-like device / a 1.50 app / the receiver / a burn-in, secure ids) and `EncoderAlignmentTest`
(a real libx264 encode: segment k of every rung and of a job restarted at segment 3 starts at the same PTS, 2 s apart;
the audio rendition and fMP4 init segments exist). Full `linuxX64Test` green. Also verified by hand on the host's GPU:
the builder's exact command shape (H.264 tone-mapped, 2 rungs + audio, fMP4) on the P4000 — 10 aligned 2.002 s segments
per rung and an init segment each.

### Not verified (needs the deploy)

No device has played through our encoder yet: that needs `docker-compose.gpu.yml` on the dev stack, `[encoder]
enabled = true`, and a test play (Stue TV debug app, the Pixel, a Chromecast for the TS path).

## Live testing (2026-10-08 night, v1.50-73/-74)

- **Deployed** with the GPU override and `[encoder] enabled = true`: `/api/health` shows jellyfin-ffmpeg 8.1.2-5 ready
  (checksum OK) and both cards.
- **Pixel 9 Pro (debug app), a 4K Dolby Vision 7 REMUX:** `encoder=ours`, H.264, 4 rungs, card 0; **no Jellyfin
  transcode job** (no `FFmpeg.Transcode-*` log). First segment 9.0 s on the first start after the deploy (empty CUDA
  cache), 5.8 s on the next.
- **Found 1 — fMP4 doesn't play on Media3:** with fMP4 video + fMP4 audio renditions the player stayed BUFFERING at 0 with
  48 s buffered and never created a decoder (the stream itself decodes cleanly in ffmpeg). Fixed: `encoderMuxFor` gives
  MPEG-TS to everything except the Mac, iOS and the web (`b43de79f`); with TS the Pixel went READY 7.5 s after Play.
- **Found 2 — the P4000 couldn't keep up at preset p4:** a seek restarted the job once (segment 725, as designed), but
  the job then ran at ~0.5× realtime and playback stalled. Measured on the same source, 30 s: decode only 9.2×,
  decode + tone-map 4.8×, + 4 H.264 rungs at **p4 1.1×**, at **p1 8.8×**; the 2060 SUPER at p4 4.4×, p1 6.1×; HEVC Main 10
  (2160p top + 3) on the P4000 p4 2.3×, p1 4.6×. 313a's 4.7× figure matched decode + tone-map, not the encode. Fixed:
  preset p1 for every GPU encode.
- **The app's 8 s segment timeout** fires before a cold first segment (9 s, 12.9 s after a seek at p4); Media3 retries
  and recovers, but the first frame waits for the retry. With p1 and a warm cache the first segment should land well
  inside 8 s; if not, the backend should answer a not-yet-made segment early (e.g. 503 + Retry-After) rather than hold.
- **With p1 (v1.50-76), the same film on the Pixel:** first segment **1.7 s**, READY **3.2 s** after Play, ~2.5× overall;
  one slow patch early (segments 4–5 took 9 s for 4 s of film → a 3.8 s stall at 0:10). A seek (to 43:33) restarted the
  job once, first segment 1.6 s after the restart, but seek-to-picture took ~8.7 s and stalled once more:
  (a) Media3 waited ~4 s for an in-flight request for a segment the job hadn't made (paused 40 s ahead) before it asked
  for the new position — a request for a segment beyond the pause point should resume the job or be answered at once,
  never held; (b) the first segments after a restart came at ~0.6× before the job sped up. Both measured while the
  qBittorrent force recheck read the films disk at ~220 MB/s (60–80 % busy), which likely explains the slow patches;
  re-measure when the recheck is done.
- **A cast to Stue TV's web receiver (2026-10-09, debug Pixel build with the development Cast app):** the receiver
  enrolled, `encoder=ours HEVC HDR rungs=2160p@20000k/… TS card=0`, but ffmpeg exited at once: NVENC refused the 2160p
  rung — *"InitializeEncoder failed: Invalid Level"*. Level 5.0 (Main tier) caps the peak at 25 Mbps and the 20 Mbps
  top rung peaks at 30 Mbps (`-maxrate` 1.5×). Fixed: 2160p HEVC is level **5.1** (`hvc1.2.4.L153.B0`), verified by
  hand on the P4000 (5.0 fails, 5.1 encodes). The job retried 3× and the phone's remote showed *"Stue TV couldn't play
  this · Play on this phone"* — the failure path works. **Open:** when our encoder's job fails to start, the play should
  fall back to Jellyfin's transcode instead of retrying the same command (FR-313 fallback covers "no slot/no GPU" but not
  "ffmpeg refused").
- **Køkken Hub:** casting from the Pixel failed before anything reached the backend — the hub's Cast port (8009) times
  out from both the host and the phone (8008 answers), so the hub's Cast service is down; not a jellystructure fault.
  Reboot the hub.
- **With level 5.1 (v1.50-78), the same cast to Stue TV's receiver started:** `encoder=ours HEVC HDR` 2160p/1080p/720p/480p,
  TS, card 0, first segment 5.9 s; the receiver reported PLAYING and advanced normally for the first minutes. **But
  the unattended play then ran ~62 min (23:15–00:17 UTC) with 208 rebuffers totalling 1 495 s** (QoE: first frame
  15.9 s, 5 steps up / 3 down, last variant ~30 Mbps, 5 212 bandwidth samples). **Not diagnosed yet** — candidates:
  (a) the P4000 can't hold 4 HEVC Main 10 rungs incl. 2160p at realtime while the qBittorrent force recheck reads the
  films disk (~220 MB/s, 60–80 % busy); (b) Shaka climbing to the 2160p rung the receiver then can't fetch/decode fast
  enough; (c) the paused-job / in-flight-segment wait seen on the Pixel. Measure next: segment production rate vs
  realtime per rung during a cast, after the recheck has finished; consider capping a cast at 1080p HEVC, or the
  2160p rung on the 2060 SUPER. **Until then, 313 should not be the default for casts.**
- **The receiver's stop lost the place:** it reported its stop at 0 ms after ~37 min of film (backend `playback stop …
  at 0ms`); a 312-class bug on the receiver side — to fix with 312.

## Build notes — 313d / 313e (2026-10-08, worktree branch with 309a/309b, not merged or deployed)

- **313d, burn-in in our job:** a restream with an image subtitle passes its place among the file's own subtitle streams
  (`embeddedSubtitleOrder`: Jellyfin's non-external subtitle streams in index order, what `0:s:N` names) to the plan;
  the plan is H.264 (tone-mapped once from HDR; `overlay_cuda` composites 8-bit frames), the overlay before the split as
  already built. A subtitle the scan can't place, or a sidecar, stays Jellyfin's (`… (313d)` fallback reason). Not yet
  seen on a device.
- **313d, WebVTT renditions:** for a client with `hls_subtitles` (AVPlayer/Safari), the ticket's `hls` text subtitles
  become `#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs"` renditions of our master (`SUBTITLES="subs"` on every variant);
  `/api/tv/stream/{id}/t/{i}/main.m3u8` is one VOD segment of the whole track and `…/sub.vtt` is Jellyfin's own VTT
  conversion, fetched by this server (the tokened URL never reaches the player), cached ≤ 64 texts, refused unless it
  starts with `WEBVTT`. Other clients keep the ticket's own subtitle list as before.
- **313e:** `[encoder] enabled` now defaults to **true**; with no GPU in the container the ffmpeg download is skipped and
  every play falls back to Jellyfin with the reason *no GPU in the container*. The ticket says `encoder`
  (`ours`/`jellyfin`/`direct`), QoE stores it, and *Playing now* shows *our encoder · 4 qualities · HEVC HDR* or
  *Jellyfin* beside 308's variant. FR-313-14: 309's warm rungs and decision cache were never built, so nothing was
  removed.
- **Deploy:** the dev stack needs `docker-compose.gpu.yml` (else the default-on encoder simply falls back).


## Integration fixes (2026-10-09, before the deploy of R379 + 309 + R376 + 314b/c)

From the live cast test (a Chromecast cast served by our encoder stalled **208 times, ~25 min of stalls in 62 min, first
frame 15.9 s**; cause not found — the qBittorrent force recheck had the films disk 60–80 % busy at the time):

1. **Cast receivers use Jellyfin until the stall is diagnosed.** `encoderDecision` gains the device kind: a `cast`
   device (the Chromecast web receiver) gets `cast receivers use Jellyfin until the stall is diagnosed (313)` as its
   logged fallback reason. A Cast Connect load into the Ravilo TV app plays as the TV (`tv`) and keeps our encoder.
   Test: `EncoderPlanTest`'s decision test. To lift: diagnose the stall (receiver QoE + segment timings with the disk
   idle), then remove the rule.
2. **ffmpeg refusing a plan no longer loops.** A job that exits with an error before writing its first segment marks
   its stream *refused*: no job is started again for it (its segments answer null until the play ends), and the file is
   remembered for 6 h, so the next play of it (the player's retry asks for a new ticket) goes to Jellyfin with
   `ffmpeg refused this file (…) — Jellyfin serves it (313)`. A job that fails later (after segments) is not a refusal.
   Tests: `refusedBeforeFirstSegment` and `RefusedSources` in `EncoderPlanTest`. Not done: switching the *running* play
   to Jellyfin without the player's own retry (needs a server-sent restream; listed for later).
3. **The receiver reports its stop where it stopped** (312's class): CAF's last `TIME_UPDATE` after the player stops
   carries no media time, which read as 0 and overwrote the place just before the stop was sent. `castPositionAfterUpdate`
   (`:shared`, `CastStopPositionTest`) keeps the last real position over a missing time or an idle reset to 0.

## Live results after the integration deploy (2026-10-09, v1.50-105 → v1.50-106)

- **Health:** `encoder enabled true, ffmpeg true, gpu true, cards [Quadro P4000, RTX 2060 SUPER]`.
- **A start served by our encoder** (a synthetic player with the Pixel's token and phone capabilities, 4K DV7 film):
  `encoder=ours H264 rungs=1080p@12000k/1080p@8000k/720p@4000k/480p@1500k audio=7 TS card=0`, start route 200 ms, master
  with four variants and exact `CODECS`/`VIDEO-RANGE`; **first segment 781 ms**; a resume at 10:00 restarted the job at
  segment 300 — **first segment 994 ms**; a different rung of the same job **0.5 s** per segment, no new encode.
- **313d burn-in:** a restream with the English SDH PGS subtitle → `encoder=ours … burn-in`, first segment **1.3 s**;
  frames pulled from the segments show the subtitle drawn in, tone-mapped to SDR.
- **Cast receivers → Jellyfin** (integration fix 1) is live; its live check needs a cast (not run: the Chromecast stall
  is still to be diagnosed with an idle disk).
- The stop watchdog ended each synthetic play within 30 s — correct: the Pixel's app (whose device id the test used)
  had no events socket open, which the watchdog reads as *app gone*.

## Mac live check (2026-10-09, test build `v1.50-117-g5c2fa010` built on the owner's new MacBook (M5 Pro, macOS 27.0.1) in `~/ravilo-test`, **signed ad hoc** (the Ravilo signing key isn't on the new Mac); driven with the in-app test driver) — two burn-in bugs

1. **The burn-in filter graph is broken for an SDR H.264 source:** picking a German PGS subtitle on a film with several audio tracks
   restreamed through our encoder (`H264 4 rungs … burn-in, card 0`) and ffmpeg failed: *"Impossible to convert between
   the formats supported by the filter 'Parsed_split_5' and the filter 'auto_scale_0' … src: cuda"* (CUDA frames into a
   software filter). The refusal path worked (`ffmpeg refused this plan — no restart; the file goes to Jellyfin`).
2. **A refused plan during a restream leaves the player dead:** the client keeps the ticket that points at our
   encoder's (now empty) master and retries it every second (`NSURLError -1100`), showing "Loading…" forever; Esc didn't
   leave it. The "goes to Jellyfin" fallback only helps the *next* play — the restream itself must be re-answered from
   Jellyfin (or the client told to restream). The restream also started at segment 0 and with `audio=default`.

### Found live 2026-10-09 — fixed

- **F1 · A burn-in's filter graph.** *Defect:* with a picked PGS subtitle the graph overlaid the subtitle with
  `overlay_cuda` and then split; ffmpeg 8 adds a colour conversion in front of the encoder whenever the output's colour
  flags (BT.709) do not match the frames' own tags, and the overlaid frames carried none — a software `auto_scale` on
  CUDA frames, so ffmpeg refused the plan. Without Jellyfin's subtitle chain the overlay also waited on subtitle frames
  and produced nothing. *Fix:* the subtitle goes through Jellyfin's own chain (`scale,scale=W:H:fast_bilinear,
  format=yuva420p,hwupload`), `overlay_cuda=eof_action=pass:repeatlast=0`, then `setparams` tags the composited frames
  BT.709 before the split, and the input gets `-canvas_size` (the PGS canvas = the source size). Checked on a real
  file with a German PGS track: 2 s segments at ~8× real time. A plan ffmpeg still refuses keeps going to Jellyfin.
- **F2 · A refused restream left the player dead.** *Defect:* the restream's ticket pointed at our encoder's master;
  ffmpeg refused, the master stayed empty, and the Mac's AVPlayer retried it every second ("Loading…", Esc could not
  leave). It also started at segment 0 and asked for `audio=default`, losing a picked DTS track. *Fix:* (a) the master,
  a variant playlist and a segment of a refused stream answer **410 Gone**, so the player fails at once instead of
  waiting; (b) the client's failure watch is armed again after a restream, and an our-encoder stream that fails or whose position has
  not moved for 20 s while playing is restreamed **once** — the refused file is marked, so that restream is Jellyfin's —
  and a second failure or stall ends in R237's error (Retry / Back), never an endless spinner; (c) the master of a play that starts
  mid-film carries `#EXT-X-START:TIME-OFFSET=<s>,PRECISE=YES`, the init segment is made at the start segment, and the
  restream asks for the audio the viewer picked (R291's renditions carry none in the session, so the picked track's
  index is sent). **Mac re-test owed:** a PGS pick on a film with several audio tracks (picture + subtitle + the picked
  audio, from the current place), and a forced refusal (playback continues from Jellyfin; Esc leaves).

## The cast stall: cause and fix (2026-10-09, v1.50-118 → v1.50-130)

- **Cause (ours):** nothing ever deleted a job's segments. Four H.264 rungs plus audio fill the 4 GB tmpfs
  (`/transcode/js`) in about 16 minutes of a film; from then on ffmpeg cannot write, the receiver's next segment never
  comes and the cast stalls for good — the stall the receiver showed at the same point every time.
- **Fix (d476880f):** the job keeps `KEEP_BEHIND = 30` segments (a minute) behind the furthest one asked for and
  unlinks everything older, for itself and for the stream's retired jobs (every 8 progress lines); a request below the
  pruned point is not "reached" (a seek back restarts the job there). `pruneRangeKeepsAMinuteBehind` tests the range.
- **Verified with the dev-only override** (`[encoder] cast_receivers = true`, set for this one test and removed
  again): a 21-minute cast of a 4K HDR film to the Køkken hub on our encoder (H.264 4 rungs, TS, tone-mapped) went
  BUFFERING → PLAYING in 7 s and stayed PLAYING to the end (1242 s, no rebuffer); the tmpfs held 350–415 MB the whole
  way (it had reached 4 GB at ~16 min before); the encoder stopped with the cast. Casts stay on Jellyfin's transcode by
  default (`cast_receivers` off) until the owner turns them on.

## Mac re-test (2026-10-09 evening, backend v1.50-130, Mac test build `v1.50-136-gfcec8bf2`, ad hoc signed, test driver)

- **F2 · a failed or refused stream recovers or ends — passes on the client.** An HDR film whose stream the Mac
  could not play (below) was restreamed **once**, failed again and ended on R237's *Something went wrong* with
  **Retry / Back**; no endless *Loading…*; **Esc** left to the title's page. The 410 path of a refusal *during* a
  restream was not reached: the refusal below happened at the 309 prewarm, so the play itself was answered by
  Jellyfin from the start.
- **F1 · a burn-in keeps the place and the audio — passes, through Jellyfin.** On a stand-in SDR film with
  TrueHD/DTS/AC-3 English + AC-3 Hindi + PGS, playing the remembered DTS English at 4:06, a German PGS pick logged
  `PlaybackInfo(restream, burn-in) … sub=9 audio=5` (Jellyfin's index 5 = the DTS track, sidecars counted first) and the
  restream loaded at `start=246684ms`; the picture came back at 4:06 with the German subtitle drawn in and played on.
  Our encoder's burn-in could not be tried on that file (marked for Jellyfin, below).
- **New, found live — every SDR source with untagged colour is refused by our encoder.** The Mac (HEVC over HLS) got
  `HEVC 3 rungs 1080p@8000k/720p@4000k/480p@1500k, 4 audio, FMP4, card 0` at the prewarm; ffmpeg exited 55808
  (*Error reinitializing filters … -38 Function not implemented*, then *hevc_nvenc: Could not open encoder*), the plan
  was refused and the file marked for Jellyfin. **Cause, reproduced on the host:** the source's colour tags are
  `unknown`, and the output's `-color_primaries/-color_trc/-colorspace bt709` make ffmpeg 8 insert a software
  `auto_scale` after the `split` on CUDA frames — the same failure as F1, on the plain SDR path. The codec doesn't
  matter: `h264_nvenc` fails the same way (*Impossible to convert between … 'Parsed_split_1' and 'auto_scale_0'*);
  `setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709` before the `split` makes both encode (4 s test,
  exit 0). **Fix owed (not done here):** tag the frames with `setparams` on the SDR path too (`encoderCommand`'s
  non-tone-map branch), and a test that an untagged SDR source gets it. Until then such files fall to Jellyfin for 6 h
  after their first play.
- **New, found live — an HEVC HDR stream does not play on the Mac.** See R329's re-test: the server served
  `encoder=ours HEVC HDR rungs=1068p@12000k/800p@8000k/534p@4000k/356p@1500k … FMP4 card=0 prewarmed`, and AVPlayer
  failed at once (`NSURLErrorDomain -1002 unsupported URL`). The init and the first segment are valid HEVC Main 10 PQ.
  A failed client stream is restreamed to **our encoder again** (only an ffmpeg refusal marks the file), so the
  restream fails the same way; a client-side failure of our stream should ask for Jellyfin on the restream.

### Found live 2026-10-09 (evening) — fixed

- **The Mac's HEVC HDR stream: not our playlist.** AVPlayer drops HDR variants on a screen that cannot show HDR
  (`AVPlayer.eligibleForHDRPlayback` false — the Mac drives a 1080p SDR monitor), so an HDR-only master has no variant
  left. Our master, variant playlists, init and segments play in AVPlayer when the claim matches the screen (R329's
  *Found live 2026-10-09 (evening)*). *Fix (client):* the Mac claims HDR only while AVPlayer is eligible. Nothing
  changes in the playlists, so Media3, hls.js and the Cast receiver read exactly what they read before. *Not done:*
  an SDR rung beside the HDR ones in the same master (Apple's authoring guidance) — it costs a second tone-mapped
  encode per play; a screen change in the middle of a film is not covered.
- **An SDR source with untagged colour is refused by our encoder** (the re-test above). *Fix:* the GPU graph tags the
  frames with the output's own colours before the `split` on every path that doesn't tone-map — `setparams` bt709 for
  SDR, bt2020 + PQ/HLG when HDR is kept (a no-op for a tagged source; metadata only, no conversion). The output's
  `-color_*` flags then match the frames and ffmpeg 8 inserts no software `auto_scale` on CUDA frames. Test: the
  generated command for an SDR and an HDR-keep plan carries the tags before `split`; the same graph run on the host's
  jellyfin-ffmpeg + P4000 against an untagged SDR clip encodes (exit 0) where the old one failed.
