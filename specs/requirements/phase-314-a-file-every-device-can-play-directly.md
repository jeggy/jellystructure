# Phase 314 — A file every device can play directly

> Owner, 2026-10-08, deciding step 3 of the no-buffering plan (`specs/research-reports/ravilo-streaming-plan-2026-10-08.md`):
> fix files at the source — **"Yes, opt-in per kind"**: built as jobs switched on per kind (a compatible audio track,
> Dolby Vision 7), **with a dry-run list first; the originals stay in the file**.

## Status

`⚠ Partial` — **built 2026-10-08 (314a) on branch `worktree-agent-a5d8835a82b48c051`**, not merged, not deployed: the
three kinds' dry run, jobs, card, Dashboard rows and the backend's copy choice; Ravilo's one-row picker, sidecar direct
play and the Dolby Vision version choice in Ravilo playback are 314b/c (see *Build notes*). Before that: `Planned` —
written 2026-10-08 (dev-authored) from the owner's decision above, the streaming evidence and approach
reports of the same day (option D), and a read-only count over Jellyfin's database, the library's files (link counts)
and qBittorrent. Dev-reviewed 2026-10-08 (section at the end). Backend (a new job kind on the media lane, the track resolver), admin
(Settings, the dry-run list, the Tracks tab, Activity, Dashboard), Ravilo (the picker hides a compatible copy).
Builds on **284** (the file is the record), **311** (work files in `<dir>/.jellystructure/`), **213** (job lanes),
**R291** (the backend chooses the audio stream), **R195**/**R241** (remembered tracks), **R379** (no-AC3 phones).

## What happens today (2026-10-08)

332 films and 9 194 episodes (Jellyfin's `MediaStreamInfos`; 281 films and 7 908 episodes are MKV).

| What the file lacks | Films | of them seeded | Episodes | of them seeded | Who transcodes because of it |
|---|---|---|---|---|---|
| **Any AAC/MP3/Opus/FLAC/Vorbis track** (only AC-3, E-AC-3, TrueHD, DTS) | **248** (75 %) | 155 | **4 828** (53 %) | 2 468 | the Chromecast (no AC-3/E-AC-3 decoder, R297), web browsers, phones without a platform AC-3 decoder (R379) |
| **Any lossy surround track** (only TrueHD/DTS) | 15 | 9 | 429 | 427 | the Mac (AVPlayer), an iPhone, anything without lossless decoders |
| **A Dolby Vision profile the TVs decode** (DV 7, all with an enhancement layer) | 16 (1.04 TB) | 12 | 0 | 0 | the TVs and phones: tone-mapped to SDR H.264 every time |

"Seeded" = the file has more than one hard link (qBittorrent's cross-seed and perma-seed folders); the seeding guard
(`SeedingGuard`, used by `MediaJobQueue.guard`) refuses to modify such a file, and must keep refusing.

On a Chromecast, 80 % of films and 57 % of episodes need a transcode; 76 % / 54 % need one **only** because of the
audio. Each transcode costs a median 8.5 s before the first frame.

Free space: 5.0 TB on the films disk, 11 TB on the series disk.

## Principles

1. **Add, never replace.** Every original track, attachment, chapter and tag stays exactly as it is. A kind adds one
   thing (a track, or a profile-8.1 picture) and can be taken away again.
2. **A seeded file is never modified.** Not by this phase, not "just a remux". Seeded files get the sidecar path
   (FR-314-6) or wait.
3. **Off until switched on, and a dry run first.** Nothing is written until the owner has seen the list and pressed
   Apply for that kind.
4. **The file says what was added.** An added track carries its origin in the file itself (284's rule), so a rescan,
   a fresh database or another tool can tell an added track from an original.
5. **Playback first.** A job never competes with someone watching (the 2026-09-15 incident: background reads of large
   files stalled a film).

## Requirements

### FR-314-1 — Three kinds, each with its own switch

| Kind | Adds | For | Encoder |
|---|---|---|---|
| **A · Stereo for everything** | one **AAC-LC stereo 256 kbps** track per audio language that has no AAC/MP3/Opus/FLAC/Vorbis track, made from that language's main track (not commentary, not audio description) | Chromecast, browsers, phones without AC-3 (R379) | ffmpeg's native `aac`, `-ac 2` with Jellyfin's own downmix settings (read from its encoding config, so it sounds like today's transcodes) |
| **B · Surround for lossless-only files** | one **E-AC-3 5.1 640 kbps** track per language whose only tracks are TrueHD/DTS | the Mac, an iPhone, AV receivers by passthrough | ffmpeg's `eac3` (7.1 folds to 5.1) |
| **C · Dolby Vision the TVs play** | the same film with its Dolby Vision RPU converted from profile 7 to **8.1**, the HDR10 base layer unchanged | Android TVs and phones (DV 4/5/8 only) | `dovi_tool -m 2 convert --discard`, remuxed with `mkvmerge` |

Why stereo AAC and not AAC 5.1: every device that needs kind A plays stereo (a TV's built-in Chromecast, a phone, a
browser), Cast's multichannel AAC is not reliable, and today these devices already get a stereo AAC transcode
(R351's channel limit). A 2-hour film gains ~230 MB. Why E-AC-3 for B: it is the one lossy surround codec every Apple
device and AV receiver takes; ~575 MB for 2 hours.

Each kind has its own switch, **off by default**, in **Settings → Libraries → Make files play directly** (one card,
three rows: switch, counts *eligible · seeded · done · failed*, **Show the list**). A kind never runs while its switch
is off, except a title-by-title action (FR-314-7).

### FR-314-2 — The dry-run list

**Show the list** opens the Library filtered to that kind (a facet *File fix: A / B / C* with values *would add ·
seeded, sidecar · seeded, waiting · done · failed · skipped*). Each row says what would be added (`+ AAC 2.0 English,
from TrueHD 7.1 · ~230 MB`), the disk it needs, and for kind C whether the enhancement layer is MEL (lossless to drop)
or FEL (12-bit detail lost; `dovi_tool info`). Nothing is written until **Apply** on the card; Apply queues the listed
files and only those (a file added later waits for the next Apply, or for **Apply automatically to new files**, a
second switch per kind, off by default).

### FR-314-3 — The job: copy, write, verify, swap

Each file is one job on the **media lane** (213), one at a time per disk:

1. **Guard:** `SeedingGuard` again at the start (a file can become seeded after the dry run); seeded ⇒ FR-314-6.
   qBittorrent unreachable ⇒ the job waits (never treated as "not seeded").
2. **Space:** free space ≥ the file's size + 10 % on that filesystem (the existing preflight).
3. **Write** the new file into **`<dir>/.jellystructure/<name>`** (311's `WorkFiles.pathFor`), never beside the video.
   Kinds A/B: `mkvmerge` the original with the new track appended **last**. Kind C: extract the HEVC stream, convert the
   RPU, mux it back with every other stream, chapter, attachment and tag in the original order.
4. **Verify** before the swap: ffprobe the work file — same duration (±1 frame), same frame count for video, every
   original stream present with the same codec, language, flags and order; the added track decodes start to end
   (`ffmpeg -v error -f null`); kind C reports DV profile 8 with `bl_signal_compatibility_id` 1 and keeps the HDR10
   mastering metadata.
5. **Swap** with one `rename(2)` (same filesystem), keeping owner and mode (`withOwnershipPreservation`).
6. **Tell Jellyfin** (an item refresh, as 284's write does) and record the change in the title's History.

Any failure leaves the original untouched and the work file removed; the row reads *failed* with the reason.

### FR-314-4 — The file says what was added

- **Kinds A/B:** the added track's name is `Stereo` / `Surround 5.1` (what a viewer sees), its language is the
  source track's language exactly (never a new language — see FR-314-9), it is **never** marked default or forced,
  and it carries a Matroska track tag **`JELLYSTRUCTURE_COPY_OF=<source track UID>`** plus
  `JELLYSTRUCTURE_ADDED=<date>`.
- **Kind C:** a global tag `JELLYSTRUCTURE_DV=8.1-from-7` with the original profile and EL type, and the EL and
  original RPU kept in `<dir>/.jellystructure/<name>.el.hevc` + `.rpu.bin` so the profile-7 file can be rebuilt
  (FR-314-8). They are hidden from Radarr, Sonarr and Jellyfin by the folder (311).

### FR-314-5 — The right track for each device, picked by the backend

The backend already chooses the audio stream (R291's `audio … → stream N`). An added copy is treated as **the same
audio as its source**:

- A device that decodes the source's codec gets the **source** (nothing changes for the TVs).
- A device that cannot gets the **copy** of the same language and kind, as **direct play**, instead of an audio
  transcode of the source.
- Remembered choices (R181/R195/R241) are kept: the copy is appended last, so every original stream keeps its index,
  and a remembered source resolves to its copy on a device that can't play the source.
- **Ravilo's picker** shows a source and its copy as **one** row (R195's two levels); on a device that can only play
  the copy, the row is the copy. The copy never shows as a version of its own.
- The admin's Tracks tab shows it as `Stereo · AAC 2.0 · added by jellystructure (copy of track 2) · Remove`.

### FR-314-6 — Seeded files: a sidecar, or wait

A seeded file is never rewritten. For kinds **A/B**, the job writes the new track as an **external audio file**
beside the video, `<base>.<lang>.<kind>.mka` (Jellyfin lists external audio files as extra audio streams of the item),
with the same tags as FR-314-4. The torrent is untouched (its content is the video's other hard link). Playback of a
sidecar track: Media3 merges it client-side with the direct-played video (`MergingMediaSource`); other players get a
Jellyfin direct-stream (video copied, audio copied: no picture encode). The dry-run list shows these as *seeded, sidecar*.

For kind **C**, a seeded file **waits** (*seeded, waiting*) and is picked up by the next run once no torrent holds it:
a full profile-8.1 copy beside it would be a second video file in the folder (311's failure mode) and ~65 GB each.

Before building the sidecar path, prove that Radarr and Sonarr ignore `.mka` (their `MediaFileExtensions` lists, in a
test like 311's regex test), and that Jellyfin 12.1 attaches `<base>.<lang>.*.mka` to the item.

### FR-314-7 — Title by title

The media detail's Tracks tab offers **Add a compatible track** / **Make Dolby Vision play on the TVs** for one title,
whatever the switches say, with the same dry-run line and Apply; and **Remove** on an added track (FR-314-8).

### FR-314-8 — Taking it back

- **A/B in the file:** *Remove* remuxes without the tagged track (the same copy-write-verify-swap); in a sidecar,
  deletes the `.mka`.
- **C:** *Restore profile 7* rebuilds the original from the kept EL and RPU and verifies it against the recorded
  frame count and duration. If the kept files are missing, *Restore* is not offered.
- Turning a kind's switch off stops new jobs; it never removes what was added.

### FR-314-9 — Radarr, Sonarr and the seed stay calm

Read 2026-10-08: Radarr's custom formats are release-title rules and **language** rules (*Block dub: …* at −10000);
Sonarr's only rule is a release-title one; no profile scores codecs, HDR or size. So an added track **in an existing
language** changes no score and cannot start an upgrade or a re-download. Rules that keep it that way:

- An added track never introduces a language the file did not have; an untagged source gives an untagged copy.
- The file name never changes (Radarr/Sonarr naming tokens are not re-applied).
- The swap is one `rename(2)` from `.jellystructure/` (311), so a scan never sees two video files or none.
- A test pins the custom-format facts above: if a profile gains a codec, HDR or size rule, the dry-run list warns
  (*Radarr scores audio codecs: an added track could change this film's score*).

### FR-314-10 — Playback first, and a budget

- A job starts only when nobody has been playing from that disk for 10 minutes, and its process is paused
  (`SIGSTOP`/`SIGCONT`) the moment a playback starts from that disk; it resumes 10 minutes after the last one ends.
- One job per disk at a time; a per-night cap (default 2 TB read) so a backlog of thousands of episodes spreads over
  nights instead of saturating the disks.
- `dovi_tool` is added to the backend image (it is missing today); a missing tool marks kind C *unavailable* on the
  card instead of failing jobs.

### FR-314-11 — Visible

- **Activity:** the job kind *Make file play directly* with progress, the kind and the file.
- **Dashboard** (285's grammar): an information row while a kind is off and has eligible files (*248 films need a
  transcode on a Chromecast only because of their audio · Settings*), a warning row per failed file, nothing at zero.

## Non-goals

- Converting image subtitles (PGS) to text (1.8 % of films, 2.9 % of episodes have PGS without a text track): later.
- Re-encoding any picture except kind C's metadata conversion (the picture itself is never re-encoded).
- MP4 files (36 films, 1 257 episodes): MKV only in this phase; an MP4 is listed as *skipped (MP4)*.
- Changing Radarr/Sonarr profiles, or seeding.

## Acceptance

1. With kind A on and applied to one unseeded film: its file gains `Stereo · AAC 2.0` last, every original track is
   byte-identical (`mkvextract` checksums), a cast of it to the TV's Chromecast **direct-plays** (no ffmpeg job in
   Jellyfin's log), and the Stue TV still plays the TrueHD/E-AC-3 original.
2. The same on a seeded film: the file and its torrent are untouched, a `.mka` sidecar appears, and Radarr's next
   rescan logs one video file and no change.
3. Kind C on one unseeded DV 7 film: the TVs play it in Dolby Vision as direct play; *Restore profile 7* gives back a
   file with the original frame count and profile.
4. A playback starting mid-job pauses the job within 2 s; the film plays without a stall.
5. *Remove* on an added track returns the file to its original track list.

## Tests (required)

1. The eligibility rules per kind on a fixture of stream lists (no compatible track; lossless-only; DV 7 MEL/FEL;
   commentary and audio description never sources; MP4 skipped).
2. The mkvmerge/ffmpeg/dovi_tool command lines: work file in `.jellystructure/`, the new track appended last, never
   default, tags present, original order kept.
3. Verification: a work file missing a stream, with a shifted duration or a changed language fails and is removed.
4. The seeding guard: seeded ⇒ sidecar (A/B) or waiting (C); unreachable ⇒ waits; a file seeded after the dry run is
   caught at the job's start.
5. The track resolver: a device that decodes the source codec gets the source; a Chromecast capability set gets the copy;
   a remembered source index maps to its copy; the picker shows one row.
6. Radarr/Sonarr: `.mka` is outside both `MediaFileExtensions` lists; the custom-format guard warns on a codec rule.
7. Revert: Remove (in-file and sidecar) and Restore profile 7 round-trip on real small fixture files.
8. The playback-first rule: a playback start pauses the job; the nightly cap stops new jobs.

## Open questions

1. **For the owner — seeded files (most of the work):** 155 of 248 films and nearly all lossless-only episodes are
   seeded. Lean: **the `.mka` sidecar** (FR-314-6) for A/B; alternatives: wait until no torrent holds the file (most
   perma-seeds never end), or skip seeded files.
2. **For the owner — kind C on seeded films (12 of 16):** lean **wait**; alternative: a full profile-8.1 copy kept
   hidden in `.jellystructure/` and served by our own encoder's direct path (phase 4 of the plan), ~0.8 TB.
3. **For the owner — FEL films in kind C:** converting a full enhancement layer drops its 12-bit detail (the EL is
   kept for *Restore*). Lean: allowed, the dry-run row says *FEL* so the owner can leave a title out.
4. Dev: confirm Jellyfin 12.1's naming rule for external audio files and that a direct-stream with an external audio
   input is a copy job (no encode), and how fast it starts.
5. Dev: whether the downmix should match Jellyfin's `DownMixStereoAlgorithm` exactly, or use a dialogue-friendly
   matrix (the household watches on TV speakers).

## Decided by the owner (2026-10-08)

1. **Seeded files are never modified (Q1).** The owner first answered *"do it anyway"*; told what that does (the torrent's
   piece check fails, qBittorrent re-downloads the changed parts and undoes our work, a private tracker may flag a bad
   seed, cross-seed breaks), the owner chose **add beside, never modify**. For a seeded file, kinds A and B write the
   new audio as a separate `.mka` next to the video (FR-314-6), never into it. Unseeded files may still get the track
   added inside the file, as FR-314-3/-4 say.
2. **Dolby Vision 7: keep both, both pickable (Q2 + Q3).** The original file stays exactly as it is, full enhancement
   layer included, and the converted profile 8.1 file is **a second version** of the same title (≈ 0.8 TB for all
   16 films), seeded or not. This replaces converting in place and the Restore data. The backend plays the 8.1 version
   for a device that cannot play profile 7 (the TVs) and the original for one that can; the viewer can switch between
   them in Ravilo's picker (R195's versions level), labelled in plain words (e.g. *Dolby Vision* · *Dolby Vision, full
   detail*). FEL films are converted too, since nothing is lost.
3. Disk: kind C needs about 0.8 TB of free space on the films' disk; the dry run shows the total before Apply.

## Dev review (2026-10-08, against `main` `fdd48f10`)

Read against `torrent/SeedingGuard.kt` + `SeedingSnapshot.kt`, `media/MediaJobQueue.kt` (guard, lanes, playback
deferral), `media/Scanner.kt` (`scanMovie`), `media/FfmpegRunner.kt` (`.jstmp_`), `tv/PlaybackService.kt`
(`buildAudioTracks`, media source ids), `tv/AudioRenditions.kt` + `AudioRenditionJobs.kt`, `auth/JellyfinClient.kt`
(device profile), `ravilo-cast/…/Receiver.kt` (capabilities); Jellyfin **v12.1** (`FFProbeVideoInfo.cs`,
`MediaInfoResolver.cs`, `NamingOptions.cs`, `VideoListResolver.cs`, `StreamBuilder.cs`, `EncodingHelper.cs`); Radarr and
Sonarr `develop` (`MediaFileExtensions.cs`, `ExistingOtherExtraImporter.cs`); the backend and Jellyfin images
(`command -v`); `df` on both media disks. The kinds and the safety rules hold. Twelve items, one for the owner.

1. **"Seeded" in this spec is not what `SeedingGuard` checks.** The guard matches the file's path against each
   torrent's `content_path` (translated to the local mount) in a seeding state; it never looks at hard links. A library
   file hard-linked from a torrent saved elsewhere (cross-seed, the perma-seed folder) reads **Allowed**. The spec's
   counts (155 films, 427 episodes) are link counts. For 314, **seeded = guard `Blocked` OR `st_nlink > 1`**, and
   qBittorrent unreachable still waits. Note what each kind of write does to a hard-linked seed: a `rename(2)` swap
   leaves the torrent's inode untouched (it does not corrupt the seed, it only ends the sharing and doubles the disk
   use), while an in-place `mkvpropedit` rewrites the shared inode and **does** corrupt it. The owner's rule (never
   modify a seeded file) covers both here. **Outside this phase, worth its own bug:** today's in-place `mkvpropedit`
   jobs (track flags, 201/263 repairs) consult only the path guard, so a hard-linked seed can be edited in place.

2. **Jellyfin attaches the sidecar, with the name the spec uses.** v12.1's `MediaInfoResolver.GetExternalFiles` takes
   any file in the folder whose name starts with the video's file name, then a `MediaFlagDelimiters` character, with an
   extension from `AudioFileExtensions` (`.mka` is listed). `ExternalPathParser` reads the language, `default` and
   `forced` from the dot-separated tokens and keeps the rest as the title. So `<base>.<lang>.<kind>.mka` works. Keep the
   kind token a plain word (`Stereo`, `Surround`), never `default` or `forced`.

3. **A sidecar shifts every embedded stream's Jellyfin index.** `FFProbeVideoInfo` builds the list as external
   **subtitles**, then external **audio**, then the file's own streams, and renumbers them 0…n. FR-314-5's "appended
   last, so every original stream keeps its index" holds for a track added *inside* the file, not for a sidecar: one
   `.mka` moves the video from index 0 to 1, and so on. Required: one resolver maps a Jellyfin index to the file's own
   stream index (by type and order, external streams skipped), used by everything that hands an index to ffmpeg or
   stores one. **Likely an existing bug:** `AudioRenditionJobs` maps R291's renditions with `-map 0:<index>`, where
   the index is **Jellyfin's** (`buildAudioTracks` copies `MediaStream.Index`). On a title with an external subtitle
   (Bazarr writes many) and two or more audio tracks, that picks the wrong stream or none. Verify on such a title and
   fix with the same resolver, before 314 adds audio sidecars.

4. **Jellyfin never direct-plays an external audio stream.** `StreamBuilder.GetCompatibilityAudioCodecDirect` adds
   `TranscodeReason.AudioIsExternal`, so a play that picks the sidecar through Jellyfin is always a job: a remux with
   `-i <sidecar>`, the video copied and the audio copied when its codec fits. That beats an audio transcode, but it
   still pays Jellyfin's cold start (the 1 GB probe). Real direct play of a sidecar needs **our** route: the backend
   serves the `.mka` (range requests, the same auth as R291's renditions), and Media3 merges it with the direct-played
   video (`MergingMediaSource`), as FR-314-6 says. Players that only take HLS (the web's hls.js, the receiver) need the
   sidecar as an HLS audio rendition: copied, never encoded (R291's job model, or 313's).

5. **The Chromecast never direct-plays, so kind A turns a transcode into a remux.** The receiver declares
   `hlsOnly = true`, and the device profile then has no direct-play profile at all
   (`JellyfinClient.deviceProfile`). Jellyfin always answers with a `TranscodingUrl`: a codec-copy remux when the
   codecs fit. With kind A applied, a cast becomes `-c:v copy -c:a copy` instead of an audio encode, which is much
   cheaper and faster. **Acceptance 1 is wrong as written** ("direct-plays, no ffmpeg job"); it should read: Jellyfin's
   job for the cast copies both streams, and the first frame comes within N s. With 313, our encoder serves the copied
   segments itself and the cold start goes. Also, the receiver declares `ac3`/`eac3` when the device answers yes for
   multichannel (a TV's built-in Chromecast may). Eligibility per play must follow the declared capabilities, not a
   blanket "Chromecast has no AC-3".

6. **Radarr and Sonarr ignore `.mka` as a video file, but they adopt it as an extra.** Neither `MediaFileExtensions`
   list contains `.mka` (verified). On a rescan, both register any other file in the folder that parses as the title as
   an **Other extra file** (`ExistingOtherExtraImporter`). So a *Rename* renames the sidecar with the video, which keeps
   Jellyfin's prefix match. An upgrade or delete of the video removes its extras, the sidecar included. 314 must treat
   a missing sidecar for a still-eligible file as *redo*, and the new video after an upgrade is a new file anyway. Add a
   test for the rename case: the sidecar's prefix equals the new video name.

7. **The Dolby Vision "second version" has no home in our stack yet, and in the folder it is 311's failure mode.**
   Jellyfin does group `<folder name> - <label>.mkv` files as versions of one film (`VideoListResolver`). But:
   - **No media sources anywhere on our side.** Nothing in the scanner, `MediaStore`, the backend or Ravilo models
     media sources: `scanMovie` reads one file per film, PlaybackInfo is always sent with `MediaSourceId = item id`, and
     Ravilo has no version chooser. R195's "versions" are versions of a *track*, so "pick it in the picker" needs a new
     row and plumbing end to end.
   - **A second video file in the folder.** Radarr's disk scan sees it as a candidate movie file. It is normally
     rejected (not an upgrade) and left alone, but in any window where the original is missing (a remux, a rescan
     mid-write), Radarr can adopt it as the film (311's incident), and an upgrade deletes only Radarr's own file and
     orphans the copy.

   **For the owner** (below). Lean: keep the 8.1 version **hidden in `<dir>/.jellystructure/`**, where Radarr, Sonarr
   and Jellyfin never see it. Our backend serves it (direct play for a device that cannot play profile 7, range
   requests from our own route) and Ravilo's picker offers a picture row: *Dolby Vision* / *Dolby Vision, full
   detail*. Playstate stays on the Jellyfin item.

8. **`dovi_tool` is missing from both images.** The backend image has `mkvmerge`, `mkvextract`, `mkvpropedit`, `ffmpeg`
   and `ffprobe`; Jellyfin's has none of the mkvtoolnix tools. jellyfin-ffmpeg's `dovi_rpu` bitstream filter can strip
   an RPU but cannot convert profile 7 to 8.1, so `dovi_tool` (a static binary) must be added to the backend image.
   FR-314-10's *unavailable* state stays. With the owner's "keep both", FR-314-8's *Restore profile 7* and FR-314-4's
   kept EL/RPU files go: the original is never changed.

9. **Disk.** Films disk: 5.0 TB free of 13 TB (59 % used); series disk: 11 TB free of 24 TB. Kind C's ~0.8 TB fits.
   The A/B sidecars are small (≈ 230 MB per film stereo, ≈ 575 MB surround). The dry run's total plus the 10 % margin
   per file (FR-314-3) stands.

10. **Reuse the job queue's playback rule instead of `SIGSTOP`.** `MediaJobQueue` already defers and yields to playback
    (`waitsForPlayback`, `yieldsToPlayback`: R262/295, the household `deferWhilePlaying` switch). FR-314-10's
    "paused within 2 s" should use that yield (a running job stops and requeues at its checkpoint), not
    `SIGSTOP`/`SIGCONT` of a child process. A stopped mkvmerge holds the file and the disk queue open, and its
    `.jellystructure/` work file may outlive a restart. "One job per disk" and the nightly read cap are new and go in
    the claim.

11. **Build order: after 311.** `WorkFiles`/`.jellystructure/` is not built (work files are still `.jstmp_<name>` beside
    the video, `FfmpegRunner.kt:84`), and 314's safety depends on it. Order: 311 → the index resolver (item 3, also
    fixes R291) → 314 A (in-file, unseeded) → the sidecar route + A/B sidecars → C (after Q1).

12. **Smaller.**
    - The downmix (open question 5): use Jellyfin's own `DownMixStereoAlgorithm`, so a copy sounds like today's
      transcode; a dialogue-friendly matrix can be a later switch.
    - The added in-file track's tag and name (FR-314-4) also let *Remove* find it after a remux by another tool.
    - The dry-run counts must exclude files whose only audio is commentary or audio description (FR-314-1 says
      "main track").

**For the owner:**
- **Q1. Where does the Dolby Vision 8.1 version live?** (a) Hidden in `<dir>/.jellystructure/`, served by our
  backend, with a picture row in Ravilo's picker. Radarr, Sonarr and Jellyfin never see it, so there is no
  second-file risk (lean). (b) As a Jellyfin version file `<name> - Dolby Vision.mkv` in the film's folder: Jellyfin's
  own apps see both, but Radarr's second-file risk (311) applies, plus the media-source plumbing.

## Decided by the owner (2026-10-08, after the dev review)

1. **The Dolby Vision 8.1 copy is a Jellyfin version file** (Q1, against the lean): `<name> - Dolby Vision.mkv` beside
   the film, so Jellyfin's own apps see both versions too. This makes three things requirements of 314:
   - **Media-source plumbing:** the scanner, `MediaStore`, the backend's playback choice and Ravilo's picker learn that
     one film can have two video files (today nothing models media sources). The backend picks the 8.1 version for a
     device without a profile 7 decoder, the original for one with it; the picker offers both in plain words.
   - **Radarr must never delete, replace or import it:** 311's failure mode (a second video file in the film's folder).
     Before writing the first one, verify on Radarr how it treats an extra video file named like a Jellyfin version, and
     make it safe (e.g. an exclusion Radarr honours, or Radarr's own handling of extra files); a dry run on one film,
     watched through a Radarr rescan and an upgrade search, is part of acceptance.
   - The version file is written through 311's work folder and verified before it appears.


## Build notes (2026-10-08, 314a — branch `worktree-agent-a5d8835a82b48c051`)

### What was built

- **`filefix/FileFixRules.kt`** (pure, tested): the three kinds; per-language eligibility (main track = default, else
  first; commentary and audio description never sources, by disposition or title; our copies recognised by their
  `JELLYSTRUCTURE_COPY_OF` tag); MP4 *skipped*; kind C = a profile-7 film whose file is named after its folder in a
  form Jellyfin groups as versions; the version path `<folder> - Dolby Vision.mkv`; the sidecar name
  `<base>.<lang>.<Stereo|Surround>.mka`; the verification of an appended file and of a version; the Radarr rule; the
  device choice (`copyForDevice`, `dvVersionSource`).
- **`filefix/FileFixCommands.kt`** (pure, tested): encode (`-map 0:a:<n>` — the file's own order, R382), decode check
  of the small encoded file, append with mkvmerge (`--default-track-flag 0:no --forced-display-flag 0:no`, appended
  last), dovi_tool convert (ffmpeg's exit status checked without `pipefail`), mkvextract timestamps, mkvmerge version
  mux with a global `JELLYSTRUCTURE_DV` tag, frame count, the move into place with the original's owner and mode.
  Every work file comes from `WorkFiles.pathFor` (311), which gained six kinds; a kind with its own extension
  (`.mka`, `.hevc`, `.txt`, `.xml`) still reads back to its library file, so 311's sweep removes a leftover.
- **`filefix/FileFixService.kt`**: the dry run (all films and episodes, one probe each, cached by size + mtime), the
  per-kind switches, Apply, one title, the queue tick, the job per kind, the card's overview, the Dashboard rows.
  **`FileFixShell`**: `popen` through the ProcessGate; a long command is stopped (`pkill -f` on its work file) within
  about a second of a playback start or a cancel.
- **Job** `file_fix` on the media lane (`MediaJobQueue.fileFixer`, `enqueueFileFix`, `deferWhilePlaying = true`);
  the tick (every 60 s, `Main.kt`) queues the next pending file when nothing plays, no file-fix job waits or runs, and
  the last 24 h read stays under the cap. A restart puts a running row back to pending.
- **Migration 76** (`file_fix`, `file_fix_setting`) — 73 is R381's (merged), 74 is reserved for R266 and 75 for 313.
  Merge 314 after 74 and 75 exist on main, or renumber: a database already past a skipped number never runs it later.
- **Routes** `/api/file-fix` (overview), `POST /plan`, `GET /{kind}/rows`, `POST /{kind}/setting`, `POST /{kind}/apply`,
  `POST /{kind}/title/{mediaId}`. **Admin:** *Make files play directly* card in Settings → Libraries
  (`SettingsFileFix.kt`): *Find files*, three rows with switch, counts, *Show the list*, *Apply*, *Apply automatically to
  new files*. **Dashboard:** an information row per kind that is off with files to add; a warning for failures.
- **Backend copy choice (FR-314-5, start of a play):** a device that can't decode the stream that would play gets the
  added copy of the same language (`… audio → stream N, the added copy this device plays (314)`).
- **Radarr:** `ArrClient.parseRelease` (`GET /parse`, read-only).
- **Image:** `dovi_tool` 2.3.4 (musl, sha256 pinned) in `/usr/local/bin`.

### Validated (2026-10-08)

- **On scratch copies** (never the library): kind A on a 2.0 AC-3 film — the AAC track appended last, not default,
  language and `JELLYSTRUCTURE_COPY_OF` kept, duration identical; kind B on a DTS 5.1 episode — E-AC-3 5.1 640 kbps in
  9.5 s for 22 minutes, the original video and audio **byte-identical** after the append (ffmpeg `streamhash` MD5),
  duration identical; kind C on a 20 s clip of a DV 7 film — dovi_tool → profile 8, BL compatibility 1, no EL, the
  same 481 frames, every stream in the original order, the HDR10 mastering and content-light SEI kept.
- **Radarr (read-only `/parse`):** every DV 7 original reads `Remux-2160p` (one reads `Unknown`), every
  `<folder> - Dolby Vision` copy reads `Unknown`, custom-format score 0 for both: never an upgrade. This Radarr has
  `renameMovies = false` and no recycle bin — an upgrade would delete, which is why the check runs before each write.
- **Live dry run** over the real library (read-only: ffprobe headers and link counts; `FileFixLiveDryRunTest`, gated by
  `FILEFIX_LIVE_LIST`): every film (317 MKV/MP4 on disk) and every 10th episode (918 of 9 173), 1 235 files: **kind A** — films 245 to add (154 seeded ⇒ a `.mka` beside them, 91 in the file), 2 MP4 skipped; episodes 464 of the sample (240 seeded, 224 in the file; ≈ 4 640 across the library), 17 MP4 skipped; ~113 GB for the sample. **Kind B** — films 22 (12 seeded), episodes 42 of the sample (all seeded; ≈ 420); ~16 GB. **Kind C** — 16 DV 7 films, all with an EL: **3 eligible, 13 skipped because the file is not named after its folder** (a scene-named file: Jellyfin would show `<folder> - Dolby Vision.mkv` as a second film, not a version). 6 files unreadable (probe failed). Counts match the spec's Jellyfin-database figures (248 / 155 films for A).
- **Tests:** `FileFixRulesTest` (20) and `FileFixServiceTest` (8, a fake shell + a real database): FR-314's tests 1–6
  and 8, and the Radarr rule. The repo's check scripts pass (work files, in-place guard, cancellation rethrow, timeouts,
  fd hygiene, Docker cache ids).

### Deviations

1. **The switches act at once** (stored in `file_fix_setting`, like the job controls on Activity), not through
   Settings' global Save: a switch here starts nothing by itself, Apply does.
2. **Show the list** opens the kind's rows on the card (the first 300), not a Library facet.
3. **The read cap is a rolling 24 h** (2 TB), and **one job at a time** overall — the media lane already runs one — not
   one per disk.
4. **Playback first** is the job queue's own rule (review item 10): a job never starts while something plays (the
   household's defer switch), and a running one is stopped and goes back to pending; no SIGSTOP.
5. **Seeded** = 315's guard asked as an in-place writer: a hard link outside the library, or a seeding torrent's path.
   qBittorrent unreachable and no hard link ⇒ the row waits.
6. **Kind C's name rule is stricter than Jellyfin's cleaner** (anchored: ` - `, `_`, `.` or a resolution token right
   after the folder name); a stricter rule only skips a film, it never makes a duplicate in Jellyfin.
7. **Downmix** = ffmpeg's `-ac 2`, which is Jellyfin's default `DownMixStereoAlgorithm` (`None`), so a copy sounds like
   today's transcode.

### Not built (314b / 314c)

- **Ravilo:** the picker showing a source and its copy as one row; Media3 merging a `.mka` sidecar with the direct-played
  video, and the backend serving the `.mka`; an HLS rendition of a sidecar for hls.js and the receiver. Until then a
  sidecar plays through Jellyfin (a remux job: video and audio copied).
- **The Dolby Vision version in Ravilo playback:** `dvVersionSource` is built and tested but not wired — the version's
  media-source id has to flow through the start path, both restream paths and the subtitle URLs, and sidecar subtitles
  named after the original do not attach to the version. Jellyfin's own apps see both versions once Jellyfin has
  re-read the folder.
- **Tracks tab** title-by-title buttons and **Remove** (the route for one title exists; no button yet). A sidecar
  removed by a Radarr/Sonarr upgrade is listed again as *seeded, beside it* by the next dry run, so Apply redoes it.
- **Live on the dev stack** (main deploys this branch): Find files; one unseeded film through kind A (Jellyfin lists
  `Stereo`; a cast to a Chromecast becomes a copy job); one seeded episode through kind A (a `.mka` appears, Jellyfin
  attaches it, the torrent's piece check stays clean); kind C on one eligible film, watched through a Radarr rescan
  (one video file, no change) — no upgrade search is triggered by this phase.

### For the owner

1. **Kind C reaches only 3 of the 16 Dolby Vision 7 films** as built: Jellyfin groups `<folder> - Dolby Vision.mkv` as a
   version only when every video in the folder starts with the folder's name, and 13 originals keep their release
   name. Options: (a) leave those 13 (as built); (b) rename the 13 originals to `<folder>.mkv` first — a rename of a
   seeded/hard-linked file is safe for the torrent (another name, same inode) but Radarr must be told (rescan) and its
   rename setting is off; (c) keep their copies hidden in `.jellystructure/` served by our backend (the review's
   original lean). Lean: (b) for the unseeded ones, asked per title in the dry run.


## Live dry run (2026-10-09, v1.50-78, deployed — nothing applied)

*Find files* over the whole library: 9 779 files in 28 min (during the qBittorrent force recheck), 0 failures.

| Kind | Added inside the file | As a `.mka` beside it (seeded / hard-linked) | Skipped | Space |
|---|---|---|---|---|
| A — stereo AAC | 2 336 | 2 618 | 182 | ~433 GB read |
| B — E-AC-3 5.1 | 12 | 439 | — | ~62 GB read |
| C — Dolby Vision 8.1 version | 3 | — | 13 (file name ≠ folder name) | ~207 GB written |

Every kind is still **off**; nothing was written. The 13 skipped kind-C films wait for the owner's per-film rename
ticks (owner decision 2026-10-08), which 314a does not offer yet (314b/c). The image carries `dovi_tool` 2.3.4 (two
Dockerfile fixes on merge: `ca-certificates` in the runtime stage, and the archive member is `./dovi_tool`).
