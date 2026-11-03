# Phase 315 — A hard-linked file counts as seeded

> Found by 314's dev review (2026-10-08) and measured the same day; the owner had just decided that *a seeded file is
> never modified* (314). Owner, 2026-10-08: *"let's start implementing all our phases/specs"*.

## Scope

Written 2026-10-08 (dev-authored), dev-reviewed 2026-10-08 (below). Backend (`torrent/SeedingSnapshot`, `SeedingGuard`,
every in-place file edit), plus one Dashboard action button. The 2026-11-03 amendment (DEB-3493) adds the admin's
repair card and the four remux routes.

## What happens today

- `SeedingGuard.check(path)` → `SeedingSnapshot.checkPath` compares the library path, translated to qBittorrent's view,
  with each seeding torrent's `contentPath`. A library file that is a **hard link** to a torrent's file saved elsewhere
  (the usual *arr import: the download folder and the library share one inode) has a different path, so the guard says
  **Allowed**.
- In-place edits (`MkvpropeditRunner`, the track routes in `TrackRoutes.kt`, the flag and default-track writes) then
  change the shared inode, which is also the torrent's data: the torrent's piece check fails, qBittorrent re-downloads
  the changed pieces (undoing our edit) or the torrent errors, and a private tracker may see a bad seed. Cross-seed
  copies break the same way.
- Measured 2026-10-08 (`find -printf %n`): **182 of 338 film files and 4 146 of 9 197 episode files have more than one
  hard link.** A remux that writes a new file and swaps it in (copy-write-verify-swap) breaks the link instead (the
  torrent keeps its data; the library gets a new inode, doubling the space), which is safe for seeding but not free.

## Requirements

### FR-315-1 — One answer to "may this file be changed in place?"

`SeedingGuard` answers **Blocked** for a file the path check blocks **or** whose link count (`stat().st_nlink`) is
greater than 1 **and** any other link lies inside a qBittorrent save path or a cross-seed link dir (found once by inode
in those trees and cached per scan; a file with extra links only inside the library — e.g. a user's own hard link — is
reported, not blocked). When qBittorrent is unconfigured or unreachable, a hard-linked file is **Blocked** (unknown
means no), with the reason shown.

### FR-315-2 — Every in-place writer asks it

Every path that edits a media file in place (mkvpropedit flags, default tracks, track names/languages, tag writes on
video files, 201/263 repairs) goes through FR-315-1 before writing; one helper, and `scripts/check-inplace-guard.sh` in
CI fails on a direct `mkvpropedit`/in-place write that skips it.

### FR-315-3 — Say why, and offer what is possible

A blocked edit says *"This file is shared with a torrent that is still seeding (hard link), so it can't be changed in
place."* and, where the change can be made without touching the file (314's sidecar, a Jellyfin-side setting, an NFO
value), offers that instead. The Dashboard counts blocked edits per kind (285's grammar, information level).

### FR-315-4 — Find what was already changed

A one-off read-only check: for each hard-linked file whose torrent is seeding, ask qBittorrent for the torrent's
piece state (or run its recheck only if the owner presses it) and list torrents with failed pieces that point at a
file jellystructure edited (its History). List only; the owner decides.

### FR-315-5 — Tests

1. A file with `nlink = 2` whose other link is under a qBittorrent save path is Blocked; with the other link only in
   the library it is Allowed with a note; with qBittorrent unreachable it is Blocked.
2. Every in-place writer calls the helper (the guard script, plus a unit test per writer with a fake guard that blocks).
3. A blocked flag edit returns the plain sentence and makes no write.

## Acceptance

1. Changing the default audio track of a hard-linked, seeding episode is refused with the sentence, and the torrent's
   pieces stay intact (qBittorrent shows no recheck error).
2. The same edit on a file with one link still works.

## Open questions (dev)

1. How the household's files got their links (Radarr/Sonarr hard-link import, cross-seed's link dir) — confirm by
   finding the other link of a few files.
