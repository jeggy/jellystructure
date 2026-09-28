# Phase 280 — Audiobooks are a library jellystructure manages: the book, its parts, and where each listener is

> Owner, 2026-09-27: part 2 of the music round — audiobooks. *"The household's real state: exactly one book, 14
> parts, no cover. Draw the shelf and the grid with one item and no apology."*

## Status

`✓ Built` 2026-09-28, **not deployed** (build notes at the end) — written 2026-09-28 from `specs/research-reports/audiobooks-library-and-player-2026-09-27.md` (§0,
§1, §3, §4), the admin brief §M1 and §M4–§M5, and the mockups (`design/app/audiobook-data.js`,
`library.html?kind=books`, `settings.html#ab-libcard`). **Dev-reviewed 2026-09-28 against `main` `728f22ea`** (below). The research's "279". Builds on
**275** (the library pattern), **152/160** (numbering fallbacks) and **149** (`FfprobeRunner -show_chapters`).

## Decisions (research §6 — leans; not yet answered)

| # | Question | Lean |
|---|---|---|
| 2 | Where the book position lives | **Our table**, mirrored to Jellyfin best-effort and never read back as truth |
| 7 | Ebooks | **Out of scope for good** — Ravilo has no reader; the library has none |
| M6·3 | Series as a Library view | **A view, present only when any book has a series** |
| M6·4 | The Listeners tab | **Show it, read-only** |

## Requirements

