# Phase 314 — A file every device can play directly

> Owner, 2026-10-08, deciding step 3 of the no-buffering plan (`specs/research-reports/ravilo-streaming-plan-2026-10-08.md`):
> fix files at the source — **"Yes, opt-in per kind"**: built as jobs switched on per kind (a compatible audio track,
> Dolby Vision 7), **with a dry-run list first; the originals stay in the file**.

## Scope

Written 2026-10-08 (dev-authored) from the owner's decision above, the streaming evidence and approach reports of the
same day (option D), and a read-only count over Jellyfin's database, the library's files (link counts) and
qBittorrent. Dev-reviewed 2026-10-08 (section at the end).

The phase divides into three parts, and the division is durable — the *Build notes* sections below are each written
against one of them:

- **314a** — the three kinds' dry run, the jobs, the Settings card, the Dashboard rows and the backend's copy choice.
- **314b/c** — the picker's one row for a source and its added copy, sidecar `.mka` direct play on Android, the Dolby
  Vision version chosen per device with a Picture tab, the per-film rename ticks, the Tracks-tab card and *Remove*.

Touches the backend (a new job kind on the media lane, the track resolver), the admin (Settings, the dry-run list, the
Tracks tab, Activity, Dashboard) and Ravilo (the picker hides a compatible copy). Builds on **284** (the file is the
record), **311** (work files in `<dir>/.jellystructure/`), **213** (job lanes), **R291** (the backend chooses the
audio stream), **R195**/**R241** (remembered tracks), **R379** (no-AC3 phones). Sidecar renditions reach hls.js and
the receiver only through **R382**'s ladder, and a sidecar with **313**'s own encoder is out of this phase.

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

## Dev review (2026-10-08, against `main` `cfbba310`)

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

## Build notes (2026-10-08, 314b/c — branch `worktree-agent-a75a1e096454ab621`)

Owner's answer to *For the owner* 1 above: **(b), asked per film** — a rename keeps the inode, so cross-seed's hard links
keep seeding; Radarr is rescanned; never for a file a torrent seeds in the library itself unless qBittorrent is
repointed as 316 does (this build refuses that case rather than repointing).

### What was built

- **One row for a source and its copy (FR-314-5).** `copySourcesOf` (Jellyfin's numbering, sidecars included) maps each
  copy — AAC *Stereo* / E-AC-3 *Surround 5.1* — to the same language's main original (default first; never a commentary,
  a description or another copy). The ticket's `AudioTrack` gains `copy_of` (+ `external`, `external_url`). Ravilo folds
  a copy onto its source's row (`foldedAudioGroups`, the R195 groups keep the player's own flat indices), shows the
  source's row while the copy plays (`shownAudio`), and a pick of the source plays the copy only when Media3 says the
  device can't decode the source and can the copy (`playableAudio`, `PlayerAudioTrack.supported` =
  `Tracks.Group.isTrackSupported`). A copy whose source isn't in the list stays its own row.
- **Ticket audio order.** Jellyfin numbers external streams first; a player sees the container's tracks, then any it
  merged. The ticket now lists the container's audio first and external tracks last (`ticketAudioTracks`), so the
  position mapping holds with or without a sidecar.
- **Sidecar direct play (FR-314-6).** `ClientCapabilities.external_audio` (Android `true`; desktop and web `false`). On a
  direct play to such a client each `.mka` beside the video is registered under a 128-bit id
  (`SidecarStreams`, lives as long as the ticket) and served at `GET /api/tv/stream/{id}/sidecar.mka` with byte ranges
  (`respondFileRange`: 206 / 416 / whole file). The file is found by name beside jellystructure's own path of the video
  (`localSidecarPath`: `.mka` only, no `..`). Media3 plays it with `MergingMediaSource(main, ProgressiveMediaSource…)`.
  A client without the capability never sees an external track on a direct play; a transcode keeps Jellyfin's handling.
  R382's renditions make an external track from its own file (`Rendition.sourcePath`, `sidecarFileFor`); a missing
  sidecar ⇒ no renditions, as before.
- **The Dolby Vision version in playback (FR-314-5, kind C).** `JellyfinItemDetail` reads `MediaSources` (+ `Name`).
  `chooseMediaSource`: a requested version wins; else a device without dual-layer DV (`supportsDolbyVision &&
  supportsDolbyVisionEl` false) plays our 8.1 version, every other device the original. The id flows through
  `getPlaybackInfo`, the direct/transcode URL, both restream paths and the un-burn path. The ticket carries `versions`
  (`Dolby Vision, full detail` / `Dolby Vision`, other versions by Jellyfin's name) and `media_source_id`;
  `PlaybackRestreamRequest.media_source_id` asks for one. Ravilo's picker gains a **Picture** tab (only with two
  versions; `player.tab_picture` × en/da/fo) whose pick restreams at the same position with the same tracks.
- **Rename ticks (kind C).** The dry run lists a DV7 original as `needs_rename` when renaming it to `<folder>.<ext>`
  would make it eligible (and only then): *rename to X first, then + a Dolby Vision 8.1 version beside it*. Settings →
  Libraries shows a tick per such film and *Rename the ticked and add their versions*; the Tracks-tab card has *Rename
  and add the version*. `POST /api/file-fix/c/rename {paths}` → `LooseFilmsService.renameToFolder`: 316's mover in
  place (`targetFolder == root`; `planRenameToFolder` takes every `<base>.…`/`<base>-…` file with it, folder-named files
  stay; refused when a target name is taken). It reads watch data, renames (undone in reverse on any failure), refreshes
  Radarr, tells Jellyfin, carries watch data and our rows to the new item, and writes History. **Refused, with nothing
  changed, when a torrent seeds the library file itself.** Then the row is planned again under the new name and its
  version queued. Apply never renames.
- **Tracks tab and Remove (FR-314-7/-8).** `GET /api/file-fix/title/{mediaId}` → a card (*Plays directly on every
  device*) on a film's Tracks & subtitles tab and a series' Seasons & episodes tab, hidden when the dry run has nothing
  for it: per kind its state, **Add** (`would_add · sidecar · failed · removed`), **Remove** (`done`). Remove is a job
  on the media lane (`remove_pending`, survives a restart via `resetRunning`): kinds A/B delete our `.mka` beside the
  file and remux our tagged copies out (`copyTrackIds` from `mkvmerge -J`: codec + our name + our
  `JELLYSTRUCTURE_ADDED`/`_COPY_OF` tag; `mkvmerge --audio-tracks '!ids'`; `verifyRemoved` + the duration; the seeding
  guard before and at the swap; one rename). Kind C deletes the version file **only when it carries our
  `JELLYSTRUCTURE_DV` tag** (the name alone never deletes). A removed row stays `removed`: neither the dry run, Apply nor
  *new files automatically* adds it back — only the title's own Add.

### Deviations

- **No migration**: the new states (`needs_rename`, `remove_pending`, `removed`) are values of the existing `state`
  column; only queries changed.
- **Remove is a job, not a request** (a remux of a 60 GB file can't run in a request).
- **The rename reuses 316's mover in place** rather than a new routine, so its order, undo and checks are 316's.
- **Desktop and web don't merge a sidecar** (`external_audio = false`): a direct play there lists no external track;
  their transcode path still has it through Jellyfin.
- **313's encoder doesn't map an external sidecar**: a play that would use our encoder with a sidecar track falls back
  to Jellyfin as before.
- **`dvVersionSource` (314a) stays unused**; `chooseMediaSource` replaces it (it also honours a request).
- A version's own **external subtitles**: Jellyfin lists for the version only what is named after the version; the
  original's sidecar subtitles may not attach to it (not handled here).

### Tests

New: `FileFixPlaybackTest` (9: ticket order + `copy_of`, no external track for a client that can't merge, copies in the
file, the sidecar's path, the version per device and on request, the versions' words, the capability id's life, byte
ranges, an external track's rendition file), `FileFixRemoveRulesTest` (5: `copySourcesOf`, `copyTrackIds` on
`mkvmerge -J`'s shape with statistics tags, the remux command, `verifyRemoved`, `renamedToFolder`), 6 more in
`FileFixServiceTest` (needs_rename and that Apply never renames; a tick renames, re-plans and queues; a refused rename
changes nothing; Remove remuxes our copy out with verification and stays removed through the dry run, Apply and
*new files automatically*; a seeded file's Remove only deletes the `.mka`; a version file without our tag is never
deleted), 3 in `LooseFilmsTest` (`planRenameToFolder`, the mover in place, a taken name), and Ravilo's
`AddedCopyFoldingTest` (5: folding, an orphan copy, `playableAudio`, `shownAudio`, the Picture tab's rows).
Green on the branch: backend `linuxX64Test` 1 320, `:ravilo-ui:testDebugUnitTest` 715, `:ravilo-ui:desktopTest` 579,
`:shared:desktopTest` 138 (wire contract included), `:ravilo-i18n` 24, `:ravilo-player` 8, `:ravilo-castv2` 33; the
admin and Ravilo Wasm compiles, `:ravilo-desktop:compileKotlinDesktop`, `:ravilo-android:assembleRelease`; every
`scripts/check-*.sh` except `check-deanonymization` (its hits are in files this phase doesn't touch: two cast tests and
the R291/R376/R377 specs). `check-player-dex`: the widest method is `PlayerScreen`'s body (R8 mapping) at **247**
registers (limit 250); `main`'s own is 246, the picture versions live in `PlayerBookkeeping` to keep it there.
`check-theme-whites`: PlayerScreen 73 → 74 (the Picture row's focus fill, over the video like its siblings).

### Validated (read-only)

`FILEFIX_LIVE_LIST` over the 299 film files (ffprobe headers, niced, nothing written, 2026-10-09): **16** Dolby Vision
7 originals — **3** eligible as they are, **12** `needs_rename` (no target name taken; 11 have another hard link, so a
seed elsewhere goes on; **1** has no other link and goes through the qBittorrent check — refused if a torrent seeds it
in place), **1** stays skipped (renaming doesn't make it eligible). A rename takes 0–3 files with it (NFO, subtitles,
`-thumb`). 1 file unreadable (as in 314a's run). Nothing applied to the library; no device used.

### Left for main (needs devices; none were used)

1. Merge, deploy the dev stack. *Find files* (the dry run lists `needs_rename` rows for kind C).
2. Kind A on one unseeded film → in Ravilo (Pixel, debug build) the picker shows **one** row for the original and its
   *Stereo* copy; a seeded film's `.mka` plays **directly** (`adb logcat -s 314`: *load: N sidecar audio file(s) merged with the video*; Jellyfin's
   dashboard shows no transcode/remux).
3. Tick one DV7 film in Settings → *Rename the ticked…*: the file is renamed in place (same inode: `stat -c %i`),
   Radarr shows the new name after its rescan, every torrent keeps seeding, the version is queued and made.
4. Stue TV (debug build): a film with the version opens on the original where the TV decodes dual-layer DV, the 8.1
   version elsewhere; the **Picture** tab switches between them at the same position.
5. *Remove* on a test title: the copy is gone from the file (`mkvmerge -J`), the `.mka`/version file deleted, the row
   reads *removed* and stays so through *Find files*.


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

## Live results after the integration deploy (2026-10-09, v1.50-105 → v1.50-106)

314b/c deployed (no migration). Not run live: the steps in *Left for main* need a signed-in Ravilo on a device (the
Pixel locked itself; no debug app is signed in on Stue TV) and kind A applied to a test film — left for the next device
session. Nothing was applied to any file.

## Decided by the owner (2026-10-12) — *Left for main*'s writes are authorised (DEB-789)

QA stopped before step 2 rather than choose a test title, which was right: every one of the five steps writes to a
library file, and `media-safety` escalates that instead of executing it. The owner answered on 2026-10-12. Nothing
had been applied to any file up to that point.

1. **The film that gets the appended track is chosen by a rule, not named.** The owner declined to nominate one and
   gave the test instead: at least **25 000 IMDb votes**, in the library **over two months**, and **currently
   available in one of our indexers in Radarr** — a film that could be replaced if the worst happened. Two gates
   follow from the phase itself and are part of the rule: the existing file carries **no edition**
   (`media-safety` §2 — a re-grab must not be able to replace a special cut), and the row is an
   **inside-the-file** kind A row, so the file is neither seeded nor hard-linked. Tie-break: **the smallest file
   that passes.** The indexer check is a search and nothing else — no grab, no upgrade, no torrent touched.
2. **The seeded `.mka` subject is QA's to pick** from the backend's own eligible list, and recorded. No existing
   file is opened: the job writes one new sidecar beside it and step 5 deletes that file, so no title needs weighing.
3. **Steps 3 and 4 are approved** on the **smallest rename-needed DV7 film the guard clears** — the pool is the
   **12** that a rename makes eligible, not the 13 with a mismatched name (the 13th stays skipped because renaming
   does not make it eligible). Recorded likewise.
4. **The walk undoes itself**: the appended track remuxed out, the `.mka` and the version file deleted, so the
   library ends where it started. **The rename stands** — the matching name is the intended end state, and 316's
   mover is what puts it there. Step 5's own acceptance is therefore also the cleanup.

The permitted writes are exactly those four plus the undo, on exactly those three films; a guard refusal is a pass to
be recorded, not an obstacle to route around; and no library title enters this repository (`DEB-793` carries them).
Step 4 keeps one prerequisite the owner's answer cannot clear — a debug Ravilo signed in on a TV that decodes
dual-layer DV, which there was not on 2026-10-09.

## Amendment 2026-10-13 — kind C's write path tells Radarr things that hurt it (DEB-868, DEB-869)

The authorised walk above ran on prod on 2026-10-13 (DEB-793 step 3) and found two defects in kind C. Both come from
the **rescan** path. **FR-314-9 is not wrong** — it reasoned about Radarr's *scoring*, and the scoring reasoning still
holds: the version's name parses to no quality and started no upgrade. What FR-314-9 never covered is that
jellystructure itself nudges Radarr to rescan after the write, and a rescan does not need a score to do damage.

### What was measured

**DEB-868 — the version becomes the movie's file, and *Remove* then unlinks the film.** Three facts, all from the
ticket:

1. `rescan_after_write = true` makes the write path issue `RescanMovie` after a kind C version lands
   (`ArrRescanService.nudge`). Radarr's rescan **adopts the version as the movie's file** — confirmed by the size in
   its own log line, which is the version's size and not the original's.
2. `radarrUnsafe()` (`FileFixService.kt`, owner review item 7, 2026-10-08) asks Radarr's `/parse` whether the
   version's *name* would score as a better release, and passed here — correctly. The hazard is not the name.
3. *Remove* then deletes the version; Radarr logs `movieFileDeleted {reason: MissingFromDisk}` for the **version's**
   size and leaves the movie at `movieFileId 0` — `hasFile false`, still monitored, still available, in
   **Wanted/Missing**, with the real file on disk and still owned by its own `moviefile` row. It does **not**
   self-heal: `RescanMovie` and `RefreshMovie` were both issued by hand, both completed, and neither changed it.

A monitored, available film sitting in Wanted/Missing is a re-grab of a film the library already has waiting to
happen, and Radarr's recycle bin is off here. **How long it waits was measured afterwards and is slower than the
first read of the ticket suggests** (DEB-868 comment, 03:21Z): this Radarr has **no scheduled missing-movie search at
all**, `RssSync` runs every 30 min and only takes what is in the feeds at that moment, and `minimumAge` is 60 min — so
the film sits there indefinitely rather than being replaced on a timer, and the trigger is a human pressing *Search*
or a matching release happening to surface. That makes this a correctness bug with a slow fuse, to be fixed before the
kind is switched on and not patched in a hurry. The kind C pool is 17 films, so a single pass over it is 17 of these.

**DEB-869 — the rename empties the folder of artwork for up to an hour.** The in-place rename is clean in itself
(`rename(2)`, inode preserved, sidecars renamed, nothing else touched by the mover). But Radarr reacts to the old path
vanishing with `movieFileDeleted {reason: MissingFromDisk}` and takes the five files it had adopted as extras with it:
poster, fanart, clearlogo, the `.src` and `movie.nfo` all disappear from the folder. jellystructure's hourly `:30`
pass rewrites them, and the measured run got off lightly only because the rename happened five minutes before the
half-hour. **A rename at `:31` leaves the film with no poster, no backdrop and no logo for 59 minutes**, which a
viewer browsing the library sees. This is the same mechanism as the known *"a Radarr upgrade wipes jellystructure's
poster/fanart/logo/NFO"* behaviour; what is new is that **the DV7 rename triggers it too**.

What was measured as **not** affected, and stays that way: the `.mkv` itself (inode, size, mtime and link count
identical before, after the version, and after *Remove*), the torrent hard-linking it, and Jellyfin's watch data.

### FR-314-12 — a kind C version never becomes the movie's file

- **The write path issues no Radarr rescan for a kind C version**, neither after writing it nor after deleting it. The
  version is ours and it is not a release; Radarr has no business adopting it. The nudge **stays** for kinds A and B,
  where the file's identity does not change, and for the in-place rename, where the path does change and Radarr must
  learn it.
- A nudge cannot be the only thing suppressed, because Radarr rescans for its own reasons too (its scheduled scan, a
  refresh someone presses, the hourly pass). So the write path also **records the movie's `movieFileId` before the
  write and re-asserts it afterwards** — after the version is written, and again after *Remove*. A `movieFileId` of 0
  on a film whose own `moviefile` row still points at a file that is on disk is **repaired, not reported**: nothing
  about that state is ambiguous.
- Preferred over both, if it holds: **name the version so Radarr's import cannot take it in the first place** — an
  extension outside Radarr's `MediaFileExtensions`, exactly as kind A's `.mka` sidecar already is (FR-314-6, test 6).
  That removes the race instead of racing it. Whoever builds this checks whether Jellyfin still groups the version
  with the original under such a name; if it does not, fall back to the two rules above, because **Jellyfin grouping
  the version is the acceptance and Radarr's quiet is the constraint**, not the other way round.
- **No kind C switch is turned on for the library until this is built and a test covers it.** The owner's per-film
  rename ticks are not the gate here; this is.

### FR-314-13 — a rename or a version write never leaves a film without its artwork

- After an in-place rename, and after a kind C version is written or removed, the write path **re-asserts the film's
  images and NFO itself** rather than leaving them to the hourly pass.
- The mover already owns an image backup-and-restore path (`ports.backup` / `ports.delete`), and the gap is narrow:
  `planRenameToFolder` returns only the video-named files, so the five adopted extras were never in the mover's plan
  and its `target == null` branch never saw them. Either the plan grows to cover them or the artwork writer is
  re-triggered on the folder; both are acceptable, and the second is the smaller change.
- The acceptance is read at the **folder**, not at the clock — see acceptance 7.

### Radarr's `dateAdded` is a consequence to know about, not a defect to fix here

Also DEB-869: the rename makes Radarr drop and re-create the `moviefile` row, so `dateAdded` moves to the rename date
and the film reads as newly acquired for two months afterwards. That is Radarr's model of a file that moved, and
jellystructure cannot preserve a field across a drop and re-create it does not perform. What follows and **is** in
scope is a reading rule: **nothing of ours may read Radarr's `movieFile.dateAdded` as "how long has this been in the
library"**. DEB-793's step-0.5 pick rule reads exactly that field for exactly that meaning, and so would any
age-based rule or recently-added view keyed on it. Recorded here so the next reader of that field knows before it
costs a measurement rather than after. No code change in 314 follows from this paragraph.

### The film left unlinked by the measured run is the owner's to repair

The subject film is still at `movieFileId 0` on prod. Re-linking it needs Radarr's own UI — a Manual Import renames
and moves files, which `media-safety` makes the owner's call and not an agent's — or it is unmonitored until then.
Escalated on DEB-793; DEB-868 carries the identifiers. It is not QA's, it is not a fix this amendment asks anyone to
build, and it is not a reason to hold FR-314-12.

### Acceptance (added)

6. Kind C applied to one unseeded DV7 film with Radarr watching: after the version is written, the movie's
   `movieFileId` still points at the **original**; after *Remove*, the movie still has its file (`hasFile true`, not
   in Wanted/Missing). Both hold across a `RescanMovie` issued by hand at any point in between.
7. An in-place DV7 rename of a film whose folder carries artwork: **within 60 s** the folder has its poster, backdrop,
   logo and NFO, at any time of the hour — in particular when the rename happens just after the `:30` pass.

### Tests (added)

9. The kind C write path issues no Radarr rescan; it re-asserts `movieFileId` after the version is written and after
   *Remove*; and a `movieFileId` of 0 against a live `moviefile` row pointing at an on-disk file is repaired. Kinds A
   and B and the rename still nudge.
10. The in-place rename re-asserts images and NFO without waiting for the hourly pass.

Unchanged by this amendment: the original is never modified by kind C, the per-film rename ticks stay the owner's, and
no library title enters this repository.

## Amendment 2026-10-13 (second, building FR-314-12) — Radarr has no API that puts `movieFileId` back (DEB-868)

Building the three rules above measured two of them out of reach. Everything below is read from Radarr's and Jellyfin's
own source on `develop`/`master`, and it agrees with what DEB-868 measured on prod by hand; where the two meet, the
measurement is cited.

**Rule 1 — a name Radarr's import will not take — is closed, not deferred.** Radarr's `MediaFileExtensions` holds 47
video extensions, Jellyfin's `NamingOptions.VideoFileExtensions` 53. Only ten are Jellyfin's and not Radarr's:
`.001 .3g2 .amv .f4v .m2t .mpe .mts .mxf .rec .tp`. Every one of them describes the file as something it is not — the
version is Matroska — and Jellyfin resolves the container partly off the extension when ffprobe answers the ambiguous
`matroska,webm`, so an exotic extension puts at risk exactly the direct play the kind exists to buy. Kind A's `.mka`
escapes this because an audio sidecar is a different kind of object to Jellyfin; a **version** has to be a video file
Jellyfin resolves in the film's own folder, and every video file Jellyfin resolves there is one Radarr's scan imports.
The folder routes Radarr skips (`plex versions`, a dotted folder, an `extras` subfolder) are routes Jellyfin does not
group a version from. The grouping is the acceptance and it cannot be kept while hiding the file by its name.

**Rule 3 — re-assert `movieFileId` — has no API to call.** `PUT /api/v3/movie/{id}` cannot set it: the controller
applies the body through `Movie.ApplyChanges`, which assigns `Path`, `QualityProfileId`, `Monitored`,
`MinimumAvailability`, `RootFolderPath`, `Tags` and `AddOptions` and nothing else, and `MovieFileId` is not in
`MovieResource.ToModel` at all. `PUT /api/v3/moviefile/{id}` writes quality, languages, edition, release group and
scene name only. `DELETE /api/v3/moviefile/{id}` runs through `MediaFileDeletionService` — **it deletes the file from
disk**, and this Radarr's recycle bin is off. A manual import is the one remaining route and it imports as a new
download, which moves or renames the file; that is why DEB-868 left the unlinked film to the owner's UI. Nothing
jellystructure can call puts the link back. (The same reading clears **316's mover**: `updateMoviePath` cannot zero
the link either, because `ApplyChanges` never touches it.)

**Rule 2 — no nudge for kind C — is built, and it is a delay rather than a prevention.** `RescanMovie` on a folder
holding a version imports it because `DiskScanService.Scan` feeds the folder's **unmapped** files to the import
decision and `ImportApprovedMovie.Import(decisions, newDownload: false)` points the movie at what it imports — the
version is unmapped, the original is not. The same `Scan` runs from Radarr's own daily `RefreshMovie`
(`rescanAfterRefresh` is on by default) and from any refresh a person presses, so suppressing our nudge removes the
trigger we own and leaves Radarr's. The same mechanism explains the measured non-self-healing: after *Remove* the
original is *mapped*, so it is not in the scan's file list and nothing re-links it.

### FR-314-12 as built (2026-10-13, superseding the three rules above)

- **No Radarr rescan for a kind C version**, neither after it is written nor after *Remove*. Kinds A and B keep their
  nudge; the in-place rename keeps the mover's own Radarr refresh (316's ports, `LooseFilmsService.renameToFolder`),
  which is a path change Radarr must learn.
- Around both moments the write path **reads Radarr's link and says what it finds**: the link moved to our version, or
  it is 0 while the film's own `moviefile` row still names the original. What it finds goes in the title's history and
  the log, with the row id, so the owner can act. *Repaired* could not be built; **reported** is what the API allows,
  and the repair is a person in Radarr's UI. Rows are matched on the file name, never the full path — Radarr's view of
  the library is mapped (`LooseFilmsService.toJellyfinView`).
- **The switch gate stands and widens.** Kind C stays off for the library: with adoption only delayed and no repair to
  call, switching the kind on hands the owner 17 films to re-link by hand. What closes this is a placement decision,
  not another patch — below.

### Open question — where a version can live that Jellyfin groups and Radarr never scans

Owner/Tróndur's call; neither shape is built, and neither is in 314 until it is answered.

- **A versions library.** The version lands under a second path Jellyfin scans and Radarr's root folder does not hold,
  and jellystructure merges the two items with Jellyfin's own `POST /Videos/MergeVersions`. Jellyfin groups by that
  merge rather than by the folder, so Radarr never sees a file to import. Costs: a Jellyfin library path the owner
  configures, and merge bookkeeping that survives a rescan.
- **Kind C stays off for Radarr-managed films.** The 17 are all Radarr's, so this is in practice "do not ship kind C",
  and it should be said plainly rather than left as a switch nobody may press.

### Acceptance 6 (revised)

6. Kind C applied to one unseeded DV7 film with Radarr watching: **jellystructure asks Radarr for no rescan** on the
   write or on *Remove*, and whatever Radarr's own scan then does to `movieFileId` is in the title's history within
   one job. The film still being linked afterwards is not something this phase can promise — see the open question.

### Test 9 (revised)

9. The kind C write path issues no Radarr rescan on the write or on *Remove*; kinds A and B still nudge; a link Radarr
   has moved to our version, and a `movieFileId` of 0 against a `moviefile` row still naming the original, are both
   recorded against the title; an unchanged link records nothing.

## Amendment 2026-10-13 (third, measuring the open question) — a merge survives everything, and costs one per-user field (DEB-875)

The open question above put one read-only measurement in front of the owner's choice: **does a `MergeVersions` link
survive Jellyfin's own rescan and a metadata refresh?** It does, and measuring it turned up a second fact that is the
real cost of shape A and was not in either costing. Nothing below is a decision and no shape is built; the two shapes
and their prices are now known, and the pick is still the owner's.

### How it was measured

In a throwaway `jellyfin/jellyfin:12.1` — prod's own image — on a loopback port, with two synthetic 1-second films
generated by the image's own `ffmpeg` and two libraries: `/media/movies` (standing in for Radarr's root folder) and
`/media/versions`. No prod read, no library file, no title, no approval, and a reviewer can re-run it. One film got its
version in the **second library** and an explicit `POST /Videos/MergeVersions`; a second film got its version **in its
own folder**, which is how kind C groups today, as the control. Jellyfin's `Startup/*` endpoints drive the wizard, so
the whole lab is one script. Item ids are derived from the path, so a file that comes back comes back under the same
id — a merge can therefore be reconciled from the path alone, with nothing stored.

### What survives, and what does not (measured, not read)

| Event | Merged pair (version in a second library) | Folder-grouped pair (today's kind C) |
| --- | --- | --- |
| `POST /Videos/MergeVersions` | one visible movie, 2 `MediaSources` | n/a — grouped by the resolver |
| `POST /Library/Refresh`, and the `RefreshLibrary` scheduled task by id | **survives** | survives |
| `FullRefresh` + `replaceAllMetadata` on the primary, and on the alternate | **survives** | survives |
| recursive `FullRefresh` of both library roots | **survives** | survives |
| container restart (so: the link is in the DB, not in memory) | **survives** | survives |
| the version file deleted, then a scan (what *Remove* does) | alternate item gone (404), **primary keeps its own path, its own file and its watch data** | same |
| the version file put back, then a scan | comes back **unmerged, as a second visible movie under the same id** — it must be re-merged | re-groups by itself |
| `DELETE /Videos/{primary}/AlternateSources` | unmerges cleanly — but it drops **every** alternate of that primary, not one | n/a |

So the survival question is answered: a merge is durable against every scan and refresh Jellyfin runs on its own, and
*Remove* under shape A is **cleaner** than today — the film keeps its file and its watch data, which is exactly the
state DEB-868 could not get back from Radarr.

### The cost that was not in either costing: a merged version obeys library permissions

A merged alternate is only a version of the film **for a user who may see the library it lives in**. Measured four
ways with one device profile, so the profile is not the variable:

- admin (sees both libraries), merged version → `PlaybackInfo` returns the source, `SupportsDirectPlay: true`.
- a non-admin user with the versions library **denied** → the film reads `MediaSources: 1`, and `PlaybackInfo` for the
  version returns **`ErrorCode: NoCompatibleStream`** with no sources at all.
- the same user, same profile, the **folder-grouped** version → the source comes back fine.
- admin, folder-grouped version → fine.

A hidden versions library therefore makes the version unplayable for everyone but the admin, which is the whole point
of the kind gone. Granting access brings the version back and brings a duplicate with it — browsing that library shows
the alternate as a film of its own. The middle path measured clean: **access granted, plus the user's own
`MyMediaExcludes` and `LatestItemsExcludes` naming the versions library** (`POST /Users/{id}/Configuration`) gives one
visible film row with two `MediaSources`, the version direct-playable, the library absent from `UserViews`, and a
search for the title returning **one** hit. The library is then reachable only by an explicit `parentId` nobody's UI
offers.

That field is per user and a new user does not have it. **prod serves users beyond the household**, so under shape A
jellystructure owns it: every pass asserts the versions library into every user's `MyMediaExcludes`, or a user who
appears later sees a second library of duplicate films. It is a small, well-defined piece of reconciliation, but it is
a new thing we own on other people's accounts and it belongs in the price.

### What shape A costs us in code, read against `main`

- `dvVersionPath` (`FileFixRules.kt`) is the only place the version's path is decided — one function, and the versions
  root is config rather than a mapped library (a mapped library would have to be `skip = false`, and then the sweep,
  loose films, the advisor and the webhook path would all treat its files as titles of their own; `skip = true` plus a
  `[file_fix]` root keeps our side of it free).
- **`namedAfterFolder` stops being a gate.** Folder grouping is what forced the original to be named after its folder,
  and a merge does not care what the original is called. That removes the in-place rename from kind C — with it the
  per-film rename ticks, DEB-869's artwork window, and Radarr's `dateAdded` churn — and makes all 17 eligible without
  one. That is the largest single item on either side of this decision and it was not in the costing above.
- Playback is shape-agnostic already: `chooseMediaSource` and `isDvVersion` (`FileFixPlayback.kt`) key on the file name
  `… - Dolby Vision.mkv` or Jellyfin's `Name`, and the version keeps that name under a different root. The one
  difference measured in the wire shape is that a merged alternate's `MediaSource.Type` reads **`Grouping`** where a
  folder-grouped one reads `Default`; `JellyfinMediaSourceInfo` does not deserialise `Type` and nothing branches on it,
  so no client change follows. Everything else — `Id`, `Name`, `Path`, `Container`, the three `Supports*` flags —
  is identical between the two shapes.
- New work: write, scan the versions library, resolve the item, merge; re-assert the merge whenever the alternate
  exists unmerged (idempotent, keyed on the path); on *Remove*, delete the file and let the scan drop the item — not
  `DELETE /AlternateSources`, which would drop a film's other alternates too.

### The shapes, repriced

- **A — a versions library.** Buys: Radarr never sees a file to import, so the defect cannot recur; *Remove* leaves the
  film whole; no rename, so all 17 films are eligible and DEB-869's window closes for kind C. Costs: a Jellyfin library
  the owner creates, a `[file_fix]` versions root, merge reconciliation, and `MyMediaExcludes` asserted on every
  Jellyfin user by us.
- **B — kind C does not ship for Radarr-managed films.** All 17 are Radarr's, so this is "do not ship kind C". Costs
  nothing and buys nothing; kinds A and B are unaffected either way.

Unchanged: the original is never modified by kind C, no kind C switch goes on until this is answered, and no library
title enters this repository.

## Amendment 2026-10-13 (fourth) — a rename costs Radarr the film's provenance, and this one Radarr can give back (DEB-881)

Measured on prod on 2026-10-13, while carrying out the owner's re-link of the film the authorised walk left unlinked.
It is a **third** defect of the same rename, and it is distinct from both above: FR-314-12 is about the film's *link*,
FR-314-13 about its *artwork*, and this one about its *record*. Neither of the two built fixes touches it.

### What was measured

The in-place DV7 rename makes Radarr drop its `moviefile` row and re-import the renamed file — the same
drop-and-re-create that FR-314-13 and the `dateAdded` note above already describe. The new row is built from a
**fresh parse of the new filename**, and the new filename is the folder's name, which by design carries no quality,
group or source tokens. Comparing the row Radarr deleted with the one it created a second later, on a film whose
existing file was a UHD BluRay remux:

| field | before the rename | after |
|---|---|---|
| `quality` | Bluray/Remux-2160p | **WEBDL-2160p** |
| `releaseGroup` | the release's group | **null** |
| `indexerFlags` | freeleech | **0** |
| `sceneName` | the full release name | **null** |

**Why this is a correctness defect and not a cosmetic one.** A remux recorded as a WEB-DL is exactly the state
`media-safety` §2 exists to prevent — a Radarr "upgrade" that replaces a better file with a worse one, blind to what
it is replacing. The same parse that loses the quality loses `edition`, and `edition` is the one field §2 names as the
check before any replacement.

**Why it is not biting today, said so nobody over-reacts.** This library's movies sit on quality profile 1, *Any*,
with `upgradeAllowed: false` (cutoff id 20), checked before the severity was called. Radarr therefore never hunts an
upgrade for these films and cannot act on the wrong label. So **today this is a provenance loss, not a data-loss
route**: the library forgets that a file came from a particular group as a freeleech BluRay remux. It becomes a real
hazard the moment any movie profile turns upgrades on, and it will hit **every** film kind C renames and everything
316's mover renames, because both produce the same token-free filename.

**What tells this apart from FR-314-12 is that an API exists.** FR-314-12 concluded, correctly, that Radarr has no API
that puts `movieFileId` back. These fields are the opposite case: `PUT /api/v3/moviefile/{id}` applies **quality,
languages, edition, release group and scene name** — close to exactly the set that is lost. So the provenance can be
read before the rename and written back onto whatever row Radarr creates afterwards.

**Not blocked on the placement decision.** Shape A of the open question (a merged versions library) would take the
in-place rename out of kind C entirely, which removes kind C as a *trigger* — but 316's mover renames either way, and
that press is live and unheld. FR-314-14 is therefore worth building before the owner answers DEB-875, and it is not
a reason to hold either.

### FR-314-14 — a rename carries the film's provenance across Radarr's drop and re-create

- **Before** an in-place rename (316's mover's rename step, and kind C's if it keeps one), the write path **reads the
  movie's current `moviefile` row and keeps its provenance**: `quality`, `languages`, `edition`, `releaseGroup`,
  `sceneName`, and `indexerFlags` if Radarr's own `MovieFileController` applies it — whoever builds this reads that
  controller rather than trusting this list, and records in the build notes which fields it actually accepts.
- **After** the rename, once Radarr has created the replacement row, the write path `PUT`s those values onto it. The
  new `movieFileId` appears **asynchronously** during Radarr's rescan, so the carry-over waits for it rather than
  assuming it: the same three-passes-15-s-apart shape FR-314-13 already uses is acceptable, and so is doing it on the
  next pass. If the row has not appeared by the last pass, the values are **reported against the title and not
  dropped silently** — this is provenance, and losing it quietly is the defect.
- **`edition` is not optional and not best-effort.** A film that had an edition and comes back without one is the
  `media-safety` §2 shape; if the carry-over cannot restore `edition`, the rename is reported as having degraded the
  record, in those words.
- It writes **only** these fields, and only values it read from the row Radarr deleted. It never invents a quality,
  never upgrades one, and never touches `movieFileId`, the path, or the file.
- **What stays lost, and is not worked around here:** `dateAdded` (not in the `PUT`'s set — see the note above, and
  the reading rule that nothing of ours treats it as "how long has this been in the library"). If
  `indexerFlags` turns out not to be in the set either, it joins that sentence rather than growing a second
  mechanism.

### Also observed, for whoever touches the mover

A Radarr **Manual Import** deletes the previous `moviefile` row too, and therefore wipes the folder's artwork exactly
as a rename does — a **third** trigger for FR-314-13's mechanism, beyond the rename and the version write. An import
is an operator action rather than a product action, so it is very likely outside 314; it is recorded here so the next
person who sees `movieFileDeleted` in Radarr's history beside a large file does not panic. **The file is not deleted
on that path** — verified byte-identical `stat` across the import.

### Acceptance (added)

8. An in-place rename of a film whose `moviefile` row carries a quality, a release group, a scene name and (where it
   has one) an edition: after the rename, Radarr's row for that film carries **the same** values, and the quality is
   not a lower source than before. Read from Radarr's API, against the row Radarr created — not from our own records.

### Tests (added)

11. The rename path reads the provenance before the rename and applies it to the row Radarr creates after it,
    including when that row appears only on a later pass; it writes no field it did not read; a film with an
    `edition` that cannot be restored is reported rather than passed; and a rename against a fake Radarr that never
    creates a replacement row reports instead of looping.

Unchanged by this amendment: the original file is never modified, the per-film rename ticks stay the owner's, FR-314-12
is still the kind C switch gate, and no library title enters this repository.

### Build notes (2026-10-13, FR-314-14 and FR-316-6 — DEB-881)

**Radarr's handler, read rather than trusted.** `MovieFileController.SetMovieFile` — `[RestPutById]`, i.e.
`PUT /api/v3/moviefile/{id}` — read on 2026-10-13 at `src/Radarr.Api.V3/MovieFiles/MovieFileController.cs`. Prod runs
Radarr v6.2.1 (`lscr.io/linuxserver/radarr:latest`); GitHub carries no tag for that build, so the handler was read on
`develop` and on `master`, which serve the same file today, and the measured before/after in DEB-881 is the check that
it is the handler this Radarr runs. What it applies:

| field | how the handler applies it |
|---|---|
| `quality` | assigned from the body **unconditionally** |
| `languages` | assigned unconditionally |
| `edition` | assigned unconditionally |
| `indexerFlags` | assigned unconditionally (cast to the `IndexerFlags` enum) |
| `releaseGroup` | only when the body's value is non-null |
| `sceneName` | only when non-null **and** Radarr's own `SceneChecker.IsSceneTitle` accepts it |

**`indexerFlags` is in the set** — that was the amendment's open question. It does not join `dateAdded`'s *stays lost*
sentence, and no second mechanism was grown for it: the measured `G_Freeleech` → `0` is repairable by the same `PUT`.
`dateAdded` is confirmed absent from the handler and stays lost.

Three properties of that handler shaped the code:

1. It is a **whole-resource PUT, not a patch**. A body that omits `quality`, `languages`, `edition` or `indexerFlags`
   *empties* them. So the write path `GET`s the row Radarr has built and overlays **only** the carried fields on it
   (`withProvenance`) — the same shape FR-316-4's `updateMoviePath` already uses for the movie.
2. `sceneName` can be **dropped silently**: `IsSceneTitle` wants a dotted name with no spaces that parses into a
   release group and a known quality, and the handler answers `202 Accepted` whether it took the name or not. So the
   carry-over never trusts the response — it re-reads the row and compares (`provenanceGaps`), and a field that did
   not come back is reported as not restored. The film DEB-881 measured has a scene name that parses, so it is
   expected back; a loose p2p name may not be, and that case now reports instead of looking clean.
3. The success status is `202`, not `200`.

**What was built.** `ArrClient.movieFileProvenance` (read), `ArrClient.applyMovieFileProvenance` (write, re-read),
`RadarrProvenance` with `carried()`/`withProvenance`/`provenanceGaps` as pure functions, two new `LooseFilmPorts`
methods (`readProvenance`, `carryProvenance`) and a `provenance` step on the mover between its artwork step (4b) and
its Jellyfin step. The read happens **before step 2**, off the row Radarr is about to drop; the write-back after the
Radarr step, which is what makes Radarr drop it. A gap puts the move in `partial` with `Radarr's file record lost …`
on the row and in the film's History — the owner sees it without reading a log.

**Where the wait lives, and how the rebuilt row is recognised.** `carryProvenanceAcrossRename` reuses
`FilmAssets.PASSES`/`SETTLE_MS` (FR-314-13's three passes 15 s apart) rather than growing a second pair of numbers for
the same asynchronous re-import. A row counts as the rebuilt one only once its **file name** is the new one — an id is
no good, because the row Radarr is about to drop keeps its id until it drops it, and FR-314-12's `movieFileId 0`
moment reads as no row at all. A row that has the new name and the *same* id is only taken as "Radarr kept it" on the
last pass, never on the first: concluding that early is exactly how the loss would go quiet again. Worst case the step
costs 30 s after 4b's 30 s, and before step 5's two minutes.

**One judgement call, for the spec's next reader.** FR-316-6 is built as its rule — *every rename the mover performs*
— and not as its example. The 316 half of this amendment says FR-316-4's move "gives the video file the basename it
derives from the film"; in `main` it does not. `planLooseFilm` keeps the video's own name (`LooseFile(video, "video",
video)`) and only the *artwork* takes folder names; it is `planRenameToFolder` (314c) that renames the video, which is
also the path DEB-881 measured. So the carry-over runs for **every** move where Radarr owns this film's file (step 4's
`UPDATE`), in place or into a new folder, because both make Radarr drop the row and re-import. Where the filename does
not change the record usually comes back whole, and the step says so ("Radarr kept the film's …") without writing
anything.

**Not verified against a live Radarr, and why that is not a gap in the build.** No write was made to the household's
Radarr. Acceptance 8 is a read at Radarr's API *after a real rename*, and the rename is the owner's — 314c's per-film
tick or 316's *Move the ticked films*. The field set above is from the handler's source; the loss it repairs is
DEB-881's measured before/after.

**Tests.** 6 on the mover's reporting in `LooseFilmsTest` (the read before any rename; the write-back on the rebuilt
row; a row that never appears reported and the move `partial`; an unrestorable `edition` reported as having degraded
the record; a record Radarr kept; a film Radarr maps to another file, where nothing is read or written) and 9 in a new
`RadarrProvenanceTest` on the field work and the wait (the carried set; the overlay touching nothing else, `dateAdded`
included; a field with nothing in it never written; the gaps, including a refused `sceneName` and quality compared by
id; the row found on a later pass; a Radarr that never rebuilds it reporting instead of looping; a kept record left
alone; a row rebuilt under some other name never mistaken for this one).

## Amendment 2026-10-13 (fifth, deciding the open question) — kind C's version lives in a versions library, merged (DEB-875)

**The open question of the second amendment is closed: shape A.** Asked on DEB-875 with both shapes repriced, the
owner answered *"Just do whatever is best, i trust you"* (2026-10-13 06:02Z), so the pick is a dev decision taken
under that delegation and recorded here as one. Two steps stay the owner's and are named below, because they are
config on his server and not code: **creating the Jellyfin library** and **setting its root in Settings**.

### Why A, now that both sides are measured

The third amendment answered the survival question. Finishing the costing added three facts, measured the same way —
a throwaway `jellyfin/jellyfin:12.1`, synthetic files, no prod read and no title:

- **A new Jellyfin user gets library access by default.** A user created through `POST /Users/New` comes back with
  `Policy.EnableAllFolders: true` and `EnabledFolders: []`, and a fresh user with those defaults **plays the merged
  version** (`PlaybackInfo` returns the source, `SupportsDirectPlay: true`). So the half of the permission cost that
  is *correctness* — the version being playable at all — needs no write to anybody's account in the common case. What
  a fresh user does get is the duplicate: `Configuration.MyMediaExcludes` defaults to `[]` and `GET /UserViews`
  lists the versions library on their home screen.
- **The whole write path needs no library scan.** `POST /Library/Media/Updated` with the single created path makes
  Jellyfin resolve that one file into an item — measured at **under a minute**, with no scan task run at all — and the
  item is then resolvable by path and mergeable. That matters because prod sets `scan_interval_hours = 0` and never
  scans itself: a shape that needed a library scan per version would have needed an admin session per version too.
- **Playback is per viewer, not per server.** `PlaybackService` negotiates with `tvTokenForClient(...)` and
  `device.jellyfinUserId` — the signed-in viewer's own Jellyfin user and token, for both `getItemDetail` and
  `getPlaybackInfo`. So the permission finding reaches Ravilo and is not confined to Jellyfin's own clients, which is
  why the rule below is written in terms of what a *viewer* sees and not what the admin token can read.

Against that, B retires the kind for the 17 films it exists for and buys nothing. A closes the DEB-868 defect by
construction rather than by racing Radarr's scan, makes *Remove* leave the film whole, and — the item that decides it
— **removes the in-place rename from kind C**, taking the per-film rename ticks, DEB-869's artwork window and
FR-314-14's provenance loss out of the kind entirely. The residual cost is one display preference per user and a
library the owner creates once.

### FR-314-15 — where a kind C version lives, and how it is grouped

- **Placement.** A kind C version is written to `<dv_versions_root>/<film folder name>/<film folder name> - Dolby
  Vision.mkv`, where `dv_versions_root` is a new `[file_fix]` config value. `dvVersionPath` takes the root; it is the
  only place the path is decided and stays so.
- **The root must be outside every Radarr root folder, and the write proves it.** Before the first version is written
  the path is checked against Radarr's own `/api/v3/rootfolder` list, and a root inside one of them **refuses the job
  and says why** rather than writing a file Radarr will adopt. The whole shape rests on Radarr never scanning that
  path, so it is a precondition, not a comment.
- **Grouping is Jellyfin's merge, not the folder.** After the version lands: `POST /Library/Media/Updated` with that
  one path (`UpdateType: Created`) — **never a library scan** — then the item is resolved **by path** from the versions
  library and `POST /Videos/MergeVersions` joins it to the film. The item appears asynchronously, so the step waits for
  it (the three-passes shape FR-314-13 already uses is fine, as is picking it up on the next pass) and **reports
  against the title** if it never appears.
- **The merge is asserted, never remembered.** The desired state is "the film's Jellyfin item has a source whose path
  is our version"; a pass that finds our file on disk and no such source **re-merges**. Jellyfin derives an item id
  from the path, so this is idempotent and needs nothing stored: a version that is removed and rewritten, or an item
  Jellyfin re-created, converges on the next pass with no bookkeeping to go stale.
- **Taking it back deletes the file and nothing else.** *Remove* deletes our version and reports it with
  `UpdateType: Deleted`; Jellyfin drops the alternate item and the film keeps its own file, its path and its watch
  data (measured). **`DELETE /Videos/{id}/AlternateSources` is never called** — it drops *every* alternate of that
  film, not ours.
- **The versions library is not one of our libraries.** It stays `skip = true` in the config
  `LibraryReconciler` writes, and the root above is what the write path uses. Mapped, its files would be titles of
  their own to the sweep, loose films, the advisor and the webhook path.
- **What a viewer sees is asserted; what a viewer may see is not.** For every Jellyfin user, jellystructure asserts
  the versions library into `MyMediaExcludes` and `LatestItemsExcludes` (`POST /Users/{id}/Configuration`) so no
  home screen gains a second library of duplicate films. That is a display preference, reversible, and it is the only
  thing we write on a user's account. **`EnabledFolders` is never widened.** A restricted account that omits the
  versions library is **reported in the admin UI** — those viewers' devices will be served the original and not the
  version — because widening a permission the owner set is the owner's call, not ours.
- **`namedAfterFolder` stops gating kind C.** A merge does not care what the original is called, so the kind no longer
  needs a rename and `needs_rename` leaves kind C: all 17 films become eligible as they are. The rename stays a 316
  feature with its own reasons and its own ticks (and FR-314-14 still applies to it there).
- **FR-314-12's reporting stays.** It costs nothing now that Radarr has no file of ours to find, and it is the canary
  if the root is ever moved inside a Radarr root folder.
- **The switch gate moves and stays shut until both halves are done**: this requirement built and tested, **and** the
  owner has created the Jellyfin library and set `dv_versions_root`. Neither alone turns kind C on.

### Acceptance (added)

9. Kind C applied to one unseeded DV7 film with the versions root configured: the version is written outside every
   Radarr root folder, the film shows in Jellyfin as **one** title with two versions within two minutes and **no
   library scan is run**, a viewer on a default account plays the version on a device that cannot decode the original,
   and Radarr's `movieFileId` for that film is **unchanged** across the write and across a `RescanMovie` issued by
   hand. After *Remove*, the film still has its own file and its watch data.
10. No viewer's home screen lists the versions library, and a restricted account that cannot see it is named in the
    admin UI rather than silently granted access.

### Tests (added)

12. The write path builds the version path from the configured root; refuses the job when that root is inside a Radarr
    root folder; reports the one created path instead of triggering a scan; resolves the item by path and merges it;
    re-merges when the file is present and the merged source is not; and on *Remove* deletes the file and reports the
    deletion without ever calling `AlternateSources`.
13. The user pass asserts the versions library into `MyMediaExcludes`/`LatestItemsExcludes` for every user, writes no
    other field of a user's account, never adds the library to an `EnabledFolders` list, and reports a restricted
    account that omits it.

Unchanged by this amendment: the original is never modified by kind C, no library title enters this repository, and
the version keeps the name `<folder> - Dolby Vision.mkv`, so `chooseMediaSource`/`isDvVersion` and the clients' picker
need no change (`MediaSource.Type` reads `Grouping` instead of `Default` and nothing deserialises `Type`).

### FR-314-15 as built (2026-10-13, commit `b354d55f`)

Two things the rules above did not say, found in building them, and one thing left owed.

- **The versions library needs its `local_path` filled in on the Libraries card**, while staying `skip = true`.
  jellystructure, Jellyfin and Bazarr mount the media under different roots (phase 273), so the version's path is
  translated before Jellyfin is told about it or asked to resolve it, and that translation is the library list's own
  `local_path` → `jellyfin_path` pair. Unmapped, the path is used as it is — right when both sides mount it the same
  way and silently wrong otherwise, so the mapping is part of the owner's configuration step rather than an extra.
  `skip` governs titles, not paths: skipped keeps the sweep, loose films, the advisor and the webhook path out of that
  library, which is what we want, and leaves the mapping readable, which is what the write path needs.
- **A viewer's `Configuration` is read and put back whole**, with only `MyMediaExcludes` and `LatestItemsExcludes`
  changed. Posting a typed model would silently reset every display preference this build does not model — subtitle
  mode, audio language, the home-screen layout — on somebody else's account. That is the difference between owning two
  fields and owning a viewer's settings, and the rule is the former.
- **Owed, and not claimed as done.** Acceptance 9 and 10 are live reads against prod Jellyfin with a root configured,
  and nobody has run them: the kind's switch is off and the library does not exist yet. The restricted-account report
  is a log line, where acceptance 10 asks for it in the admin UI — a row on the file-fix card is the next piece of work
  on this requirement and is not in `b354d55f`.

## Amendment 2026-10-13 (sixth) — the owner's three configuration steps are four, and the overlap refusal compares two namespaces (DEB-898)

Written before any Kotlin. FR-314-15 ships a switch gate whose owner half was described as three steps
(create the library, set the root, fill in the `local_path`). Writing those steps out for the owner needed the
actual paths, and reading them off the running host turned up one step that was missing and one rule that
cannot fire as built. Neither is a defect in what `b354d55f` *decided* — the placement, the merge and the
*Remove* behaviour all stand — and nothing here changes what a viewer sees.

### What was read (read-only; no write, no deploy, no library file touched)

- **Jellyfin bind-mounts each library folder individually.** `docker inspect jellyfin` lists one mount per
  library — `/mnt/media/jellyfin/<folder>` → `/media/<folder>`, and the series library from `/mnt/series/jellyfin`
  → `/media/series`. There is no parent mount under which a **new** host folder would appear inside the Jellyfin
  container, so a versions folder created on the host is invisible to Jellyfin until a mount is added and the
  container is recreated.
- **The backend has no such problem.** `jellystructure` mounts `/mnt/media` and `/mnt/series` whole, so a new
  folder under either is writable with no change to anything.
- **The arrs report their root folders in their own view.** `GET /api/v3/rootfolder`: Radarr `/media/movies`,
  Sonarr `/media/series`. That is the same namespace Jellyfin uses and **not** the one the backend writes in.
- **The two views differ on this host, so "both sides mount it the same way" is not available.** The live library
  mappings pair `jellyfin_path = /media/movies/` with `local_path = /mnt/media/jellyfin/movies/`, and every other
  mapped library has the same shape.

### FR-314-16 — the configuration named in the right namespace, and a guard that can fire

- **`dv_versions_root` is the path the *backend* writes, i.e. the `local_path` namespace.** `dvVersionPath` builds
  the target from it and `shell` writes there; `jellyfinViewOf` translates afterwards. That is correct as built and
  stays. What is wrong is the field's own hint in Settings ▸ Advanced, which calls it "a folder **Jellyfin scans as
  its own library**" — Jellyfin's name for that folder is the other side of the mapping, and on this host the two
  are never equal. The hint is corrected to name the backend's view and to say that Jellyfin's name for the same
  folder goes on the Libraries card. Text only; no behaviour changes.
- **A root that no library mapping covers refuses the job, and says which mapping is missing.** Today
  `jellyfinViewOf` falls back to the path unchanged when nothing matches, so a forgotten `local_path` does not fail
  at the root — it fails two minutes after a version has already been written, as "the item never appeared" against
  the title, with nothing naming the cause. The refusal moves to the front, beside the overlap check, and names the
  versions library's missing `local_path`. This is what makes the owner's mapping step a step rather than a trap.
- **The overlap refusal compares in one namespace: Jellyfin's.** `versionsRootOverlapsArrRoot` is handed
  `jellyfinViewOf(root, libraries)`, not `root`. As built it compares a `/mnt/...` root against `/media/...` arr
  roots, which can never overlap — so the guard the fifth amendment called *a precondition, not a comment* cannot
  fire at all, including on the exact mistake it exists to prevent: `/mnt/media/jellyfin/movies` **is** Radarr's
  `/media/movies`, and a root set there would be accepted today and then adopted by Radarr's own scan (DEB-868).
  With the translation it fires on the real overlap, and the refusal above guarantees there is always a translation
  to compare.
- **A mount is a container recreate, so the owner's configuration is four steps and they are ordered.** The bind
  mount into Jellyfin comes first — steps 2 to 4 cannot be done ahead of it, because Jellyfin's *Add library* browses
  the container's own filesystem and the admin field would otherwise take a path Jellyfin will never resolve. The
  recreate is a brief Jellyfin restart on a prod that serves users beyond the household, which is why the whole gate
  is the owner's and not ours.

### The owner's four steps (DEB-898), with the paths this host needs

| # | Where | What |
| - | ----- | ---- |
| 1 | the host's compose file for Jellyfin (outside this repo) | add `/mnt/media/jellyfin/versions:/media/versions`, create the host folder, recreate the Jellyfin container |
| 2 | Jellyfin ▸ Dashboard ▸ Libraries | add a **Movies** library on `/media/versions` |
| 3 | admin ▸ Settings ▸ Advanced ▸ *Versions library folder* | `/mnt/media/jellyfin/versions` — the **backend's** path — then the one global Save |
| 4 | admin ▸ Settings ▸ Libraries | on the new library's row, `local_path = /mnt/media/jellyfin/versions`, left `skip = true` |

Step 1's path is a suggestion and only steps 3 and 4 have to agree with whatever is chosen: any folder works as
long as it is mounted into both containers, no Radarr or Sonarr root folder holds it in Jellyfin's view, and steps 3
and 4 name the same folder on their own side of the mapping.

### What step 1 actually costs — corrected 2026-10-13 (DEB-898)

The cost was first written here, and told to the owner, as *a few seconds of Jellyfin downtime*. That is wrong, and
the correction matters because it is the only viewer-visible part of the configuration.

**The Jellyfin container carries `caddy` labels** (`jellyfin.jebster.net`, `jellyfin.xn--damkjr-tua.fo`), so
recreating it makes caddy-docker-proxy rebuild its configuration — and the household's Caddy is still **v2.11.4**,
the version carrying the unreleased data race `caddyserver/caddy#7994`. Measured on this host on 2026-10-06, a
*single* `up -d --no-deps` of one labelled service left its site unreachable for **6, ~11 and ~12 minutes** on three
separate occasions (and a multi-container burst took every site down for 8 minutes on 2026-09-28).

**That window is not confined to Jellyfin's own web UI.** `api_keys.jellyfin_url` is `https://jellyfin.jebster.net`,
which resolves to the LAN address by split horizon and is proxied by the same Caddy — so while the reconfiguration
is stalled, jellystructure cannot reach Jellyfin at all, and **every Ravilo client's playback negotiation fails**.
The symptom to expect is the one DEB-816's outage left on record: a log full of `Connection failed for request`
against Jellyfin while `http://127.0.0.1:8096/health` answers 200. Suspect the proxy, not Jellyfin.

**So step 1 is scheduled, never opportunistic**, and two rules follow:

- **Recreate exactly one container**, from `/home/jeggy/jellyfin`: `docker compose up -d --no-deps jellyfin`. That
  compose file holds a second Jellyfin service, so a bare `up -d` would recreate two containers and reproduce the
  2026-09-28 burst. Never `down`.
- **Pick an hour nobody is watching.** Over the 30 days to 2026-10-13, `playback_session` records **zero** Ravilo
  sessions in the 03:00 and 05:00 hours (Atlantic/Faroe) and one in the 04:00 hour, against 23 apiece at 20:00 and
  21:00. `playback_session` sees Ravilo only, not viewers on Jellyfin's own apps, so this is the best available
  signal rather than a guarantee — and prod serves users beyond the household, whose hours are not known.

**The route that avoids the window altogether, and why it is not the default.** `/media/channels` is already mounted
into the Jellyfin container and is the one mounted path that is not a library root, so a versions library at
`/media/channels/versions` would need no compose change and no recreate — zero downtime. It is not recommended: that
folder is the live-TV tuner directory (the `*.m3u` playlists and the XMLTV guides), and a films-versions library
living inside it is a permanent structural lie, in the config forever, for whoever next touches live TV. A one-time
scheduled window is the cheaper of the two costs. Recorded here because it is a real option and the owner's to take.

### Acceptance (added)

11. With `dv_versions_root` set to a path no library mapping covers, a kind C job **refuses before writing anything**
    and names the versions library's missing `local_path`. It does not write a version and then report a missing
    Jellyfin item.
12. With a versions root whose Jellyfin view is inside Radarr's own root folder, the job refuses with the overlap
    message. Checkable against the live configuration without writing a file: `/mnt/media/jellyfin/movies` translates
    to `/media/movies`, which is Radarr's root.

### Tests (added)

14. `versionsRootOverlapsArrRoot` is fed the **translated** root: a root whose Jellyfin view is an arr root folder
    overlaps and is refused; `/mnt/media/jellyfin/versions` → `/media/versions` against the same arr roots does not;
    and a root that no mapping translates is refused by the rule above rather than compared raw.

### FR-314-16 as built (2026-10-13, DEB-903)

Built on `main` at `6e7c7ce7`. Nothing deployed, no switch turned on, no library file touched — and on a prod with
no `dv_versions_root` configured there is no reachable behaviour change at all.

- **`jellyfinViewOrNull` is the primitive now**, and `jellyfinViewOf` is that with the old fall-back-to-the-path-
  unchanged. Every other caller — the grouping lambda, the un-grouping notification, the viewer-visibility pass —
  is unchanged by construction; only the versions root uses the nullable form, because only there is being
  unmapped the error rather than a legitimate same-mount installation.
- **`runDolbyVision` refuses before the first write** when no mapping covers the root, naming the library row's
  `local_path`, and only then compares the **translated** root against the arr roots. The overlap refusal carries
  both names now (`the versions root <local> is <jellyfin view> to Jellyfin, which the media root <arr root> holds`),
  because the whole mistake is that those are two different strings for one folder.
- **The Settings ▸ Advanced hint and its placeholder** name the backend's path (`/mnt/media/jellyfin/versions`) and
  send Jellyfin's name for the same folder to the Libraries card, where step 4 puts it.

Verified: **1509/1509** native tests green from the relinked `test.kexe` (the 54 `filefix` tests include test 14 and
a service-level test for acceptance 11: the job refuses, nothing is written, Jellyfin is never told about a path it
has never heard of), and `compileKotlinWasmJs` green for the hint. The service suite's `withVersionsRoot` helper now
sets the library mapping alongside the root, which is the configuration the host will have.

Read against the live configuration, read-only, no write: `local_path = /mnt/media/jellyfin/movies/` ↔
`jellyfin_path = /media/movies/`; Radarr's root folder `/media/movies` and Sonarr's `/media/series`, unchanged since
the amendment. So **acceptance 12 holds as specced** — a root at `/mnt/media/jellyfin/movies` translates onto
Radarr's own root and is refused — and `[file_fix]` carries no `dv_versions_root`, so the kind's switch is still
shut and the first thing the owner's step 3 will meet, before step 4, is acceptance 11's refusal naming step 4.

## Amendment 2026-10-13 (seventh) — the four configuration steps are five: the deploy is step 0 (DEB-898, DEB-925)

Steps 1, 2 and 4 of the sixth amendment's table went through on prod today. **Step 3 could not be done at all, and
the reason is a defect in that table rather than in the work**: it never said the build has to be running.

### What landed, and what did not

| # | | |
| - | - | - |
| 0 | **missing from the table** | FR-314-15 has to be the running image, not just `main` |
| 1 | **done** 07:47Z (DEB-924) | `/mnt/media/jellyfin/versions` → `/media/versions`, verified rw by uid 1000 inside the container |
| 2 | **done** 08:00Z (DEB-925) | a `movies` library on `/media/versions`, `ItemId c4ebdc1a04600fa395ca5f8214ece353`, **no scan** |
| 3 | **blocked** | the field does not exist in the deployed admin |
| 4 | **done** 08:07Z (DEB-925) | `local_path = /mnt/media/jellyfin/versions`, `jellyfin_path = /media/versions`, `skip = true` |

### Step 0 — check the phase is in the running image before configuring anything it shipped

Prod ran **`v1.51-58-gf9029c3b`**, **62 commits** behind `main`, and `b354d55f` was not an ancestor of it
(`git merge-base --is-ancestor b354d55f f9029c3b` → false, confirmed twice independently). Three symptoms, none of
which looks like a missing deploy from the admin UI:

1. `GET /api/config` on the running backend has **no `file_fix` key at all**.
2. `getElementById('dv-versions-root')` on the live Advanced tab → **null**. There is no field to type into.
3. `f9029c3b`'s own tree still has `dvVersionPath(originalPath)` — the **pre-amendment** signature that writes the
   version *beside the original*, which is DEB-868's `movieFileId 0` unlink. `jellyfinViewOf`,
   `versionsLibraryIdOf` and `VersionsLibraryViewers.kt` are not in it.

**What makes this worth a rule rather than a footnote: three of the four steps succeed anyway.** Step 1 is a change
to the host, and steps 2 and 4 are surfaces the *old* build already has — a Jellyfin library and a library mapping
both long predate this phase. So the configuration appears to be going in, and the one step that cannot go in reads
as a permissions or a path problem. **Before configuring anything a phase shipped, check
`git merge-base --is-ancestor <the phase's commit> <the version off /api/health>`.** It is one command and it is the
difference between a two-minute step and an hour of looking for a field.

### Step 1's price, measured — and mispriced in both directions

The sixth amendment priced step 1 at 6 to ~12 minutes of stalled Caddy reconfiguration, correcting an earlier
*"a few seconds"*. The measured cost on the night was **~11 seconds**: the race did not fire, controls never
dropped, there were no ordered-handler errors and `caddy` never restarted. Both numbers were wrong in the same way —
**the race is probabilistic, so neither a few seconds nor twelve minutes is the price; the exposure is.** Keep the
one-container rule and the quiet-hour rule for that reason, and do not read 11 seconds as a reason to drop either.

### FR-314-16's mapping claim does not hold in the UI yet (DEB-937)

The sixth amendment called the `local_path` mapping *"a step rather than a trap"*. It is still a trap: a library with
`skip = true` renders its `local_path` input with **`pointer-events:none`**, so step 4 as written cannot be typed at
all. It was done on the page's own path — untick Skip, type, re-tick Skip, one Save — and the stored config is
correct. The claim stands as the intent and not as a description until DEB-937 lands.

### Consequence for QA: DEB-870 is not runnable on the deployed build

DEB-870 (QA-E step 4, the Dolby Vision version per device) was described as runnable once these steps were in. It is
not. Kind C on the deployed build is the **old placement** — a version written beside the original — so running it
today would either exercise behaviour this phase has replaced or walk into the DEB-868 unlink the versions library
exists to prevent. It needs the same step 0 that step 3 needs, and no TV grant should be spent on it before that.

### Also found while configuring (DEB-936)

A Settings save writes `false` over `scan.suggestions_step_seeded`: `ConfigRoutes.kt` restores five of the six
`*Seeded` flags from `stored` and misses that one — the same family as DEB-729 and DEB-731, and **still true on
`main`**. Cost is one idempotent re-seed at the next backend start, so nothing a viewer sees. Recorded here because
it was found by **diffing the written `config.toml` against a copy taken before the Save**, which is the clearance
method that works; reasoning about which blocks the admin models is what missed it.

## Amendment 2026-10-13 (eighth) — a skipped library's paths have to be typeable, because `skip` does not govern them (DEB-937)

Written before any Kotlin, found doing the owner's step 4 on prod (DEB-925) and recorded in the seventh amendment as
the one thing that keeps FR-314-16's mapping claim an intent rather than a description. FR-314-15 requires one
library to carry a `local_path` **while staying `skip = true`** — *"`skip` governs titles, not paths"* — and
FR-314-16 made that mapping a step of the owner's configuration so it would be *"a step rather than a trap"*. The
Libraries card does not let that combination be typed, so the step is not performable in the order it is written.
This corrects FR-314-16's own usability claim; it is not new behaviour.

**Why the correction is written here.** The Libraries card has no numbered phase of its own: it arrived with the
`[[libraries]]` config in `0eef30a0` (2026-06-12), before this repo numbered its requirements, and **298** owns only
the reconciler that writes rows into it (it decides what is *added as skipped*, not what a skipped row lets you
edit). FR-314-16 is the requirement that claims the mapping step can be done, so the claim is corrected where it is
made — and the rule below is written as the card's rule, for whatever next needs a mapped-but-skipped library, not
as a special case for the versions one.

### What the card does today

`buildLibraryCardHtml` renders a skipped row's three inputs — *Jellyfin path*, *Local path* and *Fallback lang* —
with inline `pointer-events:none`, and dims the whole card to `opacity:.5`. The fields are neither `disabled` nor
`readonly`, so this is **presentation standing in for a rule**: nothing refuses the value, the field simply cannot
be reached with a pointer. Clicking *Local path* on a skipped row focuses nothing, and step 4 stops there.

Everything behind the field already accepts the combination. `readForm()` carries `local_path` on a skipped row,
the TOML preview renders it, and the write path wants it: `jellyfinViewOrNull` filters on
`localPath.isNotBlank() && jellyfinPath.isNotBlank()` and never reads `skip`. So the fix is the admin UI catching up
with what both sides already do, and **no wire field, config key, DTO or backend rule changes**.

The workaround that exists — untick *Skip*, type the path, re-tick *Skip*, one global Save — lands the right config
(it is what DEB-925 did, through the page's own listeners) and is not good enough: its middle state un-skips a
library on a prod form, which is the trap FR-314-16 set out to remove.

### FR-314-17 — `skip` greys out the controls it governs, and the paths are not among them

- **Both path fields stay editable on a skipped row**, full strength, with no `pointer-events` trick: *Jellyfin path*
  and *Local path* are the mapping, and a skipped library still needs the mapping read (FR-314-15). The *Match
  prefix* line stays at full strength with them, because it is those two fields' own feedback.
- **The per-library fallback language is `disabled` when the row is skipped** — the attribute, not a style, so the
  rule is the rule — and it is dimmed. It is a titles setting, and `skip` is exactly "no titles from this library".
  The books and music cards' metadata-provider rows dim with it for the same reason.
- **`disabled` has to reach the language picker, or it is decoration too.** `installLanguagePicker` hides the real
  input (`display:none`) behind a `div` of its own that takes every click and writes the pick through to the hidden
  field — so a `disabled` input the picker has wrapped is disabled in name only. The picker honours it: a disabled
  input gets a display that is `aria-disabled`, out of the tab order, `cursor:not-allowed`, and opens nothing.
  (This is also what the old `pointer-events:none` was really doing on that field — it was copied onto the picker's
  display with the rest of the input's inline style, which is why removing it without this would have *loosened* a
  skipped row rather than only freeing its paths.)
- **Skip's visible signal stops being the whole card dimmed**, because a dimmed card is what made every field on the
  row read as inert. What says it instead: the ticked *Skip* box, the collapsed *Skipped libraries (N)* block the row
  sits in, the absent *Scan* / *Push all to Jellyfin* buttons (already suppressed), the dimmed fallback row, and one
  line of text on a skipped row naming the rule — *titles from this library are left alone; its paths are still
  read*. A card that states the rule beats a card that mimes it.
- **The skipped block stays one collapsed `<details>`, and the versions library is not pulled out of it.** A row
  cannot be recognised as the versions library before its `local_path` is typed — that field *is* step 4 — so any
  "lift the interesting one out" rule would have nothing to key on at the moment it is needed. The operator reaching
  step 4 is following a step that already names the card, and a second place to look for a library row costs more
  than the one click it would save.
- **Nothing is granted that was not grantable before.** A skipped row could always carry a `local_path` through the
  three-step workaround; this makes the one-step version possible and removes the un-skipped middle state.

### Acceptance (added)

13. On Settings ▸ Libraries, inside *Skipped libraries (N)*, a skipped library's *Local path* and *Jellyfin path*
    take focus and accept typing with *Skip* left ticked, and the one global Save persists that row as `skip = true`
    with the typed `local_path`. The same row's fallback-language picker does not open and no language can be set on
    it, while an unskipped row's picker still does. No row leaves or enters the skipped block in the process.

### Tests (added)

There is no `wasmJsTest` source set in this repository, so this requirement gets no unit test; the verification is
`compileKotlinWasmJs` plus acceptance 13 walked against a locally built admin on a throwaway config — never prod's,
because the acceptance ends in a Save.

## Amendment 2026-10-13 (ninth) — a stored verdict is only as good as the configuration it was computed under (DEB-946)

All five steps are in: prod runs `v1.51-120-g80552895` (which carries both `b354d55f` and `ac3b37e4`),
`[file_fix] dv_versions_root = "/mnt/media/jellyfin/versions"`, the `Versioner` library is mapped and `skip = true`.
The kind's switch is off, and **the card the owner would read to decide it is wrong in the understating direction.**

### What it says, and what is true

Kind C's card reads `needs_rename: 13`, `would_add: 3`. Under the live configuration the real figure is **~16
eligible, none needing a rename** — FR-314-15's own *"all 17 films become eligible as they are"*. So the card
describes a switch that would act on **3** films when it would act on about **16**.

Neither number is a computation gone wrong. `needs_rename` is written in exactly one place,
`FileFixService.kt:314`, and that branch is guarded by **`root == null`** — which cannot be true any more. Those 13
rows are verdicts **stored before the root existed**, and nothing recomputes them: a row's state is written by the
dry run, and prod sets `scan_interval_hours = 0` and never plans on its own
(see *Prod defers nothing*). The rows are not stale by accident; there is no mechanism that would ever refresh them.

**And one write path acts on those verdicts rather than re-deriving them** — found building this, 2026-10-13, and
the reason the amendment is not only about what the card says. The two paths that write re-plan first:
`runDolbyVision` recomputes `planDolbyVision` under the current root before it touches anything, and Apply cannot
reach a `needs_rename` row at all (`markPendingForKind` takes `would_add` and `sidecar`). `renameAndQueue` is the
exception: its only gate is the **stored** `row.state != "needs_rename"`, so the owner's per-film tick on the stale
card **renames library files that the live configuration needs no rename for** — on prod today, 13 of them. Each is
a `moviefile` row deleted and re-created under Radarr: FR-314-14's provenance carry-over and FR-314-13's artwork
re-assert both run and both are built, so nothing is lost that they do not put back, and it is still a write to the
library whose only justification was a rule that no longer applies. It is the same class as the pre-write re-check
kinds A and B already do (*"the guard again: a file can become seeded after the dry run"*), applied to the one gate
that was left reading the row.

### FR-314-18 — a configuration change that alters the planning rules invalidates the verdicts it computed

- **Changing `dv_versions_root` invalidates kind C's stored rows.** Setting, clearing or moving the root changes
  which films are eligible and what the job would do to each, so the verdicts computed under the old value are not
  evidence about the new one. On that write, kind C's planned rows are cleared or marked as predating the current
  configuration; the kind's card must not present a count computed under rules that no longer apply.
- **A card that cannot vouch for its numbers says so.** Where rows predate the live configuration and no re-plan has
  run since, the card says that in place of a reassuring total — *"these counts predate the current versions root;
  run the dry run"* — rather than printing 3 as though it were measured today. **The switch decision is taken off
  this card, so the card carrying an unqualified stale number is the defect, not a cosmetic nit.**
- **The kind's own blurb is computed, not hardcoded.** `FileFixService.kt:706` states *"Writes a Dolby Vision 8.1
  version **beside** a profile 7 film"* as a constant. That is the superseded placement and the exact shape that
  caused DEB-868, printed at the top of the card while the same pass computes `"in the versions library"` for each
  row's detail twenty lines earlier. The blurb reads the configured placement like the detail does.
- **A write path never trusts a stored verdict about a rule the configuration can change.** The rename gate
  re-derives its own precondition before it renames anything: with a root configured there is no such thing as a
  film waiting on a rename, so the tick is **refused, nothing is renamed, and the row is planned again in the same
  call** — so the refusal both protects the file and corrects the count that offered the button. The refusal says
  which it is, in the owner's words (*the versions library groups the film whatever its file is called, so no rename
  is needed; this film is now listed as one to add*), rather than the existing catch-all *not waiting for a rename*.
- **No scan, no probe, no file.** None of this reads a media file or writes one; it is state the admin already holds.
  The rename refusal is the one path that would have written, and it is the path that stops writing.

### The rule this is an instance of

**A stored verdict is only as good as the configuration it was computed under**, and on an installation that never
re-plans by itself there is nothing to correct it. Any phase that stores a decision per title and lets the owner
change the rules behind it owes either an invalidation on that write or a visible "computed under older rules" mark.
Phase 314 is where it bit because the planning rules are configuration and the switch is a one-way action on 206 GB
of library, but the shape is not specific to kind C.

### Acceptance (added)

14. With planned kind C rows on disk, changing `dv_versions_root` leaves no row presenting a verdict computed under
    the old value: either the rows are gone or the card says they predate the configuration. The kind's blurb names
    the placement the live configuration implies, never *"beside"* while a root is set.
15. With a root configured and a `needs_rename` row still on disk, ticking that film and pressing the rename button
    **renames no file** — the library file keeps its name, Radarr's `movieFileId` for it is unchanged — and the row
    reads as one to add when the card comes back.

### Tests (added)

15. A `needs_rename` row written with no root does not survive a root being configured as a bare count on the card;
    and the kind's blurb is derived from the configured root rather than a constant.
16. `renameAndQueue` on a `needs_rename` row with a root configured calls the renamer **not at all**, answers with
    the reason, and leaves the row planned as `would_add`; with no root configured the same row still renames.

### Sequencing — the dry run comes before the switch decision

The recompute is `POST /api/file-fix/plan`: all kinds, background-gated, **writes rows only** — no media file is
touched, no Apply is run, Jellyfin is not called. It is the designed mechanism and the original 314 decision was
*dry run first* in any case. So the order for turning kind C on is: **recompute → read the real counts → the switch
decision → an Apply on one film (acceptance 9) → DEB-870**. Deciding the switch off today's card would be deciding
off a fifth of the real reach, which is the one thing this amendment exists to prevent.

### FR-314-18 as built (2026-10-13, commit `8574493d`)

Three things worth keeping, one of them a limit.

- **The invalidation is not a write on the config path, and not a timestamp.** *"Changing `dv_versions_root`
  invalidates kind C's stored rows"* reads as a hook on the Settings save, and that was not built: a save that
  deletes rows is a destructive side effect of a text field, and a *"when did the root last change"* timestamp is a
  new stored datum (`file_fix` has no column for it, `file_fix_setting` no spare field, and this schema has no
  generic key-value table — it would be a migration). What is built instead needs nothing stored: the card asks
  **which stored verdicts the live rules cannot produce**. With a root configured there are exactly two — a row in
  `needs_rename`, and a row `skipped` for its folder's naming — and both are recognised by the rule's own strings
  (`DV_SKIP_FILE_NOT_NAMED_AFTER_FOLDER`, `DV_SKIP_OTHER_VIDEO_NOT_NAMED`, exported from `FileFixRules` so the card
  compares the verdict's own words rather than a guessed substring; a skipped row's `detail` is its `reason`
  verbatim). One aggregate query, `countPreRootDvVerdicts`, no schema change, nothing to go stale on its own.
- **The limit, stated rather than hidden: the reverse direction is not detected.** Clearing or moving a root leaves
  no impossible state behind — every state a rooted dry run writes is also a state the beside-the-original rules can
  write — so a card whose verdicts were computed *under* a root and then has the root taken away reports them
  unqualified. Detecting that is the stored-configuration column this deliberately did not add. It is not a
  direction this installation takes (the root was configured once, towards the merged placement), and if it ever
  becomes one, the column is the honest answer rather than a second pattern-match.
- **The prod rows are not repaired by this.** The code stops the card lying and stops the rename acting; the 13 + 1
  verdicts on prod stay as they are until the dry run in *Sequencing* above is run, which is the owner's gate, not
  this requirement's. One thing does repair them piecemeal: the rename refusal replans each film it refuses, so a
  tick-everything-and-press-rename — which is what the stale card invited and what would have renamed 13 library
  files — now corrects all thirteen rows and touches nothing.

### The recompute as run (2026-10-13 09:37:44Z → 10:00:47Z, DEB-956)

Step 1 of *Sequencing* above has been taken, so the numbers the switch is decided against are measured rather than
predicted. `POST /api/file-fix/plan`, all kinds, 9 834 files, no per-file failure. Rows only, verified after the
fact: the versions root is still empty, `df` on `/mnt/media` is unchanged to the byte, nothing is `pending` or
`running`, no Apply, no rename, no deploy. It structurally could not queue work — `planOne` promotes a `would_add`
to `pending` only for a kind that is both enabled and auto-new, and every kind is off.

| kind C | verdicts from before the root | after the recompute |
|---|---|---|
| `would_add` | 3 | **17** |
| `needs_rename` | 13 | **0** |
| `skipped` (folder naming) | 1 | **0** |
| `eligible_bytes` | 206 546 128 370 | **1 100 068 788 012** |

- **17 films, 1.100 TB** (1.0005 TiB) — FR-314-15's *"all 17 films become eligible as they are"*, now measured
  rather than argued. The 18th row stays `removed` through a dry run and is not among the 17, and `needs_rename` is
  0 across the whole `file_fix` table, not only kind C.
- **Per film: 45.9 GB smallest, 65.7 GB median, 88.5 GB largest.** `est_bytes` is the original's size, so a version
  is budgeted as a full copy and acceptance 9 on *one* film costs 46–88 GB. There is no cheap outlier to pick.
- **Headroom does not constrain the decision.** The versions root shares `/dev/sda1` (`/mnt/media`) with the movies
  library, so a version is a second full copy on the same disk rather than a spill onto another: 5.26 TB free, and a
  full pass would leave 4.16 TB (61 % → ~69 % used).
- **`countPreRootDvVerdicts` reads 0** for all three kinds, so deploying `8574493d` will find nothing to gate: no
  stale sentence in place of the counts, no Apply disabled by staleness, and the rename refusal with nothing left to
  refuse. The repair the as-built note above left to the dry run is done.
- **The blurb half of DEB-946 is still on prod**, and the recompute cannot reach it: the kind's sentence is a
  constant reading *"beside a profile 7 film"* while every row under it reads *"in the versions library"*. Only the
  deploy fixes that, which is why it is recommended before the switch rather than after it.
- An all-kinds recompute moved the other two kinds slightly, in the safe direction: kind A `would_add` 2 342 → 2 339
  and kind B 13 → 12, three files having become seeded since the last plan.
- **Cost, for whoever schedules the next one: ~23 minutes at ~7 files/s with a cold `probeCache`** (the container
  had been recreated at 08:34Z). A warm re-run is seconds.

## Amendment 2026-10-13 (tenth) — the owner's answers on the kind C switch, and the hold that is a configuration fact (DEB-949)

The switch question this phase has carried since DEB-868 is answered. Asked on DEB-949 as three questions against
the measured figures above, the owner answered at **2026-10-13 21:27:41Z**, taking the recommendation on all three:

1. **Kind C goes on, applied to exactly one film, with the other 16 held** until DEB-870's per-device Dolby Vision
   test has passed. 46–88 GB committed rather than 1.100 TB before any device has confirmed a version plays.
2. **`8574493d` (FR-314-18) is deployed before the switch.** Not for the counts — the recompute fixed those — but
   because prod's kind sentence still names *"beside a profile 7 film"*, the superseded placement that caused
   DEB-868, and that is the sentence read while deciding.
3. **The three restricted accounts are reported, never granted.** FR-314-15's reporting stays exactly as built; no
   account's `EnabledFolders` is widened.

### The hold is `auto_new = 0`, and that is the whole mechanism

*"One film, hold the other 16"* is not a habit anybody has to remember — it is a stored setting, and it is also the
one way to lose the hold by accident. Kind C's switch lives in the DB (`POST /file-fix/{kind}/setting`), and
`planOne` promotes a `would_add` to `pending` only when a kind is **both** `enabled` and `auto_new`. Prod sweeps
itself hourly (`scan_schedule = "30 */1 * * *"`), so:

