# Phase 278 — Music in the admin UI: the Music kind, the Album and Artist pages, and everywhere else music shows

## Status

`✓ Built` 2026-09-28, **not deployed** (build notes at the end). Written 2026-09-28 from `specs/design-brief-music-in-the-admin-2026-09-27.md` (§A–§G, the §H
questions) and the round-1 mockups. **Dev-reviewed 2026-09-28 against `main` `728f22ea`** (below). Builds on **275–277**, the Library workbench's facet
idiom, `media.html`'s pagebar/tabs, and the triage dock. The seven §H questions are drawn as one live panel at the
foot of every music page (`design/app/music-qs.js`, localStorage `js-music-q`). The leans below are the spec's
defaults until the owner picks.

## Decisions (round 1 — leans; not yet answered)

| # | Question | Lean |
|---|---|---|
| H1 | WMA | **A *Convert…* action** (FR-278-7) |
| H2 | Candidate fit | A sentence (276) |
| H3 | Music genres on the Metadata page | **Their own tab** beside Genres (the alternative is a segment inside Genres) |
| H4 | The Artist page's missing albums | **On disk only** (277 FR-277-10) |
| H5 | Providers card | Connections (276) |
| H6 | Lyrics | On by default (277) |
| H7 | Tag writing | Not drawn (277) |

## Requirements

### Library

**FR-278-1 — A Music kind.** The kind picker reads *Movies · TV · Music videos · **Music** · **Audiobooks***
(the last is 281). *Music* shows a segmented control **Albums · Artists · Songs**, a status line (*30 albums ·
27 matched · 3 need you · 60 songs · 23 artists · 4 without a cover* + **Match now** when anything needs
matching), and one sentence saying what this is: Jellyfin's music library, matched against MusicBrainz, written
to NFO files and never into the files — and *not* the Music videos library. `?kind=music&mview=` deep-links.

**FR-278-2 — The three views.**

- **Albums** — a **1:1** grid (not the 2:3 poster). Each cell: cover or wordmark (277 FR-277-3), title, album
  artist, *year · N songs*, and a corner chip for *unmatched* / *needs you* / 🔒 locked (matched shows nothing).
- **Artists** — circles, with *N albums · N songs* or *credited · N songs*.
- **Songs** — a dense table: # · title (+ *feat.*) · artist · album · length · **format** (*WMA · 128* in `--warn`
  with *re-encodes on a phone*) · lyrics · recording match.

**FR-278-3 — Facets and bulk actions.** The workbench's facets with music values: Match · Cover · Artist image
· **Format** (WMA carries *plays on a phone only by re-encoding*) · Genre · Decade · Album type · Lyrics ·
Library. Every value shows a live count, zero-count values are dimmed, and active conditions are removable chips
with a live count. Selecting albums (hover checkbox; a click selects once a selection exists) opens a sticky bar:
**Match now · Fetch covers · Write NFOs · Lock match · Clear match** and *select all shown*.

**FR-278-4 — States.** *Empty library* (a sentence + the card link), *mapped, not yet scanned* (the paths, the
counts Jellyfin reports, *Scan now*), **everything unmatched** (the household's first real state: every cell
*unmatched*, one cover, *Match now* showing progress *Matching… n of 30* and the one-request-a-second time
estimate), partly matched, and a search with no results.

### The Album page (`app/album.html`)

**FR-278-5 — Pagebar and head.** Title (year) · type badge when not *Album*. Then *External links* (Jellyfin,
MusicBrainz, Cover Art Archive, and the URL relationships' Discogs/Wikipedia), *Re-pull* (Jellyfin; MusicBrainz
disabled while locked — 276 FR-276-6), ⋯ (lock/unlock, clear, *Find match…* / *Change match…*) and the split
Save button. The head has:

- the cover (lightbox);
- **by {artist}** as a link, several as *A & B*;
- *year · 2 of 18 songs · 9 min* · type · format(s);
- the **match chip**: *MusicBrainz · release-group ▸ id* and *release: NO 2005 · label*, or *N candidates ·
  needs you*, or *No MusicBrainz match*, or 🔒 *Locked · won't be re-matched*;
- the primary action for the state (**Find match…** / **Choose…** / ghost *Change match…*);
- *Partial album — the release has 18 tracks, the library holds 2*.

**FR-278-6 — Tabs.**

