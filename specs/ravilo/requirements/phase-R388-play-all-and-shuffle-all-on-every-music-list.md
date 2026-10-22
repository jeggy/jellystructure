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

## Amendment (2026-10-22): the adopted direction — one split control, *▶ Play all | ▾*

The design round (DEB-1294, round 7) came home in `b1743ce5` under `design/incoming/2026-10-22-music-play-all/`. The
owner drew it at the keyboard over three revisions and **adopted v3** (2026-10-17): he asked for the two buttons to
be **merged into one control**, and for that control **never to get a row of its own**. This amendment is what the
client build (DEB-1300) implements. Where it differs from the text above, it wins. The behaviour does not change:
FR-R388-2…7 and the route (DEB-1299, `578ebfac`) stand as written.

**References** (read-only, never copied into production): `REPORT v3.md`, `music-play-all-v3.js`, the v3 block at the
end of `music-play-all.css` (`.mpa-sp`, `.mpa-spm`, `.mpa-bkrow`), and the 61 `png/music-play-all-split-*.png`
frames. The v1 (`a`/`b`/`c`/`d1`/`d2`) and v2 (`quiet`) frames are rejected directions.

**FR-R388-1, amended — the pair is one split control.** *▶ Play all | ▾*. A tap on *Play all* plays (FR-R388-5).
The ▾ opens a one-item menu, *⤮ Shuffle all*, anchored under the control's right edge. Tapping that item shuffles
(FR-R388-5). A tap outside closes the menu. **Shuffle is two taps.** That is the cost the owner chose when he merged
the buttons, and it replaces *"each is one press"*.

- **Shape:** the album page's own split, *Play album ▾* (`SplitPlayButton`, `MusicDetailScreens.kt`), so the two
  read as siblings.
- **Fill:** the sort pill's quiet fill, not the album page's primary fill: `rgba(fg, .09)`, a 1 px `line` border,
  and a 1 px `line-2` divider between the halves.
- **Heights:** 38 dp in a top bar (the sort pill's and cast's height), 28 dp on a text line. The tap target is
  44 dp either way.