- **`enabled = 1`, `auto_new = 0`** — the switch is on, nothing queues itself, and the only thing that writes a
  version is an explicit Apply. This is the owner's answer as configuration.
- **`enabled = 1`, `auto_new = 1`** would start **all 17 films / 1.100 TB** at the next `:30`. It is one field away
  from the answered decision, and nothing in the UI warns that it is.

Acceptance 9's Apply is therefore one title — `POST /api/file-fix/c/title/{id}` — and never the kind-wide Apply,
which for kind C is all 17 films in one press.

### What the deploy carries, since the mechanism ships `main` and not one commit

The owner approved deploying `8574493d`; the dev-compose route deploys the tip of `main`, which at the time of the
answer is **six code commits** ahead of prod's `v1.51-120-g80552895` — `8574493d` (FR-314-18), `1664a1ec`
(FR-314-17, a skipped library's paths typeable), `923fd817` (FR-274-17, a Settings save keeps
`suggestions_step_seeded`), and the three 317 commits `83be648f` (FR-317-30), `787af7fa` and `9728729d`
(FR-317-32/33). All six are merged, tested and in the safe direction, and the 317 ones are the **backend** halves
that FR-317-30's as-built note requires to be live *before* the sidecar half — so this deploy advances that
ordering rather than breaking it. Recorded here because "deploy `8574493d`" and "deploy `main`" are not the same
act, and the difference should be written down rather than discovered.

