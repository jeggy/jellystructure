# An artist plays in release order

Task: DEB-3619

> Owner, 2026-11-04 (DEB-3619), with a screenshot of an artist page on the Mac: *Play all* had started on a song that
> is *"not the first or latest song"* by the artist. *"I would like some more flexibility under the "Play all" button.
> Maybe an option under it with a Play all in a specific order, like newest or oldest first. Newest = sort all
> albums/eps/singles by date but keeping their track ordering within each one and then play that order. Oldest = the
> same just reverse the album ordering, but keeping the track ordering."*

## Scope

Ravilo's artist page on the phone and the desktop (Mac, Linux), and one additive scope on R388's selection route in the
backend. The TV, the web and the receiver have no artist page (R380 FR-R380-9; the web has no music mode). This spec
replaces the **Play all** of R321 FR-R321-8 (and R337's desktop artist head). The *Shuffle* button beside it and the
*Songs* list are kept, with one change to how ties are ordered (FR-6). The owner answered both decisions on DEB-3619 on
2026-11-05. There is no design round: the control is the album page's own split button with different menu rows.

## What is there today

- `MusicTvService.artist` returns `topTracks`, which is every song of the artist (folded, R373) **most played first**,
  capped at 200. Ties are broken by `albumId`, which is an internal id, and then by track position. An artist the viewer
  has barely played therefore starts on an arbitrary song. That is the owner's report.
- The page draws *Play all* (primary pill) and *Shuffle* (quiet pill). Both play `topTracks` locally, with no request,
  under `MusicContext("artist", name, id)`.
- The album page has the split button *Play album ▾* (primary fill, a 1 dp divider, a ▾ that opens a `DropdownMenu` of
  rows with a glyph and a label; `MusicDetailScreens.kt`). R388's lists have *Play all ▾* in the quiet fill
  (`PlayAllControl.kt`), backed by `GET /tv/music/selection` and the `PlayAll` press (busy ring, one request in flight,
  failure toast, capped toast).
- R388's `artists` scope already plays an artist oldest album first (`originalYear`), album by album, each album as its
  *Play album* plays it. Then it plays their songs on other artists' albums.
- The page's groups are *Albums · Singles & EPs · Compilations · Live · Soundtracks*, then *Appears on* (another
  artist's album the artist is credited on). A single or EP that lives under an album (R373 FR-R373-7) is in no group
  and is listed on the *"… live under {album}"* line.

## Decisions

| # | Question | Answer |
|---|---|---|
| D1 | What a plain tap on *Play all* plays (owner, 2026-11-05) | **Newest first.** The ▾ holds *Oldest first* and *Most played* |
| D2 | Which releases join the date order (owner, 2026-11-05) | **Every group on the page**, *Compilations*, *Live*, *Soundtracks* and *Appears on* included |
| D3 | A song that appears on more than one release (PO call) | It plays once. An **album** (the *Albums* group) always keeps its whole tracklist, and every other release adds only the songs no album in the order already has (FR-3) |
| D4 | The *Shuffle* button | Unchanged (PO call: the owner asked for options under *Play all*, not a change to *Shuffle*) |
| D5 | Remembering the last order | Nothing is remembered, as on the album page (PO call) |

D3 refines the cost stated on the D2 card (*"a newer compilation plays first, and the albums after it skip the songs it
already played"*). With D3 the compilation is the release that gives up the repeated songs, and every album plays
whole, which is the owner's *"keeping the track ordering within each one"*.

## Requirements

**FR-1 — The control.** The artist page's *Play all* pill becomes the album page's split button: *▶ Play all | ▾*, in
the album page's primary fill, at the size and place of today's pill. On the phone it keeps today's `pillWidth()`; on
the desktop it sits where today's pill is, beside *Shuffle*. The ▾'s accessible name is `ed.more_play`. The ▾ opens the
album page's menu with two rows, in this order:

| Row | Label | Glyph |
|---|---|---|
| Oldest first | `browse.sort.oldest` | play |
| Most played | `music.sort_played` | play |

A tap outside closes the menu. Newest first is the main half and has no row of its own. The control is drawn whenever
today's pill is (the artist has at least one song).

**FR-2 — What each order plays.**

- **Newest first** (the main half) and **Oldest first** call the selection route (FR-4) with `sort=newest` or
  `sort=oldest`, and play the answer from its first song with shuffle off.
- **Most played** plays `topTracks` from its first song with shuffle off, locally and with no request, as today's
  *Play all* does.

Each order **replaces** the queue (R322 FR-R322-8), under `MusicContext("artist", name, id)` as today, and plays on
whatever the viewer is playing on (`MusicPlayback` routes it). Track gain applies, never album gain (R322 FR-R322-9).

**FR-3 — The release order** (D2, D3). The releases are every album the artist page draws in a group: the artist's own
albums of every type, minus the singles and EPs that live under an album, plus the *Appears on* albums.

1. **Dated.** A release's date is `MusicAlbum.originalDate()` (`YYYY`, `YYYY-MM` or `YYYY-MM-DD`). Releases are ordered
   by that string, newest first or oldest first. A release whose date has fewer parts than another of the same year
   sorts as if it were that year's 1 January (it comes first in *Oldest first*, last in *Newest first*). Ties are broken
   by type in the page's group order (*album, single, compilation, live, soundtrack*, then *Appears on*), then by title
   (lower-cased). A release with no date goes **last in both orders**, ordered by title.
2. **Each release in its own order.** An own release contributes what its *Play album* plays: its official tracklist
   when R373 matched one, else its files in disc/track order (R388's `albumSongs`). A single or EP that lives under an
   album contributes nothing on its own, because its album's tracklist already holds it. An *Appears on* album
   contributes only the artist's songs on it (`songsBy`), in that album's disc/track order.
3. **One copy of every song, albums whole** (D3). The order is folded to one copy of every song (`View.fold`, the copy
   the viewer is shown). A song on any release of type *album* plays at its place in that album: in *Newest first* that
   is the newest such album, and in *Oldest first* the oldest. Every other release (single, EP, compilation, live,
   soundtrack, *Appears on*) adds only the songs that are on no *album* in the order. Its remaining songs keep their
   relative order.
4. **The cap.** At most `MUSIC_SELECTION_CAP` (1 000) songs: the first 1 000 of the order. `capped` and the capped toast
   are R388's FR-R388-4 and FR-R388-6.

**FR-4 — The route.** R388's `GET /api/tv/music/selection` takes a new scope:

`scope=artist&artist={id}&sort=newest|oldest`

It applies the viewer's `View` like every other music route, and answers R388's `MusicSelection` with FR-3's order.
An absent `artist`, or a `sort` other than `newest` or `oldest`, is a 400 (R388's error body). An artist the viewer
cannot see answers `MusicSelection(tracks = [], total = 0, capped = false)`, which the client treats as a failure
(FR-5). `shuffle` is ignored for this scope.

**FR-5 — Pressing it.** R388's press, unchanged: the half that was pressed (the main half for a menu row too, because
the menu closes on the tap) swaps its glyph for the busy ring until the route answers. The control ignores presses
while a request is in flight. A failure, an empty answer or no connection toasts `music.fail_t` through `MusicToasts`
and leaves the queue as it was. *Most played* makes no request, so it is never busy.

**FR-6 — Ties in *Most played*.** `topTracks` keeps its order by the viewer's play count. Songs with the same count
follow **FR-3's newest-first order** instead of `albumId`. The cap of 200 stays. This also orders the page's *Songs*
list, which draws `topTracks`. For a viewer who has played nothing by the artist, the first five songs are now the
newest release's first five.

**FR-7 — The desktop keyboard.** As R388's amended FR-R388-9: Tab reaches *Play all*, then ▾, then *Shuffle*. Enter or
Space on *Play all* plays newest first. Enter or Space on ▾ opens the menu with *Oldest first* focused; ↑/↓ move, Enter
picks, Esc closes. The focus ring is the app's own.

## Wire

Additive only.

- **The route:** a new `scope` value, `artist`, and a new query parameter, `artist`, on `GET /tv/music/selection`.
  `sort` takes two new values for that scope only.
- **No DTO changes.** `MusicArtistDetail.topTracks` keeps its type and meaning ("most played first"); only the tie order
  changes.

An installed app never sends `scope=artist`, so it keeps today's *Play all* and sees only FR-6's tie order. A new app
against an older server receives R388's 400 and toasts *Couldn't play this* (FR-5). The server ships first.

## Strings

None new. `browse.sort.oldest` (*Oldest first* / *Ældste først* / *Elst fyrst*), `music.sort_played` (*Most played* /
*Mest afspillet* / *Mest spælt*),
`music.play_all`, `ed.more_play` and `music.fail_t` are reused. No string names Jellyfin.

## Acceptance

1. An artist with an album, an EP and a single in three different years, never played by this viewer: *Play all*
   starts on the first track of the newest release and plays it in its track order, then the next newest.
2. ▾ → *Oldest first* starts on the first track of the oldest release, then plays forward through the years.
3. An artist whose lead single (not under its album) precedes the album, *Oldest first*: the single's song is not played
   from the single; it plays at its place in the album, and the album plays whole and in order.
4. An artist with a newer compilation of album songs, *Newest first*: the compilation adds only its songs that are on
   none of the artist's albums; every album plays whole.
5. An artist credited on another artist's album: only their songs on it are queued, at that album's date.
6. ▾ → *Most played* plays immediately with no busy ring, the most-played song first. An artist with no plays: the
   *Songs* list's first song is the newest release's first track.
7. The route answers 400 for `scope=artist` with no `artist`, or with `sort=title`.
8. While casting to a speaker, *Play all* plays on the speaker in the same order.
9. The Mac: Tab reaches *Play all*, then ▾, then *Shuffle*; Enter on ▾ opens the menu with *Oldest first* focused.
10. Unit (backend): FR-3 on a fixture artist covering every group, dates of mixed precision, an undated release, a
    single under an album, a lead single not under its album, a compilation repeating album songs, *Appears on*, the cap,
    the viewer's library filter, and the 400s. FR-6's tie order.
11. Unit (client): each menu row's request (or no request for *Most played*), the context, and a second press in flight
    sends nothing.

## Out of scope

A remembered order. A *Shuffle* change. A version filter (excluding remixes and live recordings; phase 292). The TV,
the web and the receiver.
