# Phase 311 — A remux is never seen half-done by Radarr or Sonarr

> Owner, 2026-10-08, after a film in the library was downloaded a second time overnight and the better copy was
> deleted: *"Yes definitely [put the first copy back], and then a spec so this would not happen."*

## Status

`Planned` — written 2026-10-08 (dev-authored) from a read-only investigation of Radarr's history, its debug log and
the backend's temp-file code. Not dev-reviewed, not built. Backend only (`FfmpegRunner`, `TrackCommandBuilder`,
`MediaJobQueue`, `FileIntegrity`, `MkvLayoutAudit`).

## What happened (2026-10-07/08, local time)

1. **15:00** — Radarr imports a 2026 film: a 13.5 GB 2160p HDR10+ WEB release with two audio tracks, French and
   English (EAC3).
2. **17:24–17:26** — jellystructure remuxes the file (a track-layout repair). Every remux writes its output beside the
   original as **`.jstmp_<name>.mkv`**, then `mv`s it over the original.
3. **17:26:11** — a Radarr disk scan runs mid-remux. Radarr finds **two** video files, does not skip the dot-file, and
   **assigns `.jstmp_<name>.mkv` as the film's file** (log: `Assigning file [.jstmp_…mkv] to movie`).
4. **17:26:59** — the next scan finds the temp file gone and drops it (`movieFileDeleted`, reason
   `MissingFromDisk`). It does **not** re-link the real file, which is still there. From here **Radarr believes the
   film is missing.**
5. **02:35 (next night)** — Radarr's search grabs another release of the film, as a missing film. The profile's
   `upgradeAllowed: false` does not stop a grab for a film with no file.
6. **02:35:36** — a rescan re-finds the real file. When the new download finishes at **02:37**, Radarr compares the
   two. The custom format that blocks foreign dubs scores the original **−10000** (it has a French track), the new
   one 0, so Radarr **deletes the 13.5 GB HDR10+ original** and imports a 4 GB SDR copy with one untagged AAC track.

The owner put the original back by hand the same morning (both downloads still seed).

**This was the fifth time; the first four were lucky.** Radarr and Sonarr history since June show the same
`.jstmp_` assign-then-`MissingFromDisk` on four films and one episode (2026-06-25, 07-06, 07-08, 09-14, 10-07). In
the first four, a rescan re-linked the real file before any search ran.

**Why the dot doesn't hide it.** Radarr's and Sonarr's `DiskScanService` skip a file only if its name matches
`^\.(_|unmanic|DS_Store$)|^Thumbs\.db$`, but they skip any **folder** whose name starts with a dot
(`ExcludedSubFoldersRegex: …|\.[^\\/]+`). Jellyfin ignores every hidden path (`**/.*`). `.jstmp_` is a hidden *file*,
so Radarr and Sonarr see it. A `._` prefix would be skipped, but phase 284 deletes `._` files as macOS leftovers on
every scan, so it can't be used.

FileIntegrity's swap file **`.jsreplace_<name>`** (sitting beside the library file until it is moved into place)
has the same exposure. Music conversion's holding folder (`.js-quarantine`, outside the library root) does not.

## Requirements

### FR-311-1 — Work files live in a hidden folder, never beside the video

Every file jellystructure writes **while making a new version of a library file** goes into a hidden folder in the
same directory: **`<dir>/.jellystructure/<name>`**. That covers the remux output (`.jstmp_`), FileIntegrity's
replacement (`.jsreplace_`) and any later writer of the same kind.

- Same directory ⇒ same filesystem ⇒ the final `mv` into place stays one atomic `rename(2)`. A scan sees either
  the old file or the new one, never both and never neither.
- The folder is created with the library file's owner and mode (the existing `withOwnershipPreservation` stat).
  It is removed (`rmdir`, which only removes an empty folder) after the swap and after a failed or cancelled remux.
- The file keeps its real extension, so ffmpeg keeps choosing the muxer from the name (no `-f` needed).

### FR-311-2 — One function names the work file

`TrackCommandBuilder.tmpPath` (commonMain, also shown as the command preview in the admin) and
`FfmpegRunner.tmpPath` are today two copies of the same string. They become **one** function in `commonMain`,
`WorkFiles.pathFor(libraryPath, kind)`, used by every caller: the remux commands, `runRemux*`'s cleanup,
`MediaJobQueue`'s cancel (`pkill -f`) and disk preflight, `MkvLayoutAudit`, and `FileIntegrity`'s swap. The admin
preview shows the new path.

### FR-311-3 — Leftovers are swept

A crash or a container stop mid-remux can leave a work file behind. The library scan removes any
`.jellystructure/` entry **older than 24 h** that no running job holds (`MediaFileLock`) and logs each removal. It
also removes old-style `.jstmp_*` / `.jsreplace_*` files (none exist today; this is for the transition). It never
touches anything else in the folder.

### FR-311-4 — No other temp video ever lands in a library folder

`scripts/check-workfiles.sh` (CI, beside `check-phases.sh`) fails on any string that builds a path ending in a
video or audio extension inside a library directory other than through `WorkFiles.pathFor` (patterns `/.js`,
`.jstmp`, `.jsreplace`, `"$dir/.`).

### FR-311-5 — Tests are part of the phase

`linuxX64Test`:

1. `WorkFiles.pathFor` for a movie, an episode in `Season 1/`, and a name with `'`, spaces and unicode: the result is
   in `<dir>/.jellystructure/`, and keeps the extension.
2. Each command `TrackCommandBuilder` produces (MKV remux, MP4 remux, track removal, reorder) writes only to that path
   and ends with `mv` from it to the library path.
3. **Radarr's and Sonarr's own filters, copied into a test:** for every work-file path the builder can produce, the
   regexes quoted above exclude it, and Jellyfin's `**/.*` matches it. If an upstream filter changes, this test is
   where it gets updated.
4. A remux that fails or is cancelled leaves no work file and no `.jellystructure/` folder (real files in a temp dir).
5. FileIntegrity's swap (`FileIntegrityTest`'s `Bob's Show` case) quarantines the old file and moves the new one
   in from `.jellystructure/`.
6. The sweep removes a 25 h-old work file, keeps a 1 h-old one, keeps one held by `MediaFileLock`, and never
   removes a non-work file.

## Non-goals

- Changing the Radarr or Sonarr profiles or custom formats. The dub-block custom formats stay as they are (a file
  with English plus a blocked dub still scores −10000; that is the owner's earlier, deliberate choice). With this
  phase, Radarr never thinks a film is missing because of us, so that score can't decide a replacement it shouldn't.
- Telling Radarr or Sonarr to rescan after a remux. They pick up the swapped file on their own schedule, as they
  do today.

## Acceptance

1. During a remux on the production library, a Radarr `RescanMovie` (or a Sonarr `RescanSeries`) logs **one** video
   file in the folder and no `Assigning file`. Radarr's history gets no `MissingFromDisk` for the title.
2. Over two weeks after deploy, Radarr and Sonarr history contain no `movieFileDeleted` / `episodeFileDeleted`
   whose path has `.jellystructure/`, `.jstmp_` or `.jsreplace_`.
3. Jellyfin never lists an item from a `.jellystructure/` folder.

## Open questions (dev)

1. The folder name: `.jellystructure/` (lean: names the owner) vs a shorter `.js/`. Any leading dot works for all
   three scanners.