### FR-314-19 — a report nobody reads is not a report

The owner's answer to question 3 makes acceptance 10's surface the remaining gap rather than a nice-to-have: three
accounts will quietly get the original instead of the version, deliberately and indefinitely, and the only record
of it is a log line. A decision to leave something unimproved has to be visible where the switch is.

The restricted-account report appears **in the admin UI**, on the kind C card or beside it: the count of accounts
that cannot see the versions library, and which they are. It is a report, not a control — it offers no button that
widens a permission, because FR-314-15 does not grant and neither does this. It reads from the same pass that
writes the log line today; no new Jellyfin call, no stored state.

### Acceptance (added)

16. With kind C enabled and at least one account whose `EnableAllFolders` is false and whose `EnabledFolders` omits
    the versions library, the admin UI names that account where the kind C card is, not only in the log. With no
    such account, the UI says nothing rather than showing a zero.

### Tests (added)

17. The restricted-account report renders the accounts the pass found, and renders nothing when the pass found
    none — asserted on the view model, not on a log string.

### What this leaves on the phase

The order is **deploy → switch (`enabled = 1`, `auto_new = 0`) → one Apply on one unseeded film → DEB-870**, and
DEB-870 tests the film that Apply picks rather than rebuilding its old subject. FR-314-19 does not gate any of it.
Acceptance 11 and 12 are met; acceptance 9 closes with the one Apply; acceptance 10 closes with FR-314-19.