**FR-280-1 — The folder is the book.** Jellyfin makes one item per **file** in a `books` library. We group by
folder (Jellyfin's own PR #17362 does the same):

- `audiobook(id, library_id, folder_path, title, subtitle, authors[], narrators[], series, series_position,
  year, publisher, language, description, genres[], cover_state, duration_ms, part_count, source, locked, …)`
- `audiobook_part(book_id, jellyfin_id, number, position, path, duration_ms, title, codec, bitrate)`, ordered
  by `IndexNumber`, then filename (152/160's fallbacks). **`position` is ours** and can be reordered (281
  FR-281-5); `number` is the file's own and is kept.
- `author(id, name, sort_name, image_state, bio, provider_ids…)` — **not** a `music_artist`.

A one-file `.m4b` is a book with one part and embedded chapters.

**FR-280-2 — `scan_books`.** A step inside `scan_files`' run: audio items of `books` libraries, grouped per
FR-280-1, with tags (title · album · artist · year · genre), duration, codec and bitrate from Jellyfin, and
`ffprobe -show_chapters` **only** on single-file books. `Book` items (ebooks) are **ignored**, and the card says
so.

**FR-280-3 — Two things a scan flags, never guesses.**

- **A part is missing** — a gap in the part numbers (the household's book: 1–5, 7–15). Shown on the book with
  *The numbering goes 5 → 7*, and an *It's just numbered wrong* dismissal that stays dismissed for that folder.
- **Folder holds two books** — the parts carry two different `Album` tags. Shown with *Split into two books…*
  (parts grouped by tag; each gets its own page) and *It's one book*.

**FR-280-4 — The position is ours.** `audiobook_progress(user_id, book_id, part_index, position_ms, speed,
updated_at, finished_at)` and `audiobook_bookmark(user_id, book_id, position_ms, note, created_at)`.

- It is written from the phone's heartbeats (the existing progress shape, with `book_id` and a **book**
  position; the server resolves the part).
- A book is *in progress* from the first heartbeat and **finished** when the last part is within its last 5
  minutes or the viewer marks it.
- *Time left* = total − (earlier parts + position).
- The mirror to Jellyfin writes the current part's `PlaybackPositionTicks` and marks earlier parts played,
  best-effort. It is **never read back**, because Jellyfin never saves a position in a part under 5 minutes
  (the household's book has four such parts).

**FR-280-5 — Visibility.** The same `libraryId ∈ EnabledFolders` rule. Without the books library: no shelf, no
Audiobooks chip or item, no search hit. Kids profiles follow the grant only.

**FR-280-6 — The books library card** (Settings → Libraries), in the Musik card's shape:

- the paths;
- *Metadata: **the files, then you** · Suggestions: iTunes · Open Library · Audnexus · Google Books* linking to
  the providers card;
- the match-prefix check with *14 audiobook files → 1 book · 0 ebooks*;
- *Jellyfin makes one item per file; the folder is grouped into a book here. Ebooks in this library are
  ignored*;
- the advisor: online fetchers off (✓), and an **informational** note on *Save artwork into media folders* —
  harmless for audio, since Jellyfin has no metadata file for audiobooks to overwrite.

**FR-280-7 — Health and triage counts.** `/api/health` gains `books { books, parts, authors, missing_parts,
two_in_one, covers_missing, no_narrator }`. The Dashboard gains an **Audiobooks** tile (*1 book · 14 parts ·
5 h 24 min*) and attention entries: *Audiobooks with a missing part* (warn), *Audiobooks without a cover*
(warn), *Audiobooks with no narrator* (information, dimmed) and *Folder holds two books*. The dock gets one
entry per item.

**FR-280-8 — The Library's Audiobooks kind.**

- **Books** — a 1:1 grid: cover or wordmark + dot, title, author, *5 h 24 min · 14 parts*, *✓ finished by n*
  when any, and a `--warn` corner chip for *part 6 missing* / *two books?*. Listener rings are **not** shown here.
- **Authors** — circles, *N books*.
- **Series** — M6·3; present only when any book has one.

Facets: Cover · Narrator · Description · Parts (single / multi) · Needs you · Format · Language · Library. The
status line reads *1 book · 14 files · 5 h 24 min · 1 needs you · 1 without a cover*, plus the one-sentence
description (*the folder is the book … the files' own tags come first, then what you type, then suggestions*).
States: **one book — the household's, drawn with one cell and no apology**; many; empty.

## Acceptance

1. On the household server, the books library becomes **one** book with 14 parts, 5 h 24 min, a *part 6
   missing* flag and no cover.
2. A folder with two `Album` tags is flagged, never merged or split without the admin.
3. A phone that listens through a 2.4-minute part and stops resumes there. Jellyfin's own client, opening the
   same file, lands at its start — and nothing breaks.
4. Finishing the last part within its last 5 minutes marks the book finished for that listener only.

## Mockup

`design/app/audiobook-data.js`, `design/app/library.html?kind=books` (fence: one · many · empty), and the books
parts of `design/app/settings.html#ab-libcard` and `index.html`.

## Dev review (2026-09-28, against `main` `728f22ea`)

Buildable. Nine items; one correction (1) and one grouping detail (3).

1. **Heartbeats must not ride `/tv/playback/progress`.** `reportProgress(device, jellyfinId, …)`
   (`tv/PlaybackService.kt:607`) keys on the tracked session and relays to Jellyfin by item id — a `book_id`
   is a stranger there. **Correction:** FR-280-4 uses 281's `PUT /music/book/{id}/progress {part,
   position_ms}`; the server writes `audiobook_progress` and mirrors with `reportProgress` on the **part's**
   Jellyfin id plus `setPlayed` (`:756`) on the earlier parts. Jellyfin applies its own 5-minute rule; we
   never read it back ✓.
2. **Chapters:** `FfprobeRunner.chapters(filePath): List<ChapterMarker>` (`media/FfprobeRunner.kt:9-11`,
   `-show_chapters`) exists — call it only for single-file books, as written.
3. **Group by Jellyfin's parent, not by path parsing.** Every `AudioBook` item carries `ParentId` = the folder
   item (measured: a `Folder` with `ChildCount 14`) → group by `ParentId`; fall back to the path's parent only
   when the parent is a `CollectionFolder` (a one-file book directly under the library root).
4. **The missing part.** Measured `IndexNumber` 1–15 with 6 absent → FR-280-3's example is right. Since 152/160
   parse the filename too, the flag should say *numbered wrong* only when the filename disagrees with
   Jellyfin, and *not in the folder* when both agree there is a gap.
5. **Tables:** migration **59** (with 275's) or **60**: `audiobook`, `audiobook_part`, `author`,
   `audiobook_progress`, `audiobook_bookmark`.
6. **Jellyfin's semantics, confirmed in source:** `MinAudiobookResume` / `MaxAudiobookResume` are **minutes**
   (5 / 5 here), `AudioBook : Audio, IHasSeries` with `SupportsPositionTicksResume = true`; the resolver's
   multi-part path is commented out and PR #17362 is open and held — the book is ours to build ✓.
7. **Advisor:** `metadataOwnershipFindings` would flag `MetadataSavers` — the books library has `[]`
   (measured) → silent; `SaveLocalMetadata: true` → the informational note as written. Nothing fires wrongly.
8. **Health** additive ✓; `Book` items ignored ✓ (0 measured).
9. **Wire:** none (new paths only).

## Build notes (2026-09-28)

Built on `main` after R321/R322. Compiles (backend + admin); the full backend suite passes (723), including the new
`AudiobooksIngestTest` (grouping, the gap's two kinds, a re-read keeping what was typed and dragged, split and join,
*finished*). **Not deployed and not tried in a browser when written.**

**Owner, mid-build — "call it audiobooks":** *"We might [do] books at some later point, so would make sense to call it
audiobooks."* Everything this phase names is therefore *audiobooks*, not *books*: the package
`dev.jellystructure.audiobooks`, `Audiobook*` types, the step **`scan_audiobooks`** (FR-280-2's `scan_books`), the
health key **`audiobooks`** (FR-280-7's `books { … }`; the fields inside are as written), the triage ids
`audiobooks_*`, the tables `audiobook`, `audiobook_part`, **`audiobook_author`** (dev review 5's `author`),
`audiobook_progress`, `audiobook_bookmark` (migration **60**), the admin routes `/api/audiobooks/…`, the Library kind
`?kind=audiobooks` (the mockup's `?kind=books`) and the phone's paths (281's build notes). Only Jellyfin's own
collection type stays `books` — it is Jellyfin's name.

1. **Grouping (FR-280-1, dev review 3):** by Jellyfin's `ParentId`; a file straight under the library root is its own
   book (`f:<item id>`). Our `position` starts as `IndexNumber`, else the filename's number, then the path; a part
   already known keeps the position the admin dragged it to, and a new one goes after. A re-read replaces only a
   field the files still own (281's origins; the lock freezes everything). Rows Jellyfin no longer lists are kept and
   marked missing, the films' rule. `ffprobe -show_chapters` runs only for a one-file book, and only again when its
   length changed.
2. **The flags (FR-280-3, dev review 4):** a gap is *not in the folder* when the filename numbers agree with
   Jellyfin's, *numbered wrong* when they disagree; both dismiss per folder. **Split into two books…** is built: the
   folder's book keeps the parts tagged with its first `Album`, and each other tag becomes a book of its own
   (`<folder id>~<tag>`) — **the files are not moved**, so Jellyfin still shows one folder. The confirmation lists
   which parts go where (open question 2's preview, as a list, **without** dragging between columns). *Join back into
   one book* undoes it (and dismisses the flag). A split-off book's cover is `cover-<tag>.jpg` beside the folder's
   `cover.jpg`, which Jellyfin does not read.
3. **Position (FR-280-4, dev review 1):** ours, in `audiobook_progress`; finished when the last part is within its last
   five minutes, or marked; a heartbeat from an earlier part takes *finished* away. The Jellyfin mirror is 281's.
4. **Visibility (FR-280-5):** the viewer's library grant, as music (`musicVisible`). **Deviation:** the phone's search
   does not look in audiobooks this round.
5. **Library card (FR-280-6):** the metadata line linking to the providers card, *N audiobook files → M books · E
   ebooks (left alone)* from `/api/audiobooks/status`, *Open in Library*; the advisor gained the informational
   *Save artwork into media folders* note for an audiobook library (`SaveLocalMetadata` read, nullable).
6. **Health and triage (FR-280-7):** the `audiobooks` block (null without a mapped library; `ebooks` counted by the
   last scan); a Dashboard *Audiobooks* tile; four attention rows (*no narrator* dimmed, information); dock entries
   for a missing part (→ Parts), two books (→ the book) and no cover (→ Artwork). The triage cache keys on the
   audiobook store's version.
7. **The Audiobooks kind (FR-280-8):** Audiobooks · Authors · Series (Series only when any book has one), the eight
   facets counted server-side against every other facet, the status line, sort, and the not-mapped / not-scanned /
   empty states. The kind picker is now one shared control across films, Music and Audiobooks. Styles are
   `music.css`'s `mu-*`; the book page's `ab-*` rules moved there from the mockup's inline `<style>`.

8. **On the household server (2026-09-28, deployed with the dev compose, DB backed up first).** `scan_audiobooks`
   made the books library **one book of 14 parts, 5 h 27 min** (the acceptance says 5 h 24 — the sum of Jellyfin's
   own part lengths), with *part 6 not in the folder* and *no cover* (acceptance 1). The Library kind, the book page,
   the Parts tab's gap row and the Dashboard tile rendered against it. **One fix from that run:** Jellyfin 12.1 gives
   an `AudioBook` item no top-level `Container` (an `Audio` item has one), so every part's format was blank; the
   query now asks for `MediaSources` and a part falls back to its first source's container and bitrate. The health
   block now says its counts even when they are all zero (`encodeDefaults`).
   **Finding, not built:** the house's MP3 parts carry an embedded picture that Jellyfin does not surface as the
   book's image, so the book reads *no cover* while its files have one. Offering the embedded picture as a cover
   candidate on the Artwork tab is a follow-up.
