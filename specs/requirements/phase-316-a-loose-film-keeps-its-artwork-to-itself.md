# Phase 316 — A loose film keeps its artwork to itself

> Owner, 2026-10-08, from a screenshot of Jellyfin's dashboard: *"Something is wrong here. That backdrop and logo
> should not be together, they are two different ones."* A film playing on Stue TV showed another film's logo.

## Scope

Written 2026-10-08 (dev-authored) from the owner's observation above and a read-only investigation of the films root,
Radarr and qBittorrent; dev-reviewed the same day (section at the end). Touches the backend
(`media/ArtworkDownloader.kt`'s `assetFilePath`, every artwork writer and reader, and `media/LooseFilmMover.kt`) and
the admin's Dashboard.

**The move against the real library is the owner's press by design** — *Move the ticked films*, not a step any agent
completes. That is a property of the phase and not a status: see FR-316-4 and the amendments below. Where the phase
stands at any moment is `STATUS.md`'s row and nowhere else.

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

## Dev review (2026-10-08, against `main` `87e4c735`)

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

## Live (2026-10-09, v1.50-78)

The Dashboard shows the warning **"3 films have no folder of their own"** (wording fixed on merge). **Move** was not
pressed (the owner's button).

## Held by the owner (2026-10-12) — Move waits behind 310's repair

Asked on DEB-668 alongside 310's and 312's repairs. The owner chose **310 alone first, its result shown before the
other two**, so **Move** stays unpressed. As with 312 there is no code left — the move itself is the whole remainder,
and it is the owner's button by design.

Unhold condition: 310's applied result reported and read. Note that the live warning said **3 films**; `planMove`
re-plans at the press, so that count may differ by then and a difference is not a fault.

## Amendment 2026-10-12 — the hold is lifted; Move is the only press left (DEB-769)

310's repair turns out to have been applied on **2026-10-08 11:00:07 UTC** — 7 of 7, and six of the seven still hold
four days later (310's 2026-10-12 amendment has the read-back). The unhold condition above is therefore **met**:
the result exists and has been read. **Move is now the only unpressed owner button of the three.**

The button is live where it matters: 316's commits are ancestors of the build prod is running, so the Dashboard
warning and its overview are reachable now without a deploy. (This paragraph named `v1.51-4-gf27ddae4-dirty` until
2026-10-13; prod had moved twice by then. Read the running version off `/api/health` — it was `v1.51-58-gf9029c3b`
on 2026-10-13 — and never from a version pinned in prose.) The count it shows is whatever `planMove` finds at the press; the recorded **3 films** is the
2026-10-09 reading, not a promise.

Unchanged by this amendment: the press is the owner's, every write stays behind `LooseFilmPorts`, and the second
film's Radarr mismatch is still left alone.

## Amendment 2026-10-13 — the mover's rename costs Radarr the film's provenance too (DEB-881)

Measured on prod on 2026-10-13 against 314c's DV7 rename, which uses **this phase's mover**
(`LooseFilmMover` / `planRenameToFolder`). The finding, the numbers and the fix are written once, in
[314's fourth amendment of 2026-10-13](phase-314-a-file-every-device-can-play-directly.md) as **FR-314-14**; this
section exists so nobody builds the mover's half from 314 alone and so the next reader of this file is not surprised.

**What it means here.** Radarr reacts to a rename by dropping its `moviefile` row and re-importing the renamed file,
and it rebuilds the row from a **fresh parse of the new filename**. A name taken from the folder carries no quality,
group or source tokens, so the row comes back with its quality downgraded (a BluRay remux read as a WEB-DL), and with
`releaseGroup`, `sceneName` and `edition` emptied. **FR-316-4's move produces exactly that kind of name** — FR-316-1
is about artwork names, but step 1's rename gives the video file the basename it derives from the film, not the
release — so the loss is this mover's to carry, whichever caller asked for the rename.

### FR-316-6 — the mover carries provenance across every rename it performs

The mover implements **FR-314-14** once, at the rename step, for all of its callers: read the film's `moviefile`
provenance before the rename, `PUT` it back onto the row Radarr creates afterwards, report rather than drop silently
when the new row never appears, and never invent or upgrade a value it did not read. The acceptance and test are
FR-314-14's acceptance 8 and test 11; they are not restated here.

This is additive to FR-316-4's order — the provenance read goes before step 1's rename and the write-back after the
Radarr step — and it changes none of its existing rules: the renames stay same-filesystem and undone on failure,
qBittorrent is repointed only where it seeds the library path, Radarr's path is still updated with `moveFiles=false`,
and the second film's Radarr mismatch is still left alone.

**It does not hold *Move the ticked films*.** The press remains the owner's and remains unheld: three films, with
`upgradeAllowed: false` on this library's quality profile, so the lost labels cannot start a replacement today (the
reasoning is in FR-314-14). Pressing Move before FR-316-6 is built costs provenance on the films it moves and nothing
else; the fix is worth building either way, because the hazard arrives the moment any movie profile turns upgrades on.

## Amendment 2026-10-15 — Move bound four films to one music album; the repair (DEB-1251)

The owner pressed **Move the ticked films** at 08:29:05Z on 2026-10-15. Turið's read-back is on DEB-785: disk,
qBittorrent, Radarr and the image backups came out right; **the Jellyfin half did not**. Jellyfin 12.1 ignores
`GET /Items?Path=` and returns the whole library, `JellyfinClient.getItemByPath` took `Items[0]`, and every film was
bound to the same item — a **music album**. Step 7 passed because the album has a file. The ids are on DEB-785 and
nowhere in this repository.

Fixing the lookup itself (step 5 binds the item **at the path it asked for**, step 7 asserts that path) only makes the
code do what FR-316-4 steps 5 and 7 already say. It needs no amendment here. This amendment is the repair of what has
already been written wrong, plus two rules that would have stopped it costing more than one id.

### What the prod database says (read-only copy of 08:31:56Z, read 2026-10-15 for this amendment)

Four facts change what the repair can and must do:

1. **The history `before_snapshot` is empty.** `LooseFilmMover` writes its history line with no `beforeSnapshot`, so
   all four `moved_into_folder` entries carry `''`. The only per-film record is each line's `detail`: the steps, the row
   count moved and whether watch data was written. Nothing keeps the old ids. The newest database backup on the host
   is from 2026-09-27, so the old ids cannot be recovered from a backup either. **The repair does not need them**:
   every row it moves carries its own evidence (below).
2. **It is four films, not three.** 314c's DV rename of **2026-10-13 02:26Z** runs through this mover (FR-316-6) and
   its history line says *"Found it under a new id; watch data put back for 1 viewer · 6 rows moved to the new id"*.
   It was bound to **the same album**. Its media row healed on its own three minutes later (`jellyfin_missing` and then
   `realtime_ingest` with the real id). Its six rows and its one viewer's watch data did not heal.
3. **36 rows sit on the album's id, and the four history lines account for exactly 36** (6 + 4 + 20 + 6):
   `playback_session` 10, `playback_start_sample` 6, `playback_qoe` 4, `starter_list` 13, and
   `recommendation.reason_item_id` 3. Turið's 33 left out the last of these. The 20 playback rows all belong to the
   4K REMUX (its line says 20), and every one of them carries evidence of that. The 16 derived rows belong to the
   other three films in a split that nothing records.
4. **The 2026-10-15 press wrote no watch data onto the album.** None of its three lines says *"watch data put back"*,
   and the counter only moves on a 2xx. Nine viewers cannot read the album, so their writes were refused. The one who
   can already had data on it and was skipped. **That data is the 2026-10-13 rename's single write** — the
   `Played: true` Turið read.

### FR-316-7 — the repair: re-bind every film the mover bound to the wrong item

**Shape: a one-off run, by hand, that does a dry run unless told otherwise.** It is not a durable action. Once the
lookup asserts its path the binding cannot recur, and nothing schedules the run. Two admin routes:
`GET /api/loose-films/repair?wrongId=<id>` prints the plan and changes nothing; `POST` with the same `wrongId` and the
plan's digest applies it. The `POST` refuses when a fresh plan differs from the digest, so only a plan someone has
read gets applied. Any seat may run the dry run, because it is read-only. **The apply is the owner's call, given on the
build ticket**, as Move was. The run is idempotent: a second dry run after an apply says there is nothing to do.

**Inputs.** The wrong id is given, not inferred. The dry run then confirms it: Jellyfin reports that id as an item that
is not a film, at a path that is no moved film's path. If it cannot confirm that, the plan stops there. The films are
every `moved_into_folder` history entry, which today means the four above. For each film, the plan finds its video
path now: the media row's path when the row exists, or else the single video in the folder its history line names
under the films root. It then finds **the item Jellyfin has at that path**, using the fixed, path-asserting lookup.
A film with no item yet is listed as *waiting for the scan*. Nothing is moved for it, and a later run picks it up.

**Partitioning the rows (question 2).** Only rows still keyed to the wrong id are considered. Each row is attributed
**by evidence it carries itself**. The plan names the rule used for each row:

| Table | Evidence | Rule |
|---|---|---|
| `playback_session` | `queue_json` entries carry the film's slug in their artwork URL (`/api/tv/image/<slug>/…`) and its title | The row goes to the film whose slug its `item_id`'s entry names. Each **queue entry** whose `id` is the wrong id is rewritten by its own slug, not by a blind `replace()`. |
| `playback_start_sample` | `file_id` is the file's path at the time of the sample | The film whose video, before the move, had that path (the library root plus the moved video's basename; a move into a folder keeps the basename). |
| `playback_qoe` | none of its own | The film of the attributed `playback_session` on the same device whose `[created_at, ended_at]` holds the QoE row's `updated_at` (QoE in **seconds**, sessions in **milliseconds**). Only when exactly one film's sessions match. |
| `starter_list`, `recommendation`, `playback_outbox`, `dirty_item`, `ai_order`, `ai_theme` | none | **Not attributed.** These rows are derived. The run queues the existing full recommendations build (FR-269-8's, the pipeline's own step, not a new writer), which rewrites every scope from the library's real ids. The run hand-edits none of them. |

**A row the evidence cannot account for stays where it is.** That covers a playback row with no slug, a path that
matches no film, or a QoE row matching no session or more than one film. The plan lists each such row by table and
key, and it might be the album's own row. Guessing is never an option. The plan also checks the count: rows attributed
plus derived rows, against the history lines' total. A mismatch is printed, and it does not block the rest.

**The album's own watch data (question 3).** The mover writes only where the target item reads empty or cannot be read
at all. A write that landed means the viewer could read the item, so it read empty. The album's row for any viewer the
mover wrote to was therefore **empty before**. The plan reads the album for every viewer who can read it. **Exactly one
non-empty row is expected** (the 2026-10-13 write). If there is exactly one, the run does two things:

1. **Carries it to the film it came from** (question 5's write, two days late). That film is the one whose history line
   says *"put back for 1 viewer"*. The write happens only when the film's real item reads empty for that viewer.
2. **Resets the album's row for that viewer to empty**, read back. It does this only when the row's `LastPlayedDate` is
   no later than the history line's timestamp. A later date means the viewer has used the album since, and the row is
   left alone and reported.

With zero or several non-empty rows, the run resets none and carries none, and the plan says why. The nine viewers who
cannot read the album have nothing on it: their writes were refused.

**The media rows (question 4).** The run **does not recreate the two swept rows**. Ingest recreates them from Jellyfin,
as it did for the 2026-10-13 film. The run checks each film's media row instead: there is exactly one, at the film's
path now, bound to the item Jellyfin has at that path. A row bound elsewhere (today, the 1080p film's) gets its
`jellyfinId` corrected through the store, with no other field touched. A missing row is reported as *waiting for the
scan*. Anything that lived only in a swept row is gone, because the newest backup predates it. So the plan lists the
two films' history entries since that backup that edited metadata by hand, and the owner can see what to re-check.

**Film watch data on the real items (question 5).** For the three films of 2026-10-15, the mover's per-viewer read
lived only in memory and was never written anywhere. The run therefore **reports and does not reconstruct**. For each
film and each viewer, it says whether the real item carries watch data. When Jellyfin moved the data by provider keys
(dev review 6), the item carries it already. When it did not, the plan gives the count of viewers left empty. The run
never derives a watched flag or a resume point from our session rows, since that would be inventing values it did not
read. A non-zero count goes to the owner as a decision card. It is not a silent loss, and the run itself does not
settle it.

**Records.** Each film's apply writes one history entry (`rebound_jellyfin_id`). Its `before_snapshot` holds the wrong
id, the right id, and every row it moved with its table, key and old value, so the repair itself can be undone by hand.

### FR-316-8 — a move and a sweep never cost a film its row

The 2026-10-15 press raced the 08:30 pipeline. Its sweep deleted two films' media rows on paths the move had just
emptied (*"Movie file not found on disk"*), while step 6 was giving them their new paths. **A film being moved is never
deleted by a sweep.** Either the sweep re-reads a row from the store immediately before deleting it and skips a row
whose file exists at the re-read path, or moves and sweeps never overlap. The engineer chooses. The outcome is what is
specified. The exact interleaving has not been read, so the test is the outcome: a sweep that read the old path before
step 6 ran deletes nothing.

### FR-316-9 — the mover keeps the record it was assumed to keep

Every `moved_into_folder` history entry whose step 6 moved rows writes a `before_snapshot` holding the old id, the new
id, and the table and key of every row moved. The record is written whoever called the mover (316's Move, 314c's
rename). Had this existed, question 2 would have been a lookup.

### Tests added to FR-316-5

7. The lookup: a Jellyfin fake that ignores `Path=` and returns another item first makes step 5 fail the film
   (`partial`, *"Jellyfin hasn't found it in the new folder yet"*). It never binds the other item, and step 7 checks
   the bound item's path.
8. The repair, with fakes: sessions attributed by slug, queue entries rewritten one by one, samples by path, QoE by
   device and window (mixed units), derived rows left to the build, a row with no evidence left and listed, the album
   row reset only on exactly one candidate with a `LastPlayedDate` at or before the write, the carry only onto an empty
   film row, a `POST` refused on a stale digest, and a second run planning nothing.
9. FR-316-8: a sweep holding a pre-move read of a film being moved deletes no row.
10. FR-316-9: the history entry's `before_snapshot` names old id, new id and every moved row.

### Acceptance added

5. After the apply, a fresh dry run plans nothing. No playback row keys to the album's id except rows the plan listed as
   unaccounted. Each moved film's media row, where it exists, is bound to the item Jellyfin has at its path. After the
   full recommendations build, no `starter_list` or `recommendation` row keys to the album's id.
6. The album's own watch data for the viewer the 2026-10-13 rename wrote to reads empty again, or the plan said why it
   was left. That film's real item carries the carried values for that viewer.

### Hold, until FR-316-7's apply is read back

- **Move stays unpressed**, and nobody hand-edits the rows on the album's id. A hand fix would rewrite the evidence the
  repair attributes by.
- **314c's DV rename goes through this mover.** Any rename it makes before the lookup fix ships binds to the album
  again. That is the 2026-10-13 case. 314c renames are held under the same rule as Move until the build that carries
  the lookup fix is deployed.