- **Tracks** — every position of the release, with **gap rows** for runs not in the library (*3–17 · not in
  library · 15 tracks*). Columns: codec · kbps · kHz, recording match (276 FR-276-5), lyrics (277), gain, and a
  ▶ that plays in the browser for direct-play formats only. WMA says *no direct play* with the segment editor's
  "direct play or nothing" reason. The album gain line sits above.
- **Artwork** and **Genres & tags** as 277 and 276, with Jellystructure tags underneath as for films.
- **NFO (raw)** — the path and the file as written.
- **History**.

Banners: *Unmatched*, *Needs you* (why), 277's drift, and 136's *Locked in Jellyfin*.

**FR-278-7 — Convert… (H1).** On an album or song with non-direct-play files, and on the Dashboard entry, a
confirmation: *Convert N songs so a phone can play them directly?* It says honestly that the files **are already
lossy, so a little more is lost** and that this cannot be undone from the new files. The originals move to a
holding folder, not deleted. Files seeding in qBittorrent are skipped. *Keep as they are* / *Convert N*. It is a
one-time repair job (WMA → AAC 192 kbps; the research's Opus is the alternative — dev pick), shown in Jobs &
workers. With H1 = information only, the *re-encodes on a phone* note stays and no action is offered.

### The Artist page (`app/artist.html`)

**FR-278-8 — Pagebar, head, tabs.** Circle image, name, **sort name** (mono), *Group · NO · 1999–* and the
disambiguation, the match chip, *N albums · N songs* and *credited on …*, and the no-picture line with a link to
Artwork. The **Overview** tab has the biography (277 FR-277-6) and albums grouped *Albums · Singles & EPs ·
Compilations · Live* (a Live album is an album, not a video), plus the Videos group (277 FR-277-9). The other
tabs are **Artwork · Genres · NFO · History**. An artist credited only on a compilation track is *unmatched*
with *Credited on one compilation track only — nothing to match against yet*.

### Everywhere else

**FR-278-9 — Settings.** The **Musik** library card (275 FR-275-3, -5): paths, the *Metadata: MusicBrainz ·
Covers: Cover Art Archive · Artist images: fanart.tv · Lyrics: LRCLIB* line linking to the providers card, the
match-prefix check with counts, *Open in Library*, *Scan*, and *Not the Music videos library* (one line). Plus
the providers card (276 FR-276-8).

**FR-278-10 — Activity.**

- The steps strip gains `scan_music · match_musicbrainz · fetch_music_artwork · write_music_nfo ·
  fetch_lyrics` (the last only when on), marked ♪, and the step count follows.
- The pacing card gains the MusicBrainz and AcoustID rows (276 FR-276-2).
- The run summary gains *Music — 30 albums · 27 matched · 3 need you · 26 covers fetched · 4 without a cover ·
  9 songs with lyrics*.
- Jobs & workers: if 213's lanes gain a **music** lane (dev decision), it shows its kinds (*match_album ·
  fetch_cover · fingerprint · convert_audio*) and *slow by design — never holds up a film's re-order*. Without a
  music lane, music rides the media lane. Both are drawn.

**FR-278-11 — Dashboard.** A **Music** stat tile (*30 albums · 27 matched · 26 covers*). The attention list
gains *Albums need a match* (with/without candidates), *Albums without a cover*, *Artists without a picture*
and — with H1's Convert — *Songs a phone plays only by re-encoding · Convert…*. The recent-activity feed shows a
*needs you* line.

**FR-278-12 — Triage dock.** Entries per item (brief §G): album *needs you* / *no match* → the album with Find
match… open; *no cover* → its Artwork tab; artist *no picture* → the artist's Artwork tab.

**FR-278-13 — Metadata → Music genres (H3).** A wall of MusicBrainz genres with *N albums · N songs* each,
linking to the Music kind filtered by genre. There are no logos and no merging with TMDB's genres (a film's
*Comedy* is not an album's *comedy rock*).

## Acceptance

1. `library.html?kind=music` on the household library, before the first match: 30 *unmatched* cells, one cover,
   *Match now*. After it: the counts in FR-278-1.
2. Every fence state of `album.html` renders its banner and primary action. The Tracks tab of a partial album
   shows its gaps.
