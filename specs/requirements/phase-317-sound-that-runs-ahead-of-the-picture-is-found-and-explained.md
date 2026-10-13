# Phase 317 — Sound that runs ahead of (or behind) the picture is found, stored, and explained

> Owner, 2026-10-10, on a KVF broadcast (*Broadcast K*, stand-in): *"The audio is a bit out of sync … at
> around 1:09:00 and forward"* — then: *"build a warning and show this type of issue on the dashboard … and when
> opening the details page it would show it as well, with the suggested command to fix it."*

## Scope

Written 2026-10-10 from a dev-authored investigation against `main` `d37f1a7a`; owner decisions the same day
(§Decisions); **dev-reviewed 2026-10-10 against the same commit** (§Dev review — every site this phase touches,
and the six gaps the first draft left, closed in the FRs below). The measurement is prototyped and measured in
`avsync/` and `specs/research-reports/av-sync-measurement-on-the-gpu-2026-10-10.md`.

The phase owns the measurement, its storage, and the words that explain a finding. It does **not** own the
container's locale: [phase 318](phase-318-the-container-speaks-ascii-and-every-mkvtoolnix-call-pays-for-it.md)
took that over on 2026-10-13, after it turned out to be the cause of the truncation this spec's 2026-10-13
amendment was written about.

