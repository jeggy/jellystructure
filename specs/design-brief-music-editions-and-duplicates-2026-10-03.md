# Design brief — the official album, its extras, its singles, and one copy of every song

**Date:** 2026-10-03 · **For:** the design project (Cosmos): admin `design/app/` (`album.html`, `artist.html`,
`library.html`) and Ravilo `design/ravilo/` (`Ravilo Mobile.html`, `Ravilo Desktop.html`) · **Status:** brief; the
owner answered the four round-0 questions on 2026-10-03 (§A); round-1 directions wanted on §G · **Source:**
`research-reports/music-editions-extras-and-duplicates-2026-10-03.md` (the household library, Lidarr's copy of
MusicBrainz and MusicBrainz itself, read-only).

> Owner, 2026-10-03: *"Maybe we need to add better support for the official album, without losing any songs.
> Example being deluxe or Japanese releases. I think it would be nice to always support the official album and
> then maybe have an indicator that this song is extra."*

Every title below is a fictional stand-in, from `app/music-data.js` where one exists. Every **number** is real, from
the household library today, so the mockup can be drawn at true weight. **No spec is written yet**; the designs
come first. Prospective numbers are admin 303 and Ravilo R361; check they are still free on `main` first.

## 0. What exists, so nothing is drawn twice

- **Admin album page** (`album.html`), Tracks tab: songs in file order, with position, title, length, the
  recording's agreement mark (276), the lyrics mark (277) and version chips (292). The header already shows
  *release: country · year · label · format* and *Partial album: the release has N tracks*, with gap rows
  *not in library*. **Find match…** lists the pressings of the album.
- **Admin artist page** (`artist.html`): sections *Albums · Singles & EPs · Compilations · Live*.
- **Library → Music → Songs**: every file is a row; nothing is folded.
- **Ravilo phone and desktop** (R321/R337/R344): album page with *Play* and *Shuffle*; artist page grouped
  *Albums · Singles & EPs · Compilations · Live*; read-only version chips on song rows (phone: two, then *+N*).
- **Version chips** (292 / R344) are about the *recording* (Live, Demo, Remix …). This brief is about the
  *edition* a song came on. The two are separate axes: a live cut on an anniversary edition carries *Live* **and**
  *Bonus*.

## A. What the owner decided (2026-10-03)

1. **The official album is the standard edition**, and it is found automatically: the tracklist that most of the
   album's official releases share. The owner can pick another one per album, and that pick is kept across runs.
   It applies to albums, live albums and soundtracks, never to singles and EPs (a single has no standard
   tracklist: one here has 14 releases of 2 to 6 tracks).
2. **Collect the biggest edition; lose nothing.** Songs on the held edition that are not on the official album are
   **extras**. They stay in the library and are shown, never hidden or deleted.
3. **The album page, top to bottom (Q1):** *the album's songs* → **a thin divider** → *its extra songs* → below
   that, **its singles and B-sides**.
4. **Play (Q2):** *Play album* plays the official album. A small menu beside it offers **Play album + extras**.
5. **The marker (Q3):** a small **Bonus** chip on an extra song wherever it appears outside its own album's
   extras section. Where it came from (*Japanese edition*, *20th Anniversary*, *super deluxe*) is in the song's
   detail and in the divider's label, not on the chip.
6. **Duplicates (Q4): show every song once.** A duplicate is *the exact same edition* of a song. The owner first
   proposed *same name and length ±3 s*. Measured, it both joins different takes and splits one take (research §4),
   so the owner asked for the proper rule instead (2026-10-03). **A song is its recording:**
   1. the same **trusted MusicBrainz recording** (276 `agrees`/`manual`) ⇒ one song;
   2. a copy **without a trusted recording** ⇒ compared by **audio fingerprint** with the artist's other songs; a
      near-identical match makes it that song;
   3. two trusted but different recordings that **sound** near-identical ⇒ a **suggestion** to the owner, never
      joined on its own;
   4. the owner's *same song* / *not the same song* always wins and is kept across runs.

   Title and length never decide. **Nothing is deleted.** Every album page keeps its own full tracklist; every list
   that spans albums shows one copy.

## B. The numbers to draw at

- **Editions:** 5 of 10 studio albums are held as a bigger edition. Their extras: **1 · 1 · 12 · 1 · 10**. The
  12 are one Japanese bonus track plus 11 live and demo cuts from an anniversary edition; the 10 are alternate,
  acoustic and remix versions from a super deluxe. One live album has 1 extra.
