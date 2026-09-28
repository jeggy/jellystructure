# Phase 281 — A book's facts: the files first, the admin second, providers as suggestions

## Status

`✓ Built` 2026-09-28, **not deployed** (build notes at the end) — written 2026-09-28 from the audiobooks research (§2, §3.1, §4, §6), the admin brief §M2–§M4 and
§M6, and the mockups `design/app/audiobook.html` (+ `audiobook.js`), `author.html` and
`settings.html#sect-musicprov`. **Dev-reviewed 2026-09-28 against `main` `728f22ea`** (below). The research's "280". Builds on **280**, **276** (the
providers card, MusicBrainz's client) and **279** (the phone's paths).

## Decisions (§M6 — leans; not yet answered)

| # | Question | Lean |
|---|---|---|
| M6·1 | Tag writing into audiobook files | **Draw the switch, off by default** (FR-281-8). The alternative is to leave the files alone for good |
| M6·2 | How a suggestion is taken | **Per field** (*Apply* on each line, plus *Apply all*). The alternative is the whole card only |
| M6·3 / M6·4 | Series view · Listeners tab | as 280 |
| 5 | Assist providers | iTunes + Open Library + Audnexus without keys, and Google Books with the owner's key |

## Requirements

**FR-281-1 — The ladder is inverted.** For a film, TMDB fills everything and the admin corrects. For a book:

1. **The files' tags + the folder** — primary, not a fallback.
2. **What the admin types** — the Details tab is an **editor first**.
3. **Suggestions** from providers, which the admin accepts or ignores.

Nothing from a provider is written without an accept. AcoustID is skipped (spoken word is not in its database).
The **source chip** on the head says which shaped the book: *From the files* · *Edited here* · *iTunes* ·
*Audnexus* …, plus 🔒 when locked.

**FR-281-2 — The Details tab.** Title · subtitle · authors (chips) · narrators (chips) · series + position ·
year · publisher · language · genres · description.

- Each label carries its origin in small type: *from the files* / *typed here* / *from iTunes*.
- An empty field is dashed. The description placeholder says *None in the files and none from a provider. Type
  one if you have it*.
- It saves as you type (write-through). A line under the fields says where the save goes (FR-281-8).
- **Lock** keeps a re-read from changing what was typed.

**FR-281-3 — The Suggestions rail.** It sits beside the editor with *asked at 03:05* and *Ask again*. There is
one card per provider in order — **iTunes (DK store) · Google Books (da) · Open Library · Audnexus (uk · de)**.
MusicBrainz is asked too and gets a card only when it hits.

- **The first state drawn is four empty cards**, because that is the household's: each says in one sentence
  what the provider knows (*It knows Ingrid Lykke — 5 other audiobooks, none of them this one* · *it covers what
  Audible sells, and Audible has no Danish store*).
- A hit shows a thumb, title, author and year, then *What it would fill* — one line per field with *Apply* (M6·2)
  and *Apply all*.
- An applied line turns ✓ and the field's origin changes.

**FR-281-4 — Providers.** Rows added to the providers card (276 FR-276-8), which is renamed **Metadata
providers** and has a *Music* / *Audiobooks · suggestions only* grouping:

- **iTunes** — no key, a store picker (*DK · NO · SE · GB · US*; *there is no Faroese store*), covers at 600 px.
- **Google Books** — **API key** (*required in practice — without a key the shared quota is always used up, so
  the provider is skipped and this row says so*).
- **Open Library** — nothing to configure; 1 req/s, or 3/s with an identifying User-Agent.
- **Audnexus** — no key, a region picker, *covers the titles Audible sells*.

**FR-281-5 — The Parts tab.**

- Each row: drag handle · number · title tag · length · format · **Jellyfin position** · ▶.
- A **gap row** for a missing number (*part 6 · not in the folder*).
- **Drag reorders** our `position` (280 FR-280-1) and never renames a file (*Order kept here · the files are not
  renamed*).
