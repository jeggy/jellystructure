# Phase 222 — The segment editor plays where you clicked, and the timeline stays where the markers are

> Reported 2026-09-16, from using the Intro & credits editor:
>
> *"Video player always starts from the beginning, even when clicking on the timeline at some other
> points than the beginning. And sometimes when opening some media and it already has a credits and
> intro defined, it's not shown at all on the timeline. And sometimes it's only shown for a few seconds
> and then it goes away from the timeline."*
>
> Three symptoms, two root causes, both in the part of the editor phase **190** added to make the
> remux stream work — and the remux stream is what **6 833 of the 7 984** units the editor can open
> use, because they are MKV. Then a review of the whole tool on top, as asked: eleven further
> findings, ranked.

## Status

`Planned` — written 2026-09-16 from a live report plus a full read of `Segments.kt`, `SegmentApi.kt`,
`SegmentRoutes.kt`, `MediaSegmentStore.kt`, `FfmpegRunner.computeWaveform` and Jellyfin's own source
at tag **v10.11.11** (this house's server). Not dev-reviewed, not built. Backend + admin frontend
(`/segments`). No Ravilo half — Ravilo reads `media_segment` through `DetailService.toTv` and is
unaffected by every finding here except FR-222-5's validation, which only ever makes its input saner.

**Numbering:** verified against `STATUS.md` on 2026-09-16 — admin taken through 221.

**Evidence used**, so the dev review can re-check it rather than trust it:
- Jellyfin **v10.11.11** source: `Jellyfin.Api/Helpers/StreamingHelpers.cs`,
  `Jellyfin.Api/Helpers/FileStreamResponseHelpers.cs`, `MediaBrowser.MediaEncoding/Transcoding/TranscodeManager.cs`,
  `MediaBrowser.Controller/MediaEncoding/EncodingHelper.cs` (line numbers below are that tag's).
- A local ffmpeg reproduction of the stream shape Jellyfin serves (§2.2, §2.3) — no request was made
  to the production Jellyfin.
- A read-only copy of the production database (`media_segment`, `media`), 2026-09-16 18:38.

## 1. What the editor does today (traced against `main` `ad906857`)

**The stream.** `GET /api/segments/{itemId}/stream` (`SegmentRoutes.kt:356-390`) decides server-side
between two shapes (phase 190 FR-190-2): **direct** — `/Videos/{id}/stream?Static=true…` for a file
whose container is mp4/m4v/webm *and* whose audio is aac/mp3/opus/flac/vorbis — and **remux** —
`/Videos/{id}/stream.mp4?Static=false&VideoCodec=copy&AudioCodec=aac&AudioChannels=2&PlaySessionId=…&StartTimeTicks=…`
for everything else. The `PlaySessionId` is **deterministic**: `segmentsPlaySessionId()`
(`:73-74`) returns `"segeditor-<jellyfinId>-<episodeKey.hashCode()>"`, chosen so a reopen "reuses the
same id, which is harmless — Jellyfin's `StopEncodingProcess` is a no-op when nothing matches".

**Seeking.** `seekToAbsoluteMs` (`Segments.kt:736-754`) is the one seek entry point (track click,
±10 s, "play the cut", `I`/`O`'s playhead). Direct play sets `video.currentTime`. Remux — because
Jellyfin answers `Accept-Ranges: none` for a live transcode — sets `trimRemuxBaseMs = clamped`, asks the
server for a fresh URL with `startMs`, and assigns it to `video.src`. From then on the playhead is
`trimRemuxBaseMs + video.currentTime` (`currentVideoAbsoluteMs`, `:723-725`).

**The timeline's scale.** The server's `durationSec` is `durationSecOf()` (`SegmentRoutes.kt:562-563`):
TMDB's whole-minute `runtime × 60`, else the furthest marker edge, else 0 — the doc comment says so
("only a rough estimate … the trim view gets the frame-accurate figure from the real `<video>`").
The client then does exactly that: on `loadedmetadata`, when `trimRemuxBaseMs == 0`,
`correctDuration(video.duration)` (`:842-857`) re-labels the header, rebuilds the ruler and evidence
lane and repositions every bar against `video.duration`. Every bar is placed as
`left = start / duration × 100 %` inside a `.track` with `overflow:hidden` (`segments.css:82`).

**The markers.** `buildTrack` (`:254-267`) draws each row from `startMs` to `endMs ?: startMs` with a
0.5 % minimum width. `nudgeSegment` (`:868-884`) clamps a start edit to `[0, end − 100 ms]` and an end
edit to `≥ start + 100 ms`; the drag handles (`:951-1005`) clamp the same way. `PUT /{itemId}/{kind}`
(`SegmentRoutes.kt:240-255`) stores `startMs`/`endMs` and any `kind` string as received.

**The waveform.** `wireWaveform` (`:658-668`) asks for 150 peaks over `0 … durationSec` on every
render *and* every `refreshTrimBody`; the server (`SegmentRoutes.kt:413-431` →
`FfmpegRunner.computeWaveform`, `:260-285`) runs `ffmpeg -ss 0 -i <file> -t <window> -vn -ac 1 -ar 8000
-f s16le -` under `nice -n 19 ionice -c3` and `SegmentProcessGate`, reading the whole PCM stream into
memory before bucketing.

## 2. Root causes

### 2.1 Every seek in remux mode is served the *old* transcode from byte zero (symptom 1)

Jellyfin keys a progressive transcode's output file on the request's identity, not on where it starts:

```csharp
// StreamingHelpers.cs:376
var data = $"{state.MediaPath}-{state.UserAgent}-{deviceId!}-{playSessionId!}";
```

and serves an existing file rather than starting ffmpeg:

```csharp
// FileStreamResponseHelpers.cs:149-163
if (!File.Exists(outputPath))
{
    job = await transcodeManager.StartFfMpeg(state, outputPath, ffmpegCommandLineArguments, …);
}
else
{
    job = transcodeManager.OnTranscodeBeginRequest(outputPath, TranscodingJobType.Progressive);
    state.Dispose();
}
var stream = new ProgressiveFileStream(outputPath, job, transcodeManager);
```

`StartTimeTicks` is not in the hash. So the editor's seek — same `MediaPath`, same browser, same
`DeviceId`, same deterministic `PlaySessionId` — resolves to the **same output path as the stream it
is already playing**, which exists, so Jellyfin hands back that file from its first byte. The
`-ss` never runs. A progressive job is only killed **10 s** after its last request ends
(`TranscodeManager.cs:153`), and the browser issues the new request the instant `video.src` changes,
so the file is always still there. Meanwhile the client has already set `trimRemuxBaseMs` to the
requested offset, so the playhead reads *20:00 + t* while the picture shows *0:00 + t* — and `I`/`O`,
"play the cut"'s auto-pause and the ±10 s buttons all act on the lie.

This is also why 190's live probe "confirmed" `StartTimeTicks`: a request 20 minutes in returned in
~2 s versus ~6.5 s from the start because it *reused the running job's file* — faster, not further in.

The same hash also means two admin tabs (or two operators) on the same title share one transcode.

### 2.2 The remux stream's own duration is one fragment long, and the timeline believes it (symptoms 2 and 3)

Jellyfin's progressive mp4 is written as a fragmented MP4:

```csharp
// EncodingHelper.cs:7565
format = " -f mp4 -movflags frag_keyframe+empty_moov+delay_moov";
```

With `empty_moov` the header carries no total duration; a demuxer learns the duration from the
fragments it has read so far. Reproduced locally with a 600 s sample and those exact flags, keeping
only the first 300 KB — what a browser has at `loadedmetadata`:

```
$ ffmpeg -i sample.mp4 -c copy -movflags frag_keyframe+empty_moov+delay_moov -f mp4 pipe:1 | head -c 300000 > head.mp4
$ ffprobe -show_entries format=duration head.mp4
10.031020        ← one GOP of a 600.000000 s file
```

Chromium's demuxer is ffmpeg's, so `video.duration` ≈ one keyframe interval. `correctDuration(10.03)`
then rescales a 45-minute timeline to ten seconds: every bar's `left` becomes hundreds or thousands
of percent and it leaves the visible track; the ruler collapses to a single `0:00`; the header reads
`00:10`. The markers were drawn correctly against the TMDB estimate at first paint and vanish when
`loadedmetadata` arrives — which for a remux is the 2–7 s ffmpeg spin-up phase 190 measured. That is
symptom 3 exactly. When the job is still warm (reopened within 10 s, per §2.1) `loadedmetadata`
arrives in well under a second and the bars are gone before the eye registers them — symptom 2.

There is a second way a marker is "not shown at all", independent of the stream: the TMDB estimate
is *shorter than the file* for most credits. In production, **2 152 credits markers start after
`runtime × 60 s`** and 201 intro ends do; those bars are drawn beyond 100 % and are clipped before any
video loads. Direct play repairs that within a second (its `video.duration` is real); remux never does.

### 2.3 Even a working seek lands one keyframe early, and the playhead does not know

Jellyfin emits `-ss <StartTimeTicks>` before `-i` and, for a progressive job, **without**
`-noaccurate_seek` (`EncodingHelper.cs:2985-2992` excludes `TranscodingJobType.Progressive`). With
`-c:v copy` ffmpeg cannot cut inside a GOP, so the stream begins at the keyframe at or before the
requested time, and the browser's clock starts at zero *there*. Reproduced:

```
$ ffmpeg -ss 303 -i sample.mp4 -c copy -copyts -movflags frag_keyframe+empty_moov+delay_moov -f mp4 pipe:1 | head -c 400000 > s.mp4
$ ffprobe -select_streams v -show_entries packet=pts_time,flags s.mp4 | head -1
300.000000,K__   ← requested 303, stream starts at 300; without -copyts the first pts is -3.0
```

So once FR-222-1 makes seeks real, `trimRemuxBaseMs + currentTime` overstates the picture's position
by up to one GOP (1–10 s on typical x264/x265 rips) after every seek — a frame the operator marks with
`I` is later than the frame they are looking at. A trim tool cannot carry that silently.

## 3. Review findings (everything else, ranked)

**F1 — The waveform reads the entire file, per open, per edit, for 150 numbers.** `computeWaveform`
demuxes the whole interleaved file to decode its audio (`-vn` skips decoding, not reading), so opening
the trim view of a 60 GB 4K remux is a 60 GB linear read — the exact I/O class of the 2026-09-15 stall
(phases 212–215) — and `refreshTrimBody` repeats it after every lock, add, remove and Jellyfin-apply.
`ionice -c3` does nothing on this host (mq-deadline ignores it; phase 212's headline finding). The
whole 8 kHz PCM stream is held in memory before bucketing (a 4 h window, the route's cap, is 230 MB in
the native process), and `captureBinaryCommand` reads to EOF with no cancellation path visible, so
navigating away does not stop it. And the result is unreadable: 150 buckets over a 45-minute episode
is one peak per **18 s** — every bucket saturates near 100 %.

**F2 — A credits marker cannot be moved later.** 7 627 of 7 628 credits rows have `end_ms = NULL`
(detection writes credits as "from here to the end"; `PipelineStepOps.kt:361/378`). The client treats
`endMs ?: startMs` as a zero-length marker: the bar is a 0.5 % sliver, the row reads *"0:00 long"*, and
`nudgeSegment`'s `coerceIn(0, end − 100)` turns **+1 s into −100 ms** — both steppers and the drag
handle move the credits start *earlier* only. Editing the "end" edge writes an explicit `end_ms` a
second after the start, silently changing the marker's meaning.

**F3 — Writes are not validated.** `PUT /{itemId}/{kind}` accepts any `kind` string, negative
`startMs`, `endMs < startMs`, and times past the end of the file; `I`/`O` at a playhead on the wrong
side of a marker produce an inverted row today (`applyEditInPlace` with `start > end`). Production
has none yet — only **4** manual rows exist at all, which is what a tool that seeks to zero and loses
its markers produces.

**F4 — `refreshTrimBody` discards the corrected duration.** Every structural refresh re-fetches the
trim response and rebuilds the timeline against the *server's estimate* again, so even in direct play
the scale flips between "real" and "TMDB minutes" on every lock/add/remove, and the waveform (fetched
once against the estimate) no longer lines up with the ruler.

**F5 — The consensus math runs on estimates.** `computeConsensus` derives the credits lead from
`durationSec − creditsStart` and `applyConsensusToTargets` (`SegmentRoutes.kt:508-533`) places
credits at `estimate − lead`; for the 515 segment-bearing units with no TMDB runtime the "duration" is
the credits start itself, so the lead is 0. Only the intro branch is reachable from the UI today, so
this is latent — but it is the design for "give them the season's credits" and would write wrong rows.

**F6 — 392 orphan units.** `media_segment` holds rows for 392 `(item, episode_key, episode_number)`
keys that match no episode in `media` (renames and re-scans since; nothing prunes below item level —
only `deleteSegmentsForItem` exists). They are invisible to the editor and Ravilo, count nowhere, and
survive every sweep.

**F7 — A race on open.** `wireVideo` requests the stream with `startMs = trimPlayheadMs` and sets
`trimRemuxBaseMs` from `trimPlayheadMs` *when the response arrives*; a timeline click in between (or a
failed `streamInfo` in `seekToAbsoluteMs`, which leaves the new base with the old stream playing)
desynchronises playhead and picture with no recovery until the next seek.

**F8 — "＋ Credits" is placed off the end.** The add-row defaults an end-anchored marker to
`estimate − 60 s`; when the estimate is shorter than the file the new credits marker is *past the real
end* and, on remux, invisible (§2.2). With `durationSec = 0` it writes `start = 30 s, end = 0`.

**F9 — Unchecked writes.** `goNext` toasts *"confirmed"* and navigates regardless of `setChecked`'s
result; `bulkLock` and the drawer's lock/ok ignore theirs. 189 FR-189-6 made this the rule for the
trim view's edits; these four paths predate it.

**F10 — The ruler is not clickable.** `wireEditableRegion`'s comment says "ruler included", but the
click handler is on `#seg-track` and only fires on `.grid`; clicks on the ruler, a bar, or the
playhead line do nothing. Also the transport controls are `<span>`s — not focusable, no keyboard.

**F11 — `▶` on a stream that has not loaded** calls `video.play()` on a `src`-less element (rejected
promise, console noise) while the badge still says *checking playback…*; a remux that Jellyfin refuses
(a 5xx) shows the generic *can't play this file here* with no status.

## 4. Requirements

### FR-222-1 — A seek starts a new transcode at the requested offset

Every remux stream the server mints carries a **unique** `PlaySessionId` (the deterministic prefix
plus a nonce or counter; the server mints it, the client never composes one). A seek requests a new
stream and, in the same call or immediately after, the server issues `DELETE /Videos/ActiveEncodings`
for the **previous** id — by `PlaySessionId`, never by `DeviceId` alone, so a second tab on the same
title is not killed. Because the new id hashes to a new output path, correctness does not depend on the
old file being deleted first (Jellyfin's delete retries on a 500 ms/1.5 s ladder; the request would
win the race). Teardown on leaving `/segments` and on switching titles stops the *current* id.

Acceptance: with a burned-in-timecode sample in a test library, click the timeline at 20:00 — the
first frame shown reads 19:5x or 20:00, and Jellyfin's log shows a second ffmpeg with `-ss 00:20:00`.

### FR-222-2 — The playhead states where the picture is

The stream response carries `startedAtMs`: the media time of the first frame the stream will
contain, i.e. the keyframe at or before the requested offset. The server finds it with a bounded
probe (`ffprobe -select_streams v -show_entries packet=pts_time,flags -read_intervals "<T>%+#1"` —
88 ms on the local sample, one seek and a few packets on a remote file) or by any equivalent means;
it may not guess. The client sets its base from the **response**, never from its own state at request
time (closes F7), and the `I`/`O`/±/drag paths read that base. Direct play is unchanged.

Acceptance: after any seek, the burned-in timecode of the displayed frame and the playhead label agree
within one frame.

### FR-222-3 — One true duration, stored, and the timeline never rescales itself

`Episode` and `MediaItem` gain `durationMs: Long?`, written from **jellystructure's own ffprobe** —
`format.duration` is already in the JSON the scanner parses (`FfprobeRunner.kt:125` has carried
`-show_format` since phase 185) and `detect_segments` already calls `FfprobeRunner.duration()`
(`PipelineStepOps.kt:371/700`) and throws the answer away. No new process spawn; backfilled by the
next examination of each file (196's `last_examined_at` path) and by every detect run. TMDB's runtime
is never used as a duration again.

The sheet and trim responses carry `durationSec` from it, plus `durationSource: "file" | "markers" |
"unknown"` so the client can say *"length not measured yet — re-scan to fix"* instead of drawing to an
estimate. The client **does not** rescale from `<video>.duration`: `correctDuration` is retired (or
kept as a logged consistency check in direct mode only, never an override), `refreshTrimBody` keeps the
scale it had (closes F4), the waveform and evidence lane share the number, and a marker that lies past
`durationMs` is drawn clamped at the end with a warning chip rather than clipped out of existence.
The `[data-add]` defaults (F8), `computeConsensus` and `applyConsensusToTargets` (F5) run on the same
field.

### FR-222-4 — A marker with no end runs to the end

For `credits` (and any kind detection stores start-only), `endMs == null` means "to the end of the
file": the bar is drawn from `startMs` to `durationMs`, the row reads *"to the end"*, the ± steppers and
the left handle move the **start** in both directions with no upper clamp but the duration, and the
end edge is not offered unless the operator explicitly gives it one. Nudging a start never rewrites
the end (closes F2). `nudgeSegment`'s clamp can never throw (`coerceIn` with `min > max`).

### FR-222-5 — Writes are validated, and every write is checked

`PUT /{itemId}/{kind}` rejects with **422** and a reason: a `kind` not in `SegmentKind.ALL`;
`startMs < 0`; `endMs != null && endMs <= startMs`; `startMs` (or `endMs`) past `durationMs + 2 s` when
the duration is known. `I`/`O` clamp instead of inverting and say so in the toast. `goNext`,
`bulkLock`, `applyConsensus` and the drawer actions report a failed write instead of a success toast
(closes F3, F9).

### FR-222-6 — The waveform is computed once, in the background, and stored

A file's peak envelope is computed at most once, at ≤ 2 s per bucket, on the segments lane (`detect_segments`
already reads the file; the envelope rides that pass, or a one-off backfill under `GateClass.BACKGROUND`
through 213's queues), stored next to the evidence, and the request path only reads it. An absent
envelope renders an empty lane — the trim view never spawns ffmpeg to draw it, and a structural
refresh never re-fetches it (closes F1). The full-file lane keeps the whole file; a second strip at
±60 s around the selected marker, sliced from the same stored envelope, is what the operator judges a
boundary on.

### FR-222-7 — Orphan rows are pruned

When an examination of a series finds that a `media_segment`/`segment_evidence` key no longer matches
any episode (renamed, removed, or renumbered), the rows are deleted and one History line records it per
title. A one-off sweep clears the 392 present today and reports the count (closes F6).

### FR-222-8 — The player is honest while it is starting

The badge distinguishes *checking playback…* (deciding the shape) from *starting the stream…*
(remux spin-up, up to ~7 s) from *playing*; `▶` before the stream exists is a no-op with the same
badge, not a rejected `play()`; a stream URL that Jellyfin refuses shows the status it refused with.
The ruler and the bars accept a click to move the playhead; the transport controls are real buttons
with `Space` bound to play/pause (closes F10, F11).

## 5. Non-goals

- HLS.js or any client-side HLS — 190's open question 4 stands; a working reload-per-seek is enough
  for an editing monitor.
- Transcoding video (190 FR-190-1 stands; the keyframe snap in FR-222-2 is the honest cost of copy).
- Changing detection, consensus rules or the sheet's layout beyond what the duration field fixes.
- Proxying the stream through jellystructure (`jellyfin_url` must be browser-reachable today, as it
  is for every Ravilo device; unchanged).
- Ravilo's Skip Intro/Credits (R182) — consumes the same rows, gains only saner input.

## 6. Verification

1. **Unit (backend):** `segmentsPlaySessionId` is unique per mint and stable in prefix; the stream
   route's `startedAtMs` equals the keyframe ffprobe reports for a fixture; `PUT` rejects each FR-222-5
   case with 422; `durationSecOf` prefers `durationMs` and labels its source; the orphan sweep removes
   exactly the keys with no episode; `computeConsensus` on fixtures with real durations.
2. **Unit (frontend, pure functions):** bar geometry for `endMs == null` reaches `durationSec`;
   `nudgeSegment` never throws and moves a start-only marker both ways; the base is taken from the
   response, not the request.
3. **Live, on a test library with a burned-in-timecode MKV** (the 600 s `testsrc2` sample used here,
   remuxed to MKV with an AC-3 track so it takes the remux path): FR-222-1 and FR-222-2's acceptance
   lines; the intro and credits bars of a real episode stay put through `loadedmetadata`, a lock, a
   remove and an add; the credits bar reaches the end; opening the trim view of a 4K remux produces
   **no** ffmpeg spawn in the log after FR-222-6's backfill has run.
4. **Data:** after one full examination pass, the count of segment-bearing units with `durationMs`
   null is 0 (515 have no TMDB runtime today; every one has a file).

## 7. Open questions

1. **Keyframe snap vs. re-encode of the first GOP.** FR-222-2 tells the truth about a one-GOP-early
   start; an alternative is to ask Jellyfin for accurate seeking by transcoding video, which 190
   forbids. Recommendation: snap, and show the snapped time in the toast ("stream starts at 19:57").
2. **Where the envelope lives** — a blob column on the episode, a row per file in `segment_evidence`,
   or a sidecar file under `artwork/` like 220's presized variants. Recommendation: a compact blob
   (one byte per bucket) on a small new table keyed like `media_segment`, so 220's pattern of "the
   pipeline produces what the request path reads" holds.
3. **Whether `durationMs` should also feed Ravilo's detail payload** (R222's slow-to-start note, 149's
   combined rows both estimate from runtime today). Out of scope here; worth a line in the next
   Ravilo sync.
4. **`UserAgent` in Jellyfin's hash** means the same operator in two browsers already gets two
   transcodes; nothing to do, noted so the per-tab argument in FR-222-1 is not over-built.
