# Phase R373 — The official album, its extras and B-sides, and one copy of every song in Ravilo

> Owner, 2026-10-03/04 (see 305). In Ravilo: *Play album* plays the official album; a menu beside it offers *Play album
> + extras*, which **also plays the B-sides** (owner, Q3); Shuffle has the same menu (Q2); extras are unnumbered (Q4);
> lists show one copy of every song, the better file (Q5); a **Bonus** chip after the version chips (Q6).

## Status

`Planned` · **dev-reviewed 2026-10-04** (against `main` `5210045a`; see the end) · not built · pending export. Written
2026-10-04 (design-authored) beside **admin 305**, from
`specs/design-brief-music-editions-and-duplicates-2026-10-03.md` and the mockups:
- `design/ravilo/mobile/ravilo-editions.js` (`window.RaviloEditions`), used by `Ravilo Mobile.html` (`re-*` in
  `mobile/ravilo-music.css`) and `Ravilo Desktop.html` (`dx-ed*` in `desktop/ravilo-desktop.css`); both load
  `../app/editions.js` for the stand-in data
- the round-1 canvas `design/app/Music Editions - Directions.html` (D1 **A** picked)

Number checked free on `main` tree `fdbc126` (Ravilo tops at R367; our sessions specs hold R368–R372).

**Builds on** R321/R326 (the listening mode), R322 (the player, queue, Now playing), R337 (the desktop's layout), R344
(read-only version chips) and **305** (the server-side model and rules). **Not on the TV** (no music mode).

## Requirements

**FR-R373-1 — The wire.** `/api/tv/music/**` (279) gains, from 305's tables:
- on an album: `official` (ordered track ids), `extras` (ordered track ids), `edition` (*Japanese edition*),
  `singles` (release-group ids, year order) and `bsides` (track ids, single order, A-sides excluded) — all absent for
  an unmatched album;
- on every track: `extra: true` when it is one (drives *Bonus*), and `copies` (the other track ids of the same song);
- on a single: `home_album` when homed;
- every list endpoint that spans albums (songs, an artist's songs and top songs, search, *Shuffle all*) returns **one
  track per song** — 305's FR-305-10 shown copy.
The app computes nothing itself.

**FR-R373-2 — The album page**, phone and desktop, top to bottom:
1. header *{n} songs · {length}* of the official album, then **+ {k} extra(s)** in the dimmer ink;
2. on a single's own page: *Single from {album}* (tappable);
3. **Play album ▾** (primary) and **Shuffle ▾** as split buttons. The ▾ menu: **Play album** (*{n} songs · the
   official order*) · **Play album + extras** (*{n} songs · extras, then the B-sides*), the current one ticked. The
   pick is remembered **per album, per device**, relabels the Play button (*Play album + extras*), and both buttons
   follow it; picking from the menu also starts playback;
4. the official songs, numbered in the official order;
5. a thin divider **Extras · {edition}**, then the extras **unnumbered** — no Bonus chip here (the divider says it);
6. **Singles & B-sides**: the single cards (cover, title, *{year} · Single*), each opening its single; then one fold
   row **{n} B-sides · Show** (*Hide* when open), listing the B-sides with *from {single} (single)* as a second line;
7. *More from {artist}* — without the singles that live on this album.
A tap on any row plays from it inside the current pick's order (*Play album + extras* = official → extras →
B-sides). An unmatched album is today's page.

**FR-R373-3 — One copy in lists.** Songs, search, an artist's top songs and the desktop's Songs page use the server's
folded lists (FR-R373-1); the counts follow (*385 songs*). An album page always lists its own tracks.

**FR-R373-4 — Also on.** The phone's song ⋯ sheet ends with **Also on {n} releases** (*Also on 1 release*) and one
row per other copy (cover · title · *{kind} · {year}*), each opening that release. The desktop's song context menu gets
the same list (not drawn; same rows).

**FR-R373-5 — The Bonus chip.** R344's chip family, no hue (ink on a neutral fill), after the version chips, **never
folded into *+N*** (it is another axis). Shown on song rows in lists, queue rows and Now playing's line; **not** on
an album's own extras section, the mini bar, the lock screen / media session or the TV.

**FR-R373-6 — Chips sit at the row's right** (owner, 2026-10-04): on Ravilo song and track rows the version chips and
Bonus sit just left of the length (desktop: their own column before *Length*, a fixed width on the Songs table so the
columns line up; phone: the right end of the title area, beside the length / ⋯). The title keeps the space and
ellipsises first. Now playing's line keeps them before the artist · album text. (The admin keeps them after the title.)

**FR-R373-7 — Artist page** (Q1): homed singles and EPs leave *Singles & EPs*; under the section a line *{n} singles
live under their album — {album} · …*, each album tappable.

## Strings

`ed.play_album · ed.play_extras · ed.extras · ed.n_extra · ed.n_extras · ed.bonus · ed.singles · ed.n_bsides ·
ed.one_bside · ed.show · ed.hide · ed.from_single · ed.single_from · ed.also_on · ed.also_on_one · ed.sub_album ·
ed.sub_extras · ed.under · ed.album · ed.more_play` × en/da/fo in the mockup's `ravilo-i18n.js` (**da/fo are drafts**;
the shipped `i18n/*.json` wins). The desktop mockup is English like the rest of it.