- One **info line**, not a warning, when any part is under 5 minutes: *Jellyfin only saves a position after 5
  minutes of a file, so it never remembers one in the 4 parts shorter than that. Ravilo keeps the book's
  position itself.* The Jellyfin position column says *— under 5 minutes* on those rows.

**FR-281-6 — The Chapters tab.** The source is stated: **embedded** (an `.m4b`: *24 chapters*) or **file
boundaries** (*the files carry no chapters of their own, so each part is one*). A single-file book with
embedded chapters has a *Use embedded / Use file boundaries* toggle. Titles rename inline, and starts and
lengths are shown. The chapter title is the part's title tag, else *Chapter n*.

**FR-281-7 — Artwork, Listeners, History.**

- **Artwork** — *in use* (`cover.jpg`, lock), or *no cover.jpg · no embedded art*; candidates from iTunes ·
  Google Books · Open Library · the embedded art; upload (written as `cover.jpg` in the book folder, which
  Jellyfin reads).
- **Listeners** (M6·4) — **read-only**, one row per household member: ring · name · *3 h 12 min left · part 9 ·
  last night* / *finished in March* / *not started*. The admin never moves a listener's position.
- **History** — the group scan, provider asks, accepts and edits.

**There is no NFO tab** — Jellyfin has no metadata file for audiobooks.

**FR-281-8 — Write tags into audiobook files (M6·1).** A switch in the providers card, **off by default**:
*The only way Jellyfin's own apps show a narrator or a description.* When on, **Save** on a book writes title ·
album · artist=author · composer=narrator · comment=description · publisher · genre · track number into
**every part**, skipping files seeding in qBittorrent. Save's menu says what it will write (*cover.jpg + tags in
14 files* vs *cover.jpg*), and the head's line says the rest (*what you type here reaches Ravilo, and Jellyfin's
own apps keep showing the files' tags*). Ravilo never needs it.

**FR-281-9 — The Author page (`app/author.html`).** Circle image · name · sort name · *N books in the library*.

- **Biography** — editable; a suggestion when a provider has one (*Open Library knows 17 works by this author,
  no biography*).
- **Books** as 1:1 cells, grouped by series when M6·3 says so.
- **Artwork** — iTunes · Open Library · Commons candidates and upload.
- **History**.

**FR-281-10 — Phone paths** (extends 279):

- `GET /music/books` — this viewer's shelf: in-progress first by `updated_at`, then all, with sort `added` ·
  `title` · `author` · `series`, and authors/series when present.
- `GET /music/book/{id}` — the head, chapters, and this viewer's position, speed and bookmarks.
- `POST /music/play {book_id, position_ms}` — the part's ticket plus the part list, so the phone queues the rest.
- `PUT /music/book/{id}/progress|speed|finished` and `POST|DELETE /music/book/{id}/bookmarks`.

Direct play for MP3/M4B/FLAC; HLS only for what the phone cannot demux. **No provider name reaches the phone**
(the description is plain text).

## Acceptance

1. The household's book shows four empty suggestion cards with their one-line reasons, and the editor with
   *from the files* on title, author, year and genre.
2. Applying one field from a card changes only that field and its origin label. *Lock* keeps it across a re-read.
3. The Parts tab shows the gap row for part 6 and the 5-minute line. Dragging a part changes the phone's queue
   order, and no file is renamed.
4. With tag writing off, Save writes only `cover.jpg`. With it on, the parts' tags change and Jellyfin's web UI
   shows the narrator.
5. The Listeners tab reflects a phone's position within one heartbeat and has no control that changes it.

## Mockup

`design/app/audiobook.html?b=vinterfaergen` (the household's book), `?b=the-salt-road` (a single M4B with a
suggestion taken and embedded chapters), `?b=nordlys` (a gap, edited here), `?b=samlede` (two books in one
folder); `design/app/author.html` (three fence states); `design/app/settings.html?tab=connections#sect-musicprov`.
The §M6 questions are in the shared panel at the foot of each page.