2. Whether a remux of a hard-linked file should keep writing a new file (breaking the link, doubling space) or be
   refused too; lean: allowed but shown with the space cost.

## Dev review (2026-10-08, against `main` `dbd8ce43`)

1. **The diagnosis holds, measured on the real disks (read-only, `find -xdev -links +1`).** Every one of the **4 341**
   multiply-linked library media files has its other name **outside** the library roots, all of them in cross-seed's
   hard-link dirs (`/mnt/series/cross-seed-links/<tracker>/…`, `/mnt/media/jellyfin/cross-seed-links/…`; cross-seed's
   `linkDirs`, `linkType: "hardlink"`). None are library-only. Under the path-only guard every one of them read as
   *Allowed*. 4 296 of them map to one of **1 709** torrents in the seeder qBittorrent (1 945 torrents, 1 891 `stalledUP`).
2. **FR-315-1's search is simpler and safer as "a name outside the library".** The seeder qBittorrent sees the link
   dirs under `/media/cross-seed-links` and `/mnt/series/cross-seed-links`, which `[qbittorrent] path_mappings` doesn't
   cover; cross-seed's link dirs aren't in our config at all. Instead of finding the other name in a known torrent tree,
   the guard walks the configured library roots once (cached 10 min, only when a checked file has `st_nlink > 1`) and
   counts each inode's names inside them: `st_nlink` higher than that ⇒ a name elsewhere ⇒ **Blocked**. This needs no
   qBittorrent, no cross-seed config and no new setting, and it also covers a backup or download-folder link. A file
   whose extra names are all inside the library is allowed with a log note, as FR-315-1 asks.
3. **Only `mkvpropedit` (and one audiobook fallback) writes in place.** Every remux (`TrackCommandBuilder`, the media
   job queue's repairs, 201/254/263) writes `.jstmp_<name>` and `mv`s it over the library name: a new inode, the torrent
   keeps its bytes. The music and audiobook tag writer (284) is copy → save → verify → rename, and *Convert…* renames.
   Their guard calls pass `inPlace = false` (open question 2, the lean: allowed, it only breaks the link); the
   audiobook fallback tagger (mutagen `save()` in place) keeps the hard-link check. `scripts/check-inplace-guard.sh`
   only lets the known rename-based writers pass `inPlace = false`.
4. **FR-315-2's "one helper" already exists: `SeedingGuard.check`.** Every in-place writer already called it (TrackRoutes,
   MediaRoutes, TriageRoutes, the audiobook service); the link check went inside it, so none of them changed shape. The
   CI script fails on an `MkvpropeditRunner.set…` call in a file that never asks the guard, and on an `mkvpropedit '…'`
   command outside the runner and the plan/health views.
5. **FR-315-4 can't use piece state.** qBittorrent never re-hashes a seeding torrent on its own (a changed piece is only
   noticed on a recheck, or by the peer that downloads it), so `pieces_have` says nothing. The check instead joins
   History's in-place edits (`set_default`, `set_forced`, `set_language`, `assign_language`, `ep_assign_language`,
   `bulk_reorder_tracks`) × files that still have a name outside the library × the torrents those names belong to, and
   tells the owner to press *Force recheck* on them. **History keeps only its newest 2 000 rows** (≈ 3 days here), so
   older edits are invisible to it.
6. **Unconfigured or unreachable qBittorrent:** a hard-linked file is Blocked (unknown means no); a single-name file keeps
   today's answer (Unconfigured → allowed; Unreachable → the callers refuse).
7. Open question 1 answered: the links come from cross-seed (`linkType: "hardlink"`, two link dirs) and the perma-seed
   link dirs; the library roots never contain them.

## Build notes (2026-10-08)