- **Where the extras first appeared:** four of the five single-song extras first came out on the **Japanese CD**.
- **Duplicates:** 487 songs → **385** shown, with 102 copies in 78 groups folded away (98 by recording, 4 by
  audio). **3 suggestions** wait for the owner, and accepting all three gives 382. The kept copy comes from the album
  50 times and from the single 26 times. 65 of the hidden copies are on a box set and a soundtrack set, 37 on singles
  and EPs. The worst song has 10 copies.
- **Singles under albums:** 43 of 49 singles and EPs find their album. **B-side songs per album** (after
  duplicates): **25 · 19 · 13 · 9 · 9 · 6 · 2 · 0 · 0**. Single cards per album: 2 to 7. Six releases stay alone
  on the artist page: 2 singles on no album, 1 single from another artist's soundtrack, and 3 EPs.

Stand-ins: *Harbour Lights* — *Kite Weather* (2006): 11 songs + 1 extra (*Lantern Swing*, Japanese edition), held as
a 24/96 download. *Signal Found* (2003): 14 songs + 12 extras (*Signal Found 20th Anniversary*), one of them also on
the Japanese CD. Singles from *Kite Weather*: *Northern Line*, *Fog Bank*, *Salt on the Window*, *Low Tide*,
*Tidewater*, with 13 B-sides between them.

## C. Where it is stored (recommendation)

**Our own tables.** No tag standard has an "official tracklist" or "bonus track" field. Picard writes none, and
MusicBrainz only implies it through which releases carry a recording. The owner's music rule is *standards where one
exists, our tables where none does and Ravilo gains* (`research-reports/music-tags-in-the-files-2026-09-28.md`).

- Per album: the official tracklist (a list of recordings, plus the release it was read from and *on N of M
  releases*), and the owner's pick if any.
- Per track: *extra*, plus where it first appeared.
- Per single: its home album, and how that was found (MusicBrainz *single from* · via a remix · by its A-side's
  title · picked by you).
