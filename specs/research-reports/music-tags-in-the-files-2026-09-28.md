# The file is the record: managing music tags and metadata in the audio files themselves

**Date:** 2026-09-28 · **For:** the design project (Cosmos) that owns `design/app/`, and the dev team ·
**Status:** research + design brief, awaiting round-1 directions on the questions in §8 · **Builds on:**
`music-library-and-player-2026-09-27.md` (§2, §4, §7), `audiobooks-library-and-player-2026-09-27.md`, phases
275–278 and 281 (built), 283 (planned). Everything below was measured on the household server on 2026-09-28,
read-only, with the tagger that phase 281 already put in the runtime image.

> Owner, 2026-09-28: *"All this tags management is something we should add into jellystructure."* And the rule
> that decides the shape of it: *"We want jellystructure to hold as little metadata on its own, but rather use
> all standard things, so any tool (like Jellyfin or any other) will benefit from jellystructure without the need
> of knowing of its existence."*

## 0. The principle, and what it changes

Today the music phases treat jellystructure's tables as the place a fact lives and the files as something to be
read. The owner's rule turns that around. **For music the file is the record.** Three stores, ranked:

| Rank | Store | Who reads it | What belongs there |
|---|---|---|---|
| 1 | **The audio file's own tags** (ID3 in MP3, Vorbis comments in FLAC/Ogg/Opus, iTunes atoms in M4A, ASF attributes in WMA) | every player, tagger, scanner and server on earth: Jellyfin, Kodi, Navidrome, Plex, Picard, beets, Lidarr, a car stereo, a phone's file browser | the song's identity: title · artists · album · album artist · track and disc numbers · year · genre · the MusicBrainz ids · loudness gain |
| 2 | **Sidecars beside the file**, in Kodi's and Jellyfin's conventions: `album.nfo`, `artist.nfo`, `cover.jpg`, `folder.jpg`, `backdrop.jpg`, `.lrc` lyrics | Jellyfin, Kodi, Emby, Audiobookshelf-style readers | what a *file* cannot carry: an album's release type and MusicBrainz ids as a unit, an artist's biography, formed/disbanded, the pictures, synced lyrics |
| 3 | **jellystructure's tables** (`music_*`) | jellystructure and Ravilo only | what is *about the work of managing*, never the facts themselves: match state, which release was chosen and why, locks, the cover's and NFO's hashes for drift, history, listeners' positions (audiobooks), what the last lookup found |

The consequence for the design: **a fact typed or matched in the admin is not "saved" until it is in the file.**
The tables cache what the files say so the pages are fast and Ravilo can be served; they are rebuilt from the
files by a scan, and a scan that finds a file changed by another tool (Picard on a laptop, Lidarr on import)
takes the file's word. That is the opposite of 277's *"nothing in this phase opens an audio file for writing"*,
which was the research's lean pending exactly this owner decision (§7 of the music report, H7).

Two things stay true from the movie side: **a file is written only on an explicit action or an explicit switch,
never as a side effect of a scan** (constitution invariant 1's spirit; 254's rule that files are fixed by
jellystructure and never by hand is about *how*, not *whether*), and **a file seeding in qBittorrent is never
touched** (the existing `SeedingGuard`, already wired into 281's tag writer and 278's Convert).

## 1. What the files carry today (measured 2026-09-28)

Sixty tracks, thirty albums, one `music` library. Read with mutagen inside the running container; nothing
written.

