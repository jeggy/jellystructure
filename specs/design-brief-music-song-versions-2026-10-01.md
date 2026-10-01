# Design brief — a song's version: Live, Remix, Instrumental, Edit …

**Date:** 2026-10-01 · **For:** the design project (Cosmos) that owns `design/app/` (`album.html`, `library.html`,
`metadata.html`, `index.html`) · **Status:** brief; the owner answered §G on 2026-10-01; **Q1 picked: A · words (owner, 2026-10-01) — spec'd as admin 292 + Ravilo R344** · **Source:** the household's
music library on 2026-10-01 (read-only), MusicBrainz, and `research-reports/music-lyrics-that-do-not-belong-2026-10-01.md`.

> Owner, 2026-10-01: *"I would like to have support for adding types of music, like Live, Remix, Instrumental …
> We want to be able to manage and see this information within jellystructure (maybe show it in Ravilo later)."*
> On filters: *"One example (maybe not yet) will be shuffle all [an artist's] songs, excluding Remixes and Live
> songs. Badges should be part of the first implementation."*

Every title below is a fictional stand-in. Every **number** is real, from the household library today, so the
mockup can be drawn at true weight. **No spec is written for this yet** (owner): the designs come first.

## 0. What exists, so nothing is drawn twice

- **Album page** (`album.html`): tabs *Tracks · Files · Artwork · Genres · NFO · History*. The Tracks tab lists
  songs with position, title, length, the recording's agreement mark (276) and a lyrics mark (277).
- **Library → Music** (`library.html?kind=music`): *Albums · Artists · Songs*, the workbench's facets, bulk actions.
- **Metadata** (`metadata.html`): *Studios · Networks · Genres · Music genres · Tags · Age ratings · Trackers*. The
  **Tags** tab is the model this brief borrows: a list of named things with a colour swatch, a description and a
  count, each opening the titles that carry it.
- **Genres on an album** (276 FR-276-7): MusicBrainz's votes shown as chips with where they came from; tick or
  untick to override, *"the override is kept across runs"*. This is the editing model this brief borrows.
- **JS tags on films and series** (phase 82/199): our own tags survive every re-pull; a scan never removes them.
- **The Dashboard** (285): one row grammar — severity · label + sentence · count · the one action.

## A. What the owner decided

1. **Nine version types** (§B). A song has **zero, one or many**: *Live + Acoustic*, *Remix + Edit*, *Demo +
   Instrumental*. **A normal song has no version** and shows nothing.
2. **Instrumental** is an instrumental version of a sung song — nothing else (owner). A piece that was never sung
   (an intro, a prelude) is a normal song and has **no version**. MusicBrainz agrees: it records the version as a
   performance marked `instrumental`, and a never-sung piece as an ordinary performance of a work with no words
   (`zxx`) — 13 such songs here, none marked. Such a piece still never gets lyrics (§D5).
3. **Found automatically wherever possible** (MusicBrainz first, then the title), **and managed by hand** — the
   owner's own choice always wins and is kept across runs, as genres and JS tags are.
   **A change spreads only along a direct link** (owner): two songs share a version only when MusicBrainz says they
   are the same recording. A song with the same title and the same length on another album is **not** linked — the
   album name may be the only thing that differs, and nothing says it is the same version.
