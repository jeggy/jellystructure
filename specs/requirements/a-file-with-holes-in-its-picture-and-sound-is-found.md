# A file with holes in its picture and sound is found, stored and explained

Task: DEB-3633

> Owner, 2026-11-04 23:24Z (on DEB-3627): *"I also ran the next episode and it works, but was jumping a few
> seconds forward every now and then. I'm watching on stue tv."*
>
> Owner, 2026-10-30 (DEB-3093), the bar every playback fault is held to: quality may drop, but the viewer
> never loses part of the film.

## Scope

Backend and admin. Ravilo is not changed and no client reads anything added here, so the wire rule does not
bind it. It adds a third finding to the `verify_files` step's single read of a file (phase 254's deep check,
scheduled by phase 261), a table, a Triage type and a block on the title page. It amends **FR-254-1** (the
deep check's command now also yields the file's packet timeline; dated amendment in phase 254's file) and
**FR-254-9** (a source copy is only a source if its own timeline is whole). It writes no media file and never
calls Sonarr or Radarr.

## What happened, measured 2026-11-04

- **The play.** Android TV in the living room, direct play from Jellyfin (`playback_qoe.encoder='direct'`,
  H.264 decoder, first frame 439 ms, no rebuffer, no stall, no seek). Sampled every 20–40 s from the session
  row: position ran at 1.000× wall clock, except for one step of **+2.2 s in 20 s**, at 19:09–19:31 of the
  episode. 1 000 s after the first frame the position stood **67 s ahead** of the wall clock, with no pause
  and no seek.
- **The file.** One read of every packet (`ffprobe -show_entries packet=pts_time,duration_time`, the same
  one-pass cost as phase 254's deep check): **58 stretches of 0.5 s or more where neither the picture nor
  either audio track has a single packet, 1:31 in all, the longest 3.9 s.** One of them is at 19:28.0, 1.9 s
  (2.2 s on the default audio track), the jump measured live. The holes before the 1 000 s mark add up to
  66 s, which is the 67 s the position had gained. They sit on a 2 s grid, which is what a web capture that
  dropped HLS segments leaves behind. The Matroska statistics tags still claim the full count
  (`NUMBER_OF_FRAMES=71253`; 68 689 are in the file), so a header read cannot see it.
- **Not the player and not our encoder.** The TV's player played through each hole, as it should: there is
  nothing to show. Through our encoder the same stretch freezes the picture and leaves the sound silent
  (ffmpeg fills the video at constant frame rate, not the audio). The segment grid stays aligned: 35 files
  for 70 s, each on its 2 s slot, measured on the first 70 s, which hold 34 holes. Neither route can play
  what is not in the file.
- **Why nothing said so.** Prod had run both file checks on all five damaged files: `file_integrity`
  damage 0 (one of them flagged for its header damage, DEB-3627) and `file_track_coverage` `[]`. A demux pass
  prints nothing for a hole, because the container is valid. The tail probe sees every track reach the end.
  *The check found nothing* was not *nothing is wrong*, phase 254's own lesson one level down.

## What the library holds (measured 2026-11-04)

| Set | Files | With holes | Video missing per file (gaps ≥ 0.5 s) |
|---|---|---|---|
| One release group's web capture, one season | 5 | **5** | 58 s – 8 min 33 s |
| The same season, another group | 5 | 0 | — |
| The same group, another series and broadcaster | 6 | **3** | 4 s – 1 min 57 s |
| Other releases from the first broadcaster's site | 34 | 0 | — |

Only one device has played any of the eight damaged files since QoE rows began (2026-08-28): the owner's TV,
on 2026-11-04. The library-wide count is not measured. A census is a read of every byte, and on this host
`ionice` does nothing under `mq-deadline` (phase 254, open question 2), so an investigator's census competes
with live playback. The sweep this spec adds is the census, deferred while anyone plays.

## Requirements

### Finding it

- **FR-1 — the same read, never a second one.** The deep check of FR-254-1 yields the packet timeline as well
  as the damage lines, in **one** pass over the file. For example, `ffmpeg -nostdin -v error -i <f> -map 0 -c copy
  -f framecrc -` prints one line per packet (stream, dts, pts, duration, size) on stdout, and the demuxer's damage
  on stderr, which `FileIntegrity.damageLines` keeps reading unchanged. The engineer may pick a different
  command if it is still one read. The timeline is **streamed** and folded as it arrives. It is never held whole
  in memory, because a long film has more than half a million packets. Niced, through `SegmentProcessGate`, as
  today.
- **FR-2 — one pure classifier decides what is a hole.** `TimelineHoles` (commonMain, unit-tested) folds
  the packets of the file's **main video stream** and of **each audio stream**. Cover art is never the main
  video (phase 255's rule: `attached_pic`, an image codec, ≤ 1 packet). Subtitles never count.
  - For one audio track, a **hole** is a stretch of at least **0.5 s** in which neither the main video nor
    that audio track has a packet. A packet covers `[pts, pts + duration)`. A video packet with no duration
    covers one frame at the stream's frame rate.
  - Video alone pausing is **not** a hole: a still title card in a variable-frame-rate file has no
    packets, and its sound continues. Sound alone pausing is not a hole either, because the picture continues.
    Both have to stop.
  - The file's figures are its **default audio track's** (else the first audio track's): hole count,
    total missing, longest, and the first 50 holes as `(start_ms, length_ms)`. The worst audio track's
    total is kept beside them when it differs by more than 1 s. A viewer on the audio-description track can
    get a different jump from one on the main track (2.2 s against 1.9 s, measured on the same hole).
  - A file is **whole** with zero holes. Constants are named and kept in one place.
- **FR-3 — stored per file, keyed on what the file is.** New table `file_timeline(path PK, size, mtime,
  checked_at, hole_count, missing_ms, longest_ms, worst_track_missing_ms, holes TEXT)`. The same rule as
  FR-254-3: a row is current only while both size and mtime match. There are three states, never two:
  `has holes`, `checked, whole`, `unchecked`. *Unchecked* is never counted or shown as whole. A pass that did
  not finish (killed, deferred mid-file, ffmpeg missing) stores nothing.
  *Amended 2026-11-05 (DEB-3642):* the row also stores `empty_streams TEXT`, the streams with no packet over the
  whole read, in the same migration. See `a-damaged-file-still-plays.md`, FR-1.
- **FR-4 — when it runs.** It runs on the `verify_files` step (phase 261), one job per file, no new step and
  no new switch. That step is **due** for a file when `file_integrity` or `file_timeline` lacks a current row
  (FR-261-7's rule, applied to both tables). So every file verified before this ships becomes due once. That
  one backfill is ordered **files any device played in the last 30 days first, newest play first**, then
  FR-261-3's order. The files a viewer is actually watching are answered first, not in five years.
  `deferWhilePlaying` stays on, and **Check now** runs it for a title with no deferral (FR-254-6).

### Saying it

- **FR-5 — Triage.** A new type `timeline_holes`, *"Picture and sound skip ahead"*, severity `bad`.
  Description: *"These files are missing stretches of picture and sound. Players jump over them, so viewers
  lose those seconds of the episode. Nothing in the file can put them back."* It appears on the Dashboard's
  attention breakdown and as `Library ?filter=timeline_holes`. It is counted from the table (instances = files,
  titles = distinct titles) and **omitted until a sweep has completed**, as `file_damage` is (phase 203).
  Each file with holes counts once into its episode's or movie's `issueCount`. The Triage count cache is keyed
  on library version **and** this table's revision (phase 254's lesson).
- **FR-6 — the title's own page.** `GET /api/media/{id}/health/timeline` answers, per file, the state and the
  figures. The detail page shows one block per file with holes, beside phase 254's integrity banner and phase
  255's track block, and never merged into either. The block says, in words, with every figure decided
  server-side:
  1. **what** — *"Picture and sound are missing in 58 places, 1:31 in all. The longest gap is 3.9 s, at 0:24."*
     — with the first holes listed as `m:ss (n.n s)`, expandable;
  2. **what a viewer sees** — *"The episode jumps ahead at each gap. On a transcoded stream the picture
     freezes and the sound goes silent instead."*;
  3. **the suggestion** (FR-7).

  A series groups the files by season (*"S01: 5 episodes, 2 to 9 minutes missing each"*), expandable to
  the episodes, as phase 255's block does. The Checks card's *Verify files* line (FR-261-10) counts a file
  with holes as a finding, so a title with holes never reads *clean*. FR-261-9's per-title outcome is the
  worst state across `file_integrity` **and** `file_timeline`.
- **FR-7 — the suggestion is text, chosen by the case. One builder** (`TimelineHolesAdvice`, commonMain,
  pure, unit-tested):

  | Case | Suggestion |
  |---|---|
  | holes, and other files of the **same release** in this library have holes | *"These files came from one release, and it is missing parts. Replace them with a different release: in Sonarr, delete the files and search again, rejecting this release."* |
  | holes, one file | *"This file is missing parts, and nothing local can restore them. Replace it with a different release: in Sonarr or Radarr, delete the file and search again."* |

  There is no command and no button: no local edit restores what is missing. The *same release* is the
  release group taken from the filename, and where that is absent, the same parent directory with the same
  group-free name pattern. It is a hint in the text, never a gate.
- **FR-8 — a source copy is only a source if it is whole.** FR-254-9's candidate (same basename, another
  inode, in a torrent's content path) must also have **no more holes than the library file**, measured by FR-1
  at repair time. A seeding copy of the same release carries the same holes, and replacing one with the other
  would read as a fix that fixed nothing. A candidate that fails is *no source*, said plainly.

## Non-goals

- **Replacing any file automatically, or any write to Sonarr or Radarr.** Finding is automatic. A replacement
  is the operator's decision (phase 254's posture, and the media rules: a decision that replaces a file is
  escalated, not executed).
- **Filling the holes** with frozen frames or silence. That hides the jump and restores nothing.
- **A warning in Ravilo.** No streaming service tells the viewer that a file is damaged. The remedy is a
  replaced file, and that is the admin's job.
- Changing phase 255's tail probe, or 254's damage rules.

## Acceptance

1. Unit, `TimelineHoles`: a synthetic 25 fps video plus two audio tracks with a 2.0 s hole in all three ⇒
   one hole of 2.0 s. A 4 s video-only gap with continuous audio ⇒ no hole. A 3 s audio-only gap with
   continuous video ⇒ no hole. A hole that is 2.2 s on track 1 and 1.9 s on track 2 ⇒ the default track's
   figure, plus the worst track's figure kept. 0.4 s ⇒ none. A cover-art stream with one packet is never the
   main video.
2. Unit: a `file_timeline` row stops being current when size or mtime changes. A pass that did not finish
   stores nothing.
3. Unit: `TimelineHolesAdvice` picks the release case only when another file of the same release has holes.
   A filename with `'` is safe.
4. Unit: the deep check's single pass yields both the damage lines and the timeline. A fixture with a
   demuxer damage line **and** a hole yields both findings from one run.
5. On production, after the deploy and the backfill: all five damaged files of the season measured above
   show the block, each missing roughly 1 to 9 minutes (video-only measure: 58 s – 8 min 33 s). The
   season's other five read *checked, whole*. The Dashboard counts at least **8** files under *Picture and
   sound skip ahead*: the five above plus the other series' three. The 34 other files from that
   broadcaster read whole.
