# Phase 292 — A song's version: Live, Remix, Instrumental, Edit …

> Owner, 2026-10-01: *"I would like to have support for adding types of music, like Live, Remix, Instrumental …
> We want to be able to manage and see this information within jellystructure."* And: *"Badges should be part of the
> first implementation."* On the badge's directions: *"Yes lets go with direction A."*

## Status

`Planned`. Written 2026-10-01 (design-authored) from `specs/design-brief-music-song-versions-2026-10-01.md` (owner answered §G) and the mockups built from it:
- `design/app/versions.js` + `versions.css` (`vr-*`), loaded by `album.html`, `library.html`, `metadata.html`, `artist.html` and `index.html`
- the directions canvas `design/app/Song Versions - Directions.html`, where Q1 = **A · words** is picked and B/C are kept as declined

Not dev-reviewed. The number was checked free on `main` (tree `ef52889`, admin tops at 290) on 2026-10-01. **Ravilo's side is R344.**

**Builds on** 275 (the music library), 276 (MusicBrainz, the recording match, genres' override model), 277 (lyrics) and 285 (the Dashboard's row grammar). It borrows the tag model from phase 82/199.

## The nine types (fixed in round 1; Q3)

Each type has a fixed key and an English name, plus a colour and a one-line meaning that the owner can edit.

| Key | Name (chip) | Means | Found from | Household today |
|---|---|---|---|---|
| `live` | Live | recorded at a concert — and every Session | MusicBrainz (`live`) · title | 116 |
| `demo` | Demo | an early, unfinished recording | MusicBrainz (`demo`) · title | 28 |
| `remix` | Remix | someone re-made the recording | MusicBrainz (*remix of*, a remixer) · title | 25 |
| `instrumental` | Instrumental | a sung song without the voice | MusicBrainz (every performance `instrumental`) · title | 12 |
| `cover` | Cover | one artist playing another artist's song | MusicBrainz (`cover`) | 20 |
| `acoustic` | Acoustic | played unplugged | title | 10 |
| `edit` | Edit | the same recording made shorter or longer | title | 8 |
| `alternate` | Alternate version (chip: *Alternate*) | a different take or arrangement | title | 7 |
| `session` | Session | recorded live in a studio for radio, TV or a website | title | 4 |

The default colours are the mockup's (`versions.js` `TYPES`): Live `#f0795b` · Demo `#a3aec6` · Remix `#c67fe3` · Instrumental `#3fb6f5` · Cover `#2dd49a` · Acoustic `#d8ad62` · Edit `#9d95f7` · Alternate `#e9709f` · Session `#f2a65a`.

In total, 193 of 487 songs get at least one type.

## Requirements

### The model

**FR-292-1 — A song has zero, one or many types; a normal song has none and shows nothing.** The types are a set (*Live + Acoustic*, *Remix + Edit*, *Cover + Demo + Instrumental*). Display order is the table's order.

**FR-292-2 — One answer per recording.** A new table, `music_recording_version`, is keyed by the **MusicBrainz recording id** of a matched song. An unmatched song uses its own key, `trk:<track id>`.
- A row holds: `(recording_key, type, source, state)`.
  - `source ∈ { musicbrainz, title, user }`.
  - `state ∈ { on, removed }` (`removed` is only ever `source = user`).
- The same recording on an album, a best-of and a box set has one set of types, and a change on one copy holds for every copy.
- **A change spreads only along this link (owner, Q7).** Title and length never link two songs.
- When an unmatched song gains a match, its `trk:` answers move to the recording, and anything the owner set wins over what the recording already had.

**FR-292-3 — Found automatically; the owner's choice wins and is kept.** On every music scan and every MusicBrainz match (276), the automatic set is rebuilt:
- **MusicBrainz:** the recording's performance attributes (`live`, `demo`, `cover`, `instrumental` when **every** performance is marked so), and a *remix of* relationship or a remixer credit for Remix.
- **The title:** case-insensitive whole-word patterns in brackets or after a dash — *live*, *demo*, *remix*/*… mix*/*reinterpretation*, *instrumental*/*karaoke*, *acoustic*/*unplugged*, *edit*/*radio edit*/*extended*/*single edit*, *alternate*/*alternative version*/*alternate take*, *session*/*radio session*. The list lives in one file, so it can be tuned without a schema change.
- **The union of the two** is the automatic set.
- `user` rows sit on top of the automatic set: `on` adds a type, `removed` takes one away. **A scan never touches a `user` row** (the genres override, 276 FR-276-7; JS tags, 199).