- Per track: an audio fingerprint, only for copies that need the audio check (no trusted recording, plus the
  artist's songs they are compared with). It is computed once and kept until the file changes.
- The owner's decisions: *same song* / *not the same song* per pair, and an answered suggestion, so it is never
  asked again.
- Duplicate groups themselves are **computed**, never stored: they follow the files and the decisions.

No file is moved, renamed, retagged or deleted for any of this.

## D. What to draw

### D1. Album page: Ravilo phone + desktop (the main surface)

Top to bottom:

1. **Header:** title, year, *11 songs · 48 min*. When there are extras: *+ 1 extra* in quieter type. Duration and
   count describe the official album.
2. **Play album** (primary) with a small **▾** or **⋯** menu: *Play album + extras*. Shuffle follows the same
   choice (lean: Shuffle = the album only, with the same menu).
3. **The official songs**, numbered **1–11 in the official order**. An extra that sits in the middle of the held
   edition (one sits at track 12 of 26) moves down to the extras.
4. **A thin divider labelled with where the extras came from:** *Extras · Japanese edition* or *Extras · Signal
   Found 20th Anniversary · 12*. No numbers on extra rows (or a quiet *+1, +2*; designer's call).
5. **Singles & B-sides:** a row of the album's single cards (cover, title, year), then the **B-side songs**
   (songs on those singles that are not already shown above). Each has its single's name as a quiet second line.
   With 25 B-sides on the oldest album, it likely starts folded (*25 B-sides · Show*).
   Version chips (R344) still apply on every row.

**Not on this page:** a single's A-side or any other duplicate of a song already shown (§A6).

### D2. Album page: admin (`album.html`, Tracks tab)

The same order and divider, plus what an admin needs:

- An **official-tracklist line** under the release line: *Official album: 11 songs, as on 14 of 20 releases ·
  Change…*. *Change…* opens the pressing list (Find match…'s) with **Use as the official album** on each; a pick
  reads *chosen by you · Back to automatic*.
- An official song the held edition lacks shows as today's gap row (*not in library*).
- Each extra row says where it first appeared (*first on the Japanese CD, 2006*).
- The singles section lists each single with *how it was linked* (*MusicBrainz* · *via remix* · *by title* · *you*)
  and lets the owner move a single to another album, or to *no album*.

### D3. Artist page (admin + Ravilo)

Albums as today. **Singles that belong to an album leave the *Singles & EPs* row** and live under their album
(lean, to keep it clean; §G Q1). The row keeps the stand-alone ones: here 2 singles + 1 soundtrack single + 3 EPs.

### D4. Songs everywhere else: one copy

Library → Songs (admin), Ravilo's Songs, an artist's songs and top songs, search, *Shuffle all*, queues built
from a list: **one row per song**. The row shown is the album's official copy first, then the album's extra, then
a single's, then a compilation's or box set's, then a live album's. On a tie, the higher sample rate or bit depth
wins.

- The row's detail or side panel lists the other copies: *also on 3 releases: Signal Found (single), Tide Tables
  1999–2012 (box set) …*, each opening its album.
- **Admin only:** the Songs view gets a **Show every copy** switch, because the admin manages files, not songs.
  It is off by default.
- **Admin only:** in a song's side panel, each other copy shows *why* it counts as the same song (*same recording ·
  MusicBrainz*, *sounds the same*, *you said so*), plus **Not the same song**. A copy that is not joined can be
  joined with **Same song as…**.
- **Admin only:** the 3 suggestions, each as two players side by side with *These sound the same: one song?*
  **Yes** / **No**. Where they live is the designer's call: one Dashboard row in 285's grammar (*Songs that may be
  the same · 3*, severity info) is the lean.
- An extra keeps its **Bonus** chip in these lists.

### D5. The Bonus chip

The same visual family as the version chips (R344): small, read-only, in its own neutral colour. It sits after the
version chips, in admin and Ravilo song rows, the Now playing line, and the queue. It is **not** on the mini bar,
the lock screen or the TV (R344's rule). In an album's own extras section the divider already says it, so there
is no chip there.

### D6. States

- An album held as the standard edition: nothing changes, and there is no divider.
- The super deluxe album: 11 + 10 extras, the extras mostly carrying version chips too.
- The held edition lacks an official song: a gap row above the divider.
- The owner has picked another release as official.
- An unmatched album: no official tracklist, so a plain list with no divider and no singles section.
- An album with no singles: no section.
- A single the owner moved to *no album*.
- A song with 10 copies: one row, *also on 9 releases*.
- A single whose tags name another recording but whose audio is the album's: one row, *sounds the same* in the
  admin's side panel.
- A suggestion answered *No*: the two stay separate and are never asked about again.

## E. Strings (admin + Ravilo, English)

*Play album* · *Play album + extras* · *Extras* · *Extras · {edition}* · *+ {n} extra(s)* · *Bonus* · *Japanese
edition* · *first on {release}, {year}* · *Singles & B-sides* · *{n} B-sides* · *Official album: {n} songs, as on
{k} of {m} releases* · *Use as the official album* · *chosen by you* · *Back to automatic* · *also on {n}
releases* · *Show every copy* · *No album* · *same recording · MusicBrainz* · *sounds the same* · *you said so* ·
*Same song as…* · *Not the same song* · *These sound the same: one song?* · *Songs that may be the same*. Danish and Faroese are drafts for the implementer (the shipped
`i18n/*.json` wins).

## F. What is deliberately out

- Fetching missing editions or songs: that is Lidarr's job.
- Deleting duplicate files; the files stay the record.
- Spreading a version or any other fact between copies joined by audio or by hand (292 Q7 still holds: only the
  same MusicBrainz recording shares facts).
- Telling MusicBrainz that two recordings should be merged (a later idea, by hand).
- An official tracklist for singles, EPs or box sets.
- The TV (no music mode).

## G. Questions for round 1

| # | Question | Lean |
|---|---|---|
| Q1 | Do singles that belong to an album leave the artist's *Singles & EPs* row? | **yes**: they live under their album; the row keeps stand-alone singles and EPs |
| Q2 | Does *Shuffle* follow the same album / album + extras choice? | **yes**, with the same menu |
| Q3 | Do B-sides ever play with *Play album + extras*? | **no**: they belong to the singles; a *Play B-sides* on the section if the designer finds room |
| Q4 | Numbers on extra rows? | **none** (they are not part of the album's order) |
| Q5 | Which copy represents a duplicate in lists? | **album > the album's extra > single > compilation/box set > live album**; then the better file |
| Q6 | The Bonus chip on extras that also have version chips | **after the version chips**, same size |
| Q7 | Admin Songs: duplicates folded by default? | **yes**, with *Show every copy* |
