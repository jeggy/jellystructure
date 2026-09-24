# Phase 255 — A track that stops before the file does is found, stored, and explained

## Status

`Planned` — written 2026-09-24 from a household report, not dev-reviewed, not built. Spec first.

> *"Starhaul played without audio yesterday evening, but playing same episode again this morning
> worked. Yesterday was tested via Wholphin and today was tested via Ravilo, both on Stue TV."*

> Owner, 2026-09-24: *"Let's create a spec that makes sure this type of audio issues are being tracked
> in jellystructure and stored. So they become normal issues which will be shown on the dashboard and
> when opening the media item page in jellystructure, we'll show the warning and suggested fix. If the
> fix is to remove the audio track or whatnot, let's just write that in the suggestion."*

## What happened

*Starhaul* S02E14 (`…S02E14 (1080p WEBRip x265 10-bit SDR OPUS 2.0).mkv`) has two English audio tracks, **neither flagged default**:

| Stream | Codec | `DURATION` tag | Last real packet |
|---|---|---|---|
| `#1` (first audio) | Opus 2.0 | `00:04:41.265` | **281.25 s** |
| `#2` | AAC 2.0 | `00:22:33.728` | 1349.82 s |
| file (`format.duration`) | | | 1353.7 s |

The first audio track **stops at 4:41** of a 22:34 episode. A loudness pass from 5:00 onward finds no
audio at all on it. The file has not changed since May.

- **Wholphin** (third-party, mpv backend) plays the first audio track. On 2026-09-23 18:59 UTC it
  resumed at 5:35, past the end of that track: silence. The viewer restarted from 0:06, heard sound for
  4:41, and stopped at ~8 min (Jellyfin activity log: four start/stops in 70 s, then one 8-minute play).
- **Ravilo** played the same episode for 25 minutes the next morning with sound, so it picked the AAC
  track — by luck of its own selection rules, not because anything knew the Opus track was short.
- **jellystructure said nothing.** `issueCount` for the episode is `0`.

Every other episode of the season has one Opus track that runs the full length. This episode is the
only one of 20 with a second audio track, which is why no season-level pattern showed it.

## What the library holds (measured 2026-09-24)

A one-off sweep of every `.mkv` under both library roots (8,300+ files) compared each audio/video
stream's `DURATION` tag against the container's declared duration, flagging anything under 90%. Every
hit was then checked against **real packet timestamps** (`ffprobe -read_intervals`). Nine files were
flagged; the packets sort them into five kinds:

| File | Tags said | Packets say | Kind |
|---|---|---|---|
| *Starhaul* S02E14 | audio #1: 281 s of 1354 s | audio #1 ends **281.25 s**; audio #2 (same language) complete | **A — a short track, and a complete one in the same language** |
| *Sunny and Goose* S01E28 | audio #2 (Danish): 20 s of 429 s | Danish ends **19.99 s**; Faroese (default) complete | **B — a short track, the only one in its language** |
| *Sunny and Goose* S01E32 | audio #2 (Danish): 40 s of 429 s | Danish ends **40.00 s**; Faroese complete | B |
| *My 300-kg Life* S05E12 | audio #1: 999 s of 5010 s | the **only** audio track ends **999.08 s**; video runs to 5009.5 s | **C — the only audio track is short: silent for 66 minutes** |
| *The Ruse* S01E07 | every stream: 2873 s of 3564 s | video and audio both end **2873 s**; TMDB runtime 45 min | **E — the header claims more than the file holds; the content is complete** |
| *Blended Household* S09E14 | every stream: 1294 s of 1677 s | everything ends **1294 s**; TMDB runtime 22 min | E |
| *Beetlemania* (movie + its cross-seed link) | every stream: 3494 s of 7094 s | packets to **7087 s** | **false positive — the tags are wrong** |
| *Playroom 3*; *Blended Household* S08E15, S08E18 | video #4/#12–14: 0 s | `mjpeg` cover images, not tracks | **false positive — cover art** (phase 144's `cover_as_video` owns it) |

Two lessons the requirements below are built on:

1. **A `DURATION` tag is a hint, never a verdict.** *Beetlemania*'s tags are off by an hour in the
   file's favour. The reverse also happens by construction: a download that stopped part-way keeps the
   tags its muxer wrote for the whole file, so tags alone would call an incomplete file complete.
   Only packets are evidence.
