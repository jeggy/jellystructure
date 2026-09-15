# Phase 213 — `prewarm_subtitles` becomes a serialized job lane, and stops out-running playback

**Status:** Planned
**Authored:** 2026-09-15 (design-authored with the owner, not dev-reviewed)
**Depends on:** Phase 109 (media job queue), Phase 164 (the segments lane — the pattern this copies),
Phase 178 (defer while playing), Phase 182 (gate partitioning)
**Related:** Phase 179 (introduced `prewarm_subtitles`), Phase 207, Phase 210 (its two prior bug fixes)
**Sibling:** Phase 212 (Jellyfin settings advisor) — 212 is advisory and is explicitly *not* a fix for
this incident.

---

## 1. The incident

2026-09-15, ~17:35 UTC. A viewer (`aleks`, BRAVIA 4K GB ATV3) was 30 minutes into *The Godfather* when
playback began stalling roughly every 10 seconds and stayed that way.

Everything about the title was fine and stayed fine:

- 19.3 Mbps total, 18.6 Mbps HEVC Dolby Vision P8.1 — far below the device's 60 Mbps decode ceiling.
- `PlayMethod: DirectPlay`, `TranscodingInfo: null`. Jellyfin chose correctly; no transcode was ever
  involved.
- Link `ethernet`, 100 Mbps. `video_decoder: OMX.MTK.VIDEO.DECODER.HEVC`, `dropped_frames: 0`.

The `playback_qoe` row recorded `rebuffer_count: 3`, `rebuffer_ms: 111124` — 111 seconds of stall on a
file needing a steady ~2.4 MB/s.

**The disk was the bottleneck, and jellystructure caused the load.** Measured during the stall:

| Signal | Value |
|---|---|
| `sdc` (`/mnt/media`, rotational) utilisation | **80.8 %**, 205 MB/s read |
| `r_await` / queue depth | **74 ms** / **20.8** |
| I/O pressure `full` avg10 | **53 %** |
| CPU pressure `some` avg10 | 0.15 % |
| Concurrent Jellyfin subtitle-extraction ffmpegs | **10**, oldest 17 min, still climbing |

Every one of those ffmpeg processes was a full linear read of a UHD remux off the same spindle the
Godfather was streaming from — *Despicable Me 4* (32 subtitle tracks in one pass), *Dream Scenario*,
*Enola Holmes*, *Enola Holmes 2*, *The Last Viking*, *1922*, *The Bad Guys*, *Everything Everywhere All
At Once*. All of them were requested by jellystructure's `prewarm_subtitles` step.

## 2. Root cause

### §2.1 The step has no bound on the work it creates *inside Jellyfin*

`PipelineStepOps.prewarmSubtitles` (`PipelineStepOps.kt:96`) walks every non-external text subtitle
stream on an item and calls `JellyfinClient.warmSubtitleExtraction` per stream. Each call is a `GET` of
`/Videos/{id}/{id}/Subtitles/{index}/0/Stream.vtt`, which makes Jellyfin spawn an ffmpeg that reads the
whole source file.

The step runs through `runPipelineStepPool` at `pipelineStepConcurrency(...)` — scan-worker parallelism,
4 here — so four items are in flight, each looping its own stream list.

Every one of jellystructure's own resource gates was reporting idle while this happened. From its own
diagnostic line at 17:44:54:

```
workers=4/4 outboundHttp(shared=9/48 reserved=0/16 waiting=i0+b0)
             processGate(shared=0/12 reserved=0/4 waiting=i0+b0)
```

`processGate` shows 0 of 12 in use while the disk sat at 81 %. **Phase 182 partitioned the gates that
govern work in jellystructure's own process; this step's cost lands in Jellyfin's process, where no
gate can see it.** Nine outbound HTTP permits looks like nothing. Behind them were ten ffmpegs reading
UHD remuxes. The gate accounting is not wrong — it is measuring the wrong process.

### §2.2 A client timeout does not stop the server's work

`warmSubtitleExtraction` gives up at 120 s, logs, and returns `false`
(`JellyfinClient.kt:946-957`). Jellyfin's ffmpeg keeps running — it is filling its own subtitle cache,
not serving the abandoned response. The loop then proceeds to the *next* stream index and issues
another request, which starts another extraction.

The logs show precisely this walk, same items, index climbing, ~120 s apart:

```
17:42:38  item=442799400f… index=5   Request timeout has expired
17:44:38  item=442799400f… index=8
17:46:38  item=442799400f… index=9
17:48:38  item=442799400f… index=10
```

So abandoned work accumulates monotonically. Ten live extractions at 17:50 was not a steady state; it
was still rising.

### §2.3 The playback guard could not have fired, for two independent reasons

`awaitPlaybackClear` (`PipelineEngine.kt:96-120`) is the Phase 178 protection, and it works — earlier
runs deferred correctly:

```
17:00:26  Pipeline deferred — TV playing (BRAVIA 4K VH21)
17:10:00  Pipeline deferred — TV playing (BRAVIA 4K VH21, BRAVIA 4K GB ATV3)
```

It did not fire for the run that caused this. Two defects, either sufficient alone:

**(a) Manual runs are never eligible to defer.** `MediaRoutes.kt:2325`:

```kotlin
val deferEligible = triggerKind == "scheduled" && configStore.current.scan.deferWhilePlaying
```

`awaitPlaybackClear` returns immediately on `!deferEligible`. The run in question started at 17:08:50
logging `(full — no freshness filter)` — a manual/resume trigger, so `deferEligible` was `false` and
the guard was inert for the whole run, across every step.

**(b) The guard is a step-entry gate, not a continuous one.** Even when eligible, it is checked once
before the step begins. `prewarm_subtitles` then runs for tens of minutes with no further check. A
viewer who presses play one second after the gate opens gets no protection at all for the rest of the
step. The step's own comment claims the re-check makes it safe *"real disk/CPU work on the same media
files a TV might now be reading"* — it re-checks at step entry, which is not the same guarantee.

### §2.4 Timeline

| Time (UTC) | Event |
|---|---|
| 17:04:03 | backend container start |
| 17:05:13 | `PlaybackInfo item=a26d9808… directPlay=true` — the Godfather starts |
| 17:08:50 | `Pipeline starting … (full — no freshness filter)` — manual run, `deferEligible=false` |
| 17:34:34 | `Pipeline step: prewarm_subtitles` — **no deferral line**, playback active 29 min |
| ~17:35 | stalls begin, ~30 min into the film |
| 17:50 | 10 concurrent extractions; `sdc` 80.8 %; still climbing |

The step start and the reported onset of stalling are the same moment.

## 3. The fix

**`prewarm_subtitles` stops being a pipeline step that fans out, and becomes a job lane that does one
thing at a time** — exactly what Phase 164 did for `detect_segments` (owner decision, 2026-09-15).

This is the right shape rather than a convenient one, because `MediaJobQueue`'s segments lane already
solves all three defects in §2, and solves §2.3 in a way a step-level fix cannot:

```kotlin
// MediaJobQueue.kt:355-365 — the segments lane's claim loop
val playing = if (queued.any { it.deferWhilePlaying() }) isPlaybackActive() else false
val next = queued.firstOrNull { !playing || !it.deferWhilePlaying() } ?: return@withLock null
```

Playback is re-evaluated **at every dispatch**. A queue that checks before each unit of work degrades
gracefully when a TV starts mid-run; a step that checks once at entry cannot.

### FR-213-1 — a `subtitles` lane on `MediaJobQueue`

A third lane beside `media` (FIFO-1) and `segments` (`behavior.segment_workers`), following the Phase
164 pattern: persistent `media_job` rows, a claim mutex, WS snapshots, cancel support, and live
worker-count re-polling.

**Default concurrency 1** (owner decision). Unlike segments, whose work is CPU-bound and locally gated
by `SegmentProcessGate`, this lane's cost is remote disk I/O that jellystructure cannot meter (§2.1).
When the only lever is how many you start, the safe default is one. Configurable as
`behavior.subtitle_workers`, default `1`.

### FR-213-2 — one job per item, deferrable by default

The pipeline step is replaced by an enqueue pass. Each item becomes one `prewarm_subtitles` job with
`MediaJobParams.deferWhilePlaying = true`, deduplicated on `(media_id, type)` against active rows so a
re-run cannot stack duplicates — the `SegmentEnqueueResult.deduped` shape already exists for this.

`deferWhilePlaying` is `true` **regardless of trigger kind**. This deliberately diverges from
`PipelineEngine`'s use of `deferEligible` for segment jobs (`PipelineEngine.kt:491`). Subtitle
pre-warming is never urgent: it is a cache warm for a playback that has not happened yet, and there is
no operator intent — not even an explicit manual scan — that is worth stalling a live film for. An
operator who genuinely wants it now still has "Run anyway" (FR-178-4).

### FR-213-3 — a per-stream playback check

Within a single job, playback is re-checked **between streams**, not only when the job is claimed. One
item can hold many streams (32 on *Despicable Me 4*), each costing a full-file read; a job that started
legitimately must not keep extracting through a film that began after it was claimed.

On observing active playback mid-item the job stops cleanly and re-queues its remainder. Streams
already warmed stay warmed — Phase 210's `onStreamWarmed` callback already credits confirmed progress
under cancellation, and that contract carries over unchanged.

### FR-213-4 — a bounded wait, and no silent re-request

`warmSubtitleExtraction`'s 120 s timeout stands, but a timeout now **abandons the rest of that item's
stream list** instead of proceeding to the next index. A timeout means Jellyfin is already busy
extracting from this file; issuing the next request against the same file is the behaviour that built
the backlog in §2.2.