## Open questions

1. Audnexus needs an ASIN, and the ASIN lookup is Audible's undocumented per-store endpoint (research §2.1). Is
   that acceptable, or does Audnexus only answer when the admin pastes an ASIN?
2. The *Split into two books…* flow is drawn as one confirmation. Does it need a preview of which parts go where?
   Lean: yes — two columns, drag between them.

## Dev review (2026-09-28, against `main` `728f22ea`)

Buildable, with one thing the image does not have (1). Nine items.

1. **There is no tag writer in the runtime image.** The Dockerfile installs ffmpeg, mkvtoolnix, wget and fpcalc
   — nothing that edits ID3/MP4 tags in place. FR-281-8's switch needs either **`ffmpeg -i in -c copy
   -metadata …`** (a re-mux: new bytes, new mtime, atomic temp + rename, `SeedingGuard.check` per file as
   written) or a dedicated tagger in the image (`mid3v2` from python3-mutagen, or `id3v2` + `AtomicParsley`).
   Lean: **a tagger** — a remux of an M4B risks its chapter atoms — plus the Dockerfile change and
   `scripts/check-docker-cache-ids.sh`. Owner decision M6·1 still gates whether the switch is drawn at all.
2. **Provider clients** (iTunes · Google Books · Open Library · Audnexus · MusicBrainz) go through
   `OutboundHttp` BACKGROUND with identifying User-Agents. Google Books **needs a key** (the keyless quota
   was exhausted on the probe) → `[api_keys].google_books_key`; the store and region pickers need a home:
   `[audiobooks] { itunes_store = "dk", audnexus_region = "uk" }`.
3. **Audnexus needs an ASIN** (open question 1). Audible's catalogue search is undocumented and has no
   Danish store. Lean: **only when the admin pastes an ASIN or MusicBrainz carries an Audible/ASIN URL
   relationship** — no Audible scraping in v1.
4. **One play shape with 279.** `POST /music/play {track_id?, book_id?, part?, capabilities}`; the part
   list rides `GET /music/book/{id}` (FR-281-10 puts it on the play response — either works; pick one).
5. **Progress** — see 280's review, item 1.
6. **Routing and CSS:** `/audiobook/{id}`, `/author/{id}` in `Main.kt`'s `when` (`:84-102`); the design's
   `music.css` carries the book pages too → one registration (278's review, item 1).
7. **Listeners tab** joins `audiobook_progress` with the users the Users & devices tab already lists
   (`server/routes/TvRoutes.kt` admin overview) — read-only ✓.
8. **Series** only when any book has one ✓; Jellyfin's `AudioBook` is `IHasSeries`, so `SeriesName` may arrive
   from tags (null on the household's book).
9. **Wire:** none.

## Build notes (2026-09-28)

Built with 280 (same commit). Compiles (backend + admin + the phone app); the full backend suite passes, including
`AudiobooksTvServiceTest` (the viewer's shelf, book time, the mirror, *finished*, speed and bookmarks per book), and
the R319 wire tests pass with the new shapes in `WIRE_ROOTS`. Named *audiobooks* throughout (280's build notes).

1. **The ladder (FR-281-1/2):** every field carries its origin — `files`, `typed`, or the provider's name — and the
   head's source chip is computed server-side from them. The Details tab saves on change (write-through). The lock
   freezes a re-read.
2. **Suggestions (FR-281-3, M6·2 → per field):** iTunes (store from the card), Google Books (only with a key — without
   one the card says so), Open Library, Audnexus **only for a pasted ASIN** (dev review 3; no Audible scraping), and
   MusicBrainz only when it hits. One card per provider with its one-sentence reason when it has nothing. *Apply* per
   line and *Apply all*; an applied line turns ✓ and stays marked across *Ask again* while the value is unchanged.
   Providers are paced (Open Library 1/s, iTunes 1/s burst 3).
3. **Providers card (FR-281-4):** the same card, now *Music · Audiobooks*, with an *Audiobooks · suggestions only*
   group saved on its own through `PUT /api/audiobooks/providers` (`[audiobooks] itunes_store / audnexus_region /
   write_tags`, `[api_keys] google_books_key`, masked and preserved by the general Settings save). **Deviation:** no
   *Test* buttons on the audiobook rows.
4. **Parts (FR-281-5):** drag reorders our `position` (never a rename; the phone plays that order); the gap row; the
   5-minute info line; the Jellyfin position column is **the signed-in admin's own** Jellyfin position; ▶ plays a
   browser-playable part through Jellyfin directly.
5. **Chapters (FR-281-6):** embedded or file boundaries, stated; the toggle only for a one-file book with embedded
   chapters; titles rename inline (`chapter_titles`, by index).
6. **Artwork, Listeners, History (FR-281-7):** cover candidates are the suggestion cards' covers; *Use*, upload (both
   written as `cover.jpg` and locked), clear, lock; embedded art shows through Jellyfin's image. Listeners is
   read-only, one row per viewer with a position, names from the Users & devices list. History records edits,
   asks, applies, locks, orders, flags, covers, splits and saves. No NFO tab.
7. **Tag writing (FR-281-8, dev review 1 → a tagger):** `python3-mutagen` is added to the runtime image (Dockerfile,
   `check-docker-cache-ids.sh` green). With the switch on, Save writes title · album · artist/album artist = author ·
   composer = narrator · comment = description · publisher · genre · track number (our order) into every part,
   **in place** (an M4B's chapter atoms survive), through the shared ProcessGate, skipping files seeding in
   qBittorrent (or when qBittorrent cannot be asked). Verified against a synthetic MP3 and M4B with ffprobe. Without
   a tagger in the image, the card says the switch has nothing to drive. Save's menu says what it will write.
8. **The Author page (FR-281-9)** at `#/audiobook-author/{id}`: sort name, *N books*, the biography (typed here),
   books grouped by series, History, and an on-demand Open Library sentence. **Deviation:** no author pictures and
   no Artwork tab — there is no folder Jellyfin reads an author image from, and the phone does not show one.
