# Phase 316 — A loose film keeps its artwork to itself

> Owner, 2026-10-08, from a screenshot of Jellyfin's dashboard: *"Something is wrong here. That backdrop and logo
> should not be together, they are two different ones."* A film playing on Stue TV showed another film's logo.

## Status

`⚠ Partial` — **built 2026-10-08** (branch `worktree-agent-a20570a9bfe5edf8c`, not merged, not deployed); dev-reviewed
2026-10-08. Unit-tested and checked read-only against the real films root, Radarr and qBittorrent; **the move itself has
not run on the real library** — that is the owner's Apply once it is deployed (see *Build notes*). Written 2026-10-08
(dev-authored) from a read-only investigation. Backend (`media/ArtworkDownloader.kt`'s `assetFilePath`, every artwork
writer and reader) and the admin's Dashboard.

## What happened

- Three films in the films library have their video file **loose in the library root** instead of their own folder
  (`find -maxdepth 1` on the films root: 3 video files).
- `assetFilePath(item, filename)` (`ArtworkDownloader.kt:87`) puts a **film's** artwork in the file's directory under
  **folder names**: `MediaKind.MOVIE -> "${item.path.substringBeforeLast('/')}/$filename"` → `poster.jpg`,
  `fanart.jpg`, `clearlogo.png`. For a loose film that directory is the **library root**. Only music videos (phase 171)
  get `<basename>-<kind>` names.
- So on 2026-09-06 a logo for one loose film was written as `<films root>/clearlogo.png`, and on 2026-10-04 the owner's
  hand-picked poster and backdrop for the same film became `<films root>/poster.jpg` and `fanart.jpg` (with `.manual`).
  The root's `clearlogo.png` is byte-identical (md5) to that film's own `<basename>-logo.png`.
- **Jellyfin reads folder-named images in a library root as the library folder's own images.** Its dashboard (and any
  client) falls back to a parent's logo when an item has none, so a different film without a logo (in its own folder)
  showed the loose film's logo over its own backdrop.
- **jellystructure reads the same paths** (`assetFilePath` serves Ravilo's artwork, `posterArtworkExists`, the clearlogo
  ink check), so every loose film resolves the *same* root files: Ravilo can show one loose film's art on all three.
- Only the films root is affected today (the series, music, music-video, books and mixed roots have no folder-named
  images).

## Requirements

### FR-316-1 — Loose files get basename names

A film whose file is not alone in its folder — the folder is a library root, or holds other video files that belong
to other titles — gets Jellyfin's per-file names, the ones Jellyfin already uses for these files:
`<basename>-poster.jpg`, `<basename>-backdrop.jpg`, `<basename>-logo.png`, `<basename>-landscape.jpg`, with their
`.src`/`.manual` sidecars beside them (`<basename>-poster.jpg.manual`). One rule in `assetFilePath`, used by every
writer and reader. A film in its own folder keeps today's names.

### FR-316-2 — Never write folder-level artwork into a library root

`assetFilePath` (and any direct write) refuses a folder-named image whose directory is a library root, whatever the
item kind; a test and `scripts/check-artwork-paths.sh` in CI keep it so.

### FR-316-3 — A warning on the Dashboard: films with no folder of their own

Owner, 2026-10-08: *"Let's create a yellow/orange warning in the jellystructure dashboard where we find these and ask
for moving these to proper subfolders instead, and also provide an overview of which movies and how many seeding they
have. And also provide an apply button, so jellystructure can do it for me, and when doing this it should properly
inform Jellyfin, Radarr and fix file links etc, so the seeding keeps working."*