- **The ▾'s accessible name** is the album page's own, `ed.more_play` (*More ways to play*). No new string.
- **Present or absent:** the control is shown while the list loads (the frame *"the split is there; the count is
  not yet"*). It is absent once the surface resolves to nothing: an empty search, a genre with no songs, or an
  empty library. It is present for a selection of one song, with its menu.
- **An empty answer:** if a press answers with no songs, that is FR-R388-8's failure.

**Where it sits — it always shares a row that is already there.**

| Surface | Row it shares | Frame |
|---|---|---|
| **Listen** | the **top bar's page slot**, between the brand and cast | `split-phone-listen`, `-crowded-listen` |
| **A search** (a query on Browse) | the **top bar's page slot** (a search has no sort pill and no count line) | `split-phone-search`, `-crowded-search` |
| **Artists · Albums · Songs** | the **count line**, right-aligned (*30 albums ··· ▶ Play all ▾*); the row grows to 28 dp; the count text is unchanged | `split-phone-albums`, `-artists`, `-songs` |
| **A genre** | the **‹ Genres line**, right-aligned; the line keeps its 44 dp height | `split-phone-genre`, `-crowded-genre` |
| **Desktop, wide** | the **page head**, right of the title (`DeskPageTitle`'s trailing slot, after the sort pill where there is one); on a search, the same slot of its *Search* head | `split-desktop-wide-*` |
| **Desktop, compact (< 600 dp) and phone landscape** | as the phone | `split-desktop-compact-*`, `split-phone-land-*` |

**The top-bar slot.** On Listen and a search the control fills the phone's top-bar page slot. The round flagged this
as bending FR-R267-5c. It does not: FR-R267-5c governs the Library page's type filter and the video pages. FR-R267-2
defines the slot as *"the page's own control if it has one"*, and music mode already puts the sort pill there on the
chips (R321 FR-R321-6). *Play all ▾* is Listen's and the search's own control, and the owner placed it there himself.
**R267 is not amended.** On the chips, the slot keeps the sort pill. The two do not fit side by side at 360 dp, which
is why the control moves to the count line there.

**FR-R388-5, unchanged; the call per surface.** Every press calls `TvApiClient.musicSelection(scope, sort, genre,
query, shuffle)` and then `MusicPlayback.playQueue`:

| Surface | `scope` | `sort` | `genre` | `query` |
|---|---|---|---|---|
| Listen | `library` | — | — | — |
| Albums chip | `albums` | the chip's sort (`MusicBrowseStore.sorts["albums"]`) | — | — |
| Artists chip | `artists` | `sorts["artists"]` | — | — |
| Songs chip | `tracks` | `sorts["songs"]` | — | — |
| A genre | `genre` | — (the genre view has no sort pill) | the genre's name | — |
| A search | `search` | — | — | the query as typed |

The chip's sort key goes on the wire as the browse route takes it (`added` is the route's default).

- *Play all* sends `shuffle = false` and calls `playQueue(tracks, 0, context, shuffle = false)`.
- *Shuffle all* sends `shuffle = true` and calls `playQueue(tracks, tracks.indices.random(), context, shuffle = true)`.

**FR-R388-8, amended — the busy state.** The round did not draw it, so here it is:

- From the press until the route answers, the half that was pressed swaps its glyph for a small progress ring at the
  glyph's size and in the label's ink. The label and the control's width do not change. (For *Shuffle all*, that
  half is *Play all*'s, because the menu closes on the tap.)
- The whole control ignores presses while the request is in flight.
- A failure, an empty answer, or no connection toasts `music.fail_t` (*Couldn't play this*) through `MusicToasts`.
  The control does not change and the queue is left as it was (frame `split-phone-error`; offline is the same).

**FR-R388-6, unchanged.** The capped toast goes through `MusicToasts`, only when `capped` (frames `split-phone-capped-{en,da,fo}`,
`split-desktop-wide-capped`).

**FR-R388-7, unchanged.** The `MusicContext` kinds and labels are as in the table above (frames
`split-phone-from-library{,-da,-fo}`, `-from-genre`, `-from-search`). Add `library` to the kind list in
`MusicEngine.kt`. The desktop draws no *Playing from* line, but the label is still stored with the queue.

**FR-R388-9, amended — the desktop keyboard** (frames `split-desktop-wide-focus-play`, `-focus-menu`):

- Tab order is the sidebar, then *Play all* ①, then ▾ ②, then the page's content. Nothing else on the page changes
  its focus order.
- Enter or Space on ① plays.
- Enter or Space on ② opens the menu with *Shuffle all* focused; ↓ and Enter pick it, and Esc closes the menu.
- The focus ring is the app's own: 2 dp `ring` with a 2 dp `bg` gap.

**Strings, final.** Four new keys. `music.play_all`, `music.fail_t` and `ed.more_play` are reused. R288's lexicon
wins, which settles the round's Faroese finding: the app already says *løg* (`music.songs_n` = *{n} løg*), so the
capped toast reads ***løgum*** (dative after *av*), not the draft's *sangum*. *Search "{q}"* takes the curly quotes of
`music.no_results`.

| Key | en | da | fo |
|---|---|---|---|
| `music.shuffle_all` | Shuffle all | Bland alle | Blanda øll |
| `music.selection_capped` | Playing {shown} of {total} songs | Afspiller {shown} af {total} sange | Spælir {shown} av {total} løgum |
| `music.from_library` | Your library | Dit bibliotek | Títt savn |
| `music.from_search` | Search “{q}” | Søgning “{q}” | Leiting “{q}” |

**The round's other findings, and why none of them is a change here:**

- *"The desktop has no sort pill"* is the mockup's gap, not the app's. The desktop page head already carries the
  sort (`MusicBrowseScreen`, R337), so the desktop sends the same `sort` as the phone.
- *"The desktop search groups are built in another order"* is also the mockup's. The app draws *Songs · Albums ·
  Artists* on both, which is FR-R388-3's order.
- *"The genre menu covers the heading while open"* is accepted. It lasts only while the menu is open.
- *"At 360 dp the Browse top row already clips cast in Faroese"* predates this phase and is not made worse: this
  control sits on the count line there, not in the top bar.
- *Continue listening* not being drawn has no consequence, because Listen's rows are unchanged.

**Acceptance, amended:**

- **3.** A genre → ▾ → *Shuffle all* → *Playing from* names the genre (the rest stands).
- **5.** *"Shuffle all twice"* means ▾ → *Shuffle all* twice.
- **8.** On the Mac's Songs, Tab reaches *Play all* and then ▾. Enter on *Play all* starts it. Enter on ▾ opens the
  menu, and Enter on *Shuffle all* starts a shuffle.
- **10 (new).** At 360 dp with the mini player docked, the control sits on the count line on Albums, in the top bar
  on Listen and a search, and on the *‹ Genres* line on a genre. It never sits on a row of its own, in en, da and fo.
- **11 (new, unit).** The scope, sort, genre and query each surface sends. The context kind and label. The toast
  only when `capped`. A second press while in flight sends no second request.

## Amendment (2026-10-22, build): the count line is drawn, because the app had none (DEB-1300)

The 2026-10-22 amendment above places the control on *"the count line, right-aligned (*30 albums ··· ▶ Play all ▾*) …
the count text is unchanged"*, and the round called counts *"existing and unchanged"*. They are not: the app's Artists /
Albums / Songs lists have **never drawn a count line** (the round's mockup did). The owner adopted frames that show it, so
the build draws it, as the row the control shares:

- **The line:** `{total} albums` / `{total} artists` / `{total} songs` (the chip's own `total`, the browse route's), in
  the `textDim` ink at 12.5 sp, on the left; the control on the right. Below the 600 dp family only. The desktop has no
  count line and carries the control in its page head, as amended above.
- **Present or absent:** drawn while the list loads (the control, no count yet) and while the list holds anything; absent
  when it resolves to nothing or fails (as the control is).
- **Two strings:** `music.artists_n` / `music.artists_one` (en *{n} artists* / *1 artist*, da *{n} kunstnere* / *1
  kunstner*, fo *{n} tónleikarar* / *1 tónleikari* — each the word the app already uses for an artist), siblings of
  `music.albums_n`. `music.albums_n` and `music.songs_n` are reused.
- **The 44 dp tap target** is real layout in Compose, not an overhang as on the web: the control's row measures 44 dp
  (the pill is still 28 dp or 38 dp tall, centred in it). QA's acceptance 10 reads the pill's size, not the row's.