4. **Where it is stored** — our own table (§C).
5. **First version:** visible and editable in jellystructure, **badges**, and **filters** (include *and* exclude).
   Ravilo later — the filter example (an artist's songs without Remix and Live) is the use case to keep in mind.
6. **Lyrics on an instrumental is an issue on the Dashboard** (§D5), and **only the owner removes them** — a button
   on the row, never automatic. The lyrics step stops *fetching* for a song MusicBrainz calls instrumental.
7. **Telling LRCLIB** that a song is instrumental is a manual action in jellystructure, never automatic (§D5).
8. **A Session is also Live** — ticking Session ticks Live, so *without Live* hides sessions too.

## B. The nine types

How many songs in the library would get each type automatically today (487 songs; **193 get at least one**):

| Type | Means | Found from | Songs |
|---|---|---|---|
| **Live** | recorded at a concert (and every Session) | MusicBrainz (`live` on the recording's performance) · title *live* | 116 |
| **Demo** | an early, unfinished recording | MusicBrainz (`demo`) · title *demo* | 28 |
| **Remix** | someone re-made the recording (*… remix*, *… mix*, *reinterpretation*) | MusicBrainz (a *remix of* relationship, a remixer) · title | 25 |
| **Instrumental** | a sung song without the voice | MusicBrainz (every performance of the recording marked `instrumental`) · title *instrumental*, *karaoke* | 12 |
| **Cover** | one artist playing another artist's song | MusicBrainz (`cover`) | 20 |
| **Acoustic** | played unplugged | title *acoustic*, *unplugged* | 10 |
| **Edit** | the same recording made shorter or longer (*radio edit*, *extended*, *single edit*) | title *edit*, *extended* | 8 |
| **Alternate version** | a different take or arrangement of the same song | title *alternate*, *alternative version* | 7 |
| **Session** | recorded live in a studio for radio, TV or a website | title *session*, *Radio 2*-style | 4 |

Common combinations today: *Live* alone 100 · *Remix* 18 · *Demo* 17 · *Cover* 11 · *Cover + Live* 8 · *Demo +
Instrumental* 8 · *Remix + Edit* 6 · *Live + Session* 3. Stand-ins for the mockup:

- *Salt on the Window* (no version) · *Salt on the Window (live at the harbour, 2011)* — Live
- *Northern Line (Lighthouse Keepers remix) (extended)* — Remix + Edit
- *Fog Bank* on a box set — Cover + Demo + Instrumental (an instrumental demo of a song another artist wrote)
- *Prelude* — no version (never sung; never given lyrics)
- *Low Tide (acoustic, radio session)* — Acoustic + Session

## C. Where it is stored (recommendation)

**Our own table, one set of types per recording.** No tag standard holds a set of version types that players
read (Picard writes none; MusicBrainz keeps them as relationship attributes, not tags), and the owner's rule for
music is *standards where one exists, our tables where none does and Ravilo gains*
(`research-reports/music-tags-in-the-files-2026-09-28.md`). Keyed by the MusicBrainz recording, so the same
recording on an album, a best-of and a box set carries one answer and a change on one copy holds for all; a song
without a match is keyed by itself and never shares. Title and length never link two songs (§A3). Each type remembers **where it came from** (MusicBrainz · the title · you),
and a type you remove stays removed, as an unticked genre does. Writing a copy into the files (a free-text
*version* or *subtitle* field) is left for later — no player found yet reads it.

## D. What to draw

### D1. Album page — Tracks tab

- **Badges** after each song's title: one small chip per type, in the type's colour. A song with no version shows
  nothing. Up to three chips; more fold into *+1*.
- **Editing.** A song's chips open its **side panel** (owner) with the nine types as toggles; each ticked type says where it came
  from (*MusicBrainz* · *the title* · *you*). Unticking an automatic one is kept (*removed by you*), with *Back to
  automatic* on that song. The panel names the other copies — only the same MusicBrainz recording: *the same recording is also on 2 other
  albums — the change applies there too*. Ticking *Session* ticks *Live* with it.
- **Instrumental's detail line:** *Instrumental version of {song}*, linking to the sung song when it is in the
  library. A never-sung piece shows no chip; its lyrics mark says *No words — MusicBrainz*.
- **Many songs at once:** select rows → *Set version…* (add / remove a type on all of them).
- Album header: a quiet summary when the album is mostly one kind (*12 of 14 songs live*) — designer's call.

### D2. Library → Music → Songs

- The same badges in the song rows.
- A **Version** facet: each type with its count, and **No version** (the originals). Each value can be
  **included or excluded** — *Live: hide* is the case that matters (the owner's example: an artist's songs without
  Remix and Live). The active filter reads in words: *Songs by Harbour Lights · without Live, Remix*.
- Bulk *Set version…* on a selection.
- Albums and Artists views: no badges (a version belongs to a song).

### D3. Metadata → a **Versions** tab

Beside Tags and Music genres: the nine types, each with its colour swatch (editable), its one-line meaning
(editable), its count (*114 songs*) opening Library → Songs filtered to it, and how many were set by hand. The list
of types itself is fixed in round 1 (§G Q3).

### D4. Artist page

Song counts by version under the artist's songs (*86 songs · 41 live · 6 remixes*) — designer's call whether this
earns its place; it is the doorway to the filter.

### D5. Dashboard — one row in 285's grammar

**Lyrics on an instrumental** — *These songs have no singing, but have lyrics beside them* (an Instrumental
version, or a piece MusicBrainz says has no words). Count today: **10**
(nine instrumental demos on one box set, one B-side titled *(instrumental)*). The action: **Remove the lyrics** — a
button, pressed by the owner, never run on its own (only sidecars jellystructure wrote; the song is then never given
lyrics again). A second, quieter action, also only by hand: *Tell LRCLIB it is instrumental*. Opens Library →
Songs filtered to *Instrumental · has lyrics*. Severity: warn.

### D6. States

- Unmatched song — only the title can say anything; chips read *from the title*.
- Matched — MusicBrainz and the title together (the union).
- A type set by you, a type removed by you, *Back to automatic*.
- The same recording on three albums, changed on one.
- A song with four types (the fold).

## E. Ravilo, later (not drawn now)

The same chips on song rows, and the filter as a choice before *Shuffle* on an artist (*Without live and remixes*).
Nothing for the viewer in round 1.

## F. Strings (admin, English)

*Version* · *Live* · *Demo* · *Remix* · *Instrumental* · *Cover* · *Acoustic* · *Edit* · *Alternate version* ·
*Session* · *No version* · *Set version…* · *from MusicBrainz* · *from the title* · *set by you* · *removed by you*
· *Back to automatic* · *Instrumental version of {song}* · *No words — MusicBrainz* · *also on {n} other albums — the
change applies there too* · *Lyrics on an instrumental* · *Remove the lyrics*.

## G. Questions

| # | Question | Answer |
|---|---|---|
| Q0 | A piece that was never sung (an intro, a prelude) | **no version** (owner) — it is not Instrumental; MusicBrainz agrees |
| Q1 | The badge: a text chip per type, a coloured dot, one combined chip, …? | **A · words** (owner, 2026-10-01) — one chip per type with its name; also in Ravilo (R344) |
| Q2 | Where the editor opens | **the song's side panel** (owner) |
| Q3 | A fixed list, or can the owner add types? | **fixed for now** (owner); colour and meaning editable |
| Q4 | Is a Session also Live? | **yes** (owner) — ticking Session ticks Live |
| Q5 | The name of the field | ***Version*** (owner) |
| Q6 | The facet's word for songs with no type | ***No version*** (owner) |
| Q7 | Does a change spread to other copies? | **only along a direct link** — the same MusicBrainz recording (owner) |
| Q8 | Lyrics on an instrumental | **removed only by the owner's button** on the Dashboard row (owner) |
| Q9 | Telling LRCLIB | **only by hand**, never automatic (owner) |