| | WMA (38 files, `asf`/`wmav2`) | MP3 (22 files, all **ID3v2.3**) |
|---|---|---|
| Title · artist · album | 38 · 38 · 38 | 22 · 22 · 22 |
| Album artist | 38 | **8** |
| Track number | 38 | 20 |
| Disc number | 12 | 0 |
| Year | 35 | 21 |
| Genre | **1** | 20 |
| Composer · comment | 0 · 0 | 3 · 15 |
| **MusicBrainz ids** | **0** | **0** |
| ReplayGain | 0 | 0 |
| Embedded lyrics | 0 | 0 |
| Embedded cover | 0 | 0 |
| Junk | `WM/EncodingSettings` on all 38 | `PRIV` frames (Windows Media Player's) on 35, `TXXX` on 8, `TSSE` on 8, `WCOM` on 6 |

So the files know *what they are called* and almost nothing else. No file carries an id, a gain, a picture or
lyrics. Every file was remuxed through ffmpeg at some point (`Lavf61.7.100` in the encoder field of all 60), so
whatever richer tags the original rips had are already gone.

**What jellystructure has since put beside them** (phases 275–277 have run on the household server): 24 of 30
albums matched (6 unmatched), 25 albums with a `cover.jpg` (5 without), 49 of 50 recordings *agree* with their
release, 47 `.lrc` and 6 `.txt` lyric sidecars, 30 `album.nfo` and 12 `artist.nfo`. **None of that is in the files.**
A phone that copies the folder, a car that reads the SD card, Navidrome pointed at the same share: they see the
2004 tags.

**Two more findings from the walk:**

- **Every audio file has a macOS resource-fork twin** — 100 `._*` files and 4 `.DS_Store` across the library
  (and 14 more in the audiobooks folder). Jellyfin ignores every dot-file, so they are invisible there; a tagger,
  Lidarr's import or a Soulseek client sees them as unparseable audio. Worth one sweep and one health line; not
  a design surface.
- **Jellyfin's own NFO saver is overwriting ours, today.** All 30 `album.nfo` and all 12 `artist.nfo` on disk
  are in Jellyfin's shape (`<lockdata>`, `<dateadded>`, `<art><poster>` with the container path), stamped
  13:30:51 today, one second after 277's writer stamped its own (`nfoWrittenAt` 13:30:44, drift 10 fields on
  every album). The library has `SaveLocalMetadata: false` but `MetadataSavers: ["Nfo"]`, and in Jellyfin's
  `ProviderManager.IsSaverEnabledForItem` an explicit savers list **wins** over the master switch. The music
  research flagged this (§0 #6) and 242's advisor already carries the finding; the switch has simply not been
  flipped. Under the new principle this is a feature request as much as a bug: Jellyfin re-writing an NFO in its
  own vocabulary is a *standard tool benefiting without knowing us*, and it must be a rewrite we can read back
  without calling it drift (§6).

**Nothing is hardlinked** (0 files with a link count above 1) and the music folder is not a torrent content
path, so today the seeding guard would block nothing. That changes the moment Lidarr imports by hardlink.

## 2. Who reads what — verified in the readers' source

**Jellyfin** (`AudioFileProber.cs`, master, 2026-09-28) reads from embedded tags, on every full refresh:

- the six MusicBrainz ids, by **either** spelling — the Vorbis/uppercase form (`MUSICBRAINZ_ALBUMID`,
  `MUSICBRAINZ_RELEASEGROUPID`, `MUSICBRAINZ_ARTISTID`, `MUSICBRAINZ_ALBUMARTISTID`,
  `MUSICBRAINZ_RELEASETRACKID`, `MUSICBRAINZ_TRACKID`) or Picard's ID3 `TXXX` descriptions
  (`MusicBrainz Album Id`, `MusicBrainz Release Group Id`, `MusicBrainz Artist Id`, `MusicBrainz Album Artist Id`,
  `MusicBrainz Release Track Id`, `MusicBrainz Track Id`);
- `REPLAYGAIN_TRACK_GAIN` and `REPLAYGAIN_ALBUM_GAIN` — **a gain tag beats its own LUFS scan**, and the album
  gain is taken from the first track that carries one;
- `ARTISTS` / `ALBUMARTISTS` multi-value fields (Picard's), split on the library's delimiters;
- embedded lyrics, which it **extracts to a `.lrc` sidecar itself** when `SaveLyricsWithMedia` is on (it is);
- and `album.nfo` / `artist.nfo` through its NFO providers (277 confirmed this), with `ReplaceAllMetadata=true`
  on our refresh call making the files win over what it cached.

**The WMA caveat.** Jellyfin's tag library (ATL) maps ASF's `WM/AlbumArtist`, `WM/Lyrics` and `WM/Picture` to
its standard fields but has **no mapping for Picard's ASF id names** (`MusicBrainz/Album Id`, with a slash), and
the prober looks for the two spellings above only. So MusicBrainz ids written into a WMA file in the standard way
would most likely **not** reach Jellyfin — to be verified in the dev review with one file, but the design
should assume it. It lines up with 278's *Convert…*: the 38 WMA files re-encode on every phone play anyway, and
the conversion is the natural moment to write a full, modern tag set into the new file. **Tag the WMA files
for their own sake (every other reader), but do not promise Jellyfin will see ids in them.**

**Everyone else** reads the same vocabulary because it is Picard's, and Picard's is the de-facto standard
(verified against `picard/formats/{id3,vorbis,mp4,asf}.py`): Kodi, Navidrome, Plex, beets, Lidarr, foobar2000,
Music Assistant, and the phones' own players.

## 3. The standard vocabulary — what to write, per format

Picard's mapping, which is what every reader above expects. One row per fact jellystructure holds after
275–277; the last column says where the fact comes from today.

| Fact | ID3v2 (MP3) | Vorbis (FLAC · Ogg · Opus) | MP4 (M4A) | ASF (WMA) | We hold it as |
|---|---|---|---|---|---|
| Title | `TIT2` | `TITLE` | `©nam` | `Title` | `MusicTrack.title` / `mbTitle` |
| Artists (credits) | `TPE1` + `TXXX:ARTISTS` | `ARTIST` + `ARTISTS` | `©ART` | `Author` | `artists[]` / `mbArtists[]` |
| Album | `TALB` | `ALBUM` | `©alb` | `WM/AlbumTitle` | `MusicAlbum.title` |
| Album artist | `TPE2` + `TXXX:ALBUMARTISTS` | `ALBUMARTIST` | `aART` | `WM/AlbumArtist` | album credits |
| Track / total | `TRCK` = `n/total` | `TRACKNUMBER` + `TRACKTOTAL` | `trkn` | `WM/TrackNumber` | `position` + the release's track count |
| Disc / total | `TPOS` = `n/total` | `DISCNUMBER` + `DISCTOTAL` | `disk` | `WM/PartOfSet` | `disc` + the release's medium count |
| Date · original date | `TDRC`/`TYER` · `TDOR`/`TORY` | `DATE` · `ORIGINALDATE` | `©day` | `WM/Year` · `WM/OriginalReleaseTime` | release date · release-group first date |
| Genre | `TCON` | `GENRE` | `©gen` | `WM/Genre` | `genres[]` (275) |
| Release type | `TXXX:MusicBrainz Album Type` | `RELEASETYPE` | `----:…:MusicBrainz Album Type` | `MusicBrainz/Album Type` | album type (277's NFO) |
| Release (album) id | `TXXX:MusicBrainz Album Id` | `MUSICBRAINZ_ALBUMID` | `----:com.apple.iTunes:MusicBrainz Album Id` | `MusicBrainz/Album Id` | `release_mbid` |
| Release-group id | `TXXX:MusicBrainz Release Group Id` | `MUSICBRAINZ_RELEASEGROUPID` | `…:MusicBrainz Release Group Id` | `MusicBrainz/Release Group Id` | `release_group_mbid` |
| Artist id(s) | `TXXX:MusicBrainz Artist Id` | `MUSICBRAINZ_ARTISTID` | `…:MusicBrainz Artist Id` | `MusicBrainz/Artist Id` | credits' mbids |
| Album-artist id | `TXXX:MusicBrainz Album Artist Id` | `MUSICBRAINZ_ALBUMARTISTID` | `…:MusicBrainz Album Artist Id` | `MusicBrainz/Album Artist Id` | album credits' mbids |
| Release-track id | `TXXX:MusicBrainz Release Track Id` | `MUSICBRAINZ_RELEASETRACKID` | `…:MusicBrainz Release Track Id` | `MusicBrainz/Release Track Id` | `releaseTrackMbid` |
| Recording id | `UFID:http://musicbrainz.org` | `MUSICBRAINZ_TRACKID` | `…:MusicBrainz Track Id` | `MusicBrainz/Track Id` | `recordingMbid` |
| Track / album gain | `TXXX:REPLAYGAIN_TRACK_GAIN` · `…_ALBUM_GAIN` | `REPLAYGAIN_TRACK_GAIN` · `…_ALBUM_GAIN` | `----:com.apple.iTunes:REPLAYGAIN_TRACK_GAIN` | `REPLAYGAIN_TRACK_GAIN` | `trackGainDb` · `albumGainDb` (from Jellyfin's LUFS scan today) |
| Lyrics | `USLT` | `LYRICS` | `©lyr` | `WM/Lyrics` | the `.lrc` sidecar (277) |
| Cover | `APIC` (front) | `METADATA_BLOCK_PICTURE` | `covr` | `WM/Picture` | `cover.jpg` (277) |

Three traps the vocabulary carries, which the design has to show rather than hide:

1. **"Track id" means the recording** in Vorbis/MP4/ASF (`MUSICBRAINZ_TRACKID` = recording) and something
   else in the NFO (`musicBrainzTrackID` = the track on the release). The panel says *recording* and *track on
   the release* in words, never the raw key.
2. **ID3 has versions.** All 22 MP3s are v2.3; Picard's default is v2.4, which older Windows readers and some
   car units do not parse. Lean: **rewrite in the version the file already has**, v2.4 only for files that have
   no ID3 at all.
3. **Multi-artist credits** are one joined string in the classic field (`TPE1` = *A feat. B*) and a list in the
   `ARTISTS` field. Both are written; the panel shows the credit as MusicBrainz states it.

## 4. When jellystructure writes, and what

Five moments, all explicit; a scan on its own never writes (§0):

| Moment | Trigger | Writes | Files |
|---|---|---|---|
| **A · On match** | the album's match is confirmed (a pick in *Find match…*, or the ladder's automatic rung when the *Write tags* switch is on) | the identity block + the six ids, per track, from the chosen release | every track of the album |
| **B · On Save** | the album or artist page's split button: *Save → files* (new) beside *Save → NFO* · *Sync Jellyfin* · *Save & Sync* | whatever differs between the file and what the page states — the diff the panel shows | the tracks whose rows differ |
| **C · On Convert** | 278's *Convert…* (WMA → Opus) | the **full** set into the new file, including what Jellyfin cannot read from WMA | the converted files |
| **D · In bulk** | Library → Music → a selection → *Write tags to N songs* | as A, for every selected album that is matched | the selection |
| **E · Repair** | 283's flags (*several folders, one album* · *folder and album tag disagree* · *a song in someone else's folder*) → the admin's chosen fix | the corrected album / album artist / track number | the flagged files |

What is **never** written into a file: a biography (→ `artist.nfo`), release notes, our match reasoning, locks,
anything from the tables that is not a fact about the song. What is written **only to the sidecar by default**:
synced lyrics (Jellyfin turns embedded lyrics into a sidecar anyway, and a 47-line `.lrc` is easier to edit
than a `USLT` frame) and the cover (a 1:1 `cover.jpg` is what Jellyfin and Kodi read; embedding it is a
per-album *also embed* option — §8 Q3).

**ReplayGain.** Jellyfin already holds a per-track and per-album gain from its LUFS scan, and we already carry
it to the phone (279). Writing it into the file (`REPLAYGAIN_*`, EBU R128 based) makes every other player even
out volume the same way — and once the tag exists Jellyfin prefers it to its own scan, so the two can never
disagree. Lean: write Jellyfin's numbers (§8 Q5); computing our own with `ffmpeg -af ebur128` is a later
option for files Jellyfin has not scanned.

## 5. How a write is made safe (for the dev review; the design shows the outcomes)

- **Tagger, not remux.** mutagen (`python3-mutagen`, already in the image for 281) edits the tag block in place
  and leaves the audio bytes alone; an ffmpeg remux would re-container the file and, for M4B, lose chapter atoms.
  One script, four format branches (ID3 · Vorbis · MP4 · ASF), keyed on §3's table — 281's script generalised.
- **Copy, write, verify, swap** — 254's shape: write into a temp copy in the same folder, `ffprobe` it (stream
  intact, duration unchanged, tags read back as written), then `rename()` over the original; ownership and mode
  restored. A failed verify leaves the original untouched and says why.
- **The seeding guard first**, per file: *Blocked* skips the file and the panel says *seeding — left as it is*;
  *Unreachable* skips too and says qBittorrent could not be asked. Never "write anyway".
- **Through the shared job pool** (213), on the media lane, so a 60-file write never holds up a film's re-order.
- **Then a recursive Jellyfin refresh** of the album (277's `refreshItem(recursive = true)`), which re-probes the
  tracks; and a History entry per album (*tags written into 12 files: title, album artist, 6 ids*).
- **Foreign frames.** Picard's *Clear existing tags* is a real option because old junk (`PRIV`, `WCOM`,
  encoder strings) survives forever otherwise. Lean: **keep what we do not manage** (a comment someone typed is
  theirs), with *also remove the junk these files carry* as a checkbox that names what it would remove (§8 Q2).

## 6. Drift, and who wins

The NFO drift rule (277 FR-277-7, from 139) says: if the file on disk is not the one we wrote, show a banner and
never overwrite silently. Tags need the same rule with one change in spirit: **under §0 a foreign edit is not
a problem to warn about, it is new information.** Someone re-tagged the album in Picard; Lidarr wrote ids on
import; Jellyfin rewrote the NFO in its own shape. The scan reads the file, the facts update, History says
*tags changed outside jellystructure: album artist, year*, and the page shows what changed. A **lock** (276's
per-album lock) is the only thing that holds our version against the file — and then the panel says the file
disagrees and offers *take the file's* or *write ours*.

The specific Jellyfin case from §1: its rewrite of `album.nfo` carries the same ids and titles in a different
element order plus `<lockdata>false</lockdata>` and `<dateadded>`. Comparing **facts, not bytes** (277's
`differingFields` already does this — it reported 10 fields today, which are Jellyfin's additions, not
disagreements) turns that from *drift* into *the same record, in Jellyfin's handwriting*. The advisor finding
stays, because two writers on one file is still a race; but the page should not shout on every refresh.

## 7. What to draw

**7.1 The Album page gains a *Files* tab** (beside Tracks · Artwork · Genres · NFO · History from 278). This is
the panel the whole brief is for. Picard's *pending changes* view is the precedent to beat: one row per track,
one column group per store, and a colour that means *will change*.

- **Header line:** *Tags in 12 files · 3 say something different from this page · written by jellystructure
  Tuesday* (or *never written*). The seeding state and the tagger's presence live here too: *2 files are seeding
  and will be left alone* · *This server has no tagger* (281's existing sentence).
- **The grid:** rows = tracks (file name, format chip `MP3 · ID3v2.3` / `WMA`), columns = the facts of §3 grouped
  *Identity* · *Ids* · *Loudness* · *Extras*. Each cell shows **what the file says**, and when the page's fact
  differs, the page's value beneath it with the change mark. A cell can be: same · will write (green) ·
  file-only (the file has a value we do not, grey — kept) · **cannot reach Jellyfin** (amber, WMA ids) ·
  seeding (locked glyph) · junk (the frame's name, with the remove checkbox's effect previewed).
- **The action row:** *Write tags to 12 files* (primary; disabled with its reason when nothing differs, no
  tagger, or every file is seeding) · *Also embed the cover* · *Also remove junk frames (PRIV ×11, WCOM ×3)* ·
  *Convert and tag* when any file is WMA (hands to 278's Convert with tagging on).
- **After a write:** the grid settles to all-*same*, the header line updates, History gets its entry, and a quiet
  line says *Jellyfin re-read the album*.

**7.2 The split Save button** on Album and Artist pages: *Save → NFO* · **Save → files** · *Sync Jellyfin* ·
*Save & Sync*, where *Save & Sync* now means NFO + files + refresh. The menu names the files and the count, as
281's does (*cover.jpg + tags in 14 files*).

**7.3 The Music providers card** (Settings → Connections, 276's) gains the switch: **Write tags into music
files** — off by default, lean on (§8 Q1) — with 281's sentence rewritten for music: *what this page matches
and types reaches every player that reads the files, not only Ravilo.* Under it, two sub-choices with defaults:
*Keep the files' ID3 version* and *Leave frames we do not manage*.

**7.4 Library → Music bulk action:** *Write tags to N songs* on a selection, with the same reasons for skipping
(unmatched · seeding · WMA-ids caveat) shown as counts before confirming.

**7.5 The Track row** in Tracks (278) gets one glyph per state: *file agrees* · *file differs* · *file has no
ids*, clicking through to that row in Files.

**7.6 Activity:** a `write_tags` step in the music lane's step list, with the per-run summary line *tags written
into 41 files · 2 seeding · 38 WMA (ids not readable by Jellyfin)*.

**7.7 Dashboard attention entry** (278's list): *38 songs whose files do not yet say what they are* → Library
filtered to *file has no ids*. Absent at zero.

**7.8 Audiobooks:** the same *Files* tab on the Book page replaces 281's switch-only design — the parts grid with
the 281 field set (title · album · author · narrator as composer · description as comment · publisher · genre ·
part number). One component, two field sets.

**7.9 A health line** for the resource-fork twins: *104 macOS leftover files in Music (`._*`, `.DS_Store`) ·
Remove* — a sweep, not a page.

**States the mockup must reach:** all-agree · never written · 3 differ · everything seeding · no tagger · an
album of WMA (the amber ids column and the Convert hand-off) · a foreign edit found by the last scan (the History
line and the banner-that-is-not-a-warning) · a locked album whose file disagrees (*take the file's* / *write
ours*) · junk frames present · a failed verify (*left as it was: ffprobe could not read the result*).

## 8. Round-1 questions (directions wanted, owner picks)

| # | Question | Lean |
|---|---|---|
| Q1 | The switch's default: off (281's precedent) or on (the principle says the file is the record, so an unwritten match is unfinished work) | **On** for music, off stays for audiobooks until the owner says otherwise |
| Q2 | Junk frames: keep what we do not manage, or scrub to Picard's clean set | **Keep**, with the named-junk checkbox |
| Q3 | Embed the cover in every file, or sidecar only | **Sidecar by default**, *also embed* per album (phones' file browsers and cars need it; Jellyfin does not) |
| Q4 | ID3 version | **Keep the file's**; v2.4 only where no ID3 exists |
| Q5 | ReplayGain source | **Jellyfin's LUFS numbers**, written as `REPLAYGAIN_*`; our own ebur128 later |
| Q6 | Lyrics: embed too, or sidecar only | **Sidecar only** (Jellyfin extracts embedded lyrics to a sidecar anyway) |
| Q7 | A foreign edit found by a scan: the file wins (update our facts) or we warn and hold | **The file wins** unless the album is locked |
| Q8 | WMA: tag in place and accept Jellyfin will not see the ids, or require Convert first | **Both offered**; the panel says which reader sees what |
| Q9 | Where a typed fact goes: a title typed on the Tracks tab is a tag; a biography is `artist.nfo`; a comment is neither | as stated — the panel's column groups make the split visible |
| Q10 | Should *Sync Jellyfin* stay a separate action once files and NFO are both written, or fold into one *Save everything* | **Keep three**, rename the last *Save & Sync* → *Save everything* |

## 9. Deliverables and numbering

- One admin phase, **284** (next free after 283, verified 2026-09-28): *tags in the files — the Files tab, the
  switch, the five moments, the safety rules, drift by facts.* Amends 277 (H7 reversed), 278 (Save button, Tracks
  glyphs, attention entry), 281 (the Book page's Files tab replaces the switch-only design), and 283 (its fixes
  are moment E). No Ravilo phase: the phone reads Jellyfin, and Jellyfin reads the files.
- Two things to settle in the dev review before the mockup is final: whether Jellyfin reads ids from ASF at all
  (one WMA, one refresh, one look at `ProviderIds`), and whether mutagen's in-place ID3 save on a 2.3 file with
  padding is byte-safe for the `copy → verify → swap` step or needs the copy always.
- Not in scope: writing tags for films or series (they have NFO and the container's own flags; 254 and 263 own
  those), ebooks, and any change to what Ravilo shows.

## Sources

- Household server, 2026-09-28: the music and books folders (read-only walk with mutagen 1.46), the Jellyfin
  library options on disk, a read-only copy of `jellystructure.db`.
- Jellyfin `master`: `MediaBrowser.Providers/MediaInfo/AudioFileProber.cs`,
  `MediaBrowser.Providers/Manager/ProviderManager.cs` (`IsSaverEnabledForItem`),
  `Emby.Server.Implementations/Library/IgnorePatterns.cs`; ATL `ATL/AudioData/IO/WMA.cs`, `ID3v2.cs`.
- Picard `master`: `picard/formats/id3.py`, `vorbis.py`, `mp4.py`, `asf.py`.
- Lidarr's settings wiki (*Tag Audio Files with Metadata*, *Scrub Existing Tags*, the seeding note) — the
  behaviour to match when Lidarr joins the stack.
- This codebase: `audiobooks/AudiobooksMediaService.kt` (281's tagger script), `music/MusicNfo.kt` (277's
  foreign-NFO and drift rules), `torrent/SeedingGuard.kt`, `auth/JellyfinClient.kt` (`refreshItem`),
  `advisor/JellyfinAdvisorService.kt` (the savers finding).
