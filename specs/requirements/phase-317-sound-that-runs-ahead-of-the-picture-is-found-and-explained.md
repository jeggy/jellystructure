# Phase 317 — Sound that runs ahead of (or behind) the picture is found, stored, and explained

> Owner, 2026-10-10, on a KVF broadcast (*Broadcast K*, stand-in): *"The audio is a bit out of sync … at
> around 1:09:00 and forward"* — then: *"build a warning and show this type of issue on the dashboard … and when
> opening the details page it would show it as well, with the suggested command to fix it."*

## Status

`Planned` — **ready to build.** Written 2026-10-10 (dev-authored investigation) against `main` `d37f1a7a`; owner
decisions the same day (§Decisions); **dev-reviewed 2026-10-10 against `main` `d37f1a7a`** (§Dev review — every
site this phase touches, and the six gaps the first draft left, closed in the FRs below). Number verified free
(admin specs top at 316). The measurement is prototyped and measured in `avsync/` and
`specs/research-reports/av-sync-measurement-on-the-gpu-2026-10-10.md`; nothing is wired into the backend.

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
   separately; the two coinciding on one real file has no case in today's library.**
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