### FR-314-19 as built (2026-10-13, DEB-986 — the code is inside commit `68ae6244`)

The report is one `note` on the kind C card, under its counts: *"N accounts cannot see the film versions library:
…"*, then what that costs them and that widening it is the owner's in Jellyfin. Nothing at all when the list is
empty — the normal case is nobody shut out, and a zero there would read as a finding.

**Where to find it in the history.** This work is not in a commit of its own: it was staged in the shared checkout
when a concurrent seat committed, and `68ae6244` — titled for 304's `STATUS.md` row (DEB-731) — carries all eight
files. Nothing is lost or mixed in the *content*, the two changes touch disjoint files, and it is recorded here
rather than rewritten, because `main` is public and other seats were committing on it in the same minute. Written
down because a reader looking for FR-314-19 by subject line will not find one.

Three decisions worth writing down, because each of them is a thing the row could have been and is not:

- **It reads the pass, it does not re-run it.** `VersionsLibraryVisibility` now remembers the names its last
  completed pass found (`restricted`), `FileFixService.overview()` reads that for kind C only, and
  `FileFixKindSummary.restricted_viewers` carries it to the card. The card polls every two seconds while a dry run
  is going; the pass talks to Jellyfin twice (`/Library/VirtualFolders`, `/Users`). Those two facts cannot both live
  in the request path, which is why the amendment says *no new Jellyfin call* and why this is a read of a field.