2. **"Shorter than the header" is not always damage.** Kind E is a complete episode in a file whose
   header is wrong. It still hurts: the player shows the wrong length, and **the episode can never
   reach the 90% mark, so it is never marked watched and never leaves Continue Watching** (47:53 is
   80.6% of the claimed 59:24). jellystructure's own stored `fileDurationMs` (phase 222) carries the
   wrong header value too, so the segment editor's timeline and the end-of-file credits heuristic are
   wrong for these files.

## Why nothing reported it

- `Track` has a `durationMs` only on the **video** track, and it is the **container's** declared
  duration (phase 222), not the stream's. Nothing stores or compares per-stream length.
- `zero_audio` counts files with **no** audio track. A file with a short one has one.
- Phase 254's deep check looks for **demuxer errors**. A track that simply ends early demuxes cleanly:
  the Starhaul file produces zero damage lines. The two checks are complementary, not overlapping.
- Jellyfin reports the container duration and nothing per stream.

## Requirements

### Finding it

- **FR-255-1 — every audio and video track carries its own length.** The probe the scanner already
  runs (`FfprobeRunner`, `-show_streams -show_format`) already returns each stream's `duration` and its
  `DURATION`/`DURATION-eng` tag; neither is kept today. `Track` gains `streamDurationMs: Long?` for
  every AUDIO and VIDEO track (tag first, then `streams[].duration`, else `null`). It is **labelled a
  hint in code and in the UI** (FR-255-3). `durationMs` keeps its phase-222 meaning (the container's
  declared length) — no field changes meaning. Additive, defaulted, no migration for the JSON column.
- **FR-255-2 — one pure classifier decides what counts.** `TrackCoverage.classify(...)` (commonMain,
  unit-tested with every row of the table above) takes the container's declared length, each stream's
  kind/codec/language/default flag/disposition/hint, and (when known) each stream's *measured* end, and
  returns zero or more findings. Rules:
  - **Only AUDIO and VIDEO tracks.** Subtitles legitimately end early.
  - **Not a track:** video streams that are cover art — `attached_pic` disposition, or an image codec
    (`mjpeg`, `png`, `bmp`, `gif`, `webp`), or ≤ 1 packet. They belong to phase 144's `cover_as_video`
    and are never reported here.
  - **The reference end** is the latest *measured* end among the file's real video streams (or among
    its audio streams for an audio-only file), never the header.
  - A stream is **short** when it ends at least `max(30 s, 5% of the reference)` before the reference
    end. (Every real hit above falls short by ≥ 382 s; the thresholds exist to keep a few seconds of
    silent credit tail from ever being called a problem. Constants, named, one place.)
  - The **header is wrong** when *every* audio/video stream ends at least `max(30 s, 5%)` before the
    container's declared duration.
- **FR-255-3 — confirmed by packets, never by tags.** A finding is only ever stored from **measured
  stream ends**. The measurement is a bounded tail probe:
  `ffprobe -v error -read_intervals <D−60>%+60 -show_entries packet=stream_index,pts_time -of csv=p=0 <file>`
  where `D` is the header's duration, niced, through `SegmentProcessGate`. Measured 2026-09-24: 0.14 s /
  ~14 MB on the Starhaul file, 0.85 s / ~61 MB on a 10 GB film. Any audio/video stream **with packets
  in that window** reaches the end: done. A stream **absent from it** is resolved to its real last
  packet by a bounded follow-up: probe at its tag end (±10 s) when a tag exists; otherwise a binary
  search over `read_intervals`, at most 8 probes. If **no stream at all** has packets in the window,
  the file is kind E or an incomplete download, and the same search finds where the content ends.