## Open questions (with leans)

1. **The ▾ pick across devices** — lean per device (it is a listening habit, not a library fact).
2. **Casting with *Play album + extras*** — lean: the receiver gets the expanded queue (R359's long-queue rules apply).
3. **Desktop context menu for *Also on*** — lean the same rows as the phone's sheet; not drawn.

## Acceptance

- *Kite Weather*: *11 songs · 48 min + 1 extra*, *Extras · Japanese edition*, *Lantern Swing* unnumbered, five single
  cards, *13 B-sides · Show*.
- *Play album + extras* queues 11 + 1 + 13 = 25 tracks in that order; the button relabels and is remembered.
- Songs lists each song once; *Lantern Swing* carries *Bonus* there and in the queue, not on the mini bar.
- *Northern Line*'s ⋯ sheet: *Also on 3 releases*.
- Harbour Lights' *Singles & EPs* no longer shows the five *Kite Weather* singles.

## Dev review (2026-10-04, against `main` `5210045a`)

Read against `shared/…/tv/Music.kt`, `MusicTvService`, `MusicTvRoutes`, `MusicDetailScreens` (album and artist pages),
`MusicCommon.TrackRow`, `MusicPlayerScreens.QueuePanelRow`, `MusicCast.playQueue`, `MusicDeviceStore` and the
`i18n/*.json` tables, and alongside 305's review. The design holds. FR-R373-1's wire changes shape (item 1). Ten items.
The owner questions are 305's Q-A and Q-C (they decide what this page says), plus open question 1 here.

1. **FR-R373-1 changes shape. The app cannot use release-group ids or bare track ids.** The phone opens an album by its
   Jellyfin id, and the B-sides are tracks of *other* albums, which are not in `MusicAlbumDetail.tracks`. Every addition
   is optional with a default, per the file's own rule (`shared/…/tv/Music.kt:6-15`). No field is removed or changes
   meaning.
   - **`MusicAlbumDetail`** gains `official_ids: List<String>?` (null means unmatched: today's page), `extra_ids`,
     `edition_title`/`edition_country` (305 Q-C), `singles: List<MusicAlbumCard>` (the **held** singles, year order),
     `bside_tracks: List<MusicTrackItem>` (full items, single order; the existing `album`/`album_id` give *from
     {single}*), and `single_from: MusicAlbumCard?` (a single's own page, FR-R373-2 point 2). `tracks` keeps today's
     file order, so an installed app shows today's page.
   - **`MusicTrackItem`** gains `extra: Boolean = false` (305 Q-A decides what it means on a folded row) and
     `also_on: Int = 0`.
   - **Not `copies` on every track.** Up to ten ids on each of 60 rows per page is weight the sheet only needs on a tap.
     Add **`GET /tv/music/track/{id}/copies`** instead, answering the other visible copies as `{track, album card}`
     (cover, title, kind, year). It is checked with `visible()` like the other routes.
   - **`MusicArtistDetail`** gains `singles_under: [{album: MusicAlbumCard, count}]` (FR-R373-7). Homed singles leave
     `groups`, whichever group holds them (305 item 2e: a live single sits under *Live*).
   - **There is no *Shuffle all*.** `MusicTvRoutes.kt:41-91` has no such route, and the app has none. Drop it from the
     list.

2. **What folds, where** (`MusicTvService`). Browse → Songs (`:184`), search's songs (`:292`), an artist's top songs
   (`:268`), Listen's *Recently played* (`:155`, deduplicated by song, order kept) and the empty search's recent songs
   (`:287`). `artistCard`'s `track_count` (`:127`) folds. Never folded: an album's own tracks, a playlist (the viewer's
   own list), and `last-played` (it is the copy that was played). The copy shown is chosen among the copies **this
   viewer** may see (305 item 8). Installed apps get the folded lists, and fewer singles on an artist page without the
   new line. That changes content, not fields, so it is acceptable.

3. **The queue.** *Play album + extras* is `official_ids` → `extra_ids` → `bside_tracks`, in that order. The order is the
   wire's contract, so no rule lives in the app. Both buttons pass that list to `MusicPlayback.playQueue` (Shuffle with
   `shuffle = true`), as today (`MusicDetailScreens.kt:130-131`, `:153-154`). **A tap on a row outside the current pick**
   (an extra or a B-side while the pick is *Play album*) plays the extended order from that row and leaves the
   remembered pick alone. FR-R373-2's "inside the current pick's order" cannot reach those rows otherwise.

4. **The pick (open question 1).** Kept in `MusicDeviceStore` under one key (`album_pick` → a map of album id →
   `extras`), per device, as leaned. `forgetListening()` (`MusicDeviceStore.kt:42`) clears it with the mode and the
   queue, so the next viewer on the phone starts with the plain album. An unmatched album ignores the stored pick.

5. **Casting (open question 2, answered).** The phone builds the queue and `MusicCast.playQueue`
   (`MusicCast.kt:282-291`) sends it. R359 splits a long queue. Nothing to add.

6. **FR-R373-6 amends R344 FR-R344-3 and R352 FR-R352-1.** Say so in the requirement. The chips move from after the
   title to just before the length, in `TrackRow` (`MusicCommon.kt:216`) and `QueuePanelRow`
   (`MusicPlayerScreens.kt:662`). R352's fold rule stays. **Bonus is a fixed chip outside the fold**: the version chips
   fold first (down to *+N*), then the title truncates, and Bonus is never cut. On the desktop's Songs table the chip
   column has a fixed width. Now playing keeps them before the artist · album line (R344 unchanged there).

7. **The album page** is one composable with two branches (`MusicAlbumScreen`, `deskWide` and phone,
   `MusicDetailScreens.kt:109-158`). Both get the split buttons. The header's length is the sum over `official_ids`, as
   today's header sums `tracks` (`:115`). *More from* (`moreFromArtist`, `MusicTvService.kt:246`) leaves out the
   singles homed on this album on the server.

8. **Strings.** `ed.*` is a new namespace in `i18n/*.json`; none exist yet. Reuse `music.songs_n` / `music.songs_one`
   for *{n} songs*. `music.singles` (*Singles & EPs*) is a different phrase from `ed.singles` (*Singles & B-sides*), so
   both stay. The Faroese follows R288's lexicon (`i18n/lexicon`). `ed.edition` replaces any built *Japanese edition*
   text (305 Q-C).

9. **Desktop context menu (open question 3, answered).** The same rows as the phone's sheet, from the same route.

10. **Out of reach, as intended.** The TV never calls these routes (no music mode). The mini bar, the lock screen and
    MPRIS draw no chips today, so FR-R373-5's "not there" needs no code.


## Owner decisions (2026-10-04, after the dev review)

Follows 305's decisions: *Bonus* only when the song is official nowhere; the copy shown is the highest bitrate
(re-encoding doesn't lower it); the extras section is named from MusicBrainz, else the country in the viewer's
language, else *Extras*. The ▾ pick stays per device.
