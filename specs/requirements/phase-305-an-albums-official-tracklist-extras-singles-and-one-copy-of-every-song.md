# Phase 305 — An album's official tracklist, its extras, its singles, and one copy of every song

> Owner, 2026-10-03: *"Collect the biggest edition and lose nothing"* — the official album first, a thin divider, the
> extras, then the singles and B-sides; every song shown once. Owner, 2026-10-04, on the round-1 directions:
> D1 **A**; Q1 yes · Q2 yes · **Q3 yes, the B-sides play with *Play album + extras*** · Q4 no number ·
> **Q5 the better file first, wherever it is from** · Q6 after the version chips · Q7 folded.

## Status

`Planned` · **not dev-reviewed** · pending export. Written 2026-10-04 (design-authored) from
`specs/design-brief-music-editions-and-duplicates-2026-10-03.md` (owner decided §A on 10-03 and §G on 10-04) and the
mockups built from it:
- `design/app/editions.js` + `editions.css` (`ed-*`, `window.Editions`), loaded by `album.html`, `library.html`,
  `artist.html`, `metadata.html` and `index.html`
- the round-1 canvas `design/app/Music Editions - Directions.html` (+ `editions-directions.js`), where D1 = **A** is
  picked and B/C are kept as declined

The number was checked free on `main` (tree `fdbc126`, admin tops at 304 with our playback-sessions spec) on
2026-10-04. The brief's own "303 / R361" were taken the same day. **Ravilo's side is R373.**

**Builds on** 275 (the music library), 276 (MusicBrainz: the release match, the recording match, `agrees`/`manual`),
278 (the admin UI: album, artist, Library → Music), 285 (the Dashboard's row grammar) and 292 (song versions — a
separate axis: a live cut on an anniversary edition carries *Live* **and** *Bonus*).

## The household (numbers to build against)

- 5 of 10 studio albums are held as a bigger edition; their extras are **1 · 1 · 12 · 1 · 10**. One live album has 1.
- 487 songs → **385** shown; 102 copies in 78 groups folded away (98 by recording, 4 by audio); **3 suggestions**.
- 43 of 49 singles and EPs find their album. B-sides per album: **25 · 19 · 13 · 9 · 9 · 6 · 2 · 0 · 0**.
- Stand-ins in the mockup: Harbour Lights — *Kite Weather* (2006, 11 + *Lantern Swing*, Japanese edition, 24/96) and
  *Signal Found* (2003, 14 + 12 extras from *Signal Found 20th Anniversary*). Singles from *Kite Weather*:
  *Northern Line*, *Fog Bank*, *Salt on the Window*, *Low Tide*, *Tidewater* — 13 B-sides.

## Requirements

### The model

**FR-305-1 — Our own tables; no file is touched.** No tag standard carries an official tracklist or a bonus flag
(research §C), so this lives in jellystructure's database. Nothing here moves, renames, retags or deletes a file.
- `music_album_official` — per album: the official tracklist (ordered MusicBrainz recording ids), the release it was
  read from, `k` of `m` releases that share it, `source` = `auto` | `user`, `user_release_mbid` when picked.
- `music_track_extra` — per track: `extra` (bool), `first_release` (title + year of the earliest official release
  that carries the recording; *the Japanese CD, 2006*).
- `music_single_home` — per single/EP release-group: `album_id` or null, `how` = `mb_single_from` | `via_remix` |
  `by_title` | `user`.
- `music_fingerprint` — per track, only where the audio check is needed (FR-305-9): the Chromaprint, kept until the
  file's size or mtime changes.
- `music_same_song` — the owner's decisions: pair (track a, track b) → `same` | `not_same`; plus answered
  suggestions (`yes` | `no`), so a pair is never asked about twice.
- Duplicate **groups are computed, never stored** — they follow the files, the matches and the decisions.

### The official album

**FR-305-2 — Found automatically.** For an album, a live album or a soundtrack with a trusted release match (276),
the official tracklist is **the tracklist most of the release group's official releases share** (by recording, in
order; status *Official*, not *Bootleg* / *Promotion*). Ties go to the earliest release. The line records *k of m*.
Singles, EPs, compilations and box sets never get one (a single here has 14 releases of 2–6 tracks).