- **FR-255-4 — stored, per file, keyed on what the file is.** New table
  `file_track_coverage(path PK, size, mtime, checked_at, content_end_ms, header_ms, findings TEXT)`,
  where `findings` is the classifier's JSON. Exactly phase 254's rule (FR-254-3): a row is *current*
  only while size **and** mtime match, so any rewrite makes the file unchecked again with no
  bookkeeping. Three states, never two (FR-254-4's rule): `has findings`, `checked, fine`,
  `unchecked`. *Unchecked* is never counted or rendered as fine.
- **FR-255-5 — it happens without being asked.** A `track_coverage_sweep` job on the **`segments`**
  queue (read-only work), `deferWhilePlaying = true`, deduped, bounded slice, stopping between files
  when playback starts. The same shape and loop as phase 254's `file_integrity_sweep`, and ordered the
  same way: **unchecked files, most recently modified first** (new arrivals first). New and changed
  files are also checked right after `scan_files`. Gated by `behavior.verify_files` (phase 254's
  switch — one switch for "read my files to check them", not two).
- **FR-255-6 — it happens when asked.** The title page's **Check now** (phase 254, FR-254-6) also
  runs this check for that title's files. One button, both checks, not deferrable.

### Saying it

- **FR-255-7 — Triage: two types, because they mean different things.**
  - `track_ends_early`, *"Audio or video stops before the file ends"*, severity `bad` — kinds A–D.
    Description: *"A track in these files ends early. Viewers who get that track hear silence (or see
    black) from that point on, and which track they get depends on the player. Open the title for the
    exact time and the suggested fix."*
  - `duration_header_wrong`, *"File claims to be longer than it is"*, severity `warn` — kind E.
    Description: *"Everything in these files ends before the length the file reports. The episode can
    never reach 90%, so it is never marked watched, and players show the wrong length."*

  Both appear in the Dashboard's attention breakdown and as `Library ?filter=track_ends_early` /
  `?filter=duration_header_wrong`, counted from the table (instances = files, titles = distinct
  titles), and are **omitted, not sent as 0, until a sweep has completed** (phase 203's rule, as
  `file_damage` does). Each finding counts into the episode's/movie's `issueCount` like every other
  issue. The Triage count cache is keyed on library version **and** this table's revision (phase 254's
  lesson: a finding changes the count without any library write).
- **FR-255-8 — the title's own page: the warning.** `GET /api/media/{id}/health/tracks` answers, per
  file, the state and the findings. The detail page renders one warning block per file with findings,
  beside phase 254's integrity banner, never merged into it. Each block says, in words:
  1. **what** — which track (language, codec, channel layout, title, stream number), and that it
     stops at `m:ss` of `mm:ss`;
  2. **what a viewer experiences** — *"silence from 4:41"*, *"black picture from 12:03"*, or for kind E
     *"never marked watched; the player shows 59:24 for a 47:53 episode"*;
  3. **who is affected** — whether the short track is the default, the first audio track (what a
     player with no default flag picks), or only reachable by choosing it;
  4. **the suggested fix** (FR-255-10) as text, with the exact command where there is one, with a Copy
     button.

  Series pages group per season (*"S01: 2 episodes"*), expandable to the episodes. Unchecked files get
  the same one quiet line phase 254 already renders (*"N files not verified yet · Check now"*), not a
  second one.
- **FR-255-9 — the track row says it too.** On **Tracks & subtitles** / **Seasons & episodes**, a short
  track's row carries *"ends at 4:41 of 22:34"* in `--bad` ink, so the warning and the track it is
  about can't be read apart. Tracks whose length is only a tag hint show no length at all: a hint is
  never displayed as fact.

### The suggestions

