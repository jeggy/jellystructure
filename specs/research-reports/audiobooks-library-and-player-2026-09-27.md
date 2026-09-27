# Audiobooks — part 2 of the music investigation: the books library in jellystructure, and books in Ravilo's listening mode

**Date:** 2026-09-27 · **Part 1:** `music-library-and-player-2026-09-27.md` (music). This part reuses its
storage model, its provider client, its `MediaSessionService` and its phone mode, and says only what
audiobooks change.
**Scope:** the household's Jellyfin **books** library (audiobooks; the ebook half is out of scope), managed by
jellystructure like films and music — metadata, covers, grouping of multi-file books, progress — and played
in Ravilo on the phone inside the listening mode from part 1, with the things an audiobook player must have
(resume across parts, chapters, speed, a sleep timer).
**Trigger:** the owner, 2026-09-27: *"we also have audiobooks in our Jellyfin. Let's write a second part to
all of this which will include that as well."*
**Status:** research only. Design: part-2 sections appended to both briefs
(`specs/design-brief-music-in-the-admin-2026-09-27.md` §M, `specs/ravilo/design-brief-music-player-on-the-phone-2026-09-27.md` §M).

Measured on **2026-09-27** against the household server (Jellyfin 12.1.0) with read-only calls, and against
four metadata providers with the library's one book. No title, author or narrator from the library appears
here.

---

## 0. What audiobooks change

1. **Jellyfin has no idea what a book is.** In a `books` collection every audio **file** becomes its own
   `AudioBook` item (`AudioResolver`: multi-part grouping is commented out — *"Until multi-part books are
   handled…"*). The household's library is **one book in 14 MP3 parts** (96 kbps, 2.4 min – 32 min each,
   5.4 h in all) and Jellyfin shows 14 items inside a plain `Folder`. Upstream PR **#17362** (*multi-file
   chapter support*) is **open, held for the next major release, with merge conflicts** as of 2026-09-15 —
   so **the book as an entity is ours to build**, and when Jellyfin eventually groups files itself our
   grouping maps onto it 1:1 (same folder, same order).
2. **Resume exists for audiobooks — per file, and with a trap.** `AudioBook.SupportsPositionTicksResume =
   true` (music has none), but `MinAudiobookResume = 5` **minutes** must be played before a position is
   saved and `MaxAudiobookResume = 5` minutes from the end resets it and marks the file played. **Three of
   the 14 parts are shorter than or close to five minutes** — Jellyfin will *never* remember a position
   inside them. A book-level position therefore has to live in **jellystructure** (a small per-viewer table:
   the first listening state that cannot ride Jellyfin), mirrored to the part best-effort.
3. **No provider covers a Danish shelf.** With the library's one book (a 2008 Danish title): iTunes DK
   found the **author** (5 audiobooks) but not this title; Open Library knows the author (17 works) but not
   the title; Audible `.com`/`.de` return nothing (there is no Danish or Faroese Audible store); **Audnexus**
   (the audiobook aggregator Audiobookshelf uses) is ASIN-keyed with regions au ca de es fr in it jp us uk —
   no search, no Denmark; **Google Books refused without a key** (the shared keyless quota is exhausted —
   a key is mandatory, not optional); DBC's FBI-API (the Danish library data) is for **Danish libraries
   only**. Consequence: the **embedded tags and the folder are the primary source**, the **admin's own
   editing is first-class** (not a fallback), and online providers are an *assist* ladder that will hit for
   English and Nordic mainstream titles and miss the rest. This is the opposite of TMDB and MusicBrainz.
4. **Jellyfin will not show what we know unless we write it into the files.** There is **no NFO provider for
   `AudioBook`** (readers on this library: *Comic Provider · EPUB Metadata · Open Packaging Format* — all
   for ebooks); audiobook metadata enters Jellyfin only through **embedded tags** (title · album = book ·
   artist = author → `People[Author]` · composer = narrator · comment = description · publisher → studio ·
   genre) and a `cover.jpg`. Ravilo does not need Jellyfin's copy — it reads the catalogue from
   jellystructure — so tag-writing decides only what Jellyfin's **own** clients show. Part 1's tag-writing
   question (§7-2 there) now carries more weight.