9. **Phone paths (FR-281-10, named *audiobooks*):** `GET /tv/music/audiobooks?sort=added|title|author|series`
   (Continue listening, then all; authors only when more than one, series when any), `GET /tv/music/audiobook/{id}`
   (the head, chapters, **the part list** — dev review 4's pick —, position, speed, bookmarks),
   `GET /tv/music/audiobook-author/{id}`, `PUT …/audiobook/{id}/progress|speed|finished`,
   `POST …/audiobook/{id}/bookmarks`, `DELETE …/bookmarks/{bookmark}`, and the cover at the open
   `/tv/image/audiobook/{id}`. **One play shape** (dev review 4): `POST /tv/music/play {audiobook_id, part,
   start_position_ms}` — `MusicPlayRequest.track_id` became optional (never released as required) — and the answer is
   the part's own `StreamTicket`; the part's stop is the films' stop, as a song's is. A heartbeat saves ours first,
   then mirrors the position to Jellyfin on the part's own session (`reportProgress`) and marks the parts left
   behind played; Jellyfin is never read back. Facts, not sentences: the phone words the times itself.


## Amendment (2026-09-28) — no *Save audiobook providers*

Build note 3's *saved on its own* is withdrawn: the *Audiobooks · suggestions only* rows have no Save button, and the
page's one Save sends them through `PUT /api/audiobooks/providers` when they changed. The rule and its order are 276's
amendment of the same date (FR-276-8). Build note 3's other deviation is withdrawn too: the **Google Books** row has
*Test* (one real volume search with the saved key), and its dot is green only when Google accepted the key — the same
honest-dot rule as 276's keyed rows.
