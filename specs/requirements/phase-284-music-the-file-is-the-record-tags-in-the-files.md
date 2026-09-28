# Phase 284 — For music, the file is the record: jellystructure writes standard tags into the files

> Owner, 2026-09-28: for music **the file is the record.** jellystructure writes standard things — embedded tags in
> Picard's vocabulary first, Kodi/Jellyfin sidecars (`album.nfo`, `artist.nfo`, `cover.jpg`, `.lrc`) second — so any
> tool benefits without knowing jellystructure exists. Our own tables keep the work of managing (match state,
> locks, hashes, history) and whatever has no standard home but makes Ravilo better.

## Status

`Planned` — written 2026-09-28 from the research report `research-reports/music-tags-in-the-files-2026-09-28.md`
(§0–§9) and the mockups `design/app/files-tab.js` (the Files tab, shared by Album and Book), `design/app/album.js`,
`design/app/audiobook.js`, `design/app/artist.js`, `design/app/music-library.js`, `design/app/settings.html`
(Music providers card), `design/app/activity.html`, `design/app/dashboard-data.js`. **Dev-reviewed 2026-09-28** against `main` `32daeee2` (§Dev review). Number
verified free on `main` 2026-09-28 (admin tops at 283).

**Amends:** 277 (its "no tag writing" lean, H7, is **reversed**) · 278 (the split Save button, Tracks glyphs, an
attention entry) · 281 (the Book page's Files tab **replaces** the switch-only design; the audiobook switch stays
off by default) · 283 (its fixes become moment E). No Ravilo phase: the phone reads Jellyfin, and Jellyfin reads
the files.

## Decisions (owner, 2026-09-28 — every lean taken)

