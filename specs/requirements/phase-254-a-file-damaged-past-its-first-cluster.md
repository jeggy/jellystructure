# Phase 254 — A file damaged past its first cluster is found, said, and repaired by jellystructure

## Status

`✓ Built` 2026-09-21, the day it was written — from an owner report: *"Hoppe Hares Byggebande is
still not working properly. Something going on with their files. And when opening it, jellystructure
does not report anything — why is that?"* Spec first, then code. **Not deployed, not dev-reviewed, and
no production file has been touched** — acceptance 4 needs a release and an operator's click.

**Amended by phase 263 (2026-09-26):** FR-254-10's carry-over by stream index mislabelled audio whenever the
library's tracks are not in the source's order (33 of 36 damaged production files with a source), and all three
of its checks passed on the wrong file. The copy now pairs tracks by their packets and maps the source in the
library's order; check 2 is a per-stream `streamhash`, not a packet count. See
`phase-263-a-replacement-matches-tracks-by-content.md`.

### Build (2026-09-21)

- `FileIntegrity` + `FileRepairPlan` (commonMain, pure) · `FileIntegrityService` + `FileDamage`
  (linuxX64) · table `file_integrity` + migration `48.sqm` · four job types in `MediaJobQueue`
  (`file_integrity_sweep`/`_title` on `segments`, `file_replace_from_source`/`file_lossy_repair` on
  `media`) · three routes in `TrackRoutes` · Triage type + `Library ?filter=file_damage` · the detail
  page's `#integrity-banner` · `behavior.verify_files` (TOML only; Settings carries it through a save).
- **A failed check is never stored as clean.** Not in the first draft: a missing `ffmpeg` prints a line
  no demuxer tag matches, which would have read as *zero damage lines*. The pass now reports its exit
  status; 126/127 ⇒ nothing stored, any other failure ⇒ damaged with ffmpeg's own last line.
- **FR-254-5 deviation:** the sweep is re-queued by its own 15-minute loop in `Main.kt` (first run five
  minutes after boot), not from the `scan_files` hook — a changed file turns unchecked by itself
  (FR-254-3), so the loop finds it within one interval and the pipeline needed no new dependency.
- **The Triage count cache is keyed on library version + `FileDamage.revision`** — a finding changes the
  count without any library write, and a version-only key would have served the old number.
- **Verified on a real file without touching it:** FR-254-10's exact command, S01E06, temp file in a
  scratch directory. Damaged library copy: 15 935 video packets. Source and rebuilt file: **16 093**
  (158 restored), zero damage lines, flags equal to the library copy's — which differ from the source's
  on five of seven streams (Danish default, the `dub`/`original` marks), so the carry-over is doing work.
- 10 unit tests, every fixture a real line from the 2026-09-21 pass; `compileKotlinLinuxX64` and
  `compileKotlinWasmJs` clean.

## What is wrong, measured 2026-09-21

A read-only demux pass (`ffmpeg -nostdin -v error -i f -map 0 -c copy -f null -`, `nice 19`) over all 93
files of *Hoppe Hares Byggebande* (`/mnt/series/jellyfin/Hoppy Hare Builders`, 21 GB):

- The same files the 2026-09-17 research report
  (`specs/research-reports/mkv-payload-corruption-beyond-first-cluster-2026-09-17.md`) named are **still
  damaged** — nothing has changed on disk since except S01E05's lossy remux. Every damage line comes
  from the Matroska demuxer itself, **mid-file**: `Element at 0xc0b7c0 ending at 0x2a9881f9d exceeds
  containing master element ending at 0xd77244`, `Unknown-sized element at 0x16c81 inside parent with
  finite size`, `Invalid length 0x61 > 0x8 for element with ID 0xE7`, `0x00 at pos 56420409 invalid as
  first byte of an EBML number`, `Truncating packet of size 1656599 to 4847`.