5. **The player is where audiobooks earn their place**, and it collides with one earlier decision:
   **playback speed** was removed from the *video* player's design by owner decision (2026-09-16, *"no new
   seam member"*). Audiobook listeners expect speed (0.8×–2×, remembered per book), a **sleep timer**,
   **chapters**, **±30 s skips**, **skip silence**, and a **Continue listening** row that says *3 h 12 min
   left*. Media3 has all of it (`setPlaybackSpeed`, `setSkipSilenceEnabled`, playlists for parts); the
   phone's `MediaSessionService` from part 1 carries it with no second service.
6. **In the phone's mode picture, audiobooks are a shelf inside the listening mode, not a third app.** One
   switch, one bar, one mini bar, one service; the bar's fourth item becomes **Audiobooks**, and Playlists
   folds into Library as a chip (a round-1 question in the brief, with this lean).

---

## 1. The books library on the household server (Jellyfin 12.1.0)

| Fact | Measured |
|---|---|
| Library | `books` (*Bøger*), `/media/books` = host `/mnt/media/jellyfin/books` (the same `/mnt/media` volume as films and music); type options Book · AudioBook; **not mapped in `config.toml`** (part 1 §0-1) |
| Contents | **14 `AudioBook` items, 0 `Book` (ebook) items**; root › one folder (a category level) › author › book folder › 14 parts (path depth 6); the book folder is a plain `Folder` with `ChildCount 14` |
| Files | 14 × MP3 96 kbps; `IndexNumber` 1…14 (one number missing in the middle — parts 6 is absent from the sequence, so either a gap in the rip or a naming miss), `ParentIndexNumber` null, `Container` null on the DTO, `Chapters: []` on every part |
| Tags | `Album` = the book title (one distinct value), `AlbumArtist`/`Artists` = the author, `People: [{Type: Author}]`, `Genres: ["Audiobook"]`, `ProductionYear 2008`; no `Overview`, no `Studios`, no narrator, no `SeriesName`, no `ProviderIds`, no images |
| Per-user state | `UserData.PlaybackPositionTicks` 0 on all, `Played` false on all; `Filters=IsResumable` → 0 — nobody has listened through Jellyfin yet |
| Library options | `SaveLocalMetadata: true`, `MetadataSavers: []`, `LocalMetadataReaderOrder: [Comic Provider, EPUB Metadata, Open Packaging Format]`, all `MetadataFetchers: []`, `ImageFetcherOrder: [Image Extractor]` (embedded art only), `EnableLUFSScan: true` |
| Server rules | `MinAudiobookResume 5` (minutes played before playstate is saved), `MaxAudiobookResume 5` (remaining minutes at which it resets and marks played); `MinResumePct 5` / `MaxResumePct 90` / `MinResumeDurationSeconds 300` apply to video |
| Streaming | `PlaybackInfo` with an audio-only profile → MP3 **direct play** (`/Audio/{id}/stream?static=true`, range requests); the same ticket path as music |
| Plugins | none for books or Audible; Jellyfin's *Bookshelf* plugin (Google Books/Comic Vine for **ebooks**) is not installed and would not help audio |

Jellyfin source, confirmed: `AudioBook : Audio, IHasSeries` with `SupportsPositionTicksResume` and
`SupportsPlayedStatus`; `AudioResolver` creates one `AudioBook` per file in a `books` collection and its
multi-part path is commented out; `BookResolver` handles only `.azw .azw3 .cb7 .cbr .cbt .cbz .epub .mobi
.pdf`; `XbmcMetadata` has no AudioBook provider or saver.

---

## 2. Metadata: where an audiobook's facts can come from

### 2.1 The ladder (what part 1's match ladder becomes for books)

1. **Embedded tags + the folder** — title, author, year, genre, cover (all the library has today); folder
   names give author › book; file order gives the parts. **Primary, not fallback.**
2. **The admin types it** — subtitle, narrator, series + position, publisher, description, language. The
   Book page's *Details* tab is an editor first, a viewer second (unlike `media.html`, where TMDB fills
   everything and the admin corrects).