Modelled on phase 255 (a persisted per-file check → Triage row → the title page's banner with what / who / fix /
command → a ↻ in 261's Checks card). The new thing is the **measurement**: it cannot be done from container metadata
and needs a lip-sync model on the GPU, so it lives in a sidecar.

## Decisions (owner, 2026-10-10)

| # | Question | Answer |
|---|---|---|
| 1 | Sweep the existing library? | **Yes, every file** — *"but this means we need to use the GPU properly, so first investigate how"* → the research report: ~22 h of one card for ~9 600 files, one file at a time (FR-317-3, FR-317-8) |
| 2 | Skip Animation? | **Yes** (FR-317-8) |
| 3 | Which GPU? | Dev's call → **GPU 1, the RTX 2060 SUPER** (FR-317-1). Reverses the first draft's lean to device 0: GPU 0 (P4000) carries every Jellyfin transcode (`cuda:0` by default) and 313's encoder (`EncoderPlan.cudaDevice = 0`); GPU 1 has tensor cores and only Immich's ML |

## What was found (household server, 2026-10-10)

| Finding | Evidence |
|---|---|
| **The offset is in the picture and the sound, not in the timestamps.** The file is a clean `-c copy` of KVF's HLS: no gap in any packet timestamp over 2 h 42 min; KVF's own segments start audio 35 ms before video, as the file does. | ffprobe packet dump; KVF's 1080p chunklist |
| **So nothing ffprobe reports can find it.** | — |
| **A cheap mouth-opening vs loudness correlation is too weak** (peak z ≈ 2–3). | 2 × 90 s windows |
| **SyncNet measures it**: SyncNet v2 (Chung & Zisserman, MIT code; S3FD faces) reads **+3 to +4 frames at every window**, constant. | 11 windows |
| **The sign, calibrated.** Audio delayed 200 ms (5 frames) moved +4 to −1. **+N = audio N frames early.** | shifted copy |
| **The fix works and is lossless.** Audio delayed 160 ms (`-itsoffset … -c copy`): every window from +3/+4 to −1/0. | remuxed copy, 7 windows |
| **In-sync material reads −1 to 0, not 0.** ~20 live-action titles of a random sample: every confident window at −1 or 0, a few −2. | research report |
| **The sample found a second one**: a 2014 film (800p BluRay encode, *Film T*): +2…+4 over nine windows. | research report |
| **Faces are needed.** Animation, nature documentaries, sports crowds: no faces or conf < 1.6. | research report |
| **As published, SyncNet's pipeline cannot do a library** (~160 s per 45 s window, 16 % GPU). **Rebuilt GPU-native: 8.2 s per file one at a time, 5.4 s with two in flight**, same answers. | research report |

Thresholds (ITU-R BT.1359): detectable at **45 ms audio early / 125 ms late**, acceptable to **90 ms early / 185 ms
late**. The reported broadcast's ≈ 150 ms early is past both.

## Requirements

### The measurer

**FR-317-1 — A sidecar on GPU 1, seeing the library at jellystructure's own paths.**
- Image `jellystructure-avsync`, built from `avsync/Dockerfile` with the **repo root as context** (as `publish.yml`
  builds the others): PyTorch, SyncNet v2 + S3FD (code pinned by commit; weights fetched at build from VGG's URLs and
  checked by SHA-256, never committed — their licence is not stated), **jellyfin-ffmpeg 7** for `scale_cuda`,
  `tonemap_cuda` and dav1d. Published by `publish.yml` as a third matrix entry.
- Overlay `docker-compose.avsync.yml`, in the shape of `docker-compose.gpu.yml`: GPU reserved **by device id**
  (`device_ids: ['1']` on the household host; documented as "the card that does not transcode") with
  `capabilities: [gpu, compute, utility, video]` — `video` is what lets NVDEC in; without it every decode silently
  falls back to the CPU.
- **Every library root mounted read-only at the same container path jellystructure uses** — the sidecar opens the
  paths jellystructure sends it verbatim. The repo overlay mounts `${MEDIA_PATH:-./media}:/media:ro` like
  `docker-compose.yml`; the household's deploy compose mounts `/mnt/media` and `/mnt/series` at those paths.
  (The first draft said `/media`; the household container sees `/mnt/media` and `/mnt/series` — it would have found
  nothing.)

**FR-317-2 — The sidecar's API** (`avsync/server.py`, stdlib `http.server`, wrapping `measure.py`'s functions; one
measurement at a time):
- `GET /health` → `{ok, gpu, vram_total_gb, model, busy}` — `model` = `"syncnet_v2+s3fd@907c0b5/1"` (the last part
  is the pipeline's own version; a bump makes every stored result due again, FR-317-7).
- `POST /stat {path}` → `{exists, size}` — the mount check (FR-317-12).
- `POST /measure {path, audio, others, windows:[{start_s, dur_s}]}` → `200 {windows:[{start_s, offset_frames, conf,
  frames, faces:[{at_s, frames, offset_frames, conf}]}], others:[{audio, lag_frames, corr}], decode:"nvdec"|"cpu",
  seconds}`. `audio` is the stream to measure lips against, `others` the file's other audio streams — both ffprobe
  specifiers (`Track.specifier`, `0:a:N`; never Jellyfin's numbering, R382).
- Errors: `404` path not visible · `422` undecodable (the body says ffmpeg's last line) · `503 {retry_after_s}` busy.
- Limits the prototype measured: one GPU worker, 3 decoders ahead **within the file**, `vram_gb = 2` cap per worker
  (3.9 GB card peak with the decoders' own CUDA contexts — Immich keeps ~4 GB). Env `AVSYNC_VRAM_GB`,
  `AVSYNC_DECODERS`.

**FR-317-3 — How it uses the GPU** (each measured in the research report; the prototype does all of them): NVDEC +
`scale_cuda` to 640 px + `fps=25`, read straight into a tensor; HDR tone-mapped with Jellyfin's own `tonemap_cuda …
bt2390` chain first; **AV1 to the CPU by codec** (neither card's NVDEC has AV1; trying first costs ~20 s); faces on
every 5th frame, batched, FP16, NMS on the GPU, linked by centre distance, tracks ≥ 2 s cut into ≤ 10 s pieces; crops
by `roi_align` in FP16; speech features once per window; **one distance curve per window**, every face weighted by its
length. One file per request: **~8.2 s per file → ~440 files/h → the library in ~22 h**.

**FR-317-4 — Which audio is measured, and what the others do.** Lips follow the **original language**:
- `audio` = the file's audio track whose language is the title's `originalLanguage`
  (`LanguageResolver.sameLanguage`), the default among several, else the first. A file whose only audio is untagged
  is measured on it. **No original-language track and more than one audio track** → no request; `unknown`
  (*"no track in the original language — a dub has no lips to follow"*).
- `others` = every other audio stream. The sidecar cross-correlates each one's loudness envelope with the measured
  track's over the same windows (±2 s): a dub mixed onto the same music-and-effects lines up with it. Each comes back
  as `lag_frames` + `corr`. **Unmeasured in the prototype — verify on build** against a film with a Nordic dub
  (acceptance 8).

### What is stored, and what it means

**FR-317-5 — Stored per file**, raw, like `file_track_coverage` (`FileAvSync.sq`, migration **78.sqm** — verify free at
build; 77 is 309/313's): `file_av_sync(path TEXT PRIMARY KEY, size, mtime, checked_at, model TEXT, audio TEXT,
reason TEXT, windows TEXT, others TEXT, mkv_audio_ids TEXT)`. `windows` / `others` are the sidecar's JSON; `reason` is
set only when nothing was measured (`animation` · `too_short` · `no_original_track` · `no_faces`); `mkv_audio_ids` maps
each `0:a:N` to its mkvmerge track id, read once at check time (`mkvmerge -J`, FileFixCommands.identify), so the
advice never reads a media file on the request path (255's rule). Current only while size and mtime match.

**FR-317-6 — Zero is the library's.** The verdict measures from the **conf-weighted median `offset_frames` of every
confident window stored** (FR-317-7's test), recomputed when the table's revision changes; **−1 frame until 200
confident windows exist**. The verdict is computed **on read**, never stored, so a moving zero never leaves a stale
state behind.

**FR-317-7 — The verdict** (`AvSyncVerdict.of(windows, zeroFrames, runtimeS)`, commonMain, pure, tested):
- a window is **confident** when `conf ≥ 2` over `frames ≥ 250`;
- offsets are relative to zero; `m` = their conf-weighted median;
- **steady** when ≥ 75 % of confident windows lie within ±1 frame of `m`; **drifting** when not steady, there are ≥ 4
  confident windows, a least-squares line over time has ≥ 75 % of them within ±1 frame of it, and it changes ≥ 3
  frames from the first window to the last;
- states: `in_sync` (steady, `m` inside the threshold) · `offset` (steady, `m` past it: audio early ≥ 80 ms, i.e.
  `m ≥ +2`, or late ≥ 160 ms, `m ≤ −4`) · `drift` · `unknown` (fewer than two confident windows, a `reason`, or readings
  that are neither steady nor drifting — *"the readings disagree"*). **`unknown` is never counted or shown as clean.**
- `offset_ms = m × 40`; for `drift`, the line's value at the first and last window.

Weighted median = the smallest value whose cumulative weight reaches half. *Film T*'s nine measured windows
(`offset_frames`/`conf`/`frames`: +2/2.45/191 · +3/2.54/936 · +2/0.16/601 · +3/1.03/710 · +4/2.68/329 · +15/0.31/197 ·
+4/3.13/1311 · +4/1.05/713 · +2/0.94/1235) have three confident ones, +3, +4, +4 — from a zero of −1, +4, +5, +5:
steady, `m = +5` → `offset`, **200 ms early**. The reported broadcast, seven windows with the prototype (+3, +4, +4,
+4, +3, +3, +3; confident: +4/4.37, +3/4.29, +3/2.05) → from −1: +5, +4, +4 → `m = +4` → `offset`, **160 ms early** —
and its 160 ms remux, the same seven windows, reads −1, 0, 0, 0, −1, −1, −1 (confident: 0/4.56, −1/4.49, −1/2.07): the
library's zero, `in_sync`. The command's own number is the one that fixes it (measured 2026-10-10).

### When it runs

**FR-317-8 — Every file, once, on its own lane.**
- A fourth file check, step `check_av_sync` (`FileCheckSteps.AVSYNC`, job `check_av_sync`, dedupe `avsync:<path>`):
  `defaultStep` with `recheckUnchanged = false` — a result stands until the file changes or `model` changes (both make
  it due, `NO_RESULT` priority). Seeded once into a configured pipeline by `seedAvSync` (after `check_subtitles`,
  before trailing wait/notify — `seedSubtitles`' shape) and added to `rawPipeline`'s built-in default. The step's
  pass is enqueue-only, like the other three, over the whole library: **this is the sweep** — every file has no
  result at first, newest first (254's order).
- **Skipped without a request**, written straight to `file_av_sync` with a `reason` in the same pass: titles whose
  `genreIds`/`tmdbGenreIds` contain TMDB **16 (Animation)** — ids, never the localised genre name; files shorter than
  2 min (`too_short`); no original-language track among several (`no_original_track`).
- **A lane of its own, `avsync`, with its own worker.** The other three lanes share `behavior.job_workers` (213's
  rule: one worker per lane). A 22-hour sweep there would hold a shared worker the whole time and starve segment
  detection; its cost is GPU 1 and the disks, not the backend's process gates. So `avsync` rows are claimed only by a
  dedicated worker, which runs only while `[avsync] enabled` and the sidecar answers `/health`; the shared pool never
  claims them. Rows wait while a TV plays (`deferWhilePlaying`, the household setting, 262/295's parking) — the real
  contention is the spinning disks (~3 × 45 s read per file).
- Windows: 20 %, 50 %, 80 % of the runtime, 45 s each, clamped inside the file; when fewer than two come back
  confident, a second request for 35 %, 65 %, 90 %.
- The Checks card's ↻ (`check_avsync`) queues the title's files first in the lane (`PRIORITY_OPERATOR`, never
  deferred), as the other three.
- Activity's Jobs view: the lane's card (*A/V sync · 1 204 waiting · ~2 h 45 min at 8 s a file*) and the per-file group
  line (`groups()`: *Check A/V sync*, findings today from `file_av_sync`).

### Saying it

**FR-317-9 — The Dashboard**, one triage type per state that needs action (255's five sites, FR-255-7):
- `av_offset` — **warning**, unit *files*, domain Films/Series by kind, fix *open the item*:
  *"Sound and picture out of step"* — *"Across {titles}: speech is heard before the lips move (or after). One
  lossless remux fixes it."*
- `av_drift` — **warning**, unit *files*: *"Sound slowly drifts from the picture"* — *"Across {titles}: a single
  shift can't fix it; a different copy can."*

Neither stops playback, so neither is critical (285 FR-285-3). `unknown` is not a row. A file newly found `offset` or
`drift` is recorded in `media_history` (`av_sync_found`) for *Since your last visit*.

**FR-317-10 — The title page**, a banner beside 255's (`#avsync-banner`), one block per flagged file, the series
grouped per season — every word from the server (`GET /media/{id}/health/avsync`):
- **What:** *Audio is 160 ms ahead of the picture* (or *behind*; drift: *from +40 ms at 0:20 to +420 ms at 1:40*);
- **What a viewer gets:** *lips move after the words are heard — most visible in close-ups and dialogue*;
- **Who is affected:** *every player — it is in the file, not in the playback*;
- **Suggested fix:** *delay the audio by 160 ms; a remux, no re-encode — the picture and sound are unchanged*
  (drift: *replace the file*); which other tracks it moves with it, and which it leaves (FR-317-11);
- **the command**, in `permCopyBlock`;
- **the evidence**, folded: each window's time, offset and confidence, each time a link that opens the player there.

The measured audio track's row gets a mark (*160 ms early*), like 255's short-track mark. `unknown` files with a
`reason` show one quiet line in the Checks card's file list (*skipped — animation*), never a banner.

**FR-317-11 — The command** (`AvSyncAdvice`, commonMain beside `TrackCoverageAdvice`, pure, tested). Delay = the
verdict's `offset_ms` (positive = audio early = delay by that much; late → a negative `--sync`). Tracks shifted: the
measured one, and every `others` entry with `corr ≥ 0.6` and `|lag_frames| ≤ 1` (in step with it); every other
track is left and named (*"the commentary track is not in step with the main audio and is left as it is"*). Work files
are WorkFiles kinds `AV_SHIFT("avshift_")` and `AV_TAGS("avtags_", "xml")` in `<dir>/.jellystructure/` (311):
- **MKV:** `printf '<Tags><Tag><Simple><Name>JELLYSTRUCTURE_AV_SHIFT</Name><String><ms></String></Simple></Tag></Tags>'
  > '<tags>' && nice -n 19 ionice -c3 mkvmerge -q -o '<work>' --global-tags '<tags>' --sync <id>:<ms> [--sync …]
  '<file>' && mv '<work>' '<file>' && rm '<tags>'` — ids from `mkv_audio_ids`;
- **MP4:** `nice -n 19 ionice -c3 ffmpeg -nostdin -v error -i '<file>' -itsoffset <s> -i '<file>' -map 0:v -map 0:a?
  -map 0:s? -map 1:a:<n> … -c copy -map_metadata 0 -movflags +faststart+use_metadata_tags -metadata
  JELLYSTRUCTURE_AV_SHIFT=<ms> '<work>' && mv '<work>' '<file>'` — shifted streams from input 1, the rest from input 0,
  in the original order (the shape run on the reported broadcast, generalised);
- other containers: no command, *"MKV and MP4 only — remux it to MKV first"*.

Paths shell-quoted (`FileFixCommands.q`); the output is never the input.

**FR-317-12 — Seeding (315) and Radarr/Sonarr (311).** The command writes a **new** file in the work directory and
renames it over the library's name: a hard-linked torrent copy keeps its bytes and keeps seeding; Radarr/Sonarr never
see a half-written file. The advice says so in one line.

**FR-317-13 — Settings → Advanced, one card** *A/V sync* (Settings holds settings only, 285 FR-285-12): the switch
(`[avsync] enabled`), the address (`url`, default `http://avsync:8000`), and one status line from `/health` + `/stat`:
*Measuring on NVIDIA GeForce RTX 2060 SUPER · model syncnet_v2/1* · *Not reachable* · *Can't see /mnt/series —
mount it at the same path as here*. The mount check `/stat`s one known file per library root on save and at start;
any root it can't see turns the check off (no rows, Checks card *off*) until it can.

**Config** (`AppConfig`, beside `encoder`): `@SerialName("avsync") val avsync: AvSyncConfig = AvSyncConfig()` with
`enabled = false`, `url = "http://avsync:8000"`, `timeout_s = 180`. Calls go through `OutboundHttp.withPermit` (the
background class) with that timeout.

## Dev review (2026-10-10, against `main` `d37f1a7a`)

Buildable. It is 255's shape plus a lane and an HTTP client. Fourteen items.

1. **The first draft's mount was wrong.** The household container mounts `/mnt/media:/mnt/media` and
   `/mnt/series:/mnt/series` (`/home/jeggy/jellystructure/docker-compose.yml`), not `/media`. FR-317-1 now mirrors
   jellystructure's mounts, and FR-317-13's `/stat` check catches a mismatch instead of a sweep of 404s.
2. **The lane.** `MediaJobQueue.QUEUE_NAMES = listOf("media", "segments", "subtitles")` (`:1344`); `claimNext`
   (`:497–524`) iterates it for the shared pool. Add `"avsync"` to the names the Jobs routes accept
   (`JobsRoutes.kt:79`), `laneSummaries` (`:391–403`), `emptyQueues`; **exclude it from the shared pool's loop** and
   give it its own claim loop (`claimNext(lanes = listOf("avsync"))`), started only while enabled. The one-worker-per-
   lane rule holds.
3. **`enqueueFileCheck` hard-codes `"segments"`** (`MediaJobQueue.kt` FR-261-1 block, `queries.insert(… "segments",
   key …)`): it takes the lane from the job type (`FileCheckSchedule.laneFor(jobType)`), `avsync` for the new one.
4. **The check's plumbing**, following `SUBTITLES_JOB`'s footprint exactly: `FileCheckSchedule` (`AVSYNC_JOB`,
   `JOB_TYPES`, `jobTypeFor`, `dedupeKey`, `label`) · `FileCheckSteps` (`AVSYNC`, `defaultStep`, `seedAvSync`;
   `ConfigStore.kt:121`'s seeding log line) · `PipelineEngine.rawPipeline` (`:171`) and the step dispatch (`:728`,
   noun *"an A/V sync check"*) · `MediaJobQueue.runFileCheck` (`:803`), `storedChecks` (`:822`, `hasFinding` = the
   verdict is `offset`/`drift`), `groups()` (`:357`, `:367`) · `TrackRoutes.kt:796` (`"avsync"`) · `TitleChecks`
   (`:97` an `avSyncStep`, `STEP_LABELS` *"Sound in step with the picture"*).
5. **The service**, `media/AvSyncService.kt` (255's `TrackCoverageService` shape): `check(unit)` → choose the track
   (FR-317-4), skip with a reason, `mkvmerge -J` for the ids, call the sidecar, store; `AvSyncFlags` (`service`,
   `revision`, `flaggedOrNull()` = path → verdict for `offset`/`drift`) for Triage and the Library filter; constructed
   in `Main.kt` beside `TrackCoverageService` (`:368–371`).
6. **The five Triage sites** (255's list): `TriageRoutes.kt` (two `TriageTypeCount`s after `:518`, the count-cache key
   at `:394` gains `AvSyncFlags.revision`) · `MediaStore.kt:563` (the Library filter) · `DashboardRoutes.kt:57–88`
   (two `Spec`s) and `titlesFor` (`:277`, `:295`) · `TriageDetection` (`avSyncCount`, = `trackCoverageCount`'s rule) ·
   `Library.kt:86` (two labels).
7. **The title page**: `#avsync-banner` beside `#coverage-banner` (`MediaDetail.kt:871`), `loadAvSync(item)` launched
   beside `loadTrackCoverage` (`:1178`), rendered like `loadTrackCoverage`'s block (`:3336–3380`, `permCopyBlock`);
   `MediaApi.avSyncStatus` beside `trackCoverageStatus` (`MediaApi.kt:1017`); the ↻ case beside `:3327`.
8. **Animation by id**: `MediaItem.genreIds`/`tmdbGenreIds` (271) carry TMDB ids; 16 is Animation for films and
   series alike (`SeerrDiscoverService.kt:28/34`). The localised name would miss *Tegnefilm*.
9. **The original language**: `MediaItem.originalLanguage` (TMDB) and `Track.language`; an episode's tracks are the
   `Episode`'s own. The specifier the sidecar takes is `Track.specifier` (`FfprobeRunner.kt:167`, `0:a:N`).
10. **The verdict is computed on read** (FR-317-6) — the first draft stored a state, which a moving zero would have
    left stale across thousands of rows.
11. **The first draft's drift rule** (*"disagree by more than two frames, growing with time"*) could not be coded from
    three windows and would have called *Film T* (spread 2 frames, noise) a drift or nothing. FR-317-7 is the
    replacement, with *Film T* worked through.
12. **The first draft shifted every audio track by the measured amount.** Wrong whenever tracks were muxed from
    different sources (a separately-timed dub, commentary). FR-317-4/11: measure the original-language track, shift
    the tracks that move with it, name the rest.
13. **Publishing**: `publish.yml`'s matrix (`:86–91`) gains `{image: jellystructure-avsync, dockerfile:
    avsync/Dockerfile}` (context `.` — the Dockerfile copies `avsync/measure.py`). It has no Gradle/Konan cache
    mounts, so `check-docker-cache-ids.sh`'s list is unchanged. The image is ~6 GB (PyTorch + CUDA runtime) — opt-in
    by overlay, never pulled by the base compose.
14. **Prototype → service**: `avsync/measure.py` becomes a module (decode · faces · SyncNet · `measure(path, audio,
    others, windows)`), kept runnable as the CLI it is now; `server.py` serves FR-317-2; the envelope cross-correlation
    for `others` is new code (FR-317-4).

### Build order

1. Sidecar: module split, `server.py`, `others` correlation, Dockerfile, overlay, publish entry.
2. Backend: config + Settings card + mount check · migration + `AvSyncService` · lane + the check's plumbing (items
   2–5) · verdict + zero · Triage sites.
3. Frontend: banner, Checks card, Library label.
4. Household: overlay with `/mnt/media`, `/mnt/series`, `device_ids: ['1']`; switch on; watch the first hour of the sweep.

## Later (317b, not this phase)

A one-click **Shift audio** (banner and Dashboard row) through `FileFix`'s machinery: work file → verify (every packet's
hash unchanged, duration within one frame, **re-measured `in_sync`**) → swap → re-check; *Undo* from the tag. Two
files in flight (a batch claim on the `avsync` lane: 8.2 → 5.4 s a file). TensorRT for S3FD; cuDNN autotune with fixed
batch shapes.

## Out of scope

- Container-only checks (start-time gaps, edit lists). The reported broadcast shows they would not have found this.
- Fixing drift (a stretch, `atempo`, re-encode).
- Lip sync on animation, music videos, concerts without close-ups.
- Ravilo/players compensating at playback time.
- The video-crawler's own downloads outside the library: a KVF/DR download is checked once it is copied into the
  library, like any new file.

## Acceptance

1. The reported broadcast as downloaded: `offset`, **160 ms early** (FR-317-7's worked example); a Series warning row *Sound and
   picture out of step* names it; the title page shows the MP4 command and the window evidence.
2. The same episode delayed by 160 ms (the command's number): `in_sync` — measured −1/0 at every window; no row.
3. A cartoon episode: no request sent; `unknown` (*animation*); no row, never counted as clean.
4. The sidecar stopped, or a library root it can't see: Settings says which; the Checks card shows *A/V sync · off*;
   no `av_*` rows; nothing stored.
5. `AvSyncAdvice`: an MKV with three audio tracks of which two are in step → two `--sync` arguments with mkvmerge ids,
   the third named as left; a path with a `'` is quoted; the output is never the input.
6. `AvSyncVerdict`: *Film T*'s nine windows (FR-317-7) → `offset` +200 ms; the broadcast's → `offset`; a −1/0 film → `in_sync`;
   a linear +1 → +6 over five windows → `drift`; one confident window → `unknown`.
7. The sweep on the household server: ≥ 400 files/hour on GPU 1 with nothing playing; it stops for playback; GPU 0's
   transcodes are untouched; GPU 1 stays under 4.5 GB beside an idle Immich; segment detection keeps running.
   — **restated as 7a/7b/7c by the 2026-10-13 amendment (FR-317-19): the rate's denominator is library files, and the
   per-file card cost is the clause measurable without the library.**
8. A film with a Nordic dub and the original track: the original is measured; the dub comes back in step (`corr ≥ 0.6`,
   lag ≤ 1) and is shifted with it — or, if not, is named and left.
   — **restated as 8a/8b/8c by the 2026-10-13 amendment (FR-317-18): the measurement and the rendering are met
   separately; the two coinciding on one real file was ruled to have no case in today's library.**
   — **met whole by the 2026-10-13 amendment, third of the day (DEB-812): the case did exist. One real
   non-animation two-track film reads `offset` +120 ms and renders both branches — the measured track moved,
   the other named, left and given its reason. 8c is not a watch, and the sweep owes it nothing.**
9. *Film T*: `offset`, audio early — and the owner hears it before it is called a finding.

## Verification plan

Unit tests: `AvSyncVerdictTest` (acceptance 6, the zero, the thresholds, the sign), `AvSyncAdviceTest` (acceptance 5,
MP4 ordering, a negative delay), track choice (original / untagged / dub-only), the skip reasons, `FileCheckSchedule`
for the new type and lane. A contract test against a fake sidecar (404, 422, 503, a measured answer). Against the real
sidecar: the reported broadcast and its fixed copy, a dialogue film known to be in sync, a cartoon, an AV1 file, a 4K
HDR remux, a dubbed film. `scripts/check-phases.sh`, `scripts/check-deanonymization.sh`, `scripts/check-workfiles.sh`.

## Amendment (2026-10-12) — the four admin gaps the browser found, and the two rules they turned up

From DEB-722's browser walk of the built phase on an isolated `-p e2e` stack (backend from `main` `171a151b`;
prod untouched, nothing deployed) — findings routed through DEB-742. **The backend is right in every one of
these; what is missing is the admin.** Amended before the Kotlin, per the repo rule.

Two of the seven findings came up as spec-interpretation questions rather than build gaps, and both are settled
here (FR-317-16, FR-317-17) rather than sent to the owner: neither is viewer-visible and neither contradicts
[`constitution.md`](../constitution.md). One of the two turns out not to be a question at all — FR-317-13 had
already decided it, and only the code disagreed.

### FR-317-14 — The `avsync` lane is on the Jobs panel, and the panel stops saying *three*

`GET /api/jobs` already returns `{lane: "avsync", configuredWorkers: 1}` beside media/segments/subtitles
(`MediaJobQueue.kt:442-445`, which correctly reports **its own one worker**, not the shared pool's number).
`ui/Activity.kt` contains no occurrence of `avsync` at all: three hardcoded lane blocks
(`#jobs-worker-line-media|-segments|-subtitles`) and nothing that reads a fourth. FR-317-8's Jobs surface was
not built. So:

- **A fourth lane card**, *A/V sync*, beside the three — same shape, reading the fourth lane from the same
  response. It shows **1 worker**, the lane's own, and never the pool size; the queue depth and the estimate
  (*~2 h 45 min at 8 s a file*) are FR-317-8's wording.
- **The per-file group row carries its own lane.** The line already reads correctly — *Check A/V sync · 1
  waiting · 1 running · 17 done today · 7 findings* — but is badged `segments`, because `laneBadge("segments")`
  is hardcoded on every group row (`Activity.kt:733`) and `JobGroup` has no lane. **Add a lane field to
  `JobGroup`** — additive, nothing retyped or removed (the wire rule) — filled from
  `FileCheckSchedule.laneFor(type)`, and badge the row from it. Today the Running card beside it says `avsync`
  while the group row says `segments`: two badges for one job, disagreeing on screen.
- **The explanatory paragraph** (`Activity.kt:162`) stops claiming *"Three queues share one worker pool"* and
  *"how many of the three queues can be busy at once"*. It describes the shared pool as the pool of the three it
  governs, and names `avsync` as the fourth lane **outside** it, with one dedicated worker, gated on the
  sidecar — which is the whole reason FR-317-8 gave it a lane of its own. A reader of that paragraph must not
  come away thinking the sweep is competing for a shared worker, because it is not.

### FR-317-15 — The measured track's mark survives the tab being opened

FR-317-10's mark (*160 ms early*) never renders. The API supplies it — `audio_stream_index: 3`,
`audio_mark: "160 ms early"` — and the rows carry matching `data-stream`/`data-file`, but `loadAvSync`
(`MediaDetail.kt:3411-3422`) walks `.trk[data-stream=…]` **at page load**, when the Tracks & subtitles tab has
not been opened and the `.trk` count is 0. Nothing re-applies it afterwards. Measured in a browser: 3 rows,
0 marks. It is unreachable by construction, not mistimed by a race.

**The fix is 255's, which solved exactly this:** a store the row renderer reads, not a mutation of rows that do
not exist. `TrackCoverageMarks.byFile` is set by `loadTrackCoverage` and emitted by the row renderer
(`TrackEditor.kt:438`); 317 gets the same pair — the A/V marks stored per file and stream index when the banner
loads, and the Tracks row renderer emitting the mark for the row it is building, whenever that is. The
page-load pass stays (it costs nothing and covers a tab already open on a re-render); it is no longer the only
path.

### FR-317-16 — *off* is said wherever the check cannot run

**Not a spec question.** FR-317-13 already says it — *"any root it can't see turns the check off (no rows, Checks
card off) until it can"* — acceptance 4 already tests it, and `TitleChecks.avSyncStep`'s own KDoc already claims
it (*"the whole line reads off while the sidecar is not reachable (FR-317-13)"*). Only the code disagrees:
`on = step.enabled && service.enabled()`, and `AvSyncService.enabled()` is the config flag alone
(`AvSyncService.kt:181`). So with the sidecar stopped, or a library root it cannot see, the walk read *not yet ·
next run · 3 not measured* — an invitation to wait for a check that cannot happen. The acceptance text is **not**
amended; the Checks card is fixed to match it. It is also the kinder of the two readings, and the one the
Settings card beside it already gives.

- **`avSyncStep` consults readiness, not the flag** — switched on **and** the sidecar answered **and** every
  library root visible, i.e. the condition `avSyncWorkerLoop` already evaluates.
- **It must not cost an HTTP round-trip per title page.** `ready()` is `suspend` and `/stat`s a file per library
  root; `avSyncStep` runs on every Checks card render. So **`AvSyncService` keeps its last readiness answer**
  beside `lastModel` — a value and the moment it was taken — refreshed by the places FR-317-13 already names
  (start, a Settings save) and by `avSyncWorkerLoop`'s existing 30/60 s heartbeat, which today keeps that same
  answer in a local variable. `avSyncStep` reads the cached value and stays non-suspending. Unknown-yet (nothing
  has asked `/health` since boot) reads as **not ready**: the lane is not claiming either.
- **The Settings card's *off* line is rendered where it can be seen.** `AvSyncService.settings()`'s *"Off —
  nothing is measured and nothing is counted."* is unreachable today because `#avsync-status` is nested inside
  the `display:none` `#avsync-on` block (`Settings.kt:726,737`). Move the status line **out** of that block
  rather than deleting the branch: the status line is the one place that says *why* nothing is happening, and a
  card whose switch is off should not go silent on the question. (Nobody is misinformed today — the hint above
  the toggle carries the same sentence — so this one is cosmetic and may land last.)

### FR-317-17 — A second triple whenever the first pass has no steady answer

FR-317-7 needs `DRIFT_MIN_WINDOWS = 4` confident windows for `drift`. FR-317-8 asks for the second triple only
*"when fewer than two of them read clearly"* (`AvSyncService.kt:484`). Each holds on its own; together they make
`drift` unreachable for a **well-measured** file: three windows, all confident, disagreeing — the exact signature
of a steady drift — can only ever come out `unknown`. Measured both ways in DEB-722: three confident disagreeing
windows gave *"the readings disagree — they are neither one steady offset nor a drift"*, and the only route to
`drift` was to make the first pass inconclusive so a second triple was fetched (6 windows, 4 confident → `drift`,
rendered correctly: *+115 ms at 0:30, +286 ms at 1:45*, no command, *replace the file*). Acceptance 6 hands the
verdict five windows directly, so no unit test can see this.

`unknown` is **not** the intended answer for a confident disagreement. A drifting film is one of the two findings
this phase exists to report, and `unknown` is never counted and never shown (FR-317-7) — so a steadily drifting
film would vanish silently, which is worse than the false quiet FR-317-16 fixes. The trigger gets a second arm:

- **A second triple is requested when the first pass yields neither `in_sync` nor `offset`** — i.e. fewer than
  two confident windows (as today) **or** the confident windows are not steady. One extra pass only; there is no
  third, and six windows that still disagree are `unknown` with FR-317-7's sentence unchanged.
- **`DRIFT_MIN_WINDOWS` stays 4.** The dev review's item 10 set it deliberately against calling noise a drift;
  this amendment reaches four windows by measuring more of the file, never by lowering the bar.
- **The sweep's cost.** Only files whose readings disagree pay — in-sync material reads −1…0 and is steady on the
  first pass, so the common case is untouched. At ~8.2 s a file and ~4 s a second triple, acceptance 7's
  ≥ 400 files/hour survives a disagreement rate well above anything the sample suggests; if the sweep's measured
  rate drops under 400 because of this, that is a finding for the owner, not a reason to lower the bar.

### Tests

- `AvSyncVerdictTest`: three confident windows that disagree → `unknown` **with no second pass available**
  (unchanged), and the same three plus a second triple on a line → `drift` — the case acceptance 6 could not see.
- The second-pass trigger itself, against the fake sidecar: a first pass of three confident-but-not-steady
  windows **requests the second triple** (today it does not); a steady first pass **does not**; six disagreeing
  windows end `unknown` and no third request is made.
- `TitleChecks`: with readiness false and the switch on, the A/V sync line reads `off` and no row reads
  *next run* — acceptance 4, which currently passes on Settings and fails on the Checks card.
- The admin has no test harness for the Jobs panel or the Tracks rows; FR-317-14 and FR-317-15 go back on the QA
  queue as a re-walk of DEB-722's items 1, 2, 3, 4 — a fourth card, one badge agreeing with the Running card
  beside it, and a mark that appears when the Tracks tab is opened.

### Acceptance (added)

10. Activity ▸ Jobs & workers with the sweep running: **four** lane cards; the *A/V sync* card says 1 worker; the
    per-file group row and the Running card beside it both read `avsync`; the paragraph above them does not say
    *three*.
11. A flagged file's title page: open *Tracks & subtitles* **after** the page has settled — the measured audio
    row carries its mark.
12. The switch on and the sidecar stopped: the Checks card reads *A/V sync · off*, no row says *next run*, and
    Settings says which — acceptance 4, in full.
13. A file whose first three windows are confident and disagree: a second triple is requested, and four confident
    windows on a line are reported as `drift`.

## Amendment (2026-10-13) — acceptance 7 and 8 restated against real material, and a third *next run* that cannot come (DEB-810)

From the measurement of real library files through prod's sidecar with the backend's own window plan
(evidence in DEB-722's thread; ids and paths deliberately not repeated here). **Neither of the two
acceptance findings is a code defect** — both are numbers this spec asserts that the library, as it stands,
does not produce. Both are **settled here rather than sent to the owner**: neither is viewer-visible, neither
contradicts [`constitution.md`](../constitution.md), and both numbers are dev-authored — the owner's three
decisions of 2026-10-10 were *sweep every file*, *skip Animation*, *GPU is dev's call*, and neither 400 nor
acceptance 8's wording is among them. The same evidence turned up one real admin gap, FR-317-20, which is a
fix and not a question.

### What real material proved (no change asked for)

- **`decode: nvdec` on both codec paths**, including a 2160p HEVC DV/HDR `smpte2084` file — the tonemap path
  keeps hardware decode. This retires the earlier e2e note that read `cpu`: that was the `rawvideo` fixtures.
- **FR-317-8's seeding is right on a real config.** Prod's live `config.toml` carries `[[scan.pipeline]]
  step = "check_av_sync"`, `enabled = true`, `scope = "missing"`, seeded after `check_subtitles`, with
  `avsync_step_seeded = true`. The once-only seeding did what it promised.
- **FR-317-17 earns itself on the first real file.** The reported broadcast's first triple yields one
  confident window → `unknown` → the second triple brings it to three and the `+160 ms` answer. Without the
  12-10 amendment's second arm this file would have come out unmeasured.
- **FR-317-11's measurement, on real dubs.** Eight real Nordic dub tracks across two films: every one
  `lag_frames 0`, `corr 0.630–0.773`, all above `IN_STEP_CORR = 0.6` → the rule says *they move with the
  original*, which is the correct answer for both files, neither of which needs moving.

### FR-317-18 — Acceptance 8 is three clauses, and only one of them is the library's to grant

Acceptance 8 as written wants one real film that is **out of step** *and* **carries a Nordic dub**, so that the
*Suggested fix* can name the tracks that move together. Measured, both candidates, with the backend's own
window plan:

- the **primary dub film** (1080p, original + four dubs) — 6 windows across both triples, **0 confident**
  (face frames 62–451, conf 0.155–1.430) → `unknown`, *"no window read clearly enough to measure"*. Live
  action by eye; the model could not read it anywhere.
- the **backup dub film** (2160p HEVC DV/HDR, same track layout) — first triple 2 of 3 confident, median
  **+1 frame = +40 ms** → `in_sync`, and `wantsMoreWindows` is false, so the code correctly stops there.

So neither renders a banner, and the acceptance has nothing to render in. Svabo's candidate notes say why
there may be no better one: the library's multi-track NORDiC films are nearly all **Animation**, which the
owner's decision skips, and the live-action remainder is animal-led or has no third track.

**The conjunction was never what was under test** — two separable rules were, and they are proven separately:

8a. **The measurement** — on a real multi-track film the original-language track is the one measured, and
    every other audio track gets its `corr`/`lag_frames` and is classified by FR-317-11's rule. **Met** on
    eight real dub tracks (above), including `mkv_audio_ids` read from a real `mkvmerge -J`.
8b. **The rendering** — the advice names which tracks move with the measured one and which is left, one
    `--sync <mkvmerge id>:<ms>` per moved track. **Met** on the e2e three-track case, which produced the full
    *"moves 2 tracks together / swe is left as it is"* wording and `--sync` arguments carrying mkvmerge ids.
    This half is pure code over stored JSON (`AvSyncAdvice`, commonMain): a synthetic file exercises it
    exactly as a real one would, which is why acceptance 5 already puts it on one.
8c. **8a and 8b on one file** — a dubbed live-action film that is itself `offset` or `drift`. **Not
    satisfiable from today's library, and not a defect.** It stands as a standing watch, not a gap: the first
    real dubbed film the sweep returns `offset` or `drift` for gets this walk. If the sweep finishes and finds
    none, 8c closes as *no case in the library*, with the sweep's own counts as the evidence — not as a
    failure, and not left reading as one.

A real film can also come out **wholly unreadable** (the primary dub film: 6 windows, 0 confident), and
FR-317-17's second triple does not rescue it. That is the correct answer under FR-317-7 — `unknown`, never
counted as clean — and it must not be read as *in step*. Where it is said is FR-317-20.

### FR-317-19 — Acceptance 7's rate names its denominator, and gains a clause that is measurable today

Measured sidecar compute, one file at a time on an otherwise idle GPU 1, real files, 3 × 45 s windows: **9.91 ·
13.43 · 17.78 · 20.17 seconds** per triple (1080p h264 broadcast two triples, the 1080p dub film, the 2160p
DV/HDR film). Call it 10–20 s, ~15 s in the middle ⇒ **~180–360 measured files/hour**, before the backend's own
`ffprobe` per file and `mkvmerge -J` per matroska. Acceptance 7 asks for ≥ 400/hour, which is 8.25 s a file.

The ≥ 400 came from the research report's `--one-file` bench (14 files, 115 s, **8.2 s** a file, 3.9 GB VRAM
⇒ ~440/h ⇒ ~22 h), and that bench and this measurement are not timing the same work — which is the whole
discrepancy:

- **content, not code**: the detector's cost follows the face track, and the bench's own sample is not the
  library's mix (62–451 face frames on one of these files against four figures on another);
- **per run against per request**: the bench's process set up once and answered fourteen times; the built
  sidecar answers one HTTP request per file;
- **and the denominators differ.** ~440/h counted *measured* files. Acceptance 7's sentence is about *the
  sweep*, whose denominator is **library** files — and the skips (animation, `too_short`,
  `no_original_track`) are written straight to `file_av_sync` and never reach the card, while a file that
  answers on its first triple never pays for a second. Both of those cut the file count, not the per-file cost.

The number is therefore not wrong so much as **undenominated**. Restated, with the measurable part separated
from the part only the sweep can settle:

7a. **Per-file card cost** — a 3 × 45 s triple costs **≤ 25 s of GPU 1** on an idle card, for 1080p and for
    2160p, hardware-decoded on both codec paths. **Met**: 9.91–20.17 s on four real files, `decode: nvdec`
    throughout. This is the clause that is falsifiable without the library, and it is the one a regression in
    the sidecar would break.
7b. **The sweep's rate is a rate over library files, skips included**, and ≥ 400/hour stays the target the
    ~22 h estimate came from — **settled by the sweep and by nothing else**, because both of its unknowns are
    properties of the library rather than of the code. At ~15 s a measured file, ≥ 400 library files/hour needs
    roughly **two files in five to be skipped**; the sweep reports both numbers directly (`reason` rows against
    measured rows in `file_av_sync`), so its first hour answers it. A measured rate under 400 is **a finding
    for the owner, not a build defect** — the rule the 12-10 amendment already set for FR-317-17's extra pass.
7c. **Unchanged, and still the pass/fail part**: the sweep stops for playback; GPU 0's transcodes are
    untouched; GPU 1 stays under 4.5 GB beside an idle Immich; segment detection keeps running.

**If the owner wants 400 rather than an answer about 400**, the route is not a tune: FR-317-8 gives the lane
one worker deliberately, and the research report's two-in-flight (5.4 s a file, 87 % SM) is already in
*Later (317b)* — at a **6.5 GB** VRAM peak, which breaches 7c's own 4.5 GB ceiling beside Immich. That makes
concurrency an owner trade rather than dev's call, and it is recorded here as a 317b candidate with its
number, not decided.

### FR-317-20 — A file that was measured and could not be read does not say *next run*

The unreadable real film above has no `reason` (one is set only when **nothing was measured** — FR-317-5), so
it is a current row whose verdict is `unknown`. In `TitleChecks.avSyncStep` such a row's `state` is
`"unchecked"` (correctly — never *clean*), and `unchecked > 0` makes the **step's** `nextDue` read *next run*
(`TitleChecks.kt:303-307`), while the row's own line already reads *when the file changes* — which is the
truth, since `recheckUnchanged = false` means no next run will touch it. Two lines on one card, disagreeing,
and the card's version is the one that invites a wait for a check that cannot happen. This is the same defect
FR-317-16 fixed for a stopped sidecar, arriving by a second door; a skipped cartoon reaches it too, so it is
not confined to unreadable films. Derived from the built code against a now-proven real case, not yet seen in
a browser — the re-walk below confirms it.

- **The step's *next run* counts only rows with no current result.** A row that has been measured — whether
  it came out a finding, clean, skipped, or unreadable — is settled; with no unmeasured row left the line
  reads *when a file changes*, as the per-file rows already do.
- **The card's detail distinguishes the two.** *N not measured* today covers both *not measured yet* and
  *measured, nothing readable*. Split it: **not measured yet** for rows with no result, and for the settled
  ones the words the verdict already carries — `AvSyncVerdict.why`'s *"no window read clearly enough to
  measure"* (`AvSync.kt:187`) and `AvSyncSkip`'s *"skipped — …"* (`AvSync.kt:61-63`), which FR-317-10 already
  puts on the file's own quiet line. Nothing new is written and no new wording is invented.
- **`outcome` is untouched**: a title with an unreadable file stays *partial*, never *clean* (FR-317-7).

### Tests (added)

- `TitleChecks`: a file with a current `unknown` row and no `reason` → the step reads *when a file changes*,
  not *next run*, and the detail says the window-confidence sentence rather than *not measured*; a file with
  `reason = animation` → the same, with the skip sentence; a file with **no** row → *next run*, unchanged.
- No new sidecar or verdict test: FR-317-18 and FR-317-19 change no behaviour. 7a is a measurement, recorded
  above; 7b and 8c are the sweep's and the library's to answer.

### Acceptance (restated and added)

- **7 is replaced by 7a/7b/7c above.** 7a is met; 7b awaits the sweep (the owner's call to start); 7c is
  unchanged and unverified.
- **8 is replaced by 8a/8b/8c above.** 8a and 8b are met; 8c is library-blocked and stands as a watch.

14. A title whose only file was measured and could not be read: the Checks card's A/V sync line reads *when a
    file changes* — never *next run* — says in the card's own words that no window read clearly enough, and
    the title is not counted clean.
15. A cartoon title, the sweep having skipped it: the same line, with *skipped — animation* and no *next run*.

## Amendment (2026-10-13) — advice that is told what it cannot do, and never half a command (DEB-833)

From the second browser walk of the admin on prod (`v1.51-58-gf9029c3b`, DEB-722 → DEB-833; the four gaps of
the 10-12 amendment all confirmed fixed). One real file reads `offset` **+120 ms**, renders its whole banner —
headline, *What a viewer gets*, *Who is affected*, *Suggested fix: delay the audio by 120 ms* — and then shows
**no command and no Copy block at all, and nothing saying why**. Evidence and the verified chain are in
DEB-833; ids and paths deliberately not repeated here.

The chain's first link is **right and stays**: `AvSyncAdvice.command` refuses a matroska `--sync` without
mkvmerge's own ids, *"without mkvmerge's own ids a --sync would move the wrong track"*. The defect is
everything downstream of that refusal — the ids were **present and complete** in output that was thrown away,
the throw-away was silent, and the page went quiet exactly where it is hardest to be quiet.

Four decisions, all **settled here rather than sent to the owner**: none is viewer-visible (the admin is the
household's own), none contradicts [`constitution.md`](../constitution.md), and the owner's three decisions of
2026-10-10 were *sweep every file*, *skip Animation*, *GPU is dev's call* — wording of advice is not among
them. FR-317-24's and FR-317-25's sentences are therefore the PO's, as asked for on DEB-833.

### What the evidence establishes (and the one thing it does not)

`mkvmerge -J` on that file exits `0`, writes empty stderr, and returns **3844 bytes of JSON cut mid-string
inside a subtitle track's `track_name`** — reproduced twice. `parseMkvAudioIds` does a whole-document
`Json.parseToJsonElement`, so the malformed tail discards the whole document; three successive
`runCatching{…}.getOrNull()` / `.getOrDefault(emptyList())` swallow it; `mkv_audio_ids` is stored `null`.
**Both audio ids were complete in those 3844 bytes** — the cut falls hundreds of lines later on track 6, a
subtitle — and the file needed `0:a:0 → 1`, which was sitting in the discarded output.

**Not established: who truncates.** Either `mkvmerge` emits a short document at exit 0, or our own capture
does. `captureShell` (`TrackCoverageService.kt:240-247`) reads `fgets(buf, 4096)` and appends
`buf.toKString()` — a pipeline that cannot carry a NUL byte and mangles a multi-byte character split across
the 4096-byte boundary, and 3844 is near enough a buffer to be worth excluding. **This is not a gate on any
of the four fixes below**: each is correct whichever answer comes back. It goes on the QA queue as a
two-command check (`mkvmerge -J <file> | wc -c` against the same output redirected to a file, exit code and
stderr captured), because if the answer is `captureShell` then the blast radius includes phase 255's track
coverage and every `FileFixCommands` read, and that is a finding of its own and not 317's.

**Ruled out as a shortcut, explicitly:** ffprobe's stream index must **never** stand in for an mkvmerge track
id. On this very file the audio ordinal is `0:a:0` and the mkvmerge id is `1`; the two agree here only because
one video track precedes it, and mkvmerge numbers tracks it accepted, in its own order. Substituting one for
the other is precisely the mis-attribution the refusal exists to prevent.

### FR-317-21 — The id parse reads a truncated document as far as it is whole, and no further

`parseMkvAudioIds` stops being whole-document. It walks `tracks` in order and takes the ids of **complete
track objects only**, stopping at the first object it cannot read in full and returning what it has:

- an id is **either read in full from a complete track object, or not read at all** — the safety property the
  refusal depends on is unchanged, and nothing is inferred past the cut;
- the `0:a:N` ordinal stays correct by construction, because audio ordinals count audio tracks in document
  order **from the start**: a prefix of the track array yields a prefix of the ordinals. An incomplete object
  must not advance `n` — and because the walk stops there, no later track exists to be mis-numbered;
- the result is a **possibly short, never wrong** map. A map shorter than the file's audio track count is a
  normal outcome and FR-317-22 is what makes it safe;
- the existing behaviour is preserved where it was already right: output that is not JSON at all, or has no
  `tracks` array, still yields an empty map.

This recovers both ids on the real file, and `--sync 1:120` with it. Implementation is free to tolerate the
tail however it likes (incremental decode, or trimming to the last complete object and re-parsing) as long as
the three properties above hold; the function stays pure and in the `companion object`, so the decision is
tested rather than eyeballed.

While the line is open: the probe is run only for `.mkv` (`AvSyncService.kt:520`) while `command` accepts
`mkv`/`mka`/`mks`. Harmless today — an `.mka`/`.mks` carries no video, so no window ever reads a face and no
such file is ever flagged — but the two lists should read from one place rather than drift apart.

### FR-317-22 — A command is whole or it is absent; it is never partial

`command` currently drops unknown ids (`mapNotNull { it.mkvId }`) and refuses only if **every** id is missing.
With FR-317-21 making short maps ordinary, that becomes the dangerous branch: a file whose first two ids were
recovered and whose third was past the cut would get prose saying *"it moves 3 tracks together"* and a shell
command moving two — one page saying two different things, which `AvSyncAdvice`'s own contract forbids
(*"so a page can never say one thing in prose and another in the shell"*).

**The rule: if any track the prose says moves has no mkvmerge id, there is no command.** The refusal widens
from *no ids at all* to *fewer ids than moved tracks*, and FR-317-24 says why on the page. Half a fix applied
to a file is worse than no fix offered, and a `--sync` that silently leaves a dub behind re-creates the
problem one track over.

The mp4 branch is unaffected: it maps by ffmpeg specifier and needs no mkvmerge id.

### FR-317-23 — A failed id probe leaves a trace, and the two silences are told apart

Today the failure is invisible in the logs and indistinguishable in the data from a container that has no
command form at all.

- **One `WARN`, on the integrity channel, when the probe returns nothing usable for a matroska file**, naming
  the path, the **byte count of what came back**, and the exit status — the byte count because that single
  number is what would have answered the *who truncates* question above without a container shell, and the
  next occurrence should not need one either.
- **No new column and no migration.** The marker is already derivable and is defined here so that both ends
  read it the same way: for a matroska row that has been **measured**, `mkv_audio_ids IS NULL` means *the
  probe ran and could not be read* — the probe is attempted on the same write path for every `.mkv`, so there
  is no third state to confuse it with. A non-matroska row's null means nothing and is never read.
- **The retry is the one that already exists.** `recheckUnchanged = false` means no sweep will revisit the
  file, and the lane must **not** gain a re-probe pass: the GPU measurement is the expensive part and it
  succeeded. The Checks card's existing ↻ (force) re-runs the whole check including the probe, and FR-317-24's
  sentence tells the operator that this is the retry.

### FR-317-24 — Where there is no command, the banner says why, in one sentence

FR-317-11 promises *"the exact command where one exists"* and names the words for the unsupported container
(*"MKV and MP4 only — remux it to MKV first"*) — **those words are in this spec and in no Kotlin**; the
no-command branch renders nothing at all. A phase whose whole purpose is explaining a problem in words must
not go silent precisely where the fix is hardest.

The server carries the reason — the admin renders server text only (no derived state in the frontend) — in a
**new, additive** DTO field `no_command_reason` on `AvSyncFile`, nullable, beside `command`. Nothing is
removed or retyped: an installed reader that does not know the field is unaffected, and the admin is the only
reader. It is populated from `AvSyncFix`, which gains one nullable `noCommandReason` built in the same place
as the command, so prose and command cannot disagree. The admin renders it where `permCopyBlock` would have
gone, in the banner's own quiet style, never as a second warning badge.

Two cases are live, and they get these words:

- **A container neither mkvmerge nor an mp4 stream copy fits** —
  *"No one-line fix for this container: the shift can be written into MKV and MP4 only. Remux it to MKV first
  and this check will offer the command."*
- **A matroska file whose own track numbers could not be read** (FR-317-21 recovered none, or FR-317-22
  recovered too few) —
  *"No command yet: this file's own track numbers could not be read, and a shift without them could move the
  wrong track. Re-run the check with ↻ above; if it keeps failing, the file's header is what to look at."*

Two cases are deliberately **not** given a line:

- **`drift`** — its `suggestion` already explains the absence in full (*"a single shift cannot fix a drift …
  replace the file"*). A second sentence saying there is no command would be the same sentence twice.
- **A zero shift** — `command` refuses `delayMs == 0`, and that branch is **unreachable from a banner**:
  `OFFSET` needs a median of `≥ EARLY_FRAMES (2)` or `≤ LATE_FRAMES (−4)` frames, so a flagged offset is never
  0 ms. No wording is invented for a state that cannot be rendered; if a later change makes it reachable, it
  arrives as a finding, not as dead prose.

### FR-317-25 — A left-out track names the reason it was actually left, and no other number

Live on prod: *"Untagged EAC3 (0:a:1) is not in step with the main audio (+0 ms of it, correlation 0.546) and
is left as it is."* It leads with a lag that reads as **perfectly in step** and then excludes the track anyway.
The lag is not the reason — `corr 0.546` is, against `IN_STEP_CORR = 0.6` — and a lag measured at 0.546
correlation is not a measurement at all. A reader stumbles, and the stumble is the page's fault.

**The rule: say the test the track failed, and do not print a number that was not the reason.** One clause per
left track, by which arm of `inStep` failed:

- **correlation below the bar** (whatever the lag) — *"<label> could not be matched against the main audio
  (correlation 0.55, below 0.6), so it is left as it is: it is different audio, not a delayed copy of it."*
  The lag is **not shown**;
- **in step by correlation but out of step by itself** (`corr ≥ 0.6`, `|lag_frames| > 1`) — *"<label> is itself
  +120 ms out of step with the main audio, so it is left as it is: one shift cannot put both right."* The
  correlation is **not shown** — it passed;
- **nothing comparable** (`lag_frames` null) — *"<label> could not be compared with the main audio, so it is
  left as it is."*

Correlation is printed to **two decimals** and always against its bar (*below 0.6*), so the number means
something to a reader who has never seen the constant. The clauses still join with `"; "` and the first
character still uppercases — the shape of the sentence is unchanged, only which facts it chooses.

### The readiness window costs a handful of job rows, and that is left alone (no change asked for)

Stopping the sidecar mid-sweep to reach acceptance 4's red branch, the lane kept claiming for up to
`READY_MAX_AGE_S = 300` and **5 jobs failed** on `CURLE_COULDNT_CONNECT` before *"av sync lane: the sidecar is
not ready — holding"*. That is FR-317-16 working as designed: readiness is a cached answer with a stated
staleness, and the alternative — a `/health` per claim — is a round trip per file for a condition that changes
once a month. The failed claims store no row, so `scope = "missing"` re-queues them on the next run; the
sweep self-heals.

**Decision: neither the window nor the log line changes.** Narrowing the window buys a handful of `[WARN] job
… failed` lines back at the cost of a request per file; softening the line hides a real connect failure, which
is the one thing the lane's log must not do. Recorded here so the next reader of those warnings finds the
answer instead of re-investigating: **during a sidecar blip, up to a few minutes of `avsync` job failures are
expected, leave no rows, and re-queue themselves.**

### Tests (added)

- `parseMkvAudioIds` (pure, `linuxX64Test` beside the three existing cases): the **real 3844-byte truncated
  document**, trimmed of anything identifying, as a fixture → both audio ids, correctly ordinalled; a document
  cut **inside** an audio track object → that track's id absent and the walk stopped, never a guessed id; a
  document cut after the first audio track → one id; unchanged: not-JSON → empty, no `tracks` → empty, `tracks:
  []` → empty.
- `AvSyncAdvice.command`: three moved tracks and two recovered ids → **null**, not a two-`--sync` command
  (FR-317-22); three moved and three recovered → three `--sync` arguments, unchanged; an mp4 with no mkvmerge
  ids at all → the command is still built (the mp4 branch needs none).
- `AvSyncAdvice.advise`: a matroska file with too few ids → `command == null` **and** `noCommandReason` is the
  track-numbers sentence; an `.avi` → the container sentence; a `drift` row → `command == null` and
  `noCommandReason == null`; an offset with every id → both unchanged from today.
- `AvSyncAdvice` left-track wording (FR-317-25): `corr 0.546, lag 0` → the correlation clause, **no `+0 ms`
  anywhere in the string**; `corr 0.80, lag 3` → the lag clause, **no correlation in the string**; `lagFrames
  == null` → the third clause; `corr 0.546` renders as `0.55`.
- No sidecar test and no migration: nothing changes in the measurement or the schema.
- The banner's rendering of `no_command_reason` has no admin harness, as with FR-317-14/15 — it goes on the QA
  re-walk as acceptance 16 below.

### Acceptance (added)

16. The `offset` matroska file from this walk, re-checked: the banner renders a `--sync` command in a Copy
    block. (If the truncation is still there, FR-317-21 recovered the ids from the prefix; if it has gone, the
    ids were read whole — either way a command.)
17. A matroska file whose track numbers genuinely cannot be read: **no command**, and one sentence saying the
    track numbers could not be read and that ↻ is the retry — never a bare silence, and never a `--sync` that
    moves fewer tracks than the prose names. One `WARN` on the integrity channel carrying the path, the byte
    count and the exit status.
18. A flagged file whose second audio track is excluded by correlation: the sentence names the correlation
    against its bar and **does not quote a lag**; one excluded by lag alone names the lag and **does not quote
    a correlation**.

## Amendment (2026-10-13, second of the day) — who truncates is the C locale, and an error document is not an empty file (DEB-848)

The amendment above left one question open and said it was not a gate: **who truncates `mkvmerge -J`** —
mkvmerge at exit 0, or `captureShell`'s `fgets(4096)`. It was put on the QA queue as a two-command check
(DEB-843). The answer came back **neither**, and it is wider than this phase: the backend container carries no
`LANG` and no `LC_*`, so every process it spawns runs in the C locale, and MKVToolNix in the C locale cannot
handle a non-ASCII path or a non-ASCII string it must emit. That is now
[**phase 318**](phase-318-the-container-speaks-ascii-and-every-mkvtoolnix-call-pays-for-it.md), which carries
the environment variable, the measured blast radius across every MKVToolNix call site, and the evidence. This
amendment carries only what is 317's.

### What the answer changes here, and what it does not

- **`3 844` was never evidence of a buffer.** The cut falls at the first non-ASCII byte of the first string
  mkvmerge emits that carries one — in that file, a subtitle track's `track_name` on track 6. It is not
  4 096-aligned because it has nothing to do with 4 096. **`captureShell` is cleared**; so is the suspicion
  that mkvmerge emits short documents at exit 0 for its own reasons.
- **Phase 255 is cleared too, and should not be re-measured.** The 10-13 amendment's worry — *"if the answer
  is `captureShell` then the blast radius includes phase 255's track coverage"* — does not land: 255's three
  probes are `ffprobe`, and `ffprobe` is proven immune in the same locale on the same file (318's table).
  `captureShell` is shared; MKVToolNix is not.
- **The 92 non-ASCII-path files are not a parser problem at all.** `mkvmerge -J` on such a path returns a
  ~115-byte *valid JSON error document* at **exit 2** — there is no `tracks` array and no truncation, because
  there was never an identification. **No change to `parseMkvAudioIds` can reach those files**; only 318's
  locale can, and it reaches all 135.

### FR-317-21 stands exactly as written, and is still built

The routing question DEB-848 put to the PO was whether FR-317-21 still lands, given it only covers the
smaller class. **It does, unchanged**, and the reasoning is recorded so it is not re-litigated:

- it is a **safety property, not a workaround for this bug**. *"An id is either read in full from a complete
  track object, or not read at all"* is the right rule whatever made a document short — a killed process, a
  full pipe, a future mkvmerge change — and the version it replaces throws away a whole good prefix for a bad
  tail, which is wrong on its own terms;
- it and 318 reach **different files**: 318 is the only thing that reaches the 92, FR-317-21 is the only thing
  that reaches a document that arrives short for any other reason, and neither substitutes for the other;
- after 318, the C-locale truncation stops happening, so **FR-317-21 becomes a belt on a fixed brace** — which
  is the correct posture after a fault that was silent for an unknown number of months.

Two corrections to its own prose, which was written before the cause was known:

- *"This recovers both ids on the real file, and `--sync 1:120` with it"* was true **because the locale was
  wrong**. After 318 that file returns a complete 23 125-byte document and the ids are read whole, so
  acceptance 16 is satisfied by 318 rather than by the new parse. Acceptance 16 already allowed for both
  outcomes and needs no restating; this says which one it will be.
- the test fixture must **not** be trimmed out of the real file. A truncated document is reproducible on
  demand under `LC_ALL=C`, and 318's synthetic pair produces one that carries no library string at all — that
  is the fixture to capture, in this public repository.

### FR-317-26 — An identification that failed is not a file with nothing in it

FR-317-21 preserves today's behaviour for output with no `tracks` array: an empty map. That is **safe** and it
is **mute**, and the ~115-byte error document lands exactly there. A refusal to identify and a container that
genuinely has no tracks then look identical in the data, in the logs and on the page.

`parseMkvAudioIds`, or the caller around it, tells the two apart: **a document whose `errors` array is
non-empty is a failed identification**, never an empty one. The map is still empty — nothing is inferred —
but the failure is reported as a failure, and FR-317-23's `WARN` carries mkvmerge's **own first `errors[]`
line** so the next reader is told *"could not be opened for reading"* instead of being left to guess. The
distinction is pure and testable: `{"errors":[…],"warnings":[]}` → failed; `{"tracks":[]}` → an empty
container; not JSON at all → failed.

### FR-317-23's exit-status clause is dropped, and the error line takes its place

FR-317-23 asked the `WARN` to carry *"the path, the byte count of what came back, and the exit status"*. **The
exit status cannot be had where that `WARN` is written**: `captureShell` (`TrackCoverageService.kt:240-247`)
discards `pclose`'s return value, and the call pipes `2>/dev/null` — so neither the status nor stderr reaches
Kotlin. The two ways out are to make `captureShell` return a status, which changes a function phases 255 and
the work-file sweep also call for one caller's benefit, or to use what is already in the document.

**Decision: the `WARN` carries the path, the byte count, and the first `errors[]` line where there is one.**
It is strictly more useful than `exit=2` — it names *why* mkvmerge refused — it needs no change to a shared
function, and the byte count stays, because that single number is what distinguishes a refusal (~115 bytes)
from a truncation (anything larger) at a glance. `2>/dev/null` stays: letting stderr into the pipe would
corrupt the JSON it is being parsed from.

### The sweep is held until 318 lands

317's library sweep has not been started, and the ordering is worth a factor of nine: measured after 318, the
~1 247 non-ASCII-path matroska files are identified correctly the first time and the recovery set stays at the
**135 rows that exist today**; measured before it, every one of them stores a `NULL` id map and the recovery
set becomes the whole sweep again. The reasoning and the recovery mechanism are 318's (FR-318-3, FR-318-4);
FR-317-23's rule that the lane gains **no** permanent re-probe pass is unchanged, because a one-off recovery
of a known finite set is not a standing mechanism.

### Tests (added)

- `parseMkvAudioIds`: the **115-byte `errors` document from 318's synthetic non-ASCII-path run** → an empty
  map **and** a failed identification, not an empty container; `{"tracks":[]}` → an empty map and **not** a
  failure; a document carrying both `errors` and a usable `tracks` prefix → the ids are still read (mkvmerge
  warns about plenty of files it identifies perfectly well).
- The `WARN`'s wording is asserted on the error document's first line, not on an exit status.
- No new fixture from a library file, and no migration.

### Acceptance (restated and added)

- **17 is restated**: the `WARN` carries the path, the byte count and mkvmerge's first error line — **not** an
  exit status. The rest of 17 is unchanged.

19. A matroska file at a non-ASCII path, on a backend **without** `LANG` set: the Checks card shows the
    no-command sentence rather than silence, and one `WARN` naming the path, ~115 bytes and *"could not be
    opened for reading"*. The same file on a backend **with** `LANG=C.UTF-8`: a `--sync` command in a Copy
    block. The second half is 318's acceptance 6 seen from this phase's page.

## Amendment (2026-10-13, third of the day) — 8c is met: the library did have the case (DEB-812)

The 10-13 amendment above (FR-317-18) ruled acceptance **8c** *"not satisfiable from today's library"* and left
it as a standing watch on the first dubbed film the sweep flags. **That ruling is wrong, and it was already
wrong when it was written** — the file that closes 8c is the same file the DEB-833 amendment is about, measured
in the second browser walk on prod (`v1.51-58-gf9029c3b`) an hour earlier. FR-317-18 was reasoning from Svabo's
two-candidate shortlist; the walk had widened the search to eight candidates and found a ninth answer.

**The case.** Jellyfin id `7b6266539e4bbe415fbbb05aa1cf02d9` — non-animation, matroska, **two** audio tracks
(one language-tagged, one untagged, both EAC3), reading `offset`, `offset_ms: 120`, `audio_mark: "120 ms early"`.
Out of step *and* multi-track: the conjunction 8a and 8b never met. No title here; the id is the reference
(this repository is public).

**8a and 8b coinciding on it, read in a browser:**

- the *Suggested fix* moves the measured track and names it — `"dan" EAC3 (0:a:0)`;
- the other track is **named, left, and given its reason** — *"Untagged EAC3 (0:a:1) is not in step with the
  main audio (+0 ms of it, correlation 0.546) and is left as it is"* — which is FR-317-11's *left as it is*
  branch on real material, the half the e2e three-track case could only simulate;
- the measured track's row on *Tracks & subtitles* carries the `120 ms early` mark and the untagged row carries
  none (FR-317-15, verified separately on DEB-722).

**Both arms of FR-317-11 are now proven on real audio, not one.** The eight Nordic dub tracks of FR-317-18
(`lag_frames 0`, `corr 0.630–0.773`) are the *moves with the original* arm; this file is the *left behind* arm.
Worth recording because the deciding factor here is **correlation, not lag**: the excluded track's lag is `0`
frames, so by lag alone it would have moved. It is excluded on `corr 0.546` against the `0.6` bar — a thin
margin, and the reason FR-317-25 insists a left-out track names the arm of `inStep` it actually failed.

**What this does not depend on.** This file renders no command and no Copy block, because its `mkvmerge -J`
came back truncated and `mkv_audio_ids` is null — that is DEB-833 and, via DEB-848, the container's C locale in
phase **318**. It is independent of 8c: the *wording* 8c is about is fully rendered, and a missing command must
not be read as 8c failing. 8c closes on the advice, not on the shell line.

**Consequences for the acceptance record:**

- **8c is met.** It is **not** a watch, and it does **not** close as *no case in the library* — that branch of
  FR-317-18 is spent and must not be exercised.
- With 8a and 8b already met, **acceptance 8 is met whole**.
- **The sweep owes 8c nothing.** 7b still waits on it; 8c no longer does. Nothing here asks for the sweep to
  start, and nothing here is the owner's call.

### Tests (added)

None. This amendment changes no behaviour and no Kotlin — it corrects an acceptance ruling against a
measurement that already existed. FR-317-11's rule, FR-317-25's wording and FR-317-15's mark are unchanged.

## Amendment (2026-10-13, fourth of the day) — a decode that never returns stops the sweep, and nothing says so (DEB-897)

The sweep ran for the first time on 2026-10-13 and **died at 02:39:19Z with 4 079 files still queued**, while
the Jobs panel and `/api/health` both went on reporting `running: 1` for the `avsync` lane. It was found four
hours later, by someone watching the sweep for an unrelated verification (DEB-722), and not by anything this
phase built. Three bounds are missing — one in the sidecar, one in the lane, one in what the backend says
about itself — and **any one of them would have turned a four-hour silent stall into a visible failure**.

### What was found

Two `ffmpeg -hwaccel cuda` decoders inside `jellystructure-avsync`, both started 04:39:20, in interruptible
sleep at **0 % CPU** for over four hours, holding **1 820 MiB of GPU 1** with the card at 0 % utilisation.
They are one `/measure` call's prefetch (`AVSYNC_DECODERS` window fetches run at once, three by default), and
that call never returned. Downstream of it:

- the single-flight `lock` in `server.py` is **still held**, so `/health` answers `"busy": true` and **every
  `/measure` answers `503`, for as long as the process lives**;
- `job mj-1791844302-987` (`check_av_sync`) has been cycling *re-queued → resumes* ever since — ~14 cycles
  every five minutes, at **24.9 % of a core**, forever;
- the lane's one dedicated worker is pinned on that row, so **none of the other 4 079 rows can be claimed**;
- and nothing anywhere says *failed*: no `file_av_sync` row with a reason, no Dashboard finding, no unhealthy
  status, and a lane that reports itself as working.

**One file that cannot be decoded therefore stops the whole library, silently and permanently.** That is the
fault; the particular file matters much less than the three missing bounds.

### The mechanism, read in the code — and one correction to the report

- **`decode_video` can wait forever.** `avsync/measure.py:129-137` spawns ffmpeg with both pipes at
  `bufsize=0`, reads **stdout only** until the frame buffer is full or EOF, and only then reads stderr.
  There is no timeout on `readinto`, none on `p.wait()`, and none on `fu.result()` in `measure()`
  (`measure.py:363`) — and `ThreadPoolExecutor`'s `with` block joins its threads on the way out, so even an
  exception cannot get the handler thread out while a decoder is stuck. The **leading candidate** for *why*
  these two stopped is the stderr pipe: ffmpeg blocking on a full stderr buffer while we block on stdout is a
  mutual wait that matches both processes being asleep at 0 % CPU with the card idle, and `-v error` keeps
  stderr small right up until a stream starts emitting an error per frame. A GPU-side wedge on a card that
  has already thrown **10 CUDA OOMs** is the other candidate. Whichever it is, the deadlock is in *our* code
  rather than ffmpeg's, and the fix below does not depend on which.
- **A client timeout cancels nothing.** `[avsync] timeout_s = 180` bounds the backend's Ktor request
  (`AvSyncService.kt:335`). When it fires, the connection closes and **`measure()` goes on running**,
  holding the lock. The 180 s bound is therefore a bound on the *caller*, never on the work, and the sidecar
  has no bound of its own at all.
- **The report's "no backoff" is not quite right, and the real defect is worse.** There *is* a wait:
  `MediaJobQueue.kt:863-867` sleeps `retry_after_s` (20 s, clamped 1–120) before re-queueing in place, which
  is what the observed ~21 s cycle is. So it is not a hot spin on the HTTP call. What is missing is a
  **give-up**: the retry is unbounded, and 25 % of a core consumed by one re-queue every 21 s is far more
  than the claim path should cost — that number wants attributing (the scheduler's per-pass read over ~4 954
  files is the first place to look) and is not itself the fault. **The fault is that the loop has no end.**

### FR-317-27 — The sidecar's `/measure` is bounded, and the lock never outlives the work

No decode may wait indefinitely, and no answer may be withheld indefinitely.

- **Every ffmpeg the sidecar spawns is killed if it stops producing.** `decode_video` and `decode_audio` get
  a **no-output watchdog**: if nothing has been read for `AVSYNC_DECODE_STALL_S` (default **60 s**, env, like
  `AVSYNC_DECODERS`), the process is killed — the **whole process group**, `SIGKILL` after a `SIGTERM` grace
  — and the fetch raises.
- **`stderr` is drained concurrently with `stdout`**, or redirected to a temporary file, so that the two
  pipes can never wait on each other. This is required whatever the watchdog does: a watchdog that fires
  every time on a file that would otherwise have measured fine is a regression, not a fix.
- **The whole call is bounded too.** `/measure` carries a ceiling of `AVSYNC_MEASURE_MAX_S` (default
  **900 s** — a file is ~8 s and the worst measured file is far under a minute, so this is a backstop, not a
  schedule). On expiry every child is killed, every decoder thread is abandoned, and the route answers.
- **A watchdog kill is answered, as a failure, with its reason.** `500` with
  `{"error": "decode stalled after 60s on window 1620.0s"}` — not `422`: `422` means *this file cannot be
  decoded* and already carries a settled meaning in FR-317-16/FR-317-20's wording, while a stall is a fault
  of the run. The backend stores no reading for the file and the row **fails**, visibly.
- **The lock is released in every one of those paths.** It is held by the handler and released in `finally`
  today; the requirement is that no path can reach that `finally` later than the ceiling above.

### FR-317-28 — `/health` says how long it has been busy, so busy and wedged are told apart

`/health`'s `busy` boolean cannot distinguish *measuring the file in front of me* from *wedged since
04:39*. It gains two additive fields, and nothing is removed or retyped:

- **`busy_for_s`** — whole seconds since the current measurement acquired the lock, `0` when idle;
- **`current_path_basename`** — the basename only, never the full path, so an admin status line can say
  *measuring* without this phase starting to print library filenames where it did not before.

The backend reads `busy_for_s` where it has it and treats **absent as unknown** (an older sidecar image
against a newer backend is the normal deployment order here), falling back to FR-317-29's count rule.

### FR-317-29 — A lane that is refused forever fails the row and says which

`503` stops meaning *come straight back* without end.

- **The wait keeps `retry_after_s`** (FR-317-8's rule is right: the lane is the queue, and waiting in place
  is what busy should cost). What is added is a **cap**: after **`AVSYNC_BUSY_GIVE_UP` consecutive busy
  answers for the same job — 30, roughly 10 minutes at the sidecar's 20 s — the row **fails** with *"the
  sidecar never became free (busy 10 min)"*. It is a `Failure`, so it lands in the job history and the
  Dashboard's existing failure surface, and the lane moves to the next row.
- **Where `busy_for_s` is available it decides sooner.** A `busy_for_s` greater than
  `AVSYNC_MEASURE_MAX_S` is a wedged sidecar by definition, and the row fails immediately rather than
  waiting out the count.
- **The give-up does not disable the lane.** The next row is attempted, and it will meet the same wedged
  sidecar and fail too — quickly, loudly, and in a way an operator can read, which is the whole point. A
  rule that stops the lane after *n* consecutive give-ups is **deliberately not specified here**: a lane
  that turns itself off is a second silent state, and this amendment is about removing one.
- **A failed row is due again**, by FR-317-8's existing rule (no stored result ⇒ `NO_RESULT`), so a sweep
  re-run after a restart picks the file up. **It may well wedge on the same input**, which is FR-317-27's
  job to bound, not this one's.

### What is not changed, and one trade settled

- **317b's "two files in flight" stays at one.** The note on DEB-722 is right and this amendment settles it:
  this hang happened with **one** measurement in flight on a card that has thrown 10 CUDA OOMs, and two
  would have been six stuck decoders and twice the VRAM. Until FR-317-27 is built and a sweep has completed
  under it, the concurrency stays at one; *"Later (317b)"* keeps the item and gains this as its condition.
- **No new `[avsync]` config key, and no change to the admin's Settings surface.** The three new bounds are
  sidecar environment variables beside `AVSYNC_DECODERS`, and the backend's cap is a constant. `timeout_s`
  keeps exactly its current meaning.
- **`/api/health`'s top-level `ok` is not flipped by a stalled lane.** Something outside the household may
  be reading it; changing what *unhealthy* means is the owner's call, not this amendment's. The lane's age
  is reported as a number — that is **phase 213**'s `job_queues` block and is amended there (FR-213-8).
- **The operational clear is not ours.** `docker restart jellystructure-avsync` frees the lock and the lane;
  it is a prod service restart, so it goes to the owner. The evidence above is captured, and the sidecar's
  log from 02:00Z should be captured *before* the restart so the earlier 02:39→04:39 gap can still be read.

### Tests (added)

- **A decoder that produces nothing is killed and answered.** A fake `ffmpeg` on `PATH` that writes one
  frame and then sleeps: `/measure` returns `500` with the stall reason inside
  `AVSYNC_DECODE_STALL_S + a margin`, the child is gone (not a zombie, not an orphan in another process
  group), and a following `/measure` **succeeds** — the lock was released.
- **A noisy stderr does not deadlock.** A fake `ffmpeg` that writes well over a pipe's worth of stderr while
  streaming valid frames measures normally, with no watchdog kill. This is the regression test for the
  leading candidate cause, and it fails against today's code.
- **The ceiling fires.** `AVSYNC_MEASURE_MAX_S` set small against a slow fake: the route answers, the lock
  is free, no decoder is left running.
- **`/health` while busy** carries `busy_for_s` rising and a basename, and `busy_for_s == 0` when idle.
- **The lane gives up.** A sidecar stub answering `503` forever: the job fails after the cap with *"the
  sidecar never became free"*, the next row is claimed, and the failed path has **no** `file_av_sync` row.
  With `busy_for_s` over the ceiling in `/health`, it fails on the first answer instead of the thirtieth.
- No migration, no fixture from a library file.

### Acceptance (added)

20. A `/measure` whose decoder hangs is answered as a failure within a minute or so, the sidecar's next
    `/measure` is served, and the file's job shows as **failed** in the job history with the stall reason —
    proven on the fake decoder in tests, and not requiring the real card.
21. With the sidecar wedged (or stopped mid-measurement), the `avsync` lane fails rows and advances rather
    than cycling one row: in a 15-minute window the job history shows failures and `running` moves, and the
    backend's CPU share for the lane is indistinguishable from idle.
22. A sweep restarted after a give-up re-queues every failed file (no stored result ⇒ due), and a file that
    wedges twice fails twice rather than stalling the sweep once.

### FR-317-29 as built (2026-10-13, DEB-902) — the one number the backend cannot be told

The cap and the `busy_for_s` short cut are built as written, in `AvSyncBusyGiveUp` (pure, so both of its
ways to decide are tested without ten minutes of wall clock) and `MediaJobQueue.avSyncBusyAnswer`. Two
facts worth keeping:

- **`AVSYNC_MEASURE_MAX_S` is mirrored in the backend as a constant, not read.** FR-317-28 gives `/health`
  how long the sidecar has been busy, not what its own ceiling is, so the backend compares `busy_for_s`
  against its own `AvSyncBusyGiveUp.WEDGED_AFTER_S = 900` — FR-317-27's default. Raising the sidecar's env
  past that would make the lane give up on a run the sidecar would still have finished; lowering it only
  makes the lane more patient than it needs to be. With a measurement at ~8 s, both are backstops. If that
  trade is ever load-bearing, the fix is for `/health` to report the ceiling beside the elapsed time.
- **`busy_for_s` is asked on the busy answer itself**, one `/health` GET per `retry_after_s` wait, to a
  sidecar that has just refused a measurement and so answers immediately. It is not polled otherwise, and
  an absent field falls back to the count — which is the normal case against an older sidecar image.

## Amendment (2026-10-13, fifth of the day) — the sweep's own failure rows explain the hang, and there was never a second stall (DEB-897)

The fourth amendment was written from the live processes and the code. The lane's own rows, read out of the
production database at 07:13:54Z while the wedge was still held, settle three things it left open and add one
requirement. **Nothing in FR-317-27, FR-317-28 or FR-317-29 changes**; this is the evidence behind them, and
it is worth having on the record because two of the three answers contradict what the report believed.

### The 2 h gap was a timezone, not an event

DEB-897 closed with *"the hung decoders started 04:39:20, two hours after the last successful row at
02:39:19Z — so there may be a second, earlier stall I have not accounted for."* There is none. The lane reads:

| row | state | started | finished | error |
|---|---|---|---|---|
| `mj-…-985` | done | 02:38:39Z | **02:39:19Z** | — |
| `mj-…-986` | **failed** | 02:39:19Z | 02:42:21Z | `Request timeout has expired [url=http://avsync:8000/measure]` |
| `mj-…-987` | running | 10-12 22:31:42Z (created) | — | cycling ever since |

`ps` prints **local time** (CEST, UTC+2) and the database and the logs print UTC: the decoders' `04:39:20`
**is** `02:39:20Z`, one second after `-986` began. So the wedging measurement is `-986`, it began the instant
the last good row finished, and the gap closes to zero. **No earlier stall is to be looked for**, and nothing
is lost by restarting before reading the sidecar's own log.

### The failure the report could not find does exist — on the row before the one that spun

*"No `failed` row, no finding, no unhealthy status"* was true of `-987` and false of the lane: `-986`
**failed at 02:42:21Z**, at exactly `[avsync] timeout_s`'s 180 s, with Ktor's timeout message. That is the
clearest possible confirmation of the fourth amendment's second finding — the client timeout fired, the
connection closed, the row failed honestly, **and `measure()` went on holding the lock** — and it refines the
diagnosis: the invisible part is not that nothing failed, it is that the *next* row then spun for ever
against the lock the failed row left behind. FR-317-29 is aimed correctly.

### The card was already in trouble 22 minutes before the hang

Of the lane's 20 failures, **11 are `OutOfMemoryError: CUDA out of memory`, clustered 02:17:00–02:18:59Z** —
the 10 OOMs the report mentions, plus one at 22:51:50Z the evening before. The wedge followed 20 minutes
later on the same card. Two consequences:

- **a GPU-side wedge rises relative to the stderr-pipe deadlock** as the cause of this particular hang. It
  does not displace it — a mutual pipe wait remains a real defect in our code on its own terms — so
  FR-317-27 keeps **both** the concurrent `stderr` drain and the watchdog, and the drain is not to be dropped
  because the watchdog alone makes a test pass;
- **317b's one-in-flight ruling hardens from a judgement to a measurement.** A card that OOMs eleven times
  in two minutes at one measurement has no headroom for two.

### FR-317-30 — The sidecar's JSON never carries `NaN` or `Infinity`

Two of the 20 failures are the backend refusing the sidecar's own answer:
*"Unexpected JSON token at offset 1576: Unexpected special floating-point value"* (00:50:03Z and 01:07:26Z).
Python's `json.dumps` emits bare `NaN` and `Infinity` for those floats; **`NaN` is not JSON**, and
`kotlinx.serialization` is right to reject it. A file that measured — the decode ran, the GPU time was
spent — is thrown away at the last step, and it reads as a parse bug in the backend rather than as the
sidecar handing out something indefensible.

- Every float the sidecar serialises is **finite or `null`**. A non-finite `corr`, `offset_frames` or
  `conf` becomes `null` at the point of serialisation, which the backend's existing nullable fields already
  read as *no answer* — and *no answer* is what a `NaN` correlation means.
- **The backend's parser is not loosened.** Accepting `NaN` would mean storing one, and a stored `NaN`
  propagates into the verdict arithmetic where it compares false against every bar in FR-317-7.
- Where a window's correlation is non-finite, the `reason` the row stores says so, so the file is not
  silently counted as measured.

### The two tensor-shape failures are a watch, not a requirement

*"RuntimeError: The size of tensor a (2) must …"* (00:50:26Z) and *"… a (23) must …"* (01:05:26Z) are the
remaining two failures. **No requirement is written for them here**: they are a real defect, they are rare
(2 in 969 measured files), and the honest next step is the input that produced them rather than a guess at
the shape mismatch — both rows are identifiable from the lane's history. They are recorded so the count of
unexplained failures stays known, and so nobody closes 317's failure list believing it is empty.

### Tests (added)

- A sidecar response carrying `NaN` for a window's `conf` and `Infinity` for an `others[].corr`: the sidecar
  emits `null` for both, the backend stores the row, and the file is not counted as cleanly measured. The
  fixture is a hand-written response document — **no library file is needed**, and none is to be used.
- The backend's parser still **rejects** a literal `NaN` from any other source: that behaviour is deliberate
  and is asserted, not relaxed.

### Acceptance (added)

23. Across a sweep, no job fails with *"Unexpected special floating-point value"*. The two rows that did on
    2026-10-13 are the baseline.

### As built (2026-10-13, DEB-915) — the backend goes first

Built as specified. One fact the build turned up, recorded here because it is a deployment order and nothing
in the code can enforce it: **the backend half must be live before the sidecar half.** The two halves are not
symmetrical.

- The **backend alone**, against today's sidecar image, changes nothing: a bare `NaN` is still refused, with
  the same message and the same failed job. No regression, no window of broken behaviour.
- The **sidecar alone**, against today's backend, turns two failures into a failure for every file that has a
  non-finite number: `conf: null` arriving at a parser without `coerceInputValues` is *"Unexpected 'null'
  value for non-nullable type"*, which is the same job failure wearing a different sentence.

A `null` in that field is therefore the one thing in this phase that is **not** backwards compatible with an
older reader, which is why the flag beside it (`nonfinite`) is additive and defaulted and the `null` itself is
only ever sent for a value that had no answer to send.

Also as built: `nonfinite` is a reason the row stores for a file **whose measurement contained a non-finite
number at all**, not only for one that had no other answer — the row reads `UNKNOWN`, and a file with two
clean windows beside one non-finite window is not reported as in step on the strength of the two. It is not in
`SKIP_REASONS`, so a `PIPELINE_VERSION` bump measures such a file again.

## Amendment (2026-10-13, sixth of the day) — a busy retry re-pays the whole pre-measure probe (FR-317-31, DEB-912)

FR-317-29 bounded how *long* a busy retry can go on. This bounds what each one **costs**. The finding is
DEB-912's, filed out of DEB-902 because that issue asked for DEB-897's 24.9 % of a core to be attributed and
explicitly asked that no performance change be folded into that build. It is attributed here, and the fix is
specified here, so the Kotlin has a requirement to point at.

**It is not the scheduler's per-pass read over the library.** An in-place requeue runs no scheduler pass at
all — the claim is one indexed `nextQueued` SELECT per cycle. What the cycle re-pays is `AvSyncService.check()`
itself, every attempt, *before* it ever reaches `/measure` and learns the sidecar is busy:

- `FfmpegRunner.probeDurationSeconds(path)` — one `ffprobe`, reading the header of a large file;
- `readMkvAudioIds(path)` → `mkvmerge -J` through `SegmentProcessGate` — a second process and an
  identification parse, conditional on Matroska. The wedged row's file is an `.mkv` (prod
  `media_job.params`), so both were paid on every one of the ~690 cycles: about 1 400 process spawns over
  four hours, for a call refused with a `503` milliseconds later. That is the shape of the measured 24.9 %.

Nothing in the measurement itself is wrong, and nothing about the give-up changes. The waste is paid by
**every ordinary busy retry too**, not only by a wedged sidecar — a sweep that happens to queue two files
behind one long measurement pays it in miniature. Since FR-317-29 the blast radius is ten minutes rather
than forever, which is why this is a separate, lower-urgency requirement and not an amendment to that one.

### FR-317-31 — A retry re-sends only `/measure`

The two pre-measure probes are **asked once per file per claim run, not once per attempt**. `check()` keeps
the answers for the file it is working on, keyed by the path and the stamp it already takes (`before`: size
and mtime), and an in-place requeue of the same row re-uses them:

- what is kept is exactly the two **process** answers — `probeDurationSeconds`'s own return (`null`
  included: *asked and got nothing* is an answer, and must not be re-asked) and `readMkvAudioIds`'s map (an
  **empty** map included, which FR-317-22 and FR-317-26 both give meaning to). Nothing else: `runtimeS`'s
  `knownRuntimeS` fallback, `skipReason`, `chooseAudio` and `audioTracks` all read the `MediaItem` already in
  memory and spawn nothing, so they stay where they are and are re-evaluated freely.
- the key is **path + size + mtime**. A file rewritten between attempts misses the cache and is probed
  again, which is the same stamp rule 255 and FR-317-8 already enforce two lines later — so a kept answer can
  never describe a different file than the one being measured. A hit on a *later* sweep, for a file whose
  stamp has not moved, is correct by that same rule and is allowed.
- **one entry is enough and the store is in memory.** The `avsync` lane has exactly one worker (317b stays
  at one in flight, hardened by the fifth amendment), so at most one file is in flight; a second path
  replaces the entry. No column, no migration, no config key — the same shape as FR-317-29's
  `avSyncBusyRun`, and like it, worthless across a restart and dropped there without ceremony.
- the decision is a **pure type** with its own unit tests (hit, stamp-changed miss, different-path miss,
  *asked and empty* counting as a hit), so it is not provable only through a 12-minute native run against
  real media.

Two consequences worth stating, because both are improvements and neither is the point of the change:
`readMkvAudioIds`'s FR-317-23 `WARN` is logged **once** per claim run rather than thirty times, and the
one worker sits in `avSyncBusyAnswer`'s `delay()` at approximately no CPU while it waits.

### The other per-file lanes do not have this defect — no requirement

DEB-912 asked that the question be put to them at the same time. It was, and the answer is structural:
`avsync` is the **only** lane that turns a transient refusal into a requeue. `verify`, `subtitles` and
`tracks` each turn a service that could not read the file into a `Failure` and the row terminates
(`MediaJobQueue.runClaimed`'s `when (row.type)`), so there is no second attempt to re-pay a probe on. This
is recorded rather than required, so nobody re-derives it; if another lane is ever given a retry, FR-317-31
is the rule it inherits.

### Deliberately not specified

- **A readiness pre-flight** — asking `/health` at the top of `check()` and refusing before probing. It
  reaches the same cost, but it puts the skip decisions *behind* a sidecar gate: an Animation or
  short-runtime file, which costs the sidecar nothing, would wait out the ten minutes and then fail where
  today it stores its skip and is finished. Keeping the answers is free of that.
- **Persisting the probe in `media_job.params`** — a second source of truth about a file, and a migration
  for a cache.
- **Any change to the give-up count, the `retry_after_s` wait or `/health`'s shape.** FR-317-27 to
  FR-317-29 stand as written.

### Tests (added)

- The memo type directly: a hit on an unchanged stamp, a miss when size or mtime moves, a miss on another
  path, and an *asked and empty* mkv-id map reading as a hit rather than a re-ask.
- The lane: a row answered `503` twice in a row runs the two probe commands **once**, and the second
  attempt sends `/measure` and nothing else. A fake process runner, a hand-written sidecar response; **no
  library file is needed, and none is to be used.**

### Acceptance (added)

24. With the sidecar busy (and only busy, not wedged), an `avsync` row that retries to its FR-317-29 give-up
    spawns **two** processes for that file in total, not two per attempt — and the lane's worker shows no
    measurable CPU across the wait. The 24.9 % of a core measured on 2026-10-13 is the baseline.

## Amendment (2026-10-13, seventh of the day) — lips are never compared against audio that is not there (FR-317-32, FR-317-33, DEB-939)

The fifth amendment left the sweep's two tensor-shape failures as *"a watch, not a requirement"*, on the
grounds that the honest next step was the input rather than a guess at the shape mismatch. **The input was
then read** (DEB-915, reported on DEB-939), and it closes the watch: all four of the lane's unexplained
failures — both *"Unexpected special floating-point value"* rows and both *"The size of tensor a (N) must
match the size of tensor b (0)"* rows — are **one defect with one cause**, and that cause also produces a
reading that is wrong without failing at all. The requirement is written here, and the four questions DEB-939
put to this seat are answered in it. **Nothing has been built for it yet.**

### The defect, and the part of the report that is not the defect

`distance_curve`'s window is

```python
n = min(lf.shape[0], af.shape[0] - f0)
```

where `f0` is the face track's first frame inside the window. Two cases reach the lane's failure list:
`n == 0` makes every slice empty, `d.mean(0)` over an empty dimension is `NaN`, and the window's `conf` is
`NaN` — the two special-floating-point failures, now survivable through FR-317-30 but still thrown away.
`n < 0` keeps `lf_len + n` lip rows against an empty audio slice, and PyTorch says so.

**One correction to the report, and it matters.** DEB-939 reads the line as indexing *audio* features by a
*video* frame index, as if the units were mixed. They are not. The video is decoded at exactly `fps=25` and
`audio_feats` steps the MFCC by 4 columns of 10 ms, so `af[i]` and lip feature `i` are the same 40 ms of the
same window by construction, and `F.pad(af, (0, 0, VSHIFT, VSHIFT))` is what makes the ±15-frame shifts
legal at the edges. `min(lf_len, af_len − f0)` is the **correct** upper clamp. The defect is that it is
unbounded **below**, and that it silently assumes `af` reaches as far as the frames it is clamped against.
Anyone who "fixes the indexing" here moves every reading in the library for nothing.

### Why the audio runs out, and the reading that is wrong without failing

Both tensor-failure jobs have a damaged first audio track; one decodes 2 287 s of a 2 841 s stream behind AAC
*"Number of bands (41) exceeds limit (40)"*. `decode_audio` asks ffmpeg for `-t dur` of PCM and gets whatever
survives: 36–45 s per 45 s window, 40–229 frames short of the video. **ffmpeg does not pad a dropped
packet** — the surviving samples are handed over end to end — so a gap mid-window does not merely shorten
the audio, it **shifts everything after it**. Frame *k*'s lips are then measured against speech from
*k* + the gap, which is not a measurement of that file's sync at all, and today **nothing in the row tells
such a window apart from a real one**. The two crashes are only the cases where the shift happened to land on
an empty slice; the quiet cases are the ones worth fixing. `file_track_coverage` cannot catch them either —
255 measures where a track *ends*, and both files read `content_end_ms ≈ header_ms` with no findings.

**The construction gap is not this.** A healthy 45 s window decodes 1 125 video frames against
`mfcc_frames // 4 − 5` = 1 119 audio features: six short, every time, by arithmetic. So the last ~0.24 s of
**every** window is already a tail with no audio under it, the clamp already truncates it, and that is
correct behaviour that must survive untouched. Any bar drawn here has to separate six from forty.

### FR-317-32 — A window is measured only where the audio reaches the picture

Per window, before any face work:

- `v` = the video frames decoded (`fr.shape[0]`, exactly 25/s), `a` = the audio features that PCM will
  produce. `a` is derived **arithmetically from the sample count**, not by running the model:
  `numframes = 1 + ceil((N − 400) / 160)` for `N ≥ 400` (`python_speech_features`' own winlen/winstep at
  16 kHz, 1 below that), then `// 4 − 5`. A unit test pins the arithmetic against
  `python_speech_features.mfcc(pcm, 16000).shape[0] // 4 − 5` so the two cannot drift; it is CPU-only and
  needs no card.
- the window carries two additive fields: **`audio_short_frames`** = `v − a − AUDIO_FEATURE_LAG` (signed,
  **never clamped**, `AUDIO_FEATURE_LAG = 6`, the construction gap above, named in `measure.py` with the
  arithmetic beside it) and **`audio_short`**, true when `audio_short_frames > AUDIO_SHORT_TOL`
  (`= 2` — one codec packet of 1 024 samples at the `-ss` seek is 1.6 frames, and nothing healthy is worse).
- **a window with `audio_short` is refused**: it is reported exactly as a window with no face track is
  (`offset_frames: null`, `conf: 0.0`, `frames: 0`, `faces: []`) with the two new fields set, and **no
  detection, no crops and no lip features are computed for it.** That is where the GPU is saved, and it costs
  nothing, because the audio was decoded either way.
- **`audio_short_frames` is reported on every window, refused or not.** A clean window stores a 0 or a ±1,
  so the healthy distribution of `v − a` is readable out of `file_av_sync` without a new build: if `2` ever
  turns out to be the wrong bar, the evidence to re-draw it is already stored. This is the whole reason the
  field is not a bare boolean.
- a refused window contributes `None` to **every** envelope list — the measured track's and each
  `others` entry's — so FR-317-4's zip stays aligned and the window casts no vote in the cross-correlation
  either. An `others` stream that is itself short in a window contributes `None` for that stream in that
  window alone.

**The bar is against the decoded video, not against the requested duration.** DEB-939 proposed
`25 · dur − 5`. That bar refuses two healthy things: a window clamped at the end of the file (FR-317-8 clamps
`durS`, but a container whose runtime overstates the stream still asks for audio that was never there) and
any file whose real length is under its header's. In both, video and audio come back short **together**, the
clamp does its job, and there is nothing wrong with the reading. `v − a` is invariant to all of it.

### FR-317-33 — `n` is bounded, and nothing else about the clamp moves

- `distance_curve` is never called with `n <= 0`: a face track with no audio under it is **not measured** —
  no curve, no weight, no `faces[]` entry.
- **This is a backstop, not the fix, and that is the answer to DEB-939's second question.** It kills both
  crash classes and leaves every silent wrong answer in place, which is why FR-317-32 exists. Inside a window
  FR-317-32 accepted it is **unreachable**: `v − a ≤ 8`, a track piece is ≥ 30 frames so `f0 ≤ v − 30`, hence
  `a − f0 ≥ 22 > 0`. If it is ever reached, the window is refused by the same path as a short-audio window,
  with `audio_short: true` and an `audio_short_frames` **at or below the bar** — a signature no healthy row
  can wear, and the bug report writes itself.
- **No minimum-`n` filter is introduced.** A face track that ends at the last frame of a healthy window
  legitimately reaches `n = 24`, and FR-317-7 already weights it by its length against a 250-frame
  confidence bar, so it cannot move a verdict. A floor would drop the last second of every window's evidence
  across the whole library to buy nothing.

### What the row says, and what it does not override

A new reason **`short_audio`** (`AvSyncReason.SHORT_AUDIO`), label *"not measured — part of the audio did
not decode, so there were no words under the lips"*. Like `nonfinite` it is **not** in `SKIP_REASONS`: a
pipeline version measures such a file again. Unlike `nonfinite` it is **not** passed to
`AvSyncVerdict.of`'s `reason` parameter and so **never overrides a verdict**:

- it is decided inside the `confident.size < 2` branch, after `nonfinite` and before `no_faces` — `no_faces`
  would be the same lie about a window the model was never shown as it would be about a `NaN` (and the same
  lie DEB-836 filed about an unreadable file);
- **a file with two clean windows beside one refused window keeps its verdict.** A refused window is *there
  is nothing to measure here*, which is what a window with no face already is — not *the measurement
  failed*. The refused windows are simply never confident, FR-317-17's second triple then asks three more
  places in the file for exactly this reason, and a file whose damage is local gets a real answer out of its
  healthy parts. A file whose damage is everywhere reaches `unknown` and says `short_audio`;
- `nonfinite` stays the headline where both appear. After this change a non-finite number is **by definition
  not explained by short audio**, so it is the one that is news.
- `check()` logs one `WARN` on the `integrity` channel naming the path and the worst `audio_short_frames`
  **whatever the verdict**, including when the verdict survives. That log plus the stored fields are the
  audit trail DEB-939 correctly says does not exist today.

### `PIPELINE_VERSION` goes to 2 with the fix

DEB-939's fourth question. Yes, in the same change: `MODEL` becomes `syncnet_v2+s3fd@907c0b5/2`, and by
FR-317-2/FR-317-8 every stored row is due again. The reasoning and the cost, both small:

- the readings that moved are exactly the ones we cannot identify without re-measuring. A file that crashed
  stored no row at all; a file that was quietly misaligned stored a row that looks like every other. Nothing
  in `file_integrity` resolves it either — only one of the two known files carries a `damage_count`.
- a bump marks rows due; it **starts nothing**. FR-317-8's pass is enqueue-only, so the re-measure happens
  when a pass is next run, on the `avsync` lane's own worker, parked while a TV plays.
- **the library's zero resets for about the first hour.** `zeroFrames()` reads `measuredWindows(model)`, so
  at the new model there are none and FR-317-6's `−1` default stands until 200 confident windows exist
  again — a few dozen files. Verdicts computed in that gap are computed against `−1` rather than the
  library's measured zero, which can flip a borderline `offset` either way on the Dashboard for that hour.
  Stated because someone will find it; it is FR-317-6 working as specified, and it is the reason to bump
  **now**, with the sweep being restarted after DEB-918 anyway, rather than as its own event later.

### Deployment order — the sidecar may go first this time

The opposite of FR-317-30's. `measureJson` has `ignoreUnknownKeys = true`, and a refused window is expressed
in fields today's backend already reads (`offset_frames: null`, `frames: 0`), so **the sidecar alone against
today's backend is correct**, degrading only to `no_faces` where it would have said `short_audio`, which is
not a skip and is re-measured by the bump regardless. The backend alone against today's sidecar is also
correct and simply never sees the flag. Neither half has a broken window, so this one deploys in either
order — but `PIPELINE_VERSION` lives in the sidecar, so the re-measure begins when **it** lands.

### Deliberately not specified

- **A whole-stream `ffmpeg -map 0:a:N -f null` pre-flight** — DEB-939's third question, and the answer is
  no. It reads the entire file to answer a question FR-317-32 answers for free from audio it has already
  decoded, on the same `check()` path FR-317-31 just took two header reads off, and against 255's rule that
  the advice path never reads a media file. The GPU saving DEB-939 wanted is real and is taken — a refused
  window skips detection and lip features — it is simply taken per window instead of per file.
- **Skipping a file because `file_integrity.damage_count > 0`.** Damage need not touch the audio, let alone
  the three measured windows, and a skip would lose real findings on files that measure cleanly.
- **Naming that damage in the quiet line.** *"part of the audio did not decode"* is what this phase measured
  itself; attributing it to 255's damage count means joining two services' findings on a render path for one
  sentence. It belongs to whoever next touches that banner, and the reason string is the hook.
- **`-af aresample=async=1:first_pts=0`**, which would pad a gap with silence and keep the timeline
  anchored. It makes a damaged window measurable against silence rather than refused, it changes the first
  samples of **every** healthy window, and it hides the damage instead of reporting it.
- Any change to FR-317-30, FR-317-7's bars, or the clamp's upper half.

### The one case this does not catch

A decode that comes back the **right length** but is internally shifted — a damage mode that adds samples
where it drops them. `v − a` cannot see it, no case of it has been observed, and the honest place to record
that limit is here rather than in a guess at a second check.

### Tests (added)

- The feature-count arithmetic against `python_speech_features.mfcc` on synthetic PCM (CPU, no card): exact
  at 45 s, at a 10 s window, at one sample, and at `N < 400`.
- `distance_curve`'s bound directly: `n == 0` and `n < 0` both return *not measured* rather than a `NaN`
  curve or a tensor-shape raise, driven by lengths alone — the shape both 2026-10-13 failures were
  reproduced from. **No library file is needed, and none is to be used.**
- A refused window end to end in the sidecar: a window whose PCM is 36 s against 45 s of video reports
  `audio_short: true`, `audio_short_frames` **225**, `frames: 0`, `offset_frames: null`, an empty `faces`,
  and **the detector is never called** (asserted by a stub, since that is the GPU saving).
  *(Corrected while building, 2026-10-13: this line first said ≈ 219, which is `25 · 45 − 25 · 36 − 6`. The
  rule above gives 225, because the 36 s that did arrive are themselves 6 features short of 36 × 25:
  1 125 − 894 − 6. The estimate was wrong, not the requirement, and `v − a − AUDIO_FEATURE_LAG` is what is
  built and tested.)*
- A healthy window reports `audio_short: false` and an `audio_short_frames` of 0 or ±1 — the regression
  that would otherwise refuse the whole library.
- `linuxX64Test` on hand-written response documents: a row with one refused window beside two confident ones
  keeps its `offset` verdict and stores **no** reason while logging the warning; a row whose every window is
  refused reads `UNKNOWN` with reason `short_audio` and **not** `no_faces`; a row with a refused window and a
  non-finite number elsewhere reads `nonfinite`; `short_audio` is not in `SKIP_REASONS`, so a model bump
  makes such a row due.
- The `others` zip: a refused window drops that window from the measured track's and every other stream's
  envelope list together, and a stream short in one window alone still reports a lag from the rest.

### Acceptance (added)

25. Across a sweep, no job fails with *"must match the size of tensor"* or *"Unexpected special
    floating-point value"*. The four rows that did on 2026-10-13 are the baseline, and the two files behind
    them (jobs `mj-1791844302-556` and `-625`) each come back with a stored row: a verdict from their
    healthy windows, or `unknown` with reason `short_audio`.
26. `file_av_sync` can be asked which files had a window refused and by how much, and the answer for a
    library of healthy files is none. Every stored window from a healthy file carries an
    `audio_short_frames` of 0 or ±1.

## Amendment (2026-10-13, eighth of the day) — a `PIPELINE_VERSION` bump is half a change: the pass that follows it has to be somebody's (DEB-955)

No code moves here. The seventh amendment decided the bump to `/2` and stated its cost correctly; what it
assumed, and what this phase never wrote down, is **who runs the pass that the bump makes due**. Høgni found
the gap from the other end — a live sweep spending GPU on rows the pending deploy discards — and the
sequencing below is the answer, decided rather than discovered.

### The gap, in the seventh amendment's own words

*"a bump marks rows due; it **starts nothing**"* is right, and *"it is the reason to bump now, with the
sweep being restarted after DEB-918 anyway"* is where the reasoning leans on something that is not true of
prod: **`scan_interval_hours = 0` there, so nothing restarts a sweep but a person.** A bump therefore has
two halves — the sidecar lands, and an operator runs a pass at `scope=missing` — and only the first half had
an owner. With just the first half, the library's measured A/V-sync picture reads as unchecked and stays
that way: `rowsCurrentByPath()`/`isCurrent()` drop every row from an older `model`, which is FR-317-7 and
FR-317-8 working exactly as specified.

### What is at stake, measured on prod at 2026-10-13 09:33Z

`file_av_sync` carries 5 923 rows under exactly one model string, `syncnet_v2+s3fd@907c0b5/1`:

| | rows | survives the bump to `/2` |
|---|---|---|
| `animation` 4 566, `too_short` 28, `no_original_track` 11 — FR-317-8's skips | 4 605 | yes |
| `reason IS NULL` — measured | 836 | no |
| `no_faces` — measured, no face found | 482 | no |
| **made due by the bump** | **1 318** | |

And the hand-started sweep of 2026-10-12 22:31Z is still advancing: **3 672 `check_av_sync` rows waiting**
in the `avsync` lane at ~195 files/h, with the skip population long since drained (it resolved in the first
five minutes), so what is left is ~19 h of GPU writing rows under `/1` that `/2` will discard. Post-bump the
pass has the same ~4 990 files to measure whether or not those 19 h are spent first, which is what makes the
waiting half of that lane pure waste rather than a head start.

One number settles the id-map question with it: of the rows whose `mkv_audio_ids` is still `NULL`, **338 sit
on a non-skip row (177 measured, 161 `no_faces`) and every one of them is inside the 1 318** — the remaining
4 605 are skips, which never ran `mkvmerge` and never needed a map. So the forced refill of those maps and
the bump's re-measure are the same work done twice, with 318's `LANG` fix (DEB-928) live under both.

### The order, decided (Tróndur, 2026-10-13, DEB-955)

1. **The waiting part of the `avsync` lane is emptied before the bump** (DEB-958), with FR-260-1's own control
   (`POST /api/jobs/empty`, `{"queues":["avsync"]}`) — the running job finishes, a waiting job has touched no
   file, and nothing stored is lost. This is reversible by a single `scope=missing` pass, which is also why
   it is not an owner decision: 317's readings are admin-facing (the Checks card, the flagged list), no
   viewer sees a difference, and these jobs already stand aside for playback (`defer_while_playing`).
2. **The pass that follows the bump is a card of its own** (DEB-959), held on the deploy and carrying the id-map
   evidence 318's acceptance 6 wanted. A bump without that card booked is not deployed.
3. **If the bump does not land, the sweep resumes under `/1`** — the same one-line pass, no re-enqueue by
   hand, decided at 2026-10-14 09:30Z so the library does not sit unmeasured on an owner-gated deploy.

### Deliberately not specified

- **The backend enqueuing its own re-measure when the model string changes.** It would close the gap in code
  rather than in a runbook, and it is tempting for exactly that reason, but it contradicts FR-317-8's
  enqueue-only design and would have a deploy start several hours of GPU work with nobody watching — the
  opposite of what `scan_interval_hours = 0` is for on prod. The pass stays a person's, and the requirement
  below is what makes it one.

### Acceptance (added)

27. After a `PIPELINE_VERSION` bump reaches prod, a pass at `scope=missing` is run and `file_av_sync` holds
    no non-skip row under an older model string. During the pass the library's zero rebuilds as the seventh
    amendment describes — the first hour's verdicts are computed against FR-317-6's `−1` default, and that is
    not a finding.
28. That pass is what refills the `NULL` `mkv_audio_ids` on non-skip rows: afterwards, every row the pass
    measured carries a map, and a non-ASCII path is not over-represented among any that do not.

## Amendment (2026-10-13, ninth of the day) — acceptance 28 fails on a perfect fix as worded: the id-map test is a Matroska test (DEB-959)

No code moves here either, and no requirement changes. The eighth amendment added acceptance 28 one paragraph
after the sentence that makes it safe, and dropped the word that carries it. **A `NULL` `mkv_audio_ids` on a
non-Matroska row is correct, not damage** — `mkvmerge -J` is never run on an `.mp4`, so no map was ever owed —
and read literally across *all* non-skip rows, both halves of acceptance 28 fail after a flawless pass. Høgni
found it from DEB-905 before the pass ran; the numbers below are a second reading taken four minutes later,
and they agree exactly.

318's acceptance 6 never had the defect: it says *"no **measured matroska** row at a non-ASCII path"*, and the
10-13 DEB-848 amendment there defines that population as `reason IS NULL OR reason = 'no_faces'`. Acceptance 28
is the same test seen from this phase's page and must name the same population.

### Both halves, measured on prod at 2026-10-13 09:45Z

A WAL-complete read-only copy (`.db` plus `-wal` and `-shm`; a copy without the sidecars reads frozen at an
older revision, which looks exactly like a pass that did nothing). 5 965 rows, 1 360 of them non-skip, the
`/1` lane still advancing — so these counts drift upward and are quoted with their clock, not as constants.

| extension | non-skip rows | has an id map | **no map** |
|---|---|---|---|
| `.mkv` | 1 217 | 1 022 | **195** |
| `.mp4` | 140 | **0** | 140 |
| `.avi` | 3 | **0** | 3 |

**Not one of the 143 non-Matroska rows has ever carried a map, and none ever will.** So a naive count after the
pass drains returns 143 "failures" that are correct by design, and the first half of acceptance 28 can never be
met as worded.

The second half is the dangerous one, because it is the half that looks like evidence:

| set | non-ASCII share |
|---|---|
| all 1 360 non-skip rows — the naive baseline | 21.3 % |
| the 143 non-Matroska leftovers | **59.4 %** |
| the 195 Matroska recovery set | 72.3 % |
| **all 1 217 non-skip Matroska rows — the right baseline** | **16.8 %** |

A perfect pass that leaves only the 143 therefore reads as 59.4 % against a 21.3 % baseline — a 2.8× skew and a
confident-looking *"non-ASCII paths are still losing their id maps"*. It is nothing of the kind: non-ASCII
filenames cluster by directory in this library and the `.mp4`/`.avi` material happens to be heavily Nordic.

### Acceptance (restated)

- **28 is restated.** After the pass, this query returns **0**:

      select count(*) from file_av_sync
       where reason not in ('animation','too_short','no_original_track')
         and path like '%.mkv'
         and mkv_audio_ids is null;

  and the over-representation clause is evaluated **on non-skip Matroska rows only**, against that
  population's own non-ASCII share (16.8 % at 09:45Z) rather than the whole table's. A non-Matroska row
  without an id map is not a counter-example to anything and is not counted.

- **28 expects 0, not *n* − 2.** The two files behind the original locale error — the non-Matroska container
  wearing an `.mkv` extension, and the 4 096-byte stub — fail to **measure**; a failed job stores no row
  (FR-317-8), so they surface as a failed job or an absent row, never as a measured row with a null map. A
  failed measure must not be filed as a missing id map, nor the reverse. The `avsync` lane holds 20 such
  failed jobs at 09:45Z against 1 379 done.

### What the live `/1` sweep already says about the outcome

The lane has been measuring since the backend's `LANG` fix reached prod at 08:34Z (DEB-928), which makes the
rows it has written since then a free pre-test of acceptance 28 — one that costs no GPU and does not wait on
the deploy:

| Matroska rows, last checked | rows | carry an id map |
|---|---|---|
| non-ASCII path, **after** 08:34Z | 56 | **56** |
| non-ASCII path, before it | 149 | 8 |
| ASCII path, after 08:34Z (the control) | 181 | 181 |
| ASCII path, before it | 831 | 777 |

Every non-ASCII Matroska row re-measured since the fix carries a map. That is the strongest statement
available before the pass runs, and it also shows the restated test has real teeth rather than being trivially
satisfiable: the skew it looks for is exactly the bug's fingerprint — 72.3 % non-ASCII among the 195 unmapped
rows against 6.3 % among the 1 022 already mapped. If the pass leaves non-ASCII Matroska rows unmapped, that
is a genuine finding; the 143 were never one.

## Amendment (2026-10-13, tenth of the day) — the eighth amendment's premise is wrong: prod sweeps itself every hour, and the pass the bump makes due has no owner because it needs none (DEB-955)

My error, found by checking the thing the eighth amendment asserted. It says *"`scan_interval_hours = 0`
there, so nothing restarts a sweep but a person"*, and that is **false**. `scan_interval_hours` is only the
**legacy** fallback: `Main.kt`'s scheduler reads `cfg.scanSchedule` first and reaches the interval branch
only when that is blank (`Main.kt:722-737`). Prod's live config, read at 2026-10-13 11:05Z:

```toml
scan_schedule = "30 */1 * * *"        # line 2 — every hour at :30, local wall clock

[[scan.pipeline]]
    step = "check_av_sync"
    enabled = true
    scope = "missing"
```

So prod runs the **whole pipeline, including the A/V-sync check at `scope = missing`, every hour**. Three
independent readings of the same thing: `scan_state` holds `scan-1791887400` — started **10:30:00Z** exactly,
`COMPLETE` 10:39:05Z; `item_step_run` over the last 24 h clusters in minutes **:30–:35 across thirteen
different hours**; and the `check_av_sync` enqueues fall in exactly two large clusters (10-12 22:31Z, 4 968
rows; 10-13 10:35Z, 3 548 rows) with handfuls of 6–9 rows at 23:32Z and 02:33Z in between — the hourly runs
*were* firing all along and adding only the newly-dirty files, because the lane still held the rest and the
queue dedupes. That is why hourly runs were invisible in the lane and why "the sweep was hand-started at
22:31Z" needs no human in it at all.

### What the halt actually bought: 31 minutes

DEB-958 emptied the waiting lane at **10:04:08Z** (`removed.avsync = 3 572`, one running job kept, exactly as
FR-260-1 says). The **10:30Z scheduled run re-enqueued 3 548 of them at 10:35:48Z**, and at 11:01Z the lane is
advancing again — 99 rows measured since 10:00Z, 6 107 rows stored, 1 502 of them non-skip. Nothing was lost
or damaged; the halt simply expired.

### The order, restated

1. **The halt is withdrawn, and the lane is not emptied again.** An empty is a ≤60-minute palliative here.
   The only durable stops are a write to the owner's `scan_schedule` or `enabled = false` on the step, and
   both are a bigger intervention in his configuration than the GPU they save — which is **measured at ~195
   files/h**, about an hour of the post-bump pass per hour of waiting, on a card the owner holds anyway.
2. **The eighth amendment's fallback clause is void.** There is nothing to "resume under `/1` by 2026-10-14
   09:30Z": the `/1` sweep never stopped and cannot be stopped from the queue.
3. **Acceptance 27 stands; its subject changes.** The pass that clears the older-model rows is the **next
   scheduled run**, within 60 minutes of the sidecar landing — not an operator's trigger. DEB-959 is a
   verification card, not the trigger, and whoever deploys should expect FR-317-6's zero to rebuild (the
   seventh amendment's first-hour window) on its own, with no one having asked for it.
4. **The seventh amendment was right, and for a better reason than it gave.** *"The reason to bump now, with
   the sweep being restarted anyway"* holds without needing DEB-918 in it: the restart is hourly and
   automatic.

### What survives from the eighth amendment

Everything that was read rather than assumed: the invalidation arithmetic (`SKIP_REASONS` spares 4 605 rows
and makes every measured row due), the id-map containment (all non-skip `NULL` `mkv_audio_ids` sit inside the
invalidated set, so a forced per-title refill is redundant), acceptance 27 and acceptance 28 as the ninth
amendment restated it, and the decision that DEB-905's separate refill is withdrawn. The only load-bearing
thing that was wrong was *who* starts the pass.

### Deliberately not specified, restated

A backend-side auto-enqueue on model change is still not specified, and now for a stronger reason: the
scheduler already is one. A requirement for it would describe existing behaviour and invite a second
mechanism beside it.

## Amendment (2026-10-13, eleventh of the day) — the model string is *learned*, not derived: a sidecar swap under a running backend invalidates nothing and mislabels what it then measures (DEB-959)

The tenth amendment is right that the scheduler starts the pass, and it finishes the sentence the eighth
started. What neither says is **when the running backend finds out that the sidecar's model string has
changed** — and the answer decides whether the bump does anything at all. Read out of prod's own running
commit `80552895`, not out of `main`.

### `lastModel` is one in-memory variable with exactly two writers

`AvSyncService.lastModel` is a `private var`, per process, **null at boot** — nothing persists it and nothing
reads it from the table. Two paths set it, both through `health()`'s
`?.also { if (it.model.isNotBlank()) lastModel = it.model }` (`:305` at prod's commit):

1. **`GET /api/config/avsync/status`** → `settings()` → `settingsAnswer()` → `health()`, **unconditionally**.
   This is the Settings card's own refresh, which FR-317-16 already describes; the side effect on `lastModel`
   is what matters here.
2. **`checkOnce`'s `if (lastModel == null) health()`** (`:601`) — lazy, and **gated on null**.

Nothing else teaches it: not `Main.kt`, which constructs the service and wires it into the queue (`:386-389`)
without probing; not `PipelineEngine`; not `MediaJobQueue`; not DEB-923's watchdog; not `/api/health/full`.
And `rowsCurrentByPath()` (`:449`) filters `m == null || it.model == m || reason in SKIP_REASONS`, with
`MediaJobQueue.storedChecks()` building the `AVSYNC_JOB` map from it — so **while `lastModel` is null, every
stored row reads as current** and the scheduled run has nothing to re-measure.

### Three consequences, in the order they would bite

1. **With the deploy order this phase already requires — backend half first, sidecar second (`65ceb2f1`,
   FR-317-30's `conf: null` is not readable by an older parser) — the bump is inert unless something
   re-teaches the string.** The recreated backend comes up with `lastModel = null`, the `avsync` lane resumes
   on the thousands of `queued` rows that survive a recreate, and within a minute `checkOnce` asks the
   **still-`/1`** sidecar and learns `/1` again. The sidecar is then swapped to `/2` and **nothing asks a
   second time**, because the only unattended probe is gated on null. From that moment: no row is invalidated,
   the `:30` run enqueues nothing new, acceptance 27 cannot become true — **and every row the `/2` sidecar
   measures is stored under `/1`** (`lastModel` is what the write uses), so the new pipeline's readings are
   mislabelled as the old one's and are afterwards indistinguishable from genuine `/1` rows. That is worse
   than a bump that did nothing.
2. **Re-teaching it costs one request and no deploy window.** Because path 1 is unconditional, a single
   authenticated `GET /api/config/avsync/status` once the `/2` sidecar is up updates the running process, and
   the next `:30` run then sees the `/1` rows as stale. Opening the Settings page does the same thing. A
   second backend recreate also would, at 11 s of downtime instead of one GET.
3. **A lane emptied at deploy time is the one state where nothing teaches it at all.** `checkOnce` only runs
   when there is a job; with an empty queue and every non-skip row current under `/1`, the string is never
   learned, nothing is invalidated, and the scheduled run finds nothing to enqueue — a stable, silent
   standstill until someone opens Settings. The library's ~3 600 never-measured files make this moot today,
   and it is one more reason the withdrawn "empty the lane before the bump" step was worse than useless.

Measured while writing this, 2026-10-13 11:47Z: the sidecar answers `ok: true` on an RTX 2060 SUPER at
`model syncnet_v2+s3fd@907c0b5/1` with `busy: true`, and the lane is advancing. So consequence 1's premise is
today's actual state, not a hypothetical.

### Acceptance (restated)

- **27 is restated a second time.** Its subject is **the first scheduled run after the backend has learned the
  new model string**, and the verification runs in that order:
  1. the sidecar's own `/health` reports the `/2` string (`docker exec`, not the tag — a tag is rebuilt in
     place);
  2. the **backend** agrees, read off `GET /api/config/avsync/status`'s `model` — which is also what teaches
     it, so this step is the remedy and the check at once;
  3. the next `:30` run enqueues, and `max(file_av_sync.checked_at)` advances (never the lane counters,
     DEB-918/923);
  4. `select distinct model from file_av_sync` drains to the one new string plus the untouched skips.

  **A reading of "1 318 rows still under `/1`" taken before step 2 is not a failed bump — it is a bump that
  has not begun.** Acceptance 28, as the ninth amendment restated it, is unaffected.

### Left as a question, not specified here

Whether `health()` should be re-probed on a cadence (or `lastModel` invalidated on a sidecar reconnect) so
that a swap cannot go unnoticed by a long-lived process is a design question for whoever owns FR-317-7, not
something to settle in an operator's amendment. The runbook step in consequence 2 closes today's risk without
new code.

## Amendment (2026-10-13, twelfth of the day) — the VRAM budget is a frame *count* and the decode target is a fixed *width*, so a 4:3-ish SD file is the most expensive frame in the library (FR-317-34, FR-317-35, FR-317-36, DEB-979)

Sjúrður's census of the `avsync` lane's failures, DEB-979: of 150 failed rows **129 are
`OutOfMemoryError: CUDA out of memory`** — against 5 connect, 5 tensor-shape, 4 `conf: NaN`, 4 FR-317-29
give-ups and 3 one-offs. Those 129 jobs are **122 distinct files in only 4 titles**, and 120 of the 122 come
from two **standard-definition** series: 111 files of a 200-episode series at **720 × 552**, and 9 of 18 at
**700 × 394**. A 1080p series contributes one file and a film one. Only 7 files OOM'd twice, so this is 122
separate failures and not a retry loop.

**That inverts the hypothesis this phase had been carrying** — acceptance 7c's VRAM question, and the
4K/DV/HDR tonemap path as the obvious suspect. The report says so itself and offers the detection batch as
the mechanism. The batch is where the allocation *fails*; it is not what *varies*. Read in `measure.py` at
`main`, and at `d87d096e` — the revision whose `measure.py` is actually baked into the running container.

### The arithmetic, in the sidecar's own lines

`:619` — `W = RES; H = int(round(p['h'] * W / p['w'] / 2) * 2)`, with `RES = 640`. **The width is fixed and
the height keeps the aspect**, so the bytes per frame are proportional to **1/aspect**: the squarer the
source, the taller the decoded frame, and *small* has nothing to do with it.

| source | decoded | px/frame | the window on the card (`:660`) | `shots`' 128-frame float copy (`:350`) | peak before detection |
|---|---|---|---|---|---|
| 1920 × 1080 | 640 × 360 | 230 400 | 0.778 GB | 354 MB | ≈ 1.13 GB |
| **720 × 552** | **640 × 490** | **313 600** | **1.058 GB** | **482 MB** | **≈ 1.54 GB** |
| 700 × 394 | 640 × 360 | 230 400 | 0.778 GB | 354 MB | ≈ 1.13 GB |

Against a **hard 2.0 GB per-process cap** — `VRAM_GB = 2` (`:39`) through
`set_per_process_memory_fraction` (`:109`), deliberately, because the card is shared with Immich. A 45 s
window is 1 125 frames at `FPS = 25`, and `:660` moves **all of it** to the card at once; `shots` then makes
a 128-frame float32 copy of part of it, and S3FD's activations for `detect`'s batch of 12 land on top of
that. **At 640 × 490 three quarters of the budget is spent before the detector is reached**; at 640 × 360 it
is a little over half. The `DECODERS = 3` prefetch is not implicated — those are CPU tensors, never on the
card.

So the report's conclusion survives its own mechanism: **every allocation in this pipeline is sized by a
frame count — 1 125, 128, 12, 16 — while the cost of a frame is set by a scale that holds the width
constant.** Nothing in the sidecar knows how many bytes it is about to ask for.

### The second mechanism, which is why a 16:9 SD title OOM'd at all

700 × 394 decodes to the **same geometry and the same cost as 1080p**, so geometry cannot explain its 9
files. **No failure path hands the card back.** `torch.cuda.empty_cache()` is called only on the success
path, between windows (`:699`); `measure()`'s `except BaseException` (`:700`) kills the children and
re-raises, and `server.py:92` turns that into the 500. The caching allocator's reserved, fragmented segments
therefore survive an OOM inside a **long-lived** process, and the next file asks for its contiguous ~1 GB
inside a 2 GB budget that is no longer clean. Stated as a hypothesis, like the report's own, and it explains
both that title and **111 of the 129 landing inside one hour** at least as well as the sweep's walk order
does. FR-317-36 fixes it whether or not it was load-bearing, because handing the card back on the way out is
correct regardless.

### What the pending sidecar deploy will not do

DEB-950's image is queued and DEB-979 asks, rightly, whether it changes this. **It does not**, and that is
settled from the lines rather than left to a measurement: the baked `2bcbf336fcee636e5c015a7ad5fcd655` is
`d87d096e`'s copy, and `RES = 640`, `W = RES`, `fr_cpu.to(dev)`, the 128/12/16 chunk sizes, `VRAM_GB = 2`
and the absent `empty_cache` are **byte-identical at `787af7fa`**. FR-317-32's only touch nearby skips the
`to(dev)` for a window it *refuses*, and these files are not refused — they reach detection. Deploy DEB-950
for its own reasons.

### FR-317-34 — The card never holds a whole window

The decoded window **stays the CPU tensor it already is**. `shots`, `detect` and `crops` move only the slice
they are about to compute on and release it; nothing holds a view of the whole window on the device, and the
peak is set by the chunk rather than by the window's length or its geometry.

**This moves no number, and that is the point of choosing it.** Each of those three already computes
chunk-by-chunk and concatenates — `shots` takes its differences *after* the concatenation, `detect` is
per-image, `crops` and `lip_feats` are per-piece — so the same uint8 bytes meet the same operators in the
same order and every reading is bit-identical. **`PIPELINE_VERSION` therefore does not move, no stored row
is invalidated, and nothing is re-measured on account of this fix.**

The alternative is rejected for exactly that reason: scaling to a **pixel-area** budget instead of a fixed
width (an `RES` that targets W·H) would give the same relief in one line, and would change the detector's
input on **every file in the library** — a bump, and all ~5 000 non-skip rows due again, for a defect that
costs 122.

### FR-317-35 — Every GPU chunk is budgeted in bytes, and a refusal says what would not fit

Each chunk size is **derived from `VRAM_GB` and the frame's own byte size** — W·H·3 for the uint8 slice,
W·H·3·4 for a float32 copy — and never from the constants 128, 12 and 16, which are a frame count that
happened to fit one geometry. The floor is **one frame**: if a single frame's working set does not fit, no
chunking will, and that is the only case left to report.

An OOM that survives the floor answers with a 500 whose message **names the decoded W × H, the window's
frame count, the chunk that failed and `torch.cuda.max_memory_allocated()`**. Today's message is
`OutOfMemoryError: CUDA out of memory` and nothing else: finding the shape above cost a join from
`media_job.error` against the stored track JSON of four titles. One line would have said it.

### FR-317-36 — The card is handed back on every exit, and a file that still cannot be measured is written down

Two parts, and the second is what the owner is missing today.

1. **`torch.cuda.empty_cache()` on every exit path**, failure included, so one file's OOM cannot be the next
   file's. It belongs beside the lock release in `server.py`'s `finally`, which is the one place every
   outcome passes through.
2. **A file that still OOMs stores a row.** The sidecar's 500 body carries an additive
   `error_kind: "vram"` (an older backend ignores an unknown key and behaves exactly as it does today), and
   the backend stores `reason = vram` — `UNKNOWN`, with a sentence on the Checks card, and **not** a member
   of `SKIP_REASONS`, so a new pipeline version makes the file due again. FR-317-30's `nonfinite` pattern,
   for the same reason: nothing was *decided* about this file.

**Why a stored row is the requirement and not merely nice.** Today a 200-episode series is neither flagged
nor clean nor skipped: `check()` returns `Result.failure`, **no row is written**, the job carries the
error — and because the file has no row it is still `missing`, so the hourly `:30` scan (the tenth
amendment) re-queues all 111 episodes to fail again, every hour, for ever. Phase 317 has nothing to say
about that series and nothing tells the owner so. A `vram` row says it in one line and stops the lane
re-paying for it.

**The ordering inside this amendment is not free.** Part 2 must land **with or after** FR-317-34/35, never
before: a `vram` row is no longer `missing`, so a row stored ahead of the budget fix would strand its file
where the hourly sweep can no longer reach it, and only a `PIPELINE_VERSION` bump or FR-317-23's ↻ per-title
recheck would bring it back.

### The order, decided (Tróndur, 2026-10-13)

1. **This amendment first** — the house rule, and the only part that was ever blocked on anything.
2. **Both halves built together**, Sjúrður: the sidecar's Python (34, 35, 36.1, 36.2's `error_kind`) and the
   backend's reason string (36.2's `vram`, its label, its exclusion from `SKIP_REASONS`). No Kotlin beyond
   that.
3. **Backend to prod before the sidecar**, the standing order of this phase. It is belt and braces here —
   `error_kind` is additive and an old backend simply fails the row as it does today — but the order costs
   nothing.
4. **It rides in DEB-950's unshipped image if it is ready in time**, and follows in its own image if it is
   not. Either way there is **no second re-measure**: `PIPELINE_VERSION` does not move for 34–36, so the
   eleventh amendment's hazard — a swap under a running backend mislabelling rows under a stale `lastModel`
   — cannot arise, *because the model string does not move either*.
5. **The 122 files need no pass of their own.** They have no row, so they are `missing`, and the `:30` scan
   enqueues them within the hour of the sidecar landing. Nothing for an operator to run.

### Deliberately not specified

- **Raising `AVSYNC_VRAM_GB`.** It would move the cliff rather than remove it, the card is shared with
  Immich and a re-budget is the owner's call, and with 34/35 the peak drops far below today's 2 GB — so
  there is nothing to ask him for.
- **Changing `RES`, `FPS`, the 45 s window or the three window fractions.** Each changes what is measured;
  this amendment deliberately changes only *how it is held*.
- **Per-chunk VRAM telemetry in `file_av_sync`.** The 500's one line (FR-317-35) is the diagnostic; a stored
  column would be a new field on the wire for an operator's convenience.
- **A pre-flight that refuses a geometry outright.** With a byte budget there is no geometry to refuse, and
  a bar drawn today would be wrong the moment the budget moves.

### Tests (added)

- **The chunk arithmetic as a pure function** (no CUDA, no card): a byte budget and a frame size give a chunk
  count; the floor is one frame; the three census geometries — 1920 × 1080, 720 × 552, 700 × 394 — give the
  per-frame byte counts in the table above, which is also the regression that would catch a return to a
  frame-count constant.
- **Numerical neutrality, the test FR-317-34 lives or dies by**: `shots`, `detect` and `crops` over one
  synthetic ~300-frame window produce **identical** output at two different chunk sizes, and identical to
  the whole-window path they replace. Synthetic frames, **no library file**.
- **A forced OOM**: with `AVSYNC_VRAM_GB` set below one frame's working set, the floor retry raises, the
  response is a 500 carrying `error_kind: "vram"`, and its message names the geometry, the frame count and
  `max_memory_allocated()`.
- **`empty_cache` is called on the failure path** — asserted with a counter, since the whole of 36.1 is that
  it is called where nothing returns.
- `linuxX64Test`, on hand-written response documents: a 500 with `error_kind: "vram"` stores a row with
  `reason = vram` under the sidecar's model and reads `UNKNOWN` with its label; `vram` is **not** in
  `SKIP_REASONS`, so a model bump makes the row due; and **a 500 without `error_kind` behaves exactly as
  today** — no row, the lane's error sentence — which is the old-sidecar case and must not regress.

### Acceptance (added)

29. One episode of the 200-episode series in DEB-979's census comes back with a **stored row** from the
    deployed sidecar, and the sidecar's own log line for that file reports a peak under the configured
    budget. One file, not the series: the series follows from the sweep.
30. **FR-317-34 moved no number.** One file already measured under the current pipeline version is
    re-measured after the change and its stored windows are identical window-for-window and face-for-face —
    `offset_frames`, `conf`, `frames` and `audio_short_frames` all unchanged. A single differing field means
    the fix was not neutral and `PIPELINE_VERSION` owes a bump.
31. Across a full sweep after the deploy, `media_job` rows on the `avsync` lane carrying
    `OutOfMemoryError` stop appearing, and the 122 files of the census each hold a row — a verdict, or
    `unknown` with reason `vram`. **A `vram` row is an acceptable outcome of this phase and a silent 500 is
    not**: the requirement is that the library can say what it knows about every file, not that every file
    is measurable on this card.
