# Phase R388 — *Play all* and *Shuffle all* on every music list

> Owner, 2026-10-15 (DEB-1293): *"In Ravilo Music mode, I would like a "play all" and "shuffle all" always available.
> If I'm doing a search or just on the dashboard with nothing selected or on a specific genre page etc. And these
> buttons should just take everything that fits into the current selection of things and start playing that with
> it's queue system."*

## Scope

Written 2026-10-15 (PO-authored from DEB-1293, the owner's four answers the same day). Not dev-reviewed. Builds on
R321/R326/R339 (the listening mode and Browse), R322 (the queue and *Playing from*), R337 (the desktop's sidebar), R373
(one copy of every song, the official album) and R359 (a long queue to a cast). Touches one new backend route in
`MusicTvRoutes` / `MusicTvService`, one additive DTO in `shared`, and the music list screens of `ravilo-ui` on the
phone and the desktop. **A design round places the buttons** (DEB-1294, lane B); this spec fixes the behaviour, and
the adopted direction is recorded here as a dated amendment before the client piece is built.

Not in this phase: the TV (it has no music browsing — R380 FR-R380-9), the web app (no music mode), audiobooks, and
the owner's 2026-10-01 *"shuffle all [an artist's] songs, excluding Remixes and Live"* filter, which needs phase 292
(song versions). FR-R388-2's route takes such a filter later as an extra parameter.

## What is there today

- **Play / Shuffle exist only on one title** — an album (R373's *Play album ▾* split), an artist (*Play all* /
  *Shuffle* on its ≤ 200 top songs) and a playlist. Listen, the Artists / Albums / Songs chips, a genre and a search
  have none.
- **A tap on a song in a list queues only what is loaded:** the Songs chip plays the pages already scrolled (60 a
  page), a search its ≤ 50 songs.
- **No route returns the songs of a selection.** `/tv/music/{albums,artists,tracks}` page at 60 and only the albums
  branch reads `genre`; `/tv/music/search` caps each group at 50 (with true totals). R373's review: *"there is no
  Shuffle all route"*.
- **The queue takes whole `MusicTrackItem`s** through `MusicPlayback.playQueue(tracks, startIndex, context, shuffle)`;
  shuffle is client-side in `MusicQueue` (the original order kept, so turning shuffle off restores it). Casting sends
  a queue of any length in parts (R359), and a session reports ids only (R368).
- **A "genre page"** is the Albums chip filtered to one genre, with *‹ Genres* and the genre's name as its heading.

## Decisions

| # | Question | Answer |
|---|---|---|
| D1 | What a **search** plays (owner, 2026-10-15) | **What the search returned, each result expanded:** a song → that song, an album → all its songs, an artist → all their songs. A search that returns all three kinds plays all of them. |
| D2 | The **order** of *Play all* where the list is not songs (owner, 2026-10-15) | **The order on screen** — the list's current sort, album by album |
| D3 | A **cap** on a large selection (owner, 2026-10-15) | **1 000 songs** |
| D4 | The design round (owner, 2026-10-15) | **Lane B** — Rannvá writes the brief, the owner pastes it (DEB-1294) |
| D5 | Where the pair is drawn | Phone and desktop music lists: Listen, Artists, Albums, Songs, a genre, a search. **Not** on the Playlists chip (each playlist has its own), the Audiobooks chip, Playing, Queue or Profile. The album, artist and playlist pages keep their buttons unchanged (PO call) |
| D6 | A search's results beyond the 50 shown | **All of them**, by the same match rule — the 50 is a page size, not the result (PO call) |

## Requirements

**FR-R388-1 — The pair.** Every surface in D5 shows **Play all** and **Shuffle all** whenever its selection holds at
least one song. An empty selection (a search with no results, a genre whose albums have no visible song) shows
neither — absent, not greyed. Where they sit and how they look is the design round's (DEB-1294); they are a sibling
of the album page's *Play* / *Shuffle*, not a third style. Each is one press: no sheet, no confirmation.

**FR-R388-2 — The server resolves the selection.** A new route

`GET /api/tv/music/selection?scope=library|artists|albums|tracks|genre|search&sort=&genre=&q=&shuffle=`

answers `MusicSelection { tracks: [MusicTrackItem], total: Int, capped: Boolean }`. It applies the viewer's
`View` (libraries they may see, `missingSince`) like every other music route, and **folds the result to one copy
of every song** (R373 FR-R373-3: `View.fold`, the order of each song's first appearance kept). `total` is the
folded size of the whole selection before the cap. `sort` takes the browse route's values (`title`, `year`,
`played`, absent = the default). An unknown `scope` is a 400.

**FR-R388-3 — What each scope holds, in order** (D2 — the order the list draws):

- **`library`** (Listen, nothing selected): every album in **Recently added** order (Listen's first row), album by
  album; then the songs on no album, newest first.
- **`albums`** (the Albums chip): the albums in the chip's `sort`, album by album.
- **`genre`** (a genre): as `albums`, over the albums whose `effectiveGenres()` hold `genre`.
- **`artists`** (the Artists chip): the artists in the chip's `sort`, artist by artist. One artist is their own
  albums oldest first (`originalYear`), album by album, then their songs on other artists' albums (`songsBy`) in
  the same album-year order.
- **`tracks`** (the Songs chip): the songs in the chip's `sort`, exactly the browse route's order.
- **`search`** (`q`): the search route's own match rule and order, without its 50 cap (D6), in the order the results
  are drawn — **Songs, then Albums, then Artists**: every matched song; then each matched album, album by album; then
  each matched artist as in `artists`.

**An album, wherever it is expanded,** contributes what its own *Play album* plays: its official tracklist when R373
matched one (`MusicOfficial.layout(...).official`), else its files in disc/track order (`tracksByAlbum`). Extras and
B-sides are not included.

**FR-R388-4 — The cap (D3).** At most **1 000** songs reach the queue.

- **Play all** takes the first 1 000 of FR-R388-3's order.
- **Shuffle all** (`shuffle=true`) draws 1 000 **at random from the whole selection**, returned in FR-R388-3's
  relative order. A selection of ≤ 1 000 is returned whole, `shuffle` ignored.
- `capped` is true only when `total > 1 000`.

The cap is one constant (`MUSIC_SELECTION_CAP = 1000`) in `shared`.

**FR-R388-5 — Playing it.**

- *Play all* calls `playQueue(tracks, 0, context, shuffle = false)`.
- *Shuffle all* calls `playQueue(tracks, random index, context, shuffle = true)`. The first song is a random one,
  and turning shuffle off in the player restores FR-R388-3's order (`MusicQueue`'s `unshuffled`).

Both **replace** the queue (R322 FR-R322-8) and play on whatever the viewer is playing on — this device, or the cast
or session it holds (`MusicPlayback` already routes it). Track gain applies, never album gain (R322 FR-R322-9: this
is not one album in order).

**FR-R388-6 — The capped toast.** When `capped`, a toast reads *Playing 1 000 of {total} songs*
(`music.selection_capped`, `{shown}` and `{total}` with the locale's grouping). Nothing is said otherwise.

**FR-R388-7 — *Playing from*.** `MusicContext` gets:

| Scope | `kind` | Label |
|---|---|---|
| `library`, `artists`, `albums`, `tracks` | `library` (new) | *Your library* (`music.from_library`) |
| `genre` | `genre` (exists, unused) | the genre's name |
| `search` | `search` (exists) | *Search "{q}"* (`music.from_search`) |

The label is built on the client and stored with the queue, so an older app restoring the queue is unaffected.

**FR-R388-8 — While it loads.** A press shows the button's busy state until the route answers (on a 385-song library
it is one request, no paging). A failure toasts R237's generic *Couldn't play this* and leaves the current queue as
it was. A second press while the first is in flight is ignored.

**FR-R388-9 — The desktop.** The same pair on the same four surfaces, where the sidebar replaces the chips (R337):
*Listen*, *Library ▸ Artists / Albums / Songs*, a genre opened from *Genres*, and the sidebar search's results.
Tab reaches both buttons and Enter starts them; nothing else on the page changes its focus order.

## Wire

Additive only.

- **The route:** `GET /tv/music/selection`.
- **The DTO:** `MusicSelection` in `shared/.../tv/Music.kt`.
- **The constant:** `MUSIC_SELECTION_CAP`.

Installed apps never call the route, so they simply have no pair. No existing field changes.

## Strings

| Key | en | da | fo |
|---|---|---|---|
| `music.play_all` | Play all | Afspil alle | Spæl øll *(exists, reused)* |
| `music.shuffle_all` | Shuffle all | Bland alle | Blanda øll |
| `music.selection_capped` | Playing {shown} of {total} songs | Afspiller {shown} af {total} sange | Spælir {shown} av {total} sangum |
| `music.from_library` | Your library | Dit bibliotek | Títt savn |
| `music.from_search` | Search "{q}" | Søgning "{q}" | Leiting "{q}" |

The da and fo entries are drafts, and R288's lexicon wins. No string names Jellyfin.

## Acceptance

1. Listen → *Play all* plays the newest album first, in its track order, then the next album. Nothing else changes
   on Listen.
2. Browse ▸ Songs sorted A–Z → *Play all* → the Queue tab lists **every** song in the library, A–Z, not just the
   loaded pages. *N songs* equals the Songs chip's count.
3. A genre → *Shuffle all* → *Playing from* names the genre; every queued song is on an album of that genre.
   Turning shuffle off puts them album by album in the list's order.
4. A search that returns an artist, an album and songs → *Play all* queues the matched songs, then that album's
   songs, then all of that artist's songs — each song once.
5. A selection over 1 000 songs (the e2e fixture or a test library) → the queue holds exactly 1 000 and the toast
   names both numbers. *Shuffle all* twice gives two different sets.
6. A search with no results shows neither button.
7. While casting to a speaker, *Shuffle all* on Albums plays on the speaker, and the speaker's queue lists the same
   songs (R359's parts).
8. The Mac: Tab reaches both buttons on Songs, and Enter starts *Play all*.
9. Unit (backend): each scope's order on a fixture library, the fold, the album's official tracklist, the cap and the
   sample (seeded), the viewer's library filter, and a 400 on an unknown scope.

## Out of scope

The TV and the web (no music browsing there), audiobooks, a version filter (292), a *Mix* or radio (R326 FR-R326-6),
and changing what a tap on one song in a list queues.