3. The Songs view's Format facet shows 38 WMA with the re-encode note.
4. Settings, Activity, Dashboard, Metadata and the dock show the entries above and link to the right tab.
5. Every H question's non-lean answer is also drawn, so the owner's pick needs no new design.

## Mockup

`design/app/library.html?kind=music` (+ `music-library.js`), `design/app/album.html` (+ `album.js`),
`design/app/artist.html` (+ `artist.js`), `design/app/music.css`, `design/app/music-qs.js`, and the music parts
of `settings.html`, `activity.html`, `index.html`, `metadata.html`, `ravilo-users.html`, `app-shell.js` (dock).
Each detail page's fence links one item per state.

## Dev review (2026-09-28, against `main` `728f22ea`)

Buildable. Nine items; item 1 is the one that ships a blank page if missed.

1. **A new stylesheet must be registered in two places.** `design/app/music.css` reaches the served admin only
   through `syncDesignAssets`' include lists (`build.gradle.kts:269` and `:302`) **and** the `<link>` set in
   `src/wasmJsMain/resources/index.html` (`:10-16`) — the `segments.css` lesson. The `music-*.js` mockup
   scripts are design-only; the admin is Kotlin DOM (`ui/Library.kt`, new `ui/Album.kt`, `ui/Artist.kt`).
2. **Router and kind picker.** `Main.kt:84-102` is a `when` on the path prefix → add `/album/{id}`,
   `/artist/{id}` (281 adds `/audiobook/`, `/author/`). `Library.kt` keeps `libKind: MediaKind?` (`:60`)
   parsed with `MediaKind.valueOf` (`:126`) and three kind spans (`:197-199`) — Music is **not** a
   `MediaKind`, so the picker needs a second selector (`libView = "music" | "books"`) beside `libKind`, and
   `?kind=music` deep-links to it.
3. **Triage:** `TriageDetection` is a set of `MediaItem` functions and `TriageRoutes` a fixed list of type
   strings (`untagged`, `missing_artwork`, `cascade_mismatch`, `mkv_track_layout`, `file_damage`, …) → the
   music types are new strings with a music detection source; the dock (`app-shell.js`) renders whatever
   `/api/triage` returns.
4. **Dashboard:** `/api/stats` → `StatsResponse` (`api/MediaApi.kt:521`) gains `music` fields; attention
   entries ride the same route family.
