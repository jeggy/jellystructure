# Phase 314 — A file every device can play directly

> Owner, 2026-10-08, deciding step 3 of the no-buffering plan (`specs/research-reports/ravilo-streaming-plan-2026-10-08.md`):
> fix files at the source — **"Yes, opt-in per kind"**: built as jobs switched on per kind (a compatible audio track,
> Dolby Vision 7), **with a dry-run list first; the originals stay in the file**.

## Status

`Planned` — written 2026-10-08 (dev-authored) from the owner's decision above, the streaming evidence and approach
reports of the same day (option D), and a read-only count over Jellyfin's database, the library's files (link counts)
and qBittorrent. Not dev-reviewed, not built. Backend (a new job kind on the media lane, the track resolver), admin
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