- **`torrent/LinkGuard.kt`** — `linkInfo` (lstat), `linkVerdict` (the pure rule), `buildInsideCounts` (one walk of the
  library roots, symlinks never followed, only inodes with `st_nlink > 1` kept), `LibraryLinkIndex` (lazy, cached 10 min,
  a failed walk ⇒ hard-linked files refused), `HARD_LINK_REFUSAL` (FR-315-3's sentence), `SeedingRefusals` (refused
  edits by kind for the last 7 days, in memory).
- **`SeedingSnapshot.checkPath`** gains the link check (`linkCheck`), before and alongside the path check, including
  when qBittorrent is unconfigured or unreachable; **`SeedingGuard.check(path, config, inPlace = true, countRefusal =
  inPlace)`**. `SeedingCheckResult.Blocked` gains `hardLink` and `message`; every caller now shows `guard.message`.
- **`inPlace = false`** in the media job queue (remuxes), the music tag writer, *Convert…* and the audiobook writer's
  shared (rename-based) path; the audiobook preview passes `countRefusal = false`.
- **FR-315-4:** `torrent/SeedingDamageCheck.kt` + `server/routes/SeedingRoutes.kt` (`GET/POST /api/seeding/damage-check`,
  background, `nice`/`ionice` `find -xdev -links +1` once per disk, read-only qBittorrent).
- **Dashboard (FR-315-3/-4):** `torrent/SeedingDashboard.kt` adds Services rows — *Edits refused to keep seeding torrents
  intact* (info, count by kind, last 7 days), *Check whether earlier edits changed seeding torrents* (info, a **Check**
  button until run), *Seeding torrents that may hold changed pieces* (warning, names the torrents, *Force recheck* in
  qBittorrent). The button is `seeding_damage_check` in `ui/Dashboard.kt` → `MediaApi.seedingDamageCheck()`.
- **CI:** `scripts/check-inplace-guard.sh`, run after `check-phases.sh` in `ci.yml`.
- **Tests:** `torrent/LinkGuardTest.kt` (8): the rule; the walk counts library names only; a hard link outside is
  Blocked with no qBittorrent, with the sentence, and counted; library-only links and single files are not; an
  unreachable qBittorrent still blocks a hard link and reports *Unreachable* for the rest; a rename-based writer is not
  stopped; a preview is not counted; a torrent is matched to an outside name (unmapped and mapped); the mount root.
  Not built: a route-level test that a blocked flag edit makes no write (the guard script covers the wiring).
- **Validated read-only on the real files (2026-10-08):** 4 341 library media files hard-linked outside the library
  (100 % of the multiply-linked ones), 4 296 mapped to 1 709 seeder torrents. **FR-315-4 finding: 0 torrents** — History's
  window (2 000 rows, ≈ 3 days) holds in-place edits for 2 titles, and neither file shares its bytes with a torrent now.
  Edits older than that can't be seen.


## Amendment 2026-11-03 — a repair that writes a new file runs on a hard-linked seeded file (DEB-3493)

Owner, DEB-3493, 2026-11-03: of *"Repair a new copy, leave the seed alone"* and *"Leave the files as they are"*, the owner
chose the first. That turns open question 2's lean (*allowed, but shown with the space cost*) into an owner decision, and
it narrows 314's principle 2 for repairs (314's amendment of the same date).

### What was found (read-only, 2026-11-03)

- On a series whose cover-as-video episodes (144) are each hard-linked to a cross-seed copy (link count 2, the other
  name under `cross-seed-links/<tracker>/…`), the admin's *Fix all* refused every file with *"Couldn't queue the
  repairs — are the files seeding?"*.
