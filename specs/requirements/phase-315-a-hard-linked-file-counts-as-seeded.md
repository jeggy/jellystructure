# Phase 315 — A hard-linked file counts as seeded

> Found by 314's dev review (2026-10-08) and measured the same day; the owner had just decided that *a seeded file is
> never modified* (314). Owner, 2026-10-08: *"let's start implementing all our phases/specs"*.

## Status

`✓ Built` 2026-10-08 on a worktree branch (not merged, not deployed) — written 2026-10-08 (dev-authored), dev-reviewed
2026-10-08 (below). Backend only (`torrent/SeedingSnapshot`, `SeedingGuard`, every in-place file edit), plus one Dashboard
action button.

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

## Dev review (2026-10-08, against `main` `3ef43336`)

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