The item is re-queued with backoff. Phase 210's `CancellationException` rethrow must not regress: a
deadline-cancelled call still propagates, and still reports honestly rather than as success.

### FR-213-5 — the pipeline step becomes an enqueue

`"prewarm_subtitles"` in `PipelineEngine`'s step loop enqueues and returns, like Phase 164's
`detect_segments`. It no longer calls `runPipelineStepPool`, no longer blocks the run, and no longer
reports per-stream counts inline — the Jobs page owns progress.

`awaitPlaybackClear` at the step's head is **removed**, not kept: with FR-213-2 and FR-213-3 the
deferral is continuous and per-unit, and leaving a one-shot gate in front of an enqueue would only
delay the enqueue while implying a protection the lane now provides properly.

### FR-213-6 — fix `deferEligible` for the steps that keep it

Independent of the lane, §2.3(a) is a live defect for every *other* deferrable step. `MediaRoutes.kt:2325`
restricts deferral to `triggerKind == "scheduled"`, so a manual full scan runs `fetch_artwork` and
enqueues segment jobs with `deferWhilePlaying = false` while a TV plays.

`deferEligible` becomes `configStore.current.scan.deferWhilePlaying` alone, for every trigger kind.
"Defer while playing" is an operator preference about the household, not about which button started the
run, and FR-178-4's "Run anyway" already exists as the explicit override — which is the correct place
for that intent, because it is a decision made *while* the TV is playing rather than inferred from a
button pressed 26 minutes earlier.

### FR-213-7 — make the invisible load visible

Phase 182's health output reports gates that were all idle during a disk-saturating incident (§2.1).
`GET /api/health` gains a `subtitle_lane` block (queued, running, deferred-by-playback, last failure)
so the state that mattered is at least reportable.

This is **not** a claim to measure Jellyfin-side cost — jellystructure cannot see that process. It only
makes the count of outstanding requests legible, which was the missing number tonight.

## 4. Non-goals

- **No change to what gets warmed.** Every embedded text subtitle stream, same as Phase 179. The
  fan-out shape is the defect, not the working set.
- **No Jellyfin-side cancellation.** There is no API to stop an in-flight extraction; §2.2 is mitigated
  by not issuing the requests, not by recalling them.
- **Ravilo is untouched.** No client, DTO or payload change.
- **Not a fix for Jellyfin's configuration.** Chapter-image, trickplay and LUFS extraction generate
  comparable full-file reads and are Phase 212's subject.

## 5. Open questions

1. **Should the lane pause on I/O pressure rather than only on playback?** `/proc/pressure/io` is
   readable from the container and read **53 % `full`** during the incident. That is a far more direct
   signal than "is a TV playing" — it would have caught this even with no playback. Attractive, and
   unspecified here because a pressure threshold that is wrong in either direction is worse than the
   playback check: too low and the lane never runs on a busy server, too high and it does nothing.
2. **Retention/backoff for a repeatedly-timing-out item.** FR-213-4 re-queues with backoff but sets no
   ceiling. Four items (`e6639307…`, `442799400f…`, `c79198bd…`, `679cc343…`) timed out continuously
   for the entire window. If a file cannot be extracted in 120 s it likely never can, and retrying it
   nightly forever is its own slow leak.
3. **Is pre-warming worth it at all for large remuxes?** Phase 179 cited a 4m37s cold extraction on a
   26 GB file as the motivation. That reasoning holds for a file someone is about to watch; whether it
   holds for warming *every* stream of *every* title — 32 tracks on a film nobody has opened — is worth
   re-deriving now that the cost is measured. Narrowing the working set would shrink this phase.
4. **Does FR-213-6 change behaviour operators depend on?** Someone may be relying on a manual scan
   overriding playback deferral. FR-178-4's "Run anyway" covers it, but the change is silent.
5. **Concurrency 1 across a multi-disk library set.** `/mnt/media` and `/mnt/series` are separate
   spindles; one global worker serialises across both. Per-device lanes would be better and are not
   specified — FR-212-6 already resolves a path to its backing device, so the input exists.

## 6. Verification

- Reproduce §2.4: start playback, trigger a manual full run, confirm pre-warm work **does not** begin.
- Start a job with nothing playing, then start playback mid-item; confirm it stops between streams
  (FR-213-3) and that already-warmed streams stay credited.
- Confirm at most `behavior.subtitle_workers` extraction ffmpegs exist in the Jellyfin container at any
  time — the direct check the old code could not satisfy.
- Confirm a 120 s timeout abandons the item rather than advancing the index (FR-213-4), by watching for
  the absence of the climbing-index pattern in §2.2.
- Regression: Phase 210's honest-reporting behaviour under cancellation, and Phase 207's
  "every lookup failed" WARN, must both still hold.