- Four routes enqueue a rename-based remux: `DELETE /api/media/{id}/tracks/{specifier}` and
  `POST /api/media/{id}/tracks/reorder` (`TrackRoutes.kt`), plus their episode twins under
  `/api/media/{id}/episodes/{epFilename}/…` (`MediaRoutes.kt`). At enqueue time (109 FR A.3's fail-fast) each one calls
  `seedingGuard.check(path, config)` with the default `inPlace = true`. The job they enqueue re-checks with
  `inPlace = false` (`MediaJobQueue.guard`, this phase's build notes). The fail-fast therefore refuses what the job
  would allow, so no remux the admin offers on a hard-linked file ever starts.
- The admin drops the 409's `error` body and guesses a cause (the cover-fix handler in `MediaDetail.kt`). That
  contradicts FR-315-3.
- The detail page's *Seeded* chip matches a torrent to an item by file name (`SeedingSnapshot.coversItem` →
  `filenameMatch`), not by inode. It keeps counting the torrent after a repair splits the link.

### FR-315-6 — A rewrite asks the rewrite question at enqueue too

Each of the four routes above asks `SeedingGuard` the same question as the job it enqueues: `inPlace = false`.

- **Allowed:** a file whose other names all lie outside the library (cross-seed or perma-seed link dirs, a download
  folder). The remux writes a new library file. The other names keep the original bytes, so the torrent is untouched.
- **Still Blocked:** a library path that *is* a torrent's content path (the path check is unchanged).
- **Unchanged:** every in-place writer still asks with `inPlace = true`. That covers the `mkvpropedit` routes and
  `POST …/tracks/bulk-reorder`, which sets a default flag in place before its remux.

`scripts/check-inplace-guard.sh` accepts `inPlace = false` in `TrackRoutes.kt` and `MediaRoutes.kt` only at these four
call sites, and still fails on an in-place write that skips the guard.

### FR-315-7 — The repair card says what it costs before it runs

144's repair card (*Cover art muxed as video*, on the film or series page) says how much more disk the repair uses
when any of its files share their bytes with a name outside the library:

> *N of these files share their data with a seeding torrent. Repairing them leaves the torrent's copy untouched and
> uses ≈ X GB more while it seeds.*

For a film or a single file: *This file shares its data with a seeding torrent. Repairing it leaves the torrent's copy
untouched and uses ≈ X GB more while it seeds.* N is the number of files the button repairs that have a name outside
the library. X is the sum of their sizes, one decimal. Files with one name, or with extra names only inside the
library, cost nothing and are not counted. When N is 0 there is no line.

The server computes N and X; the admin renders them and derives nothing. The wire change is additive only: a new
read, or new optional fields on an existing response. Nothing an existing client reads is retyped or removed. The
Tracks tab's own *Remove* and reorder are not given the line (out of scope).

### FR-315-8 — A refused repair says why (FR-315-3, restored)

When the server refuses a queue request (409 or 503), the card shows the server's `error` text, never a guess. For
*Fix all*, when some files queue and some are refused: *Fixing cover track on 5 episodes — remuxes queued. 3 refused:
<reason>.* When the refused files give different reasons, each reason is shown once.

### Acceptance (added)

1. On a series whose cover-as-video episodes are each hard-linked to a cross-seed copy, *Fix all* queues one remux per
   episode. When they finish, the item has left `cover_as_video` and each library file has link count 1. Each
   cross-seed copy has the same inode, size and mtime as before, and its torrent is still seeding with no recheck
   error.
2. Before the press, the card shows N and the GB total, and they equal the count and the summed size of the shared
   files.
3. A cover repair on a library file that is itself a torrent's content path is still refused, and the card shows the
   guard's sentence.
4. A default-flag edit on a hard-linked file is still refused (FR-315-1 unchanged).

### Tests (added)

1. For each of the four routes: a file with link count 2 whose other name is outside the library answers 202 and
   enqueues. A path-matched file answers 409 with the guard's message. (A shared helper may be tested once, plus a
   test that each route calls it.)
2. The cost: two shared files of known size and one single-link file give N = 2 and X = their summed size; library-only
   extra names give N = 0.
3. `check-inplace-guard.sh` passes with the four call sites and still fails on an `MkvpropeditRunner.set…` call in a
   file that never asks the guard.