- **FR-255-10 — suggestions are text, chosen by the kind; one builder.** `TrackCoverageAdvice`
  (commonMain, pure, unit-tested) turns a finding into the suggestion text and, where one exists, the
  command. One builder, so a page can never suggest one thing in prose and another in the command.
  Paths are shell-quoted (a `'` in a filename is a unit test). The commands write to a new file beside
  the original and never overwrite it; the text says to check the new file, then swap it in.

  | Kind | Case | Suggestion |
  |---|---|---|
  | **A** | short track; a complete track in the **same language** exists | *"Remove the short track — the complete one has the same language and nothing is lost."* Command: `ffmpeg -i '<in>' -map 0 -map -0:<n> -c copy '<in>.fixed.mkv'`. Also: *"Until then, mark the complete track as the default audio (Tracks & subtitles) so players pick it."* |
  | **B** | short track; the **only** one in its language | *"The <Danish> audio stops at 0:20 — there is no complete <Danish> track in this file. Remove it (viewers choosing <Danish> currently get 7 minutes of silence), or replace the file with one whose <Danish> track is complete."* Command: the removal, as in A. |
  | **C** | the **only** audio track is short | *"This file has no audio after 16:39 and nothing in it can restore it. Re-download it: in Sonarr/Radarr, delete the file and search again."* No command — nothing local fixes it. |
  | **D** | the **video** is short | *"The picture ends at 12:03 while the sound continues. Re-download it."* No command. |
  | **E** | header wrong; content length **≈ TMDB runtime** (within 10% or 5 min) | *"The file is complete (47:53, TMDB: 45 min) but its header says 59:24. Rewrite the header with a copy remux — nothing is re-encoded."* Command: `mkvmerge -o '<in>.fixed.mkv' '<in>'` (or `ffmpeg -i '<in>' -map 0 -c copy '<in>.fixed.mkv'` for non-MKV). |
  | **E′** | header wrong; content **well short of** the TMDB runtime | *"The file stops at 31:10 but the episode is 45 minutes — the file is incomplete. Re-download it."* No command. |
  | — | no TMDB runtime | E's text without the TMDB comparison, and both possibilities named. |

  After any fix the file's size/mtime change, so the row goes stale and the next sweep re-checks it:
  the warning clears itself, with no bookkeeping (FR-255-4).

## Non-goals

- **Any write to a media file.** This phase finds, stores and explains. The owner chose suggestion
  text for the fix; a **Fix** button that runs the same command as a `media`-queue job (phase 254's
  shape: temp file, verify, quarantine, swap) is a follow-up phase, and FR-255-10's single builder is
  what makes it cheap: the job would run exactly the printed command.
- Any change in Ravilo. Ravilo happened to pick the complete track here; making its track selection
  avoid a known-short track is a possible follow-up (open question 3), not this phase.
- Subtitle tracks.
- Changing phase 254's deep check. The two checks stay separate. They share the sweep loop, the
  `verify_files` switch and the **Check now** button, not their verdicts.

## Open questions

1. **Tail-probe cost across the whole library.** At ~15–60 MB per file, a first full sweep of 8,300
   files is on the order of 200–300 GB of reads, spread across niced, playback-deferred slices. Is
   most-recently-modified-first plus the bounded slice enough, or should the first pass skip files whose
   tags already agree with the header and whose size matches the container's declared bitrate × length
   (the incomplete-download signal)? Lean: run it all once; after that it is new files only.
2. **Non-MKV containers.** MP4 has per-stream `duration` rather than tags; the tail probe is
   container-agnostic, so they are covered, but no MP4 was in the 2026-09-24 sample. Worth one test
   fixture.
3. **Should Ravilo refuse to auto-select a track jellystructure knows is short?** The server knows at
   `PlaybackInfo` time. It would fix kind A for every Ravilo viewer before anyone edits the file. Lean:
   yes, as its own R-phase, riding this phase's table.
4. **Kind B on files jellystructure made.** *Sunny and Goose* is a KVF series (the `kvf-series-onboarding`
   workflow muxes a Danish track in beside KVF's Faroese one). Two stubs of 20 s and 40 s suggest the
   Danish source for those two episodes was itself a stub. Worth checking the onboarding step's own
   output length — a finding here that jellystructure's own tooling caused should be preventable at mux
   time.

## Acceptance

1. **Unit:** `TrackCoverage.classify` on every row of the "What the library holds" table (tag hints
   and measured ends exactly as measured) returns: A for Starhaul, B for both *Sunny and Goose*, C for
   *300-kg Life*, E for *The Ruse* and *Blended Household*, **nothing** for *Beetlemania* (measured ends override
   the tags) and **nothing** for the three cover-art files.
2. **Unit:** `TrackCoverageAdvice` produces the table's suggestion per kind; shell-quotes a path
   containing `'`; its commands never name the input as the output.
3. **Unit:** a `file_track_coverage` row stops being current when size or mtime changes; a file with
   no row is `unchecked`, never fine.
4. **On production after deploy:** the Dashboard shows *Audio or video stops before the file ends* (4
   files, 3 titles) and *File claims to be longer than it is* (2 files, 2 titles); *Starhaul*'s page
   says the Opus track stops at 4:41, that players picking the first track get silence, and suggests
   removing it; *Beetlemania* and *Playroom 3* show nothing from this phase.