**FR-305-3 — Extras.** A held track whose recording is not on the official tracklist is an **extra**. Extras stay in
the library, in every count of files, and are shown — never hidden. An extra in the middle of the held edition
(track 12 of 26) moves down to the extras section. `first_release` is filled from MusicBrainz.

**FR-305-4 — The owner's pick.** *Change…* lists the album's pressings (Find match…'s list: country · date · label ·
format · tracks · *against the official*) with **Use as the official album** on each pressing whose extra songs are in
the library. A pick is `source = user`, kept across every scan and re-match, and reads *chosen by you · Back to
automatic*. Songs the pick has that the held files lack become gap rows; songs the files have that the pick lacks
become extras.

**FR-305-5 — An unmatched album** has no official tracklist: a plain list in the files' own order, no divider, no
singles section, no *+ N extras* — exactly today.

### Singles under their album

**FR-305-6 — A single finds its album**, in this order, first hit wins:
1. MusicBrainz's *single from* relationship on the single's release group → `mb_single_from`;
2. a track on the single is a remix / edit of a recording on the album (292's *remix of*) → `via_remix`;
3. the single's A-side title equals an official album song's title, same artist, single released within two years of
   the album → `by_title`;
4. the owner's **Move to…** (any album of the same artist, or **No album**) → `user`, kept across runs; **Back to
   automatic** clears it.
A single from another artist's release (a soundtrack single) is never homed. EPs follow the same rules.

**FR-305-7 — B-sides** are a homed single's tracks that are not already one of the album's songs (official or extra)
after FR-305-8's folding. The A-side is never repeated.

### One copy of every song

**FR-305-8 — A song is its recording** (brief §A6). Two tracks are one song when:
1. they have the **same trusted MusicBrainz recording** (276 `agrees`/`manual`); or
2. one has **no trusted recording** and its fingerprint is near-identical to one of the same artist's songs
   (FR-305-9); or
3. the owner said **same song** (`music_same_song`).
The owner's **not the same song** beats 1 and 2. Title and length never decide.

**FR-305-9 — The audio check.** Fingerprint only the tracks that need it: those without a trusted recording, and the
same artist's songs they are compared with. Compare over the overlapping 30 s from the same second; a match score at
or above the threshold the dev side picks (lean: Chromaprint's `≥ 0.9` similarity) joins (rule 2). Two **trusted but
different** recordings that match are never joined — they become a **suggestion** (FR-305-14).

**FR-305-10 — The copy shown in lists = the better file, wherever it is from** (owner, Q5, against the brief's lean).
Better = lossless over lossy; then higher bit depth; then higher sample rate; then higher bitrate. On a tie: the album's
official copy, then the album's extra, a single's, a compilation's or box set's, a live album's; then the earliest
added. The shown copy's play count, last played, rating and favourites are the **song's** — summed / unioned over the
copies, written back to the copy that was played.

**FR-305-11 — Facts are not spread.** 292 Q7 holds: versions, lyrics and every other fact are shared only between
copies of the same MusicBrainz recording. A copy joined by sound or by the owner keeps its own.

### The admin

**FR-305-12 — Album → Tracks** (`album.html`), top to bottom:
- header count *11 songs + 1 extra*; the length is the official album's;
- under the release line: **Official album: {n} songs, as on {k} of {m} releases · Change…** (or *as on {release} ·
  chosen by you · Back to automatic · Change…*) and *held: {country · year · label · format · tracks}*;
- the official songs, numbered in the official order; a gap row (*not in library*) where the held edition lacks one,
  above the divider;
- a thin divider **Extras · {edition} · {n}**; extra rows **unnumbered** (Q4), each with *first on {release}, {year}*;
  version chips as 292 (no Bonus chip here — the divider says it);
- **Singles & B-sides** below the table: one row per single (cover, title, year, *B-sides here*, a **Linked** chip
  *MusicBrainz · via remix · by title · you*, **Move to…**), then a fold *{n} B-sides · Show* listing them with *from
  {single} (single, {year})*;
- a single's own page: *Single from {album}* with its Linked chip and Move to…, or *No album*.

**FR-305-13 — Library → Music → Songs is folded** (Q7). One row per song (FR-305-10's copy), *also on {n} releases*
under the title; the header says *{songs} songs · {n} copies folded*. A **Show every copy** switch (off by default,
remembered per admin) lists every file: the other copies indented under their song, each with its reason (*same
recording · MusicBrainz* · *sounds the same* · *you said so*). Clicking *also on …* opens the **copies panel**: every
copy (album, kind, year, track, format), *shown in lists* on the kept one, the reason chip, **Not the same song** on
each other copy, and **Same song as…** (a search over the same artist's songs) at the foot. Albums and artists count
songs the folded way. Every copy that is an extra carries **Bonus** after its version chips (292's chip family, no
hue).

**FR-305-14 — Dashboard** (285's grammar): *Songs that may be the same* · severity **info** · counted in **pairs** ·
fix *here* · action **Listen and decide**. The modal shows one pair at a time (*{i} of {n}*): two players that start
from the same second, each with album · kind · length and its MusicBrainz recording name; **Yes, one song** · **No, two
songs** · **Later**. Yes stores `same` (*you said so*); No stores `not_same` and the pair never returns; the count
follows; at zero the row is gone.

**FR-305-15 — Artist** (`artist.html`, Q1): a homed single or EP leaves **Singles & EPs**; the section keeps the
stand-alone ones, with one line *{n} singles live under their albums — {album}'s {k} · … in Singles & B-sides*.

### Out of scope

Fetching missing editions or songs (Lidarr's job) · deleting or retagging duplicate files · telling MusicBrainz to merge
recordings · an official tracklist for singles, EPs or box sets · the TV (no music mode).

## Open questions (with leans)

1. **The fingerprint threshold** (FR-305-9) — lean Chromaprint similarity ≥ 0.9 over 30 s; the dev side measures on
   the household's 4 audio joins and 3 suggestions.
2. **Summed play counts** (FR-305-10) — lean yes, the song's numbers are the union of its copies; Jellyfin's per-item
   numbers are untouched.
3. **A pick whose extras aren't held** (FR-305-4) — lean: the row is shown but cannot be picked, with *+ 13 not in
   the library*.

## Strings (English; da/fo are drafts — the shipped `i18n/*.json` wins)

*Official album: {n} songs, as on {k} of {m} releases* · *as on {release}* · *Change…* · *Use as the official album* ·
*Use automatic* · *chosen by you* · *Back to automatic* · *held:* · *Extras · {edition} · {n}* · *first on {release},
{year}* · *{n} songs + {k} extras* · *Bonus* · *Singles & B-sides* · *Linked* · *MusicBrainz* · *via remix* ·
*by title* · *you* · *Move to…* · *No album* · *Single from {album}* · *{n} B-sides* · *Show* · *Hide* · *from {single}
(single, {year})* · *also on {n} releases* · *Show every copy* · *{n} songs · {k} copies folded* · *shown in lists* ·
*same recording · MusicBrainz* · *sounds the same* · *you said so* · *Not the same song* · *Same song as…* · *Songs
that may be the same* · *Listen and decide* · *These sound the same: one song?* · *Yes, one song* · *No, two songs* ·
*Later* · *{n} singles live under their albums*.

## Acceptance

- *Kite Weather* shows 11 numbered songs, *Extras · Japanese edition · 1*, *Lantern Swing* unnumbered with *first on
  the Japanese CD, 2006*, then 5 singles and *13 B-sides · Show*.
- Picking the Japanese CD makes *Lantern Swing* song 12; no divider; *Back to automatic* restores it.
- Library → Songs lists 385 songs; *Show every copy* lists 487.
- *Northern Line*'s panel lists 4 copies with their reasons; *Not the same song* on the compilation's splits it off.
- The Dashboard row reads *3 pairs*; answering all three removes it.
- Harbour Lights' *Singles & EPs* shows 6.
