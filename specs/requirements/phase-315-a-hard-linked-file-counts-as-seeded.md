# Phase 315 — A hard-linked file counts as seeded

> Found by 314's dev review (2026-10-08) and measured the same day; the owner had just decided that *a seeded file is
> never modified* (314). Owner, 2026-10-08: *"let's start implementing all our phases/specs"*.

## Status

`Planned` — written 2026-10-08 (dev-authored). Not dev-reviewed, not built. Backend only (`torrent/SeedingSnapshot`,
`SeedingGuard`, every in-place file edit).

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