- It is **data loss**: bytes inside clusters were overwritten by the 2026-09-13 concurrent-repair race
  (phase 201's amendment, fixed by `c10716ef` and phase 234). A stream copy makes the container valid by
  *discarding* what it cannot parse (measured on S01E05: −246 video packets, ≈10 s).
- **Why jellystructure says nothing:** the only detector it has (`scanMkvLayout`, phase 201) stops at the
  end of the **first** `Cluster` — deliberately, so a library sweep costs ~400 KB a file. Damage at
  `0xc0b7c0` is 12 MB past where it stops looking. *The detector found nothing* was never *nothing is
  wrong* (phase 201's own v1.12 lesson, one level up).
- **A clean copy of every regular episode is already on this machine, and jellystructure already knows
  where.** qBittorrent reports the seeding torrents at `/mnt/series/cross-seed-links/<tracker>/Bugs.Bunny.
  Builders.S0x…/`, the files there carry the **same basename** as the library files, are a **different
  inode** (library copy: links=1), and demux with zero errors. The backend container mounts `/mnt/series`
  read-write. `SeedingSnapshot.filenameMatch` already walks exactly these directories.
- The specials are the exception: the library renamed `Hard.Hat.Time.S01E01` → `S00E01`, so no basename
  matches. S00E13 is damaged and has no source this phase can find.

## Owner decisions (2026-09-21, mid-session)

1. **Do not fix the files by hand. jellystructure fixes them.**
2. The repair is reached **either by a button in jellystructure, or by a copy-and-paste command
   jellystructure provides** — so both: the button queues the job, and the same page shows the exact
   command that job runs.

## Requirements

### Finding it

- **FR-254-1 — a deep check, separate from the layout walk.** One file's deep check is one demux pass:
  `ffmpeg -nostdin -v error -i <f> -map 0 -c copy -f null -`, niced, through `SegmentProcessGate`. It
  decodes nothing; its cost is reading the file once. `scanMkvLayout` and `MkvHealthCache` are **not
  changed** — they stay the cheap every-15-minutes sweep.
- **FR-254-2 — what counts as damage is decided by one pure function** (`FileIntegrity.damageLines`,
  commonMain, unit-tested against the real lines above). A line counts when it comes from a **container
  demuxer** (`[matroska,webm @`, `[mov,mp4,…@`, `[avi @`, `[mpegts @`, `[in#…`) or is the demuxer's
  untagged `Truncating packet…`. It does **not** count when it is decoder/parser noise that clean sources
  also produce (`[eac3 @ …] exponent -2 is out-of-range`), a `non monotonically increasing dts`
  complaint from the null muxer, or `Last message repeated`. S01E11 is the regression fixture: it has two
  `eac3` lines and is **clean**.
- **FR-254-3 — the result is persisted, per file, keyed on what the file *is*.** New table
  `file_integrity(path PK, size, mtime, checked_at, damage_count, first_damage)`. A row is *current* only
  while the file's size **and** mtime still match; anything that rewrites the file makes it unchecked
  again with no bookkeeping. This is the one deliberate departure from FR-201-13 ("no new persisted
  state"): a header walk is cheap enough to redo, a whole-file read is not.
- **FR-254-4 — three states, never two.** A file is `damaged`, `clean`, or `unchecked`. *Unchecked* is
  never rendered or counted as *clean* (phase 203's rule).
- **FR-254-5 — it happens without being asked.** A `file_integrity_sweep` job on the **`segments`**
  queue (read-only work; never the `media` queue, which is for writes), `deferWhilePlaying = true`,
  deduped on `integrity:library`. It checks unchecked files **most-recently-modified first** (every file
  jellystructure has ever rewritten carries a fresh mtime, so its own blast radius is examined before
  the untouched majority), stops **between files** when playback starts (in-place requeue), and ends
  itself after a bounded slice (20 minutes) so it can never hold the queue ahead of `detect_segments`.
  It is re-enqueued on the health cache's existing 15-minute loop and after `scan_files`. Gated by
  `behavior.verify_files` (default `true`).
- **FR-254-6 — it happens when asked.** `POST /api/media/{id}/health/integrity/check` queues
  `file_integrity_title` for that title's unchecked files (or all of them with `force`), **not**
  deferrable — an operator's explicit request is never silently parked (phase 178's rule).

### Saying it

- **FR-254-7 — Triage.** A new issue type `file_damage`, *"Damaged video files"*, severity `bad`, on the
  Dashboard's attention breakdown and as `Library ?filter=file_damage`. Counted from the table.
- **FR-254-8 — the title's own page.** `GET /api/media/{id}/health/integrity` answers, per file: state,
  the first damage line, whether a clean source was found and where, and **the command** (FR-254-12).
  The detail page renders a banner when any file is damaged: which episodes, that it is data loss and
  what a viewer sees (a stall, a skip or a smear part-way through — not "loads forever"), how many have
  a clean copy, and the two actions below. When files are merely unchecked it renders one quiet line —
  *"12 of 93 files not verified yet"* + **Check now** — and nothing at all when every file is clean.

### Fixing it

- **FR-254-9 — find the clean source; never assume one.** For a damaged library file, a candidate is a
  file inside any qBittorrent torrent's (local-translated) content path with the **same basename**, that
  is **not the same inode** (a hard link shares the damage), and that **itself passes FR-254-1** at
  repair time. No qBittorrent, no match, or a damaged candidate ⇒ *no source*, said plainly.
- **FR-254-10 — replace, verified, reversible.** Job `file_replace_from_source` on the **`media`** queue
  (one writer per file: phase 234's `MediaFileLock`). Per file: stream-copy the **source** to
  `.jsreplace_<name>` beside the library file with `-cues_to_front 1`, carrying over the **library
  copy's** per-track language, title and dispositions (the operator's edits are why the file was
  rewritten in the first place; they are not lost to fix it). Then verify, and only then swap:
  1. the temp file has **zero** damage lines;
  2. its per-stream packet counts **equal the source's**;
  3. its per-stream language/title/disposition **equal the old library copy's**.
  On success the damaged file is **moved, not deleted**, to `<parent of the library root>/.js-quarantine/
  <path relative to the library root>` (outside anything Jellyfin scans, never over an existing
  quarantined file), ownership and mode are restored, the temp file is
  renamed into place, the integrity row is rewritten `clean`, History records it, and the usual
  post-write Jellyfin sync runs. Any failed step leaves the library file **untouched** and removes the
  temp file. The seeding copy is only ever read.
- **FR-254-11 — the lossy repair stays available, and says what it is.** For a file with no source the
  banner offers **Make playable (loses the damaged moments)** — the existing `-c copy` remux — beside
  the honest alternative (*re-download it in Sonarr: delete the file, search again*). History records a
  lossy repair as lossy. It is never the default and never offered for a file that has a source.
- **FR-254-12 — the command is the job, printed.** `FileRepairPlan` builds one shell snippet per file
  (copy → verify → quarantine → swap); the job runs that plan's steps and the page shows the snippet with
  a Copy button. One builder, so the page cannot advertise a command the job does not run.

## Non-goals

- Matching renamed files (the specials) to a source by content. Open question 1.
- Any write to Sonarr/Radarr. The *arr integration stays read-only.
- Auto-repair. Finding is automatic; **a write to a media file is always an operator's click** (phase
  188/201's posture).
- Deleting quarantined files.

## Open questions

1. A renamed file's source (S00E13): match on duration + stream layout inside torrents that share the
   series' release group? Left out until a second case exists.
2. The first full-library sweep reads every byte once (≈ a day of niced, playback-deferred reads on this
   host, where `ionice` is inert under `mq-deadline`). Is most-recent-first enough, or should the first
   pass be limited to files jellystructure's History says it rewrote?
3. Chore Captain and Ashworth were never deep-checked (2026-09-17 report). This phase answers it by
   running, not by a script.

## Acceptance

1. Unit: `damageLines` on the real S01E06/S01E04/S01E32/S01E28/S02E03 lines ⇒ damage; S01E11's `eac3`
   lines and a `non monotonically` line ⇒ clean.
2. Unit: a `file_integrity` row stops being current when size or mtime changes.
3. Unit: `FileRepairPlan` quotes a path containing `'`, carries every disposition, and never names the
   source as a write target.
4. On production, after deploy: *Hoppe Hares Byggebande* shows the banner naming its damaged episodes,
   the Dashboard counts them, **Replace from clean copy** fixes every regular episode, and a re-run of the
   demux pass over the folder reports zero damage lines outside S00E13.