5. **Metadata tab:** `TAB_LABELS` (`ui/Metadata.kt:22`) gains `"music"` (H3's lean) — one list, one render
   branch.
6. **Convert… (FR-278-7):** a `media_job` with `MediaJobParams` extended in its nullable-per-field shape
   (`jobs/MediaJobParams.kt:11`, e.g. `albumId`, `trackIds`); **`SeedingGuard.check(localFilePath, config)`**
   (`torrent/SeedingGuard.kt:12-13`) is the Phase-26 guard to call per file; ffmpeg `-c:a aac -b:a 192k` (or
   libopus) under `ProcessGate` BACKGROUND; originals to a holding folder (254's `.js-quarantine` precedent).
   **Awaits H1.**
7. **Lane (FR-278-10):** see 275's review, item 7 — lean own lane; both drawn ✓.
8. **Settings card (FR-278-9):** `ui/Settings.kt:1560` (`typeLabel`) and `:2336-2337` (`isMovie`/`isTv`) drive
   per-card behaviour → an `isMusic` branch for the no-fallback-language rule and the provider line.
9. **Acceptance 5** ("every non-lean answer is drawn") is a design acceptance; keep it, label it so.

## Build notes (2026-09-28)

Built on `main` after 277. Compiles (backend + admin); the music/config tests pass, including a new
`MusicBrowseTest` for the facet arithmetic. **Not deployed and not tried in a browser** — the pages are written against
the mockups' markup and `design/app/music.css`, so the first real look is on the next deploy.

1. **Every row and every count comes from the server** (the constitution's rule, and why this is not the mockup's
   client-side filtering): `GET /api/music/browse?view=&q=&sort=&f.<key>=a,b` returns the view's rows and each facet's
   values, each counted against every *other* active facet (the workbench's rule — ticking WMA never zeroes MP3).
   A song takes its album's facet values but keeps its own format and lyrics; an artist takes the union of its
   albums' (a credit-only artist is found through the album it is credited on). `music/MusicBrowse.kt` is pure and
   tested. Deep links work: `?kind=music&mview=songs&f.format=WMA`.
2. **The Music kind is its own view, not a fifth `MediaKind` value** (dev review 2): `renderLibrary` hands
   `?kind=music` to `renderMusicLibrary`; the film Library's picker gained *Music*. States: not mapped, mapped but not
   scanned (the paths + *Scan now*, which opens the normal pre-run dialog), mapped and empty, and the live view.
   *Match now* shows *Matching… n of N* from `/music/status` and reloads when the pass ends.
3. **Album page** (`#/album/{id}`, `ui/MusicAlbum.kt`) and **Artist page** (`#/artist/{id}`, `ui/MusicArtist.kt`)
   as FR-278-5/6/8, reading one page DTO each (`/album/{id}/page`, `/artist/{id}/page`). *Find match…* is the side
   panel: stored candidates first, else a search from the folder's artist and title (a pasted MusicBrainz URL takes
   exactly that), a candidate's pressings loaded on selection with the best-agreeing first, *Use* / *Use and lock*,
   and *Identify by sound* when an AcoustID key is set. The Tracks tab draws gap rows from the chosen release's track
   count (single-disc albums; multi-disc albums list per disc), *Match this track…*, lyrics with *Fetch*, gain, and
   ▶ for browser-playable files only (Jellyfin's `Audio/{id}/stream?Static=true` in the admin's own session — direct
   play or nothing). **Deviations:** no *Jellystructure tags* section on an album (albums carry no JS tags yet); an
   artist has no *Find match…* (artists are matched through their albums' credits, 276) — its ⋯ says so; the artist's
   Genres tab is read-only.
4. **History is recorded now:** matches (how, and how many tracks agree), *needs you*, lock/unlock, clear, a genre
   override and a hand-picked recording land in the album's History (the films' table, keyed by the album id), so
   the Dashboard's recent-activity feed shows them too.
5. **136's banner:** the scan now asks Jellyfin for an album's `LockData`/`LockedFields` (`MusicAlbum.jellyfinLocked`).
   **An album without a cover file but with art in Jellyfin** (embedded) shows Jellyfin's image through the same
   `/api/music/image/album/{id}` route.
6. **Convert… (FR-278-7, H1 → the lean):** `convert_audio` jobs on the **media lane** (no music lane — FR-278-10's
   drawn alternative), one file at a time: phase 26's seeding guard per file (seeding → skipped; qBittorrent
   unreachable → skipped, to be safe), ffmpeg to AAC 192 kbps `.m4a` with the tags carried and the embedded picture
   dropped, the original **moved** to 254's `.js-quarantine` beside the library (put back if the new file cannot be
   placed), then a recursive Jellyfin refresh of each album. Offered on the album head, the Library status line
   (*Convert… (n)*, library-wide) and — through the Dashboard's *Songs a phone plays only by re-encoding* entry — the
   Songs view filtered to WMA. The confirmation asks the server first and says the numbers. A converted song is a new
   Jellyfin item: its recording ids return on the next match refresh; its `.lrc` keeps working (same base name).
7. **Settings (FR-278-9):** the music library card's provider line links to the providers card, and *Open in
   Library* replaces *Push all to Jellyfin* there. **Activity (FR-278-10):** ♪ step labels for all five steps, the
   MusicBrainz and AcoustID rows in *Outbound pacing* (fixed rate, *slow by design*), and the convert job's label;
   the per-step *done* lines are the run summary. **Dashboard (FR-278-11):** a *♪ Music* tile (from `/music/status`,
   not `/api/stats` — deviation from dev review 4, same data, no new field on a film DTO) and four attention rows
   linking to the Music kind filtered.
8. **Triage (FR-278-12):** four count types (`music_needs_match`, `music_no_cover`, `music_no_picture`,
   `music_reencodes`) only while a music library is mapped; dock entries for albums (needs you / no match → the album
   with *Find match…* open; no cover → Artwork) and artists (no picture → Artwork) via a new `TriageItem.musicIssue`.
   The count cache is keyed on the music store's version too.
9. **Metadata (FR-278-13, H3 → its own tab):** *Music genres*, each with albums · songs, linking to the Music kind
   filtered by that genre. **`music.css` is registered** in both `syncDesignAssets` include lists and `index.html`
   (dev review 1).
10. **Acceptance 5** (every non-lean answer drawn) is a design acceptance and was met by the mockups; the build ships
    the leans only.