| # | Question | Answer |
|---|---|---|
| Q1 | The switch's default | **On for music.** Audiobooks stay off (281) until the owner says otherwise |
| Q2 | Frames we do not manage | **Keep them**; a checkbox names the junk it would remove (`PRIV ×11 · WCOM ×3`) |
| Q3 | The cover | **Sidecar `cover.jpg` by default**; *Also embed the cover* per album |
| Q4 | ID3 version | **Keep the file's** (all 22 MP3s are v2.3); v2.4 only where no ID3 exists |
| Q5 | ReplayGain source | **Jellyfin's LUFS numbers**, written as `REPLAYGAIN_*`; our own ebur128 later |
| Q6 | Lyrics | **Sidecar `.lrc` only** |
| Q7 | A foreign edit found by a scan | **The file wins** unless the album is locked |
| Q8 | WMA | **Both offered**: tag in place (Jellyfin won't see the ids) or *Convert and tag* |
| Q9 | Where a typed fact goes | A title typed on Tracks is a tag; a biography is `artist.nfo`; a comment is neither |
| Q10 | The Save menu | **Keep the separate entries**; with tag writing on, the first is renamed **Save everything** |

## Requirements

**FR-284-1 — What is written, per format.** The §3 table of the report: Picard's mapping for ID3 · Vorbis · MP4 ·
ASF — the identity block (title · artist · artists · album · album artist · track/disc numbers · date · original
date · genre), the six MusicBrainz ids (recording · track · release · release group · artist · album artist), and
`REPLAYGAIN_TRACK/ALBUM_GAIN/PEAK` from Jellyfin's numbers. **Never** written into a file: a biography, release
notes, match reasoning, locks, or anything that is not a fact about the song.

**FR-284-2 — Five moments, all explicit. A scan on its own never writes.**

| Moment | Trigger | Writes |
|---|---|---|
| A · On match | a pick in *Find match…*, or the ladder's automatic rung while the switch is on | identity + ids, every track |
| B · On Save | *Save → files* or *Save everything* on Album / Artist | only what differs (the Files tab's diff) |
| C · On Convert | 278's *Convert…* | the full set into the new file |
| D · In bulk | Library → Music → selection → *Write tags…* | as A, for every matched album selected |
| E · Repair | 283's flags → the admin's chosen fix | the corrected album / album artist / track number |

**FR-284-3 — A write is safe.** mutagen in place (never an ffmpeg remux) → **copy, write, verify, swap** (254's
shape: a temp copy in the same folder, `ffprobe` reads it back — stream intact, duration unchanged, tags as
written — then `rename()` over the original, ownership and mode restored). A failed verify leaves the original
and says *left as it was: ffprobe could not read the result*. The **seeding guard** runs first, per file:
*Blocked* and *Unreachable* both skip, with their reason; never "write anyway". Writes run in the shared job pool
(213) on the **media lane**. After a write: a recursive Jellyfin refresh of the album and one History entry per
album (*tags written into 12 files: title, album artist, 6 ids*).

**FR-284-4 — Drift is compared by facts, not bytes.** A foreign edit found by a scan (Picard, Lidarr, a person)
**is new information**: the facts update, History says *tags changed outside jellystructure: album artist, year*,
and the page shows what changed — no warning banner. A **lock** (276) is the only thing that holds our version;
then the Files tab says the file disagrees and offers *Take the file's* · *Write ours*. Jellyfin rewriting
`album.nfo` in its own element order counts as the same record (277's `differingFields`); 277's advisor finding
(two writers on one file) stays.

**FR-284-5 — The Files tab on the Album page** (beside Tracks · Artwork · Genres · NFO · History).

1. **Header line:** *Tags in 12 files · 3 say something different from this page · written by jellystructure
   Tuesday* (or *never written*); with *2 files are seeding and will be left alone* and *This server has no
   tagger* (281's sentence) when true.
2. **The grid:** one row per track (file name, a format chip `MP3 · ID3v2.3` / `WMA`), columns grouped
   *Identity* · *Ids* · *Loudness* · *Extras*. Each cell shows **what the file says**, and where the page differs,
   the page's value beneath it with its mark: **same** · **will write** (green) · **file-only** (grey — kept) ·
   **Jellyfin can't read this** (amber, WMA ids) · **seeding** (lock glyph) · **junk** (the frame's name, the
   remove checkbox's effect previewed). A legend sits under the grid.
3. **The action row:** *Write tags to N files* (primary; disabled with its reason when nothing differs, with no
   tagger, with the switch off, or when every file is seeding) · *Also embed the cover* · *Also remove junk frames
   (PRIV ×11, WCOM ×3)* · *Convert and tag* when any file is WMA (hands to 278's Convert with tagging on).
4. **After a write** the grid settles to all-same, the header updates, and a quiet line says *Jellyfin re-read the
   album*.
5. **States** (all reachable in the mockup's fence): all agree · never written · 3 differ · everything seeding ·
   no tagger · an album of WMA · a foreign edit found by the last scan · a locked album whose file disagrees ·
   junk frames present · a failed verify.

**FR-284-6 — The Files tab on the Book page** replaces 281's switch-only design: the same component with 281's
field set (title · album · author · narrator as composer · description as comment · publisher · genre · part
number). Eight of the ten states apply (no WMA, no lock).

**FR-284-7 — The split Save button** on Album, Artist and Book: *Save everything* (NFO + files + refresh; *Save &
Sync* when the switch is off) · *Save → NFO* (*Save → cover.jpg* on a book) · **Save → files** · *Sync Jellyfin*.
Each entry's second line names what it writes and the count (*album.nfo + tags in 12 files*). *Save → files* is
disabled with *Tag writing is off in Settings → Music providers* when it is.

**FR-284-8 — The switch** on the Music providers card (Settings → Connections): **Write tags into music files**,
on by default, with *What this page matches and types reaches every player that reads the files, not only
Ravilo. Save on an album writes the tags; a scan never does. Files seeding in qBittorrent are left alone. WMA files
get every tag, but Jellyfin can't read MusicBrainz ids from WMA.* Two sub-choices, both on: *Keep the files' ID3
version* · *Leave frames we do not manage*. Dimmed while the switch is off.

**FR-284-9 — The Tracks tab** gets one glyph per track — **✓** file agrees · **≠** file differs · **∅** file has no
ids — each a link to that row in Files (the row highlights).

**FR-284-10 — Library → Music bulk:** *Write tags…* on an album selection opens a confirm line with the skip
reasons as counts before anything runs: *Write tags to 52 songs? 60 selected · 8 unmatched (skipped — nothing to
write yet) · 0 seeding · 38 WMA (tagged; Jellyfin won't read their ids)* · Cancel · *Write 52*.

**FR-284-11 — Activity:** a `write_tags` step in the music steps (only while the switch is on) and the per-run
summary *tags written into 20 files · 2 seeding · 38 WMA (ids not readable by Jellyfin)*.

**FR-284-12 — Dashboard** (285's Music group): *Songs whose files don't say what they are* — the count of songs,
→ Library filtered to *file has no ids*. Absent at zero.

**FR-284-13 — macOS leftovers are removed by every scan.** `._*` and `.DS_Store` files in a music or audiobooks
library are deleted by `scan_music` / `scan_audiobooks` (owner, 2026-09-28: *removed automatically on every scan*),
and the run's Activity line says *removed 104 macOS leftover files*. No Dashboard row, no button.

## Out of scope

Tags for films or series (NFO + 254/263 own those) · ebooks · anything Ravilo shows · computing our own loudness.

## Acceptance

1. On the household library (60 tracks, none with a MusicBrainz id today), matching *Salt on the Window* writes
   identity + six ids into its tracks; `mutagen-inspect` shows them; Jellyfin's `ProviderIds` for a track carry the
   recording id after the refresh.
2. The Files tab shows *never written* before and all-same after; History has one entry for the album.
3. A file whose copy fails `ffprobe` is untouched, and the row says why.
4. A seeding file is skipped with *seeding — left as it is*; nothing is written to it.
5. Re-tagging a track's year in Picard and rescanning updates the page's year, with a History line and no banner;
   the same on a locked album shows *Take the file's* · *Write ours*.
6. With the switch off, no moment writes, and *Save → files* says why.
7. After a scan, no `._*` or `.DS_Store` remains under the music and audiobooks folders.

## Open questions (for the dev review)

1. Does Jellyfin read MusicBrainz ids from ASF at all? (one WMA, one refresh, one look at `ProviderIds`) — decides
   whether the amber column is always amber.
2. Is mutagen's in-place ID3 save on a v2.3 file with padding byte-safe, or does the copy step always run?

## Dev review (2026-09-28, against `main` `32daeee2`)

Buildable; the image already has the tagger. **Lidarr 3.1 is live on this host and manages the same folder**, which
settles several items below — the owner's rule for it: work alongside Lidarr and MusicBrainz without either knowing
we exist. Twelve items.

1. **The tagger is in the image; 281's writer is the wrong shape to grow.** `python3-mutagen` is installed (Dockerfile,
   281 FR-281-8) and `AudiobooksMediaService.TAG_SCRIPT` writes ID3/MP4/Vorbis **in place** with `f.save()`, one python
   process per file through `ProcessGate`. 284 replaces it with one shared writer for albums and books (FR-284-6): a
   script shipped in the image (`scripts/tagwrite.py`, copied by the Dockerfile — `check-docker-cache-ids.sh` after)
   that takes a JSON plan for a whole album on stdin (per file: format, the key/value set, the junk frames to drop,
   the cover to embed) and does **copy → save → verify → rename** per file, printing one JSON line per file. One
   process per album, not per file. Verify = `ffprobe -show_streams -show_format` on the copy: codec and duration
   equal to the original's (±1 s) and every written tag read back. `MediaFileLock.withLock(path)` (254) around the
   swap. **Open question 2 is moot: always copy.** A 10 MB file copies in milliseconds, and in-place is exactly what a
   hardlinked import must never get (item 4).
2. **"What the file says" is read with ffprobe** (`format.tags`; it reads ID3, Vorbis, MP4 and ASF tags including
   the MusicBrainz and ReplayGain names) by `GET /api/music/album/{id}/files` when the tab opens — nothing stored.
   ffprobe's key names differ per container (`MUSICBRAINZ_ALBUMID` Vorbis · `MusicBrainz Album Id` ID3 TXXX ·
   `MusicBrainz/Album Id` ASF): one normaliser to Picard's names. The header's *written by jellystructure Tuesday*
   is a new `music_album.tags_written_at` (the audiobook row already has the column, 281).
3. **ReplayGain: gain only, no peak.** Jellyfin's `NormalizationGain`/`AlbumNormalizationGain` are already ingested
   (`MusicIngest` → `trackGainDb`/`albumGainDb`); they are dB gains from its LUFS scan, so `REPLAYGAIN_TRACK_GAIN` /
   `REPLAYGAIN_ALBUM_GAIN` = `"<gain> dB"`. Jellyfin has **no peak**, so `REPLAYGAIN_*_PEAK` is not written (a 1.0
   would be a lie) — FR-284-1's *GAIN/PEAK* reads GAIN. A null gain (Jellyfin's LUFS scan off — the advisor knows the
   setting) writes nothing and the cell reads *no loudness yet*.
4. **Lidarr, as it runs today** (read over its API, 2026-09-28): 3.1.0.4875; root `/media/music` =
   `/mnt/media/jellyfin/music` (our Musik library); 11 artists (all with MBIDs), 865 albums of which 15 have files,
   0 monitored; `writeAudioTags = newFiles` · `scrubAudioTags = false` · `embedCoverArt = false` ·
   `copyUsingHardlinks = true` · `watchLibraryForChanges = true` · `rescanAfterRefresh = always` ·
   `allowFingerprinting = newFiles` · `renameTracks = true` (`{Album Title} ({Release Year})/{track:00} - {Track Title}`)
   · `importExtraFiles = lrc` · every metadata consumer (Kodi/Emby NFO, Roksbox, WDTV) **off**; qBittorrent via
   gluetun, category `lidarr`, downloads on `/mnt/series/qbittorrent/downloads`. Consequences:
   - **Lidarr-imported albums already carry the full Picard id set** (seen on the FLAC albums:
     `MUSICBRAINZ_ALBUMID · ARTISTID · ALBUMARTISTID · RELEASEGROUPID · TRACKID · RELEASETRACKID`). 276's ladder reads
     them through Jellyfin's `ProviderIds` (`MusicMatchService.kt:78`), so they match without a search, and moment A
     writes nothing that differs — the Files tab shows all-same. **We never overwrite an id Lidarr wrote except on a
     manual pick** (moment A's manual half), and then the Files tab's header says *Lidarr manages this album · it
     matched {release}* and the confirm line names the difference — Lidarr's next rescan will list those files as
     unmatched, and the admin should know before, not after.
   - **Hardlinks: none today, and the swap is the guard for the day there are.** 130 audio files, 0 with `nlink > 1`,
     because the downloads mount and the library are different filesystems, so Lidarr's hardlink import falls back
     to a copy. On one filesystem a library file *is* the seeded file; the seeding guard matches torrent content
     **paths** (`SeedingSnapshot.checkPath`), never inodes, so it would say *Allowed* — and an in-place save would
     corrupt the seed. Copy-and-rename breaks the link and leaves the seed intact. That is why item 1 is the only
     allowed write path, and why 281's in-place writer moves onto it (FR-284-6).
   - **No write loop today.** `newFiles` means Lidarr never rewrites a file it has imported; its watcher re-reads a
     file we wrote and its row follows. If *Tag Audio Files* is ever set to *All files*/*Sync*, or the Kodi/Emby
     consumer turned on, Lidarr becomes a second writer of the same tags / of `album.nfo` — 277's two-writers
     finding, for Lidarr. **Build a read-only `[lidarr] { url, api_key }` block** (the `[radarr]`/`[sonarr]` shape):
     `GET /api/v1/config/metadataprovider` and `/api/v1/metadata` feed one advisor finding each (silent while the
     values are what they are today, 212's rule), the Files tab's header line (above) comes from
     `GET /api/v1/album` matched on `foreignAlbumId` = our `release_group_mbid`, and after a write a best-effort
     `POST /api/v1/command {"name":"RescanFolders","folders":[…]}` so Lidarr's row agrees before its next watch
     tick. Lean: **in this phase** — it is the third *arr block, read-only like the other two.
   - **Renames** (`renameTracks = true`): a rename in Lidarr is a remove + add in Jellyfin and our rows follow
     Jellyfin; the ids in the files are what make the re-added tracks match at once — the point of the phase.
   - **MusicBrainz sees only us:** Lidarr talks to its own metadata proxy (`metadataSource` empty = Lidarr's), never
     MusicBrainz directly; 276's 1 req/s client with its own User-Agent is the household's whole footprint there.
5. **Two sidecar collisions, both handled.** Lidarr imports release `.lrc` files; 277's `fetch_lyrics` skips a track
   that already has a sidecar (`MusicMediaService.kt:162`) ✓. Ten album folders hold a `folder.jpg` and no
   `cover.jpg` (none hold both): Jellyfin's local image provider tries `folder` before `cover`, so an album that
   gains both would keep showing the old one — the Artwork tab says which file Jellyfin uses (a fact, not a finding).
6. **Moment A contradicts "a scan on its own never writes".** The ladder's automatic rung runs inside
   `match_musicbrainz`, a pipeline step of the scan; with the switch on by default (Q1) the next run writes ids
   into every album it matches for the first time. That is what the owner chose. FR-284-2's sentence and the card's
   help text (FR-284-8) read: *a scan writes only when it makes a new match; it never rewrites what it already
   wrote* — and the write is part of the match's commit (keyed on the match changing), never a per-run sweep.
7. **WMA and Jellyfin (open question 1):** the research's §"The WMA caveat" says Jellyfin's tag reader has no mapping
   for Picard's ASF names, so the amber column stands until one file proves otherwise — do the one-file test first;
   *Convert and tag* stays the WMA answer (38 of the 130 files).
8. **`._*` removal (FR-284-13) is a new folder walk.** The music and audiobook scanners ingest from Jellyfin and never
   touch the filesystem (`MusicScanner.kt` has no directory access), so this is a walk over the library's
   `local_path` (config has one for Musik and Bøger) deleting only names matching `._*` and `.DS_Store` (and an empty
   `__MACOSX`), counted into the run summary. Today: **100 `._*` + 4 `.DS_Store`** under the music folder — the
   spec's 104 is exact. The guard runs first: a `._` twin inside a seeding folder is part of the torrent's content.
   (Lidarr's import chokes on `._*.wma` too — the research's note — so this helps it as well.)
9. **Lanes.** Writes run as one job per album on the shared pool's `media` lane (`MediaJobQueue.QUEUE_NAMES` =
   `media · segments · subtitles`; there is no music lane — 287 item 2); bulk (FR-284-10) enqueues one job per
   album and Activity counts them.
10. **Schema and config:** `music_album.tags_written_at`, `music_album.embed_cover` (Q3, per album); `[music]` gains
    `write_tags = true`, `keep_id3_version = true`, `keep_unmanaged_frames = true` beside `fetch_lyrics`;
    `[audiobooks].write_tags` stays `false` (Q1). Migration 62.
11. **Wire:** admin only; Ravilo untouched. R319 WireCompat: nothing.
12. **Acceptance 1 is stale:** the library is 130 files now (the 15 Lidarr-imported albums carry ids; the 38 WMA
    carry none) — pick a WMA album for the test, and expect the ids to arrive via *Convert and tag*.