3. **Assist providers**, tried in this order and shown as *suggestions* the admin accepts:
   - **iTunes Search API** — `itunes.apple.com/search?term=…&media=audiobook&country=dk` (also `fo` is not
     a store; `dk`, `no`, `se`, `gb`, `us`), no key, no documented quota (informally ~20 calls/min), fields
     `collectionName`, `artistName` (author — **no narrator**), `description`, `releaseDate`,
     `primaryGenreName`, `artworkUrl100` (rewrite `100x100bb` → `600x600bb` for a usable cover). Measured:
     the author's other books were found in the DK store; this title was not.
   - **Google Books** — `googleapis.com/books/v1/volumes?q=intitle:…+inauthor:…&langRestrict=da`, **a key is
     required in practice** (measured: *Quota exceeded … Queries per day* for the shared keyless project);
     ISBNs, publisher, description, categories, `imageLinks`; book data, not audiobook data (no narrator).
   - **Open Library** — `openlibrary.org/search.json?author=…&title=…`, 1 req/s (3 with an identifying
     User-Agent), covers at `covers.openlibrary.org`; bibliographic only, **no audiobook editions or
     narrators**. Measured: author known (17 works), this title unknown.
   - **Audnexus** — `api.audnex.us/books/{asin}?region=…` (regions au ca de es fr in it jp us uk; 100
     requests/min; no key; **no search** — the ASIN must come from Audible's own catalogue endpoint, which
     Audiobookshelf calls undocumented per store). The richest data (narrators, series with position,
     runtime, publisher, summary, rating) — **for titles Audible sells**, i.e. English and the big
     European languages. Measured: nothing for the Danish title on `.com` or `.de`.
   - **MusicBrainz** — has audiobook releases too (secondary type *Audiobook*/*Spokenword*), and part 1's
     client is already there; low coverage, but free and id-stable when it hits.
   - **Not available:** DBC's FBI-API / bibliotek.dk (Danish libraries only, client credentials by
     agreement); Storytel/Mofibo/Saxo (no public API); Goodreads (API closed 2020).
4. **AcoustID/fingerprinting is useless here** (spoken word is not in its database) — skip the rung.

### 2.2 Covers

iTunes artwork (600 px by URL rewrite), Google Books `imageLinks` (`zoom` parameter for larger), Open
Library covers (`-L` size), the file's embedded art (Jellyfin already extracts it), or the admin's upload.
Audiobook covers are **square**; the admin grid and the phone shelf are 1:1 like albums.

### 2.3 Chapters

Three sources, in precedence: **embedded chapters** (an `.m4b`/`.m4a` carries them; `FfprobeRunner` already
runs `ffprobe -show_chapters` for phase 149 — the same probe answers here), **file boundaries** (a
one-file-per-chapter rip — the household's case; the chapter name is the file's title tag or *Part n*),
**the admin's own markers** (rare; the segment editor's idiom if ever needed). Jellyfin's DTO carries
`Chapters` for video; on these MP3 parts it is empty, so chapters are jellystructure's to compose in every
case.

### 2.4 What to write back, and where

- **Into jellystructure:** everything (the book row is the catalogue Ravilo reads).
- **Into the folder:** `cover.jpg` (Jellyfin reads it), and — **owner decision** — either **embedded tags**
  (title · album · artist=author · composer=narrator · comment=description · publisher · genre · track
  number; the only way Jellyfin's own web UI shows narrator or description) or nothing. There is no NFO
  path for audiobooks. A `metadata.json`/OPF in Audiobookshelf's shape is *not* read by Jellyfin for audio
  and is out of scope unless the household ever runs Audiobookshelf beside Jellyfin.
- **Into Jellyfin's play state:** the part-level position and *played* flags, mirrored from our book
  position (below), so a Jellyfin client that opens the same file lands roughly right.

---

## 3. Grouping and progress — the two things jellystructure must own

### 3.1 The book

`audiobook(id, library_id, folder_path, title, subtitle, authors[], narrators[], series, series_position,
year, publisher, language, description, genres[], cover_state, duration_ms, part_count, match_source,
locked, …)` grouped by **folder** (Jellyfin's own PR #17362 groups the same way); `audiobook_part(book_id,
jellyfin_id, position, path, duration_ms, title, codec, bitrate)` ordered by `IndexNumber`, then filename
(the same numbering fallbacks as phase 152/160 for episodes); `author(id, name, sort_name, image_state,
provider_ids…)` shared with nothing else (an author is not a music artist). A one-file `.m4b` book is a
book with one part and embedded chapters. A folder whose files carry **different** `Album` tags is two books
in one folder — flag it in triage rather than guess.

### 3.2 The position

`audiobook_progress(user_id, book_id, part_index, position_ms, updated_at, finished_at)` — written from
the phone's progress heartbeats (the existing `POST /api/tv/playback/progress` shape with the book's id;
the server resolves which part). Rules: a book is *in progress* from the first heartbeat; **finished** when
the last part's position is within its last 5 minutes or the viewer marks it (mirrors `MaxAudiobookResume`);
*Continue listening* orders by `updated_at`; *time left* = total − (sum of earlier parts + position). The
mirror to Jellyfin: the current part's `PlaybackPositionTicks` and earlier parts marked played, best-effort,
never read back as truth (the 5-minute trap). Per-book **speed** and a **bookmark list** (`position_ms`,
note) are per viewer too — small tables, or JSON on the progress row.

### 3.3 Visibility

The same `libraryId ∈ EnabledFolders` rule (part 1 §1.2); a viewer without the books library sees no shelf
and no *Audiobooks* item in the bar. Kids profiles: no rating source exists for audiobooks either — library
grant only, same lean as music.

---

## 4. Streaming and the player

- **Ticket flow:** part 1's `POST /api/tv/music/play` shape with `book_id` + `part_index` → the part's
  Jellyfin id → `PlaybackInfo` with the audio profile → direct play for MP3/M4B/FLAC, HLS only for what
  Media3 cannot demux (a WMA book would re-encode as music does). The **queue is the book's parts in order**;
  the phone preloads the next part so a chapter boundary is a seamless cut (Media3 playlist, gapless on
  direct play).
- **Speed:** `Player.setPlaybackSpeed(0.8…2.0)` — time-stretch on the device, works on HLS too, remembered
  **per book** per viewer. **Owner decision needed**: the video player's *no speed* ruling (2026-09-16) was
  about the video player; audiobooks are the one place every listener expects it. Lean: **speed for
  audiobooks only**, never on the video player, so the earlier decision stands where it was made.
- **Skip silence:** `ExoPlayer.setSkipSilenceEnabled(true)` — an audiobook-only toggle, off by default.
- **Sleep timer:** client-side (15 · 30 · 45 · 60 min · *end of chapter*), fades the last 10 s, pauses;
  shaking to extend is a gimmick — not drawn.
- **Skips:** ±30 s (audiobook convention; the video player's −10/+30 was tuned for film). Double-tap on the
  cover is not used (part 1's rule — a song is short; a book is not, but the buttons are the convention).
- **Chapters:** a sheet listing chapters with durations; the current one lit; tap seeks (across parts when a
  chapter is a part).
- **Bookmarks:** *Add bookmark* at the current position with an optional one-line note; listed with the
  chapters. Server-side per viewer (§3.2).
- **Resume:** opening a book starts at the book position (one *Continue* button; *Start over* in ⋯). The
  lock-screen card shows book title · author · cover, with ±30 s as the two actions beside play/pause
  (Media3 custom commands) — audiobooks are where *previous/next track* on the lock screen is wrong.
- **Progress reporting:** the same heartbeat cadence as video; positions are the book's, the server maps.
- **Web and Chromecast:** as part 1 — the web `<audio>` seam second, casting later; a TV never.

---

## 5. Where audiobooks sit in the phone (the design question)

| Option | Bar in listening mode | Cost | Verdict |
|---|---|---|---|
| **(a) a shelf in the same mode — lean** | Home · Library · Search · **Audiobooks** · Profile; Playlists becomes a chip in Library | one switch, one service, one mini bar; Home gains a *Continue listening* row | the mode card reads *Music & audiobooks*; the mode's internal name stays `music` |
| (b) a third mode | Video · Music · **Audiobooks**, each with its own bar | a second listening bar, a three-way switch, two mini bars to reconcile | a book is not a different *app*, it is a different *posture* |
| (c) audiobooks as a Library chip | Home · Library (Albums · Artists · Songs · Genres · **Books**) · Search · Playlists · Profile | cheapest | buries *Continue listening* two taps deep — the one row a listener opens the app for |

The *Audiobooks* page: **Continue listening** (large cards with a progress ring and *3 h 12 min left*), then
**All books** (2-up square covers; author under the title; a *finished* tick), **Authors**, **Series** — the
last two as chips, present only when the library has any.

---

## 6. Open questions for the owner (with leans)

1. **Speed on the phone for audiobooks** (lean yes, audiobooks only — the video player's decision stands).
2. **Where the book position lives:** jellystructure table mirrored to Jellyfin (lean) vs Jellyfin-only
   with JellyBook's mark-earlier-parts-played workaround (loses positions in short parts).
3. **Write embedded tags** into audiobook files so Jellyfin's own UI shows narrator/description (lean: offer
   it as an explicit per-book action, off by default — as part 1 §7-2), or never touch the files.
4. **Audiobooks as a shelf in the listening mode** (lean, §5 a) vs a third mode vs a Library chip.
5. **Assist providers:** iTunes + Open Library + Audnexus without keys (lean), plus Google Books if the
   owner creates a key.
6. **Bookmarks** in round 1 (lean: yes, they are cheap and expected) or later.
7. **Ebooks** (`Book` items): out of scope for good (lean — Ravilo has no reader; the library has none).

---

## 7. Prospective phases (verify numbers against `main`; part 1's ladder ends at 278 / R323)

| # | Side | One line |
|---|---|---|
| 279 | admin | **Audiobooks are a library jellystructure manages** — `audiobook`/`audiobook_part`/`author` tables grouped by folder, the mapping row, ACL, the book-level **progress** store with the Jellyfin mirror, health counts |
| 280 | admin | **A book's facts: tags first, the admin second, providers as an assist** — the ladder (iTunes · Google Books · Open Library · Audnexus · MusicBrainz), covers, chapters from `-show_chapters`/file boundaries, the Book and Author pages, triage (*folder holds two books*, *part missing*, *no cover*, *no narrator*) |
| R324 | Ravilo | **Audiobooks in the listening mode** — the *Audiobooks* item, Continue listening, the book page, the player's speed · sleep timer · chapters · bookmarks · ±30 s, book-level resume, the lock-screen actions |

---

## 8. Not established here

- The **missing part number** in the one book (a gap at 6): a rip gap or a filename Jellyfin could not
  number — look at the folder before writing the grouping rules.
- Whether Jellyfin 12.1 fills `Chapters` on an **`.m4b`** `AudioBook` (the library has none to measure).
- iTunes Search's real rate limit (undocumented; the community figure is ~20/min) and whether `country=dk`
  returns Danish **narrator** data anywhere (it did not in the fields seen: `artistName` is the author).
- PR #17362's final shape and release — when Jellyfin groups multi-file books itself, our `audiobook_part`
  becomes a mirror of its parts and the Jellyfin position mirror may become book-level.
- Whether the household wants ebooks at all; nothing here depends on it.

## Method

Read-only, as part 1: the household Jellyfin (`/Library/VirtualFolders`, `/Items` with fields, one
`PlaybackInfo`), Jellyfin's source (`AudioBook.cs`, `ServerConfiguration.cs`, `AudioResolver.cs`,
`BookResolver.cs`, the `XbmcMetadata` provider list) and PR #17362, and four provider APIs queried once each
with the library's one author/title (only hit counts and field names recorded; no name appears in this
report).

## Sources

- Jellyfin books docs — https://jellyfin.org/docs/general/server/media/books/ · `AudioBook.cs`,
  `ServerConfiguration.cs`, `Emby.Server.Implementations/Library/Resolvers/Audio/AudioResolver.cs` on
  github.com/jellyfin/jellyfin · PR #17362 *Audiobooks: add multi-file chapter support* · issue #10668
- Audnexus — https://github.com/laxamentumtech/audnexus (regions via m4b-merge PR #428) ·
  Audiobookshelf's provider list — https://www.audiobookshelf.org/guides/custom-metadata-providers/
- iTunes Search API — https://developer.apple.com/library/archive/documentation/AudioVideo/Conceptual/iTuneSearchAPI/
- Open Library APIs — https://openlibrary.org/developers/api · Google Books — https://developers.google.com/books/docs/v1/using
- DBC FBI-API (Danish libraries only) — https://dbcdigital.dk/faelles-biblioteksinfrastruktur/fbi-api/
- JellyBook (a Jellyfin audiobook client's book-position workaround) — https://github.com/AdrianPlesner/jellybook