**FR-292-4 — Session is also Live (owner, Q4).**
- Ticking Session, by hand or automatically, also ticks Live.
- *Without Live* hides sessions too.
- Unticking Live on a Session leaves Session on. That is allowed, and *without Live* then still hides it, because Session implies Live in the filter.

**FR-292-5 — A piece that was never sung has no version (owner, Q0).**
- MusicBrainz marks such a piece as a work with no words (`zxx`). It is **not** Instrumental and shows no chip.
- Its lyrics mark says *No words — MusicBrainz*.
- The lyrics step (277) **never fetches** lyrics for it, and never fetches them for a song whose automatic set includes Instrumental from MusicBrainz.

### Album page (`album.html` → Tracks)

**FR-292-6 — Badges are words (Q1 = A).** One chip per type sits after the song's title. Each chip:
- shows the type's short name in the type's colour (text mixed toward the theme's ink, a 15 % tint behind, a 42 % border)
- is 11 px Sora 700, with a 5 px radius

**Up to three chips; more fold into *+N*** (a dashed chip). A song with no version has no chip.

**FR-292-7 — The side panel (owner, Q2).** Clicking a song's chips, or the hover action *＋ Version* on a song that has none, opens the shared right-hand panel (the *Find match…* panel's frame). It shows:
- **The song's header:** title, artist · album · track N.
- **The nine types as rows:** a tick, the name, where it came from (*from MusicBrainz* · *from the title* · *set by you* · *removed by you* · *with Session* on a Live that Session added) and the meaning.
- **Saving:** a tick saves at once (*Saved as you tick · kept across runs*).
- ***Back to automatic*** in the footer, shown only when this recording has `user` rows. It deletes them.
- **Notes, when they apply:**
  - *Not matched yet* — only the title can say anything.
  - *No words — MusicBrainz*
  - *The same recording is also on {n} other albums — the change applies there too*, naming the albums.
  - *Lyrics beside a song with no singing* — links to the Dashboard row.
- **Instrumental's line:** *Instrumental version of {song}*. It links to the sung song when that song is in the library, found through MusicBrainz's *instrumental version of* link and never by title. Otherwise it names the song and its artist without a link.

**FR-292-8 — Many songs at once.**
- In the Tracks list, a row's number becomes a checkbox on hover. Ticking one shows the selection bar with ***Set version…***.
- The dialog lists the nine types, each with *N of M have it* and **Leave · Add · Remove** (default *Leave*).
- *Add Session* adds Live with it.
- When copies on other albums will change too, a note says how many.
- Apply writes `user` rows for every recording in the selection.

**FR-292-9 — The album header summary.** When at least half of an album's songs share a type, the header's facts line gains one quiet phrase: *Live · all 14 songs* or *Live · 12 of 14 songs*. Otherwise there is no phrase.

### Library → Music → Songs

**FR-292-10 — Badges in the song rows**, folding as in FR-292-6. Albums and Artists views show no badges, because a version belongs to a song.

**FR-292-11 — The Version facet.** The facet lists the nine types and ***No version*** (*the originals*), each with its count. Each value has **Only** and **Hide**:
- **Only** keeps songs that have any of the *Only* values.
- **Hide** removes songs that have any of the *Hide* values. Hide wins over Only.
- Counts reflect the other active facets.

The active filter reads in words above the list, for example *Songs by Harbour Lights · without Live, Remix* or *Songs · only Instrumental*.

The URL carries the filter:
- `vi=` — Only, comma-separated keys or `none`
- `vx=` — Hide
- `artist=` — the artist
- `lyr=1` — *has lyrics*, used by the Dashboard row

**FR-292-12 — Bulk *Set version…*** on a selection in Songs, using FR-292-8's dialog.

### Metadata → Versions

**FR-292-13 — A *Versions* tab** beside *Tags* and *Music genres*. It has one row per type:
- the colour swatch (click to pick from the nine-colour palette)
- the chip
- the meaning (an inline field, saved on blur)
- *Found from*
- the household count (*116 songs →*, opening Songs filtered to it)
- *By you* (*3 set · 1 removed*)

A last row reads *No version — an ordinary recording, and a piece that was never sung*, with its count. The list of types itself cannot be edited in round 1 (Q3).

### Artist page

**FR-292-14 — The doorway line.** Under the artist's songs: *86 songs · 41 live · 6 remixes*. Each part opens Songs filtered to this artist and that type.
- Only types with at least one song are listed.
- When the artist has Live or Remix songs, the line ends with ***songs without Live and Remix →*** (`vx=live,remix`). This is the owner's example, and it is the admin's half of R344's Shuffle filter, which comes later.

### Dashboard

