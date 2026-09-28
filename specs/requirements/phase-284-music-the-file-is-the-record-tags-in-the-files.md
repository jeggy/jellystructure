# Phase 284 — For music, the file is the record: jellystructure writes standard tags into the files

> Owner, 2026-09-28: for music **the file is the record.** jellystructure writes standard things — embedded tags in
> Picard's vocabulary first, Kodi/Jellyfin sidecars (`album.nfo`, `artist.nfo`, `cover.jpg`, `.lrc`) second — so any
> tool benefits without knowing jellystructure exists. Our own tables keep the work of managing (match state,
> locks, hashes, history) and whatever has no standard home but makes Ravilo better.

## Status

`Planned` — written 2026-09-28 from the research report `research-reports/music-tags-in-the-files-2026-09-28.md`
(§0–§9) and the mockups `design/app/files-tab.js` (the Files tab, shared by Album and Book), `design/app/album.js`,
`design/app/audiobook.js`, `design/app/artist.js`, `design/app/music-library.js`, `design/app/settings.html`
(Music providers card), `design/app/activity.html`, `design/app/dashboard-data.js`. **Not dev-reviewed.** Number
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
