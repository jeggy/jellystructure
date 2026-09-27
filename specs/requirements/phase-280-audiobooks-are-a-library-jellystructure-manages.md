# Phase 280 — Audiobooks are a library jellystructure manages: the book, its parts, and where each listener is

> Owner, 2026-09-27: part 2 of the music round — audiobooks. *"The household's real state: exactly one book, 14
> parts, no cover. Draw the shelf and the grid with one item and no apology."*

## Status

`Planned` — written 2026-09-28 from `specs/research-reports/audiobooks-library-and-player-2026-09-27.md` (§0,
§1, §3, §4), the admin brief §M1 and §M4–§M5, and the mockups (`design/app/audiobook-data.js`,
`library.html?kind=books`, `settings.html#ab-libcard`). **Not dev-reviewed.** The research's "279". Builds on
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