**FR-292-15 — *Lyrics on an instrumental* (285's grammar, warn).**
- **The row:** *These songs have no singing, but have lyrics beside them*. It counts songs (10 today) whose lyrics sidecar or embedded lyrics exist while the song is Instrumental (any source) or is a no-words piece (FR-292-5).
- **Opens:** Songs filtered to `vi=instrumental&lyr=1`, with the no-words pieces included.
- **Action 1:** ***Remove the lyrics*** is a button, pressed by the owner and **never run on its own** (Q8). It deletes only sidecars jellystructure wrote (277's record), never embedded tags and never files it did not write. Each song is then marked so the lyrics step never gives it lyrics again.
- **Action 2:** quieter, also by hand only: ***Tell LRCLIB it is instrumental*** (Q9). It sends LRCLIB's instrumental flag for each song and is never automatic.
- When the count is 0 there is no row (285: zero is silence).

### Strings (admin, English)

*Version* · the nine names · *No version* · *the originals* · *Set version…* · *＋ Version* · *Leave* · *Add* · *Remove*
· *from MusicBrainz* · *from the title* · *set by you* · *removed by you* · *with Session* · *Back to automatic*
· *Saved as you tick · kept across runs* · *Instrumental version of {song}* · *No words — MusicBrainz* · *The same
recording is also on {n} other albums — the change applies there too* · *Only* · *Hide* · *without {types}* · *only
{types}* · *Lyrics on an instrumental* · *Remove the lyrics* · *Tell LRCLIB it is instrumental* · *songs without Live
and Remix →*.

## Non-goals

- Writing a version into the music files. No player found yet reads a free-text *version* or *subtitle* field (brief §C). 284's tag writing leaves it alone.
- Owner-defined types (Q3).
- Versions on films, music videos or audiobooks.
- Ravilo (R344).

## Acceptance

1. ***Salt on the Window (live at the harbour, 2011)***, a single recording that is on three albums, shows **Live** on all three. Unticking Live on one copy unticks it on all three, and the panel said so beforehand. *Back to automatic* brings it back on all three.
2. ***Salt on the Window*** (the studio song) shows no chip, and editing the live copy never changes it, although the titles start alike.
3. ***Northern Line (Lighthouse Keepers remix) (extended)*** shows **Remix · Edit** (Remix *from MusicBrainz* and *from the title*, Edit *from the title*).
4. ***Fog Bank*** shows **Cover · Demo · Instrumental**, plus *Instrumental version of Fog Bank — The Ferrymen* with no link (not in the library).
5. ***Northbound (acoustic, alternate take, radio session)*** shows **Live · Acoustic · Alternate +1** in a row, with all four types and *Live (with Session)* in the panel.
6. ***Prelude*** shows no chip and *No words — MusicBrainz*, and a rescan never fetches lyrics for it.
7. Ticking Session on an unmatched song ticks Live too. A rescan keeps both, and after a match they belong to the recording.
8. ***Songs · Hide Live, Remix*** for *Harbour Lights* lists no live, session or remix songs. The artist page's *songs without Live and Remix →* opens the same list.
9. The Dashboard's *Lyrics on an instrumental* row counts 10. *Remove the lyrics* deletes only our sidecars, and the count goes to 0 and the row disappears. Nothing was removed before the button was pressed.
10. Changing Live's colour in Metadata → Versions recolours every Live chip in the admin and in Ravilo (R344).

## Open questions (for the dev review)

1. **Title patterns:** where do they live? The lean is a data file beside 276's ladder, read at scan time, with no UI.
2. **Bracketed live dates:** *(live at the harbour, 2011)* is caught by *live*. Should *(Radio 2 session)*-style names be caught by a list of broadcaster words, or only by *session*? The lean is *session* only, since the household has 4.
3. **LRCLIB's flag:** does LRCLIB's API accept an *instrumental* submission without a token from a published client? If not, the action opens LRCLIB's page for the song instead.
4. **The *Only* for no-words pieces:** they have no version, so they appear under *No version*. Should the Dashboard row's filter also show them? The lean is yes, with its own sub-line *no words*.
5. **Counts and Session:** should Live's count in Metadata include songs that are Live only through Session? The lean is yes, matching the filter.

## Dev notes

- **Stand-ins** (all fictional, added by `versions.js` on the admin pages): albums *Live at the Harbour* (2011), *Tide Tables 1999–2012* (box set), *Nordic Nights Vol. 2*, and extra tracks on *Signal Found*, *Kite Weather* and *Kvøld*.
- **What each finder says** for a stand-in is the `VS` table. Your ticks are in localStorage `js-ver-ovr`, keyed like FR-292-2. Colours and meanings are in `js-ver-col`.
- The Dashboard row is `m-instlyr` in `dashboard-data.js`, with `act2` in `dashboard.js` for the second action.