- The scan finds every video file loose in a library root (and any folder that holds several titles' files), per
  library. If there is at least one, the Dashboard shows a **warning** row (285's grammar, the yellow/orange level):
  *"3 films have no folder of their own · their artwork can show up on other films"*, opening an overview.
- The **overview** lists each film: title and year, file size, the files that belong to it (video, NFO, subtitles,
  `<basename>-*` artwork, plus any folder-named images in the root whose `.src` matches it), **how many torrents seed
  it** (via 315's link index: the torrents whose files are hard links of this file, and any torrent whose own path is
  the library file), whether Radarr manages it (and its Radarr movie id), and the folder it would move to
  (`<root>/<Title> (<Year>)/`, Radarr's naming if Radarr manages it).
- One **Apply** button moves the films listed (all, or the ones ticked). Nothing moves without it.

### FR-316-4 — Apply moves each film so everything keeps working

For each film, in order, stopping that film (and reporting why) at the first failure, never half-moving it:

1. **Preconditions:** nobody is playing it (backend sessions and Jellyfin `/Sessions`); no job holds its file
   (`MediaFileLock`); the new folder doesn't exist yet, or is empty.
2. **Move on the same filesystem with `rename`**, never copy+delete: the video and every file that belongs to it go into
   the new folder in one step each. A `rename` keeps the inode, so **every hard link elsewhere (cross-seed's link dirs,
   a download folder) stays valid and every torrent keeps seeding untouched**. Basename artwork becomes folder names
   (`<basename>-poster.jpg` → `poster.jpg`, `-backdrop` → `fanart.jpg`, `-logo` → `clearlogo.png`, `-landscape` →
   `landscape.jpg`, sidecars with them); a root folder-named image matched to this film by `.src` comes along under the
   same name, and where both exist the one with a `.manual` (the owner's own pick) wins. The other one goes to
   `~/jellystructure/backups/` (nothing is deleted).
3. **qBittorrent:** a torrent whose own content path *is* the moved library file (not a hard link elsewhere) is pointed at
   the new location through its API (`setLocation` / `renameFile`) and rechecked so it keeps seeding; torrents seeding
   from hard links elsewhere are left alone. The overview says which case each torrent is.
4. **Radarr:** if Radarr manages the film, its movie path is updated to the new folder **without letting Radarr move
   files** (`PUT /movie/{id}` with the new `path`, `moveFiles=false`), then a `RefreshMovie` so it finds the file there;
   it must not see the film as missing (311's lesson) — verify its `movieFile` after the refresh.
5. **Jellyfin:** a library scan of the new folder and of the root. A moved file can become a new Jellyfin item with a
   new id; **watch history, resume points and favourites must survive**: before the move, the item's `UserData` for
   every user is read; after the scan, if the new item doesn't carry it (Jellyfin matches user data by its provider
   keys), it is written back. The root's library-level images are re-read.
6. **jellystructure's own data:** the media record's path is updated (no re-scan duplicate), and everything keyed by the
   old Jellyfin id (playback sessions, QoE rows, segments, locks, history, R343/R375 holds) is moved to the new id.
7. **Verify:** the film plays (Jellyfin `PlaybackInfo` finds the file), Radarr has its file, every torrent that seeded
   it is still seeding (or was rechecked to 100 %), and the root no longer holds anything of it. Each step's result is
   shown on the overview and written to the film's History.

### FR-316-5 — Tests

1. A loose film's poster/backdrop/logo paths are basename names; a film in its own folder keeps `poster.jpg` etc.
2. A write of `clearlogo.png` into a library root is refused.
3. The finder lists loose files per library root with the right belonging files (incl. root images matched by `.src`)
   and the right torrent count from a fake link index.
4. Apply with fakes for qBittorrent, Radarr and Jellyfin: the order of FR-316-4 is followed; a `rename` (not copy) moves
   every file; a torrent with its own path in the library gets `setLocation`; Radarr gets `moveFiles=false` then a
   refresh; user data is written back when the new item lacks it; old-id rows move to the new id; a failure at any step
   leaves the film where it was.
5. A `.manual` image wins over the film's older one; the loser is backed up, never deleted.
6. Ravilo's artwork route for a loose film serves that film's own files, never another loose film's.

## Acceptance

1. The Dashboard shows the warning with the three films, their sizes and how many torrents seed each.
2. Apply moves all three into their own folders: they play in Jellyfin and Ravilo with their own artwork, their watch
   history is intact, Radarr shows each with its file, and every torrent that seeded them is still seeding (qBittorrent
   shows no error and no recheck failure).
3. The film from the screenshot no longer shows another film's logo on Jellyfin's dashboard (the films root has no
   folder-named images left).
4. Saving a new logo for a loose film (before it is moved) writes `<basename>-logo.png`; the library root gains no file.

## Dev review (2026-10-08, against `main` `17b8a08a`)

Read against `ArtworkDownloader.assetFilePath` and its callers (`ClearlogoInk`, `MediaRoutes` artwork routes,
`RaviloArtworkService` through `assetPath`), 315's `LinkGuard`/`SeedingDamageCheck`/`SeedingSnapshot`, `ArrClient`,
`QBittorrentClient`, `JellyfinClient` (`getItemByPath`, `getUserDataBulk`, `setUserData`, `refreshItem`),
`MediaStore.updateOne`, `MediaFileLock`, `MediaHistory`, `DashboardRoutes` and the admin's `Dashboard.kt`, and checked
read-only against the real films root, Radarr and the seeder qBittorrent. The design holds; no owner question.

1. **One function decides every artwork path** (`assetFilePath`), used by writers and readers alike, so FR-316-1 is one
   rule there. **Only a library root counts as "not alone".** Checked against the real films library: 5 film folders hold
   more than one video file and every one of them is one title (macOS `._` copies, two versions of one film, a sample).
   A rule judging a folder's contents would have moved those films' `poster.jpg` to per-file names and hidden it, so
   "holds another title's video" is dropped from FR-316-1 and FR-316-3 lists library roots only.
2. **Jellyfin's own names for these files are `-poster.jpg`, `-backdrop.jpg`, `-logo.png`, `-landscape.jpg`** (the three
   loose films already have them from Jellyfin, March 2026), so jellystructure reads and writes exactly those: one name
   per image, no second copy.
3. **Matching a root image to its film needs a second rule.** The root `clearlogo.png`'s `.src` matches no film's
   recorded source (logos aren't recorded on `MediaItem`), but it is byte-identical to one loose film's own
   `-logo.png`. So: `.src` equal to the film's `posterPath`/`backdropPath`, **or** the same bytes as one of the film's
   own images. An image that matches no loose film stays where it is and is listed as *unmatched*.
4. **Every other name of the three files is in cross-seed's link folder** (real data: one film single-link, one with
   2 other names, one with 1; 5 torrents in all, all `stalledUP`/`checkingUP` from `cross-seed-links`). A same-disk
   `rename` keeps the inode, so none of them is touched; no torrent seeds the library path itself today. The
   `setLocation` path stays for that case, behind the same check.
5. **Radarr is messier than the spec assumes**, so step 4 gets a guard: Radarr's path is updated only when Radarr has
   **no file** for the film or its file **is** the one being moved. Real data: one film's Radarr path is Jellyfin's
   `.trickplay` folder with no file (updating fixes it); one film is mapped to a different film's folder and file
   (Radarr's own mismatch — left alone, shown as *Radarr maps this film to another file*); one isn't in Radarr. Paths go
   to Radarr in Jellyfin's view (`/media/movies/…`), which Radarr shares here; a Radarr root that isn't a Jellyfin
   library path skips Radarr with a note.
6. **Jellyfin gives a moved film a new item id** (ids are derived from the path). Its user data usually follows by its
   provider keys, but not always, so FR-316-4 step 5 reads every user's data first and writes it back when the new item
   lacks it. `setUserData` gains optional `IsFavorite`/`PlayCount` (additive). Instead of a whole-library scan, the move
   tells Jellyfin exactly which paths changed (`POST /Library/Media/Updated`: the new folder *Created*, the old file
   *Deleted*), then polls `getItemByPath` for the new item.
7. **Our rows keyed by Jellyfin id** (checked on the real database): `playback_session`, `playback_start_sample`,
   `playback_qoe`, `playback_outbox`, `dirty_item`, `recommendation`, `starter_list`, `ai_order`, `ai_theme`. Everything
   else keys films by slug, which a move doesn't change; the media record keeps its slug and gets the new path and id.
8. **Backups go to `/config/backups/loose-films/<time>/`** (the container's `/config` is `~/jellystructure/config`):
   `~/jellystructure/backups` is not mounted in the backend container. Copies, never moves, across the filesystem
   boundary; only our own images are ever backed up (never a video file).
9. **Never half-moved:** every rename of a film is recorded; any failure before Jellyfin/Radarr are told renames the
   film's files back in reverse order. After the files are in place, a failure in steps 3–7 leaves the film in its new
   folder (which plays) and is shown on that film's row with what still needs doing.

## Build notes (2026-10-08)

**Built** (backend + admin):

- **FR-316-1** — `assetFilePath` (`ArtworkDownloader.kt`): a film loose in a library root gets `<basename>-poster.jpg`,
  `-backdrop.jpg`, `-logo.png`, `-landscape.jpg`; every other film is unchanged. The roots come from
  `LibraryRootsRegistry`, kept current by `ConfigStore` on every load and save (`media/ArtworkPaths.kt`). Readers
  (Ravilo's artwork route, the admin, the clearlogo ink check) go through the same function.
- **FR-316-2** — `refuseRootFolderImage` in `ArtworkDownloader.download` and the upload route (`MediaRoutes`, HTTP 409
  with a plain sentence); `scripts/check-artwork-paths.sh` in CI (fails on `<dir>/<folder-level image>` literals outside
  the artwork files and the music code; checked against a planted violation).
- **FR-316-3** — `LooseFilmsService` (`media/LooseFilmsService.kt`): the Dashboard's **warning** row (`loose_films`,
  domain Films, nothing at zero) from a cheap count of videos in each movie library root; the overview
  (`GET /api/loose-films`, `POST /api/loose-films/scan`) lists each film's files and where they go
  (`planLooseFilm`), its size, the torrents seeding it (315's inode walk, `SeedingDamageCheck.multiLinked`, and whether a
  torrent seeds the library path itself), Radarr's view (`radarrPlan`), the folder it moves to, and root images that match
  no film. The admin's panel (`ui/LooseFilmsUi.kt`) rescans when opened, ticks every waiting film, and follows a move.
- **FR-316-4** — `LooseFilmMover` (`media/LooseFilmMover.kt`) in the spec's order behind `LooseFilmPorts`;
  `POST /api/loose-films/apply` is the owner's press. Clients gained, all additive: `QBittorrentClient.setLocation` /
  `recheck`; `ArrClient.radarrMovie` / `radarrMovieById` / `updateMoviePath` (`moveFiles=false`) / `refreshMovie`;
  `JellyfinClient.notifyMediaUpdated` (`POST /Library/Media/Updated`) and `setUserData`'s optional `IsFavorite` /
  `PlayCount`. Our rows keyed by the old Jellyfin id move with `JellyfinIdRemap.sq` (queries only, no migration). The
  media record keeps its slug and gets the new path and id. Backups: `/config/backups/loose-films/<time>/`.
- **FR-316-5** — `LooseFilmsTest` (15) + `AssetFilePathTest` (3): per-file names, the root refusal, two loose films never
  sharing an image, the plan (rename map, `.manual` wins, the film's own wins a tie, the loser backed up), root-image
  matching, Radarr's rule, folder names, and the mover with fakes (the order, only renames, the hard-link torrent left
  alone and the library-path one repointed, user data written back only where missing, a failed rename undone in
  reverse with the folder removed, nothing moving while playing or into a taken folder, Radarr's other file left alone,
  Jellyfin not finding it yet ⇒ `partial`).

**Checked read-only against the real library (2026-10-08):**

| Film | Size | Other names | Torrents | Radarr | Moves to |
|---|---|---|---|---|---|
| the film from the screenshot's logo | 3.1 GB | 0 | none | has no file (its path is Jellyfin's `.trickplay` folder) → path updated | `<Title> (2023)/` |
| a 4K REMUX | 48.8 GB | 2 (cross-seed link folder) | 2, both via hard links — untouched | not in Radarr | `<Title> (2023)/` |
| a 4K WEB-DL | 37.8 GB | 1 (cross-seed link folder) | 3, all via hard links — untouched | maps it to a different film's file → left alone | `<Title> (2025)/` |

The root's `poster.jpg`/`fanart.jpg` (owner-picked, `.manual`) match the first film by `.src` and win over its older
`-poster`/`-backdrop` (backed up); the root `clearlogo.png` matches it by identical bytes and loses the tie to the film's
own `-logo.png` (backed up). No root image is left unmatched. No torrent seeds a library path itself, so the real move
calls no qBittorrent write.

**Not done / for the owner:**

- The move has only run against fakes. It runs for real when the owner presses *Move the ticked films* after a deploy.
- The second film's Radarr entry pointing at another film's file is Radarr's own mismatch; 316 leaves it alone and says
  so on the row.
