# Phase R373 — The official album, its extras and B-sides, and one copy of every song in Ravilo

> Owner, 2026-10-03/04 (see 305). In Ravilo: *Play album* plays the official album; a menu beside it offers *Play album
> + extras*, which **also plays the B-sides** (owner, Q3); Shuffle has the same menu (Q2); extras are unnumbered (Q4);
> lists show one copy of every song, the better file (Q5); a **Bonus** chip after the version chips (Q6).

## Status

`Planned` · **not dev-reviewed** · pending export. Written 2026-10-04 (design-authored) beside **admin 305**, from
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
