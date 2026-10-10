# Phase 292 — A song's version: Live, Remix, Instrumental, Edit …

> Owner, 2026-10-01: *"I would like to have support for adding types of music, like Live, Remix, Instrumental …
> We want to be able to manage and see this information within jellystructure."* And: *"Badges should be part of the
> first implementation."* On the badge's directions: *"Yes lets go with direction A."*

## Status

`⚠ Partial` 2026-10-01 (see *Build notes*: everything built; *Tell LRCLIB* not tried against the live API) · **Dev-reviewed 2026-10-01** against `main` `44e26871` (see *Dev review* below). Written 2026-10-01 (design-authored) from `specs/design-brief-music-song-versions-2026-10-01.md` (owner answered §G) and the mockups built from it:
- `design/app/versions.js` + `versions.css` (`vr-*`), loaded by `album.html`, `library.html`, `metadata.html`, `artist.html` and `index.html`
- the directions canvas `design/app/Song Versions - Directions.html`, where Q1 = **A · words** is picked and B/C are kept as declined

Dev-reviewed 2026-10-01. The number was checked free on `main` (tree `ef52889`, admin tops at 290) on 2026-10-01. **Ravilo's side is R344.**

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
- **Session ⇒ Live is a rule of the automatic set only (owner, 2026-10-01).** A Session found automatically brings an automatic Live (*with Session*). A Session ticked by hand writes Live on by hand at the same moment. The owner's *removed* Live always wins, so a song can show **Session without Live**. The chips (here and in R344) show that set as it is: *Session*, no *Live*.
- **Two readings of Live, each in one place.** What a song *shows* (chips, the panel, the album summary, R344's `versions`) is the set as stored. What a filter *matches* treats Session as Live: the Version facet's *Only Live* and *Hide Live*, Live's counts in Metadata and on the artist line, and every link that opens a filtered list. A count that opens a list always uses the filter's reading, so the number and the list agree.

**FR-292-5 — A piece that was never sung has no version (owner, Q0).**
- MusicBrainz marks such a piece as a work with no words (`zxx`). It is **not** Instrumental and shows no chip.
- Its lyrics mark says *No words — MusicBrainz*.
- The lyrics step (277) **never fetches** lyrics for it, and never fetches them for a song whose set includes **Instrumental from any source**: MusicBrainz, the title or the owner's tick (owner, 2026-10-01). When the owner removes Instrumental, the song is fetched again on the next run.

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
5. **Counts and Session:** should Live's count in Metadata include songs that are Live only through Session? The lean is yes, matching the filter. **Answered: yes** (Dev review item 7).

## Dev notes

- **Stand-ins** (all fictional, added by `versions.js` on the admin pages): albums *Live at the Harbour* (2011), *Tide Tables 1999–2012* (box set), *Nordic Nights Vol. 2*, and extra tracks on *Signal Found*, *Kite Weather* and *Kvøld*.
- **What each finder says** for a stand-in is the `VS` table. Your ticks are in localStorage `js-ver-ovr`, keyed like FR-292-2. Colours and meanings are in `js-ver-col`.
- The Dashboard row is `m-instlyr` in `dashboard-data.js`, with `act2` in `dashboard.js` for the second action.

## Dev review (2026-10-01, against `main` `44e26871`)

Read against the music backend (275–290: `Music.sq` and `59.sqm`–`63.sqm`, `MusicBrainzClient`, `MusicMatchService`,
`MusicMediaService`'s lyrics step, `MusicBrowse`, `MusicTagWriter`, `MusicTvService`), the admin pages
(`MusicAlbum.kt`, `MusicLibrary.kt`, `MusicArtist.kt`, `Metadata.kt`, `Dashboard.kt`), the Dashboard and triage
routes, the lyrics research (`research-reports/music-lyrics-that-do-not-belong-2026-10-01.md`) and the mockups
(`design/app/versions.js`, `dashboard-data.js`, `dashboard.js`). MusicBrainz's relationship list was checked on
musicbrainz.org the same day. **The direction holds.** MusicBrainz already says everything the spec wants, but today
the client asks it for none of it, and two things the spec leans on do not exist: a record of which lyrics sidecars
jellystructure wrote, and a Dashboard row with two actions. One shipped bug was found on the way (item 10). Twenty
items; two needed the owner (items 7 and 8c) and both are now decided (see *Owner decisions* at the end). Everything
else is the build's.

1. **MusicBrainz is never asked for versions today.** `MbRelation` holds only `type` and `url`
   (`MusicBrainzClient.kt:29`). The release lookup asks for `recordings+media+labels+artist-credits+release-groups`
   and no relationships (`:195–196`); the recording lookup asks for none either (`:205–206`). What to fetch:
   - `release(id)` gains `recording-level-rels+work-rels+artist-rels+recording-rels`. This is the lean in the lyrics
     research (idea A), and it costs no extra request where a release id is already known (`MusicMatchService.kt:225`).
   - `MbRelation` gains `attributes`, `direction`, `target-type`, and the target objects `work` (with `language` and
     `languages`), `recording` and `artist`. `MbRecording` gains `disambiguation` and `relations`.
   - **Never add relationships to `releasesOf`.** It is a browse of up to 25 pressings with every track
     (`:192–193`). When `applyMatch` picks the best pressing from that browse (`MusicMatchService.kt:227`), it makes one
     more `release(id)` lookup for the chosen pressing only.
   - A recording chosen by hand (`useRecording`, `MusicMatchService.kt:378`) is not on the release. It needs
     `recording(id)?inc=work-rels+artist-rels+recording-rels`.
   - Measure the answer for the household's nine-disc box set before relying on one request per release.

   Checked on musicbrainz.org (2026-10-01): the recording–work *performance* relationship's attributes are `live`,
   `cover`, `demo`, `instrumental`, `karaoke`, `medley`, `partial` and `acappella`. The recording–recording types
   include `remix` (*remix of*), `instrumental` (*instrumental version of*), `karaoke` (*karaoke version of*) and
   `edit` (*edit of*). So the table's MusicBrainz sources all exist, including `demo`.

2. **The rule per type, written down.** FR-292-3 says *every* for Instrumental and leaves the others open. The build's
   rule, from one performance-relationship list per recording:
   - **Live, Demo, Cover:** any performance relationship carries the attribute.
   - **Instrumental:** the recording performs at least one work that has words (a language other than `zxx`), and
     every such performance is marked `instrumental` or `karaoke`. Performances of `zxx` works are left out of the
     count, so a sung song with an instrumental intro piece is not Instrumental (the research's four live
     recordings). An *instrumental version of* or *karaoke version of* relationship from this recording also counts.
   - **No words (FR-292-5):** the recording has performance relationships and every one points at a `zxx` work.
   - **Remix:** a *remix of* relationship from this recording, or a `remixer` artist relationship on it.
   - **Edit:** an *edit of* relationship from this recording, as well as the title. MusicBrainz has it and it comes in
     the same answer, so the table's *Found from* for Edit becomes *MusicBrainz · title*. Not an owner question.
   - **The recording's disambiguation** (*instrumental demo*, *live*) is read by the title finder, with source
     `musicbrainz`. The research's own case was named only there.
   - **The album's release-group types (Live, Remix, Demo) are not used.** They describe an album, and the owner's
     rule is one recording at a time (Q7). A live album with studio bonus tracks would otherwise mark them Live.

3. **Store the facts and the owner's choices; compute the rest.** FR-292-2 stores `musicbrainz` and `title` rows. Those
   are derived data: a scan must rewrite them, and a `title` row goes stale the moment a title changes. The lean is
   migration **64** (the last is `63.sqm`), mirrored in a new `MusicVersions.sq`:
   - `music_recording_facts(recording_mbid PK, json, fetched_at)`: the attributes, the no-words verdict, *instrumental
     version of* (target id, title, artist), *remix of*, the disambiguation.
   - `music_version_choice(recording_key, type, state, set_at, PK(recording_key, type))`, with `state ∈ {on, removed}`.
     Only the owner's rows live here.
   - `music_version_type(key PK, color, meaning)`: only the overrides of the nine defaults.

   The title part is computed at read time from the title of **every copy** of the recording. The spec does not say
   whose title counts when copies differ; the union of all copies is the only answer that keeps *one answer per
   recording*. Load the three tables into `MusicStore.Snapshot` (`MusicStore.kt:31`) like the other music rows.

   **One pure function** in commonMain's model, beside `originalDate()` (`Music.kt:225`):
   `MusicVersions.of(track, copies, facts, choices) → (keys, source per key)`. The album page, Songs, the facet,
   Metadata's counts, the Dashboard row, the lyrics step and `MusicTvService` (R344) all call it. That is 290's shape:
   one fact, one function, every reader. Use explicit key prefixes, `rec:<mbid>` and `trk:<track id>`, as the mockup
   does (`versions.js` `key()`), so a key's kind never depends on its shape.

4. **Only a track that agrees, or was chosen by hand, shares its recording's answer.** `applyMatch` gives a track the
   release's recording id even when its length disagrees (`MusicMatchService.kt:250`). `DISAGREES` means *often another
   version — a single's, a live cut* (`Music.kt:211`). Keying such a track by that recording would spread the studio
   recording's answer to a live cut, which is what the owner ruled out (Q7). Rule: `AGREES` and `MANUAL` tracks use
   `rec:`; a `DISAGREES` track keeps `trk:` until *Match this track…* makes it `MANUAL`. Its MusicBrainz facts are not
   used either. (Outside this phase: 284 writes that same id into the file, `MusicTagWriter.kt:129`. Worth a look.)

5. **When the facts are read.** FR-292-3 says *on every music scan and every match*. A `missing`-scope run never
   revisits a matched album (`MusicMatchService.kt:156`), so today's matched albums would never get facts. Lean: no new
   pipeline step. Inside `match_musicbrainz`, after the album pass, a catch-up reads facts for every matched album
   that has none (`versionFactsAt` in the album's JSON; no migration). That is one lookup per album, about 30 here, once,
   at MusicBrainz's 1 per second. A `scope = all` run refreshes them. The title part needs no step at all. The step
   order already puts the match before `fetch_lyrics` (`AppConfig.kt:191`), which FR-292-5 needs.

6. **Moving `trk:` choices, and the cases the spec does not name.**
   - On a match (`applyMatch`, `MusicMatchService.kt:245–254`) and on *Match this track…* (`:378`): move the track's
     `trk:` rows to its `rec:` key in the same write. If two unmatched copies carried different choices for one type,
     the newest `set_at` wins.
   - On *Clear match* (`:346–357`): the rows stay on the recording. The track falls back to its own `trk:` key, which is
     empty, and a later re-match finds them again. Nothing is copied back.
   - *Convert…* (`MusicConvert`) makes a new file, so Jellyfin gives it a new item id. Copy the original's `trk:` rows
     to the new id in the convert's own bookkeeping.
   - A file moved or renamed by hand also gets a new id. An unmatched song's choices are lost then. Accept and say so.
   - Record every change in the History of each album that holds a copy (`music_versions`), as `setGenres` does
     (`MusicMatchService.kt:359–364`).

7. **Session ⇒ Live: the spec and the mockup disagreed — decided (owner, 2026-10-01): Session stays.** FR-292-4 says
   unticking Live on a Session leaves Session on. The mockup could not reach that state: `list()` adds Live whenever
   Session is there, `setType` unticks Session when Live is unticked, and the bulk dialog's *Remove Live* sets *Remove
   Session* too (`versions.js`, the `data-vbp` handler). The owner declined that coupling. What the build does:
   - `MusicVersions.of` applies Session ⇒ Live **inside the automatic set only**, before the owner's rows. An automatic
     Live that came only from Session has the source *with Session*. A `removed` Live then removes it like any other.
   - Ticking Session by hand (the panel, *Set version…*) writes Live `on` in the same write. Unticking Live writes Live
     `removed` and leaves Session alone. *Remove Live* in the bulk dialog no longer sets *Remove Session*.
   - The function answers two things: the **shown** set (chips, the panel, the album summary, R344) and a
     `matches(type)` for filters, where Session counts as Live. The Version facet's *Only Live* and *Hide Live*,
     Live's facet count, Metadata's Live count, the artist line's *N live* and its *songs without Live and Remix →*
     all use `matches`. So a Session-without-Live song shows only *Session*, yet *Hide Live* hides it and *Only Live*
     keeps it.
   - The album summary counts the shown set, so a Session-without-Live song does not count toward *Live · N songs*.
   - Open question 5: yes, through `matches`.
   - The mockup's `versions.js` (`list`, `setType`, the bulk handler) should follow on the next design pass.

8. **Lyrics: four things FR-292-5 and FR-292-15 assume that do not exist.**
   - **(a) No record of which sidecars are ours.** `MusicTrack` keeps only `lyricsState` and `lyricsCheckedAt`
     (`Music.kt:285–286`). `fetchLyrics` writes a file and forgets it (`MusicMediaService.kt:168–176`). The research
     says the same (§2). Build its idea B: `lyricsSource`, the LRCLIB id and a hash of the written file on the track
     (JSON, no migration), set in `fetchLyrics`. Backfill: a sidecar whose file time is at or after the track's `lyricsCheckedAt` was
     written by a fetch run (439 of 439 here, per the research). *Remove the lyrics* deletes a file only when its hash
     still matches.
   - **(b) A "never again" mark.** Add `MusicLyrics.BLOCKED` (`Music.kt:294–302`). `fetchLyrics` skips it.
     `lyricsStateOf` reports it.
   - **(c) Which Instrumental stops the fetch — decided (owner, 2026-10-01): any.** The spec skipped only
     *Instrumental from MusicBrainz* (the brief's §A6); FR-292-5 now says any source. `fetchLyrics`
     (`MusicMediaService.kt:155`) skips a song whose shown set has Instrumental (MusicBrainz, the title, or the owner's
     tick), and every no-words piece. When the owner removes Instrumental, the next run fetches the song again (its
     `lyricsCheckedAt` is cleared with the change, so the 30-day wait does not apply). A `BLOCKED` song stays blocked
     either way: that is the owner's separate *Remove the lyrics*.
   - **(d) Ravilo would keep showing them.** The viewer's lyrics route asks Jellyfin first and falls back to our file
     (`MusicTvService.kt:295–308`). A deleted sidecar keeps showing until Jellyfin refreshes the song, and an embedded
     lyric forever. Rule: for a `BLOCKED` song, a no-words piece or an Instrumental, the route answers 404 and
     `has_lyrics` is false (`MusicTvService.kt:132`). *Remove the lyrics* then refreshes the albums in Jellyfin
     (`refreshInJellyfin`, `MusicMediaService.kt:77`).
   - **(e) The count.** Jellyfin's `hasLyrics` (`Music.kt:271`) covers embedded and sidecar lyrics but only changes at
     the next `scan_music`. The row counts songs that have lyrics (our sidecar, or Jellyfin's flag), have no singing,
     and are not `BLOCKED`. After the button they are `BLOCKED`, so the count is 0 at once (acceptance 9). An embedded
     lyric stays in the file, as the spec says, and (d) keeps it off the phone.

9. **A row with two actions is a change to 285's grammar.** `DashboardRow` documents *at most one action*
   (`Dashboard.kt:40–66`, model), and the page draws one button (`ui/Dashboard.kt:447`). Add `action2` and
   `action2_id` to `DashboardRow` (admin-only DTO, same release, nothing else reads it) and two handlers beside
   `fetch_artwork` (`ui/Dashboard.kt:467–477`): `music_lyrics_remove` and `music_lyrics_lrclib`. The row itself is a
   new triage type, `music_instrumental_lyrics`, in `musicTriageCounts` (`TriageRoutes.kt:522–562`) with a `Spec`
   beside the other music rows (`DashboardRoutes.kt:71–77`), `fix = "here"`. Say in this spec that it amends
   FR-285-2's one-action rule for this row; the owner already drew the second action (Q9).

10. **Shipped bug: every music and audiobooks row on the Dashboard opens the library unfiltered** (spec'd as **293**). The row links to
    `/library?kind=music&filter=<key>` (`DashboardRoutes.kt:111`). `renderMusicLibrary` reads only `mview`, `q`, `sort`
    and `f.*` (`MusicLibrary.kt:44–49`); `AudiobookLibrary.kt:63` does the same. So *Albums need a match*, *Songs a
    phone plays only by re-encoding* and the rest all land on the plain Artists view. Fix it first: teach the music
    browse a `filter=` triage key (or map each key to its facets). It also **answers open question 4**: link the new
    row to `filter=music_instrumental_lyrics`. A triage filter can include the no-words pieces; no Version facet value
    can, because they are *No version*. Lean yes, with the sub-line *no words* on those songs.

11. **The Version facet needs *Hide*, which the facet model does not have.** Today's facets are OR within a facet and AND
    across (`MusicBrowse.kt:227–245`); `MusicFacetValue` has only `on` (`MusicApi.kt:173–180`). Add:
    - `off` on `MusicFacetValue`, an `excluded` map in `MusicBrowse.browse`, and `x.<key>=` in the URL beside the
      existing `f.<key>=` (`MusicLibrary.kt:41/49`, `api/MusicApi.kt:194`). So the spec's `vi=` / `vx=` become
      `f.version=` / `x.version=`: one convention, and *Hide wins* is one line in the filter.
    - **`lyr=` is not needed.** `f.lyrics=has` already exists (`MusicBrowse.kt:46`). The mockup's Dashboard link even
      says `lyr=has` (`dashboard-data.js:59`) where the spec says `lyr=1`.
    - **`artist=` is new.** There is no artist filter in the browse today. Songs where the artist is credited on the
      track or is the album's artist.
    - The facet is offered in the Songs view only. The sentence above the list (*Songs by … · without Live, Remix*) is
      built by the server, so the page derives nothing.
    - The Library already has an album-level *Album type* facet with a value *live* (`MusicBrowse.kt:45`). Keep both;
      label them *Album type* and *Version* so they read apart.

12. **The selections FR-292-8 and FR-292-12 need do not exist.** The Library's selection is albums only
    (`MusicLibrary.kt:176`) and `MusicBulkRequest` takes album ids (`MusicApi.kt:360`). The album's Tracks rows have no
    checkbox (`MusicAlbum.kt:319`). Build a song selection on both, and one route,
    `POST /api/music/versions/bulk { track_ids, add, remove }`. It resolves each track's key, writes each recording
    once, in one transaction, and records one History line per album touched.

13. **Routes and DTOs (admin only, all additive).**
    - `MusicTrackRow` and `MusicSongRow` gain `versions: List<String>` (`MusicApi.kt:221–238`, `:268–285`).
    - `GET /api/music/track/{id}/versions`: the panel. Per type: on or off, and its source. Notes: unmatched, no words,
      the other copies (album id and title), lyrics beside no singing. *Instrumental version of*: a track id when it is
      in the library, else title and artist.
    - `PUT /api/music/track/{id}/versions/{type}` with `{ on }`. Session on also writes Live on (item 7).
    - `DELETE /api/music/track/{id}/versions`: *Back to automatic*.
    - `GET` and `PATCH /api/music/version-types`: colour and meaning. Saved as the owner edits, like JS tags
      (`JsTagStore.kt`, `MetadataRoutes.kt:403–413`). The Metadata page is not Settings, so the top-Save rule does not
      apply.
    - `MusicAlbumPageDto` gains the header phrase (FR-292-9); `MusicArtistPageDto` gains the per-type counts
      (FR-292-14), on the existing *N songs in the library* line (`MusicArtist.kt:122`).
    - Metadata: `"versions"` joins `TAB_LABELS` after `"musicgenres"` (`Metadata.kt:23`).
    - Port `vr-*` from `design/app/versions.css` into the served stylesheet and run both CSS check scripts.

14. ***Instrumental version of {song}* exists only when MusicBrainz has the link.** It comes from the recording's
    *instrumental version of* relationship, stored with the facts. Never infer it from the work: two recordings of one
    work are not each other's versions. The relationship's target recording carries an id and a title; an artist credit
    for a target outside the library is not in that answer, so read it once, at fact time, with `recording(id)` (at most
    12 here). Acceptance 4 assumes its fixture has the link; without it, the line is absent.

15. **Title patterns (open questions 1 and 2).** One Kotlin object beside `MusicScoring`, with a unit test fed the
    household's title shapes. A data file read at scan time adds a parse and a failure path, and buys nothing: the list
    ships with the server either way, and no schema change is needed in both cases. Exclusions the test must hold:
    *original mix*, *album version* and *remaster(ed)* are not Remix or Edit. Add the Danish *akustisk* for Acoustic.
    **Open question 2:** *session* only. Agree.

16. **On a light theme, the chip text fails 4.5 : 1.** Computed for the formula in FR-292-6 (58 % colour mixed in OKLCH
    toward the ink, over a 15 % tint), with Ravilo's Daylight ink `#15171f`: Cover reads 4.28 : 1 on `#fbfbfd` and
    3.99 : 1 on a `#f1f2f6` card; Session 4.50 and 4.20. At 50 % the worst case is 4.57 on `#e8eaf0`; at 45 %, 5.19. Lean:
    58 % on dark themes, **45 % on light ones**, in the admin's Light theme and in Ravilo (R344's acceptance 5). On the
    dark themes every type is above 8.7 : 1.

17. **Small differences between the spec and the mockup. Take the spec.**
    - The album summary: the mockup needs 60 % of the songs (`versions.js` `albumSummary`), the spec half. The mockup
      also never names Session in the phrase; keep that, since Live already says it.
    - The chip: the mockup's `.vr-b` is `.64rem` (about 10.2 px), the spec 11 px.

18. **Not writing versions into the files is right (non-goal confirmed).** The tag writer writes the title from
    `track.title`, the file's own (`MusicTagWriter.kt:116`), so the title finder keeps its source after a tag write.
    The recording id is already written (`:129`), so the key travels with the file. The nearest standards are the
    Vorbis `VERSION` comment (free text, FLAC and Ogg only), ID3's `TIT3`, and Picard's album-level `RELEASETYPE`. None
    holds a set of types, and no player found reads one. That matches the owner's rule: a standard where one exists, our
    table where none does and Ravilo gains.

19. **A stale sentence on the Library page.** It says what the page maintains is written to `album.nfo` /
    `artist.nfo`, *never into the files* (`MusicLibrary.kt:112`). Since 284 that is false whenever *Write tags into
    music files* is on. Fix it in the same change.

20. **Build order.** Each step can ship on its own.
    (a) Item 10's filter links.
    (b) The client fields, migration 64, `MusicVersions.of` and its tests, and the catch-up in the match step (items
        1–6).
    (c) The album page: chips, the panel, the routes.
    (d) Songs: chips, the facet with *Hide*, the selection, *Set version…*.
    (e) Metadata → Versions and the colours.
    (f) The artist line.
    (g) Lyrics provenance, `BLOCKED`, the route rule, the Dashboard row and *Remove the lyrics* (item 8).
    (h) *Tell LRCLIB* last: it is the only part that writes outside the house.
    R344 can start after (b).

**Open question 3 (LRCLIB).** LRCLIB's documented publish API needs no account: the client asks for a challenge, solves
a small proof of work, and sends the token with the publish. A publish with both lyrics fields empty marks the track
instrumental. It adds an entry; it does not correct the copied one, and whether LRCLIB's `get` then prefers the new entry
is unknown. Verify both against the live API before building step (h). If it cannot be done, the action opens the
song's LRCLIB search page instead, as the spec says.

### Owner decisions (2026-10-01)

1. **Session stays when Live is unticked** (FR-292-4, item 7). The mockup's coupling is declined. Session ⇒ Live is a
   rule of the automatic set only; a Session ticked by hand writes Live with it; the owner's removed Live always wins.
   A song can therefore show *Session* without *Live*. Its chips (here and in R344) show only *Session*. Every filter
   and every count that opens a filtered list treats it as Live: *Hide Live* hides it, *Only Live* keeps it, and
   Live's counts in the facet, in Metadata and on the artist line include it.
2. **Any Instrumental blocks the lyrics fetch** (FR-292-5, item 8c): from MusicBrainz, from the title or from the
   owner's tick, and every no-words piece too. Removing the Instrumental mark lets fetching resume on the next run.

With these, nothing in 292 is left for the owner. Item 10's shipped bug is spec'd separately as **293**.

## Build notes (2026-10-01)

Built in the dev review's order, (a) to (h). Commits: `33e17542` (293, item 10), `f69d3588` (the data layer and
`MusicVersions.of`), `d6436271` (R344's server half), `3720e8d4` (facts, routes, lyrics), `0cb0ac50` (the admin pages).

**Model and storage (items 1–6).**
- Migration **64** (`64.sqm` / `MusicVersions.sq`): `music_recording_facts(recording_mbid, json, fetched_at)`,
  `music_version_choice(recording_key, type, state, set_at)` (only the owner's rows; `on` / `removed`),
  `music_version_type(key, color, meaning)` (only overrides). Loaded into `MusicStore.Snapshot`; each snapshot builds
  one `MusicVersionIndex` (answers per key, copies per key).
- `MusicVersions.of(track, copies, facts, choices)` in commonMain (`model/MusicVersions.kt`) is the one function:
  MusicBrainz facts (only for a `rec:` key) ∪ every copy's title, the disambiguation read by the title finder as
  MusicBrainz, no automatic Instrumental on a no-words piece, Session ⇒ Live **inside the automatic set only**
  (source `session`, *with Session*), then the owner's rows. It answers the **shown** set (chips, panel, album
  phrase, R344) and `matches(type)` (Session counts as Live; `none` = nothing shown) for every filter and count.
  `blocksLyrics` = no words, or Instrumental from any source (Owner decision 2).
- Keys: `rec:<mbid>` for `AGREES` / `MANUAL`, else `trk:<track id>` (item 4).
- The title finder (`MusicTitleVersions`, same file — a Kotlin object, not a data file, item 15): brackets and what
  follows a spaced dash; *original mix*, *album / stereo / mono mix*, *album version*, *remaster(ed)*, *edition* are
  not versions; *akustisk* is Acoustic; *session* only (open question 2).
- MusicBrainz (item 1): `MbRelation` gained `direction`, `target-type`, `attributes`, `work` (with `language` /
  `languages`), `recording`, `artist`; `MbRecording` gained `disambiguation` and `relations`. New calls:
  `releaseWithRels` (`recording-level-rels+work-rels+artist-rels+recording-rels`, one release only, never on
  `releasesOf`), `recordingRels` (a recording chosen by hand), `recordingCredit` (the artist of an *instrumental
  version of* target outside the library, item 14). The relationship shapes were checked against musicbrainz.org's
  live JSON on 2026-10-01 (read-only GETs while building, none from tests): *remix* / *edit* point `forward` from the
  remix/edit; *instrumental* / *karaoke* point `backward` from the instrumental (the type reads "has instrumental
  version"). `MusicVersionFacts` applies item 2's rules.
- When facts are read (item 5): `applyMatch` reads them after every match (one more request, usually a cache hit),
  and `match_musicbrainz` catches up every matched album without `versionFactsAt` (a locked album too) at the end of
  an unscoped pass. A `scope = all` pass re-applies every match, so it re-reads them.
- Moving ticks (item 6): on a match and on *Match this track…* a song's `trk:` rows move to its `rec:` key (newer
  `set_at` wins). A cleared match leaves them on the recording. *Convert…*: the scan that first sees the new `.m4a`
  (a new Jellyfin id, same folder and base name as a song that just went missing) copies the old song's `trk:` rows to
  it — deviation: done in `scan_music`, since the convert job never learns the new id. A file moved or renamed by hand
  loses an unmatched song's ticks (accepted). Every change is a `music_versions` History line on each album holding a
  copy.

**Admin pages (items 11–13, 16–17).**
- Routes: `GET /api/music/track/{id}/versions` (the panel), `PUT …/versions/{type} {on}`, `DELETE …/versions`
  (*Back to automatic*), `POST /api/music/versions/preview` and `/bulk` (`track_ids`, `add`, `remove`; each recording
  once, one transaction), `GET` / `PATCH /api/music/version-types` (saved as the owner edits). A tick that only
  restates the automatic set writes nothing (it deletes the opposite tick), so *By you* counts only real choices.
- Album → Tracks: chips after the title (A, words, three then a dashed *+N*), *＋ Version* on hover, the number turns
  into a checkbox → *Set version…* (Leave / Add / Remove × 9, *N of M have it*, the copies note; *Add Session* adds
  Live, *Remove Live* no longer removes Session), the side panel (sources, *with Session*, *removed by you*, notes,
  *Instrumental version of*), the header phrase (half the songs, from the shown set), *No words — MusicBrainz* and a
  warn mark on lyrics beside no singing in the Lyrics column.
- Library → Music → Songs: chips, the **Version** facet (Songs view only; nine types + *No version*, each with *Only*
  and *Hide*, counts against the other facets; `f.version=` / `x.version=`, *Hide wins*), `artist=`, the filter in
  words built by the server, a song selection with *Set version…*. The page also reads the mockup's `vi=` / `vx=`.
  `lyr=` was not built (dev review 11: `f.lyrics=has` exists; the Dashboard row uses `filter=`).
- Metadata → **Versions** tab: swatch (cycles the nine-colour palette), the chip, the meaning (saved on change),
  *Found from*, the household count (filter reading) linking to Songs, *By you*, and the *No version* row.
- Artist page: *86 songs · 41 live · 6 remixes · songs without Live and Remix →* under the songs line.
- CSS: `design/app/versions.css` is served as-is (added to both copy lists in `build.gradle.kts` and to
  `index.html`). **Two edits to the design file** the spec asked for: the chip is 11 px (item 17), and on the light
  theme its text is 45 % colour (item 16). The next design sync must keep them.

**Lyrics and the Dashboard (items 8–10, FR-292-15).**
- `MusicTrack` gained `lyricsSource` / `lyricsLrclibId` / `lyricsHash` (JSON, no migration), set when
  `fetch_lyrics` writes a sidecar; existing sidecars are backfilled on the next run (newer than `lyricsCheckedAt` ⇒
  LRCLIB, with the file's hash; older ⇒ *found*). `MusicLyrics.BLOCKED`; the lyrics step skips it, and skips any song
  whose answer `blocksLyrics` (summary: *N not looked up (no singing)*). Removing Instrumental clears the copies'
  `lyricsCheckedAt`, so the next run asks again.
- Ravilo (8d): `has_lyrics` is false and the lyrics route answers 404 for a blocked song, a no-words piece and any
  Instrumental — shipped with R344's server half.
- The row `music_instrumental_lyrics` (*Lyrics on an instrumental*), counted by 293's `MusicTriage` predicate
  (lyrics: our sidecar or Jellyfin's flag; no singing; not blocked), opens `filter=music_instrumental_lyrics` (no-words
  pieces included, open question 4) and marks them *no words* in the Songs list. **It amends FR-285-2's one-action
  rule for this row**: `DashboardRow` gained `action2` / `action2_id` (admin-only); *Remove the lyrics*
  (`POST /api/music/lyrics/remove-instrumental`) deletes only sidecars whose hash still matches what jellystructure
  wrote, keeps embedded lyrics and anyone else's files (hidden from viewers instead), marks every song `BLOCKED` and
  refreshes the albums in Jellyfin; the count goes to 0 at once.
- *Tell LRCLIB it is instrumental* (`POST /api/music/lyrics/tell-lrclib`, runs in the background, outcome in each
  album's History) is built from LRCLIB's documented publish flow: `request-challenge` → proof of work (SHA-256 of
  `prefix + nonce` ≤ `target`) → `publish` with `X-Publish-Token` and both lyrics fields empty. **Not verified against
  the live API**: verifying needs a write to a public database, which this build did not send. Every failure is one
  sentence in History. If LRCLIB answers differently, the fallback the spec names (open the song's LRCLIB page) is
  not built.

**Tests.** `MusicVersionsTest` (the function, the title finder, the Session rules, the album phrase),
`MusicVersionFactsTest` (a release answer shaped as musicbrainz.org sends it, renamed to stand-ins; the panel; a tick on
one copy reaching every copy; *Back to automatic*; *Set version…*; Only / Hide / *No version*; the Dashboard count ==
its list; the viewer's lyrics hidden; Metadata counts; colour validation), `MusicTriageTest` (293), `MusicTvServiceTest`
(R344), `MusicMatchTest` (the fake MusicBrainz now answers the new calls). `linuxX64Test` for `music.*` and
`audiobooks.*` green; `compileKotlinWasmJs` green; `verifyCommonMainJellystructureDbMigration` green.

**Seen in a browser (2026-10-01, headless Chromium, no device):** the debug backend on a scratch database seeded with
stand-in songs and facts, against the mock Jellyfin — the album's chips and header phrase, the panel (unticking Live on
a *radio session* song left *Acoustic · Alternate · Session*, Live *removed by you*), *Set version…*, Songs with the
Version facet and *Songs by Harbour Lights · without Live, Remix*, `filter=music_instrumental_lyrics` (2 songs, chip
*Issue: Lyrics on an instrumental*), Metadata → Versions, the artist doorway. The Dashboard row came back from
`/api/dashboard` with both actions; the page itself showed its *Nothing scanned yet* state (no films in that database),
so the row's two buttons were not seen drawn. *Remove the lyrics* through the route took the count to 0 and the row
away. The backend and the mock were stopped afterwards.

**Not verified:** a real MusicBrainz run on the household's library (the box-set answer's size, item 1's last line);
the LRCLIB publish; the pages against a real library. `MusicVersions.TYPES`' meanings are the spec's table; the
mockup's longer ones were not copied.

## Amended by 307 (2026-10-05)

FR-292-15's second action no longer publishes: it is *Queue for LRCLIB* and adds one item per song to 307's publish
queue. Nothing reaches LRCLIB until an admin presses **Publish** on the Dashboard's *Waiting to publish* panel
(`specs/requirements/phase-307-nothing-goes-to-a-public-database-until-someone-presses-publish.md`). OQ3, the
first live publish, is 307's acceptance 3.

## Triage (2026-10-09, against `main` `12bffb29`)

- **Code: nothing left.** Deployed with every dev-stack deploy since 2026-10-01.
- **Verified on the household's data (read-only, the live database):** `music_recording_facts` holds MusicBrainz
  facts for **9 169 recordings**, fetched 2026-10-01 → 2026-10-06 by `match_musicbrainz`'s catch-up; 456 carry
  `live: true`, about 150 a remix or remixer link, 44 an instrumental link. So the *real MusicBrainz run* this
  phase's build notes owed has happened. `music_version_choice` is empty: nobody has ticked a version by hand yet.
- **Owed:** the pages against the real library seen in a browser (album chips, Songs' Version facet, the Dashboard
  row's two buttons drawn); the LRCLIB publish is 307's acceptance 3 (the owner's **Publish** button).