- **Therefore it is empty until a pass has run**, and a pass runs when a version is grouped — i.e. once kind C has
  written its first one. That is later than the switch, and it is still the right moment: before a version exists,
  no account is getting an original *instead of* anything. What the row must never do is claim *nobody is shut out*
  when the truth is *nobody has looked*, and it does not: empty renders nothing, never a zero and never a reassurance.
- **A pass that finds none clears the field**, as does a root or a library that has gone away, so a permission the
  owner widens in Jellyfin takes the row away by itself on the next pass. Nothing is stored in SQLite: the field is
  in memory, a restart empties it, and the next grouping fills it again.

There is no control of any kind on this row. FR-314-15 does not grant and neither does this; a widening control
would be the owner's decision on its own card, not a side effect of a report.

Test 17 is `FileFixServiceTest."the kind C card names the accounts that cannot see the versions library - and
nothing when there are none"` — on the view model the card renders, and asserting that kinds A and B carry nothing,
since only kind C puts a file in that library. The one line the suite cannot reach is the assignment inside the
pass itself: `JellyfinClient` is concrete over the shared outbound pool, so a pass that answers cannot be faked
without a live Jellyfin. What it assigns is `plan.restricted`, which
`VersionsLibraryViewersTest` already covers as a pure function.

## Amendment 2026-10-14 (eleventh) — a library Jellyfin has never scanned cannot resolve its own first file, so the no-scan promise is about the *media* libraries (DEB-1009)

Written before any Kotlin. Acceptance 9 ran on prod on 2026-10-13 (DEB-985, `v1.51-145-gfa567030`) and met every
claim but one: the film did **not** become one title with two versions from the notification alone. FR-314-15 says
the grouping step sends `POST /Library/Media/Updated` and **never a library scan**; on a library Jellyfin has never
scanned, that notification is dropped silently and the version stays on disk, written, verified and invisible.
Because the written design is what failed, the amendment comes before the fix.

### What was measured, and why the earlier measurement did not carry

- The write path sent its one trigger (`{"Path": "/media/versions/…", "UpdateType": "Created"}`), waited the full
  8 × 15 s and gave up with exactly the warning FR-314-15 asks for — *version written but not grouped*. So the
  requirement's own failure path worked; what it reported was not a transient.
- Re-sending the identical body by hand: **204**, nothing logged, nothing resolved, 0 items in the library ten
  minutes later. A 204 is what that endpoint answers whether it acted or not.
- Ruled out by measurement rather than by argument: `EnableRealtimeMonitor` (set `true` on the versions library,
  re-sent, two minutes, still 0 items; set back, so the seventh amendment's configuration is as it was),
  permissions (`docker exec jellyfin ls -la` shows the file `1000:1000` and the container runs as uid 1000), and a
  scan-free refresh (`POST /Items/{versionsLibraryId}/Refresh?Recursive=true&metadataRefreshMode=None&imageRefreshMode=None`
  → 204, three minutes, 0 items, no log line — that mode is a no-op here).
- What resolved it: the same call with `metadataRefreshMode=Default&imageRefreshMode=Default` → *Validating media
  library* in Jellyfin's own log, and the item resolved **19 s** later.
- The reading: `Library/Media/Updated` is routed through `LibraryMonitor`, which walks **up** the reported path
  looking for a `BaseItem` it already knows. The versions library was created with `refreshLibrary=false` (seventh
  amendment, step 2) and had never been scanned, so no item existed anywhere along the path and there was nothing
  to attach the report to.
- The DEB-875 measurement FR-314-15 rests on — *under a minute, with no scan task run at all* — was taken in a
  throwaway Jellyfin whose library **had** been scanned. It is not wrong; it was taken on the only case that
  works. **This is a first-file problem, not a per-film one**: the folder items exist on prod now, so films 2–17
  are expected to resolve from the notification alone, and that expectation is acceptance below rather than an
  assumption to build on.

### The decision, and the candidate that is refused

Three candidates were put with the finding — scan once at configuration, escalate to a refresh when the wait
expires, retry on a timer. **The first and the third are taken; the second is refused.**

#### FR-314-20 — a library jellystructure writes into is scanned once when it is configured, never from the write path

The one-off scan belongs to the act of creating the library, next to the root check and the `skip = true` config
the seventh amendment's steps already cover: a library that has never been scanned has no item for any later
notification to hang from, and that is a property of the library, not of the file. Configuring the versions root
therefore asks Jellyfin to validate that library once, and the step **says so where the owner can see it** — it is
a scan of the versions library only, which holds our own generated files and nothing of the household's.

FR-314-15's *never a library scan* stands as written **for the write path**, and that is the sentence it was
always about: no scan per version, no admin session per version, nothing on an installation that sets
`scan_interval_hours = 0` deliberately. Acceptance 9's *no library scan is run* reads, from today, as **no scan is
run by the write path** — the configuration-time validation of the versions library is not one of its claims
either way.

#### FR-314-21 — grouping is retried on a timer, so a missed window costs minutes and not a human press

Today the assertion is reachable only from a press. `buildPlan()` has exactly one production caller — the
*Find files* route (`FileFixRoutes.kt:27`) — and `planOne`'s only other production call sites are inside
`renameAndQueue` and `runRemove`, the *Rename* and *Remove* buttons. So a version that misses its grouping window
stays ungrouped until somebody is in the admin; on prod that cost about thirty minutes and a full dry run to
recover. A bounded retry on a timer re-asserts the merge for a version that is on disk with no matching source,
and only when its bound is exhausted does FR-314-15's report-against-the-title stand as the outcome.

This changes no state anybody has to keep: FR-314-15's *the merge is asserted, never remembered* governs the retry
too, which re-derives its work from the file on disk and Jellyfin's answer rather than from a stored queue of
pending merges.

#### Refused — escalating to a library refresh from the write path

It puts an unattended library validation inside a job, on an installation whose owner turned self-scanning off on
purpose; and with FR-314-20 in place the condition that needs it cannot recur except when a library is deleted and
re-created, which is a configuration event and is exactly where FR-314-20 already lives. If a bounded retry still
cannot resolve a path, that is a finding worth a human reading it — a version Jellyfin will not resolve is not
something to escalate privileges over on a schedule.

### What this corrects in the tenth amendment — the hazard is real, its trigger is not a clock

The tenth amendment says `auto_new = 1` *"would start all 17 films / 1.100 TB at the next `:30`"*. The reach is
right; the trigger is wrong, and the difference matters because it decides whether the hold can be lost while
nobody is looking. Read in the tree today, `planOne` — the only thing that promotes a `would_add` to `pending` —
is reachable in production from three presses and from nothing else: *Find files* (via `buildPlan()`), *Rename*
and *Remove*. The hourly sweep (`scan_schedule = "30 */1 * * *"`) reaches none of them. So **`auto_new = 1` fires
on a press, not on the clock**: it is still one field away from committing all 17 films, and it still gets no
warning in the UI, but it cannot fire itself overnight. The same fact is why the missed grouping window above
waited for a human rather than healing.

### Acceptance (added)

17. On an installation where the versions library has just been configured and never scanned, the **first** kind C
    version is grouped within FR-314-15's own wait, from the notification alone, with no refresh issued by the
    write path.
18. A version whose grouping window passes with no item resolved is grouped by the retry — no *Find files* press
    and no other human action — and the title is reported only once the retry's bound is exhausted.

### Tests (added)

18. Configuring the versions root asks Jellyfin to validate that library exactly once, asserted on the request
    rather than on a log line, and asks for nothing on the movies or series libraries.
19. A write whose first grouping attempt resolves no item merges on a later retry with `buildPlan()` never called,
    and reports against the title when the bound is exhausted.

### What this does not change

- **`auto_new` stays `0`** and the other 16 films stay held until DEB-870 passes. Nothing here touches the hold.
- **Prod owes no new scan for film 1.** The 2026-10-13 23:30:53Z validation resolved it and the folder items now
  exist. Acceptance 17 is written for a freshly configured library precisely because prod can no longer reproduce
  the state that broke.
- **FR-314-19 is a deploy, not a fix.** The restricted-account row is built and in `main`, behind prod; it is
  mentioned here only so the two are not confused.

### Corrections to this amendment, measured 2026-10-14 (DEB-1009)

The diagnosis above holds and was reproduced off prod. Two of the facts it rests on do not, and one prediction in it
is now a measurement. All of it comes from a throwaway `jellyfin/jellyfin:12.1` — two synthetic libraries, four
one-second films from the image's own ffmpeg, the versions library created `refreshLibrary=false` *after* the movies
library's scan so that it had never been validated — plus a read of prod's own Jellyfin log. No prod write, no
library file, no device.

- **`POST /Items/{libraryId}/Refresh` does not resolve a never-scanned library, in any mode.** `None`,
  `ValidationOnly` and `Default`, with and without `Recursive=true`, with `ImageRefreshMode` set to each: 204,
  nothing resolved, **and no log line at all**. The call credited above with resolving film 1 does nothing.
- **What resolved film 1 was our own hourly pass.** `POST /Library/Refresh` — Jellyfin's `ValidateMediaLibrary`,
  every library — resolves the file in under six seconds and logs *Validating media library*. Prod logs that line
  **twice an hour, every hour**: `21:31:35`, `21:36:28`, `22:32:57`, `22:40:10`, `23:30:53`, `23:35:31` …, because
  `PipelineEngine.signalPipelineComplete` posts `/Library/Refresh` at the end of every pipeline run and
  `scan_schedule = "30 */1 * * *"`. The `23:30:53Z` line is one of that series, 20 s after the hand call — not its
  answer. So **this installation validates its whole library twice an hour of its own accord**, and a dropped
  notification already heals within the hour with nothing new from us; what was missing was only our side asserting
  the merge afterwards, which is FR-314-21.
- **Therefore FR-314-20 cannot be built as written.** *"Asks Jellyfin to validate that library once"* and *"it is a
  scan of the **versions** library only"* name a call that does not exist: the only one that works is
  `/Library/Refresh`, which validates every library. The decision stands either way — the choice between keeping
  FR-314-20 as one `POST /Library/Refresh` on the owner's configuration press, and dropping it to leave the whole
  job to FR-314-21 — but it is the owner's, and it is open. **FR-314-20 is not built.**
- **Films 2–17 are measured, not predicted** (the amendment's own acceptance 17, met off prod). With *any* item in
  that library, a reported path resolves from the notification alone in **50–60 s**, logging
  `LibraryMonitor: versions (/media/versions) will be refreshed` — `LibraryMonitor` walks up to the **library root**
  item, so what it needs is an item in the library and not the film's own folder. FR-314-15's 8 × 15 s window is
  about twice what that case needs.
- **Two more of FR-314-15's claims reproduced on the same lab**, both from the notification alone and both in
  50–60 s: a version rewritten into a folder that still exists after a *Remove* resolves again, and a *Remove*
  (`{"UpdateType": "Deleted"}`) takes the alternate off the film while the film keeps its own file — acceptance 9's
  last sentence, off prod.
- **Reported as data, not as a defect:** *Remove* leaves the film's now-empty folder under the versions root, and
  Jellyfin keeps an item for that folder (its `Path` is the folder, it has no video). It is **not** a source of the
  film, and the write path matches on the exact path, so nothing reads it or breaks on it — but the versions library
  gains one such item per removed film.

### FR-314-21 as built (2026-10-14, DEB-1009)

One `BACKGROUND` loop every 15 minutes (`FileFixService.assertVersionGrouping`), and three decisions worth writing
down:

- **The work list is re-derived, every tick.** The `done` kind C rows, the version file on disk, and Jellyfin's own
  answer for which files it already shows as each film's sources — one `GET /Items?Ids=…&Fields=MediaSources` for the
  whole set. Nothing is stored, so there is no pending-merge queue to go stale, which is FR-314-15's *asserted,
  never remembered* governing the retry exactly as the amendment says it should. A film **absent** from Jellyfin's
  answer is left alone: *Jellyfin does not know this film* and *this film has no sources* are different facts and
  only the second one means merge.
- **The retry asks impatiently.** `groupVersion` grew a `patient` flag: a write keeps FR-314-15's 8 × 15 s, and the
  retry sends the one notification and looks once. A quarter of an hour later the item that the notification
  resolved in about a minute is there and is merged. Waiting minutes per film inside the tick would have made the
  tick outlast its own interval the moment more than a few films were ungrouped.
- **The bound is read off the row, not off a counter** — `now − file_fix.updated_at > 6 h` — so a restart neither
  forgets it nor repeats it, and it needs nothing stored for itself. At the bound the title gets FR-314-15's report
  and the row's detail carries *· not grouped with the film yet*, which is also what makes *once* derivable with no
  state: a row already carrying the note has already been reported. The note is **taken away again** the moment
  Jellyfin does show the version, so it can never outlive what it describes.

Test 19 is three cases in `FileFixServiceTest`: the window passing and the merge landing off the timer with no dry
run in between (and a tick that finds everything grouped writing nothing); the bound, its one report, and that
report being taken back; and the two cases that must do nothing at all — no versions root, and a film Jellyfin has
no answer for. **Acceptance 17 is met off prod and acceptance 18 is covered in CI; what neither can reach is a
viewer's device**, so the one thing that stays on the QA ticket is that the film still plays its version on a device
that cannot decode the original after a merge that the timer made rather than the write.

### FR-314-20 decided and built (2026-10-14, DEB-1009) — the validate is `POST /Library/Refresh`, on the owner's press

The open question above went to the owner on card `40bcd9d1` as (a) keep FR-314-20 with the only call that works, or
(b) drop it and leave the whole job to FR-314-21. He answered at **06:12:37Z**: *"You decide what's best"* — a
delegation, not an option. **(a) is taken**, and three things decide it:

- **(b) cannot reach the case FR-314-20 exists for.** On an installation that validates its library at all, film 1
  heals by itself and FR-314-21 merges it; on one that never validates — no `scan_schedule`, `scan_interval_hours =
  0`, nobody in the admin — the path is never resolved and the first version stays ungrouped forever.
- **On this host (a) is a weaker act than the status quo.** The installation already issues the identical call twice
  an hour of its own accord (`PipelineEngine.signalPipelineComplete`), so a validate on a configuration press
  introduces no kind of load prod does not already take, and one fewer than an ordinary hour.
- **It is the sanctioned lever.** `/Library/Refresh` is the one Jellyfin call this household's rules already name
  for "we changed something on disk, tell Jellyfin"; what they forbid is the **per-item** refresh that fights
  jellystructure for an item's metadata, and this is not that.

**The sentence this corrects.** FR-314-20 said *"it is a scan of the versions library only — our own generated
files, nothing of the household's."* That is withdrawn as unbuildable: it reads **every** library, once, on the
owner's press. Nothing else about FR-314-20 changes, and the refusal of candidate 2 stands exactly as written — this
fires from a configuration write and from nothing else: no job, no schedule, no retry, and never while a version is
being written.

**Test 18 is corrected with it.** It asked for *"exactly once, asserted on the request rather than on a log line,
and asks for nothing on the movies or series libraries"*. The last clause cannot be asserted of a call that takes no
library, so what is asserted instead is the trigger: **exactly one validate on a save that changes the versions
configuration, none on a save that does not, and none at all while no root is set.** Acceptance 17 is unchanged and
is now reachable rather than aspirational — the library has items before the first version is written, so the
notification resolves it inside FR-314-15's own wait with no refresh from the write path.

**Two presses fire it, because the owner's steps are ordered the way they are.** The seventh amendment's step 3 sets
the root and step 4 types the library's `local_path`, and step 2 (create the library, no scan) can come after either
on an installation that is set up in a different order. So the validate fires when a save leaves the root non-blank
**and** either the root itself changed or the mapping that gives that root a name in Jellyfin's view changed
(`jellyfinViewOrNull(root, libraries)`). A save that edits an unrelated library does not fire it; neither does a
save that changes nothing about the versions configuration. At most one validate per configuration change, and the
save never fails on it — an unreachable Jellyfin is logged and the configuration is still written.

**Where the owner can see it**, which this amendment asks for and is the one part that is not server-side: the
*Dolby Vision versions root* field on Settings ▸ Advanced says that saving a new root asks Jellyfin to validate its
libraries once, and why — so the one scan this phase performs is on the screen that performs it rather than only in
a log line.

Test 18 is `ConfigSavePreservesBlocksTest` extended with the four cases above (the two that fire, the two that must
not). What CI cannot reach is the owner's own press against the live Jellyfin, which is on the QA ticket with the
rest of DEB-1009.
